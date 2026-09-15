# Estudio — Kairos's integrated IDE

Estudio is Kairos's integrated development environment: just another tab within the app, with
the same level of direct integration with the Termux engine and with AI providers as any other
screen in the application — no intermediate inter-process communication layers.

## What it is

A full code-editing screen inside the app's main panel, designed for working on a real project
(not just opening a single loose file). It coexists with the simple text editor in the file
explorer: that one remains the quick way to open-edit-save a single file, while Estudio is the
full "development mode," with a file sidebar, tabs, git integration, project-wide search, and an
integrated terminal.

## Main features

- **Multi-project**: up to 3 projects open at the same time, with a chip selector to switch
  between them when more than one is open.
- **Editor with real syntax highlighting** (TextMate, via the `sora-editor` library), with a
  catalog of 14 color themes (Kairos's own 3 themes plus Dracula, Nord, Gruvbox, Solarized,
  Monokai, One Dark, Atom One Light, and Ayu, the latter ones in light/dark variants where
  applicable) — the theme can be switched from either the editor settings or the command palette
  itself.
- **Language support via LSP** (Language Server Protocol) for Bash and Python — real
  autocompletion based on the standard protocol, not a homegrown heuristic. The corresponding
  language server is installed automatically, in the background, the first time a file of the
  supported language is opened.
- **Git panel**: repository status, diffs, stash, and basic operations on the open project.
  Authenticating with GitHub for push/pull uses the OAuth device flow (log in from the browser,
  no pasting credentials by hand) — once authenticated, the token is never shown on screen again,
  it can only be used, replaced, or signed out.
- **Integrated terminal**: direct access to a shell session within the same tab, without leaving
  the editing flow.
- **Build log with clickable diagnostics**: build errors and warnings show the real file, line,
  and column, and tapping them opens the editor right at that location.
- **Built-in AI assistance**: support for several cloud AI providers (using the user's own API
  key) and for Kairos's own embedded local AI engine, right within the editing flow.
- **Project-wide search**: search and replace both within a single file and across the entire
  open project, with a case-sensitivity toggle.
- **Undo/redo** with dedicated keyboard shortcuts, on top of the general physical/Bluetooth
  keyboard navigation shortcuts.
- **Command palette** (Ctrl+P) for quick navigation and actions — commands that don't apply to
  the current context (for example, no project or file open) show up disabled instead of failing
  when tapped.

## How commands are executed

Estudio runs shell commands (git, builds, running scripts) directly within the app's own
process, using the same mechanism the rest of Kairos's modules use to invoke binaries from the
Termux environment — no special permissions or external intents. This means any build, version
control, or project-run operation has the same latency and reliability as the rest of the
application.

## Current limitations

- LSP support only works when the open project resolves to a real filesystem path, not to a
  read-only reference through Android's document picker.
- The code editor is a component shared across the open file tabs — only the active tab has live
  diagnostics at any given moment; a background tab stays connected to its language server, but
  doesn't update diagnostics until it's switched back to.
- More advanced LSP protocol features (hover information, go to definition, rename, automatic
  formatting) are supported by the underlying library but don't yet have a UI wired up for them
  in Estudio.
