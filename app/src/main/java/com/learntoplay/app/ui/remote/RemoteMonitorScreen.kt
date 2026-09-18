package com.learntoplay.app.ui.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.learntoplay.app.remote.FamilySyncRepository
import com.learntoplay.app.remote.RemoteCommandType
import com.learntoplay.app.remote.RemoteFamilySnapshot
import kotlinx.coroutines.launch

/** Lets a parent's own separate phone check on a paired child device — time bank remaining,
 * gated apps, and recent quiz history, read live from Firestore — and now also send a couple
 * of remote commands back (add time, lock the device now). This screen never needs the local
 * Parent PIN — it's reached straight from the PIN screen precisely because this phone usually
 * isn't the one being administered. */
@Composable
fun RemoteMonitorScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var codeInput by remember { mutableStateOf("") }
    var connectedCode by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<RemoteFamilySnapshot?>(null) }
    var liveError by remember { mutableStateOf<String?>(null) }

    // Remote Actions state — separate from the read-only snapshot above since sending a
    // command doesn't wait for or depend on the live listener.
    var addMinutesInput by remember { mutableStateOf("") }
    var sendingCommand by remember { mutableStateOf(false) }
    var confirmLock by remember { mutableStateOf(false) }
    var commandFeedback by remember { mutableStateOf<String?>(null) }

    DisposableEffect(connectedCode) {
        val code = connectedCode
        if (code == null) return@DisposableEffect onDispose {}
        val registration = FamilySyncRepository.listen(
            familyCode = code,
            onUpdate = { snapshot = it },
            onError = { liveError = it }
        )
        onDispose { registration.remove() }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("View a Paired Child Device", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (connectedCode == null) {
            Text(
                "Enter the code shown on the child's phone (Parent Dashboard → Remote Access).",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.uppercase().filter(Char::isLetterOrDigit).take(10) },
                label = { Text("Family code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
            connectError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = codeInput.length >= 6 && !connecting,
                onClick = {
                    connecting = true
                    connectError = null
                    scope.launch {
                        if (FamilySyncRepository.ensureSignedIn()) {
                            connectedCode = codeInput
                        } else {
                            connectError = "Couldn't connect — check your internet connection and try again."
                        }
                        connecting = false
                    }
                }
            ) { Text(if (connecting) "Connecting…" else "Connect") }
        } else {
            liveError?.let {
                Card {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
            }
            val current = snapshot
            if (current == null && liveError == null) {
                Text(
                    "Waiting for data — make sure the child's phone has opened the app recently.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (current != null) {
                Text(
                    "Last updated ${timeAgo(current.lastSyncedAtEpochMillis)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(16.dp))
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Time Bank", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${current.timeBankMinutesRemaining} minute" +
                                "${if (current.timeBankMinutesRemaining == 1) "" else "s"} remaining",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Remote Actions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sent over the same connection as the status above — the child's " +
                                "phone applies it next time it's online.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = addMinutesInput,
                                onValueChange = {
                                    addMinutesInput = it.filter(Char::isDigit).take(3)
                                    commandFeedback = null
                                },
                                label = { Text("Minutes") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = !sendingCommand && (addMinutesInput.toIntOrNull() ?: 0) > 0,
                                onClick = {
                                    val minutes = addMinutesInput.toIntOrNull() ?: return@Button
                                    val code = connectedCode ?: return@Button
                                    sendingCommand = true
                                    scope.launch {
                                        val ok = FamilySyncRepository.sendCommand(
                                            code, RemoteCommandType.ADD_TIME, minutes
                                        )
                                        commandFeedback = if (ok) "Sent: +$minutes min"
                                            else "Couldn't send — check your connection."
                                        if (ok) addMinutesInput = ""
                                        sendingCommand = false
                                    }
                                }
                            ) { Text("Add Time") }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (confirmLock) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    enabled = !sendingCommand,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    onClick = {
                                        val code = connectedCode ?: return@Button
                                        sendingCommand = true
                                        scope.launch {
                                            val ok = FamilySyncRepository.sendCommand(
                                                code, RemoteCommandType.LOCK_NOW
                                            )
                                            commandFeedback = if (ok) "Sent: lock now"
                                                else "Couldn't send — check your connection."
                                            confirmLock = false
                                            sendingCommand = false
                                        }
                                    }
                                ) { Text("Confirm Lock Now") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { confirmLock = false }) { Text("Cancel") }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { confirmLock = true; commandFeedback = null },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Lock Device Now") }
                        }
                        commandFeedback?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Gated Apps", style = MaterialTheme.typography.titleMedium)
                        if (current.gatedApps.isEmpty()) {
                            Text("None configured yet.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            current.gatedApps.forEach { app ->
                                Text(
                                    "${app.displayName} — ${if (app.isGated) "Locked" else "Not gated"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Recent Quizzes", style = MaterialTheme.typography.titleMedium)
                if (current.quizHistory.isEmpty()) {
                    Text("No quizzes taken yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(current.quizHistory) { result ->
                            ListItem(
                                headlineContent = { Text(result.curriculumLabel) },
                                supportingContent = {
                                    Text(
                                        "${result.scorePercent}% • +${result.minutesAwarded} min • " +
                                            timeAgo(result.takenAtEpochMillis)
                                    )
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { connectedCode = null; snapshot = null; liveError = null },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Disconnect") }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

private fun timeAgo(epochMillis: Long): String {
    if (epochMillis == 0L) return "never"
    val diffSeconds = (System.currentTimeMillis() - epochMillis) / 1000
    return when {
        diffSeconds < 60 -> "just now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
        else -> "${diffSeconds / 86400}d ago"
    }
}
