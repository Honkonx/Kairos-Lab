# Automations

**Native Kairos screen** — accessible from the "More" menu. It's not an installable module from
`modulos/` (no `.sh` script of its own, doesn't appear in the Store) — it's 100% native Kotlin,
giving Kairos real triggers that fire an action on a module **without the user opening the app**.

---

**App:** Kairos (termux-app fork)
**Fragment:** `AutomationsFragment.kt`
**Persistence/logic:** `AutomationManager.kt` + `AutomationScheduler.kt`

---

## 1. What it is, and what it isn't

Automations is Kairos's first mechanism for something to trigger on its own, with no need to
open the app: you define a workflow (trigger → action) and the system runs it in the background.

This first version covers, end to end:

| Trigger | When it fires |
|---|---|
| **Device boot** | When the phone turns on / restarts |
| **Daily schedule** | Every day, at a fixed time picked with a native clock picker |

| Action | What it does |
|---|---|
| **Start module** | Starts a module with a service (Ollama, n8n, llama-server, etc.) |
| **Stop module** | Stops it |

**It's not** a generic rules engine like Tasker/IFTTT — the scope is deliberately small: 2
triggers × 2 actions, reusing the same module control that already backs the manual toggle on
every module screen, without reimplementing start/stop logic separately.

Battery, push notification, geofence, and "app in foreground" triggers are designed but not
implemented yet — real candidates for a future version.

## 2. The screen

A list of cards, one per created workflow: name, `trigger → action`, last-run date/time, and a
switch to enable/disable it without deleting it. A "+ Add" button opens a dialog with:

- Workflow name.
- Trigger (boot / schedule) — the time picker only appears when "schedule" is selected.
- Action (start / stop) + module selector, populated only with modules that have a real service
  of their own (pure CLIs like Claude Code or Codex are excluded, since they have no start/stop
  because they don't run in the background).

Editing, disabling, or deleting a scheduled workflow reprograms or cancels its alarm
immediately — no need to wait for the next phone restart for the change to take effect.

## 3. Why the schedule isn't "to the second"

The daily schedule uses an approximate alarm mechanism, not an exact-to-the-second one — that
level of precision on modern Android requires an extra permission the user would have to grant
by hand outside the app, for a use case ("start Ollama at 8am") that doesn't need that kind of
exactness. It can drift a few minutes under aggressive system battery saving — acceptable for
this purpose.

## 4. Security — no need to confirm every trigger

The actions a workflow can run (starting/stopping a module) are the same ones the app already
treats as low-risk when done by hand from its screen — there's no destructive action behind an
automation in this version. The real confirmation already happened when the user created or
enabled the workflow from the screen; it's not asked again on every individual trigger (which,
on top of that, happens with no screen visible — there wouldn't even be a way to show a dialog
at that moment).

## 5. Future scope (not implemented yet)

- Battery triggers (e.g. "only if above 50%"), push notifications, geofencing, and "app X came
  to the foreground".
- A more generic rules engine, with combined conditions.
