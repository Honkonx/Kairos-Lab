# Telegram Bot

**Native Kairos component**, not an installable module from `modulos/` — same criteria as
Homelab: conceptually it's an app control feature, so it's documented here even though it has
no script of its own and doesn't appear in the Store.

---

**App:** Kairos (termux-app fork)
**Service:** `TelegramBotService.kt` (foreground service, the app's main process)
**UI:** Settings → Notifications

---

## 1. What it is

Kairos could already **send** notifications to a Telegram chat (a module starting/crashing,
etc.). This piece adds the opposite direction: a Telegram bot that **receives** text commands
and button taps, and turns them into real actions on Kairos's modules — start/stop a module, or
check what's currently running — all without exposing any public port or needing a domain of
its own.

It works via *long-polling*: the phone itself keeps asking Telegram "anything new?" in a loop,
instead of Telegram needing to call a Kairos server (which doesn't exist as a public endpoint).
It's the correct pattern for a mobile client with no server behind it.

## 2. Security — single-user allowlist

The same `chat_id` already configured for outgoing notifications now serves double duty:
destination for outgoing messages **and** the only user authorized to send commands. Any
message or button tap from anyone else is ignored — the check is fail-safe: if the token or
chat id is missing, the bot simply won't start, instead of starting up unprotected.

## 3. Two-step confirmation for risky actions

The only command that can affect something in use (`/stop`, stopping a module) is never
executed directly from the command text: the bot replies with two buttons ("Yes" / "No") and
only runs the real action once the user confirms by tapping the right one. The message then
updates with the result, and the buttons disappear to prevent tapping them again on an
already-resolved message.

## 4. Available commands

| Command | What it does |
|---|---|
| `/status` | Lists the modules currently running. |
| `/start <module>` | Starts a module — same path as tapping the switch in the app. |
| `/stop <module>` | Asks for confirmation (see above) before stopping a module. |
| `/ssh` | Remote/SSH status and its port. |
| `/help` | List of commands. |

## 5. Turning it on

The "Telegram bot (receive commands)" switch lives on the same Settings → Notifications card
where the bot token and chat id for outgoing notifications are already configured — no new
fields are added. Turning it on: if the device has aggressive manufacturer battery restrictions,
Kairos asks to exclude the app from them (a long-running service listening on the network in
the background is exactly the kind of process those restrictions tend to kill after a few
hours).

## 6. Known limitations (v1)

- **Doesn't survive Android killing the app's whole process** (device reboot, "Force stop", or
  the system killing it for memory) — you need to reopen Kairos and flip the switch back on by
  hand.
- **Only one authorized chat id** — there's no list of multiple allowed users.
- **Commands limited to start/stop/status/ssh** — there isn't yet, for example, a command to
  view a module's logs over Telegram.

## 7. The token is never shown again

The bot token and chat id are stored with the same security standard as any other Kairos
credential: the fields on the Settings card are the only place they're ever shown — they don't
appear in any log or in any message the bot itself sends.
