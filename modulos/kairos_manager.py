#!/usr/bin/env python3
"""
kairos_manager.py — Controlador central KairosApp v3.0
termux-ai-stack · ARM64 · sin root

MÓDULOS: status, claude, opencode, openclaw, rootfs

Ronda 2026-07-31 (ver docs/humano/humano27.md): la mayoría de los módulos que vivían
acá (system, remote, python, sqlite, expo, ollama, n8n, hermes, tunnel,
backup, pkg, entorno, pm2) eran subprocess+lectura/escritura de archivos+JSON
sin ninguna necesidad real de Python — se migraron a Kotlin nativo
(ProcessBuilder+applyTermuxEnv, o SQLiteDatabase nativo de Android para lo
que antes usaba el módulo sqlite3). Lo que queda acá es lo que sí comparte
lógica real no trivial entre varios Fragments (_handle_project_actions:
symlinks + file locking + registry, usado por claude/opencode/openclaw) o es
demasiado extenso/riesgoso para migrar en una sola ronda (rootfs).

REGLAS:
  - SOLO stdlib · NUNCA import requests · NUNCA /tmp/
  - Toda función → JSON stdout · File locking en registry

VERSIÓN: 3.0.0 | Junio 2026
"""

import json, os, sys, subprocess, fcntl, socket, shutil, re, shlex, time
from datetime import datetime

# ════════════════════════════════════════════════════════════
#  CONSTANTES
# ════════════════════════════════════════════════════════════

HOME = os.environ.get("HOME", "/data/data/com.termux/files/home")
PREFIX = os.environ.get("PREFIX", "/data/data/com.termux/files/usr")
REGISTRY = os.path.join(HOME, ".android_server_registry")
CACHE_FILE = os.path.join(HOME, ".kairos_cache.json")
TMPDIR = os.path.join(HOME, "tmp")
SCRIPTS = os.path.join(HOME, "scripts")
ROOTFS_BASE = os.path.join(PREFIX, "var/lib/proot-distro/installed-rootfs")
PROJECTS_DIR = os.path.join(HOME, "proyectos")
OPENCLAW_WORKSPACE = os.path.join(HOME, ".openclaw", "workspace")
STORAGE_DOWNLOAD = "/storage/emulated/0/Download"
BASHRC = os.path.join(HOME, ".bashrc")

# Auditoría 2026-07-27: carpetas conocidas por ser enormes que ningún os.walk() de
# este archivo necesita recorrer nunca (find-scripts, list-dbs) — sin esto, un
# proyecto importado con node_modules/.git/venv en el primer nivel se escaneaba
# entero antes de que el guard de profundidad cortara.
_SKIP_DIRS = {"node_modules", ".git", "venv", ".venv", ".gradle", "build", "__pycache__"}

for _d in [TMPDIR, PROJECTS_DIR]:
    os.makedirs(_d, exist_ok=True)

# ════════════════════════════════════════════════════════════
#  CORE UTILS
# ════════════════════════════════════════════════════════════

def json_ok(**d):
    print(json.dumps({"ok": True, **d}, ensure_ascii=False))

def json_error(msg, **e):
    print(json.dumps({"ok": False, "error": str(msg), **e}, ensure_ascii=False))

def run_cmd(cmd, timeout=30, shell=True):
    try:
        r = subprocess.run(cmd, shell=shell, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout.strip(), r.stderr.strip()
    except subprocess.TimeoutExpired:
        return 1, "", "timeout"
    except Exception as e:
        return 1, "", str(e)

def run_proot(cmd, distro=None, timeout=120):
    if not distro:
        distro = detect_distro()
    if not distro:
        return 1, "", "no rootfs"
    return run_cmd(f"proot-distro login {distro} -- bash -c '{cmd}'", timeout=timeout)

def detect_distro():
    if not os.path.isdir(ROOTFS_BASE):
        return None
    for d in os.listdir(ROOTFS_BASE):
        p = os.path.join(ROOTFS_BASE, d)
        if os.path.isdir(p):
            for c in ["bin/bash", "usr/bin/bash", "etc/os-release"]:
                if os.path.exists(os.path.join(p, c)):
                    return d
    return None

def get_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80)); ip = s.getsockname()[0]; s.close(); return ip
    except Exception:
        rc, o, _ = run_cmd("ifconfig 2>/dev/null | grep 'inet ' | grep -v '127.' | awk '{print $2}' | head -1")
        return o or "localhost"

