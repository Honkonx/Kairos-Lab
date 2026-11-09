# Terminal IDE (nvim + NvChad)

**Kairos module** — managed through the Kairos UI (`IdeFragment.kt`). Installation and state
are handled by the app via `ProcessBuilder` → `modulos/ide.sh`. Not to be confused with **Estudio** (Kairos's
embedded graphical IDE, `app/src/main/java/com/termux/app/ui/studio/`) — this module is a
**terminal** IDE (Neovim), meant for people who prefer to work directly in the adapted terminal
rather than in Estudio's graphical UI.

---

**Script:** `modulos/ide.sh` (v1.0.0) — synchronized copy in `app/src/main/assets/scripts/ide.sh`.
**Registry:** prefix `ide.*`
**Resulting CLI:** `nvim`

---

## 1. Overview

Installs **Neovim + NvChad** (a Neovim configuration framework) with **GitHub Copilot** and
**CodeCompanion** already integrated in the config that NvChad ships — a complete terminal IDE, without
leaving Termux. It is opened by running `nvim` from any Kairos terminal session.

## 2. How it is installed (actual steps of `ide.sh`)

1. **Neovim + dependencies** (`pkg install`): `git neovim nodejs-lts python perl curl wget
   lua-language-server ripgrep stylua tree-sitter` — the full set NvChad needs for
   LSP, formatting and syntax highlighting via tree-sitter.
2. **NvChad**: clones `https://github.com/DevCoreXOfficial/nvchad-termux.git` (a fork/adaptation of
   NvChad for Termux) into `~/.local/share/kairos-nvchad`, copies its config to `~/.config/nvim`, and
   syncs plugins non-interactively: `nvim --headless "+Lazy! sync" +qa`, followed by
   `+Lazy! clean nvim-treesitter` + `+Lazy! install nvim-treesitter` (cleans and reinstalls
   tree-sitter to avoid half-downloaded parsers from a previous run). It also requires that
   `~/.local/share/nvim/lazy` not be empty (real post-condition, `ide.sh:185-188`).

Supports `--silent`/`--force`/`--describe`. "Already installed" detection: `nvim` on PATH +
`~/.config/nvim` exists (`ide.sh:121`; exits with code 0 without touching the registry). Registry `ide.*`
through `registry_write` (`editor=nvim`, `framework=NvChad`). No sha256: it clones HEAD without a
commit/tag pin. `requires: ["git"]` in the catalog even though the script installs git on its own.

## 3. What comes preconfigured

- **GitHub Copilot** and **CodeCompanion** — included in the NvChad config that gets
  cloned, not a separate installation step of `ide.sh`. They require their own login/API key
  inside Neovim (`:Copilot auth`, CodeCompanion config) — `ide.sh` does not automate it.
- LSP via `lua-language-server` + whatever NvChad configures per language detected in the project.

## 4. Execution

No service, port or tmux. `nvim` opens in the Kairos terminal via `launchTerminalCommand`.
`terminalCommand: "nvim"` in `modules.json`. Classification: add-on/extension with its own screen
(`IdeFragment`, routed from `ModuleDetailNavigator.kt:45`); ~7 UI options.

## 5. UI in Kairos (`IdeFragment.kt`)

`IdeFragment.kt` has its own dedicated screen (not `GenericModuleFragment`) with 6 buttons and
3 static informational rows:

| Control | What it does | Why |
|---|---|---|
| STATUS card | Read-only: Editor=nvim, Framework=NvChad, Built-in AI=Copilot/CodeCompanion (fixed text, does not read real state or the nvim version) | — |
| "📝 Open nvim here" | `promptOpenLocation`: `nvim` in the current directory (home) or `cd <folder> && nvim .` | Resulting CLI: `nvim` |
| "📂 Open project in nvim" | `showProjectsMenu()` (same symlink/copy pattern as other modules) → `cd '$path' && nvim .` | Same `cd + CLI` mechanism already used by other terminal modules |
| "🗂 Manage projects" | `showProjectsMenu()` without launching nvim — only create/pick/delete projects | — |
| "🩺 Diagnostics (checkhealth)" | `nvim --headless -c 'checkhealth' -c 'qa!' > file 2>&1`, shows the result in a scrollable `AlertDialog` (`IdeFragment.kt:109-149`) | `--headless` draws no buffer — the output of `:checkhealth` (interactive by default) only arrives redirected to a file, never in a visible buffer |
| "🔄 Update plugins (Lazy sync)" | `nvim --headless "+Lazy! sync" +qa` (via a terminal session, not a dialog) | Literal command identical to the one `modulos/ide.sh` uses in its own installation — re-running it syncs new plugins without reinstalling the whole module |
| "↑ Install / reinstall" | `reinstallModuleService()` | — |

## 6. Files and paths

`~/.config/nvim/**`, `~/.local/share/kairos-nvchad` (clone), `~/.local/share/nvim/lazy` (plugins),
`$PREFIX/tmp/kairos_ide_checkhealth.txt`, checkpoint `~/.install_ide_checkpoint`, registry `ide.*`.

## 7. Security

Supply chain: Neovim runs Lua from a third-party repo without a version pin. Copilot requires its
own authentication inside nvim (not managed by Kairos). No secrets in the script. No
`curl | bash`.

## 8. Plugin/LSP management with Mason — not implemented

NvChad supports Mason (`:MasonInstallAll`, `:Mason`) to manage plugins/LSPs/themes interactively.
Kairos does not expose this as a button yet — the exact headless syntax to invoke Mason without
opening nvim's interactive UI is not confirmed with sufficiently solid evidence (unlike
`checkhealth`/`Lazy! sync`, which have direct, confirmed precedent). It remains as functionality
pending evaluation.

## 9. Known limitations

- `cp -r "$NVCHAD_DIR/nvim" "$HOME/.config/nvim"` (`ide.sh:166`) does not delete an existing destination;
  with `--force` or a previous partial installation, `cp -r` nests the content as
  `~/.config/nvim/nvim`. Not verified on a device.
- The "already installed" exit (`ide.sh:121-124`) neither repairs nor writes the registry.
- The `checkhealth` diagnostic runs without a timeout (`process.waitFor()`, line 118) and can hang
  the thread if nvim blocks.
- "Sync plugins" is fire-and-forget in the terminal, with no success confirmation.

## 10. Added

`modulos/ide.sh` added on 2026-08-13.
