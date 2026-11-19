package com.example.data.ai

import android.util.Log
import com.example.data.gmail.EmailTemplateFormatter
import com.example.data.model.CandidateProfile
import com.example.data.model.FollowUpDraft
import com.example.data.model.JobApplication
import com.example.data.model.ParsedJobDraft
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
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

        val detectedSkills = detectTechSkills(lowerPost)
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
                "With $expShort of hands-on experience in $skillHighlight, SQL Server, and enterprise REST APIs, I have led scalable backend and full-stack modules in production."
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

    private fun detectTechSkills(lowerPost: String): List<String> {
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
            "sql" to "SQL Server",
            "react" to "React.js",
            "angular" to "Angular",
            "web api" to "REST APIs",
            "rest api" to "REST APIs",
            "azure" to "Azure",
            "aws" to "AWS",
            "microservices" to "Microservices"
        )
        for ((token, label) in catalog) {
            if (lowerPost.contains(token) && label !in matches) {
                matches += label
            }
        }
        return matches
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
            (it.contains("developer", ignoreCase = true) || it.contains("engineer", ignoreCase = true)) && !it.contains("#")
        }
        if (devLine != null && devLine.length <= 70) {
            return devLine.replace(Regex("^[^a-zA-Z0-9.]+"), "").trim()
        }
        if (detectedSkills.any { it.contains(".NET", ignoreCase = true) || it == "C#" || it.contains("MVC") }) {
            return ".NET Developer"
        }
        if (detectedSkills.any { it.contains("React", ignoreCase = true) }) {
            return "React / Full Stack Developer"
        }
        return "Software Developer"
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
        appendLine("You are an expert technical career assistant. Parse the LinkedIn job post below and draft a cleanly formatted job application email.")
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