def check_port(port, host="127.0.0.1"):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM); s.settimeout(1)
        r = s.connect_ex((host, port)); s.close(); return r == 0
    except Exception:
        return False

def pgrep_x(name):
    rc, _, _ = run_cmd(f"pgrep -x {name}", timeout=5); return rc == 0

def pgrep_f(pattern):
    rc, _, _ = run_cmd(f"pgrep -f '{pattern}'", timeout=5); return rc == 0

def tmux_has(session):
    rc, _, _ = run_cmd(f"tmux has-session -t {session} 2>/dev/null", timeout=5); return rc == 0

def human_size(b):
    for u in ["B", "KB", "MB", "GB"]:
        if b < 1024: return f"{b:.1f}{u}"
        b /= 1024
    return f"{b:.1f}TB"

def http_get_json(url, timeout=5):
    import urllib.request
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())

def http_post_json(url, body, timeout=120):
    import urllib.request
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())

# ════════════════════════════════════════════════════════════
#  REGISTRY
# ════════════════════════════════════════════════════════════

def read_registry():
    d = {}
    if not os.path.exists(REGISTRY): return d
    try:
        with open(REGISTRY) as f:
            for l in f:
                l = l.strip()
                if "=" in l and not l.startswith("#"):
                    k, v = l.split("=", 1); d[k.strip()] = v.strip()
    except Exception: pass
    return d

def write_registry(prefix, entries):
    lf = REGISTRY + ".lock"
    try:
        fd = open(lf, "w"); fcntl.flock(fd, fcntl.LOCK_EX)
        ex = []
        if os.path.exists(REGISTRY):
            with open(REGISTRY) as f:
                ex = [l for l in f.readlines() if not l.strip().startswith(f"{prefix}.")]
        with open(REGISTRY, "w") as f:
            f.writelines(ex)
            for k, v in entries.items(): f.write(f"{prefix}.{k}={v}\n")
        fcntl.flock(fd, fcntl.LOCK_UN); fd.close()
    except Exception: pass

def registry_del(prefix):
    lf = REGISTRY + ".lock"
    try:
        fd = open(lf, "w"); fcntl.flock(fd, fcntl.LOCK_EX)
        if os.path.exists(REGISTRY):
            with open(REGISTRY) as f:
                lines = [l for l in f.readlines() if not l.strip().startswith(f"{prefix}.")]
            with open(REGISTRY, "w") as f: f.writelines(lines)
        fcntl.flock(fd, fcntl.LOCK_UN); fd.close()
    except Exception: pass

def write_cache(key, data):
    cache = {}
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE) as f: cache = json.load(f)
        except Exception: pass
    cache[key] = {"data": data, "ts": datetime.now().isoformat()}
    try:
        with open(CACHE_FILE, "w") as f: json.dump(cache, f, ensure_ascii=False)
    except Exception: pass

# ════════════════════════════════════════════════════════════
#  PROYECTOS — reutilizable (Claude, OpenCode, OpenClaw)
# ════════════════════════════════════════════════════════════

def proj_list(base_dir, reg_prefix):
    os.makedirs(base_dir, exist_ok=True)
    reg = read_registry(); projects = []
    for name in sorted(os.listdir(base_dir)):
        full = os.path.join(base_dir, name)
        if not (os.path.isdir(full) or os.path.islink(full)): continue
        real = os.path.realpath(full)
        projects.append({
            "name": name, "path": full, "real_path": real,
            "is_symlink": os.path.islink(full),
            "origin": reg.get(f"{reg_prefix}.{name}", ""),
            "has_package_json": os.path.exists(os.path.join(real, "package.json")),
            "has_git": os.path.isdir(os.path.join(real, ".git")),
            "exists": os.path.isdir(real),
        })
    return projects

