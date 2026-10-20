package com.iqbal.seatwatcher.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqbal.seatwatcher.BuildConfig
import com.iqbal.seatwatcher.data.AlertStyle
import com.iqbal.seatwatcher.data.FakeMode
import com.iqbal.seatwatcher.data.Settings
import com.iqbal.seatwatcher.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, nav: Navigator, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notifOk = remember(tick) { SystemChecks.notificationsAllowed(ctx) }
    val batteryOk = remember(tick) { SystemChecks.batteryUnrestricted(ctx) }
    val exactOk = remember(tick) { SystemChecks.exactAlarms(ctx) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uri = res.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            vm.setSoundUri(uri?.toString() ?: "")
        }
    }
    var quietPicker by remember { mutableStateOf<Int?>(null) } // 0 = start, 1 = end

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Alerts")
            Column(Modifier.selectableGroup()) {
                listOf(AlertStyle.NORMAL to "Normal notification", AlertStyle.LOUD_ALARM to "Loud alarm (loops until you open or stop it)").forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = s.alertStyle == v, role = Role.RadioButton, onClick = { vm.setAlertStyle(v) }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.alertStyle == v, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sound")
                    Text(soundTitle(ctx, s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = {
                    val type = if (s.alertStyle == AlertStyle.LOUD_ALARM) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                    soundPicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    )
                }) { Text("Choose") }
            }
            SwitchRow("Vibrate", s.vibrate) { vm.setVibrate(it) }
            OutlinedButton(onClick = { vm.testAlert() }) { Text("Send test alert") }

            HorizontalDivider()
            Section("Checking")
            var interval by remember(s.intervalSec) { mutableFloatStateOf(s.intervalSec.toFloat()) }
            Text("Check every ${interval.toInt()} s per watch")
            Slider(
                value = interval,
                onValueChange = { interval = it },
                onValueChangeFinished = { vm.setInterval(interval.toInt()) },
                valueRange = Settings.MIN_INTERVAL.toFloat()..Settings.MAX_INTERVAL.toFloat(),
                steps = (Settings.MAX_INTERVAL - Settings.MIN_INTERVAL) / 10 - 1,
            )
            Text(
                "Default 60 s. Minimum 30 s keeps checks polite. At most ${Settings.MAX_ENABLED_WATCHES} enabled watches.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SwitchRow("Quiet hours (alerts without sound)", s.quietEnabled) { on ->
                if (on) vm.setQuietHours(23 * 60, 7 * 60) else vm.setQuietHours(-1, -1)
            }
            if (s.quietEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { quietPicker = 0 }) { Text("From ${hhmm(s.quietStart)}") }
                    OutlinedButton(onClick = { quietPicker = 1 }) { Text("To ${hhmm(s.quietEnd)}") }
                }
            }

            HorizontalDivider()
            Section("Background and permissions")
            CheckRow("Notifications", notifOk, "Fix") {
                if (Build.VERSION.SDK_INT >= 33 && !notifOk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else SystemChecks.openNotificationSettings(ctx)
            }
            CheckRow("Background activity (battery unrestricted)", batteryOk, "Fix") { SystemChecks.requestBatteryExemption(ctx) }
            CheckRow("Exact wake-up alarms", exactOk, "Open") { SystemChecks.openAppDetails(ctx) }
            TextButton(onClick = { nav.push(Screen.Onboarding) }) { Text("Run setup guide again") }

            if (BuildConfig.DEBUG) {
                HorizontalDivider()
                Section("Debug: fake data source")
                Text("Replaces the real site for every check, to test alerts.", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.selectableGroup()) {
                    listOf(FakeMode.OFF to "Off (real site)", FakeMode.NO_SEATS to "Fake: no seats", FakeMode.SEATS to "Fake: seats appear").forEach { (v, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = s.fakeMode == v, role = Role.RadioButton, onClick = { vm.setFakeMode(v) }).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.fakeMode == v, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }

            HorizontalDivider()
            Section("Debug info")
            OutlinedButton(onClick = {
                scope.launch {
                    val text = vm.debugInfo()
                    ctx.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("BR Seat Watcher debug", text))
                    snackbar.showSnackbar("Debug info copied")
                }
            }) { Text("Copy debug info") }

            HorizontalDivider()
            Section("About")
            Text("BR Seat Watcher ${BuildConfig.VERSION_NAME}")
            Text(
                "Alerts only. It reads the public search results page on eticket.railway.gov.bd using your own login session. " +
                    "It never books, fills passenger details, pays, or solves captchas, and never stores your password. No analytics.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    quietPicker?.let { which ->
        val initial = if (which == 0) s.quietStart else s.quietEnd
        val state = rememberTimePickerState(initialHour = (initial.coerceAtLeast(0)) / 60, initialMinute = initial.coerceAtLeast(0) % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { quietPicker = null },
            title = { Text(if (which == 0) "Quiet from" else "Quiet until") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    if (which == 0) vm.setQuietHours(m, s.quietEnd) else vm.setQuietHours(s.quietStart, m)
                    quietPicker = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { quietPicker = null }) { Text("Cancel") } },
        )
    }
}

private fun hhmm(m: Int) = if (m < 0) "--:--" else "%02d:%02d".format(m / 60, m % 60)

private fun soundTitle(ctx: android.content.Context, s: Settings): String {
    if (s.soundUri.isBlank()) return "System default"
    return runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(s.soundUri))?.getTitle(ctx) }.getOrNull() ?: "Custom sound"
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, fixLabel: String, onFix: () -> Unit) {
    val c = LocalStatusColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(if (ok) "✓ OK" else "✗ Needs attention", color = if (ok) c.good else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok) OutlinedButton(onClick = onFix) { Text(fixLabel) }
    }
}
