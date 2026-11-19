package com.example.data.gmail

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ResumeStorageManager(private val context: Context) {

    private val customResumeFile: File
        get() = File(context.filesDir, CUSTOM_RESUME_FILE)

    private val customResumeMetaFile: File
        get() = File(context.filesDir, CUSTOM_RESUME_META)

    suspend fun importPdfFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
                ?.takeIf { it.isNotBlank() }
                ?: "Resume.pdf"

            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IllegalStateException("Could not open selected PDF file."))

            if (bytes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Selected PDF file is empty."))
            }
            if (bytes.size > MAX_RESUME_BYTES) {
                return@withContext Result.failure(IllegalArgumentException("Resume PDF must be 10 MB or smaller for Gmail attachment."))
            }

            val safeName = if (displayName.endsWith(".pdf", ignoreCase = true)) displayName else "$displayName.pdf"
            customResumeFile.writeBytes(bytes)
            customResumeMetaFile.writeText(safeName)
            Result.success(safeName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetToBundledResume(): String = withContext(Dispatchers.IO) {
        if (customResumeFile.exists()) customResumeFile.delete()
        if (customResumeMetaFile.exists()) customResumeMetaFile.delete()
        BUNDLED_RESUME_NAME
    }

    suspend fun getActiveResumeBytes(): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        if (customResumeFile.exists() && customResumeFile.length() > 0L) {
            val name = customResumeMetaFile.takeIf { it.exists() }?.readText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: BUNDLED_RESUME_NAME
            name to customResumeFile.readBytes()
        } else {
            val bytes = context.assets.open(BUNDLED_RESUME_NAME).use { it.readBytes() }
            BUNDLED_RESUME_NAME to bytes
        }
    }

    suspend fun getShareableResumeUri(): Uri = withContext(Dispatchers.IO) {
        val (fileName, bytes) = getActiveResumeBytes()
        val cacheFile = File(context.cacheDir, fileName)
        cacheFile.writeBytes(bytes)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cacheFile
        )
    }

    fun getActiveResumeFileName(): String {
        return if (customResumeFile.exists() && customResumeMetaFile.exists()) {
            customResumeMetaFile.readText().trim().ifBlank { BUNDLED_RESUME_NAME }
        } else {
            BUNDLED_RESUME_NAME
        }
    }

    /**
     * Lightweight on-device PDF text extractor that inflates FlateDecode streams and reads
     * standard PDF text operators so uploaded resumes can be parsed instantaneously offline.
     */
    fun extractReadableTextFromPdfBytes(pdfBytes: ByteArray): String {
        val rawLatin = String(pdfBytes, Charsets.ISO_8859_1)
        val collected = StringBuilder()

        // 1. Decompress FlateDecode / compressed PDF streams
        val streamRegex = Regex("stream\\r?\\n([\\s\\S]*?)\\r?\\n?endstream")
        for (match in streamRegex.findAll(rawLatin)) {
            val streamBytes = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
            val decodedText = inflatePdfStream(streamBytes) ?: match.groupValues[1]
            extractPdfTextOperators(decodedText, collected)
        }

        // 2. Also scan top-level uncompressed PDF literals as fallback
        if (collected.length < 60) {
            extractPdfTextOperators(rawLatin, collected)
        }

        return collected.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(12_000)
    }

    private fun inflatePdfStream(compressed: ByteArray): String? {
        val offsets = intArrayOf(0, 1, 2)
        for (offset in offsets) {
            if (compressed.size <= offset) continue
            val attempt = runCatching {
                val inflater = java.util.zip.Inflater()
                try {
                    inflater.setInput(compressed, offset, compressed.size - offset)
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buf)
                        if (n == 0) {
                            if (inflater.needsInput() || inflater.needsDictionary()) break
                        } else {
                            out.write(buf, 0, n)
                        }
                        if (out.size() > 512 * 1024) break
                    }
                    out.toString(Charsets.ISO_8859_1.name())
                } finally {
                    inflater.end()
                }
            }.getOrNull()
            if (!attempt.isNullOrBlank() && (attempt.contains("Tj") || attempt.contains("TJ") || attempt.contains("BT"))) {
                return attempt
            }
        }
        return null
    }

    private fun extractPdfTextOperators(content: String, out: StringBuilder) {
        val literalRegex = Regex("\\(([^()\\\\]*(?:\\\\.[^()\\\\]*)*)\\)")
        for (m in literalRegex.findAll(content)) {
            val rawToken = m.groupValues[1]
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\\\", "\\")
            val printable = rawToken.filter { it.code in 32..126 || it == '\n' }.trim()
            if (printable.isNotBlank() && printable.any { it.isLetterOrDigit() }) {
                out.append(printable).append(' ')
            }
        }
    }

    companion object {
        const val BUNDLED_RESUME_NAME = "NareshSaini_Resume.pdf"
        private const val CUSTOM_RESUME_FILE = "custom_resume.pdf"
        private const val CUSTOM_RESUME_META = "custom_resume_name.txt"
        private const val MAX_RESUME_BYTES = 10 * 1024 * 1024
    }
}
