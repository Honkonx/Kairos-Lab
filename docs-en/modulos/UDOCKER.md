# udocker — Kairos Module

## 0. A module with its own dedicated screen

udocker is a standalone module with its own screen in the app, in addition to being used
internally as a building block by other modules (n8n's udocker variant, and Entorno). It
installs the `udocker` package plus `udockertools` (using fixed mirrors, since the dynamic
source used by `udockertools`'s own installer often fails on mobile networks/CGNAT), forces
execution mode `P2`, and generates command-line wrappers (pull an image, run it, list, remove)
so users don't have to memorize udocker's flags.

The app's dedicated screen includes:
- Container list — tapping one opens actions: open a terminal (with an optional dialog for
  volume mounts and environment variables), inspect, export to `.tar`, delete.
- Install a distro — a grid of real Docker Hub images (Alpine/Ubuntu/Debian) plus an option for
  any manual image reference.
- Terminal in a container — a picker for already-created containers.
- Import an image from a `.tar` file.
- Downloaded images list — tapping one opens: inspect, save to `.tar`, delete.

**Permissions:** none Android-specific — it runs entirely in Termux userspace via PRoot, no
root, no permissions beyond the general setup wizard.

## 1. What is udocker?

udocker is a Python tool that runs Docker images without root, without a modified kernel, and
without a daemon. It works by wrapping the container in a chroot-like environment using PRoot —
the same engine proot-distro uses on Termux.

**It is not real Docker.** It's a userspace container emulator.

### How it works internally

```
Termux (Android)
  └── udocker (Python)
        └── PRoot (ptrace syscall interception)
              └── Container rootfs at ~/.udocker/containers/<id>/ROOT/
```

The container **does not have its own network namespace**. It runs directly on top of the
host's network stack (Termux/Android). This is the most important difference from real Docker.

### Supported mode on Termux

Per udocker's official documentation, on Termux/Android only mode **P (PRoot)** is supported.
Modes F (Fakechroot), R (runc), and S (Singularity) require kernel namespaces that Android
doesn't expose without root.

## 2. Networking

### udocker in PRoot mode has no network isolation

Unlike real Docker, udocker with PRoot **shares the host's network stack**:

- The container sees `localhost` exactly as Termux sees it.
- `127.0.0.1` inside the container = Android's `127.0.0.1`.
- There's no bridge network, no NAT, no separate namespace.
- Ports the container exposes map directly to the host.

**Practical consequence:** any service running in Termux is reachable from inside the container
using `localhost` or `127.0.0.1`, and vice versa.

## 3. Communicating with other stack services

### Ollama

A service inside udocker can call Ollama (running in Termux) via `localhost:11434`, as long as
Ollama listens on `0.0.0.0` and not just `127.0.0.1`:

```bash
export OLLAMA_HOST=0.0.0.0
ollama serve
```

### SQLite

SQLite is just a file. Sharing it between udocker and Termux is done by mounting a volume:

```bash
udocker run \
  --volume=/data/data/com.termux/files/home/data:/data \
  <image>
```

**Critical rule on Android:** never use `/tmp/` — it's often noexec on recent versions. Always
use paths under `$HOME` (`/data/data/com.termux/files/home/`).

## 4. Limitations of udocker on Termux/Android

### Inherited from PRoot

| Limitation | Practical impact |
|-----------|---------------------|
| No ports < 1024 | Automatically remapped (e.g. `:80` → `:2080`) |
| No `su` or real UID switching | Processes run as a normal user |
| No filesystem mounting | Use `--volume` instead |
| No `docker-compose` | Only independent containers |
| No cross-container networking | Use `localhost` for communication |
| No `docker exec` into a running process | Only access via volumes or HTTP |

### Android-specific

- **`/tmp/` may be noexec:** never write scripts there; use `$HOME/`.
- **Ports < 1024:** automatically remapped.
- **`$HOME` in `--volume`:** udocker does not expand `$HOME` — always use the absolute path.

### What udocker cannot do without root

- Docker Compose (requires a daemon)
- Cross-container networking (bridge/overlay networking)
- Managed Docker volumes (use manual `--volume` instead)
- Privileged containers
- GPU passthrough
- `docker exec` into a running container

## 5. Quick reference commands

### Installation and setup

```bash
pkg install udocker
udocker install
export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)
```

### Managing images and containers

```bash
udocker pull <image>              # Download an image
udocker create --name=<name> <image>
udocker ps                        # List containers
udocker images                    # List images
udocker rm <name>                 # Remove a container
udocker inspect -p <name>         # Container's path on disk
udocker export -o <file.tar> <name>   # Export a container
udocker save -o <file.tar> <image>    # Save an image
udocker import <file.tar> <repo/image:tag>  # Import an image
```

### Running containers

```bash
udocker run \
  --publish=<host>:<container> \
  --volume=/absolute/host/path:/container/path \
  --env=VARIABLE=value \
  <image>

# Interactive shell
udocker run --user=root <image> /bin/bash

# Single command
udocker run <image> <command> --version
```

### Changing execution mode

```bash
udocker setup n8n                       # View current mode
udocker setup --execmode=P2 n8n         # Slower, more compatible
udocker setup --execmode=P1 n8n         # Default, faster
```

## 6. Troubleshooting

**Error "invalid host volume path":** udocker doesn't expand `$HOME` — always use the absolute
path (`/data/data/com.termux/files/home/...`).

**"this container exposes privileged TCP/IP ports":** informational warning, not a fatal error —
ports below 1024 are automatically remapped.

**A service starts but can't be reached:** verify it listens on all interfaces (`0.0.0.0`, not
just `127.0.0.1`) and check the port with `ss -tlnp`.

**Container fails to start (P1 mode fails):** try `udocker setup --execmode=P2` or explicitly
export `UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)`.

**Data doesn't persist:** verify permissions on the host directory mounted as a volume and
confirm the `--volume` uses an absolute path.

## 7. File layout

```
$HOME/
├── .udocker/
│   ├── bin/                    # udocker binaries (proot, etc.)
│   ├── containers/
│   │   └── <uuid>/
│   │       ├── ROOT/           # Container rootfs
│   │       └── container.json  # Metadata
│   └── repos/                  # Downloaded images (layers)
└── scripts/udocker/            # Wrappers: pull.sh, run.sh, list.sh, rm.sh
```

## 8. udocker vs. a full proot distro

### Rough comparison

| Factor | Service in a full proot distro | Service in udocker |
|--------|--------------------|----|
| Installation | Slower (install from source/packages) | Faster (pull a pre-built image) |
| Version | Pinned at install time | Always whatever the `latest` image ships |
| Updates | Reinstall the package inside the distro | `udocker pull` + recreate the container |
| RAM | Higher overhead (full distro) | Lower overhead (just the container) |
| Customization | High (access to a full system) | Lower (limited to the image) |

### General recommendation

- **udocker** — simpler installation, always up-to-date official image, lower overhead.
- **Full proot distro** — when deep customization is needed, or the stack is already set up
  there.
