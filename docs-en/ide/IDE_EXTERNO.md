# IDE_EXTERNO.md — Historical note: why Studio is not a separate Android app

> This document describes a design alternative that was evaluated and dropped. The current
> architecture is described in [`IDE_INTEGRADO.md`](IDE_INTEGRADO.md) — read that document first.
> This file is kept because it documents the analysis of open source reference IDE projects and
> the reasoning behind the final decision, which is still relevant to understanding why Studio is
> built the way it is.

## The original idea: two independent APKs

At an early design stage, the code editor was considered as a **fully independent Android
project**, with its own `applicationId`, its own build/release cycle, sharing no process with
Kairos. The relationship between the two would be purely optional and discovered at runtime: if
Kairos was installed and running a local AI engine (Ollama or an OpenAI-API-compatible server),
the external IDE could use it as an AI backend without bringing its own inference engine; if not,
it would still work with "bring your own key" cloud providers or with no AI at all. There would
never be a hard build-time or runtime dependency between the two APKs.

Communication with Kairos's Termux environment would go through the standard `RUN_COMMAND` Intent
mechanism used by Termux's companion apps — the same pattern used by third-party integrations
like Tasker/Widget add-ons.

## Why it was dropped

This interpretation turned out to be a misreading of the actual intent: the goal was always
**direct, same-process communication** with Termux and with AI providers, not an Intent bridge
between two separately installed apps. An IDE integrated as just another screen of the same APK
achieves that direct communication natively; an external app can only approximate it through
indirect mechanisms (Intents, sockets to localhost), with real limitations: it depends on an
explicit "allow external apps" permission in the Termux environment's configuration, and every
operation goes through an Intent/BroadcastReceiver layer instead of a direct same-process call.

The code work done during this exploration (an editor with TextMate syntax highlighting, a file
tree, a tab system, Git integration, project-wide search, physical keyboard shortcuts, a command
palette, a theme selector) wasn't lost — it's the foundation that was ported into the integrated
design described in `IDE_INTEGRADO.md`, adapting only the "how it talks to Termux" layer (which
was drastically simplified by moving to same-process execution).

## Audit of reference Android IDE projects

As part of this investigation, several open source Android projects implementing a full IDE
(editor + Gradle build) were evaluated to decide whether it made sense to start from a fork
instead of writing one from scratch.

### AndroidIDE and its forks

**AndroidIDE** (and active forks of the same project) was the most complete reference found: a
real, independently buildable Android project, with its own Gradle modules, using the **real
Gradle Tooling API** — a separate Java process that runs the build and communicates over sockets
with the main app — to compile Android projects directly on the device, without root. The Android
SDK is managed from an integrated terminal using standard tooling; the JDK ships embedded in the
APK itself. It also comes with a new-project wizard with templates by Activity type, a live
build-log panel, a visual UI designer, and a "bring your own key" AI chat with several cloud
providers.

It's a real project with active development, licensed under **GPLv3** — any fork that gets
distributed inherits that licensing obligation (source available, same license for the
derivative).

### Other projects evaluated

- A text editor with TextMate grammars already ported (same GPLv3-type license) — useful as a
  syntax-highlighting reference, but with no ability to compile Gradle projects: it's an editor,
  not a full IDE.
- A web editor (Monaco) meant to run against a remote code-evaluation service — with no real
  Android/Termux component, not applicable.
- A Flutter project with its own embedded AI agent doing tool execution with path-based
  sandboxing — a different stack (Flutter, not native Kotlin/Java) and with an AI-agent design
  that clashes with Kairos's approach (delegate tool execution to existing external CLIs, rather
  than reimplementing an agent of its own).

### Conclusion of this stage

The decision was **not to vendor** any of these complete projects into the repository (given the
weight of the code and the GPLv3 licensing obligations of inheriting an entire module), and
instead to build a lightweight editor of its own, from scratch — using the reference projects
purely as inspiration for UI and architecture patterns, never copying application code directly.
The TextMate grammars (syntax-highlighting data, not application code, of standard MIT origin)
were reused with full attribution, since they're declarative data, not program logic.

## "Bring your own key" AI provider selection pattern

One of the UI patterns confirmed useful in this research was a dedicated AI provider selection
screen, with the API key stored encrypted and separated per provider — the same multi-provider
concept (several cloud providers plus a fallback to a local AI engine) that Kairos's main chat
already uses. This pattern was implemented in Studio with encrypted storage
(`EncryptedSharedPreferences`), unlike some of the audited references, which stored the key in
plain text.

## Features that resulted from this exploration stage

The work from this stage ended up producing, among other things, the following pieces — all
ported and in use within the current integrated design:

- An editor with real TextMate syntax highlighting for several languages (Kotlin, Java, Python,
  JavaScript/TSX, XML, JSON, Gradle/Groovy, Shell, Markdown), with custom color themes.
- A navigable file tree with file operations (create, rename, delete, copy path), lazy loading,
  and a name filter.
- A tab system for open files with an unsaved-changes indicator.
- A real Git panel (status, commit, push/pull, log, diff) built by assembling and parsing the
  output of standard `git` commands.
- Recent-project management and session restoration (which tabs were open) when reopening the
  app.
- Find/replace within the active file and across the whole project, with safety caps to avoid
  hanging on large projects.
- Physical/Bluetooth keyboard shortcuts (Ctrl+S, Ctrl+F, Ctrl+P, Ctrl+Tab, etc.) and a virtual
  keyboard bar with "sticky" modifiers for touch-only devices without a physical keyboard.
- A command palette (Ctrl+P) with a registered list of actions and a text filter.
- Several editor color themes, with runtime reload.

All of these pieces remain in use today within Studio — see
[`IDE_INTEGRADO.md`](IDE_INTEGRADO.md) for the current architecture and
[`PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md`](PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md) for later
improvements (multi-project support, real autocompletion via LSP).
