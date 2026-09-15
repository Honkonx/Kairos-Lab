<p align="center">
  <img src="./art/ic_launcher2.png" width="120" alt="Kairos logo">
</p>

<h1 align="center">Kairos</h1>

<p align="center">
  <a href="./LICENSE.md"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="#minimum-requirements"><img src="https://img.shields.io/badge/platform-Android%20ARM64-3ddc84.svg" alt="Platform"></a>
  <a href="#project-status"><img src="https://img.shields.io/badge/status-early%20beta-orange.svg" alt="Status"></a>
  <a href="#credits-and-third-party-acknowledgments"><img src="https://img.shields.io/badge/built%20with-Termux%20%2B%20Kotlin-informational.svg" alt="Built with"></a>
  <img src="https://img.shields.io/badge/hobby-🔥%20active-red.svg" alt="Active hobby project">
</p>

<p align="center"><strong>🚀 One APK for local AI and a full dev stack on your Android phone — no root, no leaving the app.</strong></p>

*[Léelo en español](./README.md)*

A fork of [termux-app](https://github.com/termux/termux-app) with a complete native interface — no need to type commands by hand unless you want to. Everything installs, starts, and stops with a tap.

## Project status 🚧

> **⚠️ Active development.** Kairos is still in a testing phase — it can have bugs,
> inconsistent behavior across devices, and modules that work better than others. Don't treat
> it as finished, production-ready software. It's a personal project developed as a hobby, in
> the author's spare time — there's no fixed roadmap or team behind it. If something breaks,
> [open an issue](../../issues); real bug reports are the most useful way to help at this
> stage.

Kairos is the successor to [**termux-ai-stack**](https://github.com/Honkonx/termux-ai-stack) —
the same local AI stack + dev tools, but that project started as a collection of bash scripts
for plain Termux. Kairos takes it a step further: engine + native interface in a single APK,
with no commands to memorize or instructions to copy from a README.

## About this project

Kairos is developed as an AI-assisted open source project — the programming work is done in
pair-programming with [Claude Code](https://claude.com/claude-code) and
[OpenCode](https://opencode.ai/), used across different sessions/machines on the same
repository. Design and product decisions belong to the author; the AI assistants implement,
research, and document under that direction.

## What it can do 🔥

🧠 **Local AI, no cloud.** llama.cpp ships natively compiled (NDK, with Vulkan acceleration)
inside the APK itself — nothing else to install. It's exposed as an HTTP server on port
**8085**, compatible with any client that speaks the OpenAI/llama.cpp API. Ollama is also
available as an alternative (port 11434), with its own model catalog to download from the app.

🤖 **Command-line AI agents, with a real interface.** Claude Code, Codex, OpenCode,
Antigravity, and a dozen more AI CLIs — each with login, a direct prompt, and project
management from buttons, not the terminal.

⚡ **Tools that don't need a full Linux container.** Several modules (agent CLIs, AI tools)
ship patched to run directly on top of Termux itself using their real glibc binaries — no need
to install and boot an entire Linux distro (proot-distro) just to run them. Lighter and faster
to open.

🔄 **n8n, your choice.** Workflow automation with two ways to run it: inside a full Linux
distro (proot-distro) or in a lighter rootless container (udocker) — you pick based on what
you need.

🔐 **Real remote access — your phone as a server.** SSH with its own security panel (optional
mandatory public-key auth, just like a VPS), Mosh support so the session survives network
changes, and public exposure via tunnel (Cloudflare/ngrok) without needing a fixed IP.

🛡️ **Cybersecurity.** A network and audit toolkit (nmap, nikto, dirb, sqlmap, theHarvester)
running natively with parsed output in the interface, plus a Pro tier with **a full Kali
Linux** installed as a distro, with its real tool catalog.

🏪 **Module store.** A catalog of everything installable — search, install, and activate with
a tap. No copying install commands from a manual. Any installed module (or any system `apt`
package) can be repackaged into a local `.deb` repository, so you can reinstall it on another
device without downloading or patching anything from scratch again.

🖥️ **A full embedded Linux desktop.** Its own X11 server running inside the phone (no
dependency on any external app) — XFCE4/MATE with GPU acceleration, both in native mode and
inside a full distro. It also includes its own VNC client (RFB protocol, no external apps) as
an alternative connection method. It's a real Linux computer, inside your phone.

💻 **The phone as a mini-PC.** Full Linux distros with their own graphical desktop, real
project management, servers running in the background — built so the phone can replace a
small PC for development, not just for trying things out.

🧪 **Automatic test environments.** Point it at a project folder (Node, Python, PHP...) and
the app detects the stack on its own, installs what's needed, and runs it — with the option
to expose it to the internet via tunnel, without leaving the app. It also detects monorepos
(several sub-projects inside the same folder, like `frontend/`+`backend/`) and manages them
separately, with a button to start them all together.

🏠 **Homelab.** A panel that brings together what Kairos itself already exposes (SSH, modules
with a service running) with a list of your other self-hosted services on the local network
(Docker/Portainer, Pi-hole, Proxmox, or any generic HTTP panel) — all in one place, without
opening a different app for each service.

🤖 **Telegram bot.** Besides sending notifications, it now also receives commands: start or
stop a module, or check what's running, straight from a Telegram chat — with a single-user
allowlist and two-step confirmation for risky actions.

🔍 **Web search without an API key, in the chat.** Besides Ollama's search (which requires a
free key), the local chat has a `/buscar` command that needs no account or key to pull real
results from the web.

🍷 **Coming: Windows on your phone.** Plans to add Wine with FEXCore and DXVK to run Windows
programs directly on Android.

## Repo structure

| Folder | What's in it |
|---|---|
| [`app/`](./app/) | App source code — Termux engine (Java) + native UI (Kotlin) |
| [`modulos/`](./modulos/) | Install scripts for each module (Ollama, Claude Code, n8n, Cybersecurity, etc.) — one per module, no prefix |
| [`x11-server/`](./x11-server/) | Embedded X11 server (fork of Xlorie/termux-x11) — the Linux desktop from above |
| [`llama-engine/`](./llama-engine/) | llama.cpp NDK module — the local AI engine |
| [`terminal-emulator/`](./terminal-emulator/) / [`terminal-view/`](./terminal-view/) | Terminal engine (inherited from termux-app) |
| [`tools/`](./tools/) | Build and packaging scripts for the embedded rootfs |
| [`.github/workflows/`](./.github/workflows/) | CI pipelines — build the APK on GitHub Actions |

## Minimum requirements 📋

- 📱 Android 8.0 or higher (ARM64)
- 🧠 **RAM:** 4GB as a bare minimum, 8GB or more recommended — especially on Android, where the
  OS itself already uses a significant chunk before Kairos does anything
- 💾 **Storage:** 4GB free to get started; with every module installed usage can reach ~30GB,
  plus whatever LLM models you download on top of that

## Screenshots

_Coming soon._

## Credits and third-party acknowledgments 🙏

Kairos is a fork of [termux-app](https://github.com/termux/termux-app) — see
[`LICENSE.md`](./LICENSE.md) for the exact terms that apply to that code.

Module and feature development leans on constant analysis of external open source projects
(never vendored, only consulted for research/patterns). Thanks to all of them — these
contributed the most:

| Project | Contribution to Kairos |
|---|---|
| [termux/termux-app](https://github.com/termux/termux-app) | Base engine — sessions, terminal, shared utilities |
| [termux/termux-x11](https://github.com/termux/termux-x11) | Embedded X11 server |
| [afeimod/linbox](https://github.com/afeimod/linbox) | Real source of the `x11-server` module (fork of Xlorie/termux-x11) — `com.termux.x11.*` tree and prebuilt `libXlorie.so` |
| [sabamdarif/termux-desktop](https://github.com/sabamdarif/termux-desktop) | Shared storage, per-distro app catalog, GPU acceleration |
| [LinuxDroidMaster/Termux-Desktops](https://github.com/LinuxDroidMaster/Termux-Desktops) | Desktop session teardown, per-distro+DE startup |
| [DevCoreXOfficial/core-termux](https://github.com/DevCoreXOfficial/core-termux) | Real install/uninstall mapping for dozens of modules (languages, AI CLIs, tools) |
| [cactus-compute/needle](https://github.com/cactus-compute/needle) | Ultra-lightweight tool-calling model (`cactus` engine) |
| [GlassHaven/Haven](https://github.com/GlassHaven/Haven) | Network-change-resilient SSH session pattern (Mosh) |
| [mithun50/openclaw-termux](https://github.com/mithun50/openclaw-termux) | Graceful shutdown (SIGTERM before SIGKILL) of background processes |
| [Gentleman-Programming/engram](https://github.com/Gentleman-Programming/engram) | Persistent memory across AI agents |
| [ivam3/i-Haklab](https://github.com/ivam3/i-Haklab) | Base for several AI agent and cybersecurity modules |
| [Hope2333](https://github.com/Hope2333) | OpenCode fork for Termux/Android, used as the base for Kairos's `opencode` module |

## License

GPLv3 — inherited from termux-app, see [`LICENSE.md`](./LICENSE.md). Exception: the terminal
code (based on [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)) is under Apache 2.0.
