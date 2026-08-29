# Kairos modules catalog

Kairos organizes every service, CLI, or tool it can install as a **module**: an independent bash
script (silent, no prompts, with failure-recovery checkpoints) that the app invokes via
`ProcessBuilder`, plus an entry in the modules catalog describing its port, estimated size,
required architecture, and whether it needs proot. All modules run inside Termux — natively
against Android's libc (Bionic) when possible, or on top of a glibc/proot compatibility layer
when the project's real binary requires it — without needing to root the device.

This catalog lists the real modules grouped by category, what each one installs, and a brief
description. For full architecture details, configuration options, and screen controls for a
given module, see its dedicated document when one exists (`HERMES.md`, `OPENCLAW.md`,
`OPENCODE.md`, `UDOCKER.md` in this same folder).

## Local AI and agents with their own interface

| Module | Installs | Description |
|---|---|---|
| **ollama** | Ollama (GPU variant via npm with Vulkan support, or a CPU-only standard variant via a `pkg` package) + control scripts | Local LLM inference server with an HTTP API. It's the local AI backend most other AI-capable modules (Hermes, OpenCode, Cactus, etc.) reuse. |
| **llamaserver** | The `llama-server` binary (an OpenAI-API-compatible HTTP server, part of llama.cpp) + control scripts | An alternative local inference backend to Ollama, built directly on llama.cpp. Reuses the `.gguf` models already downloaded elsewhere in the app instead of managing its own model catalog. |
| **hermes** | Hermes Agent (Nous Research) — Python inside a dedicated virtual environment, no proot | Open-source AI agent framework with an interactive TUI and a multi-channel messaging gateway (Telegram, Discord, Slack, SMS, Signal) that runs 100% natively on Termux. |
| **openclaw** | OpenClaw — npm package on top of a glibc compatibility layer, no proot | AI agent gateway with its own web UI and TUI, fixed port `18789`, compatible with local (Ollama) and cloud providers. |
| **opencode** | OpenCode — official binary on top of Termux's glibc, no proot | AI-powered code editor (TUI + web server on `:3000`, with an optional second instance on `:4096`), with local Ollama support. |
| **cactus** | Cactus Needle — pip package (an embedded ~45M-parameter engine) | Lightweight local tool-calling engine — can execute actions (bash, Python, JSON) directly, or reason first via a local LLM (Ollama/llama-server) before acting. |
| **n8n** | n8n, in a Debian distro via proot or from an official image via udocker (user's choice) + cloudflared to expose it through a tunnel | Workflow automation platform with a visual editor, port `5678`. |
| **engram** | Engram (built from source in Go) | Persistent memory system for AI agents — saves context across sessions in a local SQLite database, with no external service dependency, so Claude Code, OpenCode, and other agents can use it as shared memory. |

## AI coding CLIs (shared interface)

The following modules are terminal-based AI coding agent CLIs — most have no web UI of their own
and share the same kind of screen in the app (launch in terminal, project management, continue
last session where the CLI supports it).

| Module | Installs | Description |
|---|---|---|
| **claude** | Claude Code — native (ELF/glibc) binary | Anthropic's coding CLI. |
| **codex** | OpenAI Codex CLI — via npm | OpenAI's coding CLI. |
| **antigravity** | Antigravity CLI (`agy`) — native binary | Coding CLI with Google account authentication. |
| **copilotcli** | GitHub Copilot CLI — via npm (`@github/copilot`) | GitHub's coding CLI, requires an account with Copilot active. |
| **cursor** | Cursor CLI (`cursor-agent`) — official installer on top of a glibc compatibility layer | The Cursor editor's coding CLI. |
| **kilo** | Kilo Code CLI — native ARM64 binary patched to run on Termux's glibc | Kilo Code's coding CLI. |
| **kimi** | Kimi Code (`@moonshot-ai/kimi-code`) — via npm | Moonshot AI's coding CLI. |
| **minimaxcli** | MiniMax CLI (`mmx-cli`) — via npm | MiniMax's coding CLI. |
| **mistralvibe** | Mistral Vibe — via pip | Mistral's coding CLI, Python-based. |
| **qwencode** | Qwen Code (`@qwen-code/qwen-code`) — via npm | Alibaba's coding CLI, a fork of Gemini CLI. |
| **pi** | Pi Coding Agent (`@earendil-works/pi-coding-agent`) — via npm | A terminal-based coding CLI. |
| **ohmypi** | Oh-My-Pi (`omp`) — binary built against glibc, with native Rust addons | An enhanced, standalone version of Pi Coding Agent — session management, MCP support, code analysis tools (AST grep, diff, syntax highlighting, fuzzy find). |
| **codebuff** / **freebuff** | Codebuff / Freebuff (an open-source Codebuff fork) — native binary, with a cascade of installation methods depending on architecture | Coding CLIs from CodebuffAI; Freebuff is the variant that prefers a native Bun runtime for Bionic as its main install method. |
| **mimocode** | MiMo Code (Xiaomi) — pure native Bionic binary (no glibc dependency) | Xiaomi's coding CLI. |
| **codegraph** | CodeGraph — precompiled Node.js binary | **Not an AI agent** — a static analysis tool that generates a relationship graph across a project's files/functions/classes, for navigation and refactoring. |
| **hf** | Hugging Face CLI (`hf`) — official installer | Manage Hugging Face models, datasets, and spaces from the terminal, including downloading GGUF files with resume support for interrupted downloads. |

## Remote access and networking

| Module | Installs | Description |
|---|---|---|
| **ssh** (Remote) | OpenSSH (port `8022`) + native cloudflared (tunnel) + `mosh-server` (best-effort) | Remote SSH access to the device, exposed through a Cloudflare tunnel without needing a public IP. Mosh, when available, provides sessions resilient to network changes. |
| **db** | MariaDB (MySQL), PostgreSQL, SQLite, and Redis, each with its own start/stop scripts | Local database engines for development. |
| **docker** | Nothing — an informational module | Explains why a real Docker daemon can't run without rooting the device (Android doesn't expose namespaces/cgroups to non-root apps) and points to the `udocker` module as the real alternative already integrated. |
| **udocker** | udocker + `udockertools`, execution mode `P2` forced, container management wrappers | A userspace container runtime (no root, via PRoot) that can pull and run real Docker Hub images. See `UDOCKER.md` for the full details. |
| **qemu** | `qemu-user-*` packages (emulating binaries from another architecture) and `qemu-system-*-headless` (headless virtual machines, no KVM acceleration) | CPU/binary emulation. User mode (running an x86_64 binary on an ARM64 phone, for example) is the strongest root-free use case; system mode runs in pure software (no KVM), useful for testing a lightweight image, not for a smooth desktop experience. |

