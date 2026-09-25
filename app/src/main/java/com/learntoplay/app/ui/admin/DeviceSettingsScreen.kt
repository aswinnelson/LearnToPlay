package com.learntoplay.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.learntoplay.app.BuildConfig
import com.learntoplay.app.FeatureFlags
import com.learntoplay.app.admin.TamperGuard
import com.learntoplay.app.remote.AuthOutcome
import kotlinx.coroutines.launch

/** Everything a parent sets up once and rarely revisits: a Parent Account (email sign-up/
 * sign-in), Tamper Protection (Device Owner lockdown), the Remote Access pairing code, and —
 * debug builds only — a way to force a test crash and confirm Crashlytics is reporting. Split
 * out of [AdminDashboardScreen] so that screen stays short enough to not need scrolling for
 * its everyday buttons. */
@Composable
fun DeviceSettingsScreen(adminViewModel: AdminViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var isDeviceOwner by remember { mutableStateOf(TamperGuard.isDeviceOwner(context)) }
    var tamperMessage by remember { mutableStateOf<String?>(null) }
    var familyCode by remember { mutableStateOf<String?>(null) }
    var justCopied by remember { mutableStateOf(false) }

    // Parent Account state — see AuthRepository. parentEmail is re-read from AdminViewModel
    // (a plain synchronous FirebaseAuth read, not a Flow) right after any sign-up/sign-in/
    // sign-out call resolves, the same pattern isDeviceOwner above already uses.
    var parentEmail by remember { mutableStateOf(adminViewModel.currentParentEmail()) }
    var authEmail by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { familyCode = adminViewModel.getFamilyCode() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDeviceOwner = TamperGuard.isDeviceOwner(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Device & Remote Settings", style = MaterialTheme.typography.headlineSmall)

        // Hidden in the MVP build — see FeatureFlags.REMOTE_AND_PARENT_ACCOUNT.
        if (FeatureFlags.REMOTE_AND_PARENT_ACCOUNT) {
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Parent Account", style = MaterialTheme.typography.titleMedium)
                    val signedInEmail = parentEmail
                    if (signedInEmail != null) {
                        Text(
                            "Signed in as $signedInEmail. This is what lets you be recognized as " +
                                "the same parent if you ever set this app up on a second phone.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            adminViewModel.signOutParent()
                            parentEmail = adminViewModel.currentParentEmail()
                            authError = null
                        }) { Text("Sign Out") }
                    } else {
                        Text(
                            "Optional for now — not required to use this app. Sets up an account " +
                                "so you can be recognized as the same parent later, e.g. on a " +
                                "second phone.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = authEmail,
                            onValueChange = { authEmail = it; authError = null },
                            label = { Text("Email") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            enabled = !authBusy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = authPassword,
                            onValueChange = { authPassword = it; authError = null },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            enabled = !authBusy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val canSubmit = authEmail.isNotBlank() && authPassword.isNotBlank() && !authBusy
                            Button(
                                enabled = canSubmit,
                                onClick = {
                                    authBusy = true
                                    authError = null
                                    val email = authEmail.trim()
                                    val password = authPassword
                                    coroutineScope.launch {
                                        when (val result = adminViewModel.signUpParent(email, password)) {
                                            AuthOutcome.Success -> {
                                                parentEmail = adminViewModel.currentParentEmail()
                                                authPassword = ""
                                            }
                                            is AuthOutcome.Failure -> authError = result.message
                                        }
                                        authBusy = false
                                    }
                                }
                            ) { Text("Create Account") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                enabled = canSubmit,
                                onClick = {
                                    authBusy = true
                                    authError = null
                                    val email = authEmail.trim()
                                    val password = authPassword
                                    coroutineScope.launch {
                                        when (val result = adminViewModel.signInParent(email, password)) {
                                            AuthOutcome.Success -> {
                                                parentEmail = adminViewModel.currentParentEmail()
                                                authPassword = ""
                                            }
                                            is AuthOutcome.Failure -> authError = result.message
                                        }
                                        authBusy = false
                                    }
                                }
                            ) { Text("Sign In") }
                            if (authBusy) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                        authError?.let { message ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

        }

        // Hidden in the MVP build — see FeatureFlags.TAMPER_PROTECTION.
        if (FeatureFlags.TAMPER_PROTECTION) {
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Tamper Protection", style = MaterialTheme.typography.titleMedium)
                    if (isDeviceOwner) {
                        Text(
                            "Device Owner is active. Blocks uninstalling this app, Safe Mode, " +
                                "and factory reset from Settings.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = {
                                tamperMessage = when (val result = TamperGuard.applyProtections(context)) {
                                    TamperGuard.ApplyResult.Applied -> "Protections applied."
                                    is TamperGuard.ApplyResult.Failed -> result.reason
                                }
                            }) { Text("Apply") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                tamperMessage = when (val result = TamperGuard.removeProtections(context)) {
                                    TamperGuard.ApplyResult.Applied -> "Protections removed."
                                    is TamperGuard.ApplyResult.Failed -> result.reason
                                }
                            }) { Text("Remove") }
                        }
                    } else {
                        Text(
                            "Not set up. One-time step outside the app — see setup notes.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    tamperMessage?.let { message ->
                        Spacer(Modifier.height(4.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

        }

        // Hidden in the MVP build — see FeatureFlags.REMOTE_AND_PARENT_ACCOUNT.
        if (FeatureFlags.REMOTE_AND_PARENT_ACCOUNT) {
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Remote Access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Share once with a parent's separate phone to check in remotely. " +
                            "Treat it like a shared password.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    val code = familyCode
                    if (code == null) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(code, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = {
                                clipboardManager.setText(AnnotatedString(code))
                                justCopied = true
                            }) { Text(if (justCopied) "Copied" else "Copy") }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { adminViewModel.syncNow() }) { Text("Sync Now") }
                    }
                }
            }

        }

        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Crash Reporting (debug only)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Forces a test crash to confirm it reaches the Firebase console.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        throw RuntimeException("Test crash from Device Settings (debug build only)")
                    }) { Text("Force Test Crash") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
        Spacer(Modifier.height(24.dp))
    }
}
