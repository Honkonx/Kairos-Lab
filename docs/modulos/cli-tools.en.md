# Shared CLI Tools (generic parameterized Fragment)

**Kairos module** — 14 AI-agent/terminal-tool CLIs that share a single Fragment
(`CliToolFragment.kt`) instead of each having its own screen, because they're homogeneous
enough (command line, some with their own login/model/prompt) that 14 nearly identical
Fragments aren't justified.

---

**Fragment:** `app/src/main/java/com/termux/app/ui/CliToolFragment.kt`
**Shared config:** `CliModuleConfig` (data class) + `CLI_MODULE_CONFIGS` (map `id →
CliModuleConfig`), in the same file
**Modules using this Fragment:** `freebuff`, `codebuff`, `copilotcli`, `minimaxcli`, `mimocode`,
`mistralvibe`, `qwencode`, `kimi`, `kilo`, `cursor`, `hf`, `pi`, `codegraph`, `ohmypi`

---

## 1. Why one Fragment for 14 modules

Before this pattern, all these CLIs fell back to a generic Fragment (open in terminal + manage
projects + update/uninstall, with no special treatment) — none had buttons for login, direct
prompt, model selection, or local AI provider, even though several of these CLIs do genuinely
support those functions. `CliToolFragment` is a superset of the generic Fragment, not a
replacement: modules with no confirmed extra capability (e.g. `freebuff`, `codebuff`) stay at
exactly the same level as the generic fallback, on purpose — a login/prompt button is never
invented for a CLI whose real command isn't confirmed.

**Design rule**: every entry in `CLI_MODULE_CONFIGS` is confirmed by reading the module's
installation script plus each project's real official documentation — never assumed.

## 2. The `CliModuleConfig` contract

```kotlin
data class CliModuleConfig(
    val baseCommand: String,               // real binary (may differ from the module's id)
    val hasAuth: Boolean = false,           // does it have a login/authentication mechanism?
    val authCommand: String? = null,        // real shell subcommand (e.g. "kimi login") — null if there's no one-liner
    val authHint: String? = null,           // text shown when hasAuth=true but authCommand=null (login only interactive)
    val supportsDirectPrompt: Boolean = false,
    val promptTemplate: String = "",        // uses the literal placeholder "{PROMPT}"
    val hasModelSelector: Boolean = false,
    val promptWithModelTemplate: String = "", // uses "{PROMPT}" and "{MODEL}"
    val localProviderCapable: Boolean = false, // can it point to a local Ollama/llama-server instead of the cloud provider?
    val continueSessionCommand: String? = null // real subcommand to resume the last session (e.g. "omp -c") — null if not applicable
)
```

- **`authCommand` vs `authHint`**: when `authCommand` is `null` but `hasAuth` is `true`, the
  CLI does support authenticating (an API-key env var and/or a command inside its own
  interactive session) but there's no single-line shell subcommand to automate it — the "Sign
  in" button opens the base CLI and shows `authHint` as a toast (real case: `qwencode`, which
  requires `/auth` inside the session).
- The `{PROMPT}`/`{MODEL}` placeholders are replaced with text already escaped for a
  single-line shell before being passed to the terminal.
- `localProviderCapable` exposes the same "LOCAL AI PROVIDER" button (Ollama/llama-server) that
  exists for `qwencode`/`mimocode`/`mistralvibe` — only these 3 CLIs support local provider
  configuration, even though all 14 share the same Fragment.

## 3. Real capability table by CLI

| Module (`id`) | Real command | Login | Direct prompt | Model | Local AI provider |
|---|---|---|---|---|---|
| `freebuff` | `freebuff` | — | — | — | — |
| `codebuff` | `codebuff` | — | — | — | — |
| `copilotcli` | `copilot` | `copilot login` | `copilot -p '{PROMPT}'` | — | — |
| `minimaxcli` | `mmx` | `mmx auth login` | `mmx text chat --message '{PROMPT}'` | — | — |
| `mimocode` | `mimo` | `mimo auth login` | `mimo run '{PROMPT}'` | — | ✅ |
| `mistralvibe` | `vibe` | — (config via file/env var, no shell subcommand) | `vibe --prompt '{PROMPT}'` | — | ✅ |
| `qwencode` | `qwen` | Interactive only — `/auth` inside the session | `qwen --prompt '{PROMPT}'` | — | ✅ |
| `kimi` | `kimi` | `kimi login` (device-code flow) | `kimi -p '{PROMPT}'` | `kimi -m '{MODEL}' -p '{PROMPT}'` | — |
| `kilo` | `kilo` | `kilo auth login` | `kilo run '{PROMPT}'` | `kilo run '{PROMPT}' -m '{MODEL}'` | — |
| `cursor` | `cursor-agent` | `cursor-agent login` | `cursor-agent -p '{PROMPT}'` | `cursor-agent -p '{PROMPT}' --model '{MODEL}'` | — |
| `hf` | `hf` | `hf auth login` | — (model/dataset/space management, not a conversational agent) | — | — |
| `pi` | `pi` | — | — | — | — |
| `codegraph` | `codegraph` | — | — (not an AI agent, see note below) | — | — |
| `ohmypi` | `omp` | — (multi-provider, no confirmed shell subcommand) | `omp -p '{PROMPT}'` (+ `omp -c` continue session) | — | — |

