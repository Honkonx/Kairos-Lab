# Homelab

**Native Kairos screen** — reachable from the "More" menu. It is the **control and monitoring**
panel of the device itself: with the ease of use of a real dashboard, it lets you see the phone's
state, control modules, and manage services and automations without opening the terminal.

---

**App:** Kairos (termux-app fork)
**Fragment:** `HomelabFragment.kt`
**Persistence/logic:** `HomelabManager.kt`, `SystemMetrics.kt`, `HomelabServer.kt`, `AutomationManager.kt`

---

## 0. What it is, and what it is NOT

Homelab has 3 tabs:

1. **Panel** — real system metrics (CPU/RAM/storage/temperature/battery/network/IP),
   the list of modules with a process running right now, and the **web panel** (`HomelabServer`)
   to control the phone like a VPS/homelab from any browser (via local IP or Cloudflare
   tunnel).
2. **Services** — quick access to Remote/SSH and to the modules with an HTTP service running on
   the device itself, + a list of EXTERNAL self-hosted services added by hand
   (a NAS's Docker/Portainer, a Raspberry Pi's Pi-hole, a server's Proxmox, or any
   generic HTTP panel) + an Add-ons section (see §11).
3. **Automations** — embedded `AutomationsFragment` (see Section 8) — registrable triggers
   (boot, schedule, battery threshold) that start/stop a module without the user
   opening the app.

It is **not** an installable module from `modulos/` — it has no `.sh` script of its own, does not appear in
`modules.json`, and has no install/start/stop switch. It is 100% native Kotlin. The "Services"
tab **does not implement** deep per-platform API integration (Docker Engine API,
Pi-hole API, Proxmox API) — the "state" of an added service is a generic HTTP probe (`HEAD`
with a short timeout), not a real reading of containers/rules/VMs.

The **Running modules** rows include a native **Stop** action, with confirmation and
using `ModuleController.stopModule()`; the list uses the real state source of
`SystemMetrics.moduleStates()`. The web panel also lets you start/stop modules. The screen
only shows metrics the system exposes; GPU and network throughput are not drawn as if a
reliable reading existed.

## 1. Difference from Remote/SSH

The difference is explicit in the screen's subtitle (`homelab_subtitle`):

| | Remote (SSH) | Homelab |
|---|---|---|
| What it is | **Terminal** access — incoming shell (sshd) or outgoing (SSH client) | **Control and monitoring** panel — what is happening right now + turning things on/off from a UI |
| How it is used | The user types commands in a shell session | The user looks at metrics/taps buttons, natively or from a browser |
| Scope | One protocol (SSH) | System metrics + module state + external services + automations + own web panel |

Homelab's "Services" tab DOES have a quick-access row to Remote (status dot + "Open"
button) — it is a navigation SHORTCUT, not a reimplementation: tapping it navigates to
`RemoteFragment`, the real SSH screen.

It also differs from the modules Kairos manages (Ollama, n8n, ...):

| | Modules (Ollama, n8n, ...) | Homelab external services |
|---|---|---|
| Where the service runs | Inside the phone itself (child process of Kairos) | Outside — on a NAS/Raspberry Pi/server on the user's network |
| How it is installed | `ProcessBuilder` + `.sh` script from `modulos/` | Nothing is installed — only a URL is registered |
| Lifecycle | Kairos starts/stops it | Kairos does not control the remote process, it only checks whether it responds |
| Authentication | Does not apply (local process) | Optional user/token, stored so it can never be viewed again |

## 2. Architecture

```
HomelabFragment.kt (UI, 100% programmatic, 3-tab TabLayout)
        │
        ├── "Panel" tab
        │       ├── SystemMetrics.kt (util/) — reads CPU%/RAM/storage/temperature/
        │       │    battery/network/IP, extracted from the same /proc-reading that
        │       │    MonitorFragment.kt already used (MonitorFragment keeps its own logic)
        │       ├── SystemMetrics.moduleStates() — ALL modules with hasSwitch/!internal,
        │       │    via ModuleController.isRunning() (no hardcoded list, unlike
        │       │    MonitorFragment.processModules())
        │       └── HomelabServer.kt (util/) — embedded HTTP server (same raw
        │            ServerSocket/Socket pattern as NubeServer.kt), token-gated
        │            (~/.homelab_token), serves the web panel + module toggle via POST
        │
        ├── "Services" tab
        │       ├── "This device" → ModuleController.isRunning("remote") + modules with a
        │       │    real `port` in modules.json that are running now
        │       └── External "Services" → HomelabManager.kt (persistence + HTTP probe)
        │              ├── ~/.android_server_registry (key "homelab.services")
        │              └── ~/.homelab_secrets/<id> (token/password, owner-only)
        │
        └── "Automations" tab
                └── AutomationsFragment embedded via childFragmentManager (see Section 8)
```

