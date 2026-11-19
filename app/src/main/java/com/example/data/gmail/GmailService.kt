package com.example.data.gmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.model.EmailMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GmailSendResult(
    val messageId: String,
    val threadId: String
)

class GmailService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()
) {

    fun buildRawMimeString(
        toEmail: String,
        subject: String,
        body: String,
        fromEmail: String? = null,
        attachmentFileName: String? = null,
        attachmentBytes: ByteArray? = null,
        messageIdHeader: String? = null,
        inReplyToHeader: String? = null,
        htmlBody: String? = null
    ): String {
        val mixedBoundary = "applyflow_mix_${UUID.randomUUID().toString().replace("-", "")}"
        val altBoundary = "applyflow_alt_${UUID.randomUUID().toString().replace("-", "")}"
        val sb = StringBuilder()

        if (!fromEmail.isNullOrBlank()) {
            sb.append("From: ").append(sanitizeHeader(fromEmail)).append("\r\n")
        }
        sb.append("To: ").append(sanitizeHeader(toEmail)).append("\r\n")
        sb.append("Subject: ").append(encodeMimeSubject(sanitizeHeader(subject))).append("\r\n")
        if (!messageIdHeader.isNullOrBlank()) {
            sb.append("Message-ID: ").append(sanitizeHeader(messageIdHeader)).append("\r\n")
        }
        if (!inReplyToHeader.isNullOrBlank()) {
            val cleanRef = sanitizeHeader(inReplyToHeader)
            sb.append("In-Reply-To: ").append(cleanRef).append("\r\n")
            sb.append("References: ").append(cleanRef).append("\r\n")
        }
        sb.append("MIME-Version: 1.0\r\n")

        val hasAttachment = attachmentBytes != null && attachmentBytes.isNotEmpty() && !attachmentFileName.isNullOrBlank()
        val hasHtml = !htmlBody.isNullOrBlank()

        fun appendAlternativeBody(targetBoundary: String?) {
            if (hasHtml) {
                if (targetBoundary != null) {
                    sb.append("--").append(targetBoundary).append("\r\n")
                }
                sb.append("Content-Type: multipart/alternative; boundary=\"").append(altBoundary).append("\"\r\n\r\n")

                sb.append("--").append(altBoundary).append("\r\n")
                sb.append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
                sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
                sb.append(Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.DEFAULT))
                sb.append("\r\n")

                sb.append("--").append(altBoundary).append("\r\n")
                sb.append("Content-Type: text/html; charset=\"UTF-8\"\r\n")
                sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
                sb.append(Base64.encodeToString(htmlBody!!.toByteArray(Charsets.UTF_8), Base64.DEFAULT))
                sb.append("\r\n--").append(altBoundary).append("--\r\n")
            } else {
                if (targetBoundary != null) {
                    sb.append("--").append(targetBoundary).append("\r\n")
                }
                sb.append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
                sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
                sb.append(Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.DEFAULT))
                sb.append("\r\n")
            }
        }

        if (hasAttachment) {
            val safeFileName = sanitizeHeader(attachmentFileName!!).replace("\"", "")
            sb.append("Content-Type: multipart/mixed; boundary=\"").append(mixedBoundary).append("\"\r\n\r\n")

            appendAlternativeBody(mixedBoundary)

            sb.append("--").append(mixedBoundary).append("\r\n")
            sb.append("Content-Type: application/pdf; name=\"").append(safeFileName).append("\"\r\n")
            sb.append("Content-Disposition: attachment; filename=\"").append(safeFileName).append("\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n\r\n")
            sb.append(Base64.encodeToString(attachmentBytes, Base64.DEFAULT))
            sb.append("\r\n--").append(mixedBoundary).append("--\r\n")
        } else {
            appendAlternativeBody(null)
        }

        return sb.toString()
    }

    fun buildMimeMessageBase64Url(
        toEmail: String,
        subject: String,
        body: String,
        fromEmail: String? = null,
        attachmentFileName: String? = null,
        attachmentBytes: ByteArray? = null,
        inReplyToHeader: String? = null,
        htmlBody: String? = null
    ): String {
        val rawString = buildRawMimeString(
            toEmail = toEmail,
            subject = subject,
            body = body,
            fromEmail = fromEmail,
            attachmentFileName = attachmentFileName,
            attachmentBytes = attachmentBytes,
            inReplyToHeader = inReplyToHeader,
            htmlBody = htmlBody
        )
        val rawBytes = rawString.toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(rawBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    suspend fun sendEmail(
        accessToken: String,
        rawBase64Url: String,
        threadId: String? = null
    ): Result<GmailSendResult> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("raw", rawBase64Url)
                if (!threadId.isNullOrBlank()) {
                    put("threadId", threadId)
                }
            }
            val request = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorMsg = parseGmailApiError(response.code, responseBody)
                    Log.e(TAG, "Gmail send failed (${response.code}): $errorMsg")
                    return@withContext Result.failure(IllegalStateException(errorMsg))
                }
                val json = JSONObject(responseBody)
                val msgId = json.optString("id", "")
                val resThreadId = json.optString("threadId", threadId.orEmpty())
                Result.success(GmailSendResult(messageId = msgId, threadId = resThreadId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gmail send exception", e)
            Result.failure(e)
        }
    }

    suspend fun fetchRfcMessageId(
        accessToken: String,
        gmailMessageId: String
    ): String? = withContext(Dispatchers.IO) {
        if (gmailMessageId.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/$gmailMessageId?format=metadata&metadataHeaders=Message-ID")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string().orEmpty())
                val headers = json.optJSONObject("payload")?.optJSONArray("headers") ?: return@withContext null
                for (i in 0 until headers.length()) {
                    val header = headers.optJSONObject(i) ?: continue
                    if (header.optString("name").equals("Message-ID", ignoreCase = true)) {
                        return@withContext header.optString("value").takeIf { it.isNotBlank() }
                    }
                }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun checkThreadHasReply(
        accessToken: String,
        threadId: String,
        myEmail: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (threadId.isBlank()) return@withContext Result.success(false)
        try {
            val request = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/threads/$threadId?format=metadata&metadataHeaders=From")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException(parseGmailApiError(response.code, body))
                    )
                }
                val json = JSONObject(body)
                val messages = json.optJSONArray("messages") ?: return@withContext Result.success(false)
                val normalizedMine = myEmail?.trim()?.lowercase().orEmpty()
                for (i in 0 until messages.length()) {
                    val msg = messages.optJSONObject(i) ?: continue
                    val headers = msg.optJSONObject("payload")?.optJSONArray("headers") ?: continue
                    for (j in 0 until headers.length()) {
                        val header = headers.optJSONObject(j) ?: continue
                        if (header.optString("name").equals("From", ignoreCase = true)) {
                            val fromValue = header.optString("value").lowercase()
                            if (normalizedMine.isNotBlank() && !fromValue.contains(normalizedMine)) {
                                return@withContext Result.success(true)
                            }
                        }
                    }
                }
                Result.success(false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchRecentMessages(
        accessToken: String,
        mailbox: String = "INBOX",
        maxResults: Int = 25
    ): Result<List<EmailMessage>> = withContext(Dispatchers.IO) {
        try {
            val labelId = if (mailbox.equals("SENT", ignoreCase = true)) "SENT" else "INBOX"
            val listReq = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages?labelIds=$labelId&maxResults=$maxResults")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val messageIds = client.newCall(listReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException(parseGmailApiError(resp.code, body)))
                }
                val arr = JSONObject(body).optJSONArray("messages") ?: return@withContext Result.success(emptyList())
                (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.optString("id") }
            }

            val items = mutableListOf<EmailMessage>()
            for (id in messageIds) {
                val msgReq = Request.Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                client.newCall(msgReq).execute().use { msgResp ->
                    if (!msgResp.isSuccessful) return@use
                    val json = JSONObject(msgResp.body?.string().orEmpty())
                    val snippet = json.optString("snippet", "")
                    val payload = json.optJSONObject("payload")
                    val headers = payload?.optJSONArray("headers")
                    var from = ""
                    var to = ""
                    var subject = ""
                    var date = ""
                    var msgId = ""
                    if (headers != null) {
                        for (j in 0 until headers.length()) {
                            val h = headers.optJSONObject(j) ?: continue
                            when (h.optString("name").lowercase()) {
                                "from" -> from = h.optString("value")
                                "to" -> to = h.optString("value")
                                "subject" -> subject = h.optString("value")
                                "date" -> date = h.optString("value").take(22)
                                "message-id" -> msgId = h.optString("value")
                            }
                        }
                    }
                    val extractedBody = payload?.let { extractTextFromPayload(it) }
                        ?.take(3500)
                        .orEmpty()
                    items += EmailMessage(
                        uid = id,
                        folder = mailbox.uppercase(),
                        from = from.ifBlank { "Unknown" },
                        to = to,
                        subject = subject.ifBlank { "(No Subject)" },
                        date = date,
                        snippet = snippet.ifBlank { extractedBody.replace(Regex("\\s+"), " ").take(240) },
                        messageId = msgId,
                        fullBody = extractedBody.ifBlank { snippet }
                    )
                }
            }
            Result.success(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractTextFromPayload(payload: JSONObject): String {
        val plainResults = mutableListOf<String>()
        val htmlResults = mutableListOf<String>()

        fun walk(node: JSONObject) {
            val mime = node.optString("mimeType").lowercase()
            val bodyData = node.optJSONObject("body")?.optString("data").orEmpty()
            if (bodyData.isNotBlank()) {
                val decoded = runCatching {
                    String(Base64.decode(bodyData, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrDefault("")
                if (decoded.isNotBlank()) {
                    when {
                        mime == "text/plain" -> plainResults += decoded
                        mime == "text/html" -> htmlResults += decoded
                    }
                }
            }
            val parts = node.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    parts.optJSONObject(i)?.let { walk(it) }
                }
            }
        }

        walk(payload)
        if (plainResults.isNotEmpty()) {
            return plainResults.joinToString("\n\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        }
        if (htmlResults.isNotEmpty()) {
            return htmlResults.joinToString("\n")
                .replace(Regex("(?is)<style[^>]*>[\\s\\S]*?</style>"), " ")
                .replace(Regex("(?is)<script[^>]*>[\\s\\S]*?</script>"), " ")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</(p|div|tr|li|h[1-6])>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }
        return ""
    }

    suspend fun trashMessages(
        accessToken: String,
        messageIds: List<String>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val cleanIds = messageIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return@withContext Result.success(0)

        var deletedCount = 0
        try {
            val emptyBody = "".toRequestBody(JSON_MEDIA_TYPE)
            for (id in cleanIds) {
                val req = Request.Builder()
                    .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id/trash")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(emptyBody)
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = if (resp.code == 403) {
                            "Gmail OAuth token lacks delete permission (gmail.modify). Connect via Gmail App Password for full Inbox/Sent delete control."
                        } else {
                            parseGmailApiError(resp.code, body)
                        }
                        return@withContext Result.failure(IllegalStateException(msg))
                    }
                    deletedCount++
                }
            }
            Result.success(deletedCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildComposeFallbackIntent(
        context: Context,
        toEmail: String,
        subject: String,
        body: String,
        attachmentUri: Uri?
    ): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (attachmentUri != null) "application/pdf" else "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(toEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (attachmentUri != null) {
                putExtra(Intent.EXTRA_STREAM, attachmentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val gmailCheck = Intent(intent).apply { setPackage("com.google.android.gm") }
        return if (gmailCheck.resolveActivity(context.packageManager) != null) {
            gmailCheck
        } else {
            Intent.createChooser(intent, "Send Application Email")
        }
    }

    private fun parseGmailApiError(statusCode: Int, responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val errObj = json.optJSONObject("error")
            val message = errObj?.optString("message").orEmpty()
            when {
                statusCode == 401 -> "Gmail token expired. Tap Connect Gmail to re-authorize."
                statusCode == 403 && message.contains("has not been used", ignoreCase = true) ->
                    "Gmail API is not enabled on the project yet. Use Open in Gmail App or enable Gmail API in Google Cloud."
                message.isNotBlank() -> "Gmail API ($statusCode): $message"
                else -> "Gmail API request failed (HTTP $statusCode)."
            }
        } catch (_: Exception) {
            "Gmail API request failed (HTTP $statusCode)."
        }
    }

    private fun sanitizeHeader(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim()

    private fun encodeMimeSubject(subject: String): String {
        val isAscii = subject.all { it.code in 32..126 }
        return if (isAscii) {
            subject
        } else {
            val encoded = Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "=?UTF-8?B?$encoded?="
        }
    }

    companion object {
        private const val TAG = "GmailService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