def proj_create_symlink(source, base_dir):
    os.makedirs(base_dir, exist_ok=True)
    name = os.path.basename(source); dest = os.path.join(base_dir, name)
    if os.path.exists(dest) or os.path.islink(dest): return False, f"Ya existe: {name}"
    try: os.symlink(source, dest); return True, f"Symlink: {name}"
    except Exception as e: return False, str(e)

def proj_delete(name, base_dir, reg_prefix):
    full = os.path.join(base_dir, name)
    if os.path.islink(full): os.unlink(full)
    elif os.path.isdir(full): shutil.rmtree(full, ignore_errors=True)
    else: return False, f"No existe: {name}"
    registry_del(f"{reg_prefix}.{name}")
    return True, f"Eliminado: {name}"

def proj_import_copy(source, base_dir, reg_prefix):
    os.makedirs(base_dir, exist_ok=True)
    name = os.path.basename(source); dest = os.path.join(base_dir, name)
    if os.path.exists(dest): return False, f"Ya existe: {name}"
    try:
        shutil.copytree(source, dest)
        write_registry(reg_prefix, {name: source})
        return True, f"Importado: {name}"
    except Exception as e: return False, str(e)

def proj_sync(name, base_dir, reg_prefix):
    reg = read_registry(); origin = reg.get(f"{reg_prefix}.{name}", "")
    if not origin: return False, f"{name} sin origen"
    src = os.path.join(base_dir, name)
    if not os.path.isdir(src): return False, f"No existe: {name}"
    try:
        for item in os.listdir(src):
            s, d = os.path.join(src, item), os.path.join(origin, item)
            if os.path.isdir(s):
                if os.path.exists(d): shutil.rmtree(d)
                shutil.copytree(s, d)
            else: shutil.copy2(s, d)
        return True, f"Sincronizado: {name} → {origin}"
    except Exception as e: return False, str(e)

def storage_dirs(path=None):
    base = path or STORAGE_DOWNLOAD
    if not os.path.isdir(base): return []
    dirs = []
    try:
        for n in sorted(os.listdir(base)):
            if os.path.isdir(os.path.join(base, n)):
                dirs.append({"name": n, "path": os.path.join(base, n)})
    except PermissionError: pass
    return dirs

# Helper para acciones de proyectos genéricas
def _handle_project_actions(action, params, base_dir, reg_prefix):
    if action == "projects-list":
        p = proj_list(base_dir, reg_prefix)
        json_ok(projects=p, count=len(p)); return True
    elif action == "projects-create-symlink":
        if not params: json_error("Falta ruta"); return True
        ok, msg = proj_create_symlink(params[0], base_dir)
        json_ok(message=msg) if ok else json_error(msg); return True
    elif action == "projects-delete":
        if not params: json_error("Falta nombre"); return True
        ok, msg = proj_delete(params[0], base_dir, reg_prefix)
        json_ok(message=msg) if ok else json_error(msg); return True
    elif action == "projects-import":
        if not params: json_error("Falta ruta storage"); return True
        ok, msg = proj_import_copy(params[0], base_dir, reg_prefix)
        json_ok(message=msg) if ok else json_error(msg); return True
    elif action == "projects-sync":
        if not params: json_error("Falta nombre"); return True
        ok, msg = proj_sync(params[0], base_dir, reg_prefix)
        json_ok(message=msg) if ok else json_error(msg); return True
    elif action == "projects-sync-all":
        results, ok_n, fail_n = [], 0, 0
        for p in proj_list(base_dir, reg_prefix):
            if not p["origin"]: continue
            ok, msg = proj_sync(p["name"], base_dir, reg_prefix)
            results.append({"name": p["name"], "ok": ok, "message": msg})
            ok_n += 1 if ok else 0; fail_n += 0 if ok else 1
        json_ok(results=results, synced=ok_n, failed=fail_n); return True
    elif action == "projects-origins":
        reg = read_registry()
        origins = {k.replace(f"{reg_prefix}.", ""): v for k, v in reg.items() if k.startswith(f"{reg_prefix}.")}
        json_ok(origins=origins, count=len(origins)); return True
    elif action == "projects-set-origin":
        if len(params) < 2: json_error("Uso: projects-set-origin <nombre> <ruta>"); return True
        write_registry(reg_prefix, {params[0]: params[1]})
        json_ok(message=f"Origen: {params[0]} → {params[1]}"); return True
    elif action == "projects-storage-dirs":
        path = params[0] if params else None
        json_ok(dirs=storage_dirs(path), base=path or STORAGE_DOWNLOAD); return True
    return False  # No manejado

