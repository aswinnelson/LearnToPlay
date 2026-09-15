package com.learntoplay.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Parent picks which installed entertainment apps require a passed quiz to open.
 * Tapping "Add app" opens AppPickerDialog, which reads the device's launchable-app list via
 * PackageManager (see InstalledAppsProvider) — this used to be the flagged TODO.
 */
@Composable
fun GatedAppsScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val gatedApps by viewModel.gatedApps.collectAsState(initial = emptyList())
    var showPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("Gated Apps", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { showPicker = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add app")
            }
        }
        Spacer(Modifier.height(16.dp))

        if (gatedApps.isEmpty()) {
            Text(
                "No apps gated yet. Tap + to pick one from this device.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn {
                items(gatedApps) { app ->
                    ListItem(
                        headlineContent = { Text(app.displayName) },
                        trailingContent = {
                            Switch(
                                checked = app.isEnabled,
                                onCheckedChange = { viewModel.setAppGated(app.packageName, app.displayName, it) }
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }

    if (showPicker) {
        AppPickerDialog(
            alreadyGatedPackages = gatedApps.map { it.packageName }.toSet(),
            onAppSelected = { app -> viewModel.setAppGated(app.packageName, app.label, true) },
            onDismiss = { showPicker = false }
        )
    }
}
