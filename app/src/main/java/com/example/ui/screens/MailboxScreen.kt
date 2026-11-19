package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.EmailMessage
import com.example.ui.viewmodel.MailboxUiState

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.LinearProgressIndicator

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MailboxScreen(
    mailbox: MailboxUiState,
    isGmailReady: Boolean,
    connectedEmail: String,
    activeResumeName: String,
    onConnectGmail: () -> Unit,
    onSelectFolder: (String) -> Unit,
    onRefresh: () -> Unit,
    onEnterSelectionMode: (String?) -> Unit,
    onToggleSelection: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDeleteSingle: (EmailMessage) -> Unit,
    onOpenMessage: (EmailMessage) -> Unit = {},
    onCloseMessage: () -> Unit = {},
    onUpdateAutoReply: (subject: String?, body: String?, attachResume: Boolean?) -> Unit = { _, _, _ -> },
    onSendOneClickReply: (EmailMessage) -> Unit = {},
    onSendDirectEmail: (
        toEmail: String,
        subject: String,
        body: String,
        attachResume: Boolean,
        inReplyTo: String?
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedMessage by remember { mutableStateOf<EmailMessage?>(null) }
    var pendingSingleDelete by remember { mutableStateOf<EmailMessage?>(null) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    var showComposeDialog by remember { mutableStateOf(false) }
    var composeTo by remember { mutableStateOf("") }
    var composeSubject by remember { mutableStateOf("") }
    var composeBody by remember { mutableStateOf("") }
    var composeInReplyTo by remember { mutableStateOf<String?>(null) }
    var composeAttachResume by remember { mutableStateOf(true) }
    var onlyRecruiters by remember { mutableStateOf(false) }

    val displayedMessages = remember(mailbox.messages, onlyRecruiters) {
        if (onlyRecruiters) mailbox.messages.filter { it.isRecruiterRelated } else mailbox.messages
    }

    BackHandler(enabled = mailbox.isSelectionMode) {
        onClearSelection()
    }

    fun openCompose(
        to: String = "",
        subject: String = "",
        body: String = "",
        inReplyTo: String? = null
    ) {
        composeTo = to
        composeSubject = subject
        composeBody = body
        composeInReplyTo = inReplyTo
        composeAttachResume = true
        showComposeDialog = true
    }

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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gmail Mailbox",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isGmailReady) {
                            "Send & Receive active • $connectedEmail"
                        } else {
                            "Connect $connectedEmail for full Inbox & Sent access"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isGmailReady && mailbox.messages.isNotEmpty() && !mailbox.isSelectionMode) {
                        IconButton(
                            onClick = { onEnterSelectionMode(null) },
                            modifier = Modifier.testTag("mailbox_select_mode_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Checklist,
                                contentDescription = "Select multiple emails to delete"
                            )
                        }
                    }
                    IconButton(
                        onClick = onRefresh,
                        enabled = isGmailReady && !mailbox.isLoading && !mailbox.isRefreshing && !mailbox.isDeleting,
                        modifier = Modifier.testTag("mailbox_refresh_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh emails")
                    }
                    Button(
                        onClick = {
                            if (isGmailReady) openCompose() else onConnectGmail()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("mailbox_compose_button")
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Compose")
                    }
                }
            }

            if (!isGmailReady) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MailOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "Connect Your Login Gmail First",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Grant Gmail Send & Receive access for ${connectedEmail.ifBlank { "your account" }} so you can read recruiter replies in Inbox, check Sent mails, and send applications + CV automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onConnectGmail,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("mailbox_connect_gmail_button")
                            ) {
                                Text("Connect Gmail Now")
                            }
                        }
                    }
                }
            } else {
                // Multi-Select Contextual Delete Bar
                AnimatedVisibility(visible = mailbox.isSelectionMode) {
                    val selectedCount = mailbox.selectedUids.size
                    val allDisplayedSelected = displayedMessages.isNotEmpty() &&
                        displayedMessages.all { it.uid in mailbox.selectedUids }

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("mailbox_selection_bar")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onClearSelection,
                                    enabled = !mailbox.isDeleting,
                                    modifier = Modifier.testTag("mailbox_exit_selection_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Cancel selection",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Text(
                                    text = "$selectedCount selected",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(
                                    onClick = { onSelectAll(displayedMessages.map { it.uid }) },
                                    enabled = displayedMessages.isNotEmpty() && !mailbox.isDeleting,
                                    modifier = Modifier.testTag("mailbox_select_all_button")
                                ) {
                                    Text(if (allDisplayedSelected) "Deselect" else "Select All")
                                }
                                Button(
                                    onClick = { showBulkDeleteConfirm = true },
                                    enabled = selectedCount > 0 && !mailbox.isDeleting,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .height(40.dp)
                                        .testTag("mailbox_delete_selected_button")
                                ) {
                                    if (mailbox.isDeleting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = MaterialTheme.colorScheme.onError,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Delete ($selectedCount)")
                                    }
                                }
                            }
                        }
                    }
                }

                // Folder Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mailbox.selectedFolder == "INBOX",
                        onClick = { onSelectFolder("INBOX") },
                        label = { Text("Inbox (Received)") },
                        modifier = Modifier.testTag("mailbox_tab_inbox")
                    )
                    FilterChip(
                        selected = mailbox.selectedFolder == "SENT",
                        onClick = { onSelectFolder("SENT") },
                        label = { Text("Sent Mail") },
                        modifier = Modifier.testTag("mailbox_tab_sent")
                    )
                    FilterChip(
                        selected = onlyRecruiters,
                        onClick = { onlyRecruiters = !onlyRecruiters },
                        label = { Text("Recruiters") }
                    )
                }

                Spacer(Modifier.height(6.dp))

                if (mailbox.isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                if (mailbox.error != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = mailbox.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (mailbox.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Loading ${if (mailbox.selectedFolder == "SENT") "Sent Mail" else "Inbox"}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (displayedMessages.isEmpty()) {
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
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (onlyRecruiters) {
                                "No matching recruiter emails found yet"
                            } else {
                                "No emails in ${mailbox.selectedFolder}"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onRefresh) {
                            Text("Refresh")
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(displayedMessages, key = { it.uid }) { msg ->
                            val isSelected = msg.uid in mailbox.selectedUids
                            EmailMessageCard(
                                msg = msg,
                                isSelectionMode = mailbox.isSelectionMode,
                                isSelected = isSelected,
                                onClick = {
                                    if (mailbox.isSelectionMode) {
                                        onToggleSelection(msg.uid)
                                    } else {
                                        selectedMessage = msg
                                        onOpenMessage(msg)
                                    }
                                },
                                onLongPress = {
                                    onEnterSelectionMode(msg.uid)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Message Viewer Sheet with Auto-Filled 1-Click Reply
    val viewing = selectedMessage
    if (viewing != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val autoReply = mailbox.autoReplyDraft
        ModalBottomSheet(
            onDismissRequest = {
                selectedMessage = null
                onCloseMessage()
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = viewing.subject,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (viewing.folder == "SENT") "To: ${viewing.to}" else "From: ${viewing.from}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (viewing.date.isNotBlank()) {
                    Text(
                        text = viewing.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = viewing.fullBody.ifBlank { viewing.snippet },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp)
                    )
                }

                if (viewing.folder == "INBOX" && autoReply != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        ),
                        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mailbox_auto_reply_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Auto-Filled Reply (Ready in 1 Click)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (mailbox.isPolishingReply) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }

                            if (autoReply.detectedRequirements.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    autoReply.detectedRequirements.forEach { reqLabel ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(999.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = reqLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = autoReply.subject,
                                onValueChange = { onUpdateAutoReply(it, null, null) },
                                label = { Text("Reply Subject") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("mailbox_auto_reply_subject")
                            )

                            OutlinedTextField(
                                value = autoReply.body,
                                onValueChange = { onUpdateAutoReply(null, it, null) },
                                label = { Text("Auto-Filled Answers & Message") },
                                minLines = 5,
                                maxLines = 10,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("mailbox_auto_reply_body")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Attach $activeResumeName",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = autoReply.attachResume,
                                    onCheckedChange = { onUpdateAutoReply(null, null, it) },
                                    modifier = Modifier.testTag("mailbox_auto_reply_attach_switch")
                                )
                            }

                            Button(
                                onClick = {
                                    onSendOneClickReply(viewing)
                                    selectedMessage = null
                                    onCloseMessage()
                                },
                                enabled = !mailbox.isSendingEmail && autoReply.toEmail.contains("@") && autoReply.body.isNotBlank(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("mailbox_one_click_reply_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("1-Click Send Reply")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val replyTarget = autoReply?.toEmail?.takeIf { it.contains("@") }
                                ?: extractEmailAddress(
                                    if (viewing.folder == "SENT") viewing.to else viewing.from
                                )
                            val reSubject = autoReply?.subject
                                ?: if (viewing.subject.startsWith("Re:", ignoreCase = true)) {
                                    viewing.subject
                                } else {
                                    "Re: ${viewing.subject}"
                                }
                            val prefilledBody = autoReply?.body.orEmpty()
                            selectedMessage = null
                            onCloseMessage()
                            openCompose(
                                to = replyTarget,
                                subject = reSubject,
                                body = prefilledBody,
                                inReplyTo = viewing.messageId.takeIf { it.isNotBlank() }
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("mailbox_full_compose_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Full Compose")
                    }
                    OutlinedButton(
                        onClick = {
                            pendingSingleDelete = viewing
                            selectedMessage = null
                            onCloseMessage()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("mailbox_viewer_delete_button")
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // Confirm Single Delete Dialog
    val singleTarget = pendingSingleDelete
    if (singleTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingSingleDelete = null },
            title = { Text("Delete Email?") },
            text = {
                Text("Move \"${singleTarget.subject}\" to Gmail Trash?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSingle(singleTarget)
                        pendingSingleDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Move to Trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSingleDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirm Bulk Multi-Delete Dialog
    if (showBulkDeleteConfirm) {
        val count = mailbox.selectedUids.size
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete $count Selected Email${if (count == 1) "" else "s"}?") },
            text = {
                Text("These $count selected emails will be moved to your Gmail Trash.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBulkDeleteConfirm = false
                        onDeleteSelected()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_bulk_delete_button")
                ) {
                    Text("Delete ($count)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Compose / Reply Dialog
    if (showComposeDialog) {
        AlertDialog(
            onDismissRequest = { showComposeDialog = false },
            title = { Text(if (composeInReplyTo != null) "Reply via Gmail" else "Send New Email") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = composeTo,
                        onValueChange = { composeTo = it },
                        label = { Text("To Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = composeSubject,
                        onValueChange = { composeSubject = it },
                        label = { Text("Subject") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = composeBody,
                        onValueChange = { composeBody = it },
                        label = { Text("Message") },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Attach $activeResumeName",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = composeAttachResume,
                            onCheckedChange = { composeAttachResume = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSendDirectEmail(
                            composeTo,
                            composeSubject,
                            composeBody,
                            composeAttachResume,
                            composeInReplyTo
                        )
                        showComposeDialog = false
                    },
                    enabled = !mailbox.isSendingEmail && composeTo.contains("@") && composeBody.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { showComposeDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailMessageCard(
    msg: EmailMessage,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val cardShape = MaterialTheme.shapes.large
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderStroke = when {
        isSelected -> BorderStroke(1.8.dp, MaterialTheme.colorScheme.primary)
        msg.isRecruiterRelated -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = cardShape,
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .testTag("email_card_${msg.uid}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.testTag("email_checkbox_${msg.uid}")
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (msg.folder == "SENT") "To: ${msg.to}" else msg.from,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (msg.isRecruiterRelated) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MarkEmailRead,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Recruiter",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
                Text(
                    text = msg.subject,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = msg.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (msg.date.isNotBlank()) {
                    Text(
                        text = msg.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun extractEmailAddress(header: String): String {
    val match = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}").find(header)
    return match?.value ?: header.trim()
}
