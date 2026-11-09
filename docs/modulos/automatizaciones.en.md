# Automations — Kairos Reference

> Automations live as the "Automations" tab inside `HomelabFragment` (a child fragment, with no
> additional code of its own). See also `HOMELAB.md`.

## 0. What it is, and what it is NOT

**Automations** gives Kairos registrable triggers that fire a real action on a module (start/stop),
**without the user having to open the app**. Before this, Kairos had NO way for anything to fire by
itself. It fits the product philosophy ("Kairos exists so you don't have to live in the terminal") —
here you do not even need to open the app.

It is **not** an installable module from `modulos/` — it has no `.sh` script and does not appear in
`modules.json`. It is 100% native Kotlin. It is **not** a generic rules engine like Tasker/IFTTT — the
scope is deliberately small: 3 triggers (device boot, daily schedule, battery level) × 2 actions
(start module, stop module), reusing `ModuleController` as it is, without reimplementing any
start/stop logic.

## 1. Implemented triggers

| Trigger | Mechanism | Receiver |
|---|---|---|
| **BOOT** (device boot) | static `ACTION_BOOT_COMPLETED` in the manifest | `AutomationBootReceiver.kt` |
| **SCHEDULE** (fixed daily time, `HH:mm`) | `AlarmManager.setInexactRepeating(RTC_WAKEUP, ..., INTERVAL_DAY, ...)` | `AutomationAlarmReceiver.kt` (via `AutomationScheduler.kt`) |
| **BATTERY_LEVEL** (threshold 1-100% + direction BELOW/ABOVE) | `ACTION_BATTERY_CHANGED`, dynamic receiver registered in `TermuxApplication.onCreate()` (it cannot be declared statically in the manifest since Android 8+) | `AutomationBatteryReceiver.kt` |

The notification, geofence and foreground-app triggers are designed but **not implemented**.

### `BATTERY_LEVEL` — details

`AutomationBatteryReceiver` is registered dynamically (`registerReceiver`, not a `<receiver>` in the
manifest) from `TermuxApplication.onCreate()` — `ACTION_BATTERY_CHANGED` is an Android 8+ "broadcast
exception" that no longer allows implicit static declaration (same reason as
`SystemEventReceiver.registerPackageUpdateEvents()` for another case). The receiver keeps the last
seen level in process memory and compares it against the new one on every `ACTION_BATTERY_CHANGED`
(which arrives with every % change) — it fires the workflow action only when the level CROSSES the
configured threshold in the chosen direction (`BELOW`: `previous >= threshold && percent <
threshold`; `ABOVE`: the inverse), not on every broadcast.

**Limitation**: unlike BOOT/SCHEDULE, this trigger is only reliable while the Kairos process is
already alive in memory (there is no dedicated foreground service sustaining it).

### Why `SCHEDULE` uses `setInexactRepeating`, not `setExactAndAllowWhileIdle`

A workflow like "start Ollama at 8am" does not need second-level precision, and the exact variant
requires the `SCHEDULE_EXACT_ALARM` permission (Android 12+/API 31+), which the user would have to
grant by hand in system Settings outside the app. That friction is avoided on purpose —
`setInexactRepeating` may drift a few minutes under Doze, which is acceptable for this use case.

### Why it is its own `BroadcastReceiver`, separate from `SystemEventReceiver.java`

`SystemEventReceiver.java` already listens for `ACTION_BOOT_COMPLETED` for
`TermuxShellManager.onActionBootCompleted()` (Termux:Boot script compatibility, inherited from the
termux-app fork). Android allows registering more than one static `<receiver>` for the same action
without conflict — a receiver of its own (`AutomationBootReceiver.kt`) is used so as not to mix
"inherited Termux:Boot compatibility" (Java) with "Kairos' own automations" (Kotlin) — two surfaces
that evolve independently.

## 2. Architecture

```
AutomationsFragment.kt (UI, 100% programmatic)
        │
        ├── AutomationManager.kt (persistence + validation + executor)
        │       │
        │       ├── ~/.android_server_registry  ("automations.workflows" key, same
        │       │    file/mechanism as HomelabManager/RemoteManager/TunnelManager — a
        │       │    compact JSON array, protected by RegistryLock)
        │       │
        │       └── execute() → ModuleController.startModule()/stopModule()  (reused as-is,
        │            without reimplementing start/stop)
        │
        └── AutomationScheduler.kt (AlarmManager — sets/cancels the daily alarm of each
             SCHEDULE workflow)

AutomationBootReceiver.kt   ← manifest BOOT_COMPLETED <receiver>
        │
        ├── AutomationScheduler.scheduleAll()   (re-arms alarms — they do not survive a reboot)
        └── AutomationManager.execute() for each enabled BOOT workflow

AutomationAlarmReceiver.kt  ← fired by the PendingIntent set by AutomationScheduler
        │
        └── AutomationManager.execute() of the corresponding workflow

AutomationBatteryReceiver.kt  ← registered dynamically in TermuxApplication.onCreate()
        │                        (ACTION_BATTERY_CHANGED, cannot be declared statically)
        └── AutomationManager.execute() of each BATTERY_LEVEL workflow whose threshold was crossed
```

`AutomationsFragment` does not extend `BaseModuleFragment` — same criterion as `HomelabFragment`
(it is not a module with its own process lifecycle). It is shown embedded as a tab of `HomelabFragment`.