# ════════════════════════════════════════════════════════════
#  cmd_status
# ════════════════════════════════════════════════════════════

def cmd_status(args):
    reg = read_registry()
    m = lambda mid, **kw: {"id": mid, **kw}
    modules = [
        m("ollama", installed=reg.get("ollama.installed")=="true",
          running=tmux_has("ollama-server") or check_port(11434),
          port=11434, version=reg.get("ollama.version","")),
        m("n8n", installed=reg.get("n8n.installed")=="true",
          running=tmux_has("n8n-server") or check_port(5678),
          port=5678, version=reg.get("n8n.version","")),
        m("openclaw", installed=reg.get("openclaw.installed")=="true",
          running=tmux_has("openclaw") or check_port(18789),
          port=18789, version=reg.get("openclaw.version","")),
        m("hermes", installed=reg.get("hermes.installed")=="true",
          running=pgrep_f("hermes.*gateway"), version=reg.get("hermes.version","")),
        m("remote", installed=reg.get("ssh.installed")=="true",
          running=pgrep_x("sshd"), port=8022, version=reg.get("ssh.version","")),
        m("python", installed=reg.get("python.installed")=="true",
          version=reg.get("python.version","")),
        m("claude", installed=reg.get("claude.installed")=="true",
          version=reg.get("claude.version",""), method=reg.get("claude.method","")),
        m("opencode", installed=reg.get("opencode.installed")=="true",
          version=reg.get("opencode.version",""), web_running=tmux_has("opencode")),
        m("expo", installed=reg.get("expo.installed")=="true",
          version=reg.get("expo.version","")),
    ]
    write_cache("status_all", modules)
    json_ok(modules=modules, ip=get_ip(), timestamp=datetime.now().isoformat())

# ════════════════════════════════════════════════════════════
#  cmd_claude
# ════════════════════════════════════════════════════════════

CLAUDE_NATIVE_BIN = os.path.join(HOME,".local","share","claude-code","claude")
CLAUDE_NATIVE_WRAP = os.path.join(HOME,".local","bin","claude")
CLAUDE_LEGACY_WRAP = os.path.join(PREFIX,"bin","claude")

def _claude_detect():
    if os.path.isfile(CLAUDE_NATIVE_BIN) and os.access(CLAUDE_NATIVE_BIN, os.X_OK): return "native"
    rc, nr, _ = run_cmd("npm root -g 2>/dev/null", timeout=5)
    if rc==0 and nr and os.path.isfile(os.path.join(nr,"@anthropic-ai","claude-code","cli.js")): return "legacy"
    if os.path.isfile(CLAUDE_LEGACY_WRAP): return "broken"
    return "none"

def _claude_cli():
    rc, nr, _ = run_cmd("npm root -g 2>/dev/null", timeout=5)
    cands = []
    if rc==0 and nr: cands.append(os.path.join(nr,"@anthropic-ai","claude-code","cli.js"))
    cands.extend([os.path.join(PREFIX,"lib","node_modules","@anthropic-ai","claude-code","cli.js"),
                  os.path.join(HOME,".npm-global","lib","node_modules","@anthropic-ai","claude-code","cli.js")])
    for c in cands:
        if os.path.isfile(c): return c
    return None

