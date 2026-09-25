package com.learntoplay.app.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.learntoplay.app.data.repository.PinCheckResult
import kotlinx.coroutines.launch

/** Gate into Admin Mode. First run: set a PIN. After that: enter it to unlock parent screens.
 * Repeated wrong guesses trigger a short, increasing lockout (handled in AdminRepository) so
 * the PIN can't just be brute-forced by trying every combination back to back.
 *
 * Also the entry point for viewing a *different* (child's) device remotely — that flow needs
 * no local PIN at all, since on a parent's own separate phone this screen's PIN doesn't apply
 * to anything (this phone was never set up as a child device). */
@Composable
fun AdminPinScreen(viewModel: AdminViewModel, onUnlocked: () -> Unit, onViewRemoteDevice: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var needsSetup by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedOutSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) { needsSetup = !viewModel.isPinSet() }

    // Tick the lockout countdown once a second while one is active, so the parent sees it
    // count down rather than having to keep tapping Unlock to find out if it's over yet.
    LaunchedEffect(lockedOutSeconds > 0) {
        while (lockedOutSeconds > 0) {
            kotlinx.coroutines.delay(1000)
            lockedOutSeconds = (lockedOutSeconds - 1).coerceAtLeast(0)
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (needsSetup) {
                null -> CircularProgressIndicator()
                true -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Set a Parent PIN", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(pin, { pin = it }, label = { Text("New PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(confirmPin, { confirmPin = it }, label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        if (pin.length < 4) { error = "PIN must be at least 4 digits"; return@Button }
                        if (pin != confirmPin) { error = "PINs don't match"; return@Button }
                        scope.launch { viewModel.setPin(pin); onUnlocked() }
                    }) { Text("Save PIN") }
                }
                false -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Parent PIN", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        pin, { pin = it }, label = { Text("Enter PIN") },
                        enabled = lockedOutSeconds <= 0,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled = lockedOutSeconds <= 0,
                        onClick = {
                            scope.launch {
                                when (val result = viewModel.verifyPin(pin)) {
                                    is PinCheckResult.Correct -> onUnlocked()
                                    is PinCheckResult.Incorrect -> {
                                        error = "Incorrect PIN — ${result.attemptsRemaining} " +
                                            "attempt${if (result.attemptsRemaining == 1) "" else "s"} left"
                                    }
                                    is PinCheckResult.LockedOut -> {
                                        lockedOutSeconds = result.secondsRemaining
                                        error = "Too many attempts. Try again in ${result.secondsRemaining}s."
                                    }
                                }
                            }
                        }
                    ) { Text(if (lockedOutSeconds > 0) "Locked (${lockedOutSeconds}s)" else "Unlock") }
                }
            }
        }

        // Deliberately outside the PIN gate above — this is for viewing a *different* device's
        // synced data, which has nothing to do with this phone's own local PIN.
        // Hidden in the MVP build — see FeatureFlags.REMOTE_AND_PARENT_ACCOUNT.
        if (com.learntoplay.app.FeatureFlags.REMOTE_AND_PARENT_ACCOUNT) {
            TextButton(onClick = onViewRemoteDevice, modifier = Modifier.fillMaxWidth()) {
                Text("View a paired child device")
            }
        }
    }
}
