# Architecture

```
core/  (pure Kotlin, no Android imports, unit-tested)
  model/     ClassSeats, TrainSnapshot, Watch, WatchState
  parser/    PageParser: page text -> List<TrainSnapshot>        (port of userscript v3.0)
  engine/    AlertEngine: snapshot + previous state -> Decision   (port of userscript v3.0)
  source/    SeatSource interface, SourceResult, SiteUrls, SnapshotCodec

app/
  source/    WebViewSeatSource (hidden WebView), FakeSeatSource (debug), AppSeatSource (picks one)
  data/      Room (watches, seen_counts, watch_state, alert_log), DataStore settings
  domain/    WatchChecker (fetch -> persist -> evaluate -> notify -> log),
             WatchController (master switch, enable/save/delete/snooze), StatusBus (live status)
  service/   WatchService (foreground, specialUse), AlarmScheduler/AlarmReceiver (exact wake-ups),
             BootReceiver, WatchdogWorker (15-min WorkManager)
  notify/    Notifier (channels, alerts, warnings), AlarmPlayer (USAGE_ALARM loop), action receiver
  ui/        Compose screens: Home, Edit, Results, Web (login/booking), History, Settings, Onboarding
```

## Check flow

```
WatchService loop (every interval, or kicked)
  for each enabled watch (max 3, 2-5 s jitter):
    [wake lock 60 s] WatchChecker.check(watch)
       AppSeatSource.fetch -> WebViewSeatSource (Main thread, one hidden WebView on eticket.railway.gov.bd)
          fetch() search-trips-v2 inside the page with the site's own saved token
          -> TripJsonParser.parse (online + offline per class)
          401 -> reload the site page so it renews its token -> retry once -> LoginRequired
          -> Success | LoginRequired | Blocked | Offline | ParseFailed(sample, never the token)
       Success: AlertEngine.evaluate with state from Room -> save merged counts
                Alert -> Notifier.seatAlert (unless snoozed; quiet hours = silent) + alert_log
  global state -> StatusBus + ongoing notification + warnings
  next delay = interval, or backoff (Offline 30 s / Login,Blocked 60 s, doubling, max 300 s)
  AlarmScheduler.setExactAndAllowWhileIdle(next) ; wait for timeout or kick
```

## State rules (from the spec, enforced in `AlertEngine` and `WatchController`)

- `searchKey = from|to|dateIso`. If the key changes, the next check becomes a new baseline.
- Counts are merged, never replaced. A card that is missing during a slow render keeps its last count, so it cannot trigger a false alert when it comes back.
- Pressing Start, switching a watch back on, or saving an edit clears that watch's state. Its next check is then a baseline and sends no alert.
- Disabled watches never alert. Each watch's state lives in its own rows, so watches never share state.

## Why the API, not the rendered page

Since Oct 2026 the results page only searches when reached through the site's form, so loading the results URL directly shows a spinner forever. The page's own data comes from `search-trips-v2` (verified with DevTools), which the app now calls from inside the site's page. `PageParser` (rendered text) is kept in core for reference and tests.

## Why these platform choices

- **`specialUse` foreground service:** Android 15 caps `dataSync` and `mediaProcessing` services at about 6 h per 24 h. `specialUse` has no such cap, and the manifest declares its subtype.
- **Exact allow-while-idle alarm:** a foreground service does not keep the CPU awake, so a plain `delay()` can stretch for many minutes while the phone is in deep sleep. The alarm (granted through `USE_EXACT_ALARM`) wakes the CPU for each check, and the wake lock is held only during the check. Firing an exact alarm also lets the app restart the service from the background.
- **Battery-optimization exemption:** without it, Doze blocks network access between maintenance windows.
