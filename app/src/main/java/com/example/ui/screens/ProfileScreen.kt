package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.CandidateProfile
import com.example.data.model.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    profileState: UiState<CandidateProfile>,
    activeResumeName: String,
    isGmailAuthorized: Boolean,
    isSmtpConnected: Boolean,
    smtpEmail: String,
    isAuthorizingGmail: Boolean,
    onConnectGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
    onSaveProfile: (CandidateProfile) -> Unit,
    onImportPdf: (Uri) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportPdf(uri)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        when (profileState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is UiState.Error -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = profileState.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            is UiState.Success -> {
                val loaded = profileState.data
                var fullName by remember(loaded.userId) { mutableStateOf(loaded.fullName) }
                var email by remember(loaded.userId) { mutableStateOf(loaded.email) }
                var phone by remember(loaded.userId) { mutableStateOf(loaded.phone) }
                var linkedinUrl by remember(loaded.userId) { mutableStateOf(loaded.linkedinUrl) }
                var currentRole by remember(loaded.userId) { mutableStateOf(loaded.currentRole) }
                var totalExp by remember(loaded.userId) { mutableStateOf(loaded.totalExperience) }
                var currentCtc by remember(loaded.userId) { mutableStateOf(loaded.currentCtc) }
                var expectedCtc by remember(loaded.userId) { mutableStateOf(loaded.expectedCtc) }
                var noticePeriod by remember(loaded.userId) { mutableStateOf(loaded.noticePeriod) }
                var location by remember(loaded.userId) { mutableStateOf(loaded.currentLocation) }
                var skills by remember(loaded.userId) { mutableStateOf(loaded.skillsSummary) }
                var resumeSummary by remember(loaded.userId) { mutableStateOf(loaded.resumeSummary) }
                var autoFollowUp by remember(loaded.userId) { mutableStateOf(loaded.autoFollowUpSend) }

                LaunchedEffect(loaded.updatedAt) {
                    fullName = loaded.fullName
                    email = loaded.email
                    phone = loaded.phone
                    linkedinUrl = loaded.linkedinUrl
                    currentRole = loaded.currentRole
                    totalExp = loaded.totalExperience
                    currentCtc = loaded.currentCtc
                    expectedCtc = loaded.expectedCtc
                    noticePeriod = loaded.noticePeriod
                    location = loaded.currentLocation
                    skills = loaded.skillsSummary
                    resumeSummary = loaded.resumeSummary
                    autoFollowUp = loaded.autoFollowUpSend
                }

                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Candidate & Gmail Setup",
                        style = MaterialTheme.typography.headlineMedium
                    )

                    // Gmail & Account Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Gmail Dispatch Access",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = when {
                                    isSmtpConnected -> "Connected via Gmail App Password (${smtpEmail.ifBlank { email }}). Automated applications, follow-ups & reply sync are active."
                                    isGmailAuthorized -> "Connected via Google OAuth (${email}). ApplyFlow can send applications and follow-up emails directly."
                                    else -> "Connect your Gmail using a Google App Password (no 403 block) or Google OAuth so ApplyFlow can auto-send emails with your CV."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = onConnectGmail,
                                    enabled = !isAuthorizingGmail,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("profile_connect_gmail_button")
                                ) {
                                    Icon(
                                        imageVector = if (isGmailAuthorized) Icons.Rounded.CheckCircle else Icons.Rounded.MailOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (isGmailAuthorized) "Manage Gmail" else "Connect Gmail")
                                }
                                if (isGmailAuthorized) {
                                    OutlinedButton(
                                        onClick = onDisconnectGmail,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("disconnect_gmail_button")
                                    ) {
                                        Text("Disconnect")
                                    }
                                }
                                OutlinedButton(
                                    onClick = onSignOut,
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("sign_out_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                                        contentDescription = "Sign out",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Resume PDF Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Description,
                                    contentDescription = "Active resume PDF",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column {
                                    Text(
                                        text = activeResumeName,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Automatically attached to every application & follow-up",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("upload_resume_button")
                            ) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Upload PDF")
                            }
                        }
                    }

                    // Candidate Profile Fields Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Recruiter Q&A Details (Auto-Added to Emails)",
                                style = MaterialTheme.typography.titleMedium
                            )

                            OutlinedTextField(
                                value = fullName,
                                onValueChange = { fullName = it },
                                label = { Text("Full Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = totalExp,
                                    onValueChange = { totalExp = it },
                                    label = { Text("Total Experience") },
                                    placeholder = { Text("e.g. 5+ Years") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = currentRole,
                                    onValueChange = { currentRole = it },
                                    label = { Text("Current Role") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = currentCtc,
                                    onValueChange = { currentCtc = it },
                                    label = { Text("Current CTC") },
                                    placeholder = { Text("e.g. 12 LPA") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("current_ctc_input")
                                )
                                OutlinedTextField(
                                    value = expectedCtc,
                                    onValueChange = { expectedCtc = it },
                                    label = { Text("Expected CTC") },
                                    placeholder = { Text("e.g. 16 LPA") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("expected_ctc_input")
                                )
                            }

                            OutlinedTextField(
                                value = noticePeriod,
                                onValueChange = { noticePeriod = it },
                                label = { Text("Notice Period") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("notice_period_input")
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("Immediate", "15 Days", "30 Days", "60 Days", "90 Days").forEach { option ->
                                    FilterChip(
                                        selected = noticePeriod.equals(option, ignoreCase = true),
                                        onClick = { noticePeriod = option },
                                        label = { Text(option) }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = location,
                                onValueChange = { location = it },
                                label = { Text("Current Location") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = phone,
                                    onValueChange = { phone = it },
                                    label = { Text("Phone") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = email,
                                    onValueChange = { email = it },
                                    label = { Text("Email") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = linkedinUrl,
                                onValueChange = { linkedinUrl = it },
                                label = { Text("LinkedIn URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = skills,
                                onValueChange = { skills = it },
                                label = { Text("Key Technical Skills") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = resumeSummary,
                                onValueChange = { resumeSummary = it },
                                label = { Text("Resume Highlights Used by AI") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "1-Tap Auto-Send Follow-Up",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "When enabled, tapping Follow Up immediately drafts & sends the email.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = autoFollowUp,
                                    onCheckedChange = { autoFollowUp = it }
                                )
                            }

                            Button(
                                onClick = {
                                    onSaveProfile(
                                        loaded.copy(
                                            fullName = fullName,
                                            email = email,
                                            phone = phone,
                                            linkedinUrl = linkedinUrl,
                                            currentRole = currentRole,
                                            totalExperience = totalExp,
                                            currentCtc = currentCtc,
                                            expectedCtc = expectedCtc,
                                            noticePeriod = noticePeriod,
                                            currentLocation = location,
                                            skillsSummary = skills,
                                            resumeSummary = resumeSummary,
                                            resumeFileName = activeResumeName,
                                            autoFollowUpSend = autoFollowUp
                                        )
                                    )
                                },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("save_profile_button")
                            ) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Save Profile")
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
