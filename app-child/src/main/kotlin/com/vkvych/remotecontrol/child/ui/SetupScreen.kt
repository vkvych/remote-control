package com.vkvych.remotecontrol.child.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vkvych.remotecontrol.child.util.LocalAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(viewModel: SetupViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Permissions are granted in system screens, so the only reliable moment to re-read them is
    // when the user lands back here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Remote Control Agent") }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item { StatusCard(state, viewModel::startAgent, viewModel::stopAgent) }
            item {
                PairingCard(
                    state = state,
                    onShowCode = viewModel::showPairingCode,
                    onHideCode = viewModel::hidePairingCode,
                    onUnpair = viewModel::unpair,
                )
            }
            item { ChecklistCard(state) }
            item { DeviceOwnerCard(state) }
        }
    }
}

@Composable
private fun StatusCard(state: SetupUiState, onStart: () -> Unit, onStop: () -> Unit) {
    SectionCard(title = "Agent") {
        StatusPill(
            ok = state.agentRunning,
            text = if (state.agentRunning) "Running on port ${state.port}" else "Stopped",
        )

        Spacer(Modifier.height(8.dp))
        Text(state.deviceName, style = MaterialTheme.typography.bodyLarge)

        if (state.agentRunning) {
            Spacer(Modifier.height(8.dp))
            if (state.addresses.isEmpty()) {
                Text(
                    "No network address yet — connect to Wi-Fi or start Tailscale.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Enter one of these in the parent app:", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                state.addresses.forEach { address -> AddressRow(address) }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (state.agentRunning) {
            OutlinedButton(onClick = onStop) { Text("Stop") }
        } else {
            Button(onClick = onStart) { Text("Start agent") }
        }
    }
}

@Composable
private fun AddressRow(address: LocalAddress) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = address.address,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (address.viaTailscale) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = if (address.viaTailscale) {
                "Tailscale — works anywhere"
            } else {
                "${address.interfaceName} — same network only"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairingCard(
    state: SetupUiState,
    onShowCode: () -> Unit,
    onHideCode: () -> Unit,
    onUnpair: () -> Unit,
) {
    SectionCard(title = "Pairing") {
        if (state.paired) {
            StatusPill(ok = true, text = "Paired with ${state.controllerName ?: "a parent device"}")
            Spacer(Modifier.height(8.dp))
            Text(
                "Pairing again replaces the current parent device and immediately locks out the old one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            StatusPill(ok = false, text = "Not paired")
        }

        val code = state.pairingCode
        if (code != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = code,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Expires in ${state.pairingSecondsLeft}s",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (code == null) {
                Button(onClick = onShowCode) {
                    Text(if (state.paired) "Pair another device" else "Show pairing code")
                }
            } else {
                OutlinedButton(onClick = onHideCode) { Text("Cancel") }
            }
            if (state.paired) {
                TextButton(onClick = onUnpair) { Text("Unpair") }
            }
        }
    }
}

@Composable
private fun ChecklistCard(state: SetupUiState) {
    val context = LocalContext.current
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Nothing to do: the screen re-reads the real permission state when it resumes.
    }

    SectionCard(title = "Permissions") {
        ChecklistRow(
            granted = state.notificationsGranted,
            title = "Notifications",
            explanation = "Lets the agent show that it is running. Android needs this for the " +
                "foreground service notification to be visible.",
            actionLabel = "Grant",
            onAction = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
        ChecklistRow(
            granted = state.dndAccessGranted,
            title = "Do Not Disturb access",
            explanation = "Required to change ring and notification volume while Do Not Disturb " +
                "is on — without it those commands fail exactly when you need them.",
            actionLabel = "Open settings",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            },
        )
        ChecklistRow(
            granted = state.batteryUnrestricted,
            title = "Unrestricted battery",
            explanation = "Stops One UI from putting the agent to sleep, which would leave the " +
                "device unreachable until somebody opens this app.",
            actionLabel = "Allow",
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        )
    }
}

@Composable
private fun DeviceOwnerCard(state: SetupUiState) {
    SectionCard(title = "Device Owner") {
        StatusPill(
            ok = state.deviceOwner,
            text = if (state.deviceOwner) "Provisioned" else "Not provisioned",
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.deviceOwner) {
                "App management will light up here once the parent app ships those controls."
            } else {
                "Volume control works without this. Provisioning unlocks hiding and uninstalling " +
                    "apps later — see docs/SETUP.md. It has to be done over ADB while the device " +
                    "has no accounts on it, so it is worth doing during initial setup."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ChecklistRow(
    granted: Boolean,
    title: String,
    explanation: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(ok = granted, text = title)
            if (!granted) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
        if (!granted) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A coloured dot plus a label, so the screen reads at a glance without an icon dependency. */
@Composable
private fun StatusPill(ok: Boolean, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(
                    color = if (ok) OkGreen else MaterialTheme.colorScheme.error,
                    shape = CircleShape,
                ),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private val OkGreen = Color(0xFF2E7D32)
