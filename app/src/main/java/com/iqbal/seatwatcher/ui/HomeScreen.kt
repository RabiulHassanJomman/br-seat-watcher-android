package com.iqbal.seatwatcher.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqbal.seatwatcher.core.engine.AlertEngine
import com.iqbal.seatwatcher.core.source.SiteUrls
import com.iqbal.seatwatcher.data.CheckStatus
import com.iqbal.seatwatcher.domain.GlobalState
import com.iqbal.seatwatcher.domain.ServiceStatus
import com.iqbal.seatwatcher.domain.WatchLabels
import com.iqbal.seatwatcher.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, nav: Navigator, snackbar: SnackbarHostState) {
    val watches by vm.watches.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val alarm by vm.alarmPlaying.collectAsStateWithLifecycle()
    val watching = settings?.watching == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BR Seat Watcher") },
                actions = {
                    IconButton(onClick = { nav.push(Screen.History) }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Alert history")
                    }
                    IconButton(onClick = { nav.push(Screen.Settings) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.push(Screen.Edit(null)) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add watch") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = watches
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    watching = watching, status = status, alarmPlaying = alarm,
                    onToggle = { vm.setWatching(it) },
                    onLogin = { nav.push(Screen.Web(SiteUrls.LOGIN, login = true)) },
                    onOpenSite = { nav.push(Screen.Web(SiteUrls.BASE, login = false)) },
                    onStopAlarm = { vm.stopAlarm() },
                )
            }
            when {
                list == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                list.isEmpty() -> item { EmptyState(onAdd = { nav.push(Screen.Edit(null)) }) }
                else -> items(list, key = { it.watch.id }) { item ->
                    WatchCard(
                        item = item,
                        onClick = { nav.push(Screen.Results(item.watch.id)) },
                        onToggle = { vm.setEnabled(item.watch.id, it) },
                        onEdit = { nav.push(Screen.Edit(item.watch.id)) },
                        onDelete = { vm.delete(item.watch.id) },
                        onOpenSite = { nav.push(Screen.Web(SiteUrls.search(item.watch), login = false)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    watching: Boolean, status: ServiceStatus, alarmPlaying: Boolean,
    onToggle: (Boolean) -> Unit, onLogin: () -> Unit, onOpenSite: () -> Unit, onStopAlarm: () -> Unit,
) {
    val colors = LocalStatusColors.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = System.currentTimeMillis() }
    }

    val line = when {
        !watching -> Line("○ Paused", colors.muted)
        status.state == GlobalState.LOGIN_NEEDED -> Line("Login needed, tap to log in", colors.warn, onLogin)
        status.state == GlobalState.BLOCKED -> Line("Site asked for a check, open it to continue", colors.warn, onOpenSite)
        status.state == GlobalState.OFFLINE -> Line("Offline, retrying", colors.muted)
        status.state == GlobalState.NO_WATCHES -> Line("No enabled watches", colors.muted)
        status.checking -> Line("● Watching: checking now…", colors.good)
        status.nextCheckAt > now -> Line("● Watching: next check in ${(status.nextCheckAt - now + 999) / 1000}s", colors.good)
        status.nextCheckAt > 0 -> Line("● Watching: checking soon…", colors.good)
        else -> Line("● Watching: starting…", colors.good)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (watching && line.color == colors.good) colors.goodContainer.copy(alpha = 0.55f)
            else if (line.color == colors.warn) colors.warnContainer.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (watching) "Watching is on" else "Watching is off", style = MaterialTheme.typography.titleMedium)
                    Text(
                        line.text,
                        color = line.color,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .then(line.action?.let { Modifier.clickable(onClick = it) } ?: Modifier),
                    )
                    if (status.lastCheckAt > 0 && watching) {
                        Text(
                            "Last cycle ${WatchLabels.clock(status.lastCheckAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = watching,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = "Watching master switch" },
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLogin) { Text("Log in / session") }
                if (alarmPlaying) Button(onClick = onStopAlarm) { Text("Stop alarm") }
            }
        }
    }
}

private data class Line(val text: String, val color: Color, val action: (() -> Unit)? = null)

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🚆", style = MaterialTheme.typography.displayLarge, modifier = Modifier.semantics { contentDescription = "Train" })
        Spacer(Modifier.height(12.dp))
        Text("Add your first watch", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pick a route and date. You'll get an alert the moment a seat opens.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Button(onClick = onAdd) { Text("Add watch") }
    }
}

@Composable
private fun WatchCard(
    item: WatchItem,
    onClick: () -> Unit, onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onOpenSite: () -> Unit,
) {
    val w = item.watch
    val colors = LocalStatusColors.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).alpha(if (w.enabled) 1f else 0.55f)) {
                    Text(WatchLabels.route(w), style = MaterialTheme.typography.titleMedium)
                    Text(WatchLabels.longDate(w.dateIso), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = w.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = "Enable watch ${WatchLabels.route(w)}" },
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Open on site") }, onClick = { menu = false; onOpenSite() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp).alpha(if (w.enabled) 1f else 0.55f)) {
                SuggestionChip(onClick = onClick, label = { Text(WatchLabels.filter(w)) })
                val (text, color) = liveLine(item, colors.good, colors.bad, colors.warn, colors.muted)
                Text(text, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val checked = item.state?.lastCheckedAt ?: 0
                if (checked > 0) {
                    Text(
                        "Checked ${WatchLabels.clock(checked)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val snoozed = item.state?.snoozeUntil ?: 0
                if (snoozed > System.currentTimeMillis()) {
                    Text("Snoozed until ${WatchLabels.clock(snoozed)}", style = MaterialTheme.typography.bodySmall, color = colors.warn)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete watch?") },
            text = { Text("${WatchLabels.route(w)}, ${WatchLabels.longDate(w.dateIso)}") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** "✓ Seats now: SUBORNO (701) AC_S 1 · F_SEAT 14" or "✗ No seats". */
fun liveLine(item: WatchItem, good: Color, bad: Color, warn: Color, muted: Color): Pair<String, Color> {
    val st = item.state
    return when (st?.lastStatus) {
        null, CheckStatus.NEVER.name -> "Not checked yet" to muted
        CheckStatus.LOGIN_REQUIRED.name -> "Login needed" to warn
        CheckStatus.BLOCKED.name -> "Site asked for a check" to warn
        CheckStatus.OFFLINE.name -> "Offline at last check" to muted
        CheckStatus.PARSE_FAILED.name -> "Couldn't read the page at last check" to warn
        else -> {
            val seats = AlertEngine.available(item.watch.copy(enabled = true), item.snapshot)
            if (seats.isEmpty()) "✗ No seats" to bad
            else {
                val byTrain = seats.groupBy { it.train }.entries.joinToString("; ") { (train, cs) ->
                    WatchLabels.shortTrain(train) + " " + cs.joinToString(" · ") { "${it.label} ${it.count}" }
                }
                "✓ Seats now: $byTrain" to good
            }
        }
    }
}
