# MiniMax CLI — Kairos Module

> Read from `modulos/minimaxcli.sh` (183 lines), `app/src/main/assets/modules.json`,
> `CliToolFragment.kt` (`"minimaxcli"` config, lines 184-196), `ModuleController.kt:917`.
> Confirmed = seen in code; Unverified = inference without a device.

## 1) What it is and what it is for
MiniMax multimodal CLI (`mmx`, npm package `mmx-cli`): text chat, image, speech synthesis and quota query.

## 2) Classification: **ADD-ON/EXTENSION**
No Fragment of its own: `ModuleDetailNavigator.kt:54` → parameterized `CliToolFragment`. No state of its own or service. It installs the tool, opens a terminal and sends one-line `mmx` commands. **~10 UI controls** (the busiest of the small CLIs in this group, but they are all terminal launchers).

## 3) Installation (`modulos/minimaxcli.sh`)
- Flags (`:43-46`): `--silent`, `--force`, `--describe`, `--describe-files`. No variants.
- Dependency: `nodejs-lts` (STEP 1 `:124`). `npm install -g mmx-cli@latest` (`MINIMAX_PKG` `:86`, STEP 2 `:146`) → `fix_npm_shebang_wrapper mmx mmx-cli` → `verify_binary_installed mmx` (`mmx --version`, `error` if it does not run, `:153`).
- **No sha256**; integrity only through npm.
- Registry: `minimaxcli.installed/version/channel=npm/install_date` (`:174-178`). "Already installed" → `exit 0` without registry. STEP 3 (API key) is only a notice: configure with `mmx`'s wizard (`:162-167`).

## 4) Execution
`terminalCommand:"mmx"`. `needsGlibcCompat=false` (pure npm shim, needs `LD_PRELOAD` active). No background process.

## 5) UI controls
Status · Open in terminal (where to open?) · Manage projects · **Log in** (`mmx auth login`) · **Send prompt** (`mmx text chat --message '<prompt>'`) · **Generate image** (`mmx image '<prompt>'`) · **Synthesize speech** (`mmx speech synthesize --text '<text>' --out ~/minimax_speech.mp3`) · **Quota** (`mmx quota`) · Update · Diagnose · Uninstall (`npm uninstall -g mmx-cli`, `ModuleController.kt:917`).

## 6) Files and paths
Global npm package `mmx-cli` under `$(npm root -g)`, wrapper `$PREFIX/bin/mmx`, speech output `~/minimax_speech.mp3` (fixed, overwritten each time), checkpoint `~/.install_minimaxcli_checkpoint`. Key/credentials: stored by the CLI (location not verified).

## 7) Security
- Prompt text escaped with `shellEscape` before interpolation.
- The API key is handled by `mmx` (interactive login); Kairos neither stores nor shows it (no code reads it).
- npm installation without a pin or hash.

## 8) Problems / gaps
- Speech always writes to the fixed `~/minimax_speech.mp3` (no path choice nor playback from the UI); the image is generated in the terminal's cwd with no controlled path.
- The subcommands `mmx image`, `mmx speech synthesize --out`, `mmx quota` come from the config comment (checked against github.com/MiniMax-AI/cli); they were not executed and the module has no on-device verification (their current validity is unverified).
- Minimal `modules.json` metadata (static `size ~50MB`, `catalogVersion:"0.x"`).
- Outputs (text/image/speech) are seen only in the terminal; there is no native viewer/player in the UI.

## 9) Added
`modulos/minimaxcli.sh`: **2026-08-12**.
