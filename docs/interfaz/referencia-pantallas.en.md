# Screen reference — main controls

What each control does on Kairos's navigation and system screens. Covers the 12
navigation/system screens (Modules, Chat, Config, Tunnel, Monitor, Plugins, Cloud, X11 / Mini
PC, Files, Local AI, Models). Individual modules (Ollama, n8n, Claude Code, etc.) have their own
control reference in `docs/modulos/`.

## "More" menu — access to secondary screens

The "More" item on the bottom navigation bar opens a bottom sheet with a 3-column grid of
shortcuts: Monitor, Files, Tunnel, Cloud, Plugins, and Config — the screens that don't have their
own fixed icon on the navigation bar. Tapping a cell navigates directly to that screen and closes
the sheet.

## ModulesFragment — home screen ("Modules")

| Control | What it does |
|---|---|
| "Installed"/"Active"/RAM stats | Read-only, refreshes every 5s |
| "↻ Refresh" (icon in the stats row) | `git pull` on the scripts' own code, distinct from pull-to-refresh (which only re-reads local state, no network) |
| "🎙 Voice → Agent" | Opens a voice-dictation dialog that sends the request as the initial prompt to any supported AI CLI |
| Pull-to-refresh (swipe down) | Re-reads registry + live sessions, no network |
| Module row (tap) | If not installed, opens the install sheet; if already installed, opens the detail screen |
| Row ON/OFF toggle | Starts/stops the module's real process |
| "Go to Plugins →" (empty state) | When no modules are installed, redirects to the Store instead of leaving the screen empty |

## ChatFragment — "AI Chat"

### Engine picker (screen shown before the chat)

| Control | What it does |
|---|---|
| "🌐 Ollama Termux" card | Checks that Ollama is installed and enters the chat with that engine |
| "📱 Local AI (llama.cpp)" card | Enters directly — embedded engine, no install check needed |
| "☁️ Cloud API (your key)" card | Opens the provider picker (Gemini/DeepSeek/OpenAI/Anthropic/Grok); if the key is missing, asks for it first |
| "Embedded (no port)" / "Server (port 8085)" switch | Changes llama.cpp's transport: in-process JNI vs. HTTP to the local server — server mode lets you use the engine from another machine on the network |

### Top bar / input

| Control | What it does |
|---|---|
| Engine button (🔄) | Returns to the engine picker |
| Model picker (text, tap) | Lists the real models available for the active engine — models from different engines are never mixed in the same picker |
| "🎭" (persona) | System-prompt presets |
| "Web: ON/OFF" | Enables/disables web search injected as context |
| Microphone | Voice dictation |
| Clip/attach image | Only enabled if the active engine isn't the local embedded one (no vision support) |
| Send | Sends the message to the active engine, or runs a shell command if the text starts with `!` |
| Clear history | Deletes the in-memory and on-disk history |
| Settings (⚙) | Sliders for temperature (0.00–2.00), context size (512–8192), history limit (10–500, or "∞") |
| Cancel (during generation) | Cuts the active connection and interrupts the ongoing generation |
| "Show details"/"Hide details" (error bar) | Expands/collapses the technical detail of an error |
| `<think>` bubble (if the model emits one) | Expands/collapses the model's reasoning |

## ConfigFragment — "Config"/Settings

| Control | What it does |
|---|---|
| "🎨 Theme" | Inline picker (Dark/Signal/Light), applies on tap |
| "Auto-start modules" (switch) | Installed modules start when Kairos opens, not when the device boots |
| "Check system packages" | Verifies the required base packages |
| "Check module updates (all)" | Checks available versions (npm, GitHub Releases, PyPI, etc.) without installing anything automatically |
| "Notifications for stopped modules" (switch) | Notifies when a module goes from running to stopped between checks |
| "Quick-access floating widget" (switch) | Requests overlay permission if missing and starts/stops the floating widget |
| "⚡ Install fzf + autosuggestions (zsh)" | Installs terminal enhancements |
| "✏️ Set nvim as default editor" | Configures the `$EDITOR` variable |
| "⌨ Use external keyboard/mouse (OTG/Bluetooth)" | Shows a guide |
| "Classic terminal (no adapted UI)" (switch) | Toggles between the terminal with the adapted bar and the original, unmodified terminal |
| "＋ Add variable" (environment) | Dialog to add a persistent environment variable |
| "↻ Re-run setup" | Re-runs the initial setup |
| "☁ Full backup" / "⭳ Restore backup" | Full backup and restore of the environment |
| "⬆ Export config" / "⬇ Import config" | Exports/imports the app's configuration |
| "📋 Export diagnostics" | Generates a diagnostics report |
| "⚠ Reinstall stack" | Reinstalls the entire stack — destructive action, requires typed confirmation |
| "🗑 Uninstall a module" | Selection and uninstall dialog |
| "🚪 Exit (stop everything and close)" | Kills all active services and closes the app |
| Telegram Token/Chat ID + "🧪 Test" | Saves credentials and sends a test message for remote notifications |

## TunnelFragment — "Tunnel"

| Control | What it does |
|---|---|
| Provider row (Cloudflare/ngrok, edit) | Saves a persistent token/domain per provider |
| "▶ Cloudflare" (per module) | Anonymous Cloudflare tunnel — not shown for modules that only expose raw TCP (e.g. SSH) |
| "🔑 With token" | Tunnel using the saved own token/domain |
| "🚇 ngrok" / "🚇 ngrok tcp" | Anonymous ngrok tunnel — the `tcp` variant is for non-HTTP protocols like SSH |
| "🔑 ngrok+domain" / "🔑 ngrok tcp+auth" | ngrok tunnel with your own token |
| "⏹ Stop" | Cuts the active tunnel |
| "Copy" (next to the active URL) | Copies the URL to the clipboard |

