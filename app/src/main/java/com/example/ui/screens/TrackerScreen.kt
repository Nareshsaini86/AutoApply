package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ForwardToInbox
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ApplicationStatus
import com.example.data.model.FollowUpDraft
import com.example.data.model.JobApplication
import com.example.data.model.UiState
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.CobaltContainer
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.OnAmberContainer
import com.example.ui.theme.OnCobaltContainer
import com.example.ui.theme.OnEmeraldContainer
import com.example.ui.theme.OnRoseContainer
import com.example.ui.theme.OnVioletContainer
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.VioletContainer
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackerScreen(
    applicationsState: UiState<List<JobApplication>>,
    followUpInProgressId: String?,
    isSyncingReplies: Boolean,
    previewFollowUpPair: Pair<JobApplication, FollowUpDraft>?,
    onFollowUpClick: (JobApplication) -> Unit,
    onConfirmPreviewFollowUp: (JobApplication, String, String) -> Unit,
    onDismissPreviewFollowUp: () -> Unit,
    onSyncRepliesClick: () -> Unit,
    onUpdateStatus: (String, ApplicationStatus) -> Unit,
    onDeleteApplication: (String) -> Unit,
    onNavigateToApply: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<ApplicationStatus?>(null) }
    var selectedAppForSheet by remember { mutableStateOf<JobApplication?>(null) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Applied Tracker",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Track companies, status & 1-tap follow-ups",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = onSyncRepliesClick,
                    enabled = !isSyncingReplies,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("sync_replies_button")
                ) {
                    if (isSyncingReplies) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Sync Gmail replies",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Sync Replies", style = MaterialTheme.typography.labelMedium)
                }
            }

            when (applicationsState) {
                is UiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
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
                            text = applicationsState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                is UiState.Success -> {
                    val allApps = applicationsState.data
                    val filteredApps = remember(allApps, searchQuery, selectedFilter) {
                        allApps.filter { app ->
                            val matchesStatus = selectedFilter == null || app.statusEnum == selectedFilter
                            val q = searchQuery.trim().lowercase()
                            val matchesQuery = q.isBlank() ||
                                app.companyName.lowercase().contains(q) ||
                                app.jobTitle.lowercase().contains(q) ||
                                app.recruiterEmail.lowercase().contains(q)
                            matchesStatus && matchesQuery
                        }
                    }

                    // Summary Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            label = "Applied",
                            value = allApps.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Follow-Ups",
                            value = allApps.sumOf { it.followUpCount }.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Active Leads",
                            value = allApps.count {
                                it.statusEnum == ApplicationStatus.REPLIED ||
                                    it.statusEnum == ApplicationStatus.INTERVIEW ||
                                    it.statusEnum == ApplicationStatus.OFFER
                            }.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search company, role, or email...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("tracker_search_input")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = { Text("All (${allApps.size})") }
                        )
                        ApplicationStatus.entries.forEach { status ->
                            val count = allApps.count { it.statusEnum == status }
                            FilterChip(
                                selected = selectedFilter == status,
                                onClick = {
                                    selectedFilter = if (selectedFilter == status) null else status
                                },
                                label = { Text("${status.label} ($count)") }
                            )
                        }
                    }

                    if (filteredApps.isEmpty()) {
                        EmptyTrackerState(
                            hasAnyApps = allApps.isNotEmpty(),
                            onNavigateToApply = onNavigateToApply
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 4.dp,
                                bottom = 24.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredApps, key = { it.id }) { app ->
                                ApplicationCard(
                                    app = app,
                                    isFollowingUp = followUpInProgressId == app.id,
                                    onCardClick = { selectedAppForSheet = app },
                                    onFollowUpClick = { onFollowUpClick(app) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail Bottom Sheet
    val currentSheetApp = selectedAppForSheet
    if (currentSheetApp != null) {
        val latestApp = (applicationsState as? UiState.Success)
            ?.data
            ?.firstOrNull { it.id == currentSheetApp.id }
            ?: currentSheetApp

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedAppForSheet = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = latestApp.companyName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${latestApp.jobTitle} • ${latestApp.recruiterEmail}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Update Application Status",
                    style = MaterialTheme.typography.titleSmall
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ApplicationStatus.entries.forEach { st ->
                        FilterChip(
                            selected = latestApp.statusEnum == st,
                            onClick = { onUpdateStatus(latestApp.id, st) },
                            label = { Text(st.label) }
                        )
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Sent Email Subject",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = latestApp.emailSubject,
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = latestApp.emailBody,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                if (latestApp.lastFollowUpNote.isNotBlank()) {
                    Text(
                        text = "Last Follow-Up (#${latestApp.followUpCount})",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Surface(
                        color = AmberContainer.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = latestApp.lastFollowUpNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnAmberContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            selectedAppForSheet = null
                            onFollowUpClick(latestApp)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ForwardToInbox, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Send Follow-Up")
                    }
                    OutlinedButton(
                        onClick = {
                            selectedAppForSheet = null
                            onDeleteApplication(latestApp.id)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete application")
                    }
                }
            }
        }
    }

    // Optional Preview Follow-Up Dialog
    if (previewFollowUpPair != null) {
        val (targetApp, draft) = previewFollowUpPair
        var editedSubject by remember(targetApp.id, draft.subject) { mutableStateOf(draft.subject) }
        var editedBody by remember(targetApp.id, draft.body) { mutableStateOf(draft.body) }

        AlertDialog(
            onDismissRequest = onDismissPreviewFollowUp,
            title = { Text("Follow-Up to ${targetApp.companyName}") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = editedSubject,
                        onValueChange = { editedSubject = it },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedBody,
                        onValueChange = { editedBody = it },
                        label = { Text("Follow-Up Message") },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmPreviewFollowUp(targetApp, editedSubject, editedBody) }
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send Now")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPreviewFollowUp) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ApplicationCard(
    app: JobApplication,
    isFollowingUp: Boolean,
    onCardClick: () -> Unit,
    onFollowUpClick: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val appliedDate = app.appliedAt?.toDate()?.let { dateFmt.format(it) } ?: "Today"
    val (badgeBg, badgeFg) = statusColors(app.statusEnum)

    Card(
        onClick = onCardClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("application_card_${app.id}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = app.companyName.take(2).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.companyName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.jobTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = app.statusEnum.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.recruiterEmail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Applied $appliedDate • Follow-ups: ${app.followUpCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = onFollowUpClick,
                    enabled = !isFollowingUp,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("follow_up_button_${app.id}")
                ) {
                    if (isFollowingUp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onSecondary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ForwardToInbox,
                            contentDescription = "Send follow-up email",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Follow Up", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyTrackerState(
    hasAnyApps: Boolean,
    onNavigateToApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (hasAnyApps) "No matching applications" else "No applications sent yet",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasAnyApps) {
                "Try clearing your search or status filter."
            } else {
                "Paste a LinkedIn hiring post in Quick Apply to send your first tailored application with CV."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasAnyApps) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNavigateToApply) {
                Text("Go to Quick Apply")
            }
        }
    }
}

private fun statusColors(status: ApplicationStatus): Pair<Color, Color> = when (status) {
    ApplicationStatus.APPLIED -> CobaltContainer to OnCobaltContainer
    ApplicationStatus.FOLLOW_UP_SENT -> AmberContainer to OnAmberContainer
    ApplicationStatus.REPLIED -> EmeraldContainer to OnEmeraldContainer
    ApplicationStatus.INTERVIEW -> VioletContainer to OnVioletContainer
    ApplicationStatus.OFFER -> EmeraldContainer to OnEmeraldContainer
    ApplicationStatus.REJECTED -> RoseContainer to OnRoseContainer
}
