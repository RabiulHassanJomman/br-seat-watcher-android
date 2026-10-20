package com.iqbal.seatwatcher.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

private const val STEPS = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(vm: AppViewModel, onLogin: () -> Unit, onFinish: () -> Unit, onBack: (() -> Unit)?) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    // Re-read system state whenever we come back from a settings screen.
    val notifOk = remember(tick) { SystemChecks.notificationsAllowed(ctx) }
    val batteryOk = remember(tick) { SystemChecks.batteryUnrestricted(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up (${step + 1} of $STEPS)") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(progress = { (step + 1) / STEPS.toFloat() }, modifier = Modifier.fillMaxWidth())
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    0 -> {
                        Title("1. Allow notifications")
                        Body("Seat alerts are notifications. Without this permission you won't hear about seats.")
                        Status(notifOk, "Notifications allowed", "Notifications are off")
                        if (!notifOk) Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else SystemChecks.openNotificationSettings(ctx)
                        }) { Text("Allow notifications") }
                        if (!notifOk) TextButton(onClick = { SystemChecks.openNotificationSettings(ctx) }) { Text("Open notification settings") }
                    }
                    1 -> {
                        Title("2. Allow background activity")
                        Body("Android pauses apps in the background to save battery. Exempting this app lets checks run about once a minute with the screen off.")
                        Status(batteryOk, "Background activity allowed", "Battery optimization is on")
                        if (!batteryOk) Button(onClick = { SystemChecks.requestBatteryExemption(ctx) }) { Text("Allow background activity") }
                    }
                    2 -> {
                        Title("3. Phone-specific settings")
                        Body("On a Pixel, step 2 is enough. Also open App info → Battery and make sure it says \"Unrestricted\".")
                        OutlinedButton(onClick = { SystemChecks.openAppDetails(ctx) }) { Text("Open App info") }
                        Spacer(Modifier.padding(4.dp))
                        Text("Other brands need extra steps:", fontWeight = FontWeight.SemiBold)
                        Body("• Xiaomi / Redmi / POCO: Settings → Apps → BR Seat Watcher → Autostart ON, Battery saver → No restrictions; lock the app in Recents.")
                        Body("• Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add BR Seat Watcher.")
                        Body("• Realme / Oppo: App info → Battery usage → Allow background activity, and allow Auto launch.")
                        Body("• Infinix / Tecno: Phone Master → App management → Auto-start → enable; also lock the app in Recents.")
                        TextButton(onClick = { SystemChecks.openUrl(ctx, "https://dontkillmyapp.com") }) { Text("More guides: dontkillmyapp.com") }
                    }
                    else -> {
                        Title("4. Log in to e-ticket")
                        Body("Log in on the Bangladesh Railway site inside the app. The app keeps only the site's session cookies in its private storage. It never stores your password and never books or pays for you.")
                        Button(onClick = onLogin) { Text("Log in") }
                        Body("You can also do this later from the status card on the home screen.")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (step > 0) step-- }, enabled = step > 0) { Text("Back") }
                if (step < STEPS - 1) Button(onClick = { step++ }) { Text("Next") }
                else Button(onClick = onFinish) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun Title(t: String) = Text(t, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Status(ok: Boolean, okText: String, badText: String) {
    Text(
        if (ok) "✓ $okText" else "✗ $badText",
        color = if (ok) com.iqbal.seatwatcher.ui.theme.LocalStatusColors.current.good else MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Medium,
    )
}