`HomelabFragment` does not extend `BaseModuleFragment` — it is not a module with
install/start/stop, the same criterion that already separates `MonitorFragment`/`NubeFragment` (plain
`Fragment`) from the rest of the real module fragments. It is added directly as a tab of
`fragment_container` in `TermuxActivity.java` (same `add()+hide()` pattern as Monitor/Nube/
Entorno), reachable from `MoreBottomSheetFragment` (`R.id.nav_homelab`, icon `ic_homelab.xml`).
`AutomationsFragment` is not added as a tab of its own in `TermuxActivity` — it lives only as a child
fragment inside `HomelabFragment` (see Section 8.1).

## 3. "Panel" tab — system metrics (`SystemMetrics.kt`)

A grid of 7 cards (CPU, RAM, Storage, Temperature, Battery, Network, Local IP) + a list of
running modules, refreshed every 4s while the Activity is in the foreground
(`onResume()`/`onPause()` start/stop the polling — `TermuxActivity` uses `add()+hide()/show()`
for the tabs, so a real `onPause()` only fires when the whole Activity goes to the background,
not when switching "More" tabs).

| Metric | Real source | Notes |
|---|---|---|
| CPU % | `/proc/stat` (jiffy delta between 2 samples) | Same mechanism as `MonitorFragment.readCpuInfo()` — there is no "% right now" from a single reading |
| RAM | `/proc/meminfo` (`MemTotal`/`MemAvailable`) | |
| Storage | `StatFs` over `Environment.getDataDirectory()` | Requires storage permission (`PermissionManager.hasStorage()`); without it shows `—` |
| Temperature | `BatteryManager.EXTRA_TEMPERATURE` (sticky broadcast `ACTION_BATTERY_CHANGED`) | Proxy for device temperature — there is no standard path to `/sys/class/thermal` without root |
| Battery | `BatteryManager.EXTRA_LEVEL`/`EXTRA_SCALE` | Same broadcast already used by `AutomationBatteryReceiver` |
| Network | `ConnectivityManager`/`NetworkCapabilities` | Type (Wi-Fi/mobile data/ethernet) + whether it validates internet |
| Local IP | `NetworkInterface` (first non-loopback IPv4) | Same mechanism as `NubeServer.localIpAddress()` |
| Running modules | `ModuleController.isRunning()` over the WHOLE `ModuleCatalog` with `hasSwitch && !internal` | List with a Stop action; opening the real module lives in Modules/Monitor |

**GPU**: there is no honest "live GPU usage" metric on Android without root, so no made-up number is
shown. The REAL GPU diagnostic (chip type, renderer, `glxgears` benchmark) exists and is specific to
Mini PC (`EntornoSistemaTab.kt`/`EntornoNative.kt`, because it needs `mesa-utils`/X11 installed) —
Homelab does not duplicate it.

## 4. "Panel" tab — web panel (`HomelabServer.kt`)

HTTP server embedded by hand (same raw `ServerSocket`/`Socket` pattern as `NubeServer.kt`,
no new libraries), port `8972` by default, gated by a token persisted in
`~/.homelab_token` (same mechanism as `.nube_token`).

**Routes:**
- `GET /?token=...` — HTML page with the same metrics as Section 3, a list of modules with a
  Start/Stop button (triggers the real `ModuleController.startModule()`/`stopModule()` through
  `POST /module`), and the list of configured automations (read-only).
- `POST /module?token=...&id=...&action=start|stop` — real toggle of a module.
- `GET /status?token=...` — the same metrics JSON, for a client without a full page reload (the
  HTML page does not use it: it uses `<meta http-equiv="refresh" content="10">` instead of
  periodic `fetch()`).

**Access**: by local IP (same pattern as Nube exposing its port on the local network) or by
Cloudflare tunnel — it reuses `TunnelManager.kt` as is (the same code already used by Nube/Tunnel/modules),
pointed at `HomelabServer`'s port instead of a module. The native UI (`HomelabFragment`,
Panel tab) shows the local URL and, if a tunnel is enabled, the real public URL.

**CSP**: unlike Nube (`script-src 'none'`, zero JS), this page needs a minimal inline
`<script>` so the Start/Stop buttons do not reload the whole page on every click
— `script-src 'unsafe-inline'` is used (documented in the `writeText()` comment) instead of
`'none'`, because there is no cheap way to generate a nonce per request without a build step. The only
script is static (it does not interpolate user data in its body — dynamic values travel through
already-escaped `onclick` attributes).

