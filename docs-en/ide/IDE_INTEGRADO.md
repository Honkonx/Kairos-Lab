# IDE_INTEGRADO.md — Studio: the IDE lives inside the Kairos APK

## The decision, in one sentence

Studio (Kairos's code editor/IDE) **is not a separate app** — it's just another screen inside the
same APK, exactly like Chat, Modules, System, or Files. It builds together with the rest of the
app, runs in the same process, and shares the same Termux engine the rest of Kairos already uses.
The underlying reason is direct, full communication with the Termux environment and with AI
providers — something a separate external app can only achieve through indirect mechanisms
(Intents, sockets to localhost), while a screen inside the same app gets that natively, because
it's already in the same process.

Before settling on this design, an alternative was explored — a standalone Android IDE project.
See [`IDE_EXTERNO.md`](IDE_EXTERNO.md) for that history and why it was dropped.

## Where Studio lives in the app's navigation

Kairos uses a bottom navigation bar with fixed slots (Modules, AI Chat, System, Settings, More) —
the fifth ("More") opens a secondary menu with additional screens (Monitor, Files, Tunnel, Cloud,
Plugins, X11). Studio is one more entry in that menu (`nav_studio`); it doesn't occupy a fixed
icon on the main bar.

### Relationship with the file manager's quick editor

Before Studio existed, Kairos already had a simple text editor (`EditorFragment.kt`) used by the
Files screen — built on `sora-editor` with real TextMate syntax highlighting (12+ languages).
Studio and that quick editor **coexist**, with distinct roles:

- The file manager's quick editor is for opening, editing, and saving a single loose file,
  without the overhead of a full project.
- Studio is the full experience: a file sidebar, tabs, Git integration, project-wide search,
  keyboard shortcuts, and an AI chat — meant for "dev mode", with a project opened as a
  workspace.

Separate from both, there's also a module that runs Neovim (with NvChad and code-completion
plugins) inside a terminal session — a TUI-style, not graphical, editor for anyone who prefers
that workflow from the command line.

## How it runs commands — same process, no special permissions

Studio runs shell commands (compiling with Gradle, running Git, installing language tooling)
directly inside the app's own process, using the same mechanism the rest of Kairos already uses
to launch processes in the embedded Termux environment: it invokes the real `bash` binary from
that environment, with the Termux environment's variables already resolved (binary paths, `HOME`,
etc.), and captures stdout/stderr/exit code.

This is deliberately different from an external-app approach communicating via Intents — no
special permission is needed and no external app has to be installed; the execution engine is
already part of the same APK.

## Code structure

Studio's code lives in its own package, organized by responsibility:

- **`ai/`** — HTTP client for AI providers (several "bring your own key" cloud providers plus a
  fallback to a local AI engine if Kairos has one running), provider configuration screen.
- **`filetree/`** — the project's file tree (lazy loading via Android's storage access
  framework, per-file-type icons, file operations: create, rename, delete, copy path).
- **`tabs/`** — the system of open-file tabs over a shared code editor.
- **`git/`** — the Git panel: status, commit, push/pull, log, diff — builds real `git` commands
  and parses their output.
- **`search/`** — find and replace, both within a single file and across the whole project.
- **`keyboard/`** — physical/Bluetooth keyboard shortcuts (Ctrl+S, Ctrl+F, Ctrl+P, etc.) and a
  virtual keyboard bar with "sticky" modifiers (Ctrl/Alt/Shift) for touch-only devices.
- **`palette/`** — a VS Code-style command palette (Ctrl+P), with a registered list of actions
  and a text filter.
- **`settings/`** — editor preferences (font size, tab size, line wrap, line numbers, syntax
  theme).
- **`build/`** — the live build-log panel.
- **`editor/`** — TextMate syntax highlighting and the editor's in-file search controller.
- **`lsp/`** — integration with real language servers (see below).

The text editor itself is built on the open source `sora-editor` library, the same one the file
manager's quick editor uses, with TextMate grammars for per-language syntax highlighting.

## Real autocompletion via LSP (Language Server Protocol)

Studio connects to real language servers (standard LSP protocol, not a custom heuristic) to
provide real autocompletion, diagnostics, hover, go-to-definition, and symbol renaming — not just
syntax highlighting. Current coverage:

- **Bash/shell** (`.sh`, `.bash`) via `bash-language-server`.
- **Python** (`.py`, `.pyw`) via `pylsp` (`python-lsp-server`).

Both were chosen because they're the languages the app's own managed scripts are written in
(Kairos's modules are bash scripts, and several internal tools are Python). Kotlin/Java was
deliberately left out of the initial scope: there's no lightweight language server installable
without a full JDK and a resolved Gradle project model — a separate effort.

The corresponding language server installs itself in the background, the first time a file of a
supported language is opened (showing only a brief notice, no blocking dialog) — as long as the
required runtime (Node.js for the bash server, Python for `pylsp`) is already available.

**Known limitations of the current LSP support:**
- It only works with projects whose folder resolves to a real path on the device's primary
  storage — a project opened only through an external storage provider (with no real file path)
  has no LSP.
- Studio uses a single shared code editor across tabs — the active tab has full UI binding; a
  background tab stays connected to the language server but doesn't show live diagnostics until
  it becomes active again.
- Autocompletion and diagnostics (error underlining) come "for free" once connected; hover,
  go-to-definition, and symbol rename are wired to a context menu that appears on long-press over
  the editor; find-references and auto-formatting still have no dedicated UI (the underlying
  library supports them at the protocol level).

## Multi-project support

Studio supports up to 3 simultaneously open projects. A project selector (a row of chips) appears
above the file tabs when 2 or more projects are open — switching the active project keeps the
other projects' tabs and state in memory instead of closing them. Past the limit, the
longest-open project closes automatically to make room for the new one. All tabs with unsaved
changes are auto-saved before switching the active project.

## Clickable build diagnostics

The build-log panel highlights error/warning lines by severity and lets you tap a line with a
recognized file location to open that file directly in the editor, at the exact line and column.

## Current state and honest limitations

- Studio's AI chat uses its own client, separate from the app's main chat — a future unification
  into a shared layer is a possible improvement, not a blocker.
- Closing a tab with unsaved changes now explicitly asks (save/discard/cancel) before losing
  content — fixed after a code audit (see
  [`AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md`](AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md)).
- The virtual keyboard's "sticky" modifiers (Ctrl/Alt/Shift locked via long-press) now reset
  automatically when the app goes to the background or a new project is opened, to avoid them
  staying "stuck" indefinitely without the user noticing.
- Drag-to-reorder tabs, a visual per-hunk diff in the Git panel, and an editor minimap are still
  unimplemented — they were out of scope for the most recent redesign (see
  [`PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md`](PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md)).
