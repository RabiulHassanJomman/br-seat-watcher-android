package com.iqbal.seatwatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqbal.seatwatcher.core.model.WatchMode
import com.iqbal.seatwatcher.core.source.SiteUrls
import com.iqbal.seatwatcher.domain.WatchLabels
import com.iqbal.seatwatcher.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(vm: AppViewModel, id: Long, nav: Navigator, snackbar: SnackbarHostState) {
    val watches by vm.watches.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val item = watches?.firstOrNull { it.watch.id == id }
    val colors = LocalStatusColors.current

    LaunchedEffect(id) { if (item != null && item.snapshot.isEmpty()) vm.refresh(id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(item?.let { WatchLabels.route(it.watch) } ?: "Results")
                        item?.let {
                            Text(WatchLabels.longDate(it.watch.dateIso), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.refresh(id) }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                },
            )
        },
        floatingActionButton = {
            if (item != null) {
                ExtendedFloatingActionButton(
                    onClick = { nav.push(Screen.Web(SiteUrls.search(item.watch), login = false)) },
                    text = { Text("Open booking") },
                    icon = {},
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text("This watch no longer exists.") }
            return@Scaffold
        }
        val w = item.watch
        val snapshot = item.snapshot
        val wantClass = w.seatClass?.takeIf { it.isNotBlank() }
        PullToRefreshBox(
            isRefreshing = id in refreshing,
            onRefresh = { vm.refresh(id) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    val checked = item.state?.lastCheckedAt ?: 0
                    val (line, color) = liveLine(item, colors.good, colors.bad, colors.warn, colors.muted)
                    Column {
                        Text(line, color = color, fontWeight = FontWeight.Medium)
                        Text(
                            WatchLabels.filter(w) + if (checked > 0) " · checked ${WatchLabels.clock(checked)}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (snapshot.isEmpty()) {
                    item {
                        Text(
                            if (id in refreshing) "Loading trains…" else "No trains to show yet. Pull down to refresh.",
                            modifier = Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(snapshot) { t ->
                    val trainMatches = w.mode != WatchMode.ONE || t.name == w.trainName
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (trainMatches && w.mode == WatchMode.ONE) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                t.classes.forEach { c ->
                                    val match = trainMatches && (wantClass == null || c.label == wantClass)
                                    val bg = if (c.count > 0) colors.good else colors.muted.copy(alpha = 0.25f)
                                    val fg = if (c.count > 0) colors.onGood else MaterialTheme.colorScheme.onSurface
                                    Text(
                                        "${c.label}  ${c.count}",
                                        color = fg,
                                        fontWeight = if (match) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(bg)
                                            .then(if (match) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)) else Modifier)
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                            .semantics {
                                                contentDescription = "${c.label}: ${c.count} seats" + if (match) ", matches your watch" else ""
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
