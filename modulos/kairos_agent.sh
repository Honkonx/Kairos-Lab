#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · kairos_agent.sh (silent mode)
#  Instala el "Agente Kairos" — v1 honesta, NO "omnipotente" todavía
#  (ver docs/agente-kairos/AGENTE_KAIROS.md para el alcance real).
#
#  Motor 100% stdlib de Python (mismo criterio que kairos_manager.py:
#  "SOLO stdlib · NUNCA import requests · NUNCA /tmp/") — sin venv,
#  sin pip, para que la infraestructura base del agente no dependa de
#  que un `pip install` de red haya salido bien.
#
#  USO DESDE APP (KairosApp):
#    bash kairos_agent.sh --silent
#
#  USO MANUAL (standalone):
#    bash kairos_agent.sh
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#    --describe Manifiesto declarativo (--describe, ver contrato de scripts)
#
#  QUÉ INSTALA:
#    ✅ Motor ~/.kairos_agent/kairos_agent_engine.py (stdlib puro)
#    ✅ SQLite ~/.kairos_agent/kairos_agent.db (catálogo/estado/historial)
#    ✅ Wrapper `kairos-agent` en $PREFIX/bin
#    ✅ Registry actualizado (kairos_agent.*)
#
#  QUÉ NO HACE (honesto, ver docs/agente-kairos/):
#    ❌ No interpreta lenguaje libre sin un LLM disponible (modo sin IA =
#       catálogo de intents fijo, no NLP)
#    ❌ No ejecuta código/shell arbitrario decidido por un LLM — el modo
#       con IA solo puede elegir UNA de las intents conocidas + argumentos
#       validados, nunca un comando libre
#    ❌ No reemplaza al bot de Telegram (TelegramBotService.kt) — ese
#       conector queda para una ronda futura
#
#  REPO: https://github.com/Honkonx/Kairos
#  VERSIÓN: 1.0.0 | Septiembre 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
for arg in "$@"; do
  case "$arg" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
  esac
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"kairos_agent","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"v1 honesta: catalogo+SQLite+5 intents reales sin IA, gancho opcional con IA (llama-server/ollama) limitado a elegir una intent conocida"}
JSON
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_kairos_agent_checkpoint"
AGENT_HOME="$HOME/.kairos_agent"
ENGINE="$AGENT_HOME/kairos_agent_engine.py"
WRAPPER="$TERMUX_PREFIX/bin/kairos-agent"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^kairos_agent_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "kairos_agent_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^kairos_agent_${1}=done" "$CHECKPOINT" 2>/dev/null; }

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◆ AGENTE KAIROS · v1 (sin IA / con IA)  ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
if command -v kairos-agent &>/dev/null && [ -f "$ENGINE" ] && ! $FORCE; then
  log "Agente Kairos ya instalado"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar el Agente Kairos? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Python (si falta) ─────────────────────────────────
step "PASO 1 — Verificando Python"
if check_done "python"; then
  log "Python ya verificado [checkpoint]"
else
  command -v python3 &>/dev/null || {
    info "Instalando python..."
    pkg_update_with_fallback
    pkg install python -y 2>/dev/null || error "No se pudo instalar Python"
  }
  command -v python3 &>/dev/null || error "Python no disponible tras instalación"
  log "Python: $(python3 --version 2>/dev/null)"
  mark_done "python"
fi

# ── PASO 2 — sqlite3 (paquete Termux, no pip) ───────────────────
step "PASO 2 — Verificando sqlite3"
if check_done "sqlite" && python3 -c "import sqlite3" &>/dev/null; then
  log "sqlite3 ya disponible [checkpoint]"
else
  python3 -c "import sqlite3" &>/dev/null || {
    info "Instalando sqlite..."
    pkg_update_with_fallback
    pkg install sqlite -y 2>/dev/null || warn "pkg sqlite falló — python3 puede traer sqlite3 embebido igual"
  }
  python3 -c "import sqlite3" &>/dev/null || error "El módulo sqlite3 de Python no está disponible tras instalar sqlite"
  log "sqlite3 OK"
  mark_done "sqlite"
fi

# ── PASO 3 — Motor del agente ────────────────────────────────
step "PASO 3 — Instalando motor del Agente Kairos"
mkdir -p "$AGENT_HOME"

cat > "$ENGINE" << 'PYEOF'
#!/data/data/com.termux/files/usr/bin/python3
# -*- coding: utf-8 -*-
"""
kairos_agent_engine.py — Agente Kairos v1 (SOLO stdlib).

Base de conocimiento persistente (SQLite) + catálogo de intents fijo
(dispatch real, sin IA) + gancho opcional de interpretación con IA
(local llama-server/ollama, endpoint OpenAI-compatible) que SOLO puede
elegir una intent conocida con argumentos validados — nunca ejecuta
código/shell arbitrario decidido por el LLM. Ver
docs/agente-kairos/AGENTE_KAIROS.md para el alcance honesto de v1 y lo
que queda pendiente para rondas futuras.

TABLAS:
  modules  — catálogo (de $HOME/scripts/modules.json si existe, o
             reconstruido desde $HOME/scripts/install/*.sh --describe)
  state    — último snapshot observado (installed/running/port)
  history  — cada acción real ejecutada por el agente (quién, qué, cuándo,
             resultado) — nunca se borra, solo se agrega

INTENTS (catálogo fijo, ver INTENTS abajo):
  refrescar, procesos, estado, instalar, iniciar, detener, historial

USO:
  kairos-agent refrescar
  kairos-agent procesos
  kairos-agent estado [id]
  kairos-agent instalar <id> [--variant X] [--force]
  kairos-agent iniciar <id>
  kairos-agent detener <id>
  kairos-agent historial [--limit N]
  kairos-agent tools
  kairos-agent interpretar "pedido en lenguaje natural" [--modelo M] [--auto]
  kairos-agent configurar-ia [--url URL] [--modelo M] [--key-stdin] [--quitar]
    (--key-stdin lee la clave de la entrada estándar, nunca como argumento:
    evita que quede en /proc/<pid>/cmdline o en .bash_history; --key K sigue
    existiendo por compatibilidad pero deja la clave expuesta mientras corre)
"""
import json
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.request
from datetime import datetime

