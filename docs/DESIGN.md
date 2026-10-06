# Palisade — on-device defensive analysis for Android

*Working name; status: draft v0.1.*

Palisade is a sideloadable Android app that watches a single device for signs
of compromise and does all analysis locally. It is built for the permissions a
sideloaded, non-root app can actually get, and it tells the user exactly what
each extra grant unlocks.

Sensors, in priority order:

1. DNS filtering and monitoring
2. File artifacts indicative of exploitation
3. Process crash detection
4. Anomalous network traffic
5. Anomalous battery drain

A sixth, supporting sensor — device posture and app inventory — feeds the other
five and is cheap, so it is built early.

---

## 1. Goals and non-goals

**Goals**

- Sideloaded APK. No root, no Google Play dependency, no account, no cloud.
- Every byte of analysis happens on the device. The only outbound traffic the
  app itself generates is an optional, signed threat-feed update.
- Findings are explainable: each one carries the raw evidence that produced
  it and a recommended action.
- Degrades gracefully. Each sensor works at a reduced level with no special
  grants and improves with each grant the user chooses to give.
- Hard to silence. Findings persist, and the app notices when it is turned
  off, revoked or tampered with.

**Non-goals**

- Not an antivirus that scans other apps' private data. Without root that is
  impossible, and we do not pretend otherwise.
- No TLS interception. We never decrypt traffic; we work from metadata.
- No telemetry, no "cloud scoring".
- Not a substitute for OS updates, Advanced Protection or a clean reflash.

---

## 2. Threat model: what an unprivileged app can really see

The app runs in the `untrusted_app` SELinux domain like any other installed
app. That bounds what it can detect. Being honest about this bound is what
makes the findings trustworthy.

| Adversary | Typical signals available to us | Detection confidence |
|---|---|---|
| Commodity malware, adware, droppers installed as apps | package metadata, signing certs, permissions, DNS/IP IOCs, dropped APKs, beaconing | High |
| Stalkerware / consumer spyware | same as above, plus accessibility/device-admin/notification-listener enablement, hidden launcher icons, background mic/camera/location use, idle battery drain, uploads while screen off | High |
| Phishing / smishing delivery | DNS to IOC or newly-seen lookalike domains, APK downloads from non-store sources | Medium |
| Mercenary spyware delivered by 0-click exploit (Pegasus/Predator-class) | native crashes of media/messaging/baseband-adjacent processes (failed attempts), malformed media files in messaging directories, C2 domain/IP IOCs, SELinux denials, kernel panics/`last_kmsg`, sudden posture changes | Medium for the noisy stages (delivery, failed exploitation, C2); Low once an implant is resident and quiet |
| Kernel-/firmware-level persistent implants | essentially none from `untrusted_app`; only indirect effects | Out of scope — we say so and point to reflash / hardware attestation |
| Adversary aware of Palisade | app uninstall, VPN revoke, feed tampering | Mitigated, not solved: always-on VPN + lockdown, `onRevoke()` alerts, signed feeds, persisted findings, tamper checks |

Design consequence: optimise for **noisy artifacts** — crashes from failed
attempts, C2 beacons, dropped files, configuration changes, resource abuse —
rather than for perfect visibility we cannot have.

---

## 3. Platform constraints and capability tiers

Android gives a sideloaded app three distinct levels of access. Every sensor
in this document is specified per tier, and the onboarding UI shows the user
what they gain by moving up a tier.

### Tier 0 — plain install, runtime permissions only

Available immediately after install. Unlocks: DNS/VPN capture (after the VPN
consent dialog), package inventory (`QUERY_ALL_PACKAGES`), readable system
settings, battery telemetry for the whole device, hardware key attestation,
our own process's crash history.

### Tier 1 — special permissions the user toggles in Settings

No computer required; each is one Settings screen away.

| Permission | Settings screen | Unlocks |
|---|---|---|
| `PACKAGE_USAGE_STATS` | Usage access | foreground app timeline, foreground-service starts of other apps, per-app network byte counts (`NetworkStatsManager`), DropBox reads (with Tier 2 `READ_LOGS`) |
| `MANAGE_EXTERNAL_STORAGE` | All files access | scanning shared storage incl. `Android/media/<messaging app>/` where received media lands |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery optimisation | keeps the sampler and scanners alive in Doze |
| VPN "Always-on" + "Block connections without VPN" | Network → VPN | tamper resistance for the DNS/flow sensor |
| `POST_NOTIFICATIONS` (runtime) | prompt | alerts |

### Tier 2 — development permissions granted once over adb

Android's *development* protection level exists precisely so that a user can
grant these to a third-party app with `pm grant`. They survive reboots (not
uninstalls). This is the same mechanism BetterBatteryStats and GSam use.

