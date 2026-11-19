package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.JobAiRepository
import com.example.data.gmail.EmailTemplateFormatter
import com.example.data.gmail.GmailService
import com.example.data.gmail.GmailSmtpManager
import com.example.data.model.CandidateProfile
import com.example.data.model.EmailMessage
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
    fun `app name matches 1Click Apply`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("1Click Apply", appName)
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

    @Test
    fun `resume reader adapts to any uploaded career domain and LinkedIn query follows`() {
        val aiRepo = JobAiRepository()
        val base = CandidateProfile.defaultForUser("uid_2", "priya@gmail.com", "Priya Verma")

        val marketingInsights = aiRepo.extractResumeInsightsLocally(
            fileName = "Priya_Marketing_CV.pdf",
            extractedPdfText = "Priya Verma Digital Marketing Specialist 4+ years experience in SEO Social Media campaigns +91 9876543210",
            fallbackProfile = base
        )
        assertEquals("Digital Marketing Specialist", marketingInsights.targetRole)
        assertEquals("Digital Marketing Specialist", marketingInsights.preferredSearchQuery)
        assertEquals("4+ Years", marketingInsights.totalExperience)
        assertTrue(marketingInsights.skillsSummary.contains("SEO"))

        val updatedProfile = base.copy(
            currentRole = marketingInsights.targetRole,
            preferredSearchQuery = marketingInsights.preferredSearchQuery
        )
        assertEquals(
            "Digital Marketing Specialist",
            com.example.ui.screens.deriveLinkedInRoleQuery(updatedProfile)
        )

        val javaInsights = aiRepo.extractResumeInsightsLocally(
            fileName = "Rahul_Java_Resume.pdf",
            extractedPdfText = "Senior Java Developer with 3 years experience in Spring Boot Microservices Docker SQL",
            fallbackProfile = base
        )
        assertEquals("Java Backend Developer", javaInsights.targetRole)
        assertEquals("Java Backend Developer", javaInsights.preferredSearchQuery)
    }

    @Test
    fun `imap batch fetch handles BODY TEXT before HEADER with Quoted-Printable and populates fullBody`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val smtp = GmailSmtpManager(context)
        val batchResp = """
            * 25 FETCH (UID 812 BODY[TEXT]<0> {140}
            Content-Type: text/plain; charset="UTF-8"
            Content-Transfer-Encoding: quoted-printable

            Hi Naresh,=0APlease share Current CTC, Expected CTC=
             and Notice Period.
             BODY[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID)] {120}
            From: Priya Gupta <priya.gupta@webmobril.com>
            To: nareshsaini868588@gmail.com
            Subject: =?UTF-8?Q?Interview_Update_=2D_.NET_Role?=
            Date: Wed, 19 Nov 2026
            Message-ID: <reply-999@webmobril.com>

            )
            A4 OK Success
        """.trimIndent()

        val parsed = smtp.parseImapBatchFetchResponse(
            batchResponse = batchResp,
            mailbox = "INBOX",
            folderPath = "INBOX"
        )
        assertEquals(1, parsed.size)
        val msg = parsed[0]
        assertEquals("INBOX:812", msg.uid)
        assertEquals("812", msg.imapUid)
        assertEquals("Interview Update - .NET Role", msg.subject)
        assertEquals("<reply-999@webmobril.com>", msg.messageId)
        assertTrue(msg.fullBody.contains("Please share Current CTC, Expected CTC and Notice Period."))
    }

    @Test
    fun `inbox auto-reply extractor detects recruiter questions and fills candidate details`() {
        val aiRepo = JobAiRepository()
        val profile = CandidateProfile.defaultForUser("uid_1", "nareshsaini868588@gmail.com", "Naresh Saini")
            .copy(
                currentCtc = "10 LPA",
                expectedCtc = "14 LPA",
                noticePeriod = "15 Days",
                currentLocation = "Noida, India"
            )
        val incoming = EmailMessage(
            uid = "INBOX:812",
            folder = "INBOX",
            from = "Priya Gupta <priya.gupta@webmobril.com>",
            to = "nareshsaini868588@gmail.com",
            subject = "Shortlisted for Sr. .NET Developer - Few Details Needed",
            date = "Wed, 19 Nov 2026",
            snippet = "Hi Naresh, please share Current CTC, Expected CTC, Notice Period, Current Location and updated CV.",
            messageId = "<reply-999@webmobril.com>",
            fullBody = "Hi Naresh,\n\nWe shortlisted your profile! Please share your Total Experience, Current CTC, Expected CTC, Notice Period, Current Location, and attach your updated CV."
        )

        val draft = aiRepo.extractAutoReplyLocally(incoming, profile)
        assertEquals("priya.gupta@webmobril.com", draft.toEmail)
        assertEquals("Re: Shortlisted for Sr. .NET Developer - Few Details Needed", draft.subject)
        assertEquals("<reply-999@webmobril.com>", draft.inReplyTo)
        assertTrue(draft.attachResume)
        assertTrue(draft.detectedRequirements.containsAll(listOf("Total Experience", "Current CTC", "Expected CTC", "Notice Period", "Current Location", "Updated CV")))
        assertTrue(draft.body.contains("Dear Priya,"))
        assertTrue(draft.body.contains("10 LPA"))
        assertTrue(draft.body.contains("14 LPA"))
        assertTrue(draft.body.contains("15 Days"))
        assertTrue(draft.body.contains("Noida, India"))
    }
}