**Authentication**: token, same as Nube — the panel is never left unprotected.

## 5. "Services" tab — UI (`HomelabFragment.kt`)

- **"This device"**: a card with a fixed row for Remote/SSH (green/grey dot + state +
  "Open" button that navigates to `RemoteFragment`) followed by one row for each module with a `port` in
  `modules.json` that is running right now — "Open" navigates to
  `ModuleWebViewFragment.newInstance("http://127.0.0.1:<port>", name)`.
- **"Services"**: a "+ Add" button opens a dialog (name, type — dropdown Docker/Portainer,
  Pi-hole, Proxmox, generic HTTP, Other —, URL/IP:port, optional user, optional token). Each
  added service is shown as a card: status dot (grey until the first probe, green/red
  after), name, `type · URL`, "Open" button. Long-pressing a card opens a menu: Edit
  / Add-or-Replace token / Delete token (if it has one) / Remove service.
- `HomelabManager.normalizeUrl()` prepends `http://` if the user types only
  `192.168.1.50:9000` without a scheme.
- The availability probe runs in a second pass after rendering the list, so the first render
  is not blocked by network latency.

## 6. Persistence and security (`HomelabManager.kt`)

Same registry pattern as `RemoteManager.kt` (see `docs/modulos/remote-ssh.en.md`): one key
(`homelab.services`) with a compact JSON array in `~/.android_server_registry`, each
write operation protected by `RegistryLock.withLock()`.

```kotlin
data class HomelabService(
    val id: String, val name: String, val type: ServiceType, val url: String,
    val username: String = "", val hasSecret: Boolean = false
)
enum class ServiceType { DOCKER, PIHOLE, PROXMOX, HTTP, OTHER }
```

**Security guarantee**: once a token/password is saved, **no** `HomelabManager` function exposes its
content to the UI — only the boolean `hasSecret`. The only actions available on a stored secret
are replacing it (the dialog ALWAYS starts empty) or deleting it. The secret file
(`~/.homelab_secrets/<id>`) is stored with owner-only permissions via `File.setReadable`/
`setWritable` — no `chmod`/`ProcessBuilder`.

The web panel token (`~/.homelab_token`) follows the same criterion: it is generated once
(`SecureRandom`, 24 bytes) and persisted, never shown except embedded in the URL that the
device's own owner copies/shares.

## 7. Availability probe

`HomelabManager.probe(url)` — HTTP `HEAD` with a 2.5s timeout. Any response code
counts as "responds" — same scope as `RemoteManager.probeClientReachable()`.

## 8. Automations

### 8.0 What it is, and what it is NOT

Registrable triggers that fire a real action on a module (start/stop), **without the user
having to open the app**.

It is **not** an installable module from `modulos/` — it has no `.sh` script and does not appear in
`modules.json`. It is 100% native Kotlin. It is **not** a generic rules engine like Tasker/IFTTT —
the scope is deliberately small: 3 triggers (device boot, daily schedule, battery
threshold) × 2 actions (start module, stop module), reusing `ModuleController` as it
exists, without reimplementing any start/stop logic.

### 8.1 Where the UI lives

`AutomationsFragment.kt` is not a root-level tab of `TermuxActivity` (it is not added to
`fragment_container`, nor does it have its own entry in `MoreBottomSheetFragment` or
`more_nav_menu.xml`). It is mounted as a **child fragment** inside the "Automations" tab of
`HomelabFragment` (`childFragmentManager.beginTransaction().add(containerId, AutomationsFragment(), ...)`,
guarded against double mounting with `findFragmentByTag`). The real engine
(`AutomationManager`/`AutomationScheduler`/the 3 `BroadcastReceiver`s) is independent of where
the UI is shown.

### 8.2 Triggers implemented end to end

| Trigger | Mechanism | Receiver |
|---|---|---|
| **BOOT** (device boot) | static `ACTION_BOOT_COMPLETED` in the manifest | `AutomationBootReceiver.kt` |
| **SCHEDULE** (fixed daily time, `HH:mm`) | `AlarmManager.setInexactRepeating(RTC_WAKEUP, ..., INTERVAL_DAY, ...)` | `AutomationAlarmReceiver.kt` (via `AutomationScheduler.kt`) |
| **BATTERY_LEVEL** (1-100% threshold + BELOW/ABOVE direction) | `ACTION_BATTERY_CHANGED`, dynamic receiver registered in `TermuxApplication.onCreate()` (cannot be declared statically in the manifest since Android 8+) | `AutomationBatteryReceiver.kt` |

