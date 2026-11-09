# TELEGRAM_BOT.md — Incoming Telegram bot (`TelegramBotService.kt`)

> This is NOT a module in `app/src/main/assets/modules.json` (it has no script in `modulos/` and does
> not appear in the Store/Modules) — it is a piece of the app's own infrastructure, documented in
> `docs/modulos/` anyway because conceptually it is "a module-control feature", not core
> architecture (same criterion as `HOMELAB.md`). `TelegramNotifier.kt` covers
> OUTGOING messages; this document covers receiving commands.

## What it is

A foreground `Service` (`TelegramBotService.kt`) that long-polls the Telegram Bot API
(`getUpdates`) to receive text commands and inline buttons, and translates them into direct
calls to `ModuleController` (start/stop modules, query status) — without exposing any
port or needing a public domain/HTTPS (Kairos has neither). It lives in the app's MAIN
process (unlike `X11Service.kt`, which runs in `:xserver`) because it
needs to call `ModuleController` without crossing processes.

The design follows three patterns: long-polling (never webhook), a fail-closed whitelist by `from.id`, and
two-step confirmation for risky commands.

## Architecture

```
ConfigFragment (switch "🤖 Telegram Bot")
        │ TelegramBotService.start(context) / .stop(context)
        ▼
TelegramBotService (foreground Service, main process)
        │ Thread "telegram-bot-poll" — loop while(alive)
        │   POST https://api.telegram.org/bot<token>/getUpdates
        │   {"offset": N, "timeout": 25, "allowed_updates": ["message","callback_query"]}
        ▼
processUpdate() → handleMessage() / handleCallbackQuery()
        │ fail-closed whitelist (from.id == configured chat_id)
        ▼
ModuleController.startModule() / .stopModule() / .isRunning()
```

- **Long-polling, never webhook**: Kairos has no fixed public HTTPS endpoint that Telegram
  could send a webhook to — the right pattern for a mobile client with no server of its own is for
  the device itself to ask ("is there anything new?") in a loop, with `timeout=25s` per request
  so Telegram keeps the connection open instead of answering empty every second.
- **Persisted offset** (`SharedPreferences "kairos_prefs"`, key `pref_telegram_bot_update_offset`):
  each processed update advances the offset and it is saved before continuing — if the service restarts,
  it does not reprocess old messages (Telegram only delivers updates with `update_id >= offset`).
- **Reconnection with exponential backoff**: if `getUpdates` fails (network down, Telegram error), the
  loop waits `2s → 4s → 8s → ... → cap 60s` before retrying, and resets the backoff to 2s as soon
  as a call succeeds again.
- **Same mold as `X11Service.kt`**: static `start()`/`stop()`, persistent foreground notification
  with its own channel (`telegram_bot`), `onDestroy()` stops the loop and disconnects the active HTTP
  connection (if the thread was blocked waiting on `getUpdates`, `disconnect()` frees it instead of
  leaving it hanging until the next timeout).

## Fail-closed whitelist (central security rule)

The same `chat_id` configured in Settings → Notifications for OUTGOING notifications
(`TelegramNotifier`) serves a double purpose: destination for outgoing messages AND the only
`from.id` authorized to send incoming commands.

- **Never `chat.id`, always `from.id`**: `chat.id` identifies the conversation (it could be a
  group with several users); `from.id` identifies the actual person who wrote the message or
  tapped the button, and is the right field for authorization.
- **Fail-closed in 3 layers, none redundant**:
  1. `TelegramBotService.start(context)` (companion) — if `token`/`chat_id` are empty, it **does not
     even send the `Intent`** to start the `Service`. `ConfigFragment` receives `false` and
     notifies the user instead of leaving the switch on while lying about the state.
  2. `onCreate()` — a second check, in case something later starts the
     `Service` directly (e.g. Android relaunching it with a `null` `Intent` after killing the process). If
     config is missing, immediate `stopSelf()` after the mandatory `startForeground()`.
  3. `runLoop()` — if the user deletes the token/chat id without turning the switch off first (config
     "emptied while running"), the loop detects it on the next iteration and stops the service instead of
     continuing to run without a real whitelist.
