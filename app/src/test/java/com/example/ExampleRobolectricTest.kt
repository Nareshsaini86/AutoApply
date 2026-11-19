package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.JobAiRepository
import com.example.data.gmail.EmailTemplateFormatter
import com.example.data.gmail.GmailService
import com.example.data.gmail.GmailSmtpManager
import com.example.data.model.CandidateProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches ApplyFlow`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ApplyFlow", appName)
    }

    @Test
    fun `default profile contains bundled resume and Naresh Saini details`() {
        val profile = CandidateProfile.defaultForUser("uid_1", null, null)
        assertEquals("Naresh Saini", profile.fullName)
        assertEquals("NareshSaini_Resume.pdf", profile.resumeFileName)
        assertTrue(profile.skillsSummary.contains("ASP.NET Core"))
    }

    @Test
    fun `gmail mime builder encodes pdf attachment and headers`() {
        val service = GmailService()
        val encoded = service.buildMimeMessageBase64Url(
            toEmail = "priya.gupta@webmobril.com",
            subject = "Application for .NET Developer",
            body = "Hi Priya, please find my CV attached.",
            fromEmail = "nareshsaini868588@gmail.com",
            attachmentFileName = "NareshSaini_Resume.pdf",
            attachmentBytes = "%PDF-1.4 test".toByteArray()
        )
        assertTrue(encoded.isNotBlank())
        assertTrue(!encoded.contains("+") && !encoded.contains("/") && !encoded.contains("="))
    }

    @Test
    fun `raw mime builder includes Message-ID and In-Reply-To for SMTP threading`() {
        val service = GmailService()
        val msgId = GmailSmtpManager.generateMessageId()
        val rawMime = service.buildRawMimeString(
            toEmail = "priya.gupta@webmobril.com",
            subject = "Re: Application for .NET Developer",
            body = "Following up politely.",
            fromEmail = "nareshsaini868588@gmail.com",
            attachmentFileName = "NareshSaini_Resume.pdf",
            attachmentBytes = "%PDF-1.4 test".toByteArray(),
            messageIdHeader = msgId,
            inReplyToHeader = "<orig-123@applyflow.app>"
        )
        assertTrue(rawMime.contains("Message-ID: $msgId"))
        assertTrue(rawMime.contains("In-Reply-To: <orig-123@applyflow.app>"))
        assertTrue(rawMime.contains("References: <orig-123@applyflow.app>"))
    }

    @Test
    fun `instant extractor handles WebMobril Q&A LinkedIn snippet`() {
        val aiRepo = JobAiRepository()
        val profile = CandidateProfile.defaultForUser("uid_1", "nareshsaini868588@gmail.com", "Naresh Saini")
            .copy(
                currentCtc = "12 LPA",
                expectedCtc = "16 LPA",
                noticePeriod = "30 Days",
                currentLocation = "Hodal, Haryana"
            )
        val snippet = """
            updated CV at:
            📩 priya.gupta@webmobril.com
            Please mention the following details
            while applying:
            Total Experience
            Current CTC
            Expected CTC
            Notice Period
            Current Location
        """.trimIndent()

        val draft = aiRepo.extractAndFormatLocally(snippet, profile)
        assertEquals("priya.gupta@webmobril.com", draft.recruiterEmail)
        assertEquals("Webmobril", draft.companyName)
        assertEquals("Priya Gupta", draft.recruiterName)
        assertTrue(draft.requestedDetails.containsAll(listOf("Total Experience", "Current CTC", "Expected CTC", "Notice Period", "Current Location")))
        assertTrue(draft.emailBody.contains("12 LPA"))
        assertTrue(draft.emailBody.contains("16 LPA"))
        assertTrue(draft.emailBody.contains("30 Days"))
    }

    @Test
    fun `instant extractor handles Quokkalabs hashtag post with wrapped email domain`() {
        val aiRepo = JobAiRepository()
        val profile = CandidateProfile.defaultForUser("uid_1", "nareshsaini868588@gmail.com", "Naresh Saini")
        val post = """
            ✅ Looking for Immediate Joiner
            only/ or serving Np
            Interested candidate may share their
            resume at
            subhika.chatterjee@quokkalabs
            .com
            #dotnet #c##MVC
            #entityframework #dotnetcore
            #Immediatejoining##noida#openf
        """.trimIndent()

        val draft = aiRepo.extractAndFormatLocally(post, profile)
        assertEquals("subhika.chatterjee@quokkalabs.com", draft.recruiterEmail)
        assertEquals("Quokkalabs", draft.companyName)
        assertEquals("Subhika Chatterjee", draft.recruiterName)
        assertEquals(".NET Developer", draft.jobTitle)
        assertEquals("Noida", draft.location)
        assertTrue(draft.emailBody.contains("Dear Subhika"))
        assertTrue(draft.emailBody.contains("NareshSaini_Resume.pdf"))
        assertTrue(draft.emailBody.contains("Notice Period / Availability"))
    }

    @Test
    fun `formatter fixes wall-of-text LLM body and generates executive HTML without ASP_NET auto-links`() {
        val collapsed = "Dear Aman, I am writing to express my interest in the Sr. Software Developer role using ASP.NET Core. Please find below my details as requested: - Total Experience: 5+ Years - Current CTC: 9 LPA - Expected CTC: 11 LPA - Notice Period: Immediate - Current Location: Hodal, India I have attached my resume (NareshSaini_Resume.pdf) for your review. Best regards, Naresh Saini +91 8685887957"
        val normalized = EmailTemplateFormatter.normalizePlainText(collapsed)
        assertTrue(normalized.contains("Dear Aman,\n\nI am writing"))
        assertTrue(normalized.contains("\n- Total Experience: 5+ Years\n- Current CTC: 9 LPA"))
        assertTrue(normalized.contains("\n\nBest regards,\nNaresh Saini\n+91 8685887957"))

        val profile = CandidateProfile.defaultForUser("uid_1", "nareshsaini868588@gmail.com", "Naresh Saini")
        val html = EmailTemplateFormatter.toStyledHtml(
            subject = "Application for .NET Full Stack",
            plainBody = collapsed,
            profile = profile,
            attachmentFileName = "NareshSaini_Resume.pdf",
            roleBadge = "Sr. Software Developer (.NET Full Stack)"
        )
        assertTrue(html.contains("<table"))
        assertTrue(html.contains("Total Experience"))
        assertTrue(html.contains("9 LPA"))
        assertTrue(html.contains("ASP.&#8203;NET"))

        val service = GmailService()
        val rawMime = service.buildRawMimeString(
            toEmail = "amanj@selectsource.com",
            subject = "Application for .NET Full Stack",
            body = normalized,
            fromEmail = "nareshsaini868588@gmail.com",
            attachmentFileName = "NareshSaini_Resume.pdf",
            attachmentBytes = "%PDF-1.4 test".toByteArray(),
            messageIdHeader = GmailSmtpManager.generateMessageId(),
            htmlBody = html
        )
        assertTrue(rawMime.contains("multipart/mixed"))
        assertTrue(rawMime.contains("multipart/alternative"))
        assertTrue(rawMime.contains("Content-Type: text/html; charset=\"UTF-8\""))
    }

    @Test
    fun `imap discovers special Sent and Trash folders from LIST response`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val smtp = GmailSmtpManager(context)
        val listResp = """
            * LIST (\HasNoChildren) "/" "INBOX"
            * LIST (\HasChildren \Noselect) "/" "[Google Mail]"
            * LIST (\HasNoChildren \Sent) "/" "[Google Mail]/Sent Mail"
            * LIST (\HasNoChildren \Trash) "/" "[Google Mail]/Bin"
            A2 OK Success
        """.trimIndent()

        val sent = smtp.findSpecialUseFolder(listResp, "\\Sent", listOf("[Gmail]/Sent Mail"))
        val trash = smtp.findSpecialUseFolder(listResp, "\\Trash", listOf("[Gmail]/Trash"))
        assertEquals("[Google Mail]/Sent Mail", sent)
        assertEquals("[Google Mail]/Bin", trash)
    }

    @Test
    fun `imap batch fetch parses Sent mail newest-first with real UID and Base64 snippet`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val smtp = GmailSmtpManager(context)
        val encodedBody = android.util.Base64.encodeToString(
            "Dear Aman, Please find attached my CV for the .NET role.".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val batchResp = """
            * 10 FETCH (UID 401 BODY[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID)] {95}
            From: naresh@gmail.com
            To: hr@older.com
            Subject: Older App
            Date: Mon, 18 Nov 2026

             BODY[TEXT]<0> {24}
            Hello from older email
            )
            * 11 FETCH (UID 409 BODY[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID)] {105}
            From: naresh@gmail.com
            To: amanj@selectsource.com
            Subject: Application for .NET Role
            Date: Tue, 19 Nov 2026

             BODY[TEXT]<0> {120}
            --mix123
            Content-Type: text/plain; charset="UTF-8"
            Content-Transfer-Encoding: base64

            $encodedBody
            )
            A4 OK Success
        """.trimIndent()

        val parsed = smtp.parseImapBatchFetchResponse(
            batchResponse = batchResp,
            mailbox = "SENT",
            folderPath = "[Gmail]/Sent Mail"
        )
        assertEquals(2, parsed.size)
        // Newest message (seq 11) must appear first
        assertEquals("SENT:409", parsed[0].uid)
        assertEquals("409", parsed[0].imapUid)
        assertEquals("[Gmail]/Sent Mail", parsed[0].folderPath)
        assertEquals("amanj@selectsource.com", parsed[0].to)
        assertTrue(parsed[0].snippet.contains("Dear Aman, Please find attached my CV"))
        assertEquals("SENT:401", parsed[1].uid)
        assertEquals("401", parsed[1].imapUid)
    }
}
