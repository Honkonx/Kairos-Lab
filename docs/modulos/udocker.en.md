# udocker

## Complete technical documentation

**Reference document** — udocker is an optional tool that can run Docker images without root.

**Reference device:** Xiaomi POCO F5 · Android 15 · HyperOS 2.0 · ARM64 · 11GB RAM
**App:** Kairos (termux-app fork) · ARM64 · Android

---

## 0. Standalone module with its own screen

udocker isn't just an underlying tool used by other modules — it has its own standalone module with a dedicated screen:

- **`modulos/udocker.sh`** — an independent standalone module, not just the internal piece already used by `n8n.sh`. Installs `pkg install udocker` + `udockertools` (with fixed mirrors as a fallback, since udocker's dynamic install source can fail on mobile networks/CGNAT) + forces `execmode P2` + generates wrappers under `~/scripts/udocker/` (`pull.sh`, `run.sh`, `list.sh`, `rm.sh`). Accepts `--silent`/`--force`/`--describe`.
- **`modules.json`** has an `"id": "udocker"` entry — `script: "udocker.sh"`, no switch, terminal command `udocker`, size `~30MB`, bionic architecture, no proot required.
- **Dedicated Fragment: `app/src/main/java/com/termux/app/ui/UdockerFragment.kt`**. A real screen with:
  - "CONTAINERS" card — a real list; tapping one opens actions: open terminal (with an optional dialog for `-v` mounts/`-e` variables), inspect (`udocker inspect`), export to `.tar` (`udocker export`, via the system's file picker), delete (`udocker rm`).
  - "Install distro" — a grid of tiles (Alpine/Ubuntu/Debian, real Docker Hub images) + "Other image…" for any manual reference → `udocker pull` + `udocker create --name=` + `udocker setup --execmode=P2`.
  - "Terminal in container" — selector of already-created containers.
  - "Import image from .tar" — `udocker import` via the system's file picker.
  - "DOWNLOADED IMAGES" card — a list (`udocker images`); tapping one opens: inspect, save to `.tar` (`udocker save`), delete (`udocker rmi`).
  - MAINTENANCE card (standard pattern shared with the rest of the modules).
- Has its own registry entry (`udocker.installed=true`, etc.), like any real module.

udocker is also still used internally by `modulos/n8n.sh` (udocker variant) and `modulos/entorno.sh` — those two integrations haven't changed, they simply aren't the only way to get udocker onto the device anymore. The rest of this document (network architecture, CLI commands, troubleshooting, PRoot limitations) is real and current information about udocker's own behavior — it doesn't depend on whether it was installed via n8n/Entorno or via the standalone module.

**Permissions**: none special to Android — runs entirely in Termux user space via PRoot, without root, without any permissions beyond the app's generic setup wizard.

---

## 1. What is udocker?

udocker is a Python tool that lets you run Docker images without root, without a modified kernel, and without a daemon. It works by wrapping the container in a chroot-like environment using PRoot — the same engine proot-distro uses in Termux.

**It is not real Docker.** It's a user-space container emulator.

### How it works internally

```
Termux (Android)
  └── udocker (Python)
        └── PRoot (ptrace syscall interception)
              └── Container rootfs at ~/.udocker/containers/<id>/ROOT/
                    └── n8n (Node.js process)
```

The container **has no network namespace of its own**. It runs directly over the host's network (Termux/Android). This is the single most important difference from real Docker.

### Mode supported in Termux

According to udocker's official documentation:

> *"udocker can be used with Termux on Android, the only mode currently supported is P using PRoot."*

Only **P (PRoot)** mode works on Android. Modes F (Fakechroot), R (runc), and S (Singularity) require kernel namespaces that Android doesn't expose without root.

---

## 2. Networking — the key to everything

### udocker in PRoot mode has no network isolation

Unlike real Docker, udocker with PRoot **shares the host's network stack**. This means:

- The container sees `localhost` exactly the way Termux does
- `127.0.0.1` inside the container = `127.0.0.1` on Android
- No bridge network, no NAT, no separate namespace
- Ports the container exposes map directly to the host

**Practical consequence:** any service running in Termux is reachable from inside the container using `localhost` or `127.0.0.1`.

### Cross-layer access table

| From \ To | Termux/Android | udocker n8n | proot Debian | Ollama :11434 |
|---------------|---------------|-------------|--------------|---------------|
| **Termux** | — | `localhost:5678` | via proot-distro | `localhost:11434` |
| **udocker n8n** | `localhost:XXXX` | — | N/A | `localhost:11434` |
| **proot Debian** | `localhost:XXXX` | `localhost:5678` | — | `localhost:11434` |
| **Android browser** | `localhost:5678` | — | — | — |
| **PC on WiFi** | `PHONE_IP:5678` | — | — | — |

---

## 3. Cloudflare tunnel + udocker n8n

### Install cloudflared inside udocker or in Termux?

**Install in native Termux — not inside the container.**

**Reason:** Since udocker shares the host's network stack, cloudflared running in Termux can tunnel to port 5678 even though n8n is inside udocker. From cloudflared's point of view, n8n in udocker is indistinguishable from n8n in native Termux.

### Cloudflare + udocker setup

```bash
# In Termux (outside the container)

# Option A: temporary URL (no account)
cloudflared tunnel --url http://localhost:5678

# Option B: fixed URL (with a Cloudflare account)
# cloudflare.com → Zero Trust → Tunnels → Create tunnel → copy token
cloudflared tunnel --no-autoupdate run --token YOUR_TOKEN_HERE
```

### In a tmux script (automatic startup)

```bash
# udocker's start.sh — add a cloudflared window
tmux new-window -t "n8n-udocker" -n "tunnel"
tmux send-keys -t "n8n-udocker:tunnel" \
  "cloudflared tunnel --no-autoupdate --url http://localhost:5678" Enter
```

### Install cloudflared in Termux if missing

```bash
pkg install cloudflared
# or manual ARM64 download:
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64 \
  -O ~/cloudflared
chmod +x ~/cloudflared
```

---

## 4. Communication between n8n (udocker) and Termux services

### 4.1 Ollama — direct access

n8n inside udocker can call Ollama (running in Termux) using `localhost:11434` with no extra configuration.

**In an n8n workflow:**
- Node: HTTP Request or LangChain Ollama
- URL: `http://localhost:11434/api/generate`
- Body: `{"model": "llama3.2", "prompt": "Your question"}`

**Important:** Ollama must listen on `0.0.0.0`, not just `127.0.0.1`.

```bash
# In Termux — verify Ollama listens on all interfaces
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
        
        # Your Python logic here — SQLite, pandas, scripts, etc.
        result = {"status": "ok", "data": process(body)}
        
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(result).encode())
    
    def log_message(self, *args): pass  # silence logs

def process(data):
    # Your Python logic here
    return data

HTTPServer(('0.0.0.0', 8765), Handler).serve_forever()
EOF

python3 ~/python_api.py &
```

In n8n (udocker): HTTP Request to `http://localhost:8765`

**Way 2: Shared volume (files)**

```bash
# Directory shared between udocker AND Termux
mkdir -p ~/shared

# udocker run with mounted volume
udocker run \
  --volume=/data/data/com.termux/files/home/shared:/shared \
  n8n

# n8n writes the result to /shared/output.json
# Python in Termux reads ~/shared/output.json
```

**Way 3: Run Python directly from n8n**

In n8n, the "Execute Command" node:
```bash
# Doesn't work directly from udocker — Python isn't inside the container
# Use the HTTP API (Way 1) instead
```

### 4.3 SQLite — direct access via volume

SQLite is a file. Share the file between udocker and Termux via `--volume`.

```bash
# Your DB path in Termux
DB_PATH="/data/data/com.termux/files/home/data/stack.db"
mkdir -p "$(dirname $DB_PATH)"

# udocker with access to the DB directory
udocker run \
  --publish=5678:5678 \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  --volume=/data/data/com.termux/files/home/data:/data \
  n8n
```

Inside n8n: the SQLite node points at `/data/stack.db`

**Critical rule (Android 15):** never use `/tmp/` — it's noexec on Android 15. Always use paths under `$HOME` (`/data/data/com.termux/files/home/`).

---

## 5. udocker limitations on Termux/Android

### Limitations inherited from PRoot

| Limitation | Impact on the stack |
|-----------|---------------------|
| No ports < 1024 | n8n uses 5678, no problem |
| No `su` or real UID change | n8n runs as a normal user, OK |
| No filesystem mounts | Use `--volume` instead |
| No routing/firewall changes | Doesn't apply for normal use |
| No `docker-compose` | Independent containers only |
| No network between containers | Use `localhost` for communication |
| No `docker exec` on a live process | Only access via volumes or HTTP |

### Limitations specific to Android 15

- **`/tmp/` is noexec:** Never write scripts there. Use `$HOME/`
- **Ports < 1024:** Android remaps them automatically (e.g. :80 → :2080)
- **`read` without `/dev/tty`:** In bash scripts inside Termux, always use `read < /dev/tty`
- **`$HOME` in `--volume`:** udocker doesn't expand `$HOME`. Use an absolute path: `/data/data/com.termux/files/home/`

### What udocker cannot do (on Android without root)

- Docker Compose (requires a daemon)
- Network between containers (bridge/overlay networking)
- Managed Docker volumes (use manual `--volume` instead)
- Privileged containers
- GPU passthrough
- `docker exec` on a live container

---

## 6. udocker or proot Debian for n8n?

### Direct comparison

| Factor | n8n in proot Debian | n8n in udocker |
|--------|--------------------|----|
| Install time | ~40 min (npm install) | ~15 min (pull image) |
| Space | ~800MB (node_modules) | ~900MB (full image) |
| n8n version | Pinned at install time | Always `latest` on pull |
| Update | `npm update -g n8n` (slow) | `udocker pull` + recreate (fast) |
| Persistent data | `/root/.n8n` in the rootfs | `~/n8n-udocker/` in Termux home |
| RAM overhead | ~150MB (Debian proot) | ~80MB (container only) |
| Cloudflared | Installed inside the proot | In native Termux (simpler) |
| Networking with the stack | Via proot-distro login | Direct — same localhost |
| Ollama access | `localhost:11434` | `localhost:11434` |
| SQLite access | Inside the proot | Via --volume to ~/data/ |

### Recommendation

- **udocker** → simpler install, always-updated official image, less overhead
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

### Install and setup

```bash
# Install udocker
pkg install udocker

# Initialize (first time)
udocker install

# Use Termux's proot (recommended for Android)
export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)
```

### Image and container management

```bash
# Download image
udocker pull n8nio/n8n

# Create named container
udocker create --name=n8n n8nio/n8n

# List containers
udocker ps

# List images
udocker images

# Delete container
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

# 3. Remove the old container
udocker rm n8n

# 4. Recreate with the new image
udocker create --name=n8n n8nio/n8n

# 5. Start
bash ~/scripts/n8n-udocker/start.sh
```

### Backup and restore

```bash
# Backup n8n data (workflows, credentials, etc.)
FECHA=$(date +%Y%m%d_%H%M)
tar -czf "/sdcard/Download/n8n_udocker_${FECHA}.tar.gz" \
  -C /data/data/com.termux/files/home/n8n-udocker .

# Restore
mkdir -p ~/n8n-udocker
tar -xzf /sdcard/Download/n8n_udocker_YYYYMMDD_HHMM.tar.gz \
  -C ~/n8n-udocker

# Full backup including the image (for migration)
# Containers live under ~/.udocker/containers/
tar -czf /sdcard/Download/udocker_full_backup.tar.gz \
  -C ~ .udocker/
```

### Changing execution mode

```bash
# See current mode
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
# ❌ Cause: udocker doesn't expand $HOME
udocker run --volume=$HOME/data:/data n8n

# ✅ Fix: always use an absolute path
udocker run \
  --volume=/data/data/com.termux/files/home/data:/data \
  n8n
```

### Error: "this container exposes privileged TCP/IP ports"

```bash
# Informational warning — not a fatal error
# Ports < 1024 get remapped automatically
# :80 → :2080, :443 → :2443
# n8n uses :5678 — unaffected
```

### n8n starts but can't be reached

```bash
# Verify n8n listens on all interfaces
udocker run --env=N8N_HOST=0.0.0.0 n8n

# Check the port
ss -tlnp | grep 5678

# Test from Termux
curl http://localhost:5678
```

### Ollama doesn't respond from n8n

```bash
# Ollama must listen on 0.0.0.0, not 127.0.0.1
OLLAMA_HOST=0.0.0.0 ollama serve

# Verify
curl http://localhost:11434/api/tags
```

### Python API doesn't respond from n8n

```bash
# Verify the Python API listens on 0.0.0.0
# (in the script: HTTPServer(('0.0.0.0', 8765), Handler))

# Verify it's running
ss -tlnp | grep 8765

# Test
curl -X POST http://localhost:8765 \
  -H "Content-Type: application/json" \
  -d '{"tipo":"test"}'
```

### Container won't start (P1 fails)

```bash
# Try with P2
udocker setup --execmode=P2 n8n
udocker run ... n8n

# Or explicitly use Termux's proot
export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)
udocker run ... n8n
```

### n8n data doesn't persist

```bash
# Verify ~/n8n-udocker has correct permissions
ls -la ~/n8n-udocker
chmod 777 ~/n8n-udocker

# Verify the --volume uses an absolute path
udocker run \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  n8n
```

---

## 10. File layout in the stack

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
├── n8n-udocker/                # Persistent n8n data
│   ├── config.json             # n8n configuration
│   ├── database.sqlite         # n8n's internal DB
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

## 11. Environment variables relevant to n8n in udocker

```bash
# Variables passed via --env when running udocker run

N8N_HOST=0.0.0.0                    # Listen on all interfaces
N8N_PORT=5678                        # Port
N8N_SECURE_COOKIE=false              # Needed over HTTP without HTTPS
N8N_RUNNERS_ENABLED=true             # Enable task runners
N8N_RUNNERS_HEARTBEAT_INTERVAL=300   # Runner timeout
NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os  # Allowed Node modules
NODE_FUNCTION_ALLOW_EXTERNAL=*       # Allowed external modules
WEBHOOK_URL=https://your-domain.com  # If using cloudflared with a fixed domain
N8N_BASIC_AUTH_ACTIVE=false          # No basic auth (uses n8n's own account)
```

---

## 12. Summary of architecture decisions

| Component | Runs where | Reason |
|-----------|-------------|-------|
| **Ollama** | Native Termux | Needs native ARM64 hardware, no overhead |
| **Python 3.13** | Native Termux | Direct access to SQLite and the filesystem |
| **SQLite** | Native Termux | File shared via --volume |
| **Claude Code** | Native Termux | CLI that needs system access |
| **n8n** | udocker | Official image, easy updates, less RAM |
| **cloudflared** | Native Termux | Shares network with udocker — doesn't need to be inside |
| **Redis** | udocker (optional) | Small Alpine image, for n8n queue mode |
| **PostgreSQL** | udocker (optional) | SQLite alternative for n8n if it grows |

## Screen controls

### "CONTAINERS" card

| Control | What it does |
|---|---|
| Container row (tap) | Menu: Open terminal / Inspect / Export to .tar / Delete |
| → "Open terminal" | Optional dialog for `-v` mounts/`-e` variables → opens a terminal with `udocker run ...` |
| → "Inspect" | `udocker inspect <container>` — real JSON metadata, read-only |
| → "Export to .tar" | `udocker export -o <tmp> <container>` → copies to a destination chosen with the system's file picker (udocker writes to a real filesystem path, it knows nothing about content URIs — a temp file in the sandbox is generated first) |
| → "Delete container" | Confirmation → `udocker rm <container>` (deletes the entire rootfs, irreversible) |
| "Install distro" | Grid of tiles (Alpine/Ubuntu/Debian) + "Other image…" (manual Docker Hub) → container name → `udocker pull` + `create` + `setup --execmode=P2` |
| "Terminal in container" | Container selector + interactive session |
| "Import image from .tar" | Opens the file picker → image name → `udocker import <tar> <repo/image:tag>` |

### "DOWNLOADED IMAGES" card

| Control | What it does |
|---|---|
| Image row (tap) | Menu: Inspect / Save to .tar / Delete |
| → "Inspect" | `udocker inspect <image>` — read-only |
| → "Save to .tar" | `udocker save -o <tmp> <image>` → copies to the chosen destination (format compatible with real Docker, not just udocker) |
| → "Delete" | Confirmation → deletes the image |

The screen covers udocker's real project commands (pull/create/run/rm/ps/images/rmi/inspect/export/save/import/setup), confirmed one by one against the official documentation (`indigo-dc/udocker`, `user_manual.md`). The distro installer gives generic access to any Docker Hub image, not just the ones n8n uses internally.

---

*Technical documentation for Kairos.*
