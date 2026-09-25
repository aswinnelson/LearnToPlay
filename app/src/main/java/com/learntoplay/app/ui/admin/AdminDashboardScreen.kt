package com.learntoplay.app.ui.admin

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.learntoplay.app.util.AllowedWindowChecker
import com.learntoplay.app.util.OverlayPermissions

/** Landing screen for Admin Mode — kept deliberately short (Finish Setup + Time Bank +
 * Allowed Hours + the four content-management buttons) so it fits on a phone screen with
 * little to no scrolling. Tamper Protection, Remote Access, and debug tools live one tap away
 * on [DeviceSettingsScreen] instead, since those are check-once-in-a-while settings, not
 * something a parent opens every time. */
@Composable
fun AdminDashboardScreen(
    adminViewModel: AdminViewModel,
    onManageCurriculum: () -> Unit,
    onManageScoreTimeRules: () -> Unit,
    onManageGatedApps: () -> Unit,
    onViewHistory: () -> Unit,
    onOpenDeviceSettings: () -> Unit,
    onExitAdmin: () -> Unit
) {
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(OverlayPermissions.hasOverlayPermission(context)) }
    var hasAccessibilityPermission by remember { mutableStateOf(OverlayPermissions.isAccessibilityServiceEnabled(context)) }

    val minutesRemaining by adminViewModel.timeBankMinutesRemaining.collectAsState(initial = 0)
    // Deliberately NOT reset every time minutesRemaining ticks (which happens once a second
    // during an active gated-app session) — otherwise a parent could never finish typing a
    // new value while the child is mid-session. The current balance is shown separately,
    // live, right above the field.
    var timeBankInput by remember { mutableStateOf("") }
    var justUpdated by remember { mutableStateOf(false) }

    val allowedWindow by adminViewModel.allowedWindow.collectAsState(
        initial = AllowedWindow(
            enabled = false,
            startMinute = AdminViewModel.DEFAULT_ALLOWED_WINDOW_START,
            endMinute = AdminViewModel.DEFAULT_ALLOWED_WINDOW_END
        )
    )

    // Re-check permissions every time this screen is shown (returning from Settings after
    // granting one, for instance). Simpler than the lifecycle-observer version this screen
    // used to have, now that there's much less on it.
    LaunchedEffect(Unit) {
        hasOverlayPermission = OverlayPermissions.hasOverlayPermission(context)
        hasAccessibilityPermission = OverlayPermissions.isAccessibilityServiceEnabled(context)
    }

    // Still scrollable as a safety net for smaller phones or larger system font sizes, but
    // this screen is now short enough that scrolling shouldn't normally be needed.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Parent Dashboard", style = MaterialTheme.typography.headlineSmall)

        if (!hasOverlayPermission || !hasAccessibilityPermission) {
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Finish setup", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
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
                    "$minutesRemaining minute${if (minutesRemaining == 1) "" else "s"} remaining",
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
                    Text(
                        "Updated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Allowed Hours", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Restrict gated apps to specific hours of the day, on top of the time " +
                        "bank. Outside this window they stay locked no matter how much time " +
                        "is banked.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = allowedWindow.enabled,
                        onCheckedChange = {
                            adminViewModel.setAllowedWindow(it, allowedWindow.startMinute, allowedWindow.endMinute)
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    adminViewModel.setAllowedWindow(
                                        allowedWindow.enabled,
                                        hour * 60 + minute,
                                        allowedWindow.endMinute
                                    )
                                },
                                allowedWindow.startMinute / 60,
                                allowedWindow.startMinute % 60,
                                false
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("From ${AllowedWindowChecker.format12Hour(allowedWindow.startMinute)}") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    adminViewModel.setAllowedWindow(
                                        allowedWindow.enabled,
                                        allowedWindow.startMinute,
                                        hour * 60 + minute
                                    )
                                },
                                allowedWindow.endMinute / 60,
                                allowedWindow.endMinute % 60,
                                false
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("To ${AllowedWindowChecker.format12Hour(allowedWindow.endMinute)}") }
                }
                if (allowedWindow.enabled && allowedWindow.startMinute > allowedWindow.endMinute) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Crosses midnight — allowed overnight from " +
                            "${AllowedWindowChecker.format12Hour(allowedWindow.startMinute)} until " +
                            "${AllowedWindowChecker.format12Hour(allowedWindow.endMinute)}.",
                        style = MaterialTheme.typography.bodySmall
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
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenDeviceSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Device & Remote Settings")
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onExitAdmin, modifier = Modifier.fillMaxWidth()) {
            Text("Back to Child Mode")
        }
        Spacer(Modifier.height(24.dp))
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
