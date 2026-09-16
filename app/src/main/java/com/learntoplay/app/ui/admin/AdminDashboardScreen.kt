package com.learntoplay.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.learntoplay.app.util.OverlayPermissions

/** Landing screen for Admin Mode — links to the parent-configurable "privilege" screens,
 * plus an inline Time Bank override so a parent can adjust the child's remaining play
 * time directly, even mid-session. */
@Composable
fun AdminDashboardScreen(
    adminViewModel: AdminViewModel,
    onManageCurriculum: () -> Unit,
    onManageScoreTimeRules: () -> Unit,
    onManageGatedApps: () -> Unit,
    onViewHistory: () -> Unit,
    onExitAdmin: () -> Unit
) {
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(OverlayPermissions.hasOverlayPermission(context)) }
    var hasAccessibilityPermission by remember { mutableStateOf(OverlayPermissions.isAccessibilityServiceEnabled(context)) }

    // Both permissions are granted in a separate Settings screen, so re-check whenever the
    // parent comes back to this screen (e.g. after tapping "Grant" and returning).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = OverlayPermissions.hasOverlayPermission(context)
                hasAccessibilityPermission = OverlayPermissions.isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val minutesRemaining by adminViewModel.timeBankMinutesRemaining.collectAsState(initial = 0)
    // Deliberately NOT reset every time minutesRemaining ticks (which happens once a second
    // during an active gated-app session) — otherwise a parent could never finish typing a
    // new value while the child is mid-session. The current balance is shown separately,
    // live, right above the field.
    var timeBankInput by remember { mutableStateOf("") }
    var justUpdated by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Parent Dashboard", style = MaterialTheme.typography.headlineSmall)

        if (!hasOverlayPermission || !hasAccessibilityPermission) {
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Finish setup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The app needs two permissions to actually lock gated apps behind the quiz.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!hasOverlayPermission) {
                        PermissionRow(
                            label = "Display over other apps",
                            onGrant = { context.startActivity(OverlayPermissions.overlayPermissionIntent(context)) }
                        )
                    }
                    if (!hasAccessibilityPermission) {
                        PermissionRow(
                            label = "Accessibility service",
                            onGrant = { context.startActivity(OverlayPermissions.accessibilitySettingsIntent()) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Time Bank", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Currently $minutesRemaining minute${if (minutesRemaining == 1) "" else "s"} remaining. " +
                        "Changing this applies immediately, even mid-session.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = timeBankInput,
                        onValueChange = {
                            timeBankInput = it.filter(Char::isDigit).take(4)
                            justUpdated = false
                        },
                        label = { Text("Set minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val minutes = timeBankInput.toIntOrNull()
                        if (minutes != null) {
                            adminViewModel.setTimeBankMinutes(minutes)
                            timeBankInput = ""
                            justUpdated = true
                        }
                    }) { Text("Update") }
                }
                if (justUpdated) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Updated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onManageCurriculum, modifier = Modifier.fillMaxWidth()) {
            Text("Select Curriculum")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onManageScoreTimeRules, modifier = Modifier.fillMaxWidth()) {
            Text("Score → Time Rules")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onManageGatedApps, modifier = Modifier.fillMaxWidth()) {
            Text("Gated Apps")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onViewHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Quiz History")
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onExitAdmin, modifier = Modifier.fillMaxWidth()) {
            Text("Back to Child Mode")
        }
    }
}

@Composable
private fun PermissionRow(label: String, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onGrant) { Text("Grant") }
    }
}
