# Shared CLI Tools — Kairos Module (generic parameterized Fragment)

**Kairos Module** — 14 AI agent CLIs/terminal tools that share a single Fragment (`CliToolFragment.kt`) instead of having a screen each, because they are homogeneous enough (command line, some with their own login/model/prompt) that 14 nearly identical Fragments are not justified.

---

**Project:** Kairos
**Fragment:** `app/src/main/java/com/termux/app/ui/CliToolFragment.kt`
**Shared config:** `CliModuleConfig` (data class) + `CLI_MODULE_CONFIGS` (map `id → CliModuleConfig`), in the same file
**Modules using this Fragment:** `freebuff`, `codebuff`, `copilotcli`, `minimaxcli`, `mimocode`, `mistralvibe`, `qwencode`, `kimi`, `kilo`, `cursor`, `hf`, `pi`, `codegraph`, `ohmypi`

---

## 1. Why a single Fragment for 14 modules

These CLIs would all fall into `GenericModuleFragment` (open in terminal + manage projects + update/uninstall, with no special treatment) — none would have buttons for login, direct prompt, model selection, or a local AI provider, even though several of these CLIs really do support those functions. `CliToolFragment` is a **superset** of `GenericModuleFragment`, not a generic replacement: modules with no confirmed extra capability (e.g. `freebuff`, `codebuff`) stay at exactly the same level as the generic fallback, on purpose — a login/prompt button is never invented for a CLI whose real command is not confirmed.

**Golden rule of the file** (documented in the code itself): every `CLI_MODULE_CONFIGS` entry is confirmed by reading `modulos/<id>.sh` + each project's real official documentation — never assumed. Do not add a new entry without confirming the CLI's real syntax.

## 2. The `CliModuleConfig` contract

```kotlin
data class CliModuleConfig(
    val baseCommand: String,               // real binary (may differ from the module id)
    val hasAuth: Boolean = false,           // does it have a login/authentication mechanism?
    val authCommand: String? = null,        // real shell subcommand (e.g. "kimi login") — null if there is no single-line one
    val authHint: String? = null,           // text shown if hasAuth=true but authCommand=null (interactive-only login)
    val supportsDirectPrompt: Boolean = false,
    val promptTemplate: String = "",        // uses the literal placeholder "{PROMPT}"
    val hasModelSelector: Boolean = false,
    val promptWithModelTemplate: String = "", // uses "{PROMPT}" and "{MODEL}"
    val localProviderCapable: Boolean = false, // can it point to local Ollama/llama-server instead of the cloud provider?
    val continueSessionCommand: String? = null // real subcommand to resume the last session (e.g. "omp -c") — null if not applicable
)
```