## 3. Data model (`AutomationManager.kt`)

```kotlin
enum class TriggerType { BOOT, SCHEDULE, BATTERY_LEVEL }
enum class ActionType { START_MODULE, STOP_MODULE }
enum class BatteryDirection { BELOW, ABOVE }   // BATTERY_LEVEL only

data class Automation(
    val id: String, val name: String, val triggerType: TriggerType,
    val scheduleTime: String = "",   // "HH:mm" 24h, SCHEDULE only
    val actionType: ActionType, val moduleId: String,
    val enabled: Boolean = true, val lastRunTs: Long = 0L,
    val batteryThreshold: Int = 20,                        // 1..100, BATTERY_LEVEL only
    val batteryDirection: BatteryDirection = BatteryDirection.BELOW
)
```

Trigger-specific fields are added directly to `Automation` instead of using a `sealed class
TriggerConfig`: with few triggers, adding fields following the `scheduleTime` pattern is the simplest
option; if notification/geofence are added, migrating to `TriggerConfig` should be reconsidered.

## 4. Validation — the referenced module always really exists

Before persisting a workflow, `AutomationManager.moduleExists(context, moduleId)` confirms that
`moduleId` exists in the catalog (`ModuleCatalog.load(context)`). The UI already restricts selection
to a `Spinner` populated from the same catalog (it is impossible to pick a nonexistent id by hand) —
validation in `AutomationManager` is the real second layer: the app does not rely only on the UI
preventing a bad choice.

## 5. Deliberate decision: it does NOT go through `ActionApprovalGate`

Documented in the KDoc of `AutomationManager.execute()` — two reasons:

1. **Technical**: BOOT/SCHEDULE fire from a `BroadcastReceiver` with no visible `Activity`.
   `ActionApprovalGate.androidAlertDialogPresenter` needs a real window to show an `AlertDialog` —
   without one, any `requestApproval()` here would always end in timeout → `false` (fail-closed).
   Gating this would make an automation that never automates anything.
2. **By design**: the real approval already happened when the user CREATED/ENABLED the workflow from
   the UI (what is approved is the creation/update of the workflow, not each individual firing). The
   actions a workflow can run (`START_MODULE`/`STOP_MODULE`) are also the same ones that
   `ActionApprovalGate.ActionType.MODULE_START`/`MODULE_STOP` classify as `REMEMBERABLE` (never
   `ALWAYS_ASK`) — there is no destructive action behind a workflow.

## 6. UI (`AutomationsFragment.kt`)

100% programmatic (no XML `<layout>` of its own), same criterion as `HomelabFragment`. A list of cards
(name, `trigger → action`, last run, enable/disable switch) + a "+ Add" button. The add/edit dialog
uses Android's native `TimePickerDialog` to choose the time (it never asks the user to type `HH:mm`
by hand), and `Spinner`s for trigger/action/module — the module spinner is populated from
`ModuleCatalog.load(context)` filtering by `hasSwitch` (only modules with their own server/real
start-stop toggle, e.g. Ollama/n8n — it excludes pure CLIs such as Claude Code/Codex, which have no
start/stop script) and `!internal` (it excludes ids consolidated inside the `languages`/`packages`
container modules). The time picker and the threshold (`EditText`) + direction (`Spinner`) fields
for BATTERY_LEVEL are shown/hidden depending on the chosen trigger —
`syncTriggerFieldsVisibility()` centralizes that show/hide for the 3 triggers.

Editing/disabling/deleting a SCHEDULE workflow reschedules/cancels its alarm immediately via
`AutomationScheduler` — so an edited time does not keep firing at the old time until the next reboot.

## 7. Files

| File | Role |
|---|---|
| `app/src/main/java/com/termux/app/ui/AutomationsFragment.kt` | Screen — 100% programmatic, list + add/edit dialog |
| `app/src/main/java/com/termux/app/util/AutomationManager.kt` | Data model + persistence (registry JSON) + validation + executor (`execute()`) |
| `app/src/main/java/com/termux/app/util/AutomationScheduler.kt` | AlarmManager — sets/cancels/re-arms daily alarms |
| `app/src/main/java/com/termux/app/AutomationBootReceiver.kt` | BOOT_COMPLETED `<receiver>` — runs BOOT workflows + re-arms alarms |
| `app/src/main/java/com/termux/app/AutomationAlarmReceiver.kt` | `<receiver>` fired by AlarmManager — runs ONE SCHEDULE workflow |
| `app/src/main/java/com/termux/app/AutomationBatteryReceiver.kt` | Dynamic receiver (`ACTION_BATTERY_CHANGED`) — detects threshold crossing and runs the matching BATTERY_LEVEL workflows |
| `app/src/main/res/drawable/ic_automation.xml` | Icon (bolt, same criterion as `ic_homelab.xml`) |
| `app/src/main/res/values/strings_automatizaciones.xml` + `values-en/` | ES/EN strings |
| `app/src/main/AndroidManifest.xml` | Declaration of the 2 static `<receiver>`s (BOOT, ALARM) |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | `AutomationBatteryReceiver.Companion.register(context)` in `onCreate()` — dynamic registration; `ACTION_BATTERY_CHANGED` does not allow an implicit static `<receiver>` since Android 8+ |