def cmd_claude(args):
    if not args: json_error("Falta acción: info, open-cmd, projects-*"); return
    a, p = args[0], args[1:]
    if _handle_project_actions(a, p, PROJECTS_DIR, "project_origin"): return
    if a == "info":
        method = _claude_detect(); reg = read_registry(); ver = reg.get("claude.version","")
        if not ver:
            if method=="native":
                rc,o,_ = run_cmd(f'unset LD_PRELOAD; "{CLAUDE_NATIVE_WRAP}" --version 2>/dev/null', timeout=10)
                if rc==0: m=re.search(r'(\d+\.\d+\.\d+)',o); ver=m.group(1) if m else ""
            elif method=="legacy":
                cli = _claude_cli()
                if cli:
                    rc,o,_ = run_cmd(f'node "{cli}" --version 2>/dev/null', timeout=10)
                    if rc==0: m=re.search(r'(\d+\.\d+\.\d+)',o); ver=m.group(1) if m else ""
        json_ok(method=method, version=ver, installed=method not in ("none","broken"))
    elif a == "open-cmd":
        method = _claude_detect(); project = p[0] if p else None
        if method=="native":
            cmd = f'unset LD_PRELOAD; "{CLAUDE_NATIVE_WRAP}"'
            if project: cmd = f'cd "{project}" && {cmd}'
            json_ok(command=cmd, method="native")
        elif method=="legacy":
            cli = _claude_cli()
            if not cli: json_error("cli.js no encontrado"); return
            cmd = f'DISABLE_AUTOUPDATER=1 DISABLE_UPDATES=1 node "{cli}"'
            if project: cmd = f'cd "{project}" && {cmd}'
            json_ok(command=cmd, method="legacy")
        else: json_error("No instalado", method=method)
    else: json_error(f"Acción desconocida: {a}")

# ════════════════════════════════════════════════════════════
#  cmd_opencode
# ════════════════════════════════════════════════════════════

