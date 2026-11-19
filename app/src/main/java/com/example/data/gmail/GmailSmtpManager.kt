package com.example.data.gmail

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.model.EmailMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class SmtpCredentials(
    val email: String,
    val appPassword: String
)

class GmailSmtpManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getCredentials(): SmtpCredentials? {
        val email = prefs.getString(KEY_EMAIL, null)?.trim().orEmpty()
        val pass = prefs.getString(KEY_APP_PASSWORD, null)?.trim().orEmpty()
        return if (email.isNotBlank() && pass.isNotBlank()) {
            SmtpCredentials(email, pass)
        } else {
            null
        }
    }

    fun isConfigured(): Boolean = getCredentials() != null

    fun saveCredentials(email: String, rawAppPassword: String) {
        val cleanPass = rawAppPassword.replace(" ", "").trim()
        prefs.edit()
            .putString(KEY_EMAIL, email.trim())
            .putString(KEY_APP_PASSWORD, cleanPass)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
    }

    suspend fun verifyCredentials(email: String, rawAppPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cleanEmail = email.trim()
            val cleanPass = rawAppPassword.replace(" ", "").trim()
            if (!cleanEmail.contains("@") || cleanPass.length < 8) {
                return@withContext Result.failure(
                    IllegalArgumentException("Enter your Gmail address and 16-character Google App Password.")
                )
            }
            try {
                openAuthenticatedSmtpSocket(cleanEmail, cleanPass) { _, writer ->
                    writer.write("QUIT\r\n")
                    writer.flush()
                }
                saveCredentials(cleanEmail, cleanPass)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "SMTP verification failed", e)
                Result.failure(e)
            }
        }

    suspend fun sendEmail(
        credentials: SmtpCredentials,
        toEmail: String,
        rawMimeContent: String,
        messageIdHeader: String
    ): Result<GmailSendResult> = withContext(Dispatchers.IO) {
        try {
            openAuthenticatedSmtpSocket(credentials.email, credentials.appPassword) { reader, writer ->
                sendCommand(reader, writer, "MAIL FROM:<${credentials.email}>", expectedCode = 250)
                sendCommand(reader, writer, "RCPT TO:<${toEmail.trim()}>", expectedCode = 250)
                sendCommand(reader, writer, "DATA", expectedCode = 354)

                val normalized = rawMimeContent
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .split("\n")
                    .joinToString("\r\n") { line ->
                        if (line.startsWith(".")) ".$line" else line
                    }

                writer.write(normalized)
                if (!normalized.endsWith("\r\n")) {
                    writer.write("\r\n")
                }
                writer.write(".\r\n")
                writer.flush()
                expectCode(reader, 250)

                runCatching {
                    writer.write("QUIT\r\n")
                    writer.flush()
                }
            }
            Result.success(
                GmailSendResult(
                    messageId = messageIdHeader,
                    threadId = messageIdHeader
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SMTP send failed", e)
            Result.failure(e)
        }
    }

    suspend fun checkReplyViaImap(
        credentials: SmtpCredentials,
        recruiterEmail: String,
        originalMessageId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var socket: SSLSocket? = null
        try {
            val session = connectImapAndLogin(credentials)
            socket = session.socket
            val input = session.input
            val writer = session.writer

            writer.write("A2 EXAMINE INBOX\r\n")
            writer.flush()
            readImapTaggedResponse(input, "A2")

            val query = if (originalMessageId.isNotBlank()) {
                "A3 SEARCH HEADER In-Reply-To \"$originalMessageId\"\r\n"
            } else {
                "A3 SEARCH FROM \"${recruiterEmail.trim()}\"\r\n"
            }
            writer.write(query)
            writer.flush()
            val searchResp = readImapTaggedResponse(input, "A3")

            runCatching {
                writer.write("A4 LOGOUT\r\n")
                writer.flush()
            }

            val searchLine = searchResp.lines()
                .firstOrNull { it.startsWith("* SEARCH", ignoreCase = true) }
                .orEmpty()
            val ids = searchLine.removePrefix("* SEARCH").trim()
            Result.success(ids.isNotBlank())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "IMAP reply check failed: ${e.message}")
            Result.failure(e)
        } finally {
            runCatching { socket?.close() }
        }
    }

    suspend fun fetchRecentEmailsViaImap(
        credentials: SmtpCredentials,
        mailbox: String = "INBOX",
        limit: Int = 20
    ): Result<List<EmailMessage>> = withContext(Dispatchers.IO) {
        var socket: SSLSocket? = null
        try {
            val session = connectImapAndLogin(credentials)
            socket = session.socket
            val input = session.input
            val writer = session.writer

            val folderPath = if (mailbox.equals("SENT", ignoreCase = true)) {
                resolveSpecialFolder(
                    input = input,
                    writer = writer,
                    tag = "A2",
                    attribute = "\\Sent",
                    fallbacks = listOf("[Gmail]/Sent Mail", "[Google Mail]/Sent Mail", "Sent")
                )
            } else {
                "INBOX"
            }

            writer.write("A3 EXAMINE ${quoteImapMailbox(folderPath)}\r\n")
            writer.flush()
            val examineResp = readImapTaggedResponse(input, "A3")
            if (!examineResp.contains("A3 OK", ignoreCase = true)) {
                runCatching {
                    writer.write("A9 LOGOUT\r\n")
                    writer.flush()
                }
                return@withContext Result.failure(
                    IllegalStateException("Could not open $folderPath on Gmail IMAP. Ensure IMAP is enabled in Gmail settings.")
                )
            }

            val existsCount = Regex("\\*\\s+(\\d+)\\s+EXISTS", RegexOption.IGNORE_CASE)
                .find(examineResp)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            if (existsCount <= 0) {
                runCatching {
                    writer.write("A9 LOGOUT\r\n")
                    writer.flush()
                }
                return@withContext Result.success(emptyList())
            }

            val startSeq = (existsCount - limit + 1).coerceAtLeast(1)

            // Single batch FETCH for all messages (1 round-trip instead of N sequential round-trips)
            writer.write(
                "A4 FETCH $startSeq:$existsCount (UID BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID)] BODY.PEEK[TEXT]<0.900>)\r\n"
            )
            writer.flush()
            val batchResp = readImapTaggedResponse(input, "A4")

            runCatching {
                writer.write("A9 LOGOUT\r\n")
                writer.flush()
            }

            val messages = parseImapBatchFetchResponse(
                batchResponse = batchResp,
                mailbox = mailbox.uppercase(),
                folderPath = folderPath
            )
            Result.success(messages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "IMAP fetch failed", e)
            Result.failure(e)
        } finally {
            runCatching { socket?.close() }
        }
    }

    suspend fun deleteEmailsViaImap(
        credentials: SmtpCredentials,
        mailbox: String,
        folderPathHint: String?,
        imapUids: List<String>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val cleanUids = imapUids
            .map { it.trim() }
            .filter { it.isNotBlank() && it.all(Char::isDigit) }
            .distinct()
        if (cleanUids.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Refresh Mailbox once to get message UIDs before deleting.")
            )
        }

        var socket: SSLSocket? = null
        try {
            val session = connectImapAndLogin(credentials)
            socket = session.socket
            val input = session.input
            val writer = session.writer

            val sourceFolder = when {
                !folderPathHint.isNullOrBlank() -> folderPathHint
                mailbox.equals("SENT", ignoreCase = true) -> resolveSpecialFolder(
                    input = input,
                    writer = writer,
                    tag = "A2",
                    attribute = "\\Sent",
                    fallbacks = listOf("[Gmail]/Sent Mail", "[Google Mail]/Sent Mail", "Sent")
                )
                else -> "INBOX"
            }

            val trashFolder = resolveSpecialFolder(
                input = input,
                writer = writer,
                tag = "A3",
                attribute = "\\Trash",
                fallbacks = listOf("[Gmail]/Trash", "[Gmail]/Bin", "[Google Mail]/Trash", "Trash")
            )

            writer.write("A4 SELECT ${quoteImapMailbox(sourceFolder)}\r\n")
            writer.flush()
            val selectResp = readImapTaggedResponse(input, "A4")
            if (!selectResp.contains("A4 OK", ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalStateException("Could not select $sourceFolder to delete emails.")
                )
            }

            val uidSet = cleanUids.joinToString(",")
            val quotedTrash = quoteImapMailbox(trashFolder)

            // Prefer atomic RFC 6851 UID MOVE; fallback to UID COPY + STORE \Deleted + EXPUNGE
            writer.write("A5 UID MOVE $uidSet $quotedTrash\r\n")
            writer.flush()
            val moveResp = readImapTaggedResponse(input, "A5")

            if (!moveResp.contains("A5 OK", ignoreCase = true)) {
                writer.write("A6 UID COPY $uidSet $quotedTrash\r\n")
                writer.flush()
                val copyResp = readImapTaggedResponse(input, "A6")
                if (!copyResp.contains("A6 OK", ignoreCase = true)) {
                    Log.w(TAG, "IMAP UID COPY to Trash returned: $copyResp")
                }

                writer.write("A7 UID STORE $uidSet +FLAGS.SILENT (\\Deleted)\r\n")
                writer.flush()
                val storeResp = readImapTaggedResponse(input, "A7")
                if (!storeResp.contains("A7 OK", ignoreCase = true)) {
                    return@withContext Result.failure(
                        IllegalStateException("Gmail rejected email deletion for this folder.")
                    )
                }

                writer.write("A8 EXPUNGE\r\n")
                writer.flush()
                readImapTaggedResponse(input, "A8")
            }

            runCatching {
                writer.write("A9 LOGOUT\r\n")
                writer.flush()
            }
            Result.success(cleanUids.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "IMAP delete failed", e)
            Result.failure(e)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private data class ImapSession(
        val socket: SSLSocket,
        val input: BufferedInputStream,
        val writer: BufferedWriter
    )

    private fun connectImapAndLogin(credentials: SmtpCredentials): ImapSession {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = (factory.createSocket() as SSLSocket).apply {
            soTimeout = 18_000
            connect(InetSocketAddress("imap.gmail.com", 993), 12_000)
            startHandshake()
        }
        val input = BufferedInputStream(socket.inputStream, 16_384)
        val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))

        readLineUtf8(input) // greeting

        val escapedPass = credentials.appPassword.replace("\\", "\\\\").replace("\"", "\\\"")
        writer.write("A1 LOGIN \"${credentials.email}\" \"$escapedPass\"\r\n")
        writer.flush()
        val loginResp = readImapTaggedResponse(input, "A1")
        if (!loginResp.contains("A1 OK", ignoreCase = true)) {
            socket.close()
            throw IllegalStateException("Gmail IMAP login failed. Check your Gmail App Password.")
        }
        return ImapSession(socket, input, writer)
    }

    private fun resolveSpecialFolder(
        input: BufferedInputStream,
        writer: BufferedWriter,
        tag: String,
        attribute: String,
        fallbacks: List<String>
    ): String {
        return runCatching {
            writer.write("$tag LIST \"\" \"*\"\r\n")
            writer.flush()
            val listResp = readImapTaggedResponse(input, tag)
            findSpecialUseFolder(listResp, attribute, fallbacks)
        }.getOrElse { fallbacks.first() }
    }

    internal fun findSpecialUseFolder(
        listResponse: String,
        attribute: String,
        fallbacks: List<String>
    ): String {
        val lines = listResponse.lines().filter { it.startsWith("* LIST", ignoreCase = true) }
        val attrMatch = lines.firstOrNull { line ->
            val attrs = line.substringAfter("(", "").substringBefore(")", "")
            attrs.contains(attribute, ignoreCase = true)
        }
        if (attrMatch != null) {
            extractMailboxNameFromListLine(attrMatch)?.let { return it }
        }

        for (candidate in fallbacks) {
            val found = lines.firstOrNull { line ->
                extractMailboxNameFromListLine(line)?.equals(candidate, ignoreCase = true) == true
            }
            if (found != null) {
                extractMailboxNameFromListLine(found)?.let { return it }
            }
        }
        return fallbacks.first()
    }

    private fun extractMailboxNameFromListLine(line: String): String? {
        val afterCloseParen = line.substringAfter(")", "").trim()
        if (afterCloseParen.isBlank()) return null
        val quotedRegex = Regex("\"([^\"]+)\"\\s*$")
        quotedRegex.find(afterCloseParen)?.groupValues?.getOrNull(1)?.let { return it }
        return afterCloseParen.split(Regex("\\s+")).lastOrNull()?.trim('"')?.takeIf { it.isNotBlank() }
    }

    private fun quoteImapMailbox(folder: String): String {
        val trimmed = folder.trim().trim('"')
        return if (trimmed.equals("INBOX", ignoreCase = true)) {
            "INBOX"
        } else {
            "\"${trimmed.replace("\"", "\\\"")}\""
        }
    }

    internal fun readImapTaggedResponse(input: BufferedInputStream, tag: String): String {
        val sb = StringBuilder()
        val literalRegex = Regex("\\{(\\d+)\\}\\s*$")
        while (true) {
            val line = readLineUtf8(input) ?: break
            sb.append(line).append('\n')

            val literalBytes = literalRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (literalBytes != null && literalBytes > 0) {
                val capped = literalBytes.coerceAtMost(256 * 1024)
                val raw = readExactBytes(input, capped)
                sb.append(String(raw, Charsets.UTF_8)).append('\n')
                if (literalBytes > capped) {
                    skipExactBytes(input, (literalBytes - capped).toLong())
                }
                continue
            }

            if (line.startsWith("$tag ", ignoreCase = true)) {
                break
            }
        }
        return sb.toString()
    }

    private fun readLineUtf8(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream(128)
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            b = input.read()
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun readExactBytes(input: BufferedInputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == count) buf else buf.copyOf(offset)
    }

    private fun skipExactBytes(input: BufferedInputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    internal fun parseImapBatchFetchResponse(
        batchResponse: String,
        mailbox: String,
        folderPath: String
    ): List<EmailMessage> {
        val lines = batchResponse.lines()
        val itemChunks = mutableListOf<Pair<String, List<String>>>()
        val fetchStartRegex = Regex("^\\*\\s+(\\d+)\\s+FETCH\\s+\\((.*)$", RegexOption.IGNORE_CASE)

        var currentSeq = ""
        var currentLines = mutableListOf<String>()

        for (line in lines) {
            val m = fetchStartRegex.find(line)
            if (m != null) {
                if (currentSeq.isNotBlank()) {
                    itemChunks += currentSeq to currentLines
                }
                currentSeq = m.groupValues[1]
                currentLines = mutableListOf(line)
            } else if (currentSeq.isNotBlank()) {
                if (line.startsWith("A4 OK", ignoreCase = true) ||
                    line.startsWith("A4 NO", ignoreCase = true) ||
                    line.startsWith("A4 BAD", ignoreCase = true)
                ) {
                    break
                }
                currentLines += line
            }
        }
        if (currentSeq.isNotBlank()) {
            itemChunks += currentSeq to currentLines
        }

        // Reverse so newest messages appear first
        return itemChunks.asReversed().mapNotNull { (seq, blockLines) ->
            parseImapFetchBlock(
                lines = blockLines,
                seq = seq,
                folder = mailbox.uppercase(),
                folderPath = folderPath
            )
        }
    }

    private fun parseImapFetchBlock(
        lines: List<String>,
        seq: String,
        folder: String,
        folderPath: String
    ): EmailMessage? {
        val headerLine = lines.firstOrNull().orEmpty()
        val uidMatch = Regex("\\bUID\\s+(\\d+)\\b", RegexOption.IGNORE_CASE).find(headerLine)
            ?: lines.lastOrNull()?.let { Regex("\\bUID\\s+(\\d+)\\b", RegexOption.IGNORE_CASE).find(it) }
        val realImapUid = uidMatch?.groupValues?.getOrNull(1).orEmpty()
        val stableId = if (realImapUid.isNotBlank()) "$folder:$realImapUid" else "$folder:seq_$seq"

        var from = ""
        var to = ""
        var subject = ""
        var date = ""
        var messageId = ""
        val snippetLines = mutableListOf<String>()
        val base64Lines = mutableListOf<String>()
        var inBody = false
        var sawBase64Encoding = false
        var lastHeader = ""

        for (idx in 1 until lines.size) {
            val line = lines[idx]
            if (!inBody) {
                val lower = line.lowercase()
                when {
                    lower.startsWith("from:") -> {
                        from = line.substringAfter(":").trim()
                        lastHeader = "from"
                    }
                    lower.startsWith("to:") -> {
                        to = line.substringAfter(":").trim()
                        lastHeader = "to"
                    }
                    lower.startsWith("subject:") -> {
                        subject = decodeMimeWords(line.substringAfter(":").trim())
                        lastHeader = "subject"
                    }
                    lower.startsWith("date:") -> {
                        date = line.substringAfter(":").trim().take(22)
                        lastHeader = "date"
                    }
                    lower.startsWith("message-id:") -> {
                        messageId = line.substringAfter(":").trim()
                        lastHeader = "msgid"
                    }
                    line.startsWith(" ") || line.startsWith("\t") -> {
                        when (lastHeader) {
                            "subject" -> subject = (subject + " " + decodeMimeWords(line.trim())).trim()
                            "from" -> from = (from + " " + line.trim()).trim()
                            "to" -> to = (to + " " + line.trim()).trim()
                        }
                    }
                    line.isBlank() || line.contains("BODY[TEXT]", ignoreCase = true) -> {
                        inBody = true
                    }
                }
            } else {
                val trimmed = line.trim()
                if (trimmed.equals("Content-Transfer-Encoding: base64", ignoreCase = true)) {
                    sawBase64Encoding = true
                    continue
                }
                if (trimmed == ")" || trimmed.startsWith("BODY[") || trimmed.startsWith("--") ||
                    trimmed.startsWith("Content-", ignoreCase = true) ||
                    trimmed.startsWith("UID ", ignoreCase = true)
                ) {
                    if (trimmed.startsWith("--") && base64Lines.isNotEmpty()) {
                        // Finished first MIME part
                        sawBase64Encoding = false
                    }
                    continue
                }
                if (sawBase64Encoding && trimmed.matches(Regex("^[A-Za-z0-9+/=]{12,}$"))) {
                    base64Lines += trimmed
                    continue
                }
                val cleaned = trimmed
                    .replace(Regex("<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("=20", " ")
                    .trim()
                if (cleaned.isNotBlank() && !cleaned.matches(Regex("^[A-Za-z0-9+/=]{36,}$"))) {
                    snippetLines += cleaned
                }
            }
        }

        if (subject.isBlank() && from.isBlank() && to.isBlank()) return null

        val decodedBase64Snippet = if (base64Lines.isNotEmpty() && snippetLines.isEmpty()) {
            val joined = base64Lines.joinToString("")
            val validLen = joined.length - (joined.length % 4)
            if (validLen >= 8) {
                runCatching {
                    val bytes = Base64.decode(joined.substring(0, validLen), Base64.DEFAULT)
                    String(bytes, Charsets.UTF_8)
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }.getOrDefault("")
            } else ""
        } else ""

        val finalSnippet = when {
            snippetLines.isNotEmpty() -> snippetLines.joinToString(" ").replace(Regex("\\s+"), " ").take(240)
            decodedBase64Snippet.isNotBlank() -> decodedBase64Snippet.take(240)
            else -> "(Open in Gmail for attachment/HTML content)"
        }

        return EmailMessage(
            uid = stableId,
            folder = folder,
            from = decodeMimeWords(from).ifBlank { "Unknown" },
            to = decodeMimeWords(to),
            subject = subject.ifBlank { "(No Subject)" },
            date = date,
            snippet = finalSnippet,
            messageId = messageId,
            imapUid = realImapUid,
            folderPath = folderPath
        )
    }

    private fun decodeMimeWords(input: String): String {
        if (!input.contains("=?")) return input
        return Regex("=\\?[^?]+\\?[Bb]\\?([^?]+)\\?=").replace(input) { match ->
            runCatching {
                String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault(match.value)
        }
    }

    private fun openAuthenticatedSmtpSocket(
        email: String,
        appPassword: String,
        block: (BufferedReader, BufferedWriter) -> Unit
    ) {
        var socket: SSLSocket? = null
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            socket = (factory.createSocket() as SSLSocket).apply {
                soTimeout = 25_000
                connect(InetSocketAddress("smtp.gmail.com", 465), 12_000)
                startHandshake()
            }
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))

            expectCode(reader, 220)
            sendCommand(reader, writer, "EHLO applyflow.local", 250)

            val authToken = Base64.encodeToString(
                "\u0000$email\u0000$appPassword".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            sendCommand(reader, writer, "AUTH PLAIN $authToken", 235)
            block(reader, writer)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun sendCommand(
        reader: BufferedReader,
        writer: BufferedWriter,
        command: String,
        expectedCode: Int
    ) {
        writer.write("$command\r\n")
        writer.flush()
        expectCode(reader, expectedCode)
    }

    private fun expectCode(reader: BufferedReader, expectedCode: Int): String {
        val sb = StringBuilder()
        var lastLine: String?
        while (true) {
            val line = reader.readLine()
                ?: throw IllegalStateException("SMTP server closed connection unexpectedly.")
            sb.appendLine(line)
            lastLine = line
            if (line.length < 4 || line[3] == ' ') {
                break
            }
        }
        val code = lastLine.take(3).toIntOrNull()
        if (code != expectedCode) {
            val friendly = when (code) {
                535 -> "Gmail rejected the App Password. Create a 16-digit App Password in Google Account Security and try again."
                534 -> "Google requires an App Password (enable 2-Step Verification and generate an App Password)."
                else -> "SMTP error ($code): $lastLine"
            }
            throw IllegalStateException(friendly)
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "GmailSmtpManager"
        private const val PREFS_NAME = "applyflow_gmail_smtp_prefs"
        private const val KEY_EMAIL = "smtp_email"
        private const val KEY_APP_PASSWORD = "smtp_app_password"

        fun generateMessageId(): String =
            "<${UUID.randomUUID()}@applyflow.app>"
    }
}