- **Every incoming message/callback is re-validated** (`isAuthorized(fromId, whitelistedChatId)`) —
  it is not assumed that "if the Service is running, everything is already authorized".

## Two-step confirmation (`/stop`)

The only "risky" command in this version — stopping a module in use — is never executed
directly from the command text:

1. `/stop <module>` → if the module is running, a message is sent with an inline keyboard
   (`reply_markup.inline_keyboard`) with two buttons: `✅ Yes` (`callback_data:
   "stopyes:<module>:<nonce>"`) and `❌ No` (`callback_data: "stopno:<module>:<nonce>"`).
2. The user taps a button → Telegram sends a `callback_query` → `handleCallbackQuery()`
   runs the real action (`ModuleController.stopModule()`) or cancels, and edits the original
   message (`editMessageText`) to show the result and **clear the keyboard** (so the
   user cannot tap the same buttons again on an already-resolved message).

**Double-tap protection**: each `callback_query.id` is only processed once — a bounded `Set`
(`processedCallbackIds`, cap of 200 entries, with a parallel FIFO queue to evict the
oldest) discards any repetition of the same id, whether because Telegram resent the update or
because the user managed to tap the button twice before the keyboard disappeared on the
client side.

## Commands

| Command | What it does |
|---|---|
| `/status` | Lists the modules with a switch (`hasSwitch: true` in `modules.json`) that are running NOW — via `SystemMetrics.moduleStates()` (same source of truth as `ModuleController`/`ModuleCatalog`). |
| `/start <module>` | `ModuleController.startModule(moduleId, context)` — same path as the UI switch. With no argument, shows `/help`. |
| `/stop <module>` | Asks for confirmation (see above) before `ModuleController.stopModule()`. |
| `/metrics` | CPU/RAM/storage/battery/network — via `SystemMetrics.kt`. The CPU % takes 2 samples 400ms apart (same criterion as `MonitorFragment`). |
| `/ssh` | Remote/SSH status — `ModuleController.isRunning("remote")` + fixed port `8022`. |
| `/help` | Command list. |

Command parser: plain text, no NLP — `"/command@BotName argument"` (the format Telegram
uses in group chats) is also recognized, trimming the `@BotName` before comparing.

## UI — Settings → Notifications

The "🤖 Telegram Bot (receive commands)" switch lives in the SAME card that has the
token/chat id/"Test" button (`ConfigFragment.setupTelegramSection()`) — it adds no new fields,
it reuses the existing ones. When enabling it:

1. Validates that the token/chat id (those on screen at that moment) are not empty — if
   missing, it reverts the switch and shows the same toast that "Test" uses for the same case.
2. Saves them in `SharedPreferences` (same keys as `TelegramNotifier`) and in
   `pref_telegram_bot_enabled` (persisted intent — what the switch reflects when the screen
   reloads).
3. Calls `TelegramBotService.start(context)`.
4. If it started, invokes `BatteryRestrictionHelper.requestDisableBatteryRestrictions(activity)` —
   the same helper the rest of the app uses to ask the user to exclude Kairos from the aggressive
   per-manufacturer battery restrictions (Samsung/Xiaomi MIUI/Huawei/OPPO/Vivo/
   OnePlus/Asus) — a long-running foreground service with a thread blocked on network I/O much of
   the time is exactly the pattern those restrictions tend to kill after hours in the background.

Turning the switch off calls `TelegramBotService.stop(context)` and clears the persisted intent.

## Secrets — never shown again

