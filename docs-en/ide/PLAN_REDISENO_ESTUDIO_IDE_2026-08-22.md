# PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md — Studio redesign: multi-project, themes, and LSP

This document describes the process of redesigning Studio, starting from a review of what
features already existed, what other open source Android editors/IDEs offered as reference, and
what was implemented as a result.

## Starting point

Before this redesign, Studio already had a solid base: an AI chat with a configurable provider, a
Git panel, a command palette, its own search engine, editor settings (including a TextMate syntax
theme selector), a virtual keyboard bar with shortcuts, a build-log panel, and a tab system. Two
real limitations were identified:

1. Studio could only have **one project open at a time** — opening a new project replaced the
   previous one, with no notion of a "background project".
2. Only the **code text** had its own theme selector (via the TextMate syntax theme) — the rest
   of Studio's interface (toolbars, panel backgrounds, dialogs) inherited the app's general theme,
   with no independent theme of its own.

## What other open source Android editors/IDEs offer as reference

- Active AndroidIDE forks demonstrate a **real Gradle Tooling API** working on-device (a separate
  Java process communicating over sockets), **project templates** by Activity type, and a
  **"bring your own key" multi-provider AI selector** — a pattern already replicated in Studio's
  AI provider settings screen.
- A compiler-output parser (for javac/aapt2/Kotlin analyzer output) that translates raw build text
  into structured diagnostics (severity, file, line, column, message) — a pattern applicable to
  Studio's build-log panel, which until then showed unstructured text.
- A code editor with a **real LSP connection** (standard language server protocol) wired to the
  graphical editor — the most relevant finding for giving real per-language autocompletion,
  beyond the lexical syntax highlighting that already existed.

No reference project directly solves **multiple simultaneously open projects** in a reusable way
— that part was designed from scratch for Studio.

## Multi-project support — implemented

The "current project" state (previously loose fields on Studio's main fragment) was extracted
into a proper project session, encapsulating the project folder, its open tabs, and its scroll
position. Studio now holds a list of up to **3 simultaneous project sessions** instead of a
single project.

- A project selector (a row of chips) appears above the file tabs when 2 or more projects are
  open — tapping a chip switches the active session without destroying the others, which stay in
  memory like browser tabs.
- Recent-project management and session restoration were extended to persist the full list of
  open sessions, not just the last one.
- Opening a fourth project automatically closes the least-recently-used session.
- Instead of prompting per unsaved tab when switching projects, the choice was to **silently
  auto-save** all modified tabs before switching — simpler and safer by default (an unsaved edit
  is never lost, at the cost of not being able to explicitly "discard changes" when switching
  projects).

**Real pending item**: the 3-simultaneous-project limit is a proposed value, not measured against
real memory usage on a mid-range device — it should be validated with Android's memory-diagnostic
tooling before raising the limit.

## Clickable build diagnostics — implemented

The build-log panel already highlighted lines by severity; the ability to tap a line with a
recognized file location was added, to open it directly in the editor at the exact line and
column — reusing the same real-path resolution already used by the "open search result" flow.

**Known limitation**: the log-line-to-diagnostic mapping assumes each logical log line occupies a
single visual line on screen — a very long log line that visually wraps can resolve to the wrong
diagnostic when tapped.

## Independent theme system for Studio's interface

**Design proposal** (not yet implemented at the time this document was written): a dedicated
interface theme for Studio (toolbars, panel backgrounds, dialogs), separate from both the app's
general theme and the editor's syntax theme — with a "sync with the app theme" option for anyone
who prefers a single global theme, and Studio-specific options for anyone who wants them kept
distinct. Studio's theme selector would reuse the same visual component as the app's general
theme selector, rather than creating a second selector style.

## Real autocompletion via LSP — implemented as a minimum viable version

The highest-effort piece of the redesign: Studio now connects to real language servers to provide
autocompletion, diagnostics, hover, go-to-definition, and symbol renaming — not just lexical
syntax highlighting.

### Library decision

The choice was to use the official LSP module of the same editor library (`sora-editor`) Studio
already uses for syntax highlighting, published as an independent package on Maven Central —
rather than reimplementing the LSP JSON-RPC protocol from scratch, or vendoring another project's
source code.

### Languages covered

- **Bash/shell** (`.sh`, `.bash`) via `bash-language-server`, installed through Node.js's package
  manager.
- **Python** (`.py`, `.pyw`) via `pylsp` (`python-lsp-server`), installed through `pip`.

Chosen based on real usage within the project itself: Kairos's module scripts are bash, and
several of its internal tools are Python. Kotlin/Java was left out of this first version — there
is no lightweight language server installable without a full JDK and a resolved Gradle project
model.

### Silent installation

The corresponding language server installs itself in the background, with only a brief notice (no
blocking dialog), the first time a file of a supported language is opened with the required
runtime already present. If the server's language runtime is missing, it warns once per session
without retrying on every subsequent keystroke.

### Hover, go-to-definition, and rename — wired to a touch menu

The underlying LSP library supports these three operations at the protocol level, but doesn't
wire them to any touch UI on its own (its out-of-the-box wiring assumes mouse/hover, not touch).
A dedicated context menu was implemented that appears on **long-press** over the editor, when the
active file has a supported language server, with three options:

- **Info (hover)** — shows type/documentation information at the tapped position.
- **Go to definition** — resolves the symbol's real location and opens that file at the
  corresponding line, reusing the same navigation mechanism as clickable build diagnostics.
- **Rename symbol** — asks for confirmation with a preview (how many files, how many changes)
  before touching any file; if any affected file can't be resolved to a real location (for
  example, an external dependency outside the open project), no file is written at all, to avoid
  leaving a rename partially applied.

### Real limitations of LSP support

1. It only works with projects whose folder resolves to a real storage path, not with projects
   opened only through an external storage provider.
2. Studio shares a single visible code editor across tabs — a background tab stays connected to
   the language server, but without live diagnostics until it becomes active again.
3. Find-references and auto-formatting still have no dedicated UI, although the library supports
   them at the protocol level.
4. It wasn't confirmed in every case whether a given language server announces support for each
   operation (for example, whether the bash server supports "go to definition") — the expected
   behavior in that case is to show "not supported" rather than fail silently.

## Features evaluated and explicitly not implemented

- **Editor minimap**: it couldn't be confirmed with certainty that the editor library version in
  use exposes this feature natively — still pending confirmation.
- **Drag-to-reorder tabs**: Studio's tab system uses a standard Material Design tab component, not
  a reorderable list — implementing this would require migrating the tab system to a different
  component, a larger refactor out of scope for this redesign.
- **Visual per-hunk diff in the Git panel**: identified as a desirable improvement but not
  implemented yet.
- **A built-in AI agent with tool execution inside the editor**: evaluated and dropped — it
  clashes with Kairos's design decision to delegate complex AI task execution to existing
  specialized external CLIs, rather than reimplementing an agent of its own inside the editor.