## Desktop environment and development

| Module | Installs | Description |
|---|---|---|
| **entorno** | proot-distro + udocker, PulseAudio, GPU drivers matched to the hardware, desktop tools (XFCE, VNC), and base AI tooling on the host | The "mini PC" module — sets up full Linux distros with a graphical desktop on the device, with an X11 server embedded in the app itself (no dependency on an external app). |
| **stacks** | Installs nothing of its own — a catalog of recipes that reuses other modules (Python, DB) or installs individual packages (Node.js, PHP) depending on the chosen preset | Predefined development environments (Python+PostgreSQL, PHP+MySQL, React+Vite, a static site, or a full Linux distro), installable natively or inside an already-existing proot distro. |
| **ide** | Neovim + NvChad (config framework) + Copilot/CodeCompanion integration | A full code editor directly in Kairos's terminal, no graphical editor needed. |
| **apk** | Android APK build chain (aapt2, javac, d8, zipalign, apksigner) | Compiles Android apps directly on the device, without Android Studio. |

## Security and verification

| Module | Installs | Description |
|---|---|---|
| **ciberseguridad** | Basic tier: nmap, netcat, dirb, nikto, theHarvester, sqlmap (native, no proot). Pro tier: additionally, a full Kali Linux distro via proot-distro | A network/OSINT security-testing toolkit, in two tiers depending on how much space and capability the user wants to dedicate. |
| **verificar** | Nothing — a diagnostic tool | Live-checks, against the real filesystem, that modules the internal registry marks as installed are actually still working (catches installs broken by a manual cleanup or a failed update). |
| **repo** | The structure of a local apt repository on the device | Lets you package what a module already installed as a real `.deb`, installable afterward with `pkg install` from any Termux session. |

## Languages and command-line tools

These lightweight modules are presented grouped under two container screens in the app
("Languages" and "Packages"), each item with its own install toggle:

| Module | Installs |
|---|---|
| **nodejs** | Node.js LTS (with Corepack enabled for pnpm/yarn) |
| **perl** | Perl |
| **php** | PHP CLI |
| **rust** | Rust (rustc + cargo) |
| **clang** | Clang (C/C++ compiler) |
| **golang** | Go |
| **kotlin** | Kotlin (`kotlinc`) |
| **typescript** | TypeScript |
| **nestjs** | NestJS CLI |
| **prettier** | Prettier |
| **livesrv** | Live Server |
| **localtunnel** | Localtunnel |
| **vercel** | Vercel CLI |
| **markserv** | Markserv |
| **psqlformat** | PSQL Format |
| **ncu** | npm-check-updates |
| **ngrok** | ngrok |

## Product tooling (Expo/EAS)

| Module | Installs | Description |
|---|---|---|
| **expo** | EAS CLI (Expo Application Services) via npm | Building, OTA updates, and publishing React Native/Expo apps directly from the device. |

---

*Kairos is a native Android app (a fork of termux-app with a Kotlin/Java UI) that unifies these
modules under a single graphical interface, designed so that most tasks for each service have a
real button/dialog path — the built-in terminal is still available for power users, but it's not
the only way in.*