Notification, geofence and foreground-app triggers are not implemented.

`BATTERY_LEVEL` is registered dynamically (not a manifest `<receiver>`) from
`TermuxApplication.onCreate()` — `ACTION_BATTERY_CHANGED` is a "broadcast exception" of
Android 8+. The receiver keeps the last seen level in process memory and only fires the
workflow's action when the level CROSSES the configured threshold in the chosen direction (`BELOW`:
`previous >= threshold && percent < threshold`; `ABOVE`: the reverse). **Real cost**: unlike
BOOT/SCHEDULE, this trigger is only reliable while the Kairos process is already alive in
memory (there is no dedicated foreground service sustaining it).

`SCHEDULE` uses `setInexactRepeating` (not `setExactAndAllowWhileIdle`) to avoid asking the
user to manually grant the `SCHEDULE_EXACT_ALARM` permission (Android 12+) in Settings — drifting
a few minutes under Doze is acceptable for this use case.

`AutomationBootReceiver.kt` is a `BroadcastReceiver` of its own, separate from
`SystemEventReceiver.java` (which already listens to `ACTION_BOOT_COMPLETED` for
`TermuxShellManager.onActionBootCompleted()`, inherited Termux:Boot compatibility) — Android
allows more than one static `<receiver>` for the same action; inherited Termux:Boot compatibility
(Java) is kept separate from Kairos's own automations (Kotlin).

### 8.3 Engine architecture

```
AutomationManager.kt (persistence + validation + executor)
        │
        ├── ~/.android_server_registry  (key "automations.workflows", same
        │    file/mechanism as HomelabManager/RemoteManager/TunnelManager — a compact
        │    JSON array, protected by RegistryLock)
        │
        └── execute() → ModuleController.startModule()/stopModule()  (reused as is,
             without reimplementing start/stop)

AutomationScheduler.kt (AlarmManager — arms/cancels each SCHEDULE workflow's daily alarm)

AutomationBootReceiver.kt   ← manifest BOOT_COMPLETED <receiver>
        │
        ├── AutomationScheduler.scheduleAll()   (re-arms alarms — they do not survive a reboot)
        └── AutomationManager.execute() for each enabled BOOT workflow

AutomationAlarmReceiver.kt  ← fired by the PendingIntent armed by AutomationScheduler
        │
        └── AutomationManager.execute() of the corresponding workflow

AutomationBatteryReceiver.kt  ← registered dynamically in TermuxApplication.onCreate()
        │                        (ACTION_BATTERY_CHANGED, cannot be declared statically)
        └── AutomationManager.execute() of each BATTERY_LEVEL workflow whose threshold was crossed
```

### 8.4 Data model (`AutomationManager.kt`)

```kotlin
enum class TriggerType { BOOT, SCHEDULE, BATTERY_LEVEL }
enum class ActionType { START_MODULE, STOP_MODULE }
enum class BatteryDirection { BELOW, ABOVE }   // only for BATTERY_LEVEL

data class Automation(
    val id: String, val name: String, val triggerType: TriggerType,
    val scheduleTime: String = "",   // "HH:mm" 24h, only for SCHEDULE
    val actionType: ActionType, val moduleId: String,
    val enabled: Boolean = true, val lastRunTs: Long = 0L,
    val batteryThreshold: Int = 20,                        // 1..100, only for BATTERY_LEVEL
    val batteryDirection: BatteryDirection = BatteryDirection.BELOW
)
```

The existing `Automation` fields were extended instead of introducing a `sealed class
TriggerConfig` — with few triggers, adding fields following the same pattern that
`scheduleTime` already used is the simplest option; if notification/geofence are added later,
that is when migrating to `TriggerConfig` makes sense.

### 8.5 Validation — the referenced module always really exists

Before persisting a workflow, `AutomationManager.moduleExists(context, moduleId)` confirms that
`moduleId` really exists in the catalog (`ModuleCatalog.load(context)`). The UI already restricts
selection to a `Spinner` populated from the same catalog — the validation in `AutomationManager` is
the real second layer; it never relies only on the UI not allowing a wrong choice.

### 8.6 Deliberate decision: it does NOT go through `ActionApprovalGate`

Documented in `AutomationManager.execute()`'s own KDoc — two reasons:

1. **Technical**: BOOT/SCHEDULE fire from a `BroadcastReceiver` with no visible `Activity` —
   with no real window, any `requestApproval()` here would always end in a timeout →
   `false` (fail-closed), an automation that never automates anything.
