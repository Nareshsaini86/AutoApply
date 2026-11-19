package com.example.data.ai

import android.util.Log
import com.example.data.gmail.EmailTemplateFormatter
import com.example.data.model.AutoReplyDraft
import com.example.data.model.CandidateProfile
import com.example.data.model.EmailMessage
import com.example.data.model.FollowUpDraft
import com.example.data.model.JobApplication
import com.example.data.model.ParsedJobDraft
import com.example.data.model.ResumeInsights
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class JobAiRepository {

    private val parsedPostSchema = Schema.obj(
        properties = mapOf(
            "companyName" to Schema.string(description = "Hiring company name inferred from post text or recruiter email domain (e.g. WebMobril, Quokkalabs)."),
            "jobTitle" to Schema.string(description = "Job role title (e.g. .NET Developer)."),
            "recruiterEmail" to Schema.string(description = "Recruiter or HR email address extracted from the post, lowercase."),
            "recruiterName" to Schema.string(description = "Recruiter first or full name inferred from the email or post, or empty string if unknown."),
            "location" to Schema.string(description = "Job location (e.g. Noida, Remote (India))."),
            "workMode" to Schema.string(description = "Work mode such as Remote, Hybrid, or Onsite."),
            "requestedDetails" to Schema.array(
                items = Schema.string(),
                description = "Specific candidate details or eligibility requirements requested in the job post (e.g. Total Experience, Current CTC, Expected CTC, Notice Period, Current Location)."
            ),
            "emailSubject" to Schema.string(description = "Professional email subject line including job title, experience, key skills, and candidate name."),
            "emailBody" to Schema.string(description = "Complete, cleanly formatted job application email body addressed to the recruiter, highlighting relevant resume skills and clearly listing all requested details.")
        )
    )

    private val followUpSchema = Schema.obj(
        properties = mapOf(
            "subject" to Schema.string(description = "Polite follow-up email subject line starting with Re: and the original subject or role."),
            "body" to Schema.string(description = "Concise, respectful, high-response follow-up email body referencing the applied role and key candidate fit.")
        )
    )

    private val resumeInsightsSchema = Schema.obj(
        properties = mapOf(
            "targetRole" to Schema.string(description = "Primary job role the candidate is suited for based on the resume (e.g. Java Developer, Digital Marketing Manager, UI/UX Designer, .NET Full Stack Developer, HR Recruiter)."),
            "preferredSearchQuery" to Schema.string(description = "Concise 2-3 word LinkedIn search query for hiring posts matching this resume (e.g. 'Java Developer', '.NET Full Stack', 'UI UX Designer', 'Digital Marketing')."),
            "skillsSummary" to Schema.string(description = "Comma-separated top 8-12 skills found in the resume."),
            "totalExperience" to Schema.string(description = "Total years of experience found in resume (e.g. '3+ Years', 'Fresher', '5+ Years')."),
            "resumeSummary" to Schema.string(description = "Concise 2-sentence professional summary based strictly on this resume."),
            "phone" to Schema.string(description = "Phone number found in resume or empty string."),
            "linkedinUrl" to Schema.string(description = "LinkedIn URL found in resume or empty string.")
        )
    )

    private val fastParseModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = parsedPostSchema
            }
        )
    }

    private val fastFollowUpModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = followUpSchema
            }
        )
    }

    private val autoReplySchema = Schema.obj(
        properties = mapOf(
            "subject" to Schema.string(description = "Reply email subject line starting with Re: and keeping original thread subject."),
            "body" to Schema.string(description = "Cleanly formatted reply answering every question or requirement asked in the recruiter's email using candidate's profile."),
            "detectedRequirements" to Schema.array(
                items = Schema.string(),
                description = "Short labels for each question/requirement detected in the incoming email (e.g. Current CTC, Expected CTC, Notice Period, Interview Slot, Updated CV)."
            ),
            "attachResume" to Schema.boolean(description = "True if attaching the candidate's PDF resume/CV is helpful or requested.")
        )
    )

    private val resumeReaderModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = resumeInsightsSchema
            }
        )
    }

    private val fastAutoReplyModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = autoReplySchema
            }
        )
    }

    /**
     * Reads an uploaded resume locally in <10ms across software, design, marketing, HR,
     * finance, sales, and operations fields so LinkedIn job discovery adapts immediately.
     */
    fun extractResumeInsightsLocally(
        fileName: String,
        extractedPdfText: String,
        fallbackProfile: CandidateProfile
    ): ResumeInsights {
        val corpus = "$fileName $extractedPdfText"
        val lowerCorpus = corpus.lowercase()
        val detectedSkills = detectDomainSkills(lowerCorpus)

        val inferredRole = inferPrimaryRoleFromResume(lowerCorpus, detectedSkills)
            .ifBlank { fallbackProfile.currentRole.ifBlank { "Software / Professional" } }

        val searchQuery = deriveConciseSearchQuery(inferredRole, detectedSkills)

        val expMatch = Regex("\\b(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)\\b", RegexOption.IGNORE_CASE)
            .find(extractedPdfText)
        val totalExp = when {
            expMatch != null -> "${expMatch.groupValues[1]}+ Years"
            lowerCorpus.contains("fresher") || lowerCorpus.contains("entry level") -> "Fresher"
            else -> fallbackProfile.totalExperience.ifBlank { "1+ Years" }
        }

        val phoneMatch = Regex("(?:\\+?\\d{1,3}[\\s-]?)?\\d{10}").find(extractedPdfText)
        val linkedinMatch = Regex("https?://(?:www\\.)?linkedin\\.com/in/[A-Za-z0-9_-]+/?", RegexOption.IGNORE_CASE)
            .find(extractedPdfText)

        val skillsText = if (detectedSkills.isNotEmpty()) {
            detectedSkills.joinToString(", ")
        } else {
            fallbackProfile.skillsSummary
        }

        val summaryText = if (extractedPdfText.length > 40) {
            "$inferredRole with $totalExp experience" +
                (if (detectedSkills.isNotEmpty()) " specializing in ${detectedSkills.take(5).joinToString(", ")}." else ".")
        } else {
            fallbackProfile.resumeSummary
        }

        return ResumeInsights(
            targetRole = inferredRole,
            preferredSearchQuery = searchQuery,
            skillsSummary = skillsText,
            totalExperience = totalExp,
            resumeSummary = summaryText,
            phone = phoneMatch?.value?.trim() ?: fallbackProfile.phone,
            linkedinUrl = linkedinMatch?.value?.trim() ?: fallbackProfile.linkedinUrl
        )
    }

    suspend fun analyzeResumePdfWithGemini(
        fileName: String,
        pdfBytes: ByteArray,
        extractedText: String
    ): Result<ResumeInsights> {
        return try {
            val promptInstruction = """
                Analyze this candidate resume ($fileName) and identify the exact job roles and LinkedIn search keywords they should target.
                Return concise, accurate fields for ANY industry (IT, Tech, Marketing, HR, Finance, Sales, Design, Operations, etc.).
                ${if (extractedText.length >= 60) "\nExtracted text snippet:\n${extractedText.take(4000)}" else ""}
            """.trimIndent()

            val response = withTimeout(15_000L) {
                if (pdfBytes.size in 1..(4 * 1024 * 1024)) {
                    resumeReaderModel.generateContent(
                        content {
                            inlineData(pdfBytes, "application/pdf")
                            text(promptInstruction)
                        }
                    )
                } else {
                    resumeReaderModel.generateContent(promptInstruction)
                }
            }
            val jsonStr = response.text
            if (jsonStr.isNullOrBlank()) {
                Result.failure(IllegalStateException("Empty resume analysis"))
            } else {
                val json = JSONObject(jsonStr)
                val targetRole = json.optString("targetRole").trim()
                val query = json.optString("preferredSearchQuery").trim()
                    .ifBlank { deriveConciseSearchQuery(targetRole, emptyList()) }
                if (targetRole.isBlank() && query.isBlank()) {
                    Result.failure(IllegalStateException("Could not infer role from resume"))
                } else {
                    Result.success(
                        ResumeInsights(
                            targetRole = targetRole,
                            preferredSearchQuery = query,
                            skillsSummary = json.optString("skillsSummary").trim(),
                            totalExperience = json.optString("totalExperience").trim(),
                            resumeSummary = json.optString("resumeSummary").trim(),
                            phone = json.optString("phone").trim(),
                            linkedinUrl = json.optString("linkedinUrl").trim()
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini PDF resume analysis fell back locally: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Quick non-blocking Gemini AI polish with a tight 8-second timeout so UI never hangs.
     * Local extraction already populates the draft instantaneously before this is invoked.
     */
    suspend fun polishDraftWithGemini(
        rawPost: String,
        profile: CandidateProfile
    ): Result<ParsedJobDraft> {
        return try {
            val prompt = buildParsePrompt(rawPost, profile)
            val response = withTimeout(8_000L) {
                fastParseModel.generateContent(prompt)
            }
            val jsonText = response.text
            if (jsonText.isNullOrBlank()) {
                Result.failure(IllegalStateException("Empty AI response"))
            } else {
                Result.success(parseGeminiDraftJson(jsonText))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini polish skipped/timed out: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun draftFollowUpEmail(
        application: JobApplication,
        profile: CandidateProfile
    ): Result<FollowUpDraft> {
        val nextFollowUpNum = application.followUpCount + 1L
        val prompt = buildFollowUpPrompt(application, profile, nextFollowUpNum)

        try {
            val response = withTimeout(8_000L) {
                fastFollowUpModel.generateContent(prompt)
            }
            val jsonText = response.text
            if (!jsonText.isNullOrBlank()) {
                val json = JSONObject(jsonText)
                val subject = json.optString("subject").trim()
                    .ifBlank { "Re: ${application.emailSubject}" }
                val body = EmailTemplateFormatter.normalizePlainText(json.optString("body").trim())
                if (body.isNotBlank()) {
                    return Result.success(FollowUpDraft(subject = subject, body = body))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Follow-up Gemini call fell back locally: ${e.message}")
        }

        val greeting = application.recruiterName.takeIf { it.isNotBlank() }
            ?.let { "Dear ${it.substringBefore(" ")}," }
            ?: "Dear Hiring Team,"
        val subject = "Re: ${application.emailSubject}"
        val body = buildString {
            appendLine(greeting)
            appendLine()
            appendLine("I hope you are doing well. I am writing to politely follow up on my application for the ${application.jobTitle} role at ${application.companyName}.")
            appendLine()
            appendLine("Quick Snapshot:")
            appendLine("• Total Experience: ${profile.totalExperience.ifBlank { "5+ Years" }}")
            appendLine("• Core Stack: ${profile.skillsSummary.split(",").take(5).joinToString(", ") { it.trim() }}")
            appendLine("• Notice Period: ${profile.noticePeriod.ifBlank { "30 Days" }}")
            appendLine()
            appendLine("My updated resume (${profile.resumeFileName}) is attached for your convenience. I would welcome the chance to connect regarding next steps.")
            appendLine()
            appendLine("Best regards,")
            appendLine(profile.fullName)
            if (profile.phone.isNotBlank()) appendLine(profile.phone)
            if (profile.email.isNotBlank()) appendLine(profile.email)
        }.trim()
        return Result.success(FollowUpDraft(subject = subject, body = body))
    }

    /**
     * Instant (<5ms) Inbox reply auto-filler: detects every question/requirement
     * the recruiter asked in their incoming email or reply and pre-fills answers
     * from the candidate's profile so the user can review and send in 1 click.
     */
    fun extractAutoReplyLocally(
        email: EmailMessage,
        profile: CandidateProfile,
        trackedApps: List<JobApplication> = emptyList()
    ): AutoReplyDraft {
        val rawSenderHeader = if (email.folder.equals("SENT", ignoreCase = true)) email.to else email.from
        val senderEmail = EMAIL_REGEX.find(rawSenderHeader)?.value?.trim()?.lowercase().orEmpty()

        val displayNameFromHeader = rawSenderHeader
            .substringBefore("<")
            .replace("\"", "")
            .trim()
            .takeIf { it.isNotBlank() && !it.contains("@") }
        val senderName = displayNameFromHeader ?: if (senderEmail.contains("@")) {
            val local = senderEmail.substringBefore("@")
            if (local !in GENERIC_EMAIL_LOCAL_PARTS) {
                local.split(".", "_", "-")
                    .filter { it.length > 1 && it.all(Char::isLetter) }
                    .joinToString(" ") { part -> part.replaceFirstChar { c -> c.uppercase() } }
            } else ""
        } else ""

        val matchedApp = trackedApps.firstOrNull { app ->
            (senderEmail.isNotBlank() && app.recruiterEmail.equals(senderEmail, ignoreCase = true)) ||
                (app.emailSubject.isNotBlank() && email.subject.contains(app.emailSubject.take(24), ignoreCase = true))
        }

        val emailBodyText = email.fullBody.ifBlank { email.snippet }
        val corpus = "${email.subject}\n$emailBodyText"
        val lower = corpus.lowercase()

        val detectedRequirements = mutableListOf<String>()
        val answerRows = mutableListOf<Pair<String, String>>()

        fun addReq(label: String, value: String) {
            if (label !in detectedRequirements) detectedRequirements += label
            if (answerRows.none { it.first == label }) answerRows += label to value
        }

        if (lower.contains("experience") || lower.contains("years of exp") || Regex("\\brelevant exp|\\bexp\\b").containsMatchIn(lower)) {
            addReq("Total Experience", profile.totalExperience.ifBlank { "5+ Years" })
        }
        if (lower.contains("current ctc") || lower.contains("cctc") || lower.contains("current salary") || lower.contains("current package") || lower.contains("current compensation")) {
            addReq("Current CTC", profile.currentCtc.ifBlank { "Negotiable (will share on call)" })
        }
        if (lower.contains("expected ctc") || lower.contains("ectc") || lower.contains("expected salary") || lower.contains("expected package") || lower.contains("salary expectation") || lower.contains("ctc")) {
            if (answerRows.none { it.first == "Current CTC" } && lower.contains("ctc") && !lower.contains("expected")) {
                addReq("Current CTC", profile.currentCtc.ifBlank { "Negotiable (will share on call)" })
            }
            addReq("Expected CTC", profile.expectedCtc.ifBlank { "Open to market standards" })
        }
        if (lower.contains("notice") || lower.contains("immediate") || lower.contains("joining") || lower.contains("how soon") || lower.contains("lwd") || Regex("\\bnp\\b").containsMatchIn(lower)) {
            addReq(
                "Notice Period",
                buildNoticeAnswer(profile.noticePeriod, asksImmediate = lower.contains("immediate"))
            )
        }
        if (lower.contains("location") || lower.contains("relocat") || lower.contains("city") || lower.contains("hybrid") || lower.contains("onsite") || lower.contains("work from office")) {
            addReq("Current Location", profile.currentLocation.ifBlank { "India" } + " (Open to relocate / Hybrid / Onsite)")
        }
        if (lower.contains("interview") || lower.contains("availability") || lower.contains("available") || lower.contains("schedule") || lower.contains("slot") || lower.contains("time to connect") || lower.contains("call")) {
            addReq("Interview Availability", "Available this week (Mon–Fri, flexible slots as per your convenience)")
        }
        if (lower.contains("reason for change") || lower.contains("why are you looking") || lower.contains("looking for a change")) {
            addReq("Reason for Change", "Seeking high-impact growth & challenging opportunities aligned with my experience")
        }
        val mentionedSkills = detectDomainSkills(lower)
        if (mentionedSkills.isNotEmpty() || lower.contains("skill") || lower.contains("tech stack") || lower.contains("requirement")) {
            val skillsAnswer = if (mentionedSkills.isNotEmpty()) {
                mentionedSkills.joinToString(", ")
            } else {
                profile.skillsSummary
            }
            addReq("Key Skills Match", skillsAnswer)
        }
        val asksResume = lower.contains("resume") || lower.contains("cv") || lower.contains("updated profile") || lower.contains("attach")
        if (asksResume) {
            detectedRequirements += "Updated CV"
        }

        // Fallback so every email always has helpful candidate details pre-filled
        if (answerRows.isEmpty()) {
            answerRows += "Current Role" to profile.currentRole.ifBlank { "Experienced Professional" }
            answerRows += "Total Experience" to profile.totalExperience.ifBlank { "5+ Years" }
            if (profile.currentCtc.isNotBlank()) answerRows += "Current CTC" to profile.currentCtc
            if (profile.expectedCtc.isNotBlank()) answerRows += "Expected CTC" to profile.expectedCtc
            answerRows += "Notice Period" to profile.noticePeriod.ifBlank { "30 Days" }
            answerRows += "Current Location" to profile.currentLocation.ifBlank { "India" }
        }

        val greeting = if (senderName.isNotBlank()) {
            "Dear ${senderName.substringBefore(" ")},"
        } else {
            "Dear Hiring Team,"
        }

        val reSubject = if (email.subject.startsWith("Re:", ignoreCase = true)) {
            email.subject
        } else {
            "Re: ${email.subject}"
        }

        val roleContext = matchedApp?.jobTitle?.takeIf { it.isNotBlank() }
            ?: profile.currentRole.ifBlank { "this opportunity" }
        val introLine = if (matchedApp != null) {
            "Thank you for reaching out regarding my application for the $roleContext role at ${matchedApp.companyName}."
        } else {
            "Thank you for your email and for considering my profile for $roleContext."
        }

        val attachCv = asksResume || matchedApp != null || detectedRequirements.isNotEmpty()
        val body = buildString {
            appendLine(greeting)
            appendLine()
            appendLine(introLine)
            appendLine()
            appendLine("Please find below the requested details:")
            answerRows.forEach { (label, value) ->
                appendLine("• $label: $value")
            }
            if (profile.phone.isNotBlank()) appendLine("• Contact Number: ${profile.phone}")
            if (profile.linkedinUrl.isNotBlank()) appendLine("• LinkedIn: ${profile.linkedinUrl}")
            appendLine()
            if (attachCv) {
                appendLine("I have also attached my updated resume (${profile.resumeFileName}) for your quick reference. Please let me know a convenient time to connect for the next steps.")
            } else {
                appendLine("Please let me know if you need any additional information or a convenient time for a quick discussion.")
            }
            appendLine()
            appendLine("Best regards,")
            appendLine(profile.fullName)
            if (profile.phone.isNotBlank()) appendLine(profile.phone)
            if (profile.email.isNotBlank()) appendLine(profile.email)
        }.trim()

        return AutoReplyDraft(
            toEmail = senderEmail,
            subject = reSubject,
            body = EmailTemplateFormatter.normalizePlainText(body),
            detectedRequirements = detectedRequirements.distinct(),
            attachResume = attachCv,
            inReplyTo = email.messageId.takeIf { it.isNotBlank() }
        )
    }

    suspend fun polishAutoReplyWithGemini(
        email: EmailMessage,
        profile: CandidateProfile,
        matchedApp: JobApplication? = null
    ): Result<AutoReplyDraft> {
        val rawSenderHeader = if (email.folder.equals("SENT", ignoreCase = true)) email.to else email.from
        val senderEmail = EMAIL_REGEX.find(rawSenderHeader)?.value?.trim()?.lowercase().orEmpty()
        if (senderEmail.isBlank()) {
            return Result.failure(IllegalArgumentException("No recipient email in message"))
        }

        return try {
            val prompt = buildString {
                appendLine("You are replying on behalf of a job candidate to a recruiter's email in their Inbox.")
                appendLine("Read every question, requirement, or detail asked in the incoming email and write a crisp, polite, ready-to-send reply with all details clearly answered.")
                appendLine()
                appendLine("INCOMING EMAIL:")
                appendLine("From: ${email.from}")
                appendLine("Subject: ${email.subject}")
                appendLine("Content:")
                appendLine(email.fullBody.ifBlank { email.snippet }.take(2500))
                appendLine()
                if (matchedApp != null) {
                    appendLine("MATCHED APPLICATION CONTEXT: Role=${matchedApp.jobTitle}, Company=${matchedApp.companyName}")
                }
                appendLine("CANDIDATE PROFILE:")
                appendLine("- Name: ${profile.fullName}")
                appendLine("- Role: ${profile.currentRole}")
                appendLine("- Experience: ${profile.totalExperience}")
                appendLine("- Current CTC: ${profile.currentCtc.ifBlank { "Negotiable (discuss on call)" }}")
                appendLine("- Expected CTC: ${profile.expectedCtc.ifBlank { "Open to market standards" }}")
                appendLine("- Notice Period: ${profile.noticePeriod.ifBlank { "30 Days" }}")
                appendLine("- Current Location: ${profile.currentLocation}")
                appendLine("- Skills: ${profile.skillsSummary}")
                appendLine("- Phone: ${profile.phone}")
                appendLine("- LinkedIn: ${profile.linkedinUrl}")
                appendLine("- Attached CV File: ${profile.resumeFileName}")
                appendLine()
                appendLine("FORMATTING RULES:")
                appendLine("- Use greeting on its own line, blank line, concise thank-you sentence, blank line, 'Please find below the requested details:' followed by '• Label: Value' lines, blank line, closing + resume note, blank line, 'Best regards,' + name/phone/email.")
            }

            val response = withTimeout(8_000L) {
                fastAutoReplyModel.generateContent(prompt)
            }
            val jsonText = response.text ?: return Result.failure(IllegalStateException("Empty auto-reply response"))
            val json = JSONObject(jsonText)
            val reqsJson = json.optJSONArray("detectedRequirements")
            val reqs = buildList {
                if (reqsJson != null) {
                    for (i in 0 until reqsJson.length()) {
                        val item = reqsJson.optString(i).trim()
                        if (item.isNotBlank()) add(item)
                    }
                }
            }
            val subject = json.optString("subject").trim().ifBlank {
                if (email.subject.startsWith("Re:", ignoreCase = true)) email.subject else "Re: ${email.subject}"
            }
            val body = EmailTemplateFormatter.normalizePlainText(json.optString("body").trim())
            if (body.isBlank()) {
                Result.failure(IllegalStateException("Blank auto-reply body"))
            } else {
                Result.success(
                    AutoReplyDraft(
                        toEmail = senderEmail,
                        subject = subject,
                        body = body,
                        detectedRequirements = reqs,
                        attachResume = json.optBoolean("attachResume", true),
                        inReplyTo = email.messageId.takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Auto-reply Gemini polish skipped: ${e.message}")
            Result.failure(e)
        }
    }

    private fun parseGeminiDraftJson(jsonText: String): ParsedJobDraft {
        val json = JSONObject(jsonText)
        val detailsJson = json.optJSONArray("requestedDetails")
        val requestedDetails = buildList {
            if (detailsJson != null) {
                for (i in 0 until detailsJson.length()) {
                    val item = detailsJson.optString(i).trim()
                    if (item.isNotBlank()) add(item)
                }
            }
        }
        return ParsedJobDraft(
            companyName = json.optString("companyName").trim(),
            jobTitle = json.optString("jobTitle").trim(),
            recruiterEmail = json.optString("recruiterEmail").trim().lowercase(),
            recruiterName = json.optString("recruiterName").trim(),
            location = json.optString("location").trim(),
            workMode = json.optString("workMode").trim(),
            requestedDetails = requestedDetails,
            emailSubject = json.optString("emailSubject").trim(),
            emailBody = EmailTemplateFormatter.normalizePlainText(json.optString("emailBody").trim())
        )
    }

    /**
     * Instant (<5ms) structured LinkedIn post extractor & email formatter.
     * Handles standard posts, bulleted Q&A prompts, and hashtag-heavy LinkedIn posts.
     */
    fun extractAndFormatLocally(
        rawPost: String,
        profile: CandidateProfile,
        noticeNote: String? = null
    ): ParsedJobDraft {
        val normalizedText = rawPost
            .replace("\u200B", "")
            .replace(Regex("([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+)\\s*\\n\\s*\\.(com|in|co\\.in|org|net|io|ai)"), "$1.$2")
            .trim()

        val emailMatch = EMAIL_REGEX.find(normalizedText)
        val recruiterEmail = emailMatch?.value?.trim()?.lowercase().orEmpty()

        val recruiterName = if (recruiterEmail.contains("@")) {
            val local = recruiterEmail.substringBefore("@")
            if (local !in GENERIC_EMAIL_LOCAL_PARTS) {
                local.split(".", "_", "-")
                    .filter { it.length > 1 && it.all(Char::isLetter) }
                    .joinToString(" ") { part -> part.replaceFirstChar { c -> c.uppercase() } }
            } else ""
        } else ""

        val domainCompany = if (recruiterEmail.contains("@")) {
            val domain = recruiterEmail.substringAfter("@").substringBefore(".")
            if (domain.lowercase() !in FREE_EMAIL_DOMAINS && domain.isNotBlank()) {
                domain.replaceFirstChar { it.uppercase() }
            } else ""
        } else ""

        val lines = normalizedText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val lowerPost = normalizedText.lowercase()

        val detectedSkills = detectDomainSkills(lowerPost)
        val jobTitle = extractJobTitle(lines, detectedSkills)
        val location = extractLocation(lines, lowerPost)
        val workMode = when {
            lowerPost.contains("remote") -> "Remote"
            lowerPost.contains("hybrid") -> "Hybrid"
            lowerPost.contains("onsite") || lowerPost.contains("on-site") -> "Onsite"
            else -> ""
        }

        val asksImmediateOrNp = lowerPost.contains("immediate") ||
            lowerPost.contains("serving np") ||
            lowerPost.contains("notice period") ||
            lowerPost.contains("immediatejoining")

        val requestedPairs = mutableListOf<Pair<String, String>>()

        fun addIf(cond: Boolean, label: String, value: String) {
            if (cond && requestedPairs.none { it.first == label }) {
                requestedPairs += label to value
            }
        }

        addIf(
            lowerPost.contains("experience") || lowerPost.contains("exp"),
            "Total Experience",
            profile.totalExperience.ifBlank { "5+ Years" }
        )
        addIf(
            asksImmediateOrNp,
            if (lowerPost.contains("immediate")) "Notice Period / Availability" else "Notice Period",
            buildNoticeAnswer(profile.noticePeriod, asksImmediate = lowerPost.contains("immediate"))
        )
        addIf(
            lowerPost.contains("current ctc") || lowerPost.contains("cctc"),
            "Current CTC",
            profile.currentCtc.ifBlank { "Negotiable (discuss on call)" }
        )
        addIf(
            lowerPost.contains("expected ctc") || lowerPost.contains("ectc"),
            "Expected CTC",
            profile.expectedCtc.ifBlank { "Open to market standards" }
        )
        addIf(
            lowerPost.contains("location") || location.isNotBlank(),
            "Current Location",
            profile.currentLocation.ifBlank { "India" } +
                if (location.isNotBlank() && !profile.currentLocation.contains(location, ignoreCase = true)) {
                    " (Open to $location)"
                } else ""
        )

        if (requestedPairs.isEmpty()) {
            requestedPairs += "Total Experience" to profile.totalExperience.ifBlank { "5+ Years" }
            requestedPairs += "Notice Period" to profile.noticePeriod.ifBlank { "30 Days" }
            if (profile.currentCtc.isNotBlank()) requestedPairs += "Current CTC" to profile.currentCtc
            if (profile.expectedCtc.isNotBlank()) requestedPairs += "Expected CTC" to profile.expectedCtc
            requestedPairs += "Current Location" to profile.currentLocation.ifBlank { "India" }
        } else {
            if (requestedPairs.none { it.first.startsWith("Total Experience") }) {
                requestedPairs.add(0, "Total Experience" to profile.totalExperience.ifBlank { "5+ Years" })
            }
            if (requestedPairs.none { it.first.startsWith("Notice Period") }) {
                requestedPairs += "Notice Period" to profile.noticePeriod.ifBlank { "30 Days" }
            }
        }

        val companyDisplay = domainCompany.ifBlank { "Hiring Team" }
        val greeting = if (recruiterName.isNotBlank()) {
            "Dear ${recruiterName.substringBefore(" ")},"
        } else {
            "Dear Hiring Team,"
        }
        val expShort = profile.totalExperience.ifBlank { "5+ Years" }
        val skillHighlight = if (detectedSkills.isNotEmpty()) {
            detectedSkills.take(4).joinToString(", ")
        } else {
            profile.skillsSummary.split(",").take(4).joinToString(", ") { it.trim() }
        }

        val subject = "Application for $jobTitle ($expShort Exp | $skillHighlight) - ${profile.fullName}"

        val body = buildString {
            appendLine(greeting)
            appendLine()
            appendLine(
                "I came across your LinkedIn hiring post for the $jobTitle position" +
                    (if (domainCompany.isNotBlank()) " at $domainCompany" else "") +
                    (if (location.isNotBlank()) " ($location)" else "") +
                    " and would love to be considered for this opportunity."
            )
            appendLine()
            appendLine(
                "With $expShort of hands-on experience in $skillHighlight, I have consistently delivered impactful work and am ready to contribute from day one."
            )
            appendLine()
            appendLine("Candidate Details (as requested):")
            requestedPairs.forEach { (label, value) ->
                appendLine("• $label: $value")
            }
            appendLine("• Key Skills: ${if (detectedSkills.isNotEmpty()) detectedSkills.joinToString(", ") else profile.skillsSummary}")
            if (profile.phone.isNotBlank()) appendLine("• Contact: ${profile.phone}")
            if (profile.linkedinUrl.isNotBlank()) appendLine("• LinkedIn: ${profile.linkedinUrl}")
            appendLine()
            appendLine("I have attached my updated resume (${profile.resumeFileName}) for your review and would welcome the opportunity to connect.")
            appendLine()
            appendLine("Best regards,")
            appendLine(profile.fullName)
            if (profile.phone.isNotBlank()) appendLine(profile.phone)
            if (profile.email.isNotBlank()) appendLine(profile.email)
        }.trim()

        return ParsedJobDraft(
            companyName = companyDisplay,
            jobTitle = jobTitle,
            recruiterEmail = recruiterEmail,
            recruiterName = recruiterName,
            location = location,
            workMode = workMode,
            requestedDetails = requestedPairs.map { it.first },
            emailSubject = subject,
            emailBody = body,
            noticeNote = noticeNote
        )
    }

    private fun buildNoticeAnswer(configuredNotice: String, asksImmediate: Boolean): String {
        val base = configuredNotice.ifBlank { "30 Days" }
        return if (asksImmediate && !base.contains("immediate", ignoreCase = true)) {
            "$base (Negotiable / Early joining possible)"
        } else {
            base
        }
    }

    private fun detectDomainSkills(lowerText: String): List<String> {
        val matches = mutableListOf<String>()
        val catalog = listOf(
            "asp.net core" to "ASP.NET Core",
            "dotnetcore" to ".NET Core",
            ".net core" to ".NET Core",
            "dotnet" to ".NET",
            ".net" to ".NET",
            "c#" to "C#",
            "mvc" to "ASP.NET MVC",
            "entityframework" to "Entity Framework",
            "entity framework" to "Entity Framework",
            "spring boot" to "Spring Boot",
            "java" to "Java",
            "python" to "Python",
            "django" to "Django",
            "fastapi" to "FastAPI",
            "react" to "React.js",
            "angular" to "Angular",
            "node.js" to "Node.js",
            "nodejs" to "Node.js",
            "typescript" to "TypeScript",
            "javascript" to "JavaScript",
            "kotlin" to "Kotlin",
            "android" to "Android",
            "flutter" to "Flutter",
            "swift" to "iOS / Swift",
            "sql server" to "SQL Server",
            "sql" to "SQL",
            "web api" to "REST APIs",
            "rest api" to "REST APIs",
            "azure" to "Azure",
            "aws" to "AWS",
            "docker" to "Docker",
            "kubernetes" to "Kubernetes",
            "devops" to "DevOps",
            "microservices" to "Microservices",
            "power bi" to "Power BI",
            "tableau" to "Tableau",
            "data analyst" to "Data Analysis",
            "machine learning" to "Machine Learning",
            "selenium" to "QA Automation",
            "manual testing" to "QA Testing",
            "figma" to "Figma",
            "ui/ux" to "UI/UX Design",
            "seo" to "SEO",
            "digital marketing" to "Digital Marketing",
            "social media" to "Social Media",
            "talent acquisition" to "Talent Acquisition",
            "recruitment" to "Recruitment",
            "human resources" to "HR Management",
            "accounting" to "Accounting",
            "tally" to "Tally / GST",
            "financial" to "Finance",
            "business development" to "Business Development",
            "sales" to "Sales",
            "product management" to "Product Management"
        )
        for ((token, label) in catalog) {
            if (lowerText.contains(token) && label !in matches) {
                matches += label
            }
        }
        return matches
    }

    private fun inferPrimaryRoleFromResume(lowerCorpus: String, skills: List<String>): String {
        return when {
            skills.any { it.contains(".NET", ignoreCase = true) || it == "C#" } ->
                if (skills.any { it.contains("React") || it.contains("Angular") }) ".NET Full Stack Developer" else ".NET Developer"
            skills.any { it == "Java" || it == "Spring Boot" } -> "Java Backend Developer"
            skills.any { it == "Android" || it == "Kotlin" } -> "Android Developer"
            skills.any { it == "Flutter" } -> "Flutter Developer"
            skills.any { it == "Python" || it == "Django" || it == "FastAPI" } -> "Python Developer"
            skills.any { it == "React.js" || it == "Angular" || it == "Node.js" } -> "Full Stack / Frontend Developer"
            skills.any { it == "UI/UX Design" || it == "Figma" } -> "UI/UX Designer"
            skills.any { it == "Data Analysis" || it == "Power BI" || it == "Tableau" } -> "Data Analyst"
            skills.any { it == "Machine Learning" } -> "AI / ML Engineer"
            skills.any { it == "DevOps" || it == "Kubernetes" || it == "Docker" } -> "DevOps Engineer"
            skills.any { it == "QA Automation" || it == "QA Testing" } -> "QA / Software Tester"
            skills.any { it == "Digital Marketing" || it == "SEO" } -> "Digital Marketing Specialist"
            skills.any { it == "Talent Acquisition" || it == "Recruitment" || it == "HR Management" } -> "HR & Talent Acquisition"
            skills.any { it == "Accounting" || it == "Tally / GST" || it == "Finance" } -> "Finance & Accounts Executive"
            skills.any { it == "Business Development" || it == "Sales" } -> "Business Development / Sales"
            skills.any { it == "Product Management" } -> "Product Manager"
            lowerCorpus.contains("software") || lowerCorpus.contains("developer") -> "Software Developer"
            else -> ""
        }
    }

    private fun deriveConciseSearchQuery(role: String, skills: List<String>): String {
        val cleanRole = role.replace(Regex("\\b(Sr\\.?|Senior|Junior|Jr\\.?|Lead|Intern)\\b", RegexOption.IGNORE_CASE), "")
            .replace("(", "")
            .replace(")", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            cleanRole.contains(".NET", ignoreCase = true) -> ".NET Full Stack"
            cleanRole.isNotBlank() && cleanRole.length <= 28 -> cleanRole
            skills.isNotEmpty() -> skills.take(2).joinToString(" ")
            cleanRole.isNotBlank() -> cleanRole.split(" ").take(3).joinToString(" ")
            else -> ""
        }
    }

    private fun extractJobTitle(lines: List<String>, detectedSkills: List<String>): String {
        val hiringPipe = lines.firstOrNull { it.contains("hiring", ignoreCase = true) && it.contains("|") }
        if (hiringPipe != null) {
            val candidate = hiringPipe.substringAfter("|").trim()
            if (candidate.length in 3..80) return toTitleCasePreservingTech(candidate)
        }
        val roleLine = lines.firstOrNull {
            (it.contains("role", ignoreCase = true) || it.contains("position", ignoreCase = true)) && it.contains(":")
        }
        if (roleLine != null) {
            val candidate = roleLine.substringAfter(":").trim()
            if (candidate.length in 3..80) return candidate
        }
        val devLine = lines.firstOrNull {
            (it.contains("developer", ignoreCase = true) ||
                it.contains("engineer", ignoreCase = true) ||
                it.contains("designer", ignoreCase = true) ||
                it.contains("analyst", ignoreCase = true) ||
                it.contains("manager", ignoreCase = true) ||
                it.contains("executive", ignoreCase = true)) && !it.contains("#")
        }
        if (devLine != null && devLine.length <= 70) {
            return devLine.replace(Regex("^[^a-zA-Z0-9.]+"), "").trim()
        }
        val inferred = inferPrimaryRoleFromResume("", detectedSkills)
        return inferred.ifBlank { "Open Position" }
    }

    private fun extractLocation(lines: List<String>, lowerPost: String): String {
        val explicit = lines.firstOrNull {
            it.contains("location:", ignoreCase = true)
        }?.substringAfter(":")?.trim()
        if (!explicit.isNullOrBlank()) return explicit

        val cities = listOf(
            "noida" to "Noida",
            "gurugram" to "Gurugram",
            "gurgaon" to "Gurgaon",
            "delhi" to "Delhi NCR",
            "bengaluru" to "Bengaluru",
            "bangalore" to "Bengaluru",
            "pune" to "Pune",
            "hyderabad" to "Hyderabad",
            "mumbai" to "Mumbai",
            "chennai" to "Chennai",
            "remote" to "Remote"
        )
        return cities.firstOrNull { lowerPost.contains(it.first) }?.second.orEmpty()
    }

    private fun toTitleCasePreservingTech(input: String): String {
        return input.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            if (word.startsWith(".") || word.length <= 2) word
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun buildParsePrompt(rawPost: String, profile: CandidateProfile): String = buildString {
        appendLine("You are an expert career & job application assistant. Parse the LinkedIn job post below and draft a cleanly formatted job application email.")
        appendLine()
        appendLine("LINKEDIN JOB POST:")
        appendLine(rawPost.trim())
        appendLine()
        appendLine("CANDIDATE PROFILE:")
        appendLine("- Full Name: ${profile.fullName}")
        appendLine("- Email: ${profile.email}")
        appendLine("- Phone: ${profile.phone}")
        appendLine("- LinkedIn: ${profile.linkedinUrl}")
        appendLine("- Current Role: ${profile.currentRole}")
        appendLine("- Total Experience: ${profile.totalExperience}")
        appendLine("- Current CTC: ${profile.currentCtc.ifBlank { "Negotiable (discuss on call)" }}")
        appendLine("- Expected CTC: ${profile.expectedCtc.ifBlank { "Open to market standards" }}")
        appendLine("- Notice Period: ${profile.noticePeriod.ifBlank { "30 Days" }}")
        appendLine("- Current Location: ${profile.currentLocation}")
        appendLine("- Technical Skills: ${profile.skillsSummary}")
        appendLine("- Resume Highlights: ${profile.resumeSummary}")
        appendLine("- Attached Resume File: ${profile.resumeFileName}")
        appendLine()
        appendLine("INSTRUCTIONS:")
        appendLine("1. Extract companyName accurately from post or email domain (e.g. subhika.chatterjee@quokkalabs.com -> Quokkalabs).")
        appendLine("2. Extract recruiterEmail (handle line-breaks inside email) and recruiterName.")
        appendLine("3. Parse hashtags too (e.g. #dotnet #c# #MVC #noida #Immediatejoining).")
        appendLine("4. Format emailBody with CLEAN line breaks (never one wall of text): greeting on its own line, blank line, 2-sentence pitch, blank line, 'Candidate Details:' header, each detail on its own line starting with '• ', blank line, resume mention, blank line, 'Best regards,' + name/phone/email on separate lines.")
    }

    private fun buildFollowUpPrompt(
        application: JobApplication,
        profile: CandidateProfile,
        nextFollowUpNum: Long
    ): String = buildString {
        appendLine("Draft a concise, polite, professional job application follow-up email (#$nextFollowUpNum).")
        appendLine("- Company: ${application.companyName}")
        appendLine("- Role: ${application.jobTitle}")
        appendLine("- Recruiter Name: ${application.recruiterName.ifBlank { "Hiring Team" }}")
        appendLine("- Original Subject: ${application.emailSubject}")
        appendLine("- Candidate Name: ${profile.fullName}, Exp: ${profile.totalExperience}, Notice: ${profile.noticePeriod}, Skills: ${profile.skillsSummary}")
        appendLine("- Resume Attached: ${profile.resumeFileName}")
        appendLine("Subject must start with 'Re: ${application.emailSubject}'. Keep body under 120 words.")
    }

    private companion object {
        const val TAG = "JobAiRepository"
        val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
        val FREE_EMAIL_DOMAINS = setOf("gmail", "yahoo", "outlook", "hotmail", "icloud", "proton")
        val GENERIC_EMAIL_LOCAL_PARTS = setOf("hr", "careers", "jobs", "hiring", "talent", "recruitment", "info", "apply")
    }
}