HOME = os.environ.get("HOME", "/data/data/com.termux/files/home")
PREFIX = os.environ.get("PREFIX", "/data/data/com.termux/files/usr")
AGENT_HOME = os.path.join(HOME, ".kairos_agent")
DB_PATH = os.path.join(AGENT_HOME, "kairos_agent.db")
CATALOG_JSON = os.path.join(AGENT_HOME, "catalog.json")
# Config de IA externa (url/modelo/key) — archivo propio, chmod 600, NUNCA se
# relee la key hacia afuera (mismo criterio que RemoteManager.kt para claves
# SSH, ver .claude/rules/kairos-secrets-never-revealed.md). Solo se guarda si
# el usuario la da; nunca va al registry de módulos en texto visible.
AI_CONFIG_PATH = os.path.join(AGENT_HOME, "ai_config.json")
REGISTRY = os.path.join(HOME, ".android_server_registry")
MODULES_JSON_CANDIDATES = [
    os.path.join(HOME, "scripts", "modules.json"),  # extraído por KairosBootstrap.kt
]
INSTALL_SCRIPTS_DIR = os.path.join(HOME, "scripts", "install")

os.makedirs(AGENT_HOME, exist_ok=True)

_ID_RE = re.compile(r"^[a-z0-9_]+$")
_VARIANT_RE = re.compile(r"^[a-z0-9_-]+$")

# Tabla de control real por módulo (start/stop/tmux/puerto) — copiada 1:1 de
# ModuleController.kt (getModuleStartScript/getModuleStopInfo/getTmuxSession/
# getModulePort) para los 7 módulos hasSwitch=true que ya tienen ese contrato
# real hoy. Para cualquier otro módulo del catálogo, el agente cae a
# "instalar"/"estado" únicamente (honesto: no inventa un start/stop que la
# propia app tampoco tiene).
MODULE_CONTROL = {
    "ollama": {
        "start": os.path.join(HOME, "scripts", "ollama", "ollama_start.sh"),
        "stop": os.path.join(HOME, "scripts", "ollama", "ollama_stop.sh"),
        "tmux": "ollama-server", "port": 11434,
    },
    "n8n": {
        # n8n tiene 2 variantes reales (udocker/proot) con scripts distintos —
        # se resuelve en runtime leyendo n8n.mode del registry (_n8n_mode()).
        "start_udocker": os.path.join(HOME, "scripts", "n8n-udocker", "start.sh"),
        "stop_udocker": os.path.join(HOME, "scripts", "n8n-udocker", "stop.sh"),
        "start_proot": os.path.join(HOME, "scripts", "n8n", "start_servidor.sh"),
        "stop_proot": os.path.join(HOME, "scripts", "n8n", "stop_servidor.sh"),
        "tmux_udocker": "n8n-udocker", "tmux_proot": "n8n-server", "port": 5678,
    },
    "openclaw": {
        "start": os.path.join(HOME, "scripts", "openclaw", "openclaw_start.sh"),
        "stop": os.path.join(HOME, "scripts", "openclaw", "openclaw_stop.sh"),
        "tmux": "openclaw", "port": 18789,
    },
    "opencode": {
        "start": os.path.join(HOME, "scripts", "opencode", "opencode_start.sh"),
        "stop": os.path.join(HOME, "scripts", "opencode", "opencode_stop.sh"),
        "tmux": "opencode", "port": 3000,
    },
    "remote": {
        "start": os.path.join(HOME, "scripts", "remote", "ssh_start.sh"),
        "stop": os.path.join(HOME, "scripts", "remote", "ssh_stop.sh"),
        "process": "sshd", "port": 8022,
    },
    "llamaserver": {
        "start": os.path.join(HOME, "scripts", "llamaserver", "start.sh"),
        "stop": os.path.join(HOME, "scripts", "llamaserver", "stop.sh"),
        "tmux": "llamaserver", "port": 8085,
    },
    "db": {
        "start": os.path.join(HOME, "scripts", "db", "start.sh"),
        "stop": os.path.join(HOME, "scripts", "db", "stop.sh"),
        "port": 3306,
    },
}

# ════════════════════════════════════════════════════════════
#  UTILS
# ════════════════════════════════════════════════════════════

def _run(cmd, timeout=60):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "").strip(), (r.stderr or "").strip()
    except subprocess.TimeoutExpired:
        return -1, "", "timeout %ss" % timeout
    except Exception as e:
        return -1, "", str(e)


