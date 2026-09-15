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
import kotlinx.coroutines.launch

/** Gate into Admin Mode. First run: set a PIN. After that: enter it to unlock parent screens. */
@Composable
fun AdminPinScreen(viewModel: AdminViewModel, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var needsSetup by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { needsSetup = !viewModel.isPinSet() }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
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
                OutlinedTextField(pin, { pin = it }, label = { Text("Enter PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        if (viewModel.verifyPin(pin)) onUnlocked() else error = "Incorrect PIN"
                    }
                }) { Text("Unlock") }
            }
        }
    }
}
