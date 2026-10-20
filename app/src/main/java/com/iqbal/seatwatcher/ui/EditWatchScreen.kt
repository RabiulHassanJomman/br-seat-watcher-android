package com.iqbal.seatwatcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.iqbal.seatwatcher.core.model.TrainSnapshot
import com.iqbal.seatwatcher.core.model.WatchMode
import com.iqbal.seatwatcher.core.source.SourceResult
import com.iqbal.seatwatcher.data.WatchEntity
import com.iqbal.seatwatcher.domain.WatchLabels
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val TRAIN_NAME = Regex("""^[A-Z0-9 ,.'&\-]+\(\d{2,5}\)$""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditWatchScreen(vm: AppViewModel, id: Long?, onDone: () -> Unit, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<WatchEntity?>(null) }
    var loaded by remember { mutableStateOf(id == null) }

    var from by remember { mutableStateOf("Chattogram") }
    var to by remember { mutableStateOf("Dhaka") }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var mode by remember { mutableStateOf(WatchMode.ANY) }
    var train by remember { mutableStateOf("") }
    var seatClass by remember { mutableStateOf("") }
    var repeatText by remember { mutableStateOf("0") }
    var alertOnIncrease by remember { mutableStateOf(false) }

    var trains by remember { mutableStateOf<List<TrainSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var manualTrain by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != null) {
            vm.getWatch(id)?.let { e ->
                existing = e
                from = e.fromCity; to = e.toCity
                date = runCatching { LocalDate.parse(e.dateIso) }.getOrDefault(LocalDate.now().plusDays(1))
                mode = runCatching { WatchMode.valueOf(e.mode) }.getOrDefault(WatchMode.ANY)
                train = e.trainName.orEmpty()
                seatClass = e.seatClass.orEmpty()
                alertOnIncrease = e.alertOnIncrease
                repeatText = e.repeatMinutes.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
                manualTrain = train.isNotBlank()
            }
            loaded = true
        }
    }

    val repeat = repeatText.replace(',', '.').toDoubleOrNull()
    val trainNames = trains.map { it.name }
    val classOptions = when {
        trains.isEmpty() -> KNOWN_CLASSES
        mode == WatchMode.ONE && train.isNotBlank() ->
            trains.firstOrNull { it.name == train }?.classes?.map { it.label }?.distinct() ?: KNOWN_CLASSES
        else -> trains.flatMap { t -> t.classes.map { it.label } }.distinct()
    }
    val routeError = when {
        from.isBlank() || to.isBlank() -> "Choose both stations"
        from.trim().equals(to.trim(), ignoreCase = true) -> "From and To must differ"
        else -> null
    }
    val trainError = if (mode == WatchMode.ONE && !TRAIN_NAME.matches(train.trim()))
        "Pick a train, or type it exactly as on the site, e.g. SUBORNO EXPRESS (701)" else null
    val valid = loaded && routeError == null && trainError == null &&
        !date.isBefore(LocalDate.now()) && repeat != null && repeat >= 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "Add watch" else "Edit watch") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComboField("From", from, { from = it; trains = emptyList() }, STATION_SUGGESTIONS, editable = true)
                    ComboField("To", to, { to = it; trains = emptyList() }, STATION_SUGGESTIONS, editable = true)
                }
                IconButton(
                    onClick = { val f = from; from = to; to = f; trains = emptyList() },
                    modifier = Modifier.semantics { contentDescription = "Swap from and to" },
                ) { Text("⇅", style = MaterialTheme.typography.headlineSmall) }
            }
            routeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            OutlinedTextField(
                value = WatchLabels.longDate(date.toString()),
                onValueChange = {},
                readOnly = true,
                label = { Text("Journey date") },
                trailingIcon = {
                    IconButton(onClick = { showDate = true }) { Icon(Icons.Default.DateRange, contentDescription = "Pick date") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (date.isBefore(LocalDate.now())) {
                Text("This date has passed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WatchMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, WatchMode.entries.size),
                    ) { Text(if (m == WatchMode.ANY) "Any train" else "One train") }
                }
            }

            FilledTonalButton(
                onClick = {
                    loading = true; loadError = null
                    scope.launch {
                        val r = vm.loadTrains(from.trim(), to.trim(), date.toString())
                        loading = false
                        if (r is SourceResult.Success) {
                            trains = r.snapshot
                            manualTrain = false
                            if (train.isNotBlank() && train !in r.snapshot.map { it.name }) train = ""
                        } else {
                            loadError = vm.describe(r) + ". You can type the train manually."
                            manualTrain = true
                        }
                    }
                },
                enabled = !loading && routeError == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Loading trains…")
                } else Text(if (trains.isEmpty()) "Load trains" else "Reload trains (${trains.size} found)")
            }
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (mode == WatchMode.ONE) {
                if (trains.isNotEmpty() && !manualTrain) {
                    ComboField("Train", train, { train = it; seatClass = "" }, trainNames, editable = false)
                    TextButton(onClick = { manualTrain = true }) { Text("Type the train instead") }
                } else {
                    OutlinedTextField(
                        value = train,
                        onValueChange = { train = it.uppercase(); seatClass = "" },
                        label = { Text("Train (name and code)") },
                        placeholder = { Text("SUBORNO EXPRESS (701)") },
                        singleLine = true,
                        isError = trainError != null && train.isNotBlank(),
                        supportingText = { Text("Exactly as on the site, e.g. SUBORNO EXPRESS (701)") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (trains.isNotEmpty()) TextButton(onClick = { manualTrain = false }) { Text("Pick from loaded trains") }
                }
            }

            ComboField(
                "Seat class",
                seatClass.ifBlank { "Any class" },
                { seatClass = if (it == "Any class") "" else it },
                listOf("Any class") + classOptions,
                editable = false,
            )

            OutlinedTextField(
                value = repeatText,
                onValueChange = { repeatText = it },
                label = { Text("Remind me again every N minutes while seats remain") },
                supportingText = { Text("0 = alert only when seats first appear") },
                isError = repeat == null || repeat < 0,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = alertOnIncrease, role = Role.Switch, onValueChange = { alertOnIncrease = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Also alert when seats increase")
                    Text(
                        "e.g. 3 → 5 after a cancellation. Off: alert only when seats appear (0 → some).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = alertOnIncrease, onCheckedChange = null)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val e = WatchEntity(
                        id = existing?.id ?: 0,
                        fromCity = from.trim(), toCity = to.trim(), dateIso = date.toString(),
                        mode = mode.name,
                        trainName = if (mode == WatchMode.ONE) train.trim() else null,
                        seatClass = seatClass.ifBlank { null },
                        repeatMinutes = repeat ?: 0.0,
                        alertOnIncrease = alertOnIncrease,
                        enabled = existing?.enabled ?: true,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    )
                    vm.save(e, onDone)
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }

    if (showDate) {
        val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
                override fun isSelectableYear(year: Int) = year >= LocalDate.now().year
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        trains = emptyList()
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** Text field with a dropdown. editable = searchable free text; otherwise a picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    options: List<String>,
    editable: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = if (editable && value.isNotBlank())
        options.filter { it.contains(value.trim(), ignoreCase = true) && !it.equals(value.trim(), ignoreCase = true) }
    else options
    val open = expanded && filtered.isNotEmpty()
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            readOnly = !editable,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .menuAnchor(if (editable) MenuAnchorType.PrimaryEditable else MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { expanded = false }) {
            filtered.take(60).forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onValueChange(opt); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