`freebuff`: free, no account — no documented login or prompt/model flags. `codebuff`: the real
binary is downloaded on the npm launcher's first run; provider/model configuration is an
internal TUI wizard, with no confirmed shell subcommand.

`pi` (terminal coding agent): no confirmed own flags/login in the available source code, stays
at the same honest level as `freebuff`/`codebuff`.

`codegraph`: **not an AI agent** — it's a static-analysis/relationship-graph tool for the
files/functions/classes/modules of a project, for navigation and refactoring. Installed as a
precompiled ARM64 binary + Node wrapper. Exposes 5 real subcommands: `codegraph query
'<symbol>'` (symbol search), `codegraph callers`/`callees`/`impact '<symbol>'` (who calls a
symbol, who it calls, and the impact of modifying it) and `codegraph affected
'<project_path>'` (files affected by pending changes, operates on the project folder rather
than a symbol).

`ohmypi` (real command `omp`): an improved/standalone version of Pi Coding Agent — a binary
compiled against glibc, with native Rust addons (AST grep, diff, syntax highlighting, fuzzy
find, shell exec), sessions and MCP support. Supports 3 real flags: `omp -p "<prompt>"`
(one-shot), `omp -c` (continue the last session) and `omp --version`. Always installed via the
"native glibc" method (glibc-repo + glibc + a compiled helper that invokes the binary through
the glibc dynamic interpreter) — the alternative methods (glibc+proot, proot-distro) aren't
implemented since they'd add overhead with no real benefit.

## 4. Real screen (`CliToolFragment.buildContent()`)

Sections (cards), conditional on `CliModuleConfig`:

| Card | Always visible | Content |
|---|---|---|
| STATUS | ✅ | Module ID, installed version (read from the registry), base command |
| USE | ✅ | "⌨ Open in terminal" + "🗂 Manage projects" (shared symlink/copy/sync menu) |
| ACCOUNT | Only if `hasAuth` | "🔑 Sign in" — runs `authCommand` if it exists, otherwise shows `authHint` (toast) and opens the base CLI |
| DIRECT PROMPT | Only if `supportsDirectPrompt` | "💬 Send prompt" — free-text (multiline) dialog; if `hasModelSelector` is true, asks for the model afterward (optional, empty = CLI default); if `continueSessionCommand` isn't null, also adds "↻ Continue last session" |
| LOCAL AI PROVIDER | Only if `localProviderCapable` | "⬡ Use local Ollama" (lists real already-downloaded models) + "◍ Use local llama-server" (lists real `.gguf` files) — never a free-text field, always confirmed real models |
| MAINTENANCE | ✅ | "🔄 Update" + "🗑 Uninstall" (with a "deep uninstall" checkbox — also deletes the real package, not just the registry state) |

## 5. Registry

Each module writes its own prefix (`<id>.installed`, `<id>.version`, etc.) just like any other
script-based module — `CliToolFragment` doesn't introduce its own registry schema, it only
reads `<id>.version` to show it on the STATUS card.

## 6. Design notes

- **Syntax is never invented** — every `authCommand`/`promptTemplate`/`promptWithModelTemplate`
  in the table is confirmed against the real project's official documentation.
- **Shell escaping**: minimal single-quote escaping (close, add a literal escaped quote,
  reopen — the standard POSIX pattern) applied to the prompt and the model before
  interpolating them into the final command.
- If a new CLI is added to this group in the future, the correct approach is to add an entry
  to `CLI_MODULE_CONFIGS` (not create a new Fragment).

## 7. Shared installation screen

When any of these 14 modules isn't installed yet, a universal modal is shown (shared by every
module in the app, not specific to `CliToolFragment`): an "Install"/"Reinstall" button, a
"Silent installation" switch (opt-in, `false` by default — closes the window and notifies on
completion instead of blocking the UI with a progress bar), and — only if the module has
variants — a variant selector before installing. The modal can't be dismissed by gesture: only
the "Cancel" button closes it, a tap outside or the back button must not be able to close it by
accident while the installation might still be running in the background.
