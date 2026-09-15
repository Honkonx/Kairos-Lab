# Module architecture

Kairos modules are classified by how they actually run on the device: a direct ARM64 native
binary, a full Linux distro via proot, a package manager package (npm/pip), or an installation
method that falls outside those categories.

## Native Bionic, direct (ARM64 without proot)

These run as plain ARM64 binaries against the environment's libc (Bionic) — no proot, no
syscall translation. Includes native compilers/runtimes and system tools:

| Module | What it is | Real installation |
|---|---|---|
| Python | Python 3.13 + pip + SQLite | native package |
| Ollama | Local LLM engine | native binary (two possible installation paths) |
| Codex | Codex CLI (OpenAI) | native channel or binary, both Bionic |
| Engram | Persistent memory for agents | compiled from Go source — the resulting binary is native, though the acquisition method is compilation, not a package |
| Remote / SSH | SSH access | OpenSSH via the package manager |
| udocker | Rootless containers via PRoot | native package |
| QEMU | qemu-user + headless qemu-system (no KVM) | native package |
| DB | MySQL/MariaDB + PostgreSQL + SQLite + Redis | native packages, 4 servers |
| Stacks | Catalog of development stacks (Python+PG, PHP+MySQL, React+Vite, HTML/CSS/JS) | mix of native package plus proot-distro for the full Linux distro preset |
| IDE (Neovim) | Neovim + NvChad + Copilot + CodeCompanion | package + plugins |
| APK | On-device APK compiler | native tools (aapt2/javac/d8/zipalign/apksigner) |
| Verify | Live verification of installed modules | in-house script |
| Repo | Package builder + local repository | in-house script |
| llama.cpp server | Local inference HTTP server (Local AI) | compiled in-house, project NDK module |
| Cybersecurity | Basic network/audit tools + optional full Kali distro | native packages at the basic tier, proot-distro only at the advanced tier |

One more module in this group, Docker, doesn't install anything: it explains why a real Docker
runtime isn't possible without root access on the device, and points to udocker (rootless
containers via PRoot, in the same table) as the real alternative available.

**Native language tools** (internal dependencies of other modules): Node.js, Perl, PHP, Rust,
Clang, Go — all precompiled native packages for ARM64.

## Requires proot-distro / glibc (full Linux distro)

These don't run against Bionic — they need a full glibc distro inside a proot environment:

| Module | What it is | Note |
|---|---|---|
| Claude Code | Anthropic's CLI | requires a glibc environment |
| Antigravity | Google's CLI | glibc binary |
| OpenClaw | Claude-API-compatible AI gateway | glibc |
| OpenCode | AI code editor via web interface | glibc |
| Cursor | Cursor's CLI | official binary with embedded Node, glibc layer |
| n8n | Workflow automation | dual: rootless container (doesn't require a full proot-distro) or classic proot-distro, user's choice |
| Entorno (Desktop) | Native XFCE4 desktop or full Linux distro | XFCE4 runs natively (Bionic) on top of the embedded X11 server; the full-distro option uses proot-distro |

## npm/node CLIs (package installed via npm on top of native Node.js)

Node.js itself is native Bionic, but the module's package comes from the npm registry:

- **AI coding agents**: several code-assistance CLIs installed as global npm packages.
- **Development tools** (the "Packages" family): TypeScript, NestJS, Prettier, dev servers,
  local tunnels, deploy tools, SQL formatting, dependency updates, ngrok.

## Python / pip

- Lightweight local tool-calling engine (a small model, ~45M parameters).
- Code agent installed via pip.
- Part of the Cybersecurity module uses Python tools installed via pip.

## Other (compiled from source, precompiled binary, or hybrid)

| Module | Real method | Why it doesn't fit the categories above |
|---|---|---|
| Engram | Compiled from Go source | Not a package from any manager — the script compiles the source code itself |
| Codegraph | Downloads a precompiled binary from GitHub releases | Doesn't go through any package manager |
| Ohmypi | glibc binary with Rust extensions, downloaded from releases; requires another npm module already installed | Mix: binary download + dependency on another module |
| Hugging Face CLI | Direct download of the official binary | No intermediate package manager |

## Meta entries

"Languages" and "Packages" are container screens that group the internal tools listed above (one
switch per language/tool) — they are not installable modules in themselves.