def cmd_opencode(args):
    if not args: json_error("Falta acción: info, web-start, web-stop, web-url, tui-cmd, projects-*, ollama-config, ollama-remove-config, ollama-models"); return
    a, p = args[0], args[1:]
    if _handle_project_actions(a, p, PROJECTS_DIR, "project_origin"): return
    if a == "info":
        reg = read_registry(); ver = reg.get("opencode.version","")
        if not ver:
            rc,o,_ = run_cmd("opencode --version 2>/dev/null", timeout=10)
            if rc==0: m=re.search(r'(\d+\.\d+\.\d+)',o); ver=m.group(1) if m else ""
        web_on = tmux_has("opencode"); web_url = ""
        if web_on:
            log = os.path.join(HOME,".opencode_web.log")
            if os.path.exists(log):
                _,u,_ = run_cmd(f"grep -o 'http://127\\.0\\.0\\.1:[0-9]*/[^ ]*' '{log}' | head -1", timeout=5)
                web_url = u
        json_ok(version=ver, installed=bool(ver) or reg.get("opencode.installed")=="true",
                web_running=web_on, web_url=web_url)
    elif a == "web-start":
        port = int(p[0]) if p else 3000; session = "opencode"
        log = os.path.join(HOME,".opencode_web.log")
        if tmux_has(session):
            _,u,_ = run_cmd(f"grep -o 'http://127\\.0\\.0\\.1:[0-9]*/[^ ]*' '{log}' | head -1", timeout=5)
            json_ok(message="Ya corriendo", url=u, port=port); return
        run_cmd("pkill -f 'opencode web' 2>/dev/null", timeout=5)
        run_cmd(f"tmux kill-session -t {session} 2>/dev/null", timeout=5)
        run_cmd(f"rm -f '{log}'", timeout=3)
        run_cmd(f'tmux new-session -d -s {session} "opencode web --port {port} --hostname 127.0.0.1 2>&1 | tee {log}"', timeout=10)
        import time; url = ""
        for _ in range(16):
            time.sleep(0.5)
            _,u,_ = run_cmd(f"grep -o 'http://127\\.0\\.0\\.1:[0-9]*/[^ ]*' '{log}' 2>/dev/null | head -1", timeout=3)
            if u: url=u; break
        json_ok(message="Iniciado", url=url, port=port) if tmux_has(session) else json_error("Falló")
    elif a == "web-stop":
        run_cmd("tmux kill-session -t opencode 2>/dev/null", timeout=5)
        run_cmd("pkill -f 'opencode web' 2>/dev/null", timeout=5)
        json_ok(message="Detenido")
    elif a == "web-url":
        log = os.path.join(HOME,".opencode_web.log")
        if os.path.exists(log):
            _,u,_ = run_cmd(f"grep -o 'http://127\\.0\\.0\\.1:[0-9]*/[^ ]*' '{log}' | head -1", timeout=5)
            json_ok(url=u or None, running=tmux_has("opencode"))
        else: json_ok(url=None, running=False)
    elif a == "tui-cmd":
        json_ok(command=f'cd "{p[0] if p else HOME}" && opencode .')
    elif a == "ollama-config":
        if not p: json_error("Falta modelo"); return
        model = p[0]; cd = os.path.join(HOME,".config","opencode"); os.makedirs(cd, exist_ok=True)
        cfg = {"$schema":"https://opencode.ai/config.json","model":f"ollama/{model}",
               "provider":{"ollama":{"npm":"@ai-sdk/openai-compatible","name":"Ollama (local)",
               "options":{"baseURL":"http://127.0.0.1:11434/v1","apiKey":"ollama"},
               "models":{model:{"name":model}}}}}
        try:
            with open(os.path.join(cd,"opencode.json"),"w") as f: json.dump(cfg,f,indent=2); f.write("\n")
            json_ok(message=f"Ollama: {model}", ollama_running=check_port(11434))
        except Exception as e: json_error(str(e))
    elif a == "ollama-remove-config":
        fp = os.path.join(HOME,".config","opencode","opencode.json")
        if os.path.exists(fp): os.remove(fp)
        json_ok(message="Config eliminado")
    elif a == "ollama-models":
        try:
            data = http_get_json("http://127.0.0.1:11434/api/tags")
            json_ok(models=[{"name":m["name"],"size":m.get("size",0)} for m in data.get("models",[])])
        except Exception as e: json_error(f"Ollama no disponible: {e}")
    else: json_error(f"Acción desconocida: {a}")

# ════════════════════════════════════════════════════════════
#  cmd_openclaw — native
# ════════════════════════════════════════════════════════════

