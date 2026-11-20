package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.JobAiRepository
import com.example.data.gmail.EmailTemplateFormatter
import com.example.data.gmail.GmailService
import com.example.data.gmail.GmailSmtpManager
import com.example.data.gmail.ResumeStorageManager
import com.example.data.model.ApplicationStatus
import com.example.data.model.AutoReplyDraft
import com.example.data.model.CandidateProfile
import com.example.data.model.CapturedLinkedInPost
import com.example.data.model.EmailMessage
import com.example.data.model.FollowUpDraft
import com.example.data.model.JobApplication
import com.example.data.model.SendMethod
import com.example.data.model.UiState
import com.example.data.repository.ApplicationRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class ComposerUiState(
    val rawPostText: String = "",
    val companyName: String = "",
    val jobTitle: String = "",
    val recruiterEmail: String = "",
    val recruiterName: String = "",
    val location: String = "",
    val workMode: String = "",
    val requestedDetails: List<String> = emptyList(),
    val emailSubject: String = "",
    val emailBody: String = "",
    val isExtracting: Boolean = false,
    val isAiPolishing: Boolean = false,
    val isSending: Boolean = false,
    val hasDraft: Boolean = false,
    val extractionError: String? = null,
    val extractionNotice: String? = null,
    val sendError: String? = null,
    val duplicateMatches: List<JobApplication> = emptyList(),
    val gmailAccessToken: String? = null,
    val isSmtpConnected: Boolean = false,
    val smtpEmail: String = "",
    val isVerifyingSmtp: Boolean = false,
    val smtpError: String? = null,
    val showGmailConnectSheet: Boolean = false,
    val oauthErrorBanner: String? = null,
    val isAuthorizingGmail: Boolean = false,
    val activeResumeName: String = ResumeStorageManager.BUNDLED_RESUME_NAME,
    val followUpInProgressId: String? = null,
    val previewFollowUpPair: Pair<JobApplication, FollowUpDraft>? = null,
    val isSyncingReplies: Boolean = false,
    val capturedLinkedInPost: CapturedLinkedInPost? = null,
    val lastLinkedInSentMessage: String? = null
) {
    val isGmailReady: Boolean
        get() = isSmtpConnected || !gmailAccessToken.isNullOrBlank()
}

data class MailboxUiState(
    val selectedFolder: String = "INBOX",
    val messages: List<EmailMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val isSendingEmail: Boolean = false,
    val isDeleting: Boolean = false,
    val selectedUids: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val autoReplyDraft: AutoReplyDraft? = null,
    val isPolishingReply: Boolean = false
)

sealed interface UiEvent {
    data class Snackbar(val message: String, val isError: Boolean = false) : UiEvent
    data class LaunchIntent(val intent: Intent) : UiEvent
    data class ShowEmailSentNotification(
        val toEmail: String,
        val companyName: String,
        val resumeFileName: String
    ) : UiEvent
    data object RequestGmailOAuthDirect : UiEvent
    data object ApplicationSentNavigateTracker : UiEvent
    data object NavigateToApplyTab : UiEvent
}

private enum class QueuedGmailWork {
    NONE,
    SEND_APPLICATION,
    FOLLOW_UP,
    SYNC_REPLIES,
    LOAD_MAILBOX
}

