# CastLite

Send videos, photos and music from an Android phone to a TV on the same Wi-Fi —
without Google Cast. CastLite targets **LG webOS TVs** (through DLNA/UPnP) and
**almost any Android TV** (through its own small receiver app).

> **Status: Phase 1 of 4** — project setup, phone app, and a local HTTP media
> server you can test from any browser. TV discovery and playback come next.

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | Project setup, phone app, local server serving picked files (browser test) | ✅ done |
| 2 | DLNA/UPnP discovery + AVTransport/RenderingControl playback (LG webOS) | ⏳ |
| 3 | Android TV receiver app (ExoPlayer) + NSD discovery + phone-side control | ⏳ |
| 4 | Queue, photo slideshow, polish, error handling | ⏳ |

## Architecture

```
┌───────────────────────── phone (sender) ─────────────────────────┐
│ System picker (video/image/audio, multi-select)                   │
│      │ content:// URIs                                            │
│      ▼                                                            │
│ MediaLibrary ── allow-list of picked files, random id per file    │
│      │                                                            │
│      ▼                                                            │
│ MediaServerService (foreground service, Wi-Fi + wake lock)        │
│   └─ MediaHttpServer (NanoHTTPD)                                  │
│        GET/HEAD /media/<id>/<name.ext>   ← Range → 206 (seeking)  │
│        GET /                             ← HTML index (testing)   │
│                                                                   │
│ Cast controllers (next phases), behind one interface:             │
│   • DLNA/UPnP  — SSDP search, SOAP AVTransport + RenderingControl │
│   • CastLite receiver — NSD/mDNS or manual IP, HTTP JSON API      │
│   • Google Cast — interface only, not implemented                 │
└───────────────────────────────────────────────────────────────────┘
                 │ URL  http://<phone-ip>:8765/media/<id>/<file>
                 ▼
┌───────────── TV ─────────────┐
│ LG webOS: built-in DLNA      │
│ renderer fetches the URL     │
│                              │
│ Android TV: CastLite TV app  │
│ (ExoPlayer) fetches the URL  │
└──────────────────────────────┘
```

The phone never pushes the media bytes itself: it tells the TV *which URL* to
play, and the TV pulls the file from the phone's HTTP server. Range requests let
the TV jump to any position, which is what makes seeking work.

### Code layout

```
phone/                         Sender app (com.castlite.phone)
  media/CastMedia.kt           Picked file model (+ MediaKind)
  media/MediaResolver.kt       Reads name / size / MIME of content:// URIs
  media/MediaLibrary.kt        Queue + server allow-list (process singleton)
  media/FileNames.kt           URL-safe file names (TVs sniff the extension)
  server/MediaHttpServer.kt    NanoHTTPD server with Range support
  server/ByteRange.kt          Range header parser (unit tested)
  server/Dlna.kt               DLNA header values (contentFeatures, transferMode)
  server/MediaServerService.kt Foreground service hosting the server
  server/ServerMonitor.kt      Observable server state + request log
  net/LanMonitor.kt            Wi-Fi/Ethernet/hotspot IP + drop detection
  ui/                          Compose UI (dark theme)
tv/                            Receiver app (com.castlite.tv) — idle screen for now
.github/workflows/build.yml    CI: unit tests + debug APKs as artifacts
keystore/debug.keystore        Shared debug key (see "Signing" below)
```

### Server details

- Binds to port **8765** (falls back to 8766–8774 if busy) on all interfaces.
- Only files you picked are reachable; each gets a random 10-character id in its URL.
- Supports `GET` and `HEAD`, single byte ranges (`bytes=a-b`, `bytes=a-`, `bytes=-n`),
  `416` for out-of-range requests, and chunked streaming when a provider doesn't
  report a file size (seeking isn't possible then).
- Sends DLNA headers (`contentFeatures.dlna.org`, `transferMode.dlna.org`) that
  strict renderers such as LG webOS require.
- Runs in a foreground service with a "Stop" action in the notification, a Wi-Fi
  lock (no power-save while streaming) and a partial wake lock (4 h safety cap).
- The UI shows a red banner when Wi-Fi drops and updates the URLs when the IP changes.
  It also works when the phone *is* the hotspot the TV is connected to.

## Getting the APKs (no Android Studio needed)

Every push builds both apps on GitHub Actions:

1. Open the repo on GitHub → **Actions** → **Build APKs** → the latest green run.
2. Under **Artifacts**, download `CastLite-phone-debug` and `CastLite-tv-debug`
   (each is a zip containing the `.apk`).

### Install on the phone

Unzip, open `CastLite-phone-debug.apk` on the phone and allow
"Install unknown apps" for your browser/file manager when asked.

### Install on an Android TV

Pick one:

**A. adb over the network**
1. On the TV: *Settings → Device Preferences → About* → click **Build** 7 times to
   enable developer options, then *Developer options →* enable **USB debugging** /
   **Network debugging** (name varies by brand).
2. Find the TV's IP (*Settings → Network*; the CastLite TV app also shows it).
3. On a computer with [platform-tools](https://developer.android.com/tools/releases/platform-tools):
   ```sh
   adb connect 192.168.1.50:5555      # accept the prompt on the TV
   adb install -r CastLite-tv-debug.apk
   ```

**B. USB drive**
1. Copy `CastLite-tv-debug.apk` to a FAT32/exFAT USB stick and plug it into the TV.
2. Install a file manager from the TV's Play Store (e.g. "File Commander" or
   "X-plore"), open the APK from the stick and allow installing unknown apps for it.

The app then appears in the TV's app row as **CastLite TV**.

### Signing

Debug builds are signed with `keystore/debug.keystore` (committed on purpose,
password `android`). Every CI build therefore has the same signature, so new
builds install **over** old ones without uninstalling. It is a debug key only —
don't use it for anything published.

## Building locally

Requirements: JDK 17+ and the Android SDK (platform 35). Android Studio handles both.

```sh
./gradlew testDebugUnitTest                 # unit tests (Range parsing, file names)
./gradlew :phone:assembleDebug :tv:assembleDebug
# → phone/build/outputs/apk/debug/phone-debug.apk
# → tv/build/outputs/apk/debug/tv-debug.apk
```

## Testing Phase 1 (browser)

1. Install the phone APK, open **CastLite**, allow notifications (Android 13+).
2. Tap **Pick media** and choose one or more videos, photos or audio files.
   The server starts automatically and a "CastLite is casting" notification appears.
3. The status card shows `http://<phone-ip>:8765/`. Open that address in a browser on
   a laptop, or on the TV's own browser, on the **same Wi-Fi**. You get a list of the
   shared files; click one to play it.
4. Seek around in the video: each jump appears under **Recent requests** in the app as
   a `206` response with a `bytes=…` range. That is what TVs will use.
5. Turn Wi-Fi off on the phone: the app shows a red "Not connected to Wi-Fi" banner.

With `curl`, from a computer on the same network:

```sh
curl -I  http://<phone-ip>:8765/media/<id>/<file>                  # 200, Accept-Ranges: bytes
curl -s -o /dev/null -D - -H "Range: bytes=1000-1999" <same URL>   # 206, Content-Range
```

### Troubleshooting

- **The browser can't connect**: make sure both devices are on the same Wi-Fi and the
  router doesn't have "AP/client isolation" (common on guest networks).
- **Server stops with the screen off**: some phones (Xiaomi, Huawei, Samsung)
  kill background apps aggressively. Set CastLite's battery usage to "Unrestricted".