```sh
PKG=org.example.palisade   # placeholder package id
adb shell pm grant $PKG android.permission.DUMP
adb shell pm grant $PKG android.permission.READ_LOGS
adb shell pm grant $PKG android.permission.BATTERY_STATS
adb shell pm grant $PKG android.permission.PACKAGE_USAGE_STATS   # or via Settings
```

| Permission | Unlocks |
|---|---|
| `DUMP` | `ActivityManager.getHistoricalProcessExitReasons()` for **all** packages (crash sensor's primary source); `dumpsys batterystats / appops / sensorservice / audio / location / power` |
| `READ_LOGS` | `DropBoxManager` entries and the `ACTION_DROPBOX_ENTRY_ADDED` broadcast: tombstones (incl. native daemons), ANRs, watchdog, `SYSTEM_LAST_KMSG`, boot/restart records |
| `BATTERY_STATS` | per-UID power attribution, wakelocks, wake-ups, sensor and GPS usage via `dumpsys batterystats --checkin` |

Notes:

- `pm grant` only works for permissions declared in the manifest, so all
  three are declared even though the app runs without them.
- ⚠ Android 13+ shows a one-time "allow access to all device logs" dialog
  when an app actually reads **logcat**, and denies background logcat reads.
  The crash sensor therefore relies on `ApplicationExitInfo` and DropBox, not
  on tailing logcat. Logcat tailing is a foreground-only diagnostic.
- After granting, the onboarding tells the user to turn USB/wireless
  debugging back off, and the posture sensor nags if it stays on.
- Root and Shizuku are deliberately not required or used.

### Hard limits we design around

- Only one VPN can be active per user profile. Work-profile traffic is
  invisible to a personal-profile VPN.
- `/proc/net/*`, `/proc/stat`, `/proc/loadavg` and other apps' `/proc/<pid>`
  are not readable on Android 10+.
- Other apps' private data (`/data/data/*`) and `Android/data/*` on shared
  storage are never readable. APK files (`ApplicationInfo.sourceDir`) are.
- Private DNS in *strict* mode sends DNS over TLS through the tunnel, which
  we cannot inspect. We detect it and guide the user; we do not break it.
- Apps can resolve names via DoH to arbitrary servers or use hard-coded IPs.
  DNS filtering alone is bypassable; that is why the flow sensor exists.

---

## 4. Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│ UI (Compose)  Dashboard · Timeline · DNS · Flows · Crashes · Files ·   │
│               Power · Posture · Settings/Onboarding · Export           │
└──────────────▲────────────────────────────────────────────▲────────────┘
               │ findings, stats                            │ config
┌──────────────┴────────────────────────────────────────────┴────────────┐
│ core: Event bus (Flow) → Detectors → Correlator → Findings → Score     │
│       Baselines (EWMA / median+MAD per key) · Rules (IOC sets, YAML)   │
│       Store (Room/SQLite, encrypted, ring-buffered) · Intel loader     │
└──▲────────▲──────────▲───────────▲───────────▲───────────▲─────────────┘
   │        │          │           │           │           │
 sensor-  sensor-   sensor-     sensor-     sensor-     sensor-
 dns      flow      crash       files       power       posture
 (VPN,    (flow     (ExitInfo,  (MediaStore (Battery-   (settings,
 resolver,table,    DropBox,    observer,   Manager,    app inventory,
 policy)  SNI,      tombstone   hash/magic/ sampler,    attestation,
          NetStats) parser)     YARA/APK)   dumpsys)    feeds diff)
   │        │
 core-native (C, NDK): TUN packet relay (lwIP), blocklist matcher, libyara
```

**Event pipeline.** Every sensor emits immutable `Event`s onto a single
in-process bus. Detectors are pure functions over `(event stream, state)`
and emit `Finding`s. A correlator joins findings across sensors inside time
windows. This keeps detection logic unit-testable with recorded fixtures and
lets the UI replay how a finding was reached.

```kotlin
data class Event(
    val id: Long, val ts: Long, val sensor: Sensor, val type: String,
    val uid: Int?, val pkg: String?, val attrs: Map<String, Any?>)

data class Finding(
    val id: Long, val firstSeen: Long, val lastSeen: Long, val count: Int,
    val detector: String, val severity: Severity,      // INFO..CRITICAL
    val confidence: Float,                              // 0..1
    val title: String, val summary: String,
    val evidence: List<Long>,                           // Event ids
    val attack: List<String>,                           // MITRE ATT&CK Mobile ids
    val action: String, val state: State)               // OPEN / ACK / ALLOWLISTED / RESOLVED
```

**Background execution.** One foreground service hosts the VPN and the
cheap samplers (battery, screen/charge state). `WorkManager` runs periodic
jobs (crash poll, file scan, dumpsys collection, feed refresh, baseline
recompute) with constraints such as "charging + unmetered" for the heavy
ones. A `BOOT_COMPLETED` receiver restarts everything.

**Storage.** SQLite via Room. High-volume tables (DNS queries, flows, battery
samples) are ring-buffered by age and size; findings and evidence are kept
long-term. The database is encrypted with a key wrapped by Android Keystore
and the app can be locked behind `BiometricPrompt`. `allowBackup=false`.

**Intel.** Blocklists, IOC sets and detection rules ship as a signed bundle
(Ed25519 signature checked before loading). Updates are opt-in, fetched over
pinned TLS through a `protect()`ed socket, and fall back to the bundled copy.

---

## 5. Sensors

Each sensor section lists data sources by tier, what is collected, the
detection logic, actions, cost, and limitations.

### 5.1 DNS filter and monitor (priority 1)

**Mechanism.** A `VpnService` with a TUN interface. No remote tunnel; the app
is its own resolver and relay. Two operating modes share one code path:

- **DNS-only (low power).** Route only the virtual resolver address through
  the tunnel (`addRoute(fakeDns, 32)`, `addDnsServer(fakeDns)`, and the IPv6
  equivalents). Only DNS packets enter userspace; everything else goes
  straight to the physical network. Pure Kotlin, no TCP stack. This is the
  DNS66 / personalDNSfilter approach.
- **Full capture.** Route `0.0.0.0/0` and `::/0`. Every packet enters
  userspace and is relayed through `protect()`ed sockets by the native
  TCP/UDP/ICMP relay (§9). Required by the flow sensor (§5.4).

Both modes occupy the single VPN slot. Full capture is the default once the
relay exists; the app drops to DNS-only automatically under thermal
pressure or when the user asks.

**Resolver.** Parses UDP/53 (and TCP/53 once the relay exists) from the TUN,
attributes each query to an app with
`ConnectivityManager.getConnectionOwnerUid()` (API 29, works for UDP and
TCP, VPN apps only), applies policy, and either answers locally or forwards
upstream. Upstream defaults to the underlying network's own DNS servers
(from `LinkProperties`) over UDP so the trust model does not change; the user
can switch to DoH or DoT with bootstrap IPs. Responses are cached by TTL and
recorded as a passive-DNS map (`name → IPs`) used by the flow sensor. Handles
`TC` responses, EDNS0 up to the client's advertised size, DNS64, and
network changes (`NetworkCallback` → reopen upstream sockets).

**Policy (evaluated in order).**

1. User allow / block rules (exact, suffix, regex), per app or global.
2. Per-app rules: "block all DNS for app X", "only allow these zones".
3. IOC sets: malware, phishing, C2, stalkerware domains, mercenary-spyware
   indicators. Match → block + `HIGH` finding.
4. Category blocklists (ads/trackers, NRD, parked, DoH/DoT endpoints) — block
   or monitor per category, user's choice.
5. Default: allow and log.

Blocked queries answer `0.0.0.0`/`::` (configurable: `NXDOMAIN`, `REFUSED`).
Everything is logged with app attribution, response IPs and policy outcome.

**Blocklist matching.** Lists of 0.5–2 M domains are too big for JVM hash
sets. Each list is compiled offline into a sorted array of 64-bit hashes of
every blocked name, memory-mapped from a file and searched by binary search
over `(name, parent, grandparent, …)` suffixes. 1 M entries ≈ 8 MB, lookup in
microseconds, no heap pressure.

**Heuristics (findings, not blocks, unless the user enables blocking).**

- **DGA score** per name: length, entropy, consonant and digit ratios,
  bigram likelihood against a bundled table, TLD rarity. Raised further by an
  `NXDOMAIN` burst from the same app.
- **DNS tunnelling**: high query rate to one zone, mean label length,
  unique-subdomain cardinality per zone per hour, unusual record types
  (`TXT`, `NULL`, `CNAME` chains), oversized responses.
- **First-seen zone per app** after a 7-day learning period (`INFO`; feeds
  the correlator).
- **DoH/DoT bypass**: traffic to port 853, to known DoH endpoint IPs, or TLS
  with a DoH provider SNI, from an app other than the system resolver.
- **Rebinding / odd answers**: public name resolving to private, loopback or
  link-local addresses.
- **Private DNS strict mode detected** (`Settings.Global.private_dns_mode`,
  `LinkProperties.isPrivateDnsActive()`): `INFO` with guidance.

**Cost.** DNS-only: negligible. Full capture: comparable to NetGuard /
RethinkDNS, a few percent on heavy traffic; see §5.4 for mitigations.

**Limitations.** Strict Private DNS, DoH inside apps and hard-coded IPs all
bypass name-level filtering (detected, not prevented). Only one VPN at a
time; chaining a real VPN upstream (WireGuard/SOCKS) is a later feature.

### 5.2 File artifacts indicative of exploitation (priority 2)

**Readable surfaces.**

| Tier | Surface |
|---|---|
| 0 | APKs of all installed packages (`sourceDir`, `splitSourceDirs`); world-readable `/system` paths; our own sandbox and `/proc/self` |
| 1 (`MANAGE_EXTERNAL_STORAGE`) | shared storage: `Download/`, `Documents/`, `DCIM/`, `Pictures/`, `Movies/`, `Music/`, `Bluetooth/`, and `Android/media/<pkg>/` for messaging apps (WhatsApp, Telegram, Signal, Messages/RCS) — this is where zero-click media lands |

**Collection.** A `ContentObserver` on `MediaStore.Files` picks up new files
within seconds; a full scan runs only when charging on an unmetered network.
Each file gets a cheap pass (size, magic bytes, extension, origin directory,
mtime) and, when warranted, an expensive pass (SHA-256, format parse, YARA).
Per-file time and size caps keep the scan bounded.

**Checks.**

1. **Hash IOCs.** SHA-256 against bundled sets: stalkerware APKs,
   MalwareBazaar Android subset, mercenary-spyware samples where published.
2. **Type / extension mismatch.** `.jpg` that is a ZIP/APK/ELF/DEX, `.pdf`
   that is an APK, etc.
3. **Format-structural checks on exploit-prone parsers.** Small, targeted
   parsers that validate structure rather than render content:
   - WebP: VP8L Huffman table sizes and colour-cache bits (CVE-2023-4863
     class), chunk-length consistency.
   - PNG / GIF / JPEG / HEIF: chunk and box lengths, dimension overflow,
     duplicate critical chunks.
   - MP4 / MKV / HEVC streams: atom/box size inconsistencies, Stagefright-era
     `tx3g`/`stsc` abuse, oversized NAL units.
   - TTF/OTF, PDF with `/JS` or `/OpenAction`, OEM-specific formats such as
     Samsung Qmage (`.qmg`) arriving over MMS.
   A structural failure is `MEDIUM` on its own and `HIGH` when correlated
   with a media-process crash (§6).
4. **APK triage** for APKs on storage and installed packages:
   `getPackageArchiveInfo` + signature; requested permissions; declared
   accessibility, device-admin, notification-listener services; `BOOT_COMPLETED`
   and `SMS_RECEIVED` receivers; no launcher activity; `debuggable`; signing
   certificate against stalkerware cert IOCs; embedded DEX/ELF/APK (dropper);
   known packer signatures; native libraries with hooking-framework names.
5. **Executables where they should not be.** ELF, DEX/ODEX, JARs, shell
   scripts in shared storage; names such as `frida`, `gadget`, `magisk`,
   `zygisk`, `lsposed`, `su`, `busybox`; Magisk module zips (`module.prop`).
6. **Root / hook artifacts.** Probe well-known paths (`/system/bin/su`,
   `/system/xbin/su`, `/sbin/su`, `/system/app/Superuser.apk`,
   `/system/framework/XposedBridge.jar`, …) and interpret `EACCES` vs
   `ENOENT` carefully — on some builds the probe itself is denied.
7. **YARA.** `libyara` built with the NDK (no `cuckoo`/`magic` modules),
   with a curated, small rule set (packers, droppers, exploit kit strings).
   Deferred until the structural checks and hash sets exist.
8. **Self-integrity.** `/proc/self/maps` for unexpected libraries,
   `TracerPid`, own signature check.

**Cost.** Hashing gigabytes of media is expensive; new files are hashed
immediately, the backlog only on charger. Native SHA-256 via mmap.

**Limitations.** Files inside other apps' private storage or `Android/data`
are invisible. A structural anomaly is evidence of a malformed file, not
proof of an exploit.

### 5.3 Process crash detection (priority 3)

Failed exploitation attempts crash their target. Repeated native crashes in
the processes that parse untrusted input are among the best signals an
unprivileged observer can get.

**Sources by tier.**

| Tier | Source | Coverage |
|---|---|---|
| 0 | own `ApplicationExitInfo`; `elapsedRealtime()` resets and `getprop sys.boot.reason` (⚠ readability varies by OEM) | own crashes, unexpected reboots, kernel-panic boot reasons |
| 2 `DUMP` | `ActivityManager.getHistoricalProcessExitReasons(null, 0, N)` | every app process: reason (`CRASH`, `CRASH_NATIVE`, `ANR`, `SIGNALED`, `LOW_MEMORY`, `EXCESSIVE_RESOURCE_USAGE`, …), process name, importance, timestamp, PSS/RSS, description; on API 31+ `getTraceInputStream()` yields the tombstone as protobuf |
| 2 `READ_LOGS` (+ usage access) | `DropBoxManager` + `ACTION_DROPBOX_ENTRY_ADDED` | `SYSTEM_TOMBSTONE` / `SYSTEM_TOMBSTONE_PROTO` for **native daemons and HALs** too, `data_app_native_crash`, `system_app_crash`, `system_server_watchdog`, `*_anr`, `SYSTEM_LAST_KMSG` (previous-boot kernel oops/panic), `SYSTEM_BOOT`, `SYSTEM_RESTART` |

Polling: `getHistoricalProcessExitReasons` every 15 min and on screen-on;
DropBox is event-driven. Records are deduplicated by `(pid, timestamp)`.

**Tombstone parsing.** AOSP's `tombstone.proto` (Apache-2.0) is compiled
into the app; text tombstones from DropBox are parsed by a tolerant parser.
Extracted: signal and code, fault address, abort message, crashing thread,
top backtrace frames (library + offset), memory-map hint for the fault
address, process name and UID.

**Scoring inputs.**

- **Process criticality.** Media: `media.codec`, `media.swcodec`,
  `mediaserver`, `media.extractor`, `com.android.providers.media` (scans
  every received file). Messaging: `com.google.android.apps.messaging`,
  `com.android.messaging`, `com.whatsapp`, `org.telegram.messenger`,
  `org.thoughtcrime.securesms`, `com.viber.voip`. Radio-adjacent:
  `com.android.phone`, `rild`/`qcrild`, `com.android.bluetooth`,
  `com.android.nfc`, `wpa_supplicant`. Platform: `system_server`, `zygote*`,
  `surfaceflinger`, `com.google.android.gms`, browser and WebView
  `:sandboxed_process*` renderers.
- **Crash type.** Native (`SIGSEGV`, `SIGBUS`, `SIGILL`, `SIGABRT`) outranks
  Java. `SEGV_ACCERR` on an executable page, `SIGILL` in a JIT region and
  PAC failures outrank `SEGV_MAPERR`.
- **Memory-safety signatures** — treated as `HIGH` on their own because a
  mitigation fired: MTE faults (`SEGV_MTESERR` / `SEGV_MTEAERR`), Scudo
  allocator aborts ("corrupted chunk header", "invalid chunk state",
  "double free"), `FORTIFY:` aborts, stack-protector ("stack corruption
  detected") and CFI ("CFI check failed") aborts.
- **Fault-address patterns.** Repeated-byte addresses (`0x41414141…`),
  addresses inside heap-spray-sized regions, non-canonical pointers.
- **Rate and burst.** Per-process baseline of crashes/day; `n` native crashes
  in a short window of the same critical process is scored far above one.
- **Kernel.** `SYSTEM_LAST_KMSG` with an oops/panic, `sys.boot.reason` of
  `kernel_panic` or `watchdog`, or an unexplained reboot: `HIGH`.
- **SELinux denials** (logcat `avc: denied`, foreground diagnostic only):
  new `{scontext, tcontext, tclass, perm}` tuples not seen in the baseline.

**Output.** One finding per `(process, signature)` with counters, the latest
tombstone excerpt as evidence, and links to anything the correlator attached
(a file that arrived just before, DNS activity of the same app).

**Limitations.** Without `DUMP` this sensor only sees reboots and our own
crashes; onboarding says so plainly. A crash is evidence of a failed or
fuzzy attempt, not of a successful one. `lmkd` kills and ordinary app bugs
are filtered by reason and baseline, but noise is inherent.

### 5.4 Anomalous network traffic (priority 4)

**Sources.**

- **Full-capture VPN (Tier 0).** A flow table keyed by 5-tuple with UID
  attribution, start/end, bytes in/out, packet counts, TLS SNI (from the
  ClientHello when no ECH), plaintext HTTP `Host`, and the DNS name that
  last resolved to the destination (from §5.1's passive-DNS map). No payload
  is stored. Optional pcapng export of headers for analysts.
- **`NetworkStatsManager` (Tier 1).** Per-UID rx/tx per time bucket per
  network type. Cheap, VPN-independent cross-check and the only source when
  the VPN is off.
- **Context.** Screen state, foreground app (`UsageStatsManager`), charging,
  Doze, network type, from the shared sampler.

**Detectors.**

- **IOC match** on destination IP, CIDR or resolved name: `HIGH`, blocked if
  the user enabled blocking.
- **Connection without prior resolution**: a destination IP the app never
  resolved via DNS in the lookback window, excluding CDN/ASN allowlists and
  the app's learned baseline. Typical of hard-coded C2 and DoH bypass.
- **Beaconing**: for `(uid, destination)` with ≥ 8 connections, coefficient
  of variation of inter-arrival times < 0.2 while the app is in the
  background. Jittered beacons are caught by periodicity over longer windows.
- **Exfiltration shape**: upload bytes per app per hour vs its own baseline
  split by `{foreground/background, screen on/off}` using median + MAD; alert
  after several consecutive abnormal buckets, with higher weight when the
  screen is off.
- **Port and protocol oddities**: non-443/80 TLS, plaintext HTTP from
  sensitive apps, raw TCP to high ports, UDP to unusual ports, port 853 or
  DoH endpoints from non-resolver apps, TLS without SNI.
- **Destination novelty**: first-seen ASN or country per app (offline
  IP→ASN/country table, ~10–20 MB, optional download) and bulletproof-hosting
  ASN list.
- **Environment**: user-installed CA certificates, global HTTP proxy, other
  VPN-capable apps, network changes to captive portals, device on a 2G
  network (`TelephonyManager`, needs location permission; `INFO`).

**Cost and mitigation.** Full capture relays every packet through
userspace. Mitigations: the native relay (§9) rather than Kotlin per-packet
work, flow-level accounting instead of per-packet logging, a hot cache for
`getConnectionOwnerUid`, batched writes, and automatic fallback to DNS-only
mode on thermal status ≥ `MODERATE`. `addDisallowedApplication` lets the
user exempt apps that misbehave behind VPNs.

**Limitations.** Encrypted payloads are opaque by design. Some system UIDs
bypass VPNs. Work profiles are not covered. SNI disappears with ECH.

### 5.5 Anomalous battery drain (priority 5)

Resource abuse is how surveillance shows up to a user — a warm phone that
empties overnight. We model expected drain per device state and flag
deviations, then try to attribute them.

**Tier 0 collection (shared sampler, every 5 min while discharging and on
every state transition).** `BatteryManager`: level, `CHARGE_COUNTER` (µAh),
`CURRENT_NOW` (µA; sign convention varies by OEM — calibrated on first
charge/discharge cycle), voltage, temperature, status; `PowerManager`:
interactive, Doze, power-save, thermal status; network type; our own CPU time
and relayed bytes (so the app subtracts its own cost).

**Tier 1 attribution.** `NetworkStatsManager` per-UID bytes in the same
window; `UsageEvents` for foreground app and `FOREGROUND_SERVICE_START/STOP`
of other apps — a background app with a running foreground service plus
uploads during an idle-drain spike is a strong attribution.

**Tier 2 attribution (`BATTERY_STATS` + `DUMP`, every 30–60 min or on an
anomaly).** Parse `dumpsys batterystats --checkin` for per-UID estimated
mAh, partial wakelock time, wake-up alarms, jobs, sensor and GPS time, CPU
time. Parse `dumpsys appops` for last-access times of `RECORD_AUDIO`,
`CAMERA`, `FINE_LOCATION`, `COARSE_LOCATION`, `READ_SMS`, `READ_CALL_LOG`,
`READ_CONTACTS`, `GET_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, including the
foreground/background tag where present. `dumpsys sensorservice`, `dumpsys
audio` and `dumpsys location` give currently active clients. Output formats
drift across versions and OEMs, so parsers are tolerant, fixture-tested and
treated as best-effort.

**Detectors.**

- **Idle drain**: screen off, not charging, Doze expected → drain rate vs a
  per-device baseline (median + MAD over 14 days, learned per
  `{screen, network type, thermal}` state). Alert after ≥ 3 abnormal
  intervals. Severity rises with magnitude and duration.
- **Thermal while idle**: temperature slope with screen off and no charging.
- **Attributed abuse** (Tier 2): background microphone/camera/location use,
  wakelock held for hours with screen off, wake-up alarm storms, sustained
  sensor sampling. Background audio/camera use by a non-system app that is
  not in the foreground is `CRITICAL` on its own.
- **Correlation** (§6): idle drain + upload spike from the same window
  (+ mic/location op if Tier 2) → "possible surveillance".

**Limitations.** Without Tier 2, attribution is inferential. OEM battery
managers and adaptive battery add noise; the learning period absorbs most
of it.

### 5.6 Device posture and app inventory (supporting)

Cheap, Tier 0, run at boot and every few hours; the diff against the last
snapshot is what matters.

- **Integrity.** Hardware key attestation (`setAttestationChallenge`, parse
  the attestation extension offline): verified-boot state, device-locked,
  OS version, OS/vendor/boot patch levels. `getprop ro.boot.verifiedbootstate`,
  `Build.TAGS` (`test-keys`), `ro.debuggable`, SELinux enforcing where
  readable, emulator/root heuristics.
- **Patch level vs exploited CVEs.** `Build.VERSION.SECURITY_PATCH` against a
  bundled table of Android CVEs listed in CISA KEV with their fix month:
  "your device is missing fixes for N actively exploited vulnerabilities".
- **Settings that matter.** Developer options, ADB and wireless debugging,
  Private DNS mode, global proxy, user CA certificates, accessibility
  services enabled (`Settings.Secure.enabled_accessibility_services`),
  notification listeners, active device admins, default SMS/dialer/browser.
- **App inventory.** New/updated/removed packages; installer source
  (`getInstallSourceInfo`): sideloaded or package-installer-installed apps
  other than ourselves; no launcher activity; `REQUEST_INSTALL_PACKAGES`;
  dangerous permission combinations (SMS + call log + location + mic +
  accessibility + boot receiver); signing certificate and package name
  against stalkerware IOCs; apps declaring a VPN service.
- **Self-protection.** Own signature, `onRevoke()` of the VPN, foreground
  service killed unexpectedly, feed signature failures — each is a finding
  and a notification.

---

## 6. Detection engine

**Detectors** come in three kinds and are registered declaratively:

1. **Static IOC matchers** — domain/IP/hash/cert/package sets from the intel
   bundle. Deterministic, high confidence.
2. **Rules** — YAML, Sigma-like, over event attributes with simple
   aggregations (count in window, distinct count, threshold). Shipped in the
   intel bundle so they can be updated without an app release.
3. **Statistical** — Kotlin detectors that keep per-key baselines
   (EWMA, median+MAD, periodicity) with a 7–14 day learning period. New keys
   produce `INFO` findings until learned, to avoid alert fatigue.

**Correlator.** Joins findings and events across sensors within time
windows. Initial rules:

| Pattern | Result |
|---|---|
| Native crash of a media/messaging process ≤ 10 min after a file arrived in a messaging media dir | `HIGH` "possible malicious media file", file hash + tombstone attached |
| ≥ 3 native crashes of one critical process in 24 h + any IOC hit from the same app | `HIGH` |
| Idle drain anomaly + background upload spike from the same app (+ background mic/location op at Tier 2) | `CRITICAL` "possible surveillance" |
| Connections without DNS + beaconing + app not from an app store | `HIGH` "possible C2" |
| New accessibility service or device admin + sideloaded app + SMS/boot receivers | `HIGH` "stalkerware pattern" |
| MTE/Scudo/CFI abort in any process | `HIGH` on its own |

**Findings** are deduplicated by `(detector, key)`; repeats bump `count` and
`lastSeen`. States: open, acknowledged, allowlisted (with scope: app, domain,
process), resolved. Each carries MITRE ATT&CK Mobile technique ids for
analysts (for example T1664 exploitation for initial access, T1437.001 web
protocols, T1429 audio capture, T1430 location tracking, T1646 exfiltration
over C2 channel).

**Device risk score** (0–100): weighted by the highest open severity,
number of distinct open findings, recency decay, and posture penalties
(missing exploited-CVE fixes, debugging enabled, unlocked bootloader).

---

## 7. Data management and privacy

- All data stays on the device. Export is user-initiated, produces an
  encrypted archive (findings, evidence, optional pcapng) for an analyst, and
  shows exactly what it contains before writing it.
- Retention defaults: DNS queries and flows 7 days or 50 MB (ring buffer),
  battery samples 30 days, findings and their evidence 180 days.
- Encrypted SQLite, Keystore-wrapped key, optional biometric app lock.
- The app's own traffic is attributed to its own UID and visible in the UI;
  nothing is hidden from the user.
- Feeds are downloaded only when the user enables updates; the request
  carries no identifiers.

---

## 8. Security of the app itself

- No exported components beyond what the system requires (VPN service,
  boot receiver); no listening sockets.
- `debuggable=false`, `allowBackup=false`, cleartext traffic disabled, R8.
- Signed intel bundles (Ed25519); pinned TLS for feed downloads.
- Reproducible release builds; APK signature published; verification
  instructions in the README. Distribution via GitHub Releases and,
  optionally, an F-Droid repository.
- Minimal third-party dependencies, pinned with checksums.

---

## 9. Technology stack and repository layout

- Kotlin, Jetpack Compose, Room, WorkManager, OkHttp (DoH), kotlinx
  serialization, Wire or protobuf-lite for `tombstone.proto`.
- `minSdk 29` (needed for `getConnectionOwnerUid`), `targetSdk` current.
  API-level gating: `ApplicationExitInfo` 30+, proto tombstones 31+.
- NDK (C): the TUN relay built on lwIP (BSD licence) — minimal TCP/UDP/ICMP
  state per flow, forwarding to `protect()`ed sockets; the mmap'd blocklist
  matcher; `libyara`. gVisor's netstack via gomobile is the fallback option
  if lwIP integration proves painful (larger APK, Go toolchain).
- ⚠ Foreground service types: the VPN service declares the type that
  current Android documentation assigns to `VpnService` apps on API 34+
  (`systemExempted` with `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, else
  `specialUse`); confirm against the docs at implementation time.
- GitHub Actions: build, unit tests, instrumentation tests on an emulator,
  release signing.

```
app/              UI, DI, onboarding, notifications, export
core/             event model, store, detectors, correlator, scoring, rules
core-native/      C: lwIP TUN relay, blocklist matcher, libyara bridge
sensor-dns/       VpnService, resolver, policy, upstream (UDP/DoH/DoT)
sensor-flow/      flow table, SNI/Host parsers, NetworkStats collector
sensor-crash/     ExitInfo + DropBox collectors, tombstone parsers
sensor-files/     MediaStore observer, hash/magic/format/APK/YARA scanners
sensor-power/     sampler, batterystats/appops/sensorservice parsers
sensor-posture/   settings, inventory, attestation, CVE table
intel/            bundle format, signature check, feed compilers (desktop tool)
testing/          fixtures: tombstones, dumpsys outputs, pcaps, sample feeds
docs/
```

---

## 10. Delivery plan and test strategy

Phases are ordered so that each ships something useful on its own.

| Phase | Scope | Done when |
|---|---|---|
| 0 | Scaffold, capability tiers, onboarding, event store, dashboard, notification plumbing | app installs, shows tier status, persists events |
| 1 | DNS-only VPN, resolver, blocklists, logging with app attribution, DoH/DoT upstream, user rules | daily-driver DNS filter with query log |
| 2 | Crash sensor: `ApplicationExitInfo` + DropBox, tombstone parsers, scoring | synthetic and recorded crashes produce correct findings |
| 3 | Posture + inventory + intel bundle + file scanner (hash, magic, structural, APK triage) | IOC and structural fixtures detected; feed update verified |
| 4 | Native relay, full capture, flow table, network detectors, pcapng export | pcap fixtures replay into expected findings; drain within budget |
| 5 | Power sensor, Tier 2 attribution parsers, correlator rules, risk score | fixtures from several OEMs parse; correlated findings fire |
| 6 | Hardening: signed bundles, reproducible builds, app lock, export, docs | release checklist complete |

**Testing.**

- Detectors are pure and tested against recorded fixtures: tombstones (text
  and proto), `dumpsys` outputs from several OEMs and API levels, pcaps
  converted to flow events, sample feeds.
- A developer-only "simulation" mode injects synthetic events (a fake media
  crash, a fake beacon, a fake idle-drain series) so end-to-end behaviour can
  be verified without real exploits.
- Emulator instrumentation tests for the VPN path (DNS-only and full
  capture), ContentObserver scanning, and the onboarding tiers.
- Battery budget test: 24 h emulator/device soak with synthetic traffic,
  measured drain must stay under a set threshold.

---

## 11. Open decisions

1. **Default capture mode** once the relay exists: full capture (proposed)
   vs DNS-only with opt-in full capture.
2. **Relay implementation**: C on lwIP via NDK (proposed) vs Go/gVisor
   netstack via gomobile.
3. **Licence**: Apache-2.0 (proposed; lets us use lwIP, gVisor, YARA, AOSP
   code and keeps GPL code such as NetGuard/DNS66 out) vs GPL-3.0.
4. **`minSdk`**: 29 (proposed) vs 30 to drop pre-`ApplicationExitInfo`
   code paths.
5. **Feeds**: bundled-only releases vs opt-in online updates (proposed:
   both, bundled by default).
6. **Name and package id.**
7. Whether to include the optional extras early: SMS link scanning
   (`READ_SMS`), radio sensor (2G downgrade), WireGuard/SOCKS upstream
   chaining.

---

## 12. Prior art and sources

- NetGuard, DNS66, personalDNSfilter (GPL-3.0) — VPN-based DNS filtering
  designs; read for behaviour, not copied.
- RethinkDNS / firestack — DNS + firewall + pcap on Android, Go netstack.
- Hypatia — on-device signature scanner for Android, no root.
- Mobile Verification Toolkit (Amnesty International) and its public
  indicators — the checks it runs over adb are the model for our on-device
  posture and IOC checks. Note its licence restricts use.
- stalkerware-indicators (Echap) — package names, certificates, domains,
  hashes.
- abuse.ch URLhaus / ThreatFox / Feodo Tracker / MalwareBazaar (CC0),
  StevenBlack hosts (MIT), hagezi and OISD lists (check licences before
  bundling), CISA Known Exploited Vulnerabilities.
- AOSP `system/core/debuggerd/proto/tombstone.proto` (Apache-2.0).
- lwIP (BSD), gVisor (Apache-2.0), YARA (BSD-3).
- Android API references: `VpnService`, `ConnectivityManager
  .getConnectionOwnerUid`, `ApplicationExitInfo`, `DropBoxManager`,
  `NetworkStatsManager`, `UsageStatsManager`, `BatteryManager`, key
  attestation.
