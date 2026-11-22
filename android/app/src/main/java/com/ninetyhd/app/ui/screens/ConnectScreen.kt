package com.ninetyhd.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninetyhd.app.data.PreferencesManager
import com.ninetyhd.app.data.remote.ApiClient
import com.ninetyhd.app.data.sync.SyncWorker
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val context = LocalContext.current
    val preferencesManager = PreferencesManager(context)
    val scope = rememberCoroutineScope()

    var serverAddress by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "90HD",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Persistent Reminders",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Connect to your server",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = serverAddress,
            onValueChange = {
                serverAddress = it
                errorMessage = null
            },
            label = { Text("Server Address") },
            placeholder = { Text("e.g. 192.168.1.10:8090") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    scope.launch { attemptConnect(serverAddress, context, preferencesManager, onConnected, { isLoading = it }, { errorMessage = it }) }
                }
            ),
            isError = errorMessage != null
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch { attemptConnect(serverAddress, context, preferencesManager, onConnected, { isLoading = it }, { errorMessage = it }) }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && serverAddress.isNotBlank()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isLoading) "Connecting..." else "Connect")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enter the IP address or URL of your 90HD server",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private suspend fun attemptConnect(
    address: String,
    context: android.content.Context,
    preferencesManager: PreferencesManager,
    onConnected: () -> Unit,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit
) {
    if (address.isBlank()) {
        setError("Please enter a server address")
        return
    }

    setLoading(true)
    setError(null)

    try {
        val api = ApiClient.getApi(address)
        val response = api.health()
        if (response.isSuccessful && response.body()?.app == "90HD") {
            preferencesManager.setServerUrl(ApiClient.normalizeUrl(address))
            // Kick off an initial sync so data flows right after connecting.
            SyncWorker.triggerImmediate(context)
            onConnected()
        } else {
            setError("Server responded but doesn't appear to be a 90HD instance")
        }
    } catch (e: Exception) {
        setError("Failed to connect: ${e.localizedMessage ?: "Unknown error"}")
    } finally {
        setLoading(false)
    }
}
