# udocker — Kairos Module
## Complete technical documentation

udocker is a tool that runs Docker images without root. In Kairos it is a module of its own with a dedicated screen, and also an internal piece of `n8n` (udocker variant) and `entorno`.

**App:** Kairos (termux-app fork) · ARM64 · Android

---

## 0. Current state of the module

### What it is and what it is for
A rootless "Docker-style" container module on top of PRoot (udocker, execmode P2): pull Docker Hub images (`pull`), create containers, open a shell, run commands, install packages, inspect, export/save/import `.tar`. It is not real Docker (no namespaces/cgroups; see the `docker` module). It is the same engine used by `modulos/n8n.sh` (udocker variant) and `modulos/entorno.sh` (`_install_udocker()`).

**Permissions**: nothing special on Android — it runs 100% in Termux user space via PRoot, with no root and no permissions beyond the generic wizard.

### Sources
`modulos/udocker.sh` (451 lines, v1.0.0, identical copy in `app/src/main/assets/scripts/udocker.sh`), `app/src/main/assets/modules.json`, `UdockerFragment.kt` (860 lines), `ModuleDetailNavigator.kt:40`, `EntornoNative.kt`, `ProcessBuilderExt.kt`.

### Entry in `modules.json`
`id: "udocker"` — `script: "udocker.sh"`, `hasSwitch: false`, `terminalCommand: "udocker"`, `size: "~30MB"`, `estimate: "~2 min"`, `arch: "bionic"`, `requiresProot: false`, `requires: []`, `installMethods: ["pkg"]`, `port: ""`.

### Classification: FUNCTIONAL MODULE
Its own Fragment (`ModuleDetailNavigator.kt:40` → `UdockerFragment()`), with live lists of containers/images (`EntornoNative.udockerContainersList()`, `udocker images`), operation dialogs and background downloads with progress — not a simple install + terminal. No service/port (it runs no daemon). **Approximate UI options: ~20** (3 action buttons + 6 actions per container + 4 per image + 3 distros + "other image" + `-v`/`-e` dialog + 2 package managers + maintenance/diagnose/uninstall).

### Usage from the screen
Under **Images**, open an image and choose **Create a container and use this image** to start the name flow and create the container. The later actions are in the container's row: open the interactive shell, install packages (Alpine `apk`, Debian/Ubuntu `apt-get`), run a non-interactive command, inspect, export or delete. The downloaded image is a template; it does not run until a container is created. Work with `apk`/`apt` happens inside PRoot and may be limited by the container's capabilities; universal compatibility and real Docker privileges are not promised.

### Installation (`bash udocker.sh --silent`)
Flags: `--silent --force --describe --describe-files` (`:56-62`). Two steps: `1/2` udocker + udockertools (`:231`), `2/2` wrappers (`:346`). Fixed variables `UDOCKER_USE_PROOT_EXECUTABLE` and `UDOCKER_USE_CURL_EXECUTABLE` (`:233,242`). Checkpoint `udocker_install`. Registry: `registry_install udocker <version> execmode=P2 scripts=...` (`:434`); version extracted with an `N.N.N` regex.

