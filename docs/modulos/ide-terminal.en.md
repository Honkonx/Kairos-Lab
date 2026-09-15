# Terminal IDE (nvim + NvChad)

**Kairos module** — managed via the Kairos UI (`IdeFragment.kt`). Installation and status are
handled by the app via `ProcessBuilder` → `modulos/ide.sh`. Not to be confused with **Estudio**
(Kairos's embedded graphical IDE, `app/src/main/java/com/termux/app/ui/studio/`) — this module is a
**terminal** IDE (Neovim), meant for anyone who prefers working directly in the adapted terminal
instead of Estudio's graphical UI.

---

**Script:** `modulos/ide.sh` — synced copy at `app/src/main/assets/scripts/ide.sh`.
**Registry:** prefix `ide.*`
**Resulting CLI:** `nvim`

---

## 1. Overview

Installs **Neovim + NvChad** (a Neovim configuration framework) with **GitHub Copilot** and
**CodeCompanion** already integrated into NvChad's config — a full terminal IDE, without leaving
Termux. It's opened by running `nvim` from any Kairos terminal session.

## 2. How it's installed (real steps in `ide.sh`)

1. **Neovim + dependencies** (`pkg install`): `git neovim nodejs-lts python perl curl wget
   lua-language-server ripgrep stylua tree-sitter` — the full set NvChad needs for LSP,
   formatting, and tree-sitter syntax highlighting.
2. **NvChad**: clones a Termux fork/adaptation of NvChad into `~/.local/share/kairos-nvchad`,
   copies its config to `~/.config/nvim`, and syncs plugins non-interactively: `nvim
   --headless "+Lazy! sync" +qa`, followed by `+Lazy! clean nvim-treesitter` + `+Lazy! install
   nvim-treesitter` (cleans and reinstalls tree-sitter to avoid half-downloaded parsers from a
   previous run).

Supports `--silent`/`--force`/`--describe`. "Already installed" detection: `nvim` on PATH +
`~/.config/nvim` exists.

## 3. What comes already configured

- **GitHub Copilot** and **CodeCompanion** — included in the NvChad config that gets cloned, not
  a separate installation step in `ide.sh`. They require their own login/API key inside Neovim
  (`:Copilot auth`, CodeCompanion config) — `ide.sh` doesn't automate this.
- LSP via `lua-language-server` + whatever NvChad configures per language detected in the
  project.

## 4. UI in Kairos (`IdeFragment.kt`)

`IdeFragment.kt` has its own dedicated screen with real controls:

| Control | What it does | Why |
|---|---|---|
| STATUS card | Read-only: Editor=nvim, Framework=NvChad, Integrated AI=Copilot/CodeCompanion | — |
| "📝 Open nvim here" | `launchTerminalCommand("nvim")` — opens in the current directory (home) | — |
| "📂 Open project in nvim" | `showProjectsMenu()` (same symlink/copy pattern as other modules) → `cd '$path' && nvim .` | Same `cd + CLI` mechanism already used by other terminal modules |
| "🗂 Manage projects" | `showProjectsMenu()` without launching nvim — just create/choose/delete projects | — |
| "🩺 Diagnostics (checkhealth)" | `nvim --headless -c 'checkhealth' -c 'qa!' > file 2>&1`, shows the result in a scrollable `AlertDialog` | `--headless` doesn't render any buffer — the output of `:checkhealth` (interactive by default) only reaches a redirected file, never a visible buffer |
| "🔄 Update plugins (Lazy sync)" | `nvim --headless "+Lazy! sync" +qa` (via terminal, not a dialog) | Literally the same command `modulos/ide.sh` uses during its own installation — re-running it syncs new plugins without reinstalling the whole module |
| "↑ Install / reinstall" | `reinstallModuleService()` | — |

## 5. Plugin/LSP management with Mason — not implemented

NvChad supports Mason (`:MasonInstallAll`, `:Mason`) for managing plugins/LSPs/themes
interactively. Kairos doesn't expose this as a button yet — the exact headless syntax for
invoking Mason without opening nvim's interactive UI hasn't been confirmed with sufficiently
solid evidence (unlike `checkhealth`/`Lazy! sync`, which have direct, confirmed precedent). It
remains pending evaluation as future functionality.
