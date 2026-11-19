package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class ApplicationStatus(val label: String) {
    APPLIED("Applied"),
    FOLLOW_UP_SENT("Follow-Up Sent"),
    REPLIED("Replied"),
    INTERVIEW("Interview"),
    OFFER("Offer"),
    REJECTED("Rejected");

    companion object {
        fun fromRaw(raw: String): ApplicationStatus =
            entries.firstOrNull { it.name == raw } ?: APPLIED
    }
}

enum class SendMethod {
    GMAIL_API,
    GMAIL_SMTP,
    GMAIL_INTENT;

    companion object {
        fun fromRaw(raw: String): SendMethod =
            entries.firstOrNull { it.name == raw } ?: GMAIL_API
    }
}

@Keep
@IgnoreExtraProperties
data class CandidateProfile(
    val userId: String = "",
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val linkedinUrl: String = "",
    val currentRole: String = "",
    val totalExperience: String = "",
    val currentCtc: String = "",
    val expectedCtc: String = "",
    val noticePeriod: String = "",
    val currentLocation: String = "",
    val skillsSummary: String = "",
    val resumeSummary: String = "",
    val resumeFileName: String = "NareshSaini_Resume.pdf",
    val autoFollowUpSend: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        fun defaultForUser(
            userId: String,
            authEmail: String?,
            authName: String?
        ): CandidateProfile {
            return CandidateProfile(
                userId = userId,
                fullName = authName?.takeIf { it.isNotBlank() } ?: "Naresh Saini",
                email = authEmail?.takeIf { it.isNotBlank() } ?: "nareshsaini868588@gmail.com",
                phone = "+91 8685887957",
                linkedinUrl = "https://www.linkedin.com/in/naresh-saini-3656501a5/",
                currentRole = "Sr. Software Developer (.NET Full Stack)",
                totalExperience = "5+ Years",
                currentCtc = "",
                expectedCtc = "",
                noticePeriod = "30 Days",
                currentLocation = "Hodal (Palwal), Haryana, India",
                skillsSummary = "ASP.NET Core, C#, ASP.NET MVC, REST API, SQL Server, Entity Framework, React.js, Crystal Reports, Azure, AWS",
                resumeSummary = "Sr. Software Developer at Prologic First India Pvt. Ltd. (Nov 2021 - Present) with 5+ years of experience building scalable enterprise web applications in ASP.NET Core, C#, ASP.NET MVC, and SQL Server. Led Touche POS System, Central Order Taking System (React.js + REST APIs), Store Value Card System, and SpaceTrack. Improved SQL stored procedure efficiency by 60-80%, integrated Hotel/GST/Payment APIs, and migrated legacy VB6 modules to .NET Core. Holds MCA (AKTU) and BCA (MDU).",
                resumeFileName = "NareshSaini_Resume.pdf",
                autoFollowUpSend = true
            )
        }
    }
}

@Keep
@IgnoreExtraProperties
data class JobApplication(
    @DocumentId val id: String = "",
    val userId: String = "",
    val companyName: String = "",
    val companyNormalized: String = "",
    val jobTitle: String = "",
    val recruiterEmail: String = "",
    val recruiterName: String = "",
    val location: String = "",
    val workMode: String = "",
    val rawPostText: String = "",
    val emailSubject: String = "",
    val emailBody: String = "",
    val status: String = ApplicationStatus.APPLIED.name,
    val gmailThreadId: String = "",
    val gmailMessageId: String = "",
    val sendMethod: String = SendMethod.GMAIL_API.name,
    val followUpCount: Long = 0L,
    val lastFollowUpSubject: String = "",
    val lastFollowUpNote: String = "",
    @ServerTimestamp val appliedAt: Timestamp? = null,
    val lastFollowUpAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val statusEnum: ApplicationStatus
        get() = ApplicationStatus.fromRaw(status)

    val sendMethodEnum: SendMethod
        get() = SendMethod.fromRaw(sendMethod)
}

data class ParsedJobDraft(
    val companyName: String = "",
    val jobTitle: String = "",
    val recruiterEmail: String = "",
    val recruiterName: String = "",
    val location: String = "",
    val workMode: String = "",
    val requestedDetails: List<String> = emptyList(),
    val emailSubject: String = "",
    val emailBody: String = "",
    val noticeNote: String? = null
)

data class FollowUpDraft(
    val subject: String,
    val body: String
)

data class EmailMessage(
    val uid: String,
    val folder: String, // "INBOX" or "SENT"
    val from: String,
    val to: String,
    val subject: String,
    val date: String,
    val snippet: String,
    val messageId: String = "",
    val isRecruiterRelated: Boolean = false,
    val imapUid: String = "",
    val folderPath: String = ""
)

data class CapturedLinkedInPost(
    val rawText: String,
    val recruiterEmail: String = "",
    val companyName: String = "",
    val jobTitle: String = ""
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
