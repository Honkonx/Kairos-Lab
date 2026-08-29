<p align="center">
  <img src="./art/ic_launcher2.png" width="120" alt="Kairos logo">
</p>

<h1 align="center">Kairos</h1>

<p align="center">
  <a href="./LICENSE.md"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="#minimum-requirements"><img src="https://img.shields.io/badge/platform-Android%20ARM64-3ddc84.svg" alt="Platform"></a>
  <a href="#project-status"><img src="https://img.shields.io/badge/status-early%20beta-orange.svg" alt="Status"></a>
  <a href="#third-party-credits"><img src="https://img.shields.io/badge/built%20with-Termux%20%2B%20Kotlin-informational.svg" alt="Built with"></a>
  <img src="https://img.shields.io/badge/hobby-🔥%20active-red.svg" alt="Active hobby project">
</p>

<p align="center"><strong>🚀 One APK for local AI and a full development stack on your Android phone — no root, no leaving the app.</strong></p>

*(Versión en español: [`README.md`](./README.md))*

A fork of [termux-app](https://github.com/termux/termux-app) with a complete native interface — no typing commands by hand unless you want to. Everything installs, starts, and stops by tapping the screen.

## Project status 🚧

> **⚠️ Actively developed project.** Kairos is still in testing — it can have bugs,
> inconsistent behavior across devices, and modules that work better than others. Don't
> treat it as finished or "production-ready" software. It's a personal project developed
> as a hobby, in the author's free time — there's no fixed roadmap or team behind it. If
> something breaks, [open an issue](../../issues); real reports are the most useful way
> to help at this stage.

Kairos is the successor to [**termux-ai-stack**](https://github.com/Honkonx/termux-ai-stack) —
the same local-AI + dev-tooling stack, but it started as a collection of bash scripts for
plain Termux. Kairos takes it one step further: engine + native interface in a single APK,
no memorizing commands or copying instructions from a README.

## About this project

Kairos is developed as an AI-assisted open-source project — the coding work is paired with
[Claude Code](https://claude.com/claude-code) and [OpenCode](https://opencode.ai/), used
across different sessions/machines on the same repository. Design and product decisions are
the author's; the AI assistants implement, research, and document under that direction.

## What it can do 🔥

🧠 **Local AI, no cloud.** llama.cpp comes natively compiled (NDK, with Vulkan acceleration)
right inside the APK — nothing to install separately. It's exposed as an HTTP server on port
**8085**, compatible with any client that speaks the OpenAI/llama.cpp API. Ollama is also
available as an alternative (port 11434), with its own model catalog to download from the app.

🤖 **Command-line AI agents, with their own interface.** Claude Code, Codex, OpenCode,
Antigravity, and a dozen more AI CLIs — each with login, direct prompting, and project
management from buttons, not the terminal.

⚡ **Tools without needing a full Linux container.** Several modules (coding-agent runtimes,
AI tools) come patched to run directly on top of Termux itself using its real glibc binaries —
no need to install and boot a whole Linux distro (proot-distro) just to run them. Lighter and
faster to open.

🔄 **n8n, your choice.** Workflow automation with two ways to run it: inside a full Linux
distro (proot-distro) or in a lighter rootless container (udocker) — you choose based on what
you need.

🔐 **Real remote access — the phone as a server.** SSH with its own security panel (optional
mandatory public key, just like a VPS), Mosh support so the session survives network changes,
and public exposure via tunnel (Cloudflare/ngrok) without needing a fixed IP.

🛡️ **Cybersecurity.** Network/auditing toolkit (nmap, nikto, dirb, sqlmap, theHarvester)
running natively with parsed output in the UI, plus a Pro tier with **full Kali Linux**
installed as a distro, with its real tool catalog.

🏪 **Module store.** A catalog of everything installable — search, install, and activate with
a tap. No copying install commands from a manual.

🖥️ **Full embedded Linux desktop.** Its own X11 server running inside the phone (no external
app dependency) — XFCE4/MATE with GPU acceleration, both in native mode and inside a full
distro, or a VNC viewer as an alternative if you prefer that route. It's a real Linux computer,
inside the phone.

💻 **The phone as a mini-PC.** Full Linux distros with their own graphical desktop, real
project management, servers running in the background — built so the phone can replace a
small development PC, not just for tinkering.

🧪 **Automatic test environments.** Point it at a project folder (Node, Python, PHP...) and
the app auto-detects the stack, installs what's needed, and runs it — with the option to
expose it to the internet via tunnel, without leaving the app.

🍷 **Coming: Windows on the phone.** Wine with FEXCore and DXVK is planned, to run Windows
programs directly on Android.

## Repo structure

| Folder | What's in it |
|---|---|
| [`app/`](./app/) | App source code — Termux engine (Java) + native UI (Kotlin) |
| [`modulos/`](./modulos/) | Install scripts for each module (Ollama, Claude Code, n8n, Cybersecurity, etc.) — one per module |
| [`x11-server/`](./x11-server/) | Embedded X11 server (fork of Xlorie/termux-x11) — the Linux desktop from above |
| [`llama-engine/`](./llama-engine/) | llama.cpp NDK module — the local AI engine |
| [`terminal-emulator/`](./terminal-emulator/) / [`terminal-view/`](./terminal-view/) | Terminal engine (inherited from termux-app) |
| [`tools/`](./tools/) | Build and packaging scripts for the embedded rootfs |
| [`.github/workflows/`](./.github/workflows/) | CI pipelines — build the APK on GitHub Actions |

## Minimum requirements 📋

- 📱 Android 8.0 or higher (ARM64)
- 🧠 **RAM:** 4GB acceptable minimum, 8GB+ recommended — especially on Android, where the
  system itself already uses a good chunk before Kairos starts anything
- 💾 **Storage:** 4GB free to get started; with every module installed, usage can reach
  ~30GB, plus whatever LLM models you download on top of that

## Screenshots

_Coming soon._

## Third-party credits 🙏

Kairos is a fork of [termux-app](https://github.com/termux/termux-app) — see
[`LICENSE.md`](./LICENSE.md) for the exact terms that apply to that code.

Module and feature development leans on constant analysis of external open-source projects
(never vendored, only consulted for research/patterns). Thanks to all of them — these
contributed the most:

| Project | Contribution to Kairos |
|---|---|
| [termux/termux-app](https://github.com/termux/termux-app) | Base engine — sessions, terminal, shared utilities |
| [termux/termux-x11](https://github.com/termux/termux-x11) | Embedded X11 server |
| [afeimod/linbox](https://github.com/afeimod/linbox) | Real source of the `x11-server` module (fork of Xlorie/termux-x11) — `com.termux.x11.*` tree and prebuilt `libXlorie.so` |
| [sabamdarif/termux-desktop](https://github.com/sabamdarif/termux-desktop) | Shared storage, per-distro app catalog, GPU acceleration |
| [LinuxDroidMaster/Termux-Desktops](https://github.com/LinuxDroidMaster/Termux-Desktops) | Desktop session shutdown, per-distro+DE startup |
| [DevCoreXOfficial/core-termux](https://github.com/DevCoreXOfficial/core-termux) | Real installer/uninstaller mapping for dozens of modules (languages, AI CLIs, tools) |
| [cactus-compute/needle](https://github.com/cactus-compute/needle) | Ultra-lightweight tool-calling model (`cactus` module engine) |
| [GlassHaven/Haven](https://github.com/GlassHaven/Haven) | Network-change-resilient SSH session pattern (Mosh) |
| [mithun50/openclaw-termux](https://github.com/mithun50/openclaw-termux) | Graceful shutdown (SIGTERM before SIGKILL) of background processes |
| [Gentleman-Programming/engram](https://github.com/Gentleman-Programming/engram) | Persistent memory across AI agents |
| [ivam3/i-Haklab](https://github.com/ivam3/i-Haklab) | Base for several AI-agent and cybersecurity tool modules |
| [Hope2333](https://github.com/Hope2333) | OpenCode fork for Termux/Android used as the base for Kairos's `opencode` module |

## License

GPLv3 — inherited from termux-app, see [`LICENSE.md`](./LICENSE.md). Exception: the terminal
code (based on [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)) is under Apache 2.0.