def _valid_id(module_id):
    return bool(module_id) and bool(_ID_RE.match(module_id))


def _load_ai_config():
    """Lee ~/.kairos_agent/ai_config.json. Nunca lanza, nunca imprime su contenido."""
    try:
        with open(AI_CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def _save_ai_config(url=None, model=None, key=None):
    """Mergea y guarda url/modelo/key en AI_CONFIG_PATH con permisos 600. Un
    valor None deja el campo existente tal cual (no lo borra)."""
    cfg = _load_ai_config()
    if url is not None:
        cfg["url"] = url
    if model is not None:
        cfg["model"] = model
    if key is not None:
        cfg["key"] = key
    with open(AI_CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f)
    try:
        os.chmod(AI_CONFIG_PATH, 0o600)
    except Exception:
        pass
    return cfg


def _remove_ai_key():
    """Quita solo la key guardada; url/modelo quedan como estaban."""
    cfg = _load_ai_config()
    cfg.pop("key", None)
    with open(AI_CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f)
    try:
        os.chmod(AI_CONFIG_PATH, 0o600)
    except Exception:
        pass


def _valid_variant(variant):
    return variant is None or bool(_VARIANT_RE.match(variant))


def check_port(port, host="127.0.0.1", timeout=1.0):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(timeout)
        r = s.connect_ex((host, port))
        s.close()
        return r == 0
    except Exception:
        return False


def tmux_has(session):
    rc, _, _ = _run(["tmux", "has-session", "-t", session], timeout=5)
    return rc == 0


def pgrep_x(name):
    rc, _, _ = _run(["pgrep", "-x", name], timeout=5)
    return rc == 0


def has_root():
    """Detección honesta de root — NUNCA asume, solo confirma con `su -c id`
    real (mismo criterio que empirical-verification-before-fix.md: no basta
    con que exista el binario `su`, hay que confirmar que responde)."""
    su_bin = shutil.which("su")
    if not su_bin:
        return False
    rc, out, _ = _run([su_bin, "-c", "id"], timeout=5)
    return rc == 0 and "uid=0" in out


def read_registry():
    reg = {}
    if not os.path.exists(REGISTRY):
        return reg
    try:
        with open(REGISTRY, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if "=" in line and not line.startswith("#"):
                    k, v = line.split("=", 1)
                    reg[k.strip()] = v.strip()
    except Exception:
        pass
    return reg


def _n8n_mode(registry):
    return registry.get("n8n.mode", "proot")


# ════════════════════════════════════════════════════════════
#  BASE DE DATOS (SQLite)
# ════════════════════════════════════════════════════════════

def db_connect():
    conn = sqlite3.connect(DB_PATH)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS modules (
            id TEXT PRIMARY KEY,
            name TEXT, description TEXT, script TEXT, port TEXT,
            tmux_session TEXT, has_switch INTEGER, category TEXT,
            requires_proot INTEGER, source TEXT, updated_at TEXT
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS state (
            module_id TEXT PRIMARY KEY,
            installed INTEGER, running INTEGER, port_open INTEGER,
            version TEXT, checked_at TEXT
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT, actor TEXT, intent TEXT, module_id TEXT,
            args TEXT, success INTEGER, detail TEXT
        )
    """)
    conn.commit()
    return conn


def log_history(conn, actor, intent, module_id, args, success, detail):
    conn.execute(
        "INSERT INTO history (ts, actor, intent, module_id, args, success, detail) VALUES (?,?,?,?,?,?,?)",
        (datetime.now().isoformat(), actor, intent, module_id or "", json.dumps(args, ensure_ascii=False),
         1 if success else 0, (detail or "")[:2000]),
    )
    conn.commit()


# ════════════════════════════════════════════════════════════
#  INGESTA DEL CATALOGO (modules.json real si esta disponible,
#  fallback a `--describe` de cada script instalado en el device)
# ════════════════════════════════════════════════════════════

def _load_modules_json():
    for path in MODULES_JSON_CANDIDATES:
        if os.path.exists(path):
            try:
                with open(path, encoding="utf-8") as f:
                    return json.load(f), path
            except Exception:
                continue
    return None, None


def _describe_installed_scripts():
    """Fallback si modules.json todavía no fue extraído por el bootstrap (APKs
    viejas, o antes del primer arranque post-actualización): reconstruye un
    catálogo mínimo (id + variantes) leyendo el manifiesto --describe de cada
    script real ya presente en ~/scripts/install/ — dato 100% observable en el
    dispositivo, sin inventar campos que no se puedan confirmar (nombre
    legible, puerto, categoría quedan vacíos a propósito en este camino)."""
    out = []
    if not os.path.isdir(INSTALL_SCRIPTS_DIR):
        return out
    for fname in sorted(os.listdir(INSTALL_SCRIPTS_DIR)):
        if not fname.endswith(".sh"):
            continue
        mod_id = fname[:-3]
        script_path = os.path.join(INSTALL_SCRIPTS_DIR, fname)
        rc, stdout, _ = _run(["bash", script_path, "--describe"], timeout=10)
        meta = {}
        if rc == 0 and stdout.strip():
            try:
                meta = json.loads(stdout.strip().splitlines()[-1])
            except Exception:
                meta = {}
        out.append({
            "id": meta.get("id", mod_id),
            "name": mod_id,
            "description": meta.get("note", ""),
            "script": fname,
            "port": "",
            "tmuxSession": "",
            "hasSwitch": mod_id in MODULE_CONTROL,
            "category": "",
            "requiresProot": False,
        })
    return out


def refresh_catalog(conn):
    modules, source_path = _load_modules_json()
    source = "modules.json"
    if modules is None:
        modules = _describe_installed_scripts()
        source = "describe_fallback"

    now = datetime.now().isoformat()
    registry = read_registry()
    conn.execute("DELETE FROM modules")
    written = 0
    for m in modules:
        mod_id = m.get("id", "")
        if not _valid_id(mod_id):
            continue
        conn.execute(
            "INSERT OR REPLACE INTO modules (id,name,description,script,port,tmux_session,has_switch,"
            "category,requires_proot,source,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (mod_id, m.get("name", mod_id), m.get("description", ""), m.get("script", ""),
             str(m.get("port", "")), m.get("tmuxSession", ""), 1 if m.get("hasSwitch") else 0,
             m.get("category", ""), 1 if m.get("requiresProot") else 0, source, now),
        )
        written += 1

        installed = registry.get("%s.installed" % mod_id) == "true"
        version = registry.get("%s.version" % mod_id, "")
        control = MODULE_CONTROL.get(mod_id, {})
        running = False
        port_open = False
        if mod_id == "n8n":
            mode = _n8n_mode(registry)
            session = control.get("tmux_udocker" if mode == "udocker" else "tmux_proot")
        elif "process" in control:
            running = pgrep_x(control["process"])
            session = None
        else:
            session = control.get("tmux")
        if session:
            running = running or tmux_has(session)
        port = control.get("port")
        if port:
            port_open = check_port(int(port))
            running = running or port_open

        conn.execute(
            "INSERT OR REPLACE INTO state (module_id, installed, running, port_open, version, checked_at) "
            "VALUES (?,?,?,?,?,?)",
            (mod_id, 1 if installed else 0, 1 if running else 0, 1 if port_open else 0, version, now),
        )
    conn.commit()

    with open(CATALOG_JSON, "w", encoding="utf-8") as f:
        json.dump({"source": source, "count": written, "generated_at": now}, f, ensure_ascii=False, indent=2)

    return {"source": source, "modules_ingested": written}


# ════════════════════════════════════════════════════════════
#  INTENTS (catalogo fijo — dispatch real, sin IA)
# ════════════════════════════════════════════════════════════

def intent_refrescar(conn, args, actor):
    result = refresh_catalog(conn)
    log_history(conn, actor, "refrescar", None, args, True, json.dumps(result, ensure_ascii=False))
    return {"ok": True, **result}


def intent_procesos(conn, args, actor):
    root = has_root()
    if root:
        rc, out, err = _run(["su", "-c", "ps -ef"], timeout=15)
    else:
        rc, out, err = _run(["ps", "-ef"], timeout=15)
        if rc != 0:
            rc, out, err = _run(["ps", "aux"], timeout=15)
    procesos = []
    for line in out.splitlines()[1:] if rc == 0 else []:
        parts = line.split(None, 7)
        if len(parts) >= 8:
            procesos.append({"pid": parts[1], "cmd": parts[-1]})
    result = {"ok": rc == 0, "root": root, "count": len(procesos), "procesos": procesos[:200]}
    if rc != 0:
        result["error"] = err or "no se pudo listar procesos"
    log_history(conn, actor, "procesos", None, args, rc == 0, "root=%s count=%d" % (root, len(procesos)))
    return result


def intent_estado(conn, args, actor):
    module_id = args.get("id")
    cur = conn.cursor()
    if module_id:
        if not _valid_id(module_id):
            return {"ok": False, "error": "id de módulo inválido"}
        cur.execute(
            "SELECT m.id,m.name,m.description,m.port,m.tmux_session,m.has_switch,"
            "s.installed,s.running,s.port_open,s.version,s.checked_at "
            "FROM modules m LEFT JOIN state s ON m.id=s.module_id WHERE m.id=?",
            (module_id,),
        )
        row = cur.fetchone()
        if not row:
            log_history(conn, actor, "estado", module_id, args, False, "módulo no encontrado en catálogo")
            return {"ok": False, "error": "módulo '%s' no está en el catálogo — corré 'refrescar' primero" % module_id}
        keys = ["id", "name", "description", "port", "tmux_session", "has_switch",
                "installed", "running", "port_open", "version", "checked_at"]
        data = dict(zip(keys, row))
        log_history(conn, actor, "estado", module_id, args, True, "")
        return {"ok": True, "modulo": data}

    cur.execute(
        "SELECT m.id,m.name,s.installed,s.running,s.version "
        "FROM modules m LEFT JOIN state s ON m.id=s.module_id ORDER BY m.id"
    )
    rows = [dict(zip(["id", "name", "installed", "running", "version"], r)) for r in cur.fetchall()]
    log_history(conn, actor, "estado", None, args, True, "count=%d" % len(rows))
    return {"ok": True, "modulos": rows, "count": len(rows)}


def intent_instalar(conn, args, actor):
    module_id = args.get("id")
    variant = args.get("variant")
    force = bool(args.get("force"))
    if not _valid_id(module_id):
        return {"ok": False, "error": "id de módulo inválido"}
    if not _valid_variant(variant):
        return {"ok": False, "error": "variante inválida"}

    script = os.path.join(INSTALL_SCRIPTS_DIR, "%s.sh" % module_id)
    if not os.path.isfile(script):
        log_history(conn, actor, "instalar", module_id, args, False, "script no encontrado: %s" % script)
        return {"ok": False, "error": "no existe %s — el módulo no está en este catálogo o el APK no lo extrajo" % script}

    cmd = ["bash", script, "--silent"]
    if variant:
        cmd += ["--variant", variant]
    if force:
        cmd += ["--force"]

    rc, out, err = _run(cmd, timeout=1800)  # instalaciones reales pueden tardar varios minutos
    ok = rc == 0
    detail = (out or "") + ("\n" + err if err else "")
    log_history(conn, actor, "instalar", module_id, args, ok, detail[-1500:])
    if ok:
        refresh_catalog(conn)
    return {"ok": ok, "exit_code": rc, "output_tail": detail[-1500:]}


def _resolve_start_stop(module_id):
    control = MODULE_CONTROL.get(module_id)
    if not control:
        return None, None, None
    registry = read_registry()
    if module_id == "n8n":
        mode = _n8n_mode(registry)
        if mode == "udocker":
            return control["start_udocker"], control["stop_udocker"], control["tmux_udocker"]
        return control["start_proot"], control["stop_proot"], control["tmux_proot"]
    return control.get("start"), control.get("stop"), control.get("tmux")


def intent_iniciar(conn, args, actor):
    module_id = args.get("id")
    if not _valid_id(module_id):
        return {"ok": False, "error": "id de módulo inválido"}
    start_script, _stop, _tmux = _resolve_start_stop(module_id)
    if not start_script:
        log_history(conn, actor, "iniciar", module_id, args, False, "sin script de arranque conocido")
        return {"ok": False, "error": "'%s' no tiene un script de arranque conocido (solo: %s)" %
                (module_id, ", ".join(sorted(MODULE_CONTROL)))}
    if not os.path.isfile(start_script):
        log_history(conn, actor, "iniciar", module_id, args, False, "script no encontrado: %s" % start_script)
        return {"ok": False, "error": "script de arranque no existe todavía — instalá el módulo primero: %s" % start_script}

    rc, out, err = _run(["bash", start_script], timeout=180)
    ok = rc == 0
    detail = (out or "") + ("\n" + err if err else "")
    log_history(conn, actor, "iniciar", module_id, args, ok, detail[-1000:])
    refresh_catalog(conn)
    return {"ok": ok, "exit_code": rc, "output_tail": detail[-1000:]}


def intent_detener(conn, args, actor):
    module_id = args.get("id")
    if not _valid_id(module_id):
        return {"ok": False, "error": "id de módulo inválido"}
    _start, stop_script, tmux_session = _resolve_start_stop(module_id)
    control = MODULE_CONTROL.get(module_id, {})
    process_name = control.get("process")

    if stop_script and os.path.isfile(stop_script):
        rc, out, err = _run(["bash", stop_script], timeout=60)
        ok = rc == 0
        detail = (out or "") + ("\n" + err if err else "")
    elif tmux_session and tmux_has(tmux_session):
        rc, out, err = _run(["tmux", "kill-session", "-t", tmux_session], timeout=15)
        ok = rc == 0
        detail = out + err
    elif process_name and pgrep_x(process_name):
        rc, out, err = _run(["pkill", "-x", process_name], timeout=15)
        ok = rc == 0
        detail = out + err
    else:
        log_history(conn, actor, "detener", module_id, args, False, "sin mecanismo de detención conocido o no está corriendo")
        return {"ok": False, "error": "'%s' no tiene un mecanismo de detención conocido, o no está corriendo" % module_id}

    log_history(conn, actor, "detener", module_id, args, ok, detail[-1000:])
    refresh_catalog(conn)
    return {"ok": ok, "output_tail": detail[-1000:]}


def intent_historial(conn, args, actor):
    limit = int(args.get("limit", 20) or 20)
    limit = max(1, min(limit, 200))
    cur = conn.cursor()
    cur.execute(
        "SELECT ts, actor, intent, module_id, success, detail FROM history ORDER BY id DESC LIMIT ?",
        (limit,),
    )
    rows = [dict(zip(["ts", "actor", "intent", "module_id", "success", "detail"], r)) for r in cur.fetchall()]
    return {"ok": True, "historial": rows, "count": len(rows)}


# Catálogo fijo de intents — es la ÚNICA superficie que el modo con IA puede
# invocar (ver interpret_with_ai abajo): el LLM elige un "intent" de esta
# lista + argumentos, nunca corre un comando arbitrario. Documenta también
# qué argumentos espera cada uno.
INTENTS = {
    "refrescar": {"fn": intent_refrescar, "args": {}, "mutates": False,
                  "desc": "Reconstruye el catálogo (modules.json o --describe) + estado observado en el dispositivo"},
    "procesos": {"fn": intent_procesos, "args": {}, "mutates": False,
                 "desc": "Lista procesos corriendo (con root si está disponible, sin root si no)"},
    "estado": {"fn": intent_estado, "args": {"id": "id de módulo (opcional, vacío = todos)"}, "mutates": False,
               "desc": "Estado instalado/corriendo/versión de un módulo o de todos"},
    "instalar": {"fn": intent_instalar, "args": {"id": "id de módulo", "variant": "variante (opcional)", "force": "bool (opcional)"},
                 "mutates": True, "desc": "Instala un módulo real (bash <id>.sh --silent [--variant X] [--force])"},
    "iniciar": {"fn": intent_iniciar, "args": {"id": "id de módulo (solo: %s)" % ", ".join(sorted(MODULE_CONTROL))},
                "mutates": True, "desc": "Arranca el servicio de un módulo ya instalado"},
    "detener": {"fn": intent_detener, "args": {"id": "id de módulo"}, "mutates": True,
                "desc": "Detiene el servicio de un módulo corriendo (tmux/proceso)"},
    "historial": {"fn": intent_historial, "args": {"limit": "int (opcional, default 20)"}, "mutates": False,
                  "desc": "Últimas acciones ejecutadas por el agente (auditoría)"},
}


def dispatch(intent_name, args, actor="cli"):
    conn = db_connect()
    spec = INTENTS.get(intent_name)
    if not spec:
        return {"ok": False, "error": "intent desconocida: %s (disponibles: %s)" %
                (intent_name, ", ".join(sorted(INTENTS)))}
    try:
        return spec["fn"](conn, args or {}, actor)
    finally:
        conn.close()


# ════════════════════════════════════════════════════════════
#  GANCHO OPCIONAL CON IA — solo elige una intent conocida, nunca
#  ejecuta código libre. Mismo espíritu de superficie acotada que
#  ActionApprovalGate/HardlineShellGuard del bot de Telegram (ver
#  docs/agente-kairos/AGENTE_KAIROS.md — esa clase la está tocando otro
#  agente en paralelo, así que acá se reimplementa el mismo PRINCIPIO
#  -no el código- de forma independiente y mínima).
# ════════════════════════════════════════════════════════════

def _reasoner_endpoints():
    base = os.environ.get("KAIROS_AGENT_LLM_URL", "") or _load_ai_config().get("url", "")
    cands = []
    if base:
        cands.append(base)
    cands += [
        "http://127.0.0.1:8085/v1/chat/completions",   # llama-server embebido (módulo llamaserver)
        "http://127.0.0.1:11434/v1/chat/completions",  # Ollama
    ]
    return cands


def _reasoner_model_default():
    return os.environ.get("KAIROS_AGENT_LLM_MODEL", "") or _load_ai_config().get("model") or "qwen2.5:1.5b"


def _reasoner_key():
    """Key opcional para una API externa OpenAI-compatible. Entorno gana sobre
    lo guardado en disco. NUNCA se imprime/loguea en ningún lado — solo viaja
    en la cabecera Authorization de la petición saliente."""
    return os.environ.get("KAIROS_AGENT_LLM_KEY", "") or _load_ai_config().get("key", "")


def interpret_with_ai(pedido, model=None):
    """Le pide al razonador local (o a la URL de KAIROS_AGENT_LLM_URL/
    configurar-ia, que puede ser una API externa OpenAI-compatible configurada
    por el usuario, con o sin api key) que devuelva SOLO un JSON
    {"intent": "...", "args": {...}}. Nunca se interpreta ni ejecuta nada más
    de la respuesta del modelo — si no matchea una intent conocida o el JSON
    es inválido, se rechaza."""
    catalogo = {name: {"desc": spec["desc"], "args": spec["args"]} for name, spec in INTENTS.items()}
    system_prompt = (
        "Sos el router del Agente Kairos. Recibís un pedido en lenguaje natural y devolvés "
        "EXCLUSIVAMENTE un JSON de una sola línea con esta forma: "
        '{"intent": "<uno de %s>", "args": {...}}. '
        "No agregues texto fuera del JSON. No inventes intents nuevas. Catálogo real: %s"
        % (", ".join(sorted(INTENTS)), json.dumps(catalogo, ensure_ascii=False))
    )
    payload = {
        "model": model or _reasoner_model_default(),
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": pedido},
        ],
        "temperature": 0.0,
        "max_tokens": 200,
    }
    headers = {"Content-Type": "application/json"}
    key = _reasoner_key()
    if key:
        headers["Authorization"] = "Bearer %s" % key
    for url in _reasoner_endpoints():
        try:
            req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers)
            with urllib.request.urlopen(req, timeout=25) as resp:
                data = json.loads(resp.read().decode())
                content = data["choices"][0]["message"]["content"].strip()
                match = re.search(r"\{.*\}", content, re.DOTALL)
                if not match:
                    continue
                parsed = json.loads(match.group(0))
                intent_name = parsed.get("intent")
                args = parsed.get("args") or {}
                if intent_name in INTENTS and isinstance(args, dict):
                    return intent_name, args, url
        except Exception:
            continue
    return None, None, None


def chat_directo(mensaje, historial=None, model=None):
    """Conversación cruda con el LLM configurado (mismos endpoints que interpret_with_ai:
    llama-server/Ollama local o la API externa de configurar-ia), SIN pasar por el catálogo
    de intents: nunca elige ni ejecuta una intent, solo reenvía el texto y devuelve la respuesta
    del modelo tal cual. Es el "chat con IA" directo del gateway (ai.chat) — distinto de
    interpret_with_ai()/dispatch() (el "chat con el Agente", agent.ask, acotado al catálogo fijo)
    y del futuro bot de Telegram (todavía sin empezar, ver
    docs/arquitectura/PLAN_MAESTRO_GATEWAY_HOMELAB_AGENTE_2026-10-10.md)."""
    mensajes = [{
        "role": "system",
        "content": "Sos un asistente conversacional de Kairos. Respondé en español, de forma breve y directa. "
                    "No tenés acceso a ejecutar comandos ni a cambiar nada del dispositivo: esto es solo una conversación.",
    }]
    for turno in (historial or []):
        if not isinstance(turno, dict):
            continue
        role = turno.get("role")
        content = turno.get("content")
        if role in ("user", "assistant") and isinstance(content, str) and content.strip():
            mensajes.append({"role": role, "content": content[:4000]})
    mensajes.append({"role": "user", "content": mensaje[:4000]})
    payload = {
        "model": model or _reasoner_model_default(),
        "messages": mensajes,
        "temperature": 0.7,
        "max_tokens": 500,
    }
    headers = {"Content-Type": "application/json"}
    key = _reasoner_key()
    if key:
        headers["Authorization"] = "Bearer %s" % key
    for url in _reasoner_endpoints():
        try:
            req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers)
            with urllib.request.urlopen(req, timeout=40) as resp:
                data = json.loads(resp.read().decode())
                content = (data["choices"][0]["message"]["content"] or "").strip()
                if content:
                    return content, url
        except Exception:
            continue
    return None, None


def cmd_chat_directo(argv):
    """CLI de `chat_directo()`: `kairos-agent chat-directo "mensaje" [--modelo M] [--historial-stdin]`.
    `--historial-stdin` lee de stdin un JSON `[{"role": "user"|"assistant", "content": "..."}]` con
    los turnos previos (opcional — sin esto es un solo mensaje, sin memoria de conversación)."""
    historial = None
    parts = []
    model = None
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--modelo":
            i += 1
            model = argv[i] if i < len(argv) else None
        elif a == "--historial-stdin":
            try:
                parsed = json.loads(sys.stdin.read())
                historial = parsed if isinstance(parsed, list) else None
            except Exception:
                historial = None
        else:
            parts.append(a)
        i += 1
    mensaje = " ".join(parts).strip()
    if not mensaje:
        print(json.dumps({"ok": False, "error": 'uso: kairos-agent chat-directo "mensaje" [--modelo M] [--historial-stdin]'}, ensure_ascii=False))
        return
    respuesta, endpoint = chat_directo(mensaje, historial, model)
    conn = db_connect()
    try:
        log_history(conn, "cli", "chat", "", {"mensaje": mensaje[:200]}, respuesta is not None, (respuesta or "sin respuesta")[:500])
    finally:
        conn.close()
    if respuesta is None:
        print(json.dumps({"ok": False, "error": "no hay un modelo de IA disponible (local u configurado)"}, ensure_ascii=False))
        return
    print(json.dumps({"ok": True, "reply": respuesta, "reasoner": endpoint}, ensure_ascii=False))


def cmd_interpretar(argv):
    if not argv:
        print(json.dumps({"ok": False, "error": "uso: kairos-agent interpretar \"pedido\" [--modelo M] [--auto]"}, ensure_ascii=False))
        return
    model = None
    auto = False
    parts = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--modelo":
            i += 1
            model = argv[i] if i < len(argv) else None
        elif a == "--auto":
            auto = True
        else:
            parts.append(a)
        i += 1
    pedido = " ".join(parts)
    intent_name, args, endpoint = interpret_with_ai(pedido, model)
    if not intent_name:
        print(json.dumps({"ok": False, "error": "sin razonador disponible o respuesta no interpretable — usa un intent directo (kairos-agent tools)"}, ensure_ascii=False))
        return
    spec = INTENTS[intent_name]
    if spec["mutates"] and not auto:
        print(json.dumps({
            "ok": True, "pending_confirmation": True, "intent": intent_name, "args": args,
            "reasoner": endpoint,
            "message": "El razonador eligió '%s' con args %s — re-ejecutá con --auto para confirmar, o corré la intent directo." % (intent_name, args),
        }, ensure_ascii=False))
        return
    result = dispatch(intent_name, args, actor="ai:%s" % (model or "default"))
    print(json.dumps({"intent_elegida": intent_name, "args": args, "reasoner": endpoint, "resultado": result}, ensure_ascii=False))


def cmd_configurar_ia(argv):
    """Guarda/quita la config de la API de IA externa (url/modelo/key) para no
    tener que exportar KAIROS_AGENT_LLM_* a mano cada vez. La key NUNCA se
    imprime de vuelta — solo se confirma si quedó configurada o no.

    Revisión de seguridad real: --key <valor> deja la clave visible
    en /proc/<pid>/cmdline mientras el proceso corre (todos los procesos de
    Termux comparten el mismo usuario) y, si se escribe a mano en una
    terminal, queda en .bash_history. Se agrega --key-stdin (lee una línea de
    stdin) como vía segura — la app de Kairos SIEMPRE usa esta. --key se deja
    solo por compatibilidad para quien prefiera escribirlo a mano a pesar del
    riesgo; el --help lo advierte."""
    url = None
    model = None
    key = None
    key_stdin = False
    quitar = False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--url":
            i += 1
            url = argv[i] if i < len(argv) else None
        elif a == "--modelo":
            i += 1
            model = argv[i] if i < len(argv) else None
        elif a == "--key":
            i += 1
            key = argv[i] if i < len(argv) else None
        elif a == "--key-stdin":
            key_stdin = True
        elif a == "--quitar":
            quitar = True
        i += 1

    if key_stdin:
        key = sys.stdin.readline().rstrip("\n")
        if not key:
            print(json.dumps({"ok": False, "error": "no llegó ninguna clave por stdin"}, ensure_ascii=False))
            return

    if quitar:
        _remove_ai_key()
        print(json.dumps({"ok": True, "message": "Clave de IA externa eliminada."}, ensure_ascii=False))
        return

    if url is None and model is None and key is None:
        cfg = _load_ai_config()
        print(json.dumps({
            "ok": True, "url": cfg.get("url", ""), "modelo": cfg.get("model", ""),
            "key_configurada": bool(cfg.get("key")),
        }, ensure_ascii=False))
        return

    cfg = _save_ai_config(url=url, model=model, key=key)
    print(json.dumps({
        "ok": True, "message": "Configuración de IA guardada.",
        "url": cfg.get("url", ""), "modelo": cfg.get("model", ""),
        "key_configurada": bool(cfg.get("key")),
    }, ensure_ascii=False))


# ════════════════════════════════════════════════════════════
#  CLI
# ════════════════════════════════════════════════════════════

def _parse_kv_args(argv):
    args = {}
    positional = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--variant":
            i += 1
            args["variant"] = argv[i] if i < len(argv) else None
        elif a == "--force":
            args["force"] = True
        elif a == "--limit":
            i += 1
            args["limit"] = argv[i] if i < len(argv) else 20
        else:
            positional.append(a)
        i += 1
    if positional:
        args["id"] = positional[0]
    return args


def main():
    argv = sys.argv[1:]
    if not argv:
        # Auditoría 2026-10-07: "Abrir en terminal" ejecuta kairos-agent sin
        # argumentos; antes salía con código 1 y un JSON de error. Ahora, en una terminal real,
        # muestra la ayuda legible y sale 0; sin TTY (llamado desde la app) mantiene el JSON.
        uso = "uso: kairos-agent <refrescar|procesos|estado|instalar|iniciar|detener|historial|tools|interpretar|chat-directo|configurar-ia> [args]"
        if sys.stdout.isatty():
            print("Agente Kairos — " + uso)
            print("Ejemplos: kairos-agent estado ollama | kairos-agent interpretar \"mostrame los procesos\"")
            sys.exit(0)
        print(json.dumps({"ok": False, "error": uso}, ensure_ascii=False))
        sys.exit(1)

    cmd = argv[0]
    rest = argv[1:]

    if cmd == "tools":
        print(json.dumps({name: {"desc": s["desc"], "args": s["args"], "mutates": s["mutates"]}
                           for name, s in INTENTS.items()}, ensure_ascii=False, indent=2))
        return
    if cmd == "interpretar":
        cmd_interpretar(rest)
        return
    if cmd == "chat-directo":
        cmd_chat_directo(rest)
        return
    if cmd == "configurar-ia":
        cmd_configurar_ia(rest)
        return
    if cmd in INTENTS:
        args = _parse_kv_args(rest)
        result = dispatch(cmd, args, actor="cli")
        print(json.dumps(result, ensure_ascii=False))
        return

    print(json.dumps({"ok": False, "error": "comando desconocido: %s" % cmd}, ensure_ascii=False))
    sys.exit(1)


if __name__ == "__main__":
    main()
PYEOF

chmod +x "$ENGINE"
log "Motor instalado → $ENGINE"

# ── PASO 4 — Wrapper en PATH + primer refresh ───────────────────
step "PASO 4 — Instalando wrapper y refrescando catálogo"
cat > "$WRAPPER" << WRAPEOF
#!/data/data/com.termux/files/usr/bin/bash
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:\$PATH"
exec python3 "$ENGINE" "\$@"
WRAPEOF
chmod +x "$WRAPPER"
log "Wrapper instalado → $WRAPPER"

# Primer refresh (best-effort — no aborta la instalación si falla, el
# catálogo se puede reconstruir después con 'kairos-agent refrescar')
kairos-agent refrescar >/dev/null 2>&1 && log "Catálogo inicial construido" || warn "Refresh inicial falló — corré 'kairos-agent refrescar' manualmente"

# ── Registry ─────────────────────────────────────────────────
registry_install kairos_agent "1.0.0" \
  "engine=$ENGINE" \
  "db=$AGENT_HOME/kairos_agent.db" \
  "intents=refrescar,procesos,estado,instalar,iniciar,detener,historial" \
  "ai_hook=llama-server:8085,ollama:11434,\$KAIROS_AGENT_LLM_URL,\$KAIROS_AGENT_LLM_KEY,kairos-agent configurar-ia"

notify_event "kairos_agent" "install_done" "1.0.0"
log "Agente Kairos instalado — probalo con: kairos-agent estado"
rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  Agente Kairos instalado ✓${NC}"
  echo "  Probá: kairos-agent tools"
  echo "         kairos-agent estado"
  echo "         kairos-agent instalar n8n --variant udocker"
  echo ""
fi

exit 0