2. **Design**: the real approval already happened when the user CREATED/ENABLED the workflow from
   the UI. The actions a workflow can run (`START_MODULE`/`STOP_MODULE`) are, besides,
   the same ones `ActionApprovalGate.ActionType.MODULE_START`/`MODULE_STOP` already classify as
   `REMEMBERABLE` (never `ALWAYS_ASK`).

### 8.7 UI (`AutomationsFragment.kt`)

100% programmatic (no `<layout>` XML of its own). A list of cards (name, `trigger → action`, last
run, enable/disable switch) grouped by trigger + a "+ Add" button. The add/edit dialog uses a
native `TimePickerDialog` to choose the time, and `Spinner`s for trigger/action/module — the
module spinner is populated from `ModuleCatalog.load(context)` filtering by `hasSwitch` (only
modules with their own server/real toggle) and `!internal`. The time selector (and, for
BATTERY_LEVEL, the threshold `EditText` + direction `Spinner`) is hidden/shown depending on the
chosen trigger — `syncTriggerFieldsVisibility()` centralizes that show/hide.

Editing/disabling/deleting a SCHEDULE workflow reschedules/cancels its alarm immediately through
`AutomationScheduler` — without this, an edited schedule would keep firing at the old time until the
next reboot.

## 9. Future scope (not implemented)

1. **External companion app** — a separate, lighter Android app with only the dashboard.
2. **SSH as a remote access path TO the panel** — reaching the web panel from outside the LAN without
   depending on an active Cloudflare tunnel.
3. **Deep per-platform integration** — real Docker Engine API (list/restart
   containers), Pi-hole API (blocking statistics), Proxmox API (VM state) — today the
   "state" of an external service is just a generic HTTP availability probe.
4. **Hardened authentication** of the web panel (PIN/biometrics before generating the link, token
   rotation) — today it is a single persistent token, same level as Nube.
5. Automation triggers: notification, geofence, foreground app.

## 10. Real files

| File | Role |
|---|---|
| `app/src/main/java/com/termux/app/ui/HomelabFragment.kt` | Screen — 100% programmatic, 3-tab TabLayout (Panel/Services/Automations) |
| `app/src/main/java/com/termux/app/util/HomelabManager.kt` | Persistence of external services (JSON registry) + secrets + HTTP probe |
| `app/src/main/java/com/termux/app/util/SystemMetrics.kt` | Reusable system metrics reader (CPU/RAM/storage/temperature/battery/network/IP/modules) |
| `app/src/main/java/com/termux/app/util/HomelabServer.kt` | Embedded HTTP server of the web panel (metrics + module toggle) |
| `app/src/main/java/com/termux/app/ui/AutomationsFragment.kt` | Automations UI — embedded via `childFragmentManager` inside `HomelabFragment` |
| `app/src/main/java/com/termux/app/util/AutomationManager.kt` | Data model + persistence + validation + automation executor |
| `app/src/main/java/com/termux/app/util/AutomationScheduler.kt` | AlarmManager — arms/cancels/re-arms daily alarms |
| `app/src/main/java/com/termux/app/AutomationBootReceiver.kt` | BOOT_COMPLETED `<receiver>` |
| `app/src/main/java/com/termux/app/AutomationAlarmReceiver.kt` | `<receiver>` fired by AlarmManager |
| `app/src/main/java/com/termux/app/AutomationBatteryReceiver.kt` | Dynamic `ACTION_BATTERY_CHANGED` receiver |
| `app/src/main/res/drawable/ic_homelab.xml` | Homelab icon (3-tray rack with LED) |
| `app/src/main/res/values/strings_homelab.xml` + `values-en/` | ES/EN strings (Panel/Services/web panel) |
| `app/src/main/res/values/strings_automatizaciones.xml` + `values-en/` | ES/EN Automations strings |
| `app/src/main/res/menu/more_nav_menu.xml` | `R.id.nav_homelab` (only entry) |
| `app/src/main/java/com/termux/app/ui/MoreBottomSheetFragment.kt` | Single "Homelab" tile in the "More" grid |
| `app/src/main/java/com/termux/app/TermuxActivity.java` | Registration as a tab (`add()`/`hide()`/`switchFragment()`) |
| `app/src/main/AndroidManifest.xml` | Declaration of the static Automations `<receiver>`s (BOOT, ALARM) |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | Dynamic registration of `AutomationBatteryReceiver` in `onCreate()` |

## 11. Add-ons

The Services tab includes an **Add-ons** section (Syncthing and Restic, on top of
`AddonEngine`/`HomelabAddons`). Syncthing starts without a web-interface password by default. The
service cards show a visible ⋮ menu and the state as text. Unverified on a device.