class ApplyFlowViewModel(
    private val repository: ApplicationRepository,
    private val aiRepository: JobAiRepository,
    private val gmailService: GmailService,
    private val smtpManager: GmailSmtpManager,
    private val resumeManager: ResumeStorageManager,
    private val currentUserId: String,
    private val authEmail: String?,
    private val authDisplayName: String?
) : ViewModel() {

    val profileState: StateFlow<UiState<CandidateProfile>> = repository.observeProfile(currentUserId)
        .map<CandidateProfile?, UiState<CandidateProfile>> { profile ->
            UiState.Success(
                profile ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)
            )
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing profile", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load profile"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val applicationsState: StateFlow<UiState<List<JobApplication>>> =
        repository.observeApplications(currentUserId)
            .map<List<JobApplication>, UiState<List<JobApplication>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing applications", rootError)
                emit(UiState.Error(rootError.message ?: "Failed to load applications"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    private val initialSmtp = smtpManager.getCredentials()

    private val _composer = MutableStateFlow(
        ComposerUiState(
            activeResumeName = resumeManager.getActiveResumeFileName(),
            isSmtpConnected = initialSmtp != null,
            smtpEmail = initialSmtp?.email ?: authEmail.orEmpty()
        )
    )
    val composer: StateFlow<ComposerUiState> = _composer.asStateFlow()

    private val _mailbox = MutableStateFlow(MailboxUiState())
    val mailbox: StateFlow<MailboxUiState> = _mailbox.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    private var queuedWork: QueuedGmailWork = QueuedGmailWork.NONE
    private var queuedFollowUpApp: JobApplication? = null
    private var polishJob: Job? = null
    private var replyPolishJob: Job? = null
    private var mailboxLoadJob: Job? = null
    private var userManuallyEditedDraft: Boolean = false
    private var userEditedAutoReply: Boolean = false
    private var stayOnCurrentTabAfterSend: Boolean = false

    init {
        viewModelScope.launch {
            repository.ensureDefaultProfile(currentUserId, authEmail, authDisplayName)
        }
        viewModelScope.launch {
            applicationsState.collect { state ->
                if (state is UiState.Success) {
                    refreshDuplicateCheck(state.data)
                    retagMailboxRecruiters(state.data)
                }
            }
        }
        if (initialSmtp != null) {
            loadMailbox("INBOX")
        }
    }

    fun updateRawPostText(text: String) {
        _composer.update { it.copy(rawPostText = text, extractionError = null, extractionNotice = null) }
    }

    fun loadSampleLinkedInPost() {
        val sample = """
            ✅ Looking for Immediate Joiner only/ or serving Np
            Interested candidate may share their resume at
            subhika.chatterjee@quokkalabs.com
            #dotnet #c# #MVC #entityframework #dotnetcore
            #Immediatejoining #noida
        """.trimIndent()
        _composer.update { it.copy(rawPostText = sample, extractionError = null, extractionNotice = null) }
    }

    fun updateDraftFields(
        companyName: String? = null,
        jobTitle: String? = null,
        recruiterEmail: String? = null,
        recruiterName: String? = null,
        emailSubject: String? = null,
        emailBody: String? = null
    ) {
        userManuallyEditedDraft = true
        _composer.update { current ->
            current.copy(
                companyName = companyName ?: current.companyName,
                jobTitle = jobTitle ?: current.jobTitle,
                recruiterEmail = recruiterEmail ?: current.recruiterEmail,
                recruiterName = recruiterName ?: current.recruiterName,
                emailSubject = emailSubject ?: current.emailSubject,
                emailBody = emailBody ?: current.emailBody,
                sendError = null
            )
        }
        val apps = (applicationsState.value as? UiState.Success)?.data.orEmpty()
        refreshDuplicateCheck(apps)
    }

    fun extractAndDraftWithAi(
        autoSendAfterExtract: Boolean = false,
        stayOnCurrentTab: Boolean = false
    ) {
        val rawText = _composer.value.rawPostText.trim()
        if (rawText.length < 10) {
            _composer.update {
                it.copy(extractionError = "Please paste or tap a LinkedIn job post first.")
            }
            return
        }

        val currentProfile = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)

        // Step 1: Instant (<5ms) structured extraction so the button NEVER hangs
        polishJob?.cancel()
        userManuallyEditedDraft = false
        stayOnCurrentTabAfterSend = stayOnCurrentTab
        val instantDraft = aiRepository.extractAndFormatLocally(rawText, currentProfile)
        _composer.update { state ->
            state.copy(
                isExtracting = false,
                isAiPolishing = !autoSendAfterExtract,
                hasDraft = true,
                extractionError = null,
                sendError = null,
                companyName = instantDraft.companyName,
                jobTitle = instantDraft.jobTitle,
                recruiterEmail = instantDraft.recruiterEmail,
                recruiterName = instantDraft.recruiterName,
                location = instantDraft.location,
                workMode = instantDraft.workMode,
                requestedDetails = instantDraft.requestedDetails,
                emailSubject = instantDraft.emailSubject,
                emailBody = instantDraft.emailBody,
                extractionNotice = "Formatted & ready! Your CV (${state.activeResumeName}) is attached."
            )
        }
        val apps = (applicationsState.value as? UiState.Success)?.data.orEmpty()
        refreshDuplicateCheck(apps)

        if (autoSendAfterExtract) {
            sendApplicationClicked()
            return
        }

        // Step 2: Optional non-blocking Gemini polish (max 8s). Silently skipped if user edits or AI is busy.
        polishJob = viewModelScope.launch {
            val aiResult = aiRepository.polishDraftWithGemini(rawText, currentProfile)
            aiResult.onSuccess { aiDraft ->
                if (!userManuallyEditedDraft && _composer.value.rawPostText.trim() == rawText) {
                    _composer.update { state ->
                        state.copy(
                            isAiPolishing = false,
                            companyName = aiDraft.companyName.ifBlank { state.companyName },
                            jobTitle = aiDraft.jobTitle.ifBlank { state.jobTitle },
                            recruiterEmail = aiDraft.recruiterEmail.ifBlank { state.recruiterEmail },
                            recruiterName = aiDraft.recruiterName.ifBlank { state.recruiterName },
                            location = aiDraft.location.ifBlank { state.location },
                            workMode = aiDraft.workMode.ifBlank { state.workMode },
                            requestedDetails = aiDraft.requestedDetails.ifEmpty { state.requestedDetails },
                            emailSubject = aiDraft.emailSubject.ifBlank { state.emailSubject },
                            emailBody = aiDraft.emailBody.ifBlank { state.emailBody },
                            extractionNotice = "AI-polished format ready! Your CV (${state.activeResumeName}) is attached."
                        )
                    }
                    val latestApps = (applicationsState.value as? UiState.Success)?.data.orEmpty()
                    refreshDuplicateCheck(latestApps)
                } else {
                    _composer.update { it.copy(isAiPolishing = false) }
                }
            }.onFailure {
                _composer.update { it.copy(isAiPolishing = false) }
            }
        }
    }

    fun onLinkedInPostCaptured(rawText: String, explicitEmail: String = "") {
        val cleaned = rawText.trim()
        if (cleaned.length < 15 && explicitEmail.isBlank()) return

        val combinedText = if (explicitEmail.isNotBlank() && !cleaned.contains(explicitEmail, ignoreCase = true)) {
            "$cleaned\n$explicitEmail"
        } else {
            cleaned
        }
        val currentProfile = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)

        val preview = aiRepository.extractAndFormatLocally(combinedText, currentProfile)
        _composer.update {
            it.copy(
                capturedLinkedInPost = CapturedLinkedInPost(
                    rawText = combinedText,
                    recruiterEmail = preview.recruiterEmail.ifBlank { explicitEmail.trim().lowercase() },
                    companyName = preview.companyName,
                    jobTitle = preview.jobTitle
                )
            )
        }
    }

    fun dismissCapturedLinkedInPost() {
        _composer.update { it.copy(capturedLinkedInPost = null) }
    }

    fun applyFromCapturedLinkedInPost(autoSend: Boolean) {
        val captured = _composer.value.capturedLinkedInPost ?: return
        _composer.update {
            it.copy(
                rawPostText = captured.rawText,
                capturedLinkedInPost = null
            )
        }
        // Keep the user inside LinkedIn when using Auto-Send so they can apply to more posts right away
        extractAndDraftWithAi(autoSendAfterExtract = autoSend, stayOnCurrentTab = autoSend)
        if (!autoSend) {
            viewModelScope.launch {
                _events.emit(UiEvent.NavigateToApplyTab)
            }
        }
    }

    fun dismissLinkedInSentBanner() {
        _composer.update { it.copy(lastLinkedInSentMessage = null) }
    }

    fun handleSharedLinkedInText(sharedText: String) {
        _composer.update {
            it.copy(
                rawPostText = sharedText.trim(),
                extractionError = null
            )
        }
        extractAndDraftWithAi(autoSendAfterExtract = false)
        viewModelScope.launch {
            _events.emit(UiEvent.NavigateToApplyTab)
        }
    }

    fun openGmailConnectSheet() {
        _composer.update {
            it.copy(
                showGmailConnectSheet = true,
                smtpError = null,
                oauthErrorBanner = null,
                smtpEmail = it.smtpEmail.ifBlank { authEmail.orEmpty() }
            )
        }
    }

    fun dismissGmailConnectSheet() {
        queuedWork = QueuedGmailWork.NONE
        queuedFollowUpApp = null
        _composer.update { it.copy(showGmailConnectSheet = false, smtpError = null) }
    }

    fun verifyAndConnectSmtp(email: String, appPassword: String) {
        viewModelScope.launch {
            _composer.update { it.copy(isVerifyingSmtp = true, smtpError = null) }
            smtpManager.verifyCredentials(email, appPassword)
                .onSuccess {
                    _composer.update {
                        it.copy(
                            isVerifyingSmtp = false,
                            isSmtpConnected = true,
                            smtpEmail = email.trim(),
                            showGmailConnectSheet = false,
                            smtpError = null,
                            oauthErrorBanner = null,
                            sendError = null
                        )
                    }
                    _events.emit(UiEvent.Snackbar("Gmail connected for Send & Receive!"))
                    runQueuedWork()
                    loadMailbox(_mailbox.value.selectedFolder)
                }
                .onFailure { err ->
                    _composer.update {
                        it.copy(
                            isVerifyingSmtp = false,
                            smtpError = err.localizedMessage ?: "Verification failed. Check Gmail & 16-char App Password."
                        )
                    }
                }
        }
    }

    fun disconnectSmtp() {
        smtpManager.clearCredentials()
        _composer.update {
            it.copy(isSmtpConnected = false, gmailAccessToken = null)
        }
        _mailbox.update { it.copy(messages = emptyList(), error = null) }
        viewModelScope.launch {
            _events.emit(UiEvent.Snackbar("Disconnected Gmail credentials."))
        }
    }

    fun requestOAuthFromSheet() {
        _composer.update { it.copy(oauthErrorBanner = null) }
        viewModelScope.launch {
            _events.emit(UiEvent.RequestGmailOAuthDirect)
        }
    }

    fun setGmailAccessToken(token: String) {
        _composer.update {
            it.copy(
                gmailAccessToken = token,
                isAuthorizingGmail = false,
                showGmailConnectSheet = false,
                oauthErrorBanner = null,
                sendError = null
            )
        }
        runQueuedWork()
        loadMailbox(_mailbox.value.selectedFolder)
    }

    fun setAuthorizingGmail(authorizing: Boolean) {
        _composer.update { it.copy(isAuthorizingGmail = authorizing) }
    }

    fun onGmailOAuthError(message: String) {
        val hint = if (message.contains("access_denied", ignoreCase = true) ||
            message.contains("403", ignoreCase = true) ||
            message.contains("cancelled", ignoreCase = true) ||
            message.contains("10:", ignoreCase = true) ||
            message.contains("verification", ignoreCase = true)
        ) {
            "Google OAuth blocked (Error 403 / Unverified Testing App). Use Option 1 (Gmail App Password) above for instant Send & Receive access with zero 403 errors."
        } else {
            message
        }
        _composer.update {
            it.copy(
                isAuthorizingGmail = false,
                showGmailConnectSheet = true,
                oauthErrorBanner = hint
            )
        }
    }

    private fun runQueuedWork() {
        val work = queuedWork
        val followApp = queuedFollowUpApp
        queuedWork = QueuedGmailWork.NONE
        queuedFollowUpApp = null
        when (work) {
            QueuedGmailWork.NONE -> Unit
            QueuedGmailWork.SEND_APPLICATION -> executeSendApplication()
            QueuedGmailWork.FOLLOW_UP -> if (followApp != null) executeFollowUp(followApp)
            QueuedGmailWork.SYNC_REPLIES -> executeSyncReplies()
            QueuedGmailWork.LOAD_MAILBOX -> loadMailbox(_mailbox.value.selectedFolder)
        }
    }

    fun loadMailbox(folder: String = _mailbox.value.selectedFolder) {
        val smtpCreds = smtpManager.getCredentials()
        val oauthToken = _composer.value.gmailAccessToken

        mailboxLoadJob?.cancel()

        if (smtpCreds == null && oauthToken.isNullOrBlank()) {
            _mailbox.update {
                it.copy(
                    selectedFolder = folder,
                    isLoading = false,
                    isRefreshing = false,
                    messages = emptyList(),
                    selectedUids = emptySet(),
                    isSelectionMode = false,
                    autoReplyDraft = null
                )
            }
            return
        }

        val switchingFolder = !_mailbox.value.selectedFolder.equals(folder, ignoreCase = true)
        val hasExisting = !switchingFolder && _mailbox.value.messages.isNotEmpty()
        mailboxLoadJob = viewModelScope.launch {
            _mailbox.update {
                it.copy(
                    selectedFolder = folder,
                    isLoading = !hasExisting,
                    isRefreshing = hasExisting,
                    error = null,
                    messages = if (switchingFolder) emptyList() else it.messages,
                    selectedUids = if (switchingFolder) emptySet() else it.selectedUids,
                    isSelectionMode = if (switchingFolder) false else it.isSelectionMode,
                    autoReplyDraft = if (switchingFolder) null else it.autoReplyDraft
                )
            }
            try {
                val result = withTimeout(25_000L) {
                    if (smtpCreds != null) {
                        smtpManager.fetchRecentEmailsViaImap(smtpCreds, mailbox = folder, limit = 25)
                    } else {
                        gmailService.fetchRecentMessages(oauthToken.orEmpty(), mailbox = folder, maxResults = 25)
                    }
                }
                result.onSuccess { list ->
                    val apps = (applicationsState.value as? UiState.Success)?.data.orEmpty()
                    val trackedEmails = apps.map { it.recruiterEmail.trim().lowercase() }
                        .filter { it.isNotBlank() }
                        .toSet()

                    val tagged = list.map { msg ->
                        msg.copy(isRecruiterRelated = isLikelyRecruiterMessage(msg, trackedEmails))
                    }
                    _mailbox.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            messages = tagged,
                            error = null
                        )
                    }

                    // Auto-sync REPLIED status if Inbox shows an incoming message from any applied recruiter
                    if (folder.equals("INBOX", ignoreCase = true) && apps.isNotEmpty()) {
                        for (app in apps) {
                            if ((app.statusEnum == ApplicationStatus.APPLIED || app.statusEnum == ApplicationStatus.FOLLOW_UP_SENT) &&
                                app.recruiterEmail.isNotBlank() &&
                                tagged.any { it.from.contains(app.recruiterEmail, ignoreCase = true) }
                            ) {
                                repository.updateStatus(currentUserId, app.id, ApplicationStatus.REPLIED)
                            }
                        }
                    }
                }.onFailure { err ->
                    _mailbox.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = err.localizedMessage ?: "Could not load $folder emails."
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                _mailbox.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = "Timed out loading $folder. Check your internet connection and tap Refresh."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _mailbox.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = e.localizedMessage ?: "Could not load $folder emails."
                    )
                }
            }
        }
    }

    fun onMailboxMessageOpened(msg: EmailMessage) {
        replyPolishJob?.cancel()
        userEditedAutoReply = false

        if (!msg.folder.equals("INBOX", ignoreCase = true)) {
            _mailbox.update { it.copy(autoReplyDraft = null, isPolishingReply = false) }
            return
        }

        val currentProfile = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)
        val trackedApps = (applicationsState.value as? UiState.Success)?.data.orEmpty()

        val instantDraft = aiRepository.extractAutoReplyLocally(msg, currentProfile, trackedApps)
        _mailbox.update {
            it.copy(
                autoReplyDraft = instantDraft,
                isPolishingReply = true
            )
        }

        val matchedApp = trackedApps.firstOrNull { app ->
            (instantDraft.toEmail.isNotBlank() && app.recruiterEmail.equals(instantDraft.toEmail, ignoreCase = true)) ||
                (app.emailSubject.isNotBlank() && msg.subject.contains(app.emailSubject.take(24), ignoreCase = true))
        }
        if (matchedApp != null &&
            (matchedApp.statusEnum == ApplicationStatus.APPLIED || matchedApp.statusEnum == ApplicationStatus.FOLLOW_UP_SENT)
        ) {
            viewModelScope.launch {
                repository.updateStatus(currentUserId, matchedApp.id, ApplicationStatus.REPLIED)
            }
        }

        replyPolishJob = viewModelScope.launch {
            aiRepository.polishAutoReplyWithGemini(msg, currentProfile, matchedApp)
                .onSuccess { aiDraft ->
                    if (!userEditedAutoReply) {
                        _mailbox.update { state ->
                            if (state.autoReplyDraft != null) {
                                state.copy(
                                    autoReplyDraft = aiDraft.copy(
                                        inReplyTo = aiDraft.inReplyTo ?: state.autoReplyDraft.inReplyTo
                                    ),
                                    isPolishingReply = false
                                )
                            } else {
                                state.copy(isPolishingReply = false)
                            }
                        }
                    } else {
                        _mailbox.update { it.copy(isPolishingReply = false) }
                    }
                }
                .onFailure {
                    _mailbox.update { it.copy(isPolishingReply = false) }
                }
        }
    }

    fun updateAutoReplyDraft(
        subject: String? = null,
        body: String? = null,
        attachResume: Boolean? = null
    ) {
        userEditedAutoReply = true
        _mailbox.update { state ->
            val current = state.autoReplyDraft ?: return@update state
            state.copy(
                autoReplyDraft = current.copy(
                    subject = subject ?: current.subject,
                    body = body ?: current.body,
                    attachResume = attachResume ?: current.attachResume
                )
            )
        }
    }

    fun clearAutoReplyDraft() {
        replyPolishJob?.cancel()
        _mailbox.update { it.copy(autoReplyDraft = null, isPolishingReply = false) }
    }

    fun sendAutoReplyInOneClick(msg: EmailMessage) {
        val currentProfile = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)
        val trackedApps = (applicationsState.value as? UiState.Success)?.data.orEmpty()
        val draft = _mailbox.value.autoReplyDraft
            ?: aiRepository.extractAutoReplyLocally(msg, currentProfile, trackedApps)

        sendDirectEmail(
            toEmail = draft.toEmail,
            subject = draft.subject,
            body = draft.body,
            attachResume = draft.attachResume,
            inReplyToHeader = draft.inReplyTo ?: msg.messageId.takeIf { it.isNotBlank() }
        )
    }

    private fun retagMailboxRecruiters(apps: List<JobApplication>) {
        val currentMessages = _mailbox.value.messages
        if (currentMessages.isEmpty()) return
        val trackedEmails = apps.map { it.recruiterEmail.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val updated = currentMessages.map { msg ->
            msg.copy(isRecruiterRelated = isLikelyRecruiterMessage(msg, trackedEmails))
        }
        _mailbox.update { it.copy(messages = updated) }
    }

    private fun isLikelyRecruiterMessage(msg: EmailMessage, trackedEmails: Set<String>): Boolean {
        val matchedTracked = trackedEmails.any { addr ->
            msg.from.contains(addr, ignoreCase = true) || msg.to.contains(addr, ignoreCase = true)
        }
        if (matchedTracked) return true
        val text = "${msg.from} ${msg.subject} ${msg.snippet}".lowercase()
        return listOf(
            "recruiter", "hiring", "interview", "shortlist", "current ctc", "expected ctc",
            "notice period", "updated resume", "updated cv", "job opening", "hr@",
            "talent@", "careers@", "jobs@"
        ).any { text.contains(it) }
    }

    fun enterSelectionMode(initialUid: String? = null) {
        _mailbox.update { state ->
            val updated = if (initialUid != null) state.selectedUids + initialUid else state.selectedUids
            state.copy(isSelectionMode = true, selectedUids = updated)
        }
    }

    fun toggleMessageSelection(uid: String) {
        _mailbox.update { state ->
            val next = if (uid in state.selectedUids) {
                state.selectedUids - uid
            } else {
                state.selectedUids + uid
            }
            state.copy(
                selectedUids = next,
                isSelectionMode = next.isNotEmpty() || state.isSelectionMode
            )
        }
    }

    fun selectAllMessages(uids: List<String>) {
        _mailbox.update { state ->
            val allSelected = uids.isNotEmpty() && uids.all { it in state.selectedUids }
            if (allSelected) {
                state.copy(selectedUids = emptySet(), isSelectionMode = true)
            } else {
                state.copy(selectedUids = uids.toSet(), isSelectionMode = true)
            }
        }
    }

    fun clearMessageSelection() {
        _mailbox.update { it.copy(selectedUids = emptySet(), isSelectionMode = false) }
    }

    fun deleteSelectedEmails() {
        val current = _mailbox.value
        val toDelete = current.messages.filter { it.uid in current.selectedUids }
        if (toDelete.isEmpty()) return
        deleteMessagesInternal(toDelete)
    }

    fun deleteSingleEmail(msg: EmailMessage) {
        deleteMessagesInternal(listOf(msg))
    }

    private fun deleteMessagesInternal(targets: List<EmailMessage>) {
        if (_mailbox.value.isDeleting) return
        val smtpCreds = smtpManager.getCredentials()
        val oauthToken = _composer.value.gmailAccessToken
        if (smtpCreds == null && oauthToken.isNullOrBlank()) {
            openGmailConnectSheet()
            return
        }

        viewModelScope.launch {
            _mailbox.update { it.copy(isDeleting = true, error = null) }
            try {
                val res = withTimeout(25_000L) {
                    if (smtpCreds != null) {
                        val imapUids = targets.mapNotNull { msg ->
                            msg.imapUid.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                        }
                        val folderHint = targets.firstOrNull { it.folderPath.isNotBlank() }?.folderPath
                        smtpManager.deleteEmailsViaImap(
                            credentials = smtpCreds,
                            mailbox = _mailbox.value.selectedFolder,
                            folderPathHint = folderHint,
                            imapUids = imapUids
                        )
                    } else {
                        gmailService.trashMessages(
                            accessToken = oauthToken.orEmpty(),
                            messageIds = targets.map { it.uid }
                        )
                    }
                }

                res.onSuccess { count ->
                    val deletedUidSet = targets.map { it.uid }.toSet()
                    _mailbox.update { state ->
                        state.copy(
                            isDeleting = false,
                            messages = state.messages.filterNot { it.uid in deletedUidSet },
                            selectedUids = state.selectedUids - deletedUidSet,
                            isSelectionMode = false
                        )
                    }
                    _events.emit(
                        UiEvent.Snackbar("Moved $count email${if (count == 1) "" else "s"} to Gmail Trash.")
                    )
                }.onFailure { err ->
                    _mailbox.update { it.copy(isDeleting = false) }
                    _events.emit(
                        UiEvent.Snackbar(err.localizedMessage ?: "Failed to delete email(s).", isError = true)
                    )
                }
            } catch (_: TimeoutCancellationException) {
                _mailbox.update { it.copy(isDeleting = false) }
                _events.emit(UiEvent.Snackbar("Delete timed out. Check connection and try again.", isError = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _mailbox.update { it.copy(isDeleting = false) }
                _events.emit(UiEvent.Snackbar(e.localizedMessage ?: "Failed to delete email(s).", isError = true))
            }
        }
    }

    fun sendDirectEmail(
        toEmail: String,
        subject: String,
        body: String,
        attachResume: Boolean,
        inReplyToHeader: String? = null
    ) {
        val cleanTo = toEmail.trim()
        if (!cleanTo.contains("@")) {
            viewModelScope.launch { _events.emit(UiEvent.Snackbar("Enter a valid recipient email.", isError = true)) }
            return
        }
        if (!_composer.value.isGmailReady) {
            openGmailConnectSheet()
            return
        }

        val currentProfile = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)

        viewModelScope.launch {
            _mailbox.update { it.copy(isSendingEmail = true) }
            try {
                val (fileName, pdfBytes) = if (attachResume) {
                    resumeManager.getActiveResumeBytes()
                } else {
                    null to null
                }
                val smtpCreds = smtpManager.getCredentials()
                val oauthToken = _composer.value.gmailAccessToken

                val normalizedBody = EmailTemplateFormatter.normalizePlainText(body)
                val htmlBody = EmailTemplateFormatter.toStyledHtml(
                    subject = subject.trim(),
                    plainBody = normalizedBody,
                    profile = currentProfile,
                    attachmentFileName = fileName
                )

                val res = if (smtpCreds != null) {
                    val msgId = GmailSmtpManager.generateMessageId()
                    val raw = gmailService.buildRawMimeString(
                        toEmail = cleanTo,
                        subject = subject.trim(),
                        body = normalizedBody,
                        fromEmail = smtpCreds.email,
                        attachmentFileName = fileName,
                        attachmentBytes = pdfBytes,
                        messageIdHeader = msgId,
                        inReplyToHeader = inReplyToHeader,
                        htmlBody = htmlBody
                    )
                    smtpManager.sendEmail(smtpCreds, cleanTo, raw, msgId)
                } else {
                    val rawB64 = gmailService.buildMimeMessageBase64Url(
                        toEmail = cleanTo,
                        subject = subject.trim(),
                        body = normalizedBody,
                        fromEmail = currentProfile.email.ifBlank { authEmail },
                        attachmentFileName = fileName,
                        attachmentBytes = pdfBytes,
                        inReplyToHeader = inReplyToHeader,
                        htmlBody = htmlBody
                    )
                    gmailService.sendEmail(oauthToken.orEmpty(), rawB64)
                }

                res.onSuccess {
                    _mailbox.update { it.copy(isSendingEmail = false) }
                    _events.emit(UiEvent.Snackbar("Email sent to $cleanTo!"))
                    if (_mailbox.value.selectedFolder == "SENT") {
                        loadMailbox("SENT")
                    }
                }.onFailure { err ->
                    _mailbox.update { it.copy(isSendingEmail = false) }
                    _events.emit(UiEvent.Snackbar(err.localizedMessage ?: "Failed to send email.", isError = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _mailbox.update { it.copy(isSendingEmail = false) }
                _events.emit(UiEvent.Snackbar(e.localizedMessage ?: "Failed to send email.", isError = true))
            }
        }
    }

    fun sendApplicationClicked() {
        val state = _composer.value
        val email = state.recruiterEmail.trim()
        if (!email.contains("@") || email.length < 5) {
            _composer.update { it.copy(sendError = "Please enter a valid recruiter email address.") }
            return
        }
        if (state.emailSubject.isBlank() || state.emailBody.isBlank()) {
            _composer.update { it.copy(sendError = "Subject and email body cannot be empty.") }
            return
        }
        if (!state.isGmailReady) {
            queuedWork = QueuedGmailWork.SEND_APPLICATION
            openGmailConnectSheet()
        } else {
            executeSendApplication()
        }
    }

    fun executeSendApplication() {
        val state = _composer.value
        val currentProfile = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)

        viewModelScope.launch {
            _composer.update { it.copy(isSending = true, sendError = null) }
            try {
                val (fileName, pdfBytes) = resumeManager.getActiveResumeBytes()
                val smtpCreds = smtpManager.getCredentials()
                val oauthToken = state.gmailAccessToken
                val cleanBody = EmailTemplateFormatter.normalizePlainText(state.emailBody)
                val htmlBody = EmailTemplateFormatter.toStyledHtml(
                    subject = state.emailSubject.trim(),
                    plainBody = cleanBody,
                    profile = currentProfile,
                    attachmentFileName = fileName,
                    roleBadge = state.jobTitle.ifBlank { currentProfile.currentRole }
                )

                val sendResult: Result<Pair<com.example.data.gmail.GmailSendResult, SendMethod>> =
                    if (smtpCreds != null) {
                        val msgId = GmailSmtpManager.generateMessageId()
                        val rawMime = gmailService.buildRawMimeString(
                            toEmail = state.recruiterEmail.trim(),
                            subject = state.emailSubject.trim(),
                            body = cleanBody,
                            fromEmail = smtpCreds.email,
                            attachmentFileName = fileName,
                            attachmentBytes = pdfBytes,
                            messageIdHeader = msgId,
                            htmlBody = htmlBody
                        )
                        smtpManager.sendEmail(
                            credentials = smtpCreds,
                            toEmail = state.recruiterEmail.trim(),
                            rawMimeContent = rawMime,
                            messageIdHeader = msgId
                        ).map { it to SendMethod.GMAIL_SMTP }
                    } else if (!oauthToken.isNullOrBlank()) {
                        val rawMimeB64 = gmailService.buildMimeMessageBase64Url(
                            toEmail = state.recruiterEmail.trim(),
                            subject = state.emailSubject.trim(),
                            body = cleanBody,
                            fromEmail = currentProfile.email.ifBlank { authEmail },
                            attachmentFileName = fileName,
                            attachmentBytes = pdfBytes,
                            htmlBody = htmlBody
                        )
                        gmailService.sendEmail(oauthToken, rawMimeB64)
                            .map { it to SendMethod.GMAIL_API }
                    } else {
                        queuedWork = QueuedGmailWork.SEND_APPLICATION
                        _composer.update { it.copy(isSending = false, showGmailConnectSheet = true) }
                        return@launch
                    }

                sendResult.onSuccess { (gmailRes, method) ->
                    val newApp = JobApplication(
                        userId = currentUserId,
                        companyName = state.companyName.ifBlank { inferCompanyFromEmail(state.recruiterEmail) },
                        jobTitle = state.jobTitle.ifBlank { currentProfile.currentRole.ifBlank { "Open Position" } },
                        recruiterEmail = state.recruiterEmail.trim().lowercase(),
                        recruiterName = state.recruiterName.trim(),
                        location = state.location.trim(),
                        workMode = state.workMode.trim(),
                        rawPostText = state.rawPostText.trim(),
                        emailSubject = state.emailSubject.trim(),
                        emailBody = cleanBody,
                        status = ApplicationStatus.APPLIED.name,
                        gmailThreadId = gmailRes.threadId,
                        gmailMessageId = gmailRes.messageId,
                        sendMethod = method.name,
                        followUpCount = 0L
                    )
                    repository.createApplication(currentUserId, newApp)
                        .onSuccess {
                            val sentToEmail = state.recruiterEmail.trim()
                            val sentCompany = newApp.companyName
                            val keepTab = stayOnCurrentTabAfterSend
                            stayOnCurrentTabAfterSend = false

                            _composer.update {
                                it.copy(
                                    isSending = false,
                                    hasDraft = false,
                                    rawPostText = "",
                                    companyName = "",
                                    jobTitle = "",
                                    recruiterEmail = "",
                                    recruiterName = "",
                                    location = "",
                                    workMode = "",
                                    requestedDetails = emptyList(),
                                    emailSubject = "",
                                    emailBody = "",
                                    extractionNotice = null,
                                    duplicateMatches = emptyList(),
                                    lastLinkedInSentMessage = if (keepTab) {
                                        "Email sent to $sentToEmail ($sentCompany) with $fileName!"
                                    } else {
                                        it.lastLinkedInSentMessage
                                    }
                                )
                            }
                            _events.emit(
                                UiEvent.ShowEmailSentNotification(
                                    toEmail = sentToEmail,
                                    companyName = sentCompany,
                                    resumeFileName = fileName
                                )
                            )
                            _events.emit(
                                UiEvent.Snackbar(
                                    "Email sent to $sentToEmail with $fileName & saved to Tracker!"
                                )
                            )
                            if (!keepTab) {
                                _events.emit(UiEvent.ApplicationSentNavigateTracker)
                            }
                        }
                        .onFailure { dbErr ->
                            _composer.update {
                                it.copy(
                                    isSending = false,
                                    sendError = "Email sent via Gmail, but saving tracker record failed: ${dbErr.localizedMessage}"
                                )
                            }
                        }
                }.onFailure { mailErr ->
                    val errMsg = mailErr.localizedMessage ?: "Failed to send email."
                    val tokenExpired = errMsg.contains("expired", ignoreCase = true)
                    _composer.update {
                        it.copy(
                            isSending = false,
                            sendError = errMsg,
                            gmailAccessToken = if (tokenExpired) null else it.gmailAccessToken
                        )
                    }
                    _events.emit(UiEvent.Snackbar(errMsg, isError = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected send error", e)
                val msg = e.localizedMessage ?: "Failed to send application."
                _composer.update { it.copy(isSending = false, sendError = msg) }
            }
        }
    }

    fun openInGmailAppFallback(context: Context) {
        val state = _composer.value
        val email = state.recruiterEmail.trim()
        if (email.isBlank()) {
            viewModelScope.launch { _events.emit(UiEvent.Snackbar("Enter recruiter email first.", isError = true)) }
            return
        }
        viewModelScope.launch {
            try {
                val uri = resumeManager.getShareableResumeUri()
                val intent = gmailService.buildComposeFallbackIntent(
                    context = context,
                    toEmail = email,
                    subject = state.emailSubject.trim(),
                    body = state.emailBody.trim(),
                    attachmentUri = uri
                )
                val fallbackRole = (profileState.value as? UiState.Success)?.data?.currentRole?.takeIf { it.isNotBlank() }
                    ?: "Open Position"
                val newApp = JobApplication(
                    userId = currentUserId,
                    companyName = state.companyName.ifBlank { inferCompanyFromEmail(email) },
                    jobTitle = state.jobTitle.ifBlank { fallbackRole },
                    recruiterEmail = email.lowercase(),
                    recruiterName = state.recruiterName.trim(),
                    location = state.location.trim(),
                    workMode = state.workMode.trim(),
                    rawPostText = state.rawPostText.trim(),
                    emailSubject = state.emailSubject.trim(),
                    emailBody = state.emailBody.trim(),
                    status = ApplicationStatus.APPLIED.name,
                    sendMethod = SendMethod.GMAIL_INTENT.name,
                    followUpCount = 0L
                )
                repository.createApplication(currentUserId, newApp)
                _events.emit(UiEvent.LaunchIntent(intent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(UiEvent.Snackbar(e.localizedMessage ?: "Failed to open Gmail app.", isError = true))
            }
        }
    }

    fun onFollowUpButtonClicked(application: JobApplication) {
        if (!_composer.value.isGmailReady) {
            queuedWork = QueuedGmailWork.FOLLOW_UP
            queuedFollowUpApp = application
            openGmailConnectSheet()
        } else {
            executeFollowUp(application)
        }
    }

    fun executeFollowUp(application: JobApplication) {
        val currentProfile = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)

        viewModelScope.launch {
            _composer.update { it.copy(followUpInProgressId = application.id) }
            try {
                val draftResult = aiRepository.draftFollowUpEmail(application, currentProfile)
                val draft = draftResult.getOrElse { err ->
                    _composer.update { it.copy(followUpInProgressId = null) }
                    _events.emit(UiEvent.Snackbar(err.localizedMessage ?: "Follow-up draft failed.", isError = true))
                    return@launch
                }

                if (!currentProfile.autoFollowUpSend) {
                    _composer.update {
                        it.copy(
                            followUpInProgressId = null,
                            previewFollowUpPair = application to draft
                        )
                    }
                    return@launch
                }

                sendFollowUpInternal(application, draft, currentProfile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _composer.update { it.copy(followUpInProgressId = null) }
                _events.emit(UiEvent.Snackbar(e.localizedMessage ?: "Follow-up failed.", isError = true))
            }
        }
    }

    fun confirmAndSendPreviewFollowUp(
        application: JobApplication,
        subject: String,
        body: String
    ) {
        if (!_composer.value.isGmailReady) {
            _composer.update { it.copy(previewFollowUpPair = null) }
            queuedWork = QueuedGmailWork.FOLLOW_UP
            queuedFollowUpApp = application
            openGmailConnectSheet()
            return
        }
        val currentProfile = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)
        viewModelScope.launch {
            _composer.update { it.copy(previewFollowUpPair = null, followUpInProgressId = application.id) }
            sendFollowUpInternal(application, FollowUpDraft(subject, body), currentProfile)
        }
    }

    fun dismissFollowUpPreview() {
        _composer.update { it.copy(previewFollowUpPair = null) }
    }

    private suspend fun sendFollowUpInternal(
        application: JobApplication,
        draft: FollowUpDraft,
        profile: CandidateProfile
    ) {
        val (fileName, pdfBytes) = resumeManager.getActiveResumeBytes()
        val smtpCreds = smtpManager.getCredentials()
        val oauthToken = _composer.value.gmailAccessToken
        val cleanFollowUpBody = EmailTemplateFormatter.normalizePlainText(draft.body)
        val htmlFollowUp = EmailTemplateFormatter.toStyledHtml(
            subject = draft.subject,
            plainBody = cleanFollowUpBody,
            profile = profile,
            attachmentFileName = fileName,
            roleBadge = "Follow-Up • ${application.jobTitle}"
        )

        val sendResult = if (smtpCreds != null) {
            val newMsgId = GmailSmtpManager.generateMessageId()
            val refHeader = application.gmailMessageId.takeIf { it.startsWith("<") }
                ?: application.gmailThreadId.takeIf { it.startsWith("<") }
            val rawMime = gmailService.buildRawMimeString(
                toEmail = application.recruiterEmail,
                subject = draft.subject,
                body = cleanFollowUpBody,
                fromEmail = smtpCreds.email,
                attachmentFileName = fileName,
                attachmentBytes = pdfBytes,
                messageIdHeader = newMsgId,
                inReplyToHeader = refHeader,
                htmlBody = htmlFollowUp
            )
            smtpManager.sendEmail(
                credentials = smtpCreds,
                toEmail = application.recruiterEmail,
                rawMimeContent = rawMime,
                messageIdHeader = newMsgId
            )
        } else if (!oauthToken.isNullOrBlank()) {
            val rfcMsgId = gmailService.fetchRfcMessageId(oauthToken, application.gmailMessageId)
            val rawMimeB64 = gmailService.buildMimeMessageBase64Url(
                toEmail = application.recruiterEmail,
                subject = draft.subject,
                body = cleanFollowUpBody,
                fromEmail = profile.email.ifBlank { authEmail },
                attachmentFileName = fileName,
                attachmentBytes = pdfBytes,
                inReplyToHeader = rfcMsgId,
                htmlBody = htmlFollowUp
            )
            gmailService.sendEmail(
                accessToken = oauthToken,
                rawBase64Url = rawMimeB64,
                threadId = application.gmailThreadId.takeIf { it.isNotBlank() && !it.startsWith("<") }
            )
        } else {
            _composer.update { it.copy(followUpInProgressId = null, showGmailConnectSheet = true) }
            return
        }

        sendResult.onSuccess { res ->
            repository.recordFollowUp(
                userId = currentUserId,
                applicationId = application.id,
                newFollowUpCount = application.followUpCount + 1L,
                followUpSubject = draft.subject,
                followUpNote = draft.body,
                threadId = application.gmailThreadId.ifBlank { res.threadId },
                messageId = res.messageId
            ).onSuccess {
                _composer.update { it.copy(followUpInProgressId = null) }
                _events.emit(UiEvent.Snackbar("Follow-up sent to ${application.companyName}!"))
            }.onFailure { dbErr ->
                _composer.update { it.copy(followUpInProgressId = null) }
                _events.emit(UiEvent.Snackbar("Follow-up emailed, but status update failed: ${dbErr.localizedMessage}", isError = true))
            }
        }.onFailure { err ->
            val msg = err.localizedMessage ?: "Failed to send follow-up."
            val expired = msg.contains("expired", ignoreCase = true)
            _composer.update {
                it.copy(
                    followUpInProgressId = null,
                    gmailAccessToken = if (expired) null else it.gmailAccessToken
                )
            }
            _events.emit(UiEvent.Snackbar(msg, isError = true))
        }
    }

    fun syncRepliesClicked() {
        if (!_composer.value.isGmailReady) {
            queuedWork = QueuedGmailWork.SYNC_REPLIES
            openGmailConnectSheet()
        } else {
            executeSyncReplies()
        }
    }

    fun executeSyncReplies() {
        val apps = (applicationsState.value as? UiState.Success)?.data.orEmpty()
        val candidates = apps.filter {
            it.statusEnum == ApplicationStatus.APPLIED || it.statusEnum == ApplicationStatus.FOLLOW_UP_SENT
        }
        if (candidates.isEmpty()) {
            viewModelScope.launch {
                _events.emit(UiEvent.Snackbar("No pending applications to check for replies."))
            }
            return
        }

        val smtpCreds = smtpManager.getCredentials()
        val oauthToken = _composer.value.gmailAccessToken
        val myEmail = (profileState.value as? UiState.Success)?.data?.email?.ifBlank { authEmail } ?: authEmail

        viewModelScope.launch {
            _composer.update { it.copy(isSyncingReplies = true) }
            var repliesFound = 0
            try {
                for (app in candidates) {
                    val hasReply = when {
                        smtpCreds != null -> {
                            smtpManager.checkReplyViaImap(
                                credentials = smtpCreds,
                                recruiterEmail = app.recruiterEmail,
                                originalMessageId = app.gmailMessageId
                            ).getOrDefault(false)
                        }
                        !oauthToken.isNullOrBlank() && app.gmailThreadId.isNotBlank() && !app.gmailThreadId.startsWith("<") -> {
                            gmailService.checkThreadHasReply(oauthToken, app.gmailThreadId, myEmail)
                                .getOrDefault(false)
                        }
                        else -> false
                    }
                    if (hasReply) {
                        repository.updateStatus(currentUserId, app.id, ApplicationStatus.REPLIED)
                        repliesFound++
                    }
                }
                val msg = if (repliesFound > 0) {
                    "Synced Gmail: $repliesFound recruiter repl${if (repliesFound == 1) "y" else "ies"} found!"
                } else {
                    "Checked ${candidates.size} applications — no new recruiter replies yet."
                }
                _events.emit(UiEvent.Snackbar(msg))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(UiEvent.Snackbar(e.localizedMessage ?: "Reply sync failed.", isError = true))
            } finally {
                _composer.update { it.copy(isSyncingReplies = false) }
            }
        }
    }

    fun updateApplicationStatus(applicationId: String, status: ApplicationStatus) {
        viewModelScope.launch {
            repository.updateStatus(currentUserId, applicationId, status)
                .onSuccess {
                    _events.emit(UiEvent.Snackbar("Status updated to ${status.label}"))
                }
                .onFailure { err ->
                    _events.emit(UiEvent.Snackbar(err.localizedMessage ?: "Status update failed.", isError = true))
                }
        }
    }

    fun deleteApplication(applicationId: String) {
        viewModelScope.launch {
            repository.deleteApplication(currentUserId, applicationId)
                .onSuccess { _events.emit(UiEvent.Snackbar("Application removed.")) }
                .onFailure { err ->
                    _events.emit(UiEvent.Snackbar(err.localizedMessage ?: "Delete failed.", isError = true))
                }
        }
    }

    fun saveProfile(updated: CandidateProfile) {
        val current = (profileState.value as? UiState.Success)?.data
        val cleanedFilters = updated.linkedinJobFilters
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val nextQuery = when {
            cleanedFilters.isNotEmpty() -> cleanedFilters.first()
            current != null && !updated.currentRole.equals(current.currentRole, ignoreCase = true) -> ""
            else -> updated.preferredSearchQuery
        }
        val toSave = updated.copy(
            preferredSearchQuery = nextQuery,
            linkedinJobFilters = cleanedFilters
        )
        viewModelScope.launch {
            repository.saveProfile(currentUserId, toSave)
                .onSuccess { _events.emit(UiEvent.Snackbar("Profile & LinkedIn filters saved!")) }
                .onFailure { err ->
                    _events.emit(UiEvent.Snackbar(err.localizedMessage ?: "Failed to save profile.", isError = true))
                }
        }
    }

    fun updateLinkedInJobFilters(filters: List<String>) {
        val current = (profileState.value as? UiState.Success)?.data
            ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)
        val cleaned = filters
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val updated = current.copy(
            linkedinJobFilters = cleaned,
            preferredSearchQuery = cleaned.firstOrNull() ?: current.preferredSearchQuery
        )
        viewModelScope.launch {
            repository.saveProfile(currentUserId, updated)
                .onSuccess { _events.emit(UiEvent.Snackbar("LinkedIn job filters updated!")) }
                .onFailure { err ->
                    _events.emit(UiEvent.Snackbar(err.localizedMessage ?: "Failed to save filters.", isError = true))
                }
        }
    }

    fun importResumePdf(uri: Uri) {
        viewModelScope.launch {
            resumeManager.importPdfFromUri(uri)
                .onSuccess { fileName ->
                    _composer.update { it.copy(activeResumeName = fileName) }
                    val (_, pdfBytes) = resumeManager.getActiveResumeBytes()
                    val extractedText = resumeManager.extractReadableTextFromPdfBytes(pdfBytes)

                    val baseProfile = (profileState.value as? UiState.Success)?.data
                        ?: CandidateProfile.defaultForUser(currentUserId, authEmail, authDisplayName)

                    val localInsights = aiRepository.extractResumeInsightsLocally(
                        fileName = fileName,
                        extractedPdfText = extractedText,
                        fallbackProfile = baseProfile
                    )

                    val matchedQuery = localInsights.preferredSearchQuery.ifBlank { localInsights.targetRole }
                    val mergedFilters = if (matchedQuery.isNotBlank()) {
                        (listOf(matchedQuery) + baseProfile.linkedinJobFilters)
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinctBy { it.lowercase() }
                    } else {
                        baseProfile.linkedinJobFilters
                    }

                    val updatedLocal = baseProfile.copy(
                        resumeFileName = fileName,
                        currentRole = localInsights.targetRole.ifBlank { baseProfile.currentRole },
                        preferredSearchQuery = matchedQuery,
                        linkedinJobFilters = mergedFilters,
                        skillsSummary = localInsights.skillsSummary.ifBlank { baseProfile.skillsSummary },
                        totalExperience = localInsights.totalExperience.ifBlank { baseProfile.totalExperience },
                        resumeSummary = localInsights.resumeSummary.ifBlank { baseProfile.resumeSummary },
                        phone = baseProfile.phone.ifBlank { localInsights.phone },
                        linkedinUrl = baseProfile.linkedinUrl.ifBlank { localInsights.linkedinUrl }
                    )
                    repository.saveProfile(currentUserId, updatedLocal)

                    val matchedLabel = updatedLocal.preferredSearchQuery.ifBlank { updatedLocal.currentRole }
                    _events.emit(
                        UiEvent.Snackbar("Resume read ($fileName) • Matched jobs: $matchedLabel")
                    )

                    // Optional non-blocking Gemini multimodal PDF refinement for richer role/summary matching
                    launch {
                        aiRepository.analyzeResumePdfWithGemini(fileName, pdfBytes, extractedText)
                            .onSuccess { aiInsights ->
                                val latestProfile = (profileState.value as? UiState.Success)?.data ?: updatedLocal
                                val aiQuery = aiInsights.preferredSearchQuery.ifBlank { latestProfile.preferredSearchQuery }
                                val aiFilters = if (aiQuery.isNotBlank()) {
                                    (listOf(aiQuery) + latestProfile.linkedinJobFilters)
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                        .distinctBy { it.lowercase() }
                                } else {
                                    latestProfile.linkedinJobFilters
                                }
                                val refined = latestProfile.copy(
                                    resumeFileName = fileName,
                                    currentRole = aiInsights.targetRole.ifBlank { latestProfile.currentRole },
                                    preferredSearchQuery = aiQuery,
                                    linkedinJobFilters = aiFilters,
                                    skillsSummary = aiInsights.skillsSummary.ifBlank { latestProfile.skillsSummary },
                                    totalExperience = aiInsights.totalExperience.ifBlank { latestProfile.totalExperience },
                                    resumeSummary = aiInsights.resumeSummary.ifBlank { latestProfile.resumeSummary },
                                    phone = latestProfile.phone.ifBlank { aiInsights.phone },
                                    linkedinUrl = latestProfile.linkedinUrl.ifBlank { aiInsights.linkedinUrl }
                                )
                                repository.saveProfile(currentUserId, refined)
                            }
                    }
                }
                .onFailure { err ->
                    _events.emit(UiEvent.Snackbar(err.localizedMessage ?: "Could not attach PDF.", isError = true))
                }
        }
    }

    private fun refreshDuplicateCheck(apps: List<JobApplication>) {
        val state = _composer.value
        val emailKey = state.recruiterEmail.trim().lowercase()
        val companyKey = state.companyName.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").trim()

        if (emailKey.isBlank() && companyKey.isBlank()) {
            _composer.update { it.copy(duplicateMatches = emptyList()) }
            return
        }

        val matches = apps.filter { existing ->
            val sameEmail = emailKey.isNotBlank() && existing.recruiterEmail.equals(emailKey, ignoreCase = true)
            val sameCompany = companyKey.isNotBlank() &&
                (existing.companyNormalized == companyKey || existing.companyName.equals(state.companyName.trim(), ignoreCase = true))
            sameEmail || sameCompany
        }
        _composer.update { it.copy(duplicateMatches = matches) }
    }

    private fun inferCompanyFromEmail(email: String): String {
        val domain = email.substringAfter("@", "").substringBefore(".")
        return domain.replaceFirstChar { it.uppercase() }.ifBlank { "Company" }
    }

    private companion object {
        const val TAG = "ApplyFlowVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
