package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.ApplicationStatus
import com.example.data.model.CandidateProfile
import com.example.data.model.JobApplication
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ApplicationRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeProfile(userId: String): Flow<CandidateProfile?> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch." }
        val path = "users/$uid"
        db.collection("users").document(uid)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        CandidateProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                    handleFirestoreError(exceptionToLog, OperationType.GET, path)
                }
                throw error
            }
            .collect { emit(it) }
    }

    suspend fun ensureDefaultProfile(
        userId: String,
        authEmail: String?,
        authName: String?
    ): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user profile initialization." }
            val docRef = db.collection("users").document(uid)
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                val defaults = CandidateProfile.defaultForUser(uid, authEmail, authName)
                docRef.set(buildProfileCreateMap(uid, defaults)).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun saveProfile(userId: String, profile: CandidateProfile): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user profile write." }
            val safeName = profile.fullName.trim().ifBlank { "Candidate" }.take(120)
            val sanitized = profile.copy(
                userId = uid,
                fullName = safeName,
                email = profile.email.trim().take(150),
                phone = profile.phone.trim().take(40),
                linkedinUrl = profile.linkedinUrl.trim().take(300),
                currentRole = profile.currentRole.trim().take(120),
                totalExperience = profile.totalExperience.trim().take(60),
                currentCtc = profile.currentCtc.trim().take(60),
                expectedCtc = profile.expectedCtc.trim().take(60),
                noticePeriod = profile.noticePeriod.trim().take(60),
                currentLocation = profile.currentLocation.trim().take(120),
                skillsSummary = profile.skillsSummary.trim().take(1000),
                resumeSummary = profile.resumeSummary.trim().take(4000),
                resumeFileName = profile.resumeFileName.trim().take(200),
                preferredSearchQuery = profile.preferredSearchQuery.trim().take(150),
                linkedinJobFilters = profile.linkedinJobFilters
                    .map { it.trim().take(80) }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .take(20)
            )
            val docRef = db.collection("users").document(uid)
            val existing = docRef.get().await()
            if (!existing.exists()) {
                docRef.set(buildProfileCreateMap(uid, sanitized)).await()
            } else {
                docRef.update(
                    mapOf(
                        "fullName" to sanitized.fullName,
                        "email" to sanitized.email,
                        "phone" to sanitized.phone,
                        "linkedinUrl" to sanitized.linkedinUrl,
                        "currentRole" to sanitized.currentRole,
                        "totalExperience" to sanitized.totalExperience,
                        "currentCtc" to sanitized.currentCtc,
                        "expectedCtc" to sanitized.expectedCtc,
                        "noticePeriod" to sanitized.noticePeriod,
                        "currentLocation" to sanitized.currentLocation,
                        "skillsSummary" to sanitized.skillsSummary,
                        "resumeSummary" to sanitized.resumeSummary,
                        "resumeFileName" to sanitized.resumeFileName,
                        "preferredSearchQuery" to sanitized.preferredSearchQuery,
                        "linkedinJobFilters" to sanitized.linkedinJobFilters,
                        "autoFollowUpSend" to sanitized.autoFollowUpSend,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observeApplications(userId: String): Flow<List<JobApplication>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch." }
        val path = "users/$uid/applications"
        db.collection("users").document(uid).collection("applications")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    JobApplication::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.appliedAt ?: it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
            .collect { emit(it) }
    }

    suspend fun createApplication(userId: String, app: JobApplication): Result<String> {
        var path = "users/$userId/applications"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user application write." }
            val company = app.companyName.trim().ifBlank { "Unknown Company" }.take(150)
            val companyNorm = company.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().ifBlank { "unknown" }.take(150)
            val jobTitle = app.jobTitle.trim().ifBlank { "Software Role" }.take(150)
            val recruiterEmail = app.recruiterEmail.trim().lowercase().take(150)
            require(recruiterEmail.length >= 3) { "Recruiter email must be at least 3 characters." }
            val subject = app.emailSubject.trim().ifBlank { "Application for $jobTitle" }.take(250)
            val body = app.emailBody.trim().ifBlank { "Please find my attached CV." }.take(15000)

            val docRef = db.collection("users").document(uid).collection("applications").document()
            path = docRef.path

            val payload = mapOf<String, Any?>(
                "userId" to uid,
                "companyName" to company,
                "companyNormalized" to companyNorm,
                "jobTitle" to jobTitle,
                "recruiterEmail" to recruiterEmail,
                "recruiterName" to app.recruiterName.trim().take(120),
                "location" to app.location.trim().take(150),
                "workMode" to app.workMode.trim().take(60),
                "rawPostText" to app.rawPostText.trim().take(10000),
                "emailSubject" to subject,
                "emailBody" to body,
                "status" to app.statusEnum.name,
                "gmailThreadId" to app.gmailThreadId.trim().take(128),
                "gmailMessageId" to app.gmailMessageId.trim().take(128),
                "sendMethod" to app.sendMethodEnum.name,
                "followUpCount" to app.followUpCount.coerceIn(0L, 50L),
                "lastFollowUpSubject" to app.lastFollowUpSubject.trim().take(250),
                "lastFollowUpNote" to app.lastFollowUpNote.trim().take(5000),
                "appliedAt" to FieldValue.serverTimestamp(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateStatus(
        userId: String,
        applicationId: String,
        newStatus: ApplicationStatus
    ): Result<Unit> {
        val path = "users/$userId/applications/$applicationId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user status update." }
            require(applicationId.isNotBlank()) { "Application ID cannot be blank." }
            db.collection("users").document(uid)
                .collection("applications").document(applicationId)
                .update(
                    mapOf(
                        "status" to newStatus.name,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun recordFollowUp(
        userId: String,
        applicationId: String,
        newFollowUpCount: Long,
        followUpSubject: String,
        followUpNote: String,
        threadId: String,
        messageId: String
    ): Result<Unit> {
        val path = "users/$userId/applications/$applicationId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user follow-up update." }
            require(applicationId.isNotBlank()) { "Application ID cannot be blank." }
            db.collection("users").document(uid)
                .collection("applications").document(applicationId)
                .update(
                    mapOf(
                        "status" to ApplicationStatus.FOLLOW_UP_SENT.name,
                        "followUpCount" to newFollowUpCount.coerceIn(0L, 50L),
                        "lastFollowUpSubject" to followUpSubject.trim().take(250),
                        "lastFollowUpNote" to followUpNote.trim().take(5000),
                        "lastFollowUpAt" to FieldValue.serverTimestamp(),
                        "gmailThreadId" to threadId.trim().take(128),
                        "gmailMessageId" to messageId.trim().take(128),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteApplication(userId: String, applicationId: String): Result<Unit> {
        val path = "users/$userId/applications/$applicationId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Refusing cross-user deletion." }
            require(applicationId.isNotBlank()) { "Application ID cannot be blank." }
            db.collection("users").document(uid)
                .collection("applications").document(applicationId)
                .delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    private fun buildProfileCreateMap(uid: String, profile: CandidateProfile): Map<String, Any> {
        return mapOf(
            "userId" to uid,
            "fullName" to profile.fullName.ifBlank { "Candidate" },
            "email" to profile.email,
            "phone" to profile.phone,
            "linkedinUrl" to profile.linkedinUrl,
            "currentRole" to profile.currentRole,
            "totalExperience" to profile.totalExperience,
            "currentCtc" to profile.currentCtc,
            "expectedCtc" to profile.expectedCtc,
            "noticePeriod" to profile.noticePeriod,
            "currentLocation" to profile.currentLocation,
            "skillsSummary" to profile.skillsSummary,
            "resumeSummary" to profile.resumeSummary,
            "resumeFileName" to profile.resumeFileName,
            "preferredSearchQuery" to profile.preferredSearchQuery,
            "linkedinJobFilters" to profile.linkedinJobFilters,
            "autoFollowUpSend" to profile.autoFollowUpSend,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}
