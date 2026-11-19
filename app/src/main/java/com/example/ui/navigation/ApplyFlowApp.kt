package com.example.ui.navigation

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.auth.attemptAutoSignIn
import com.example.auth.extractGmailTokenFromIntent
import com.example.auth.requestGmailAuthorization
import com.example.auth.signOut
import com.example.data.ai.JobAiRepository
import com.example.data.gmail.GmailService
import com.example.data.gmail.GmailSmtpManager
import com.example.data.gmail.ResumeStorageManager
import com.example.data.model.UiState
import com.example.data.repository.ApplicationRepository
import com.example.ui.components.GmailConnectSheet
import com.example.ui.screens.ApplyScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.LinkedInScreen
import com.example.ui.screens.MailboxScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.TrackerScreen
import com.example.ui.viewmodel.ApplyFlowViewModel
import com.example.ui.viewmodel.UiEvent
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

const val ROUTE_LINKEDIN = "linkedin"
const val ROUTE_APPLY = "apply"
const val ROUTE_MAILBOX = "mailbox"
const val ROUTE_TRACKER = "tracker"
const val ROUTE_PROFILE = "profile"

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val NAV_TABS = listOf(
    NavTab(ROUTE_LINKEDIN, "LinkedIn", Icons.Rounded.Public, Icons.Outlined.Public),
    NavTab(ROUTE_APPLY, "Apply", Icons.Rounded.AutoAwesome, Icons.Outlined.AutoAwesome),
    NavTab(ROUTE_MAILBOX, "Mailbox", Icons.Rounded.Mail, Icons.Outlined.MailOutline),
    NavTab(ROUTE_TRACKER, "Tracker", Icons.Rounded.TrackChanges, Icons.Outlined.TrackChanges),
    NavTab(ROUTE_PROFILE, "Profile", Icons.Rounded.Person, Icons.Outlined.Person)
)

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun ApplyFlowApp(
    sharedPostText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    auth: FirebaseAuth = Firebase.auth
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedApplyFlowShell(
                currentUserId = user.uid,
                userEmail = user.email,
                userDisplayName = user.displayName,
                sharedPostText = sharedPostText,
                onSharedTextConsumed = onSharedTextConsumed,
                onSignOut = {
                    signOut(credentialManager = credentialManager, scope = scope)
                }
            )
        }
    }
}

