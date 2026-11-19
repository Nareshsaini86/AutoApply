package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ForwardToInbox
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.gmail.EmailTemplateFormatter
import com.example.data.model.CandidateProfile
import com.example.data.model.JobApplication
import com.example.ui.viewmodel.ComposerUiState
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApplyScreen(
    composer: ComposerUiState,
    profile: CandidateProfile?,
    onRawPostChange: (String) -> Unit,
    onLoadSample: () -> Unit,
    onExtractAndDraft: () -> Unit,
    onUpdateDraftFields: (
        companyName: String?,
        jobTitle: String?,
        recruiterEmail: String?,
        emailSubject: String?,
        emailBody: String?
    ) -> Unit,
    onConnectGmail: () -> Unit,
    onAutoSendGmail: () -> Unit,
    onOpenGmailFallback: () -> Unit,
    onFollowUpDuplicate: (JobApplication) -> Unit,
    onImportPdf: (android.net.Uri) -> Unit,
    onNavigateToLinkedIn: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToTracker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportPdf(uri)
        }
    }

    val needsCompDetails = profile != null &&
        (profile.currentCtc.isBlank() || profile.expectedCtc.isBlank())

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header & Gmail Connection Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Quick Apply",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Paste LinkedIn post -> Auto email with CV",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val isGmailReady = composer.isGmailReady
                Surface(
                    onClick = onConnectGmail,
                    shape = RoundedCornerShape(999.dp),
                    color = if (isGmailReady) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.testTag("connect_gmail_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (composer.isAuthorizingGmail || composer.isVerifyingSmtp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isGmailReady) Icons.Rounded.CheckCircle else Icons.Rounded.MailOutline,
                                contentDescription = "Gmail status",
                                tint = if (isGmailReady) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = if (isGmailReady) "Gmail Ready" else "Connect Gmail",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isGmailReady) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                }
            }

            // Step 0: Gmail Access First Banner (using logged-in email)
            if (!composer.isGmailReady) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gmail_setup_first_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Step 1: Connect ${composer.smtpEmail.ifBlank { profile?.email ?: "Your Gmail" }}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Enable full Gmail Send & Receive access so 1Click Apply can auto-email your CV and track replies.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onConnectGmail,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("banner_connect_gmail_button")
                        ) {
                            Text("Grant Access")
                        }
                    }
                }
            }

            // Missing CTC Prompt Banner
            if (needsCompDetails) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Add Current & Expected CTC once",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "So every job email automatically includes your exact salary & notice details.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                            )
                        }
                        TextButton(
                            onClick = onNavigateToProfile,
                            modifier = Modifier.testTag("complete_profile_cta")
                        ) {
                            Text("Set CTC", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Step 1: LinkedIn Post Input Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "1. LinkedIn Job Post",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    val clipText = clipboard.getText()?.text.orEmpty()
                                    if (clipText.isNotBlank()) {
                                        onRawPostChange(clipText)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("paste_post_button")
                            ) {
                                Icon(
                                    Icons.Rounded.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Paste", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = onLoadSample,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("load_sample_button")
                            ) {
                                Text("Sample Post", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = composer.rawPostText,
                        onValueChange = onRawPostChange,
                        placeholder = {
                            Text(
                                "Paste LinkedIn hiring post here (role, recruiter email, required details like CTC, Notice Period)...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        minLines = 5,
                        maxLines = 9,
                        trailingIcon = {
                            if (composer.rawPostText.isNotEmpty()) {
                                IconButton(onClick = { onRawPostChange("") }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear post text")
                                }
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("linkedin_post_input")
                    )

                    if (composer.extractionError != null) {
                        Text(
                            text = composer.extractionError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = onExtractAndDraft,
                        enabled = !composer.isExtracting,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("extract_draft_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Extract Details & Draft Email")
                    }

                    OutlinedButton(
                        onClick = onNavigateToLinkedIn,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_linkedin_browser_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Open LinkedIn & Tap Any Post")
                    }
                }
            }

            // Duplicate Warning Card (shown immediately if company or email matched!)
            AnimatedVisibility(visible = composer.duplicateMatches.isNotEmpty()) {
                val firstMatch = composer.duplicateMatches.firstOrNull()
                if (firstMatch != null) {
                    DuplicateWarningCard(
                        match = firstMatch,
                        totalMatches = composer.duplicateMatches.size,
                        onSendFollowUp = { onFollowUpDuplicate(firstMatch) },
                        onViewTracker = onNavigateToTracker
                    )
                }
            }

            // Info banner when Gemini fell back to local parser
            AnimatedVisibility(visible = composer.extractionNotice != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("extraction_notice_banner")
                ) {
                    Text(
                        text = composer.extractionNotice.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // Step 2: Extracted Draft & Auto-Send Card
            AnimatedVisibility(
                visible = composer.hasDraft || composer.recruiterEmail.isNotBlank() || composer.emailBody.isNotBlank()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "2. Ready Application Email",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (composer.isAiPolishing) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "AI refining...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        if (composer.requestedDetails.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                composer.requestedDetails.forEach { detail ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "✓ $detail",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = composer.companyName,
                                onValueChange = { onUpdateDraftFields(it, null, null, null, null) },
                                label = { Text("Company") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Business, contentDescription = null)
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("company_name_input")
                            )
                            OutlinedTextField(
                                value = composer.jobTitle,
                                onValueChange = { onUpdateDraftFields(null, it, null, null, null) },
                                label = { Text("Role") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.WorkOutline, contentDescription = null)
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("job_title_input")
                            )
                        }

                        OutlinedTextField(
                            value = composer.recruiterEmail,
                            onValueChange = { onUpdateDraftFields(null, null, it, null, null) },
                            label = { Text("Recruiter Email") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Email, contentDescription = null)
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("recruiter_email_input")
                        )

                        OutlinedTextField(
                            value = composer.emailSubject,
                            onValueChange = { onUpdateDraftFields(null, null, null, it, null) },
                            label = { Text("Email Subject") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("email_subject_input")
                        )

                        var showVisualPreview by remember { mutableStateOf(true) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = showVisualPreview,
                                onClick = { showVisualPreview = true },
                                label = { Text("Formatted Preview") },
                                modifier = Modifier.testTag("toggle_formatted_preview")
                            )
                            FilterChip(
                                selected = !showVisualPreview,
                                onClick = { showVisualPreview = false },
                                label = { Text("Edit Text") },
                                modifier = Modifier.testTag("toggle_edit_text")
                            )
                        }

                        if (showVisualPreview) {
                            RichEmailPreviewCard(
                                roleTitle = composer.jobTitle.ifBlank { profile?.currentRole ?: "Job Application" },
                                candidateName = profile?.fullName ?: "Naresh Saini",
                                experience = profile?.totalExperience ?: "5+ Years",
                                body = composer.emailBody,
                                attachmentName = composer.activeResumeName
                            )
                        } else {
                            OutlinedTextField(
                                value = composer.emailBody,
                                onValueChange = { onUpdateDraftFields(null, null, null, null, it) },
                                label = { Text("Email Body (Details Included)") },
                                minLines = 8,
                                maxLines = 16,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("email_body_input")
                            )
                        }

                        // Resume Attachment Chip
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Description,
                                        contentDescription = "Attached resume",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column {
                                        Text(
                                            text = composer.activeResumeName,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "PDF attached automatically",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                AssistChip(
                                    onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                    label = { Text("Change") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.AttachFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }

                        if (composer.sendError != null) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = composer.sendError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        Button(
                            onClick = onAutoSendGmail,
                            enabled = !composer.isSending,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            ),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("auto_send_gmail_button")
                        ) {
                            if (composer.isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Sending via Gmail...")
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Auto-Send via Gmail + Attach CV",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onOpenGmailFallback,
                            enabled = !composer.isSending,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("open_gmail_app_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Review & Send in Gmail App")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DuplicateWarningCard(
    match: JobApplication,
    totalMatches: Int,
    onSendFollowUp: () -> Unit,
    onViewTracker: () -> Unit
) {
    val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val dateStr = match.appliedAt?.toDate()?.let { dateFmt.format(it) } ?: "Recently"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("duplicate_warning_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = "Duplicate application detected",
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Already Applied to ${match.companyName}!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = "Sent to ${match.recruiterEmail} for ${match.jobTitle} on $dateStr. Current status: ${match.statusEnum.label} (${match.followUpCount} follow-ups)." +
                    if (totalMatches > 1) " (+${totalMatches - 1} more match)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                FilledTonalButton(
                    onClick = onSendFollowUp,
                    modifier = Modifier.testTag("duplicate_follow_up_button")
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ForwardToInbox,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Send Follow-Up")
                }
                OutlinedButton(onClick = onViewTracker) {
                    Text("View in Tracker")
                }
            }
        }
    }
}

@Composable
private fun RichEmailPreviewCard(
    roleTitle: String,
    candidateName: String,
    experience: String,
    body: String,
    attachmentName: String
) {
    val normalized = remember(body) { EmailTemplateFormatter.normalizePlainText(body) }
    val lines = remember(normalized) { normalized.lines() }
    val bulletRows = remember(lines) {
        lines.mapNotNull { raw ->
            val t = raw.trim()
            val m = Regex("^[•\\-*]\\s*(.+)$").find(t) ?: return@mapNotNull null
            val content = m.groupValues[1].trim()
            val idx = content.indexOf(':')
            if (idx in 2..40 && !content.substring(0, idx).contains("http", ignoreCase = true)) {
                content.substring(0, idx).trim() to content.substring(idx + 1).trim()
            } else {
                "" to content
            }
        }
    }
    val firstBulletIdx = remember(lines) {
        lines.indexOfFirst {
            val t = it.trim()
            t.startsWith("•") || t.startsWith("- ") || t.startsWith("* ")
        }
    }
    val lastBulletIdx = remember(lines) {
        lines.indexOfLast {
            val t = it.trim()
            t.startsWith("•") || t.startsWith("- ") || t.startsWith("* ")
        }
    }
    val beforeBulletParagraphs = remember(lines, firstBulletIdx) {
        val slice = if (firstBulletIdx >= 0) lines.take(firstBulletIdx) else lines
        slice.map { it.trim() }.filter { line ->
            line.isNotBlank() &&
                !line.lowercase().startsWith("best regards") &&
                !line.lowercase().startsWith("warm regards") &&
                line.lowercase() != "regards," &&
                !line.lowercase().startsWith("sincerely")
        }
    }
    val afterBulletParagraphs = remember(lines, lastBulletIdx) {
        if (lastBulletIdx < 0) emptyList()
        else {
            val tail = lines.drop(lastBulletIdx + 1)
            val signOffIdx = tail.indexOfFirst {
                val l = it.trim().lowercase()
                l.startsWith("best regards") || l.startsWith("warm regards") ||
                    l == "regards," || l.startsWith("sincerely")
            }
            val bodyTail = if (signOffIdx >= 0) tail.take(signOffIdx) else tail
            bodyTail.map { it.trim() }.filter { line ->
                line.isNotBlank() &&
                    !line.equals(candidateName, ignoreCase = true) &&
                    !line.startsWith("+")
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rich_email_preview_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Executive Header Bar
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = "CANDIDATE APPLICATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = roleTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = "$candidateName • $experience Experience",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                beforeBulletParagraphs.forEach { para ->
                    Text(
                        text = para,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (bulletRows.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            bulletRows.forEachIndexed { idx, (label, value) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (label.isNotBlank()) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(0.4f)
                                        )
                                        Text(
                                            text = value,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(0.6f)
                                        )
                                    } else {
                                        Text(
                                            text = "• $value",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                if (idx < bulletRows.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }

                afterBulletParagraphs.forEach { para ->
                    Text(
                        text = para,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Resume Attached: $attachmentName",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