def cmd_openclaw(args):
    if not args: json_error("Falta acción: info, gateway-start, gateway-stop, gateway-url, tui-cmd, onboard-cmd, providers-list, workspace-*, ollama-models"); return
    a, p = args[0], args[1:]
    # Workspace actions (ruta ~/.openclaw/workspace/, prefijo openclaw_workspace)
    wa = a.replace("workspace-","projects-") if a.startswith("workspace-") else None
    if wa and _handle_project_actions(wa, p, OPENCLAW_WORKSPACE, "openclaw_workspace"): return
    if a == "info":
        reg = read_registry()
        json_ok(installed=reg.get("openclaw.installed")=="true",
                running=tmux_has("openclaw") or check_port(18789),
                port=18789, version=reg.get("openclaw.version",""),
                location=reg.get("openclaw.location",""))
    elif a == "gateway-start":
        sc = os.path.join(SCRIPTS,"openclaw","openclaw_start.sh")
        if os.path.exists(sc):
            rc,o,_ = run_cmd(f"bash {sc}", timeout=30)
            json_ok(message="Gateway iniciado", port=18789) if check_port(18789) or tmux_has("openclaw") else json_error("Falló",output=o)
        else: json_error("Script start no encontrado")
    elif a == "gateway-stop":
        sc = os.path.join(SCRIPTS,"openclaw","openclaw_stop.sh")
        if os.path.exists(sc): run_cmd(f"bash {sc}", timeout=10)
        else:
            run_cmd("pkill -9 -f openclaw 2>/dev/null", timeout=5)
            run_cmd("tmux kill-session -t openclaw 2>/dev/null", timeout=5)
        json_ok(message="Gateway detenido")
    elif a == "gateway-url":
        log = os.path.join(HOME,"openclaw-logs","runtime.log")
        token = ""
        if os.path.exists(log):
            _,t,_ = run_cmd(f"grep -o 'token=[a-f0-9]*' '{log}' | tail -1 | cut -d= -f2", timeout=5)
            token = t
        url = f"http://localhost:18789/"
        if token: url += f"#token={token}"
        json_ok(url=url, token=token, running=check_port(18789))
    elif a == "tui-cmd":
        json_ok(command="openclaw tui")
    elif a == "onboard-cmd":
        json_ok(command="openclaw onboard")
    elif a == "providers-list":
        # Intenta leer config de openclaw
        cfg_paths = [os.path.join(HOME,".openclaw","config.json"),
                     os.path.join(HOME,".config","openclaw","config.json")]
        for cp in cfg_paths:
            if os.path.exists(cp):
                try:
                    with open(cp) as f: cfg = json.load(f)
                    json_ok(config=cfg); return
                except Exception: pass
        json_ok(config=None, message="Sin config — ejecuta onboard primero")
    elif a == "ollama-models":
        try:
            data = http_get_json("http://127.0.0.1:11434/api/tags")
            json_ok(models=[{"name":m["name"],"size":m.get("size",0)} for m in data.get("models",[])])
        except Exception as e: json_error(f"Ollama no disponible: {e}")
    else: json_error(f"Acción desconocida: {a}")

# ════════════════════════════════════════════════════════════
#  cmd_rootfs — verificar/instalar/actualizar paquetes del rootfs embebido
#  (pedido explícito del usuario, ver docs/humano/humano11.md): sirve tanto si el
#  rootfs se extrajo bien (para detectar actualizaciones disponibles después,
#  algo que solo funciona porque RootfsInstaller.kt instala con "apt install"
#  real — quedan registrados en dpkg, no son archivos sueltos) como si faltó
#  algo o el rootfs no está disponible en absoluto (instala vía pkg normal,
#  mismo camino de siempre). Lee modulos/rootfs_package_list.txt (extraído a
#  $HOME/scripts/), sincronizada con tools/rootfs/package_list.txt.
# ════════════════════════════════════════════════════════════

def _rootfs_package_list():
    path = os.path.join(SCRIPTS, "rootfs_package_list.txt")
    if not os.path.exists(path):
        return []
    names = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            names.append(line)
    return names

def _dpkg_installed(pkg):
    rc, out, _ = run_cmd(f"dpkg -s {shlex.quote(pkg)} 2>/dev/null", timeout=10)
    return rc == 0 and "Status: install ok installed" in out

def _pkg_install(names, timeout=900):
    cmd = ("pkg install -y -o Dpkg::Options::=\"--force-confdef\" "
           "-o Dpkg::Options::=\"--force-confold\" " + " ".join(shlex.quote(n) for n in names))
    return run_cmd(cmd, timeout=timeout)

def _list_upgradable():
    run_cmd("pkg update -y 2>/dev/null", timeout=60)
    rc, out, _ = run_cmd("apt list --upgradable 2>/dev/null", timeout=30)
    upgradable = []
    for line in out.splitlines():
        if "/" not in line or line.lower().startswith("listing"):
            continue
        name = line.split("/", 1)[0].strip()
        if name:
            upgradable.append(name)
    return upgradable

