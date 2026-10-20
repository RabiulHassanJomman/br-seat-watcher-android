# BR Seat Watcher (Android)

Watches the Bangladesh Railway e-ticket site for seat availability and alerts you the moment a seat opens, even with the screen locked. It is a native port of the BR Seat Watcher userscript v3.0.

**Alert only.** The app never books, fills passenger details, pays, or solves captchas, and it never stores your password. You log in inside the app's WebView. The app keeps only that WebView's session cookies in private storage, which is excluded from backup. There are no analytics and all traffic is HTTPS.

## Get the APK (no Android Studio needed)

Every push to `main` triggers GitHub Actions (`.github/workflows/build.yml`), which runs the unit tests and builds the APKs. Download them in either of two ways:

- **Releases → "latest"** (a pre-release): open it on your phone and tap the `.apk` file.
- **Actions → the newest run → Artifacts → `apks`.**

To install on a Pixel, open the downloaded APK. Android asks whether your browser may install unknown apps; allow it once, then tap **Install**.

### Use your own signing key (recommended)

Without a key, each CI build signs with a throwaway debug key. Android then refuses to install one build over another unless you uninstall first, which also loses your watches and your login. To avoid that, create one key and keep it forever:

```bash
keytool -genkeypair -v -keystore seatwatcher-release.jks -keyalg RSA -keysize 4096 \
  -validity 10000 -alias seatwatcher
base64 -w0 seatwatcher-release.jks > keystore.b64     # macOS: base64 -i seatwatcher-release.jks
```

Then add these repository secrets under Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `keystore.b64` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `seatwatcher` |
| `SIGNING_KEY_PASSWORD` | key password |

With the secrets in place, CI also publishes `br-seat-watcher-release.apk` (R8-minified), and the debug APK is signed with the same key. **Never commit the `.jks` file or the passwords**; `.gitignore` already blocks them. Back the `.jks` up somewhere safe.

## Build locally (optional)

This requires JDK 17 and the Android SDK (platform 36). Set `ANDROID_HOME` or put `sdk.dir` in `local.properties`.

```bash
./gradlew :core:test                 # pure-Kotlin parser + engine tests
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease       # needs keystore.properties, see below
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a local signed release build, create `keystore.properties` in the project root (it is git-ignored):

```
storeFile=/absolute/path/seatwatcher-release.jks
storePassword=...
keyAlias=seatwatcher
keyPassword=...
```

The debug build installs as `com.iqbal.seatwatcher.debug`, so it can sit next to the release build. Only debug builds have the fake data source.

## First run

The setup guide walks you through these steps, and Settings → "Run setup guide again" reopens it:

1. **Notifications**: allow them, or you won't get alerts.
2. **Background activity**: allow it. This exempts the app from battery optimization so checks keep running with the screen off.
3. **Phone-specific settings**: on a Pixel, App info → Battery should read **Unrestricted**. The guide also covers Xiaomi, Samsung, Realme, Infinix and Tecno; see [dontkillmyapp.com](https://dontkillmyapp.com) for more.
4. **Log in** on the e-ticket site inside the app, then tap **Done**. The app runs one check to confirm the session works.

## How it checks

- One foreground service (`specialUse` type) runs every enabled watch in a single loop. The defaults are one check every 60 s per watch (30 s minimum), at most 3 enabled watches, and 2–5 s of jitter between watches.
- Each check calls the site's own search API (`railspaapi.shohoz.com/.../search-trips-v2`) from inside a hidden WebView that has eticket.railway.gov.bd open. It uses the login token the site itself saved, adds nothing else, and parses the JSON (`trip_number`, `seat_types[].type`, `seat_counts.online + offline`). The token never leaves the WebView. When the token has expired, the site's page is reloaded so the site renews it; if that fails you get "Login needed".
- Between checks the phone may sleep. An exact allow-while-idle alarm wakes it for the next check, and a wake lock is held only during the check itself.
- If the site shows a captcha or block page, or answers 403/429, the app pauses and backs off exponentially (60 s → 5 min max) and posts a "Site is blocking checks" notification. A login page triggers backoff plus a "Login needed" notification. When offline, it retries with backoff.
- A WorkManager watchdog runs every 15 min and restarts the service if Android killed it. A boot receiver resumes watching after a reboot.

## Manual test plan (real phone, debug build)

1. Install the debug APK and finish setup. Battery must show **Unrestricted**.
2. Add a watch, e.g. Chattogram → Dhaka for tomorrow. Tap **Load trains** and confirm the real trains appear. Pick a train and a class, then save.
3. Settings → Debug → **Fake: no seats**. Turn the master switch on. The status card should read "Watching: next check in …s", and the ongoing notification "Watching 1 route · last check …".
4. Wait for one check to finish (this is the baseline; no alert is expected). Lock the phone and leave it for **10 minutes**.
5. Unlock, switch to **Fake: seats appear**, lock again right away and wait 1–2 minutes. You should get a **"Seat available!"** heads-up notification with sound. Tapping it opens the Booking screen at the results URL.
6. Leave it running. **No second alert** should come while the seats stay available (repeat = 0).
7. Switch to **Fake: no seats**, wait one check, then **Fake: seats appear**. That should produce exactly one new alert.
8. Settings → Alert style → **Loud alarm** → **Send test alert**. The alarm should loop until you tap Open booking, Stop, or swipe the notification away.
9. Test **Snooze 10 min** from an alert: there should be no alert for that watch for 10 minutes.
10. Switch back to **Off (real site)**. Use the in-app **Log in / session** button and log out on the site. Within a few checks you should get a **"Login needed"** notification, not silence.
11. Reboot with watching on. Within a minute or two of unlocking, the ongoing notification should be back.
12. Overnight test: leave watching on with the screen off for 30+ minutes, then compare the "Checked hh:mm:ss" times. Gaps should stay around 1 minute.

## Known unknowns

These are flagged in code and need confirming on the real site:

- **Login detection.** A check needs login when the search API answers 401 even after the site's page has had a chance to renew its token. Settings → **Copy debug info** shows the API status (never the token).
- **Station list.** The From/To suggestions are not authoritative. Any text is accepted, and **Load trains** confirms whether the site accepts it.

See `docs/ARCHITECTURE.md` for the module layout.