The token field always starts empty (with a hint saying "one is already saved — leave it
empty to keep it or type a new one to replace it"), uses a password `inputType`
(typing mask), and a `resolveTelegramToken()` function resolves to the saved value only if
the field was left empty — the saved token is never shown on screen again. The chat id (not a
secret in itself, it is a numeric identifier without which nothing can be sent or received —
comparable to the `chat.id` that already appears in any forwarded message) is shown prefilled.

The token does not appear in logs: no `KairosLogger.log()` in `TelegramBotService.kt`/
`TelegramNotifier.kt` includes the token or chat id value, nor is it sent in any message from the
bot itself.

## Known limitations

- **It does not survive Android killing the app's whole process.** There is no `BOOT_COMPLETED`
  receiver or explicit auto-restart logic in `TermuxApplication.onCreate()` — if the
  user enabled the bot and the Kairos process dies (phone reboot, "Force stop",
  the system kills the process for memory), the switch in Settings will keep showing the
  persisted state (`pref_telegram_bot_enabled = true`) but the real service is not running until
  the user reopens the app and re-enables the switch by hand. An auto-start from
  `TermuxApplication.onCreate()` (which runs in every app process, including
  `:xserver`) was ruled out because of the real risk of also starting the service in the `:xserver` process without a
  reliable "main process" guard — two different processes calling `getUpdates` with the same
  offset would compete for the same updates. If revisited, the right guard is to compare
  `Application.getProcessName()` (API 28+) or `ActivityManager.getRunningAppProcesses()` (API
  26-27) against the package name without the `:` suffix, before calling `TelegramBotService.start()`.
- **Whitelist of only 1 chat id** — there is no list of several authorized `from.id`s. If the user
  needs to operate the bot from more than one Telegram account, it is not supported.
- **No independent "no-confirmation mode" gate** — today the only confirmation is the one for
  `/stop`, and there is no way to require the user to "enable" remote control from the device
  itself before it works. For the current catalog (few commands, one with
  confirmation) this is considered proportionate; if the command catalog grows (e.g. running arbitrary
  commands, installing modules), it would need to be reconsidered.
- **`/start`/`/stop` do not validate that `<module>` is a real `modules.json` id before calling
  `ModuleController`** — `startModule()` already returns `(false, "Unknown module")` for a
  nonexistent id, so the user gets a clear error message, but there is no autocomplete or
  list of valid ids inside the chat itself (there is one via `/status`, which lists the real
  modules with a switch).
- **No commands for other modules beyond start/stop/status/metrics/ssh** (e.g. there is no
  `/logs <module>`, no way to send installation output over Telegram).
- **`ActionApprovalGate` (`app/src/main/java/com/termux/app/util/ActionApprovalGate.kt`) is not
  connected to this bot**, even though its own KDoc says "the first real consumer is the Telegram
  bot": `TelegramBotService.kt` calls `ModuleController.startModule()`/
  `stopModule()` directly, without going through the gate or its policy (`ActionType.MODULE_START`/
  `MODULE_STOP`, both `ApprovalPolicy.REMEMBERABLE`). In practice this is CONSISTENT with the
  rest of the app: the UI switch does not go through the gate either to start/stop a module (the
  switch tap IS the consent, with no dialog) — of the 9 `ActionType` actions, only
  `MODULE_UNINSTALL_DEEP`/`MODULE_REINSTALL_CLEAN` (`ApprovalPolicy.ALWAYS_ASK`) are really wired up,
  in `BaseModuleFragment.confirmUninstallModule()`. The rest of the catalog
  (`MODULE_START/STOP/STOP_ALL/INSTALL/UNINSTALL_SOFT`, `REMEMBERABLE` policy) is prepared code
  with no real caller yet anywhere in the app — it is not a limitation specific to the Telegram
  bot. `/stop` has its own two-step confirmation, independent of the gate.

## Relevant files

- `app/src/main/java/com/termux/app/TelegramBotService.kt` — the whole service.
- `app/src/main/java/com/termux/app/util/TelegramNotifier.kt` — outgoing sends (shares the
  preference keys `PREF_BOT_TOKEN`/`PREF_CHAT_ID`).
- `app/src/main/java/com/termux/app/util/SystemMetrics.kt` — real source for `/status` (via
  `moduleStates()`) and `/metrics` (RAM/CPU/storage/battery/network).
- `app/src/main/java/com/termux/app/ui/ConfigFragment.kt` — `setupTelegramSection()`, switch
  inside the same card.
- `app/src/main/AndroidManifest.xml` — `<service>` declaration (main process,
  `foregroundServiceType="dataSync"`, same type as `X11Service`/`RunCommandService`).
- `app/src/main/res/values/strings_config.xml` + `values-en/strings_config.xml` — strings for the
  switch and the bot's replies (`telegram_bot_*`).