def cmd_rootfs(args):
    if not args: json_error("Falta acción: verify, install-missing, check-updates, update, sync"); return
    a, p = args[0], args[1:]
    packages = _rootfs_package_list()
    if not packages:
        json_error("rootfs_package_list.txt no encontrado en $HOME/scripts/ — reinstala la app o corre kairos.sh de nuevo"); return

    if a == "verify":
        installed, missing = [], []
        for pkg in packages:
            (installed if _dpkg_installed(pkg) else missing).append(pkg)
        json_ok(installed=installed, missing=missing, total=len(packages))

    elif a == "install-missing":
        missing = p or [pkg for pkg in packages if not _dpkg_installed(pkg)]
        if not missing:
            json_ok(message="Nada que instalar", installed=[]); return
        rc, out, err = _pkg_install(missing)
        if rc != 0:
            json_error("Falló instalar paquetes faltantes", output=(out or err)[-500:]); return
        json_ok(message=f"{len(missing)} paquetes instalados", installed=missing)

    elif a == "check-updates":
        upgradable = _list_upgradable()
        json_ok(upgradable=upgradable, count=len(upgradable))

    elif a == "update":
        targets = p or _list_upgradable()
        if not targets:
            json_ok(message="Todo actualizado", updated=[]); return
        rc, out, err = run_cmd(
            "pkg install -y --only-upgrade " + " ".join(shlex.quote(pkg) for pkg in targets), timeout=900)
        if rc != 0:
            json_error("Falló actualizar paquetes", output=(out or err)[-500:]); return
        json_ok(message=f"{len(targets)} paquetes actualizados", updated=targets)

    elif a == "sync":
        # Acción combinada verify -> install-missing -> check-updates -> update,
        # pensada para un solo botón (wizard o Ajustes) con spinner — sin log línea
        # por línea en pantalla, mismo criterio de instalación silenciosa del resto
        # de la app. Si falla instalar lo faltante, aborta (dato crítico); si falla
        # actualizar, solo lo reporta — no es bloqueante para terminar "sync".
        installed, missing = [], []
        for pkg in packages:
            (installed if _dpkg_installed(pkg) else missing).append(pkg)

        installed_now = []
        if missing:
            rc, out, err = _pkg_install(missing)
            if rc != 0:
                json_error("Falló instalar paquetes faltantes", output=(out or err)[-500:], missing=missing); return
            installed_now = missing

        upgradable = _list_upgradable()
        updated_now = []
        update_error = None
        if upgradable:
            rc, out, err = run_cmd(
                "pkg install -y --only-upgrade " + " ".join(shlex.quote(pkg) for pkg in upgradable), timeout=900)
            if rc == 0:
                updated_now = upgradable
            else:
                update_error = (out or err)[-300:]

        json_ok(
            already_installed=len(installed),
            installed_now=installed_now,
            updated_now=updated_now,
            update_error=update_error,
            message=f"{len(installed_now)} instalados, {len(updated_now)} actualizados",
        )

    else:
        json_error(f"Acción desconocida: {a}")

# Módulos migrados a Kotlin nativo (ver docs/humano/humano27.md, ronda 2026-07-31) y
# removidos de acá: system, remote, python, sqlite, expo, ollama, n8n, hermes,
# tunnel, backup, pkg, entorno, pm2 — eran subprocess+file-IO sin necesidad
# real de Python, ahora ProcessBuilder/SQLiteDatabase directo desde los
# Fragments correspondientes. "openclaw" queda (sus acciones workspace-*
# siguen acá, comparten _handle_project_actions con claude/opencode).
MODULES = {
    "status":cmd_status,
    "claude":cmd_claude, "opencode":cmd_opencode,
    "openclaw":cmd_openclaw, "rootfs":cmd_rootfs,
}

def main():
    if len(sys.argv)<2:
        json_error("Uso: python3 kairos_manager.py <módulo> <acción> [args...]",
                   modules=list(MODULES.keys())); sys.exit(1)
    handler = MODULES.get(sys.argv[1])
    if not handler:
        json_error(f"Módulo desconocido: {sys.argv[1]}", modules=list(MODULES.keys())); sys.exit(1)
    try: handler(sys.argv[2:])
    except Exception as e: json_error(f"Error interno: {e}"); sys.exit(1)

if __name__ == "__main__":
    main()
