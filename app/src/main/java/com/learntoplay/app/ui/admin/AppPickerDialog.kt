package com.learntoplay.app.ui.admin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.learntoplay.app.util.InstalledAppInfo
import com.learntoplay.app.util.InstalledAppsProvider
import com.learntoplay.app.util.toSafeBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-device app list for picking what to gate. Loading the list touches PackageManager for
 * every installed app's label/icon, which is slow enough to jank the UI thread on some devices —
 * loaded off Dispatchers.Default with a progress indicator while it runs.
 *
 * alreadyGatedPackages lets the list show a checkmark for apps already gated instead of a bare
 * "add" affordance, so re-opening the picker reflects current state.
 */
@Composable
fun AppPickerDialog(
    alreadyGatedPackages: Set<String>,
    onAppSelected: (InstalledAppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppInfo>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { InstalledAppsProvider.getLaunchableApps(context) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Add an app to gate") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                val list = apps
                when {
                    list == null -> Box(
                        Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    else -> {
                        val filtered = list.filter { it.label.contains(query, ignoreCase = true) }
                        LazyColumn {
                            items(filtered, key = { it.packageName }) { app ->
                                AppRow(
                                    app = app,
                                    isGated = app.packageName in alreadyGatedPackages,
                                    onClick = { onAppSelected(app) }
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun AppRow(app: InstalledAppInfo, isGated: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = remember(app.packageName) { app.icon?.toSafeBitmap()?.asImageBitmap() }
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
        } else {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (isGated) {
            Text("Added", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