See `docs/interfaz/tunel-multidominio.en.md` for the full data model (saved domains/tokens,
per-module assignment, verification).

## MonitorFragment — "Monitor"

| Control | What it does |
|---|---|
| RAM ring (Device section) | Read-only, refreshes every 5s |
| Storage ring (Device section) | Read-only |
| Storage card (tap, no permission) | Requests the permission and sets up shared storage in one step |
| Process row — "⏹ Stop"/"▶ Restart" | Toggles depending on the process's real state |
| "🗑 Delete" | Removes the process from the manager (with confirmation) |
| "↻ Reinstall" (only if the process manager is unavailable) | Reinstalls the manager without leaving the screen or reinstalling the whole app |
| "Disable" (Phantom process killer) | Offers 3 paths depending on root access: automatic, port auto-detection, or a manual tutorial — Android can kill background modules if this protection stays active |
| "Disable" (Battery optimization) | Requests excluding the app from aggressive battery optimization |
| "Auto-configure (no PC)" / "Auto-detect port (beta)" / "View manual tutorial" | Three paths of the rootless setup flow, depending on how much automation the device accepts |

## PluginsFragment — "Plugin Store" (menu "More" → Plugins)

| Control | What it does |
|---|---|
| Search bar | Filters by name/id/description/category live |
| "↻ Catalog" | Downloads the remote catalog and merges it with the local one |
| "📦 Local package" | Installs the user's own `.deb` or `.tar.gz` |
| "View full example ↗" | Shows the full contract of a local package (manifest + script) |
| Plugin card (tap) — not installed | Opens the install sheet |
| Plugin card (tap) — installed | Dialog with Change method (if it has real variants) / Enable-Disable / 🔄 Clean reinstall / 🗑 Remove from Store (local plugins only) / Uninstall |
| "🔄 Clean reinstall" | Deletes the real package and reinstalls from scratch — for a module in a broken state that a normal reinstall doesn't fix |
| Enable/Disable | Hides or restores the module on the Modules screen without uninstalling it |
| "Deep uninstall" checkbox | If checked, also deletes the real package, not just the state |
| "🗑 Remove from Store (local)" | Deletes the plugin from the local catalog and its script — doesn't uninstall the package if it was already installed |

## NubeFragment — "Cloud"

| Control | What it does |
|---|---|
| "Start"/"Stop" (server) | Starts/stops an embedded HTTP file server, protected by a token |
| "☁️ Cloudflare" / "🚇 ngrok" (chips) | Publishes the Cloud server via tunnel, reusing the same logic as the Tunnel tab |
| "Stop tunnel" | Cuts the active tunnel |
| "Copy" / "Share" | Copies or shares the active URL (public if there's a tunnel, local otherwise) — the link already includes the token |
| "↑" (go up a folder) | Navigates to the parent — disabled at the root |
| File row (tap) | If a folder, enters it; if a file, shows its size |
| File row (long-press) | Rename/Delete menu |

## Mini PC — X11 and desktop (inside `EntornoFragment`, "Mini PC" tab)

| Control | What it does |
|---|---|
| "Enter X11" | Starts the embedded X11 server and opens the viewer in a new task |
| "X11 settings" | Resolution, scale, orientation, keyboard, touch, picture-in-picture, fullscreen |
| "🖵 VNC Viewer" | Installs/starts a VNC client if needed and opens the viewer |
| "Close X11 server" | Confirmation and shutdown of both the viewer and the X11 server process |

## FileManagerFragment — "Files"

| Control | What it does |
|---|---|
| "Termux $HOME" / "Internal storage" tabs | Switches the navigation root between two fixed roots |
| "↑" (go up a folder) | Navigates to the parent — disabled at the current root |
| File row (tap) — folder | Enters the folder |
| File row (tap) — editable text file | Opens the built-in editor (known extensions, limited size) |
| File row (tap) — other | Shows name and size |
| File row (long-press) | Copy/Cut/Rename/Delete menu (+ "Paste here" if there's something on the clipboard) |
| "Paste here" | Copies or moves the clipboard file into the current folder — blocks pasting a folder into itself |

## LocalAIFragment — "Local AI"

| Control | What it does |
|---|---|
| "💬 Go to chat" | Jumps to the Chat tab — there's no separate chat screen, it's always the same chat |
| "Vulkan if available" / "CPU only" radio | Preferred GPU backend for local inference |
| "Temperature" slider (0.00–2.00) | Adjusts the parameter, warns if it exceeds 1.0 |
| "Context" slider (512–8192 tokens) | Adjusts the context size, warns if it exceeds what's recommended for the device's RAM (without blocking) |
| "📋 Model list (N/M downloaded)" | Curated catalog of 20 GGUF models (0.5B–14B) in a dropdown dialog |
| Catalog item (tap, not downloaded) | Confirmation with description and size, then downloads with a real progress bar |
| "Delete" (per downloaded model) | Deletes the model from storage |
| "📂 Import from storage" | File picker for an already-downloaded `.gguf` |
| "+ Add model by URL (advanced)" | Downloads a `.gguf` from any direct URL, outside the curated catalog |

### ModelsFragment — Ollama sub-screen ("Models")

Distinct from the GGUF model store above — specific to the Ollama engine (local HTTP API, port
11434).

| Control | What it does |
|---|---|
| Curated catalog item (tap, not installed) | Downloads the model with real live progress (%, speed, ETA) |
| "⚠ May not fit in RAM" warning (if applicable) | Estimates the RAM required against the device's total RAM and warns without blocking |
| Installed model row (tap) | Dialog with details (parameters, family) and a delete option |
| "+ Download model (advanced)" | Dialog to request a model by exact name, outside the curated catalog |