- **How the binary is installed:** `udocker` does not exist as a Termux apt package (comment `udocker.sh:252-255`). The script installs through **pip from the `github.com/Honkonx/udocker` fork** with `--no-cache-dir --force-reinstall` (`udocker.sh:285-286`), with a fallback to official PyPI (`:293`) that leaves udocker without the android→linux patch (`warn` at `:295`). Only the script header (`:26`), `installMethods: ["pkg"]` in `modules.json` and the `install_hint` of `--describe-files` (`:108`, "pkg install -y udocker") still say `pkg`.
- **Patch verification:** `udocker_fork_patch_active()` (`:157-164`) runs `HostInfo().osversion() != "android"` with Termux's Python; the `~/.udocker_fork_installed` marker (`:288`) is not enough. The "already installed" short-circuit requires binary + `~/.udocker/lib/VERSION` + the 4 wrappers + active patch (`:188-192`).
- **udockertools:** downloaded with `curl` from 2 fixed mirrors (`udocker-englib-1.2.11.tar.gz`, `:131-134`) to a per-PID temp file and installed with `UDOCKER_TARBALL=<local path> udocker install --force` (`:318-336`). If no mirror works → `error`. (The fixed mirrors prevent `udocker install`'s dynamic origin from failing on mobile networks/CGNAT.)
- **execmode P2:** the script only creates the marker `~/.udocker_force_p2` (`:339`); the real configuration `udocker setup --execmode=P2 <container>` is applied by `run.sh` (`:399`) and `UdockerFragment.installDistro()` (`:~653`). There is no global `setup` at installation.
- **Post-condition:** it does not use `verify_binary_installed`; the post-condition is a non-empty `$HOME/.udocker/lib/VERSION` + `udocker_fork_patch_active`. No sha256 of the tarball and no commit pin of the fork (pip installs the `HEAD` of `git+https://github.com/Honkonx/udocker.git`). The script does not install `python`, `pip` or `proot`; it assumes they are in the bootstrap. `ModuleInstalled.LIVE_CHECK_DISABLED = setOf("udocker")` disables the live-check for this module.
- **Wrappers** in `~/scripts/udocker/`: `pull.sh`, `run.sh <name> <image> [-- cmd]`, `list.sh`, `rm.sh`.

### Execution
No start/stop or tmux session of its own. Long operations use `ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, ...), timeout)`: `pull` up to 600 s, `create` 60 s, `setup` 20 s (`UdockerFragment.kt:620-660`). "Open terminal" launches `udocker run --interactive --tty [flags] <name> bash || ... sh` in the adapted terminal (`buildUdockerRunCommand()` `:479-483`, with `export UDOCKER_USE_PROOT_EXECUTABLE`). Commands inside the container and package installation: `runContainerCommand()` `:284` (with `--user=root --containerauth` when `asRoot`).

### Files and paths
`$PREFIX/bin/udocker` (pip), `~/.udocker/{bin,lib,doc,containers,layers,repos}`, `~/.udocker_fork_installed`, `~/.udocker_force_p2`, `~/.udocker_tarball_tmp.<pid>.tar.gz` (temporary), `~/scripts/udocker/{pull,run,list,rm}.sh`, `~/.install_udocker_checkpoint`, `~/kairos_logs/install_udocker.log`, registry (`udocker.installed=true`, `execmode=P2`, ...).

### Security
- Remote installation without verifiable integrity: `pip install git+https://github.com/Honkonx/udocker.git` (no hash or fixed commit) and the udockertools tarball from `raw.githubusercontent.com/jorge-lip/udocker-builds` / `download.a.incd.pt`, without sha256. There is no `curl | bash`.
- Running containers as root inside PRoot (`--user=root --containerauth`) is only userspace root emulation, not real isolation; Docker Hub images are third-party code.
- `-v` mounts and `-e` variables go through `quoteShellArg()` (`:486`) when building the terminal line; `pull`/`create` receive the name as a list argument (no shell). Inference: `setup` runs with `bash -c` interpolating `$name` (`:653`); it was not tested with names containing special characters.
- No secret handling of its own (anonymous Docker Hub).

### Issues / limitations
1. Installation metadata/documentation is inconsistent with the code (`pkg` vs the fork's pip), see "Installation".
2. **`udocker ps -a`**: the Fragment (header `:42`) and `EntornoNative.udockerContainers()/udockerContainersList()` call `udocker ps -a`, while `list.sh` (comment `udocker.sh:410-415`) states that `-a` is an invalid flag ("syntax error at: -a"). An unresolved internal contradiction; which one is correct was not verified on a device.
3. No sha256 / no version pin for the fork and the tarballs.
4. `requires: []` despite depending on python/pip3/proot.
5. `installDistro` does `pull` without `--platform`, so it depends entirely on the fork's patch; with the fallback to official PyPI the `pull` fails with "no image found in manifest for platform (android/arm64)" (the text of the `warn` itself, `udocker.sh:295`).
6. `rm.sh`/`pull.sh` do not set P2 themselves (only `run.sh` does).
7. Inference: that P2 performance is acceptable for heavy workloads; not measured.

### Added on
`modulos/udocker.sh`: **2026-08-16**.

---

## Screen controls (`UdockerFragment.kt`)

If it is not installed, `showNotInstalled()` shows an install button (`:107-118`).

### "CONTAINERS" card

| Control | What it does | Why |
|---|---|---|
| Container row (tap) | `promptContainerActions(name)` — menu: Open terminal / Install packages / Run command / Inspect / Export to .tar / Delete | udocker is used as a general container runtime, not only as an internal piece of n8n |
| → "▶ Open terminal" | `promptRunOptions()` (optional `-v` mounts / `-e` variables) → `launchTerminalCommand("udocker run ...")` | Allows mounting a host directory and passing variables without typing the command by hand |
| → "📦 Install packages" | Alpine: `apk`; Debian/Ubuntu: `apt-get -o APT::Sandbox::User=root update && install -y` | — |
| → "🔍 Inspect" | `udocker inspect <container>` — JSON metadata, read-only | — |
| → "📤 Export to .tar" | `udocker export -o <tmp> <container>` → copies to a Uri chosen through the Storage Access Framework | udocker writes to a real filesystem path, it does not know `content://` Uris — it is generated in a sandbox temp file first |
| → "🗑 Delete container" | Confirmation → `udocker rm <container>` | Deletes the whole rootfs, irreversible — hence the explicit confirmation |
| "📥 Install distro" | `promptInstallDistro()` — tile grid (Alpine/Ubuntu/Debian `:latest`, real Docker Hub images) + "Other image…" (any Docker Hub reference by hand) → container name → `udocker pull` + `create --name=` + `setup --execmode=P2` | `setup --execmode=P2` forces pure PRoot mode, same criterion as `modulos/udocker.sh`. Gives generic access to ANY Docker Hub image |
| "▶ Terminal in container" | `promptTerminalInContainer()` — container picker + interactive session | — |
| "📦 Import image from .tar" | File picker (SAF) → `promptImportImageName()` → `udocker import <tar> <repo/image:tag>` | — |

### "DOWNLOADED IMAGES" card

| Control | What it does | Why |
|---|---|---|
| Image row (tap) | `promptImageActions(image)` — menu: Create container / Inspect / Save to .tar / Delete | — |
| → "🔍 Inspect" | `udocker inspect <image>` — read-only | Works the same on an image reference |
| → "💾 Save to .tar" | `udocker save -o <tmp> <image>` → copies to a chosen Uri (SAF) | Format compatible with real Docker |
| → "🗑 Delete" | Confirmation → `udocker rmi` | — |

MAINTENANCE card (`addMaintenanceCard()`): update / diagnose / uninstall.

**Command coverage**: the screen covers the 12 commands of the udocker project (`pull/create/run/rm/ps/images/rmi/inspect/export/save/import/setup`), checked against the official `indigo-dc/udocker` manual (`user_manual.md`).

---

## 1. What is udocker?

udocker is a Python tool that lets you run Docker images without root, without a modified kernel and without a daemon. It works by wrapping the container in a chroot-like environment using PRoot — the same engine proot-distro uses in Termux.

**It is not real Docker.** It is a user-space container emulator.

### How it works internally

```
Termux (Android)
  └── udocker (Python)
        └── PRoot (ptrace syscall interception)
              └── Container rootfs in ~/.udocker/containers/<id>/ROOT/
                    └── n8n (Node.js process)
```

The container **has no network namespace of its own**. It runs directly on the host network (Termux/Android). This is the most important difference from real Docker.

### Mode supported in Termux

According to the official udocker documentation:

> *"udocker can be used with Termux on Android, the only mode currently supported is P using PRoot."*

Only **P (PRoot)** mode works on Android. The F (Fakechroot), R (runc) and S (Singularity) modes require kernel namespaces that Android does not expose without root.

---

## 2. Network and networking

### udocker in PRoot mode has NO network isolation

Unlike real Docker, udocker with PRoot **shares the host's network stack**. This means:

- The container sees `localhost` exactly as Termux sees it
- `127.0.0.1` inside the container = Android's `127.0.0.1`
- There is no bridge network, no NAT, no separate namespace
- Ports the container exposes map directly to the host

**Practical consequence:** every service running in Termux is reachable from inside the container using `localhost` or `127.0.0.1`.

### Access table between layers

| From \ To | Termux/Android | udocker n8n | proot Debian | Ollama :11434 |
|---------------|---------------|-------------|--------------|---------------|
| **Termux** | — | `localhost:5678` ✅ | via proot-distro ✅ | `localhost:11434` ✅ |
| **udocker n8n** | `localhost:XXXX` ✅ | — | N/A | `localhost:11434` ✅ |
| **proot Debian** | `localhost:XXXX` ✅ | `localhost:5678` ✅ | — | `localhost:11434` ✅ |
| **Android browser** | `localhost:5678` ✅ | — | — | — |
| **PC on WiFi** | `PHONE_IP:5678` ✅ | — | — | — |

---

## 3. Cloudflare tunnel + n8n udocker

### Should I install cloudflared inside udocker or in Termux?

**Install it in native Termux — NOT inside the container.**

**Reason:** Since udocker shares the host's network stack, cloudflared running in Termux can tunnel to port 5678 even if n8n is inside udocker. To cloudflared, n8n in udocker is indistinguishable from n8n in native Termux.

### Cloudflare + udocker setup

```bash
# In Termux (outside the container)

# Option A: temporary URL (no account)
cloudflared tunnel --url http://localhost:5678

# Option B: fixed URL (with a Cloudflare account)
# cloudflare.com → Zero Trust → Tunnels → Create tunnel → copy the token
cloudflared tunnel --no-autoupdate run --token YOUR_TOKEN_HERE
```

### In a tmux script (automatic start)

```bash
# udocker start.sh — add a cloudflared window
tmux new-window -t "n8n-udocker" -n "tunnel"
tmux send-keys -t "n8n-udocker:tunnel" \
  "cloudflared tunnel --no-autoupdate --url http://localhost:5678" Enter
```

### Install cloudflared in Termux if it is missing

```bash
pkg install cloudflared
# or manual ARM64 download:
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64 \
  -O ~/cloudflared
chmod +x ~/cloudflared
```

---

## 4. n8n (udocker) ↔ Termux services communication

### 4.1 Ollama — direct access

n8n inside udocker can call Ollama (running in Termux) using `localhost:11434` with no additional configuration.

**In an n8n workflow:**
- Node: HTTP Request or LangChain Ollama
- URL: `http://localhost:11434/api/generate`
- Body: `{"model": "llama3.2", "prompt": "Your question"}`

**Important:** Ollama must listen on `0.0.0.0`, not only on `127.0.0.1`.

```bash
# In Termux — check that Ollama listens on all interfaces
export OLLAMA_HOST=0.0.0.0
ollama serve
```

### 4.2 Python — three ways

**Way 1: HTTP API (recommended for n8n workflows)**

```bash
# In Termux — create a mini Python server to expose functionality
cat > ~/python_api.py << 'EOF'
from http.server import HTTPServer, BaseHTTPRequestHandler
import json, sqlite3, urllib.parse

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers['Content-Length'])
        body = json.loads(self.rfile.read(length))
        
        # Python logic here — SQLite, pandas, scripts, etc.
        resultado = {"status": "ok", "data": procesar(body)}
        
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(resultado).encode())
    
    def log_message(self, *args): pass  # silence logs

def procesar(data):
    # Your Python logic here
    return data

HTTPServer(('0.0.0.0', 8765), Handler).serve_forever()
EOF

python3 ~/python_api.py &
```

In n8n (udocker): HTTP Request to `http://localhost:8765`

**Way 2: Shared volume (files)**

```bash
# Shared directory accessible from udocker AND from Termux
mkdir -p ~/shared

# udocker run with a mounted volume
udocker run \
  --volume=/data/data/com.termux/files/home/shared:/shared \
  n8n

# n8n writes the result to /shared/output.json
# Python in Termux reads ~/shared/output.json
```

**Way 3: Run Python directly from n8n**

In n8n, the "Execute Command" node:
```bash
# Does not work directly from udocker — Python is not inside the container
# Use the HTTP API (Way 1) instead
```

### 4.3 SQLite — direct access via volume

SQLite is a file. Share the file between udocker and Termux through `--volume`.

```bash
# Path of your DB in Termux
DB_PATH="/data/data/com.termux/files/home/data/stack.db"
mkdir -p "$(dirname $DB_PATH)"

# udocker with access to the DB's directory
udocker run \
  --publish=5678:5678 \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  --volume=/data/data/com.termux/files/home/data:/data \
  n8n
```

Inside n8n: the SQLite node points to `/data/stack.db`

**Critical rule (Android 15):** NEVER use `/tmp/` — it is noexec on Android 15. Always use paths under `$HOME` (`/data/data/com.termux/files/home/`).

---

## 5. udocker limitations in Termux/Android

### Limitations inherited from PRoot

| Limitation | Impact on the stack |
|-----------|---------------------|
| No ports < 1024 | n8n uses 5678 ✅ no problem |
| No `su` or real UID change | n8n runs as a normal user — OK |
| No filesystem mounts | Use `--volume` instead |
| Cannot modify routing/firewall | Not applicable for normal use |
| No `docker-compose` | Independent containers only |
| No network between containers | Use `localhost` to communicate |
| No `docker exec` into a live process | Only access via volumes or HTTP |

### Specific limitations on Android 15

- **`/tmp/` is noexec:** Never write scripts there. Use `$HOME/`
- **Ports < 1024:** Android remaps them automatically (e.g.: :80 → :2080)
- **`read` without `/dev/tty`:** In bash scripts inside Termux always use `read < /dev/tty`
- **`$HOME` in `--volume`:** udocker does not expand `$HOME`. Use an absolute path: `/data/data/com.termux/files/home/`

### What udocker CANNOT do (on Android without root)

- Docker Compose (requires a daemon)
- Network between containers (bridge/overlay networking)
- Managed Docker volumes (use manual --volume)
- Privileged containers
- GPU passthrough
- `docker exec` into a live container

---

## 6. udocker or proot Debian for n8n?

### Direct comparison

| Factor | n8n in proot Debian | n8n in udocker |
|--------|--------------------|----|
| Installation | ~40 min (npm install) | ~15 min (image pull) |
| Space | ~800MB (node_modules) | ~900MB (full image) |
| n8n version | Fixed at install time | Always `latest` with pull |
| Update | `npm update -g n8n` (slow) | `udocker pull` + recreate (fast) |
| Persistent data | `/root/.n8n` in the rootfs | `~/n8n-udocker/` in Termux home |
| RAM overhead | ~150MB (Debian proot) | ~80MB (only the container) |
| Cloudflared | Installed inside the proot | In native Termux (simpler) |
| Networking with the stack | Via proot-distro login | Direct — same localhost |
| Ollama access | `localhost:11434` ✅ | `localhost:11434` ✅ |
| SQLite access | Inside the proot | Via --volume to ~/data/ |

### Recommendation

- **udocker** → simpler installation, always-updated official image, less overhead
- **proot Debian** → if you need deep customization, integrated cloudflared, or the stack is already there

---

## 7. Full example: n8n → Ollama → Python → SQLite workflow

### Flow architecture

```
n8n (udocker :5678)
    │
    ├── HTTP Request → Ollama (localhost:11434) [LLM inference]
    │
    ├── HTTP Request → Python API (localhost:8765) [processing]
    │        │
    │        └── SQLite (~/data/stack.db) [persistence]
    │
    └── SQLite node → /data/stack.db [direct read/write]
```

### 1. Start Ollama (Termux)

```bash
OLLAMA_HOST=0.0.0.0 ollama serve &
ollama pull llama3.2:1b
```

### 2. Start the Python API (Termux)

```bash
cat > ~/python_api.py << 'EOF'
from http.server import HTTPServer, BaseHTTPRequestHandler
import json, sqlite3, datetime

DB = "/data/data/com.termux/files/home/data/stack.db"

def init_db():
    con = sqlite3.connect(DB)
    con.execute("""
        CREATE TABLE IF NOT EXISTS eventos (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tipo TEXT,
            payload TEXT,
            ts TEXT
        )
    """)
    con.commit()
    con.close()

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length)) if length else {}
        
        # Use datetime.now() — NEVER SQLite's datetime('now')
        ts = datetime.datetime.now().isoformat()
        
        con = sqlite3.connect(DB)
        con.execute(
            "INSERT INTO eventos (tipo, payload, ts) VALUES (?, ?, ?)",
            (body.get('tipo', 'general'), json.dumps(body), ts)
        )
        con.commit()
        rows = con.execute("SELECT COUNT(*) FROM eventos").fetchone()[0]
        con.close()
        
        resp = {"ok": True, "total_eventos": rows, "ts": ts}
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(resp).encode())
    
    def log_message(self, *args): pass

init_db()
print("Python API running on :8765")
HTTPServer(('0.0.0.0', 8765), Handler).serve_forever()
EOF

mkdir -p ~/data
python3 ~/python_api.py &
```

### 3. Start n8n (udocker)

```bash
mkdir -p ~/n8n-udocker
chmod 777 ~/n8n-udocker

udocker run \
  --publish=5678:5678 \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  --volume=/data/data/com.termux/files/home/data:/data \
  --env=N8N_HOST=0.0.0.0 \
  --env=N8N_PORT=5678 \
  --env=N8N_SECURE_COOKIE=false \
  --env=NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os \
  --env=NODE_FUNCTION_ALLOW_EXTERNAL=* \
  n8n
```

### 4. Workflow in n8n

**Node 1 — HTTP Request (Ollama):**
```
Method: POST
URL: http://localhost:11434/api/generate
Body: {
  "model": "llama3.2:1b",
  "prompt": "{{ $json.pregunta }}",
  "stream": false
}
```

**Node 2 — HTTP Request (Python API):**
```
Method: POST  
URL: http://localhost:8765
Body: {
  "tipo": "consulta_llm",
  "pregunta": "{{ $json.pregunta }}",
  "respuesta": "{{ $json.response }}"
}
```

**Node 3 — SQLite (direct read):**
```
Database: /data/stack.db
Query: SELECT * FROM eventos ORDER BY ts DESC LIMIT 10
```

---

## 8. Quick reference commands

### Installation and setup

```bash
# The udocker module is installed from the Modules tab in Kairos
# (it installs the binary through pip from the fork, plus udockertools, and sets execmode P2)

# Use Termux's proot (recommended for Android)
export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)
```

### Image and container management

```bash
# Pull an image
udocker pull n8nio/n8n

# Create a named container
udocker create --name=n8n n8nio/n8n

# List containers
udocker ps

# List images
udocker images

# Delete a container
udocker rm n8n

# Container path on disk
udocker inspect -p n8n
# → /data/data/com.termux/files/home/.udocker/containers/<id>/ROOT
```

### Running containers

```bash
# n8n with volume and environment variables
udocker run \
  --publish=5678:5678 \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  --env=N8N_SECURE_COOKIE=false \
  n8n

# Interactive shell inside the container
udocker run --user=root n8n /bin/bash

# Run a single command
udocker run n8n n8n --version

# Debug mode
udocker -D run n8n
```

### Updating n8n

```bash
# 1. Stop the current container (Ctrl+C or kill tmux)
tmux kill-session -t n8n-udocker

# 2. Pull the new image
udocker pull n8nio/n8n

# 3. Delete the old container
udocker rm n8n

# 4. Recreate with the new image
udocker create --name=n8n n8nio/n8n

# 5. Start
bash ~/scripts/n8n-udocker/start.sh
```

### Backup and restore

```bash
# Backup of n8n data (workflows, credentials, etc.)
FECHA=$(date +%Y%m%d_%H%M)
tar -czf "/sdcard/Download/n8n_udocker_${FECHA}.tar.gz" \
  -C /data/data/com.termux/files/home/n8n-udocker .

# Restore
mkdir -p ~/n8n-udocker
tar -xzf /sdcard/Download/n8n_udocker_YYYYMMDD_HHMM.tar.gz \
  -C ~/n8n-udocker

# Full backup including the image (for migration)
# Containers are in ~/.udocker/containers/
tar -czf /sdcard/Download/udocker_full_backup.tar.gz \
  -C ~ .udocker/
```

### Changing the execution mode

```bash
# See the current mode
udocker setup n8n

# Switch to P2 (slower but more compatible)
udocker setup --execmode=P2 n8n

# Back to P1 (default, faster)
udocker setup --execmode=P1 n8n
```

---

## 9. Troubleshooting

### Error: "invalid host volume path"

```bash
# ❌ Cause: udocker does not expand $HOME
udocker run --volume=$HOME/data:/data n8n

# ✅ Fix: always an absolute path
udocker run \
  --volume=/data/data/com.termux/files/home/data:/data \
  n8n
```

### Error: "this container exposes privileged TCP/IP ports"

```bash
# Informational warning — not a fatal error
# Ports < 1024 are remapped automatically
# :80 → :2080, :443 → :2443
# n8n uses :5678 — not affected
```

### n8n starts but cannot be reached

```bash
# Check that n8n listens on all interfaces
udocker run --env=N8N_HOST=0.0.0.0 n8n

# Check the port
ss -tlnp | grep 5678

# Test from Termux
curl http://localhost:5678
```

### Ollama does not respond from n8n

```bash
# Ollama must listen on 0.0.0.0, not on 127.0.0.1
OLLAMA_HOST=0.0.0.0 ollama serve

# Check
curl http://localhost:11434/api/tags
```

### Python API does not respond from n8n

```bash
# Check that the Python API listens on 0.0.0.0
# (in the script: HTTPServer(('0.0.0.0', 8765), Handler))

# Check that it is running
ss -tlnp | grep 8765

# Test
curl -X POST http://localhost:8765 \
  -H "Content-Type: application/json" \
  -d '{"tipo":"test"}'
```

### Container does not start (P1 fails)

```bash
# Try P2
udocker setup --execmode=P2 n8n
udocker run ... n8n

# Or use Termux's proot explicitly
export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)
udocker run ... n8n
```

### n8n data does not persist

```bash
# Check that ~/n8n-udocker has the right permissions
ls -la ~/n8n-udocker
chmod 777 ~/n8n-udocker

# Check that --volume uses an absolute path
udocker run \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  n8n
```

---

## 10. File structure in the stack

```
$HOME/  (/data/data/com.termux/files/home/)
├── .udocker/
│   ├── bin/                    # udocker binaries (proot, etc.)
│   ├── containers/
│   │   └── <uuid>/
│   │       ├── ROOT/           # Container rootfs (n8n installed here)
│   │       └── container.json  # Metadata
│   └── repos/                  # Downloaded images (layers)
│
├── n8n-udocker/                # n8n persistent data
│   ├── config.json             # n8n configuration
│   ├── database.sqlite         # n8n internal DB
│   └── .n8n/                   # Workflows, credentials
│
├── data/
│   └── stack.db                # SQLite shared with Python and n8n
│
├── scripts/
│   ├── n8n/                    # Scripts for n8n proot
│   └── n8n-udocker/            # Scripts for n8n udocker
│       ├── start.sh
│       ├── stop.sh
│       ├── status.sh
│       ├── log.sh
│       ├── update.sh
│       └── backup.sh
│
└── python_api.py               # Local Python API (port 8765)
```

---

## 11. Relevant environment variables for n8n in udocker

```bash
# Variables that go in --env when running udocker run

N8N_HOST=0.0.0.0                    # Listen on all interfaces
N8N_PORT=5678                        # Port
N8N_SECURE_COOKIE=false              # Needed over HTTP without HTTPS
N8N_RUNNERS_ENABLED=true             # Enable task runners
N8N_RUNNERS_HEARTBEAT_INTERVAL=300   # Runner timeout
NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os  # Allowed Node modules
NODE_FUNCTION_ALLOW_EXTERNAL=*       # External modules
WEBHOOK_URL=https://your-domain.com  # If you use cloudflared with a fixed domain
N8N_BASIC_AUTH_ACTIVE=false          # No basic auth (uses the n8n account)
```

---

## 12. Summary of architecture decisions

| Component | Where it runs | Reason |
|-----------|-------------|-------|
| **Ollama** | Native Termux | Needs native ARM64 hardware, no overhead |
| **Python 3.13** | Native Termux | Direct access to SQLite and the filesystem |
| **SQLite** | Native Termux | File shared via --volume |
| **Claude Code** | Native Termux | CLI that needs system access |
| **n8n** | udocker | Official image, easy updating, less RAM |
| **cloudflared** | Native Termux | Shares the network with udocker — does not need to be inside |
| **Redis** | udocker (optional) | Small Alpine image, for n8n queue mode |
| **PostgreSQL** | udocker (optional) | Alternative to SQLite for n8n if it grows |