@Composable
private fun AuthenticatedApplyFlowShell(
    currentUserId: String,
    userEmail: String?,
    userDisplayName: String?,
    sharedPostText: String?,
    onSharedTextConsumed: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: ApplyFlowViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                ApplyFlowViewModel(
                    repository = ApplicationRepository(db),
                    aiRepository = JobAiRepository(),
                    gmailService = GmailService(),
                    smtpManager = GmailSmtpManager(app),
                    resumeManager = ResumeStorageManager(app),
                    currentUserId = currentUserId,
                    authEmail = userEmail,
                    authDisplayName = userDisplayName
                )
            }
        }
    )

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val applicationsState by viewModel.applicationsState.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val mailbox by viewModel.mailbox.collectAsStateWithLifecycle()

    var promptedGmailOnce by remember(currentUserId) { mutableStateOf(false) }

    // First-step flow: Prompt user to connect Gmail (pre-filled with login email) for Send & Receive control
    LaunchedEffect(currentUserId, composer.isGmailReady) {
        if (!promptedGmailOnce && !composer.isGmailReady) {
            promptedGmailOnce = true
            viewModel.openGmailConnectSheet()
        }
    }

    LaunchedEffect(sharedPostText) {
        if (!sharedPostText.isNullOrBlank()) {
            viewModel.handleSharedLinkedInText(sharedPostText)
            onSharedTextConsumed()
        }
    }

    val gmailConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            extractGmailTokenFromIntent(context, activityResult.data)
                .onSuccess { token -> viewModel.setGmailAccessToken(token) }
                .onFailure { err ->
                    viewModel.onGmailOAuthError(err.localizedMessage ?: "Gmail authorization failed.")
                }
        } else {
            viewModel.onGmailOAuthError("Gmail access consent was cancelled or blocked (Error 403).")
        }
    }

    fun launchGmailOAuth() {
        if (activity == null) {
            viewModel.onGmailOAuthError("Activity context unavailable for Gmail authorization.")
            return
        }
        viewModel.setAuthorizingGmail(true)
        requestGmailAuthorization(
            activity = activity,
            onAccessToken = { token -> viewModel.setGmailAccessToken(token) },
            onNeedsResolution = { intentSenderRequest ->
                gmailConsentLauncher.launch(intentSenderRequest)
            },
            onError = { msg -> viewModel.onGmailOAuthError(msg) }
        )
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Snackbar -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }
                is UiEvent.LaunchIntent -> {
                    context.startActivity(event.intent)
                }
                UiEvent.RequestGmailOAuthDirect -> {
                    launchGmailOAuth()
                }
                UiEvent.ApplicationSentNavigateTracker -> {
                    navigateToTab(ROUTE_TRACKER)
                }
                UiEvent.NavigateToApplyTab -> {
                    navigateToTab(ROUTE_APPLY)
                }
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val appCount = (applicationsState as? UiState.Success)?.data?.size ?: 0

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(snackbarData = data)
                }
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar {
                        NAV_TABS.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navigateToTab(tab.route)
                                    if (tab.route == ROUTE_MAILBOX && composer.isGmailReady) {
                                        viewModel.loadMailbox()
                                    }
                                },
                                icon = {
                                    if (tab.route == ROUTE_TRACKER && appCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$appCount") } }) {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tab.label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    }
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                modifier = Modifier.testTag("nav_tab_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        NAV_TABS.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navigateToTab(tab.route)
                                    if (tab.route == ROUTE_MAILBOX && composer.isGmailReady) {
                                        viewModel.loadMailbox()
                                    }
                                },
                                icon = {
                                    if (tab.route == ROUTE_TRACKER && appCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$appCount") } }) {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tab.label
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    }
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("nav_rail_${tab.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_APPLY
                    ) {
                        composable(ROUTE_LINKEDIN) {
                            LinkedInScreen(
                                profile = (profileState as? UiState.Success)?.data,
                                capturedPost = composer.capturedLinkedInPost,
                                duplicateMatches = (applicationsState as? UiState.Success)?.data.orEmpty(),
                                isSending = composer.isSending,
                                onPostCaptured = viewModel::onLinkedInPostCaptured,
                                onDismissCapturedPost = viewModel::dismissCapturedLinkedInPost,
                                onApplyFromCapturedPost = viewModel::applyFromCapturedLinkedInPost
                            )
                        }

                        composable(ROUTE_APPLY) {
                            ApplyScreen(
                                composer = composer,
                                profile = (profileState as? UiState.Success)?.data,
                                onRawPostChange = viewModel::updateRawPostText,
                                onLoadSample = viewModel::loadSampleLinkedInPost,
                                onExtractAndDraft = { viewModel.extractAndDraftWithAi(false) },
                                onUpdateDraftFields = { company, title, email, subject, body ->
                                    viewModel.updateDraftFields(
                                        companyName = company,
                                        jobTitle = title,
                                        recruiterEmail = email,
                                        emailSubject = subject,
                                        emailBody = body
                                    )
                                },
                                onConnectGmail = viewModel::openGmailConnectSheet,
                                onAutoSendGmail = viewModel::sendApplicationClicked,
                                onOpenGmailFallback = { viewModel.openInGmailAppFallback(context) },
                                onFollowUpDuplicate = viewModel::onFollowUpButtonClicked,
                                onImportPdf = viewModel::importResumePdf,
                                onNavigateToLinkedIn = { navigateToTab(ROUTE_LINKEDIN) },
                                onNavigateToProfile = { navigateToTab(ROUTE_PROFILE) },
                                onNavigateToTracker = { navigateToTab(ROUTE_TRACKER) }
                            )
                        }

                        composable(ROUTE_MAILBOX) {
                            MailboxScreen(
                                mailbox = mailbox,
                                isGmailReady = composer.isGmailReady,
                                connectedEmail = composer.smtpEmail.ifBlank { userEmail.orEmpty() },
                                activeResumeName = composer.activeResumeName,
                                onConnectGmail = viewModel::openGmailConnectSheet,
                                onSelectFolder = { folder -> viewModel.loadMailbox(folder) },
                                onRefresh = { viewModel.loadMailbox() },
                                onEnterSelectionMode = viewModel::enterSelectionMode,
                                onToggleSelection = viewModel::toggleMessageSelection,
                                onSelectAll = viewModel::selectAllMessages,
                                onClearSelection = viewModel::clearMessageSelection,
                                onDeleteSelected = viewModel::deleteSelectedEmails,
                                onDeleteSingle = viewModel::deleteSingleEmail,
                                onOpenMessage = viewModel::onMailboxMessageOpened,
                                onCloseMessage = viewModel::clearAutoReplyDraft,
                                onUpdateAutoReply = viewModel::updateAutoReplyDraft,
                                onSendOneClickReply = viewModel::sendAutoReplyInOneClick,
                                onSendDirectEmail = viewModel::sendDirectEmail
                            )
                        }

                        composable(ROUTE_TRACKER) {
                            TrackerScreen(
                                applicationsState = applicationsState,
                                followUpInProgressId = composer.followUpInProgressId,
                                isSyncingReplies = composer.isSyncingReplies,
                                previewFollowUpPair = composer.previewFollowUpPair,
                                onFollowUpClick = viewModel::onFollowUpButtonClicked,
                                onConfirmPreviewFollowUp = viewModel::confirmAndSendPreviewFollowUp,
                                onDismissPreviewFollowUp = viewModel::dismissFollowUpPreview,
                                onSyncRepliesClick = viewModel::syncRepliesClicked,
                                onUpdateStatus = viewModel::updateApplicationStatus,
                                onDeleteApplication = viewModel::deleteApplication,
                                onNavigateToApply = { navigateToTab(ROUTE_APPLY) }
                            )
                        }

                        composable(ROUTE_PROFILE) {
                            ProfileScreen(
                                profileState = profileState,
                                activeResumeName = composer.activeResumeName,
                                isGmailAuthorized = composer.isGmailReady,
                                isSmtpConnected = composer.isSmtpConnected,
                                smtpEmail = composer.smtpEmail,
                                isAuthorizingGmail = composer.isAuthorizingGmail || composer.isVerifyingSmtp,
                                onConnectGmail = viewModel::openGmailConnectSheet,
                                onDisconnectGmail = viewModel::disconnectSmtp,
                                onSaveProfile = viewModel::saveProfile,
                                onImportPdf = viewModel::importResumePdf,
                                onSignOut = onSignOut
                            )
                        }
                    }
                }
            }
        }

        if (composer.showGmailConnectSheet) {
            GmailConnectSheet(
                composer = composer,
                onDismiss = viewModel::dismissGmailConnectSheet,
                onVerifySmtp = viewModel::verifyAndConnectSmtp,
                onConnectOAuth = viewModel::requestOAuthFromSheet,
                onDisconnect = viewModel::disconnectSmtp
            )
        }
    }
}
