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
       AppSeatSource.fetch -> WebViewSeatSource (Main thread, one hidden WebView)
          load /booking/train/search?fromcity&tocity&doj&class  (class is required, as the site's form sends)
          the site's own code calls search-trips-v2 with its own headers
          NetHook (read-only) records that response -> TripJsonParser.parse (online + offline)
          no response in 25 s -> PageParser on the rendered text (userscript method)
          HTTP 401 or login page -> LoginRequired; 403/429 or captcha text -> Blocked
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

The results page only runs its search when the URL includes `class` (the site's form always adds it); without it the page spins forever. The data comes from `search-trips-v2`, which needs the site's own device headers (`X-Device-Id`, `X-Device-Key`) besides the login token. The app does not recreate those: it lets the site's code make the request and only reads the response.

## Why these platform choices

- **`specialUse` foreground service:** Android 15 caps `dataSync` and `mediaProcessing` services at about 6 h per 24 h. `specialUse` has no such cap, and the manifest declares its subtype.
- **Exact allow-while-idle alarm:** a foreground service does not keep the CPU awake, so a plain `delay()` can stretch for many minutes while the phone is in deep sleep. The alarm (granted through `USE_EXACT_ALARM`) wakes the CPU for each check, and the wake lock is held only during the check. Firing an exact alarm also lets the app restart the service from the background.
- **Battery-optimization exemption:** without it, Doze blocks network access between maintenance windows.