- **`authCommand` vs `authHint`**: when `authCommand` is `null` but `hasAuth` is `true`, the CLI DOES support authentication (API key env var and/or a `/something` command inside its own interactive session) but there is no single-line shell subcommand to automate it — the "Sign in" button opens the base CLI and shows `authHint` as a toast (real case: `qwencode`, which requires `/auth` inside the session).
- The `{PROMPT}`/`{MODEL}` placeholders are replaced by single-line shell-escaped text (single quotes, `shellEscape()`) before being passed to `launchTerminalCommand()`.
- `continueSessionCommand` exposes the real continue-session flag (e.g. Oh-My-Pi's `-c`) without creating a dedicated Fragment.
- `localProviderCapable` shows the "LOCAL AI PROVIDER" button (Ollama/llama-server). The actual local-provider configuration runs through `LocalCliProviderNative` (`configureQwenCode`/`configureMimoCode`/`configureMistralVibe`) — only these 3 CLIs support it, even though all 14 share the same Fragment.

## 3. Real capability table per CLI

| Module (`id`) | Real command | Login | Direct prompt | Model | Local AI provider |
|---|---|---|---|---|---|
| `freebuff` | `freebuff` | — | — | — | — |
| `codebuff` | `codebuff` | — | — | — | — |
| `copilotcli` | `copilot` | `copilot login` | `copilot -p '{PROMPT}'` | — | — |
| `minimaxcli` | `mmx` | `mmx auth login` | `mmx text chat --message '{PROMPT}'` | — | — |
| `mimocode` | `mimo` | `mimo auth login` | `mimo run '{PROMPT}'` | — | ✅ |
| `mistralvibe` | `vibe` | — (config via `config.toml`/`MISTRAL_API_KEY`, no shell subcommand) | `vibe --prompt '{PROMPT}'` | — | ✅ |
| `qwencode` | `qwen` | Interactive only — `/auth` inside the session (hint, no `authCommand`) | `qwen --prompt '{PROMPT}'` | — | ✅ |
| `kimi` | `kimi` | `kimi login` (device-code flow) | `kimi -p '{PROMPT}'` | `kimi -m '{MODEL}' -p '{PROMPT}'` | — |
| `kilo` | `kilo` | `kilo auth login` | `kilo run '{PROMPT}'` | `kilo run '{PROMPT}' -m '{MODEL}'` | — |
| `cursor` | `cursor-agent` (real binary from `cursor.sh`, not `cursor`) | `cursor-agent login` | `cursor-agent -p '{PROMPT}'` | `cursor-agent -p '{PROMPT}' --model '{MODEL}'` | — |
| `hf` | `hf` | `hf auth login` | — (model/dataset/space management, NOT a conversational agent) | — | — |
| `pi` | `pi` | — | — | — | — |
| `codegraph` | `codegraph` | — | — (NOT an AI agent, see note below) | — | — |
| `ohmypi` | `omp` | — (multi-provider, no confirmed shell subcommand) | `omp -p '{PROMPT}'` (+ `omp -c` continue session) | — | — |

`freebuff` (github.com/CodebuffAI/freebuff): free, no account — no documented login or prompt/model flags. `codebuff`: the real binary (`codecane`) is downloaded on the first run of the npm launcher; provider/model config is an internal TUI wizard, with no confirmed shell subcommand.

`pi` (github.com/earendil-works/pi, npm `@earendil-works/pi-coding-agent`): terminal coding agent, with no confirmed flags/login of its own in the available source code; it stays at the same level as `freebuff`/`codebuff`.

`codegraph` (github.com/colbymchenry/codegraph): **NOT an AI agent** — it is a static analysis tool / graph of relationships between the files/functions/classes/modules of a project, for navigation and refactoring. It is installed as a precompiled ARM64 binary from GitHub Releases + a Node wrapper (`exec node .../codegraph.js "$@"`). Its 5 documented subcommands beyond `init`/`status` are exposed as buttons in `CliToolFragment.kt`:
- `codegraph query '<symbol>'` — symbol search (`symbolQueryTemplate`).
- `codegraph callers '<symbol>'` / `codegraph callees '<symbol>'` / `codegraph impact '<symbol>'` — who calls a symbol, whom it calls, and the impact of modifying it. The contract includes `symbolQueryVariants: List<Pair<String, String>>` (label, template with `{SYMBOL}`), reusing the same dialog as `query` — the same pattern as `promptVariants`. `runSymbolQueryButton()` accepts a label per call.
- `codegraph affected '<project_path>'` — files affected by pending changes; it takes no symbol, it operates on the project folder. It reuses `projectActionTemplate`/`projectActionLabel` (the same field Oh-My-Pi uses for "omp commit").

`ohmypi` (github.com/can1357/oh-my-pi, real command `omp`): an improved/standalone version of Pi Coding Agent — a binary compiled with `bun build --compile` against glibc, with native Rust addons (AST grep, diff, syntax highlighting, fuzzy find, shell exec), sessions and MCP support. The official README documents 3 real flags: `omp -p "<prompt>"` (one-shot), `omp -c` (continue the last session) and `omp --version`. `modulos/ohmypi.sh` ALWAYS installs through the "native glibc" method (glibc-repo + glibc + a clang-compiled helper that invokes the binary through `ld-linux-aarch64.so.1`) — the alternative methods (glibc+proot, proot-distro/Ubuntu) are not implemented because they add overhead with no real need. The README declares Pi as a dependency, but `modulos/ohmypi.sh` does not force that cascading install — the relationship is documented (`requires: ["pi"]` in `modules.json`) without installing anything not explicitly requested.

## 4. Real screen (`CliToolFragment.buildContent()`)

Sections (cards), conditional on `CliModuleConfig`:

| Card | Always visible | Content |
|---|---|---|
| STATUS | ✅ | Module ID, installed version (read from the registry, `$moduleId.version`), base command |
| USE | ✅ | "⌨ Open in terminal" (`launchTerminalCommand(baseCommand)`) + "🗂 Manage projects" (shared symlink/copy/sync menu, with `onLaunchInProject` doing `cd '<path>' && <baseCommand>`) |
| ACCOUNT | Only if `hasAuth` | "🔑 Sign in" — runs `authCommand` if it exists, otherwise shows `authHint` (toast) and opens the base CLI |
| DIRECT PROMPT | Only if `supportsDirectPrompt` | "💬 Send prompt" — free-text dialog (multiline); if `hasModelSelector` is true, it asks for the model afterward (`provider/model`, optional, empty = CLI default); if `continueSessionCommand` is not null, it also adds "↻ Continue last session" (e.g. `ohmypi` → `omp -c`) |
| LOCAL AI PROVIDER | Only if `localProviderCapable` | "⬡ Use local Ollama" (lists models already downloaded via `OllamaApiClient.listModels()`) + "◍ Use local llama-server" (lists `.gguf` via `LocalModelManager.listModels()`) — never a free-text field, always real models |
| MAINTENANCE | ✅ | "🔄 Update" (`updateModuleService()`) + "🗑 Uninstall" (with a "deep uninstall" checkbox — also deletes the real package, not just the registry state) |

## 5. Registry

Each module writes its own prefix (`<id>.installed`, `<id>.version`, etc.) like any other `modulos/*.sh` module — `CliToolFragment` introduces no registry scheme of its own, it only reads `$moduleId.version` to show it in the STATUS card.

## 6. Design notes

- **Syntax is never invented** — every `authCommand`/`promptTemplate`/`promptWithModelTemplate` in the table is confirmed against the real project's official documentation.
- **Shell escaping**: `shellEscape()` does the minimal single-quote escaping (close, add a literal escaped quote, reopen — the standard POSIX pattern) on the prompt and model before interpolating them into the final command.
- To add a new CLI to this group, the correct way is to add an entry to `CLI_MODULE_CONFIGS` (not to create a new Fragment) and update the table in section 3.

## 7. Shared installation screen

When any of these 14 modules is not yet installed, `buildContent()` draws none of the above — `BottomSheetInstalacion` is shown (`BottomSheetInstalacion.kt`, `app/src/main/java/com/termux/app/ui/`), universal to ALL modules in the app (it is not specific to `CliToolFragment`). Controls: "Install"/"Reinstall" button (triggers `ModuleController.installModule()`), a "Silent installation" switch (opt-in, `false` by default: it closes the window and notifies on completion instead of blocking the UI with a progress bar), and — only if the module has variants (`hasVariants`) — a variant selector before installing. `isCancelable = false` on purpose: only the "Cancel" button closes the screen; tapping outside or the back button must not close it by accident while the installation may keep running in the background.

**Out of scope**: `verificar` (a module with its own Fragment, `VerificarFragment.kt`, which does not use `CliToolFragment`).
