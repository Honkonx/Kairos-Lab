#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · cactus.sh (silent mode)
#  Instala Cactus Needle 2 — motor de tool-calling local en
#  Termux ARM64. Usable CON IA (razonador vía Ollama/llama-server)
#  y SIN IA (modo directo needle → ejecución).
#
#  USO DESDE APP (KairosApp):
#    bash cactus.sh --silent
#    bash cactus.sh --silent --force
#
#  USO MANUAL (standalone):
#    bash cactus.sh
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Python 3 (si falta)
#    ✅ paquete pip cactus-needle (motor + modelo 14MB embebido)
#    ✅ motor ~/scripts/cactus/cactus_engine.py (tools bash/python/json)
#    ✅ wrapper cactus en $PREFIX/bin (CLI: run/ai/tools/status)
#    ✅ Registry actualizado (cactus.*)
#
#  NOTA ARQUITECTURA (x86 vs arm, ver TAREA 4 2026-08-14; causa raíz real
#  confirmada 2026-08-16 leyendo pyproject.toml del repo cactus-needle real
#  — referencia/needle-main/pyproject.toml):
#    cactus-needle NO es Python puro — depende de jax + jaxlib + flax>=0.12.8
#    + optax (además de numpy/huggingface_hub/sentencepiece). jaxlib es una
#    librería compilada (XLA en C++) que en PyPI solo publica wheels con tag
#    "manylinux_*_aarch64" (glibc) — Termux corre sobre Bionic libc (Android),
#    que NO satisface esos wheels manylinux. pip no encuentra NINGÚN jaxlib
#    instalable para esta plataforma y, al intentar backtrackear entre las
#    versiones de jax/jaxlib/flax buscando una combinación instalable, termina
#    en "ResolutionImpossible" en vez de un simple "no matching distribution"
#    (el error real que se ve en el log). Instalar python-numpy nativo antes
#    (PASO 2) sigue siendo válido — evita que pip intente compilar numpy desde
#    source — pero NO resuelve el bloqueo real, que es jaxlib. Por eso el
#    camino pip es estructuralmente inviable en Termux nativo hoy: si el pip
#    install falla, el script ahora reintenta AUTOMÁTICAMENTE con
#    proot-distro/glibc (ver _install_cactus_glibc_fallback más abajo) — antes
#    esto era solo una sugerencia manual en el mensaje de error.
#
#  CLI RESULTANTE (cactus):
#    cactus run  "pedido" [--json-only]  → needle decide tool call y
#                              ejecuta (SIN IA). --json-only: un solo
#                              JSON final en stdout (uso programático,
#                              ej. ChatFragment /run de Kairos)
#    cactus ai   "pedido" [--json-only]  → razonador (Ollama/llama-server)
#                              interpreta primero, luego needle traduce
#                              y ejecuta
#    cactus extract <schema> "texto" [--json-only]  → extracción
#                              estructurada (needle la trata como
#                              tool-calling con una sola tool declarada,
#                              ver README needle-main sección
#                              "Extraction") — schemas: invoice, receipt
#    cactus schemas           → lista los schemas de `extract` disponibles
#    cactus tools            → lista las tools del catálogo
#                              (incluye engram_remember/engram_recall)
#    cactus status           → estado del motor y del razonador
#    cactus serve [--port 8977] → servidor HTTP opt-in (POST /run) para
#                              orquestación externa (ej. n8n) — ver
#                              docs/arquitectura/PROPUESTA_ORQUESTACION_CRUZADA_2026-08-25.md
#                              sección 2. Apagado por defecto, se activa desde
#                              la pantalla de Cactus en la app.
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.4.0 | Agosto 2026 (cactus serve — modo servidor HTTP liviano y
#  opt-in para orquestación con n8n/clientes HTTP externos: POST /run con
#  {mode, query|schema+text} ejecuta needle vía _needle_run_core()/_extract_core()
#  reusados tal cual del CLI, sin duplicar lógica; gateado por token persistido en
#  ~/.cactus_http_token (mismo patrón que NubeServer.kt/.nube_token) y bindeado
#  solo a 127.0.0.1. Instala scripts/cactus/start.sh y stop.sh (tmux, mismo
#  patrón que llamaserver.sh) para que la app prenda/apague el servidor con el
#  switch estándar de módulos hasSwitch — ver CactusFragment.kt.)
#  v1.3.0: cactus extract — mejoras Cactus Needle:
#  agregado modo `extract` de extracción estructurada, confirmado contra
#  README real de referencia/ia/needle-main sección "Extraction" — needle
#  trata la extracción como tool-calling con una única tool declarada, así
#  que los "arguments" de la única llamada posible SON los campos extraídos,
#  garantizado por la gramática y no solo "pedido". Los 2 schemas
#  (invoice/receipt) son los mismos ejemplos reales del README, no
#  inventados.
#  v1.2.0: causa raíz real confirmada — jaxlib (dependencia de cactus-needle)
#  solo publica wheels manylinux/glibc, incompatibles con Bionic libc — el
#  fallback proot-distro/glibc que antes era solo una sugerencia manual en el
#  mensaje de error ahora se intenta AUTOMÁTICAMENTE si el pip nativo falla,
#  con el mismo patrón de auto-reparación en escalones que mistralvibe.sh)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
  esac
  shift
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"cactus","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Cactus tiene 2 runtimes reales
# posibles (registry cactus.runtime=pip|proot, ver nota de arquitectura arriba
# en la cabecera del script) — el motor (cactus_engine.py) y el wrapper
# (bin/cactus) SIEMPRE viven en el host aunque el runtime sea "proot" (el
# wrapper solo delega la ejecución adentro de la distro), así que ambos son
# empaquetables sin importar el runtime activo. Lo que NO se empaqueta es la
# instalación de cactus-needle en sí (pip nativo del host, o el entorno
# proot-distro/glibc completo) — ver not_covered.
if $DESCRIBE_FILES; then
  _cactus_runtime=$(grep '^cactus\.runtime=' "$HOME/.android_server_registry" 2>/dev/null | cut -d= -f2 | tail -1)
  _cactus_runtime="${_cactus_runtime:-pip}"
  jq -n \
    --arg variant "$_cactus_runtime" \
    --arg p1 "$HOME/scripts/cactus/cactus_engine.py" \
    --arg n1 "Motor real (catálogo de tools + modos run/ai/extract) — corre bajo el intérprete python3 del host o de la distro glibc según el runtime activo" \
    --arg p2 "$TERMUX_PREFIX/bin/cactus" \
    --arg n2 "Wrapper — su contenido cambia según el runtime (delega en proot-distro login si runtime=proot, o exec directo si runtime=pip), se regenera igual en el postinst" \
    --arg dep1_check "command -v python3 >/dev/null 2>&1" \
    --arg verify "\"$TERMUX_PREFIX/bin/cactus\" tools >/dev/null 2>&1" \
    --arg patch "chmod +x \"$HOME/scripts/cactus/cactus_engine.py\" \"$TERMUX_PREFIX/bin/cactus\" 2>/dev/null || true" \
    '{
      id: "cactus",
      supports_describe_files: true,
      variant: $variant,
      package_name: "kairos-module-cactus",
      version_registry_key: "cactus.version",
      files: [
        {path: $p1, required: true, note: $n1},
        {path: $p2, required: true, note: $n2}
      ],
      file_globs: [],
      dependencies: [
        {id: "python3", check_cmd: $dep1_check, install_hint: "pkg install -y python"}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "NO empaqueta la instalación real de cactus-needle (el paquete pip en sí, o la distro proot-distro/glibc completa cuando runtime=proot) — jaxlib no publica wheels compatibles con Bionic libc, ver la nota de arquitectura en la cabecera de este script. Reinstalar este .deb en un device que nunca corrió cactus.sh con éxito copia el motor/wrapper pero verify_cmd seguirá fallando porque \"import needle\" no resuelve.",
        "Si runtime=proot, el wrapper regenerado por patch_cmd no recrea el contenido real (que necesita el nombre de la distro embebido) — solo re-chmod. Reinstalar de cero con cactus.sh sigue siendo necesario para el caso proot."
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_cactus_checkpoint"
GLIBC_DISTRO="ubuntu"
# Runtime persistido en el registry entre corridas (pip nativo vs proot/glibc
# fallback) — así un re-run (checkpoint parcial) no vuelve a intentar pip en
# el host cuando ya se sabe que cayó al fallback la vez anterior.
CACTUS_RUNTIME=$(grep '^cactus\.runtime=' "$REGISTRY" 2>/dev/null | cut -d= -f2 | tail -1)
CACTUS_RUNTIME="${CACTUS_RUNTIME:-pip}"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^cactus_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "cactus_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^cactus_${1}=done" "$CHECKPOINT" 2>/dev/null; }

# Chequea si needle importa en el runtime activo (pip nativo o proot/glibc
# fallback) — CACTUS_RUNTIME se lee del registry al inicio del script.
_needle_importa_ya() {
  if [ "$CACTUS_RUNTIME" = "proot" ]; then
    proot-distro login "$GLIBC_DISTRO" --shared-tmp --shared-home -- python3 -c "import needle" 2>/dev/null
  else
    python3 -c "import needle" 2>/dev/null
  fi
}

# ── Detección de arquitectura (informativa) ──
_ARCH=$(uname -m 2>/dev/null || echo "unknown")
[ "$_ARCH" = "aarch64" ] && _ARM64=true || _ARM64=false

# ── Auto-reparación en 2 escalones (mismo patrón que mistralvibe.sh
#    _vibe_repair_charset_normalizer) cuando pip no logra instalar/importar
#    cactus-needle: jaxlib no publica wheels compatibles con Bionic libc
#    (Termux), solo manylinux (glibc) — ver nota de arquitectura arriba. La
#    única forma real de correr cactus-needle en el dispositivo es dentro de
#    una distro proot con glibc de verdad, donde esos wheels SÍ instalan.
_install_cactus_glibc_fallback() {
  info "Intentando fallback automático proot-distro/glibc (distro: $GLIBC_DISTRO)..."

  command -v proot-distro &>/dev/null || {
    info "Instalando proot-distro..."
    pkg_update_with_fallback
    pkg install -y proot-distro 2>/dev/null || { warn "No se pudo instalar proot-distro"; return 1; }
  }

  if ! proot-distro list-installed 2>/dev/null | awk '{print $1}' | grep -qx "$GLIBC_DISTRO"; then
    info "Instalando distro glibc '$GLIBC_DISTRO' (puede tardar varios minutos)..."
    proot-distro install "$GLIBC_DISTRO" 2>/dev/null || { warn "No se pudo instalar la distro $GLIBC_DISTRO"; return 1; }
  else
    log "Distro '$GLIBC_DISTRO' ya instalada"
  fi

  info "Instalando python3-pip + cactus-needle dentro de $GLIBC_DISTRO (glibc real — jaxlib sí tiene wheel acá)..."
  proot-distro login "$GLIBC_DISTRO" --shared-tmp --shared-home -- bash -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y >/dev/null 2>&1 || true
    apt-get install -y python3 python3-pip >/dev/null 2>&1 || true
    python3 -m pip install --break-system-packages cactus-needle >/dev/null 2>&1 || \
      python3 -m pip install cactus-needle >/dev/null 2>&1
  '

  if proot-distro login "$GLIBC_DISTRO" --shared-tmp --shared-home -- python3 -c "import needle" 2>/dev/null; then
    log "cactus-needle importa correctamente dentro de $GLIBC_DISTRO (glibc)"
    CACTUS_RUNTIME="proot"
    return 0
  fi

  warn "cactus-needle tampoco importa dentro de $GLIBC_DISTRO — fallback fallido"
  return 1
}


if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◍ CACTUS NEEDLE 2 · Tool-calling local ║"
  echo "  ║  con IA (Ollama/llama-server) y sin IA   ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
if command -v cactus &>/dev/null && _needle_importa_ya && ! $FORCE; then
  log "cactus-needle ya instalado (runtime=$CACTUS_RUNTIME)"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar cactus-needle? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Python (si falta) ─────────────────────────────────
step "PASO 1 — Verificando Python"
if check_done "python"; then
  log "Python ya verificado [checkpoint]"
else
  PYTHON_PATH=$(command -v python 2>/dev/null || command -v python3 2>/dev/null)
  if [ -n "$PYTHON_PATH" ]; then
    log "Python detectado: $($PYTHON_PATH --version 2>/dev/null)"
  else
    info "Instalando python..."
    pkg_update_with_fallback
    pkg install python -y 2>/dev/null || error "No se pudo instalar Python"
    PYTHON_PATH=$(command -v python3 2>/dev/null)
    command -v python3 &>/dev/null || error "Python no disponible tras instalación"
    log "Python instalado: $(python3 --version)"
  fi
  mark_done "python"
fi

# ── PASO 2 — numpy nativo (pkg) ─────────────────────────────────
# cactus-needle depende de numpy. En Termux con Python muy reciente (3.14 al
# momento de escribir esto) numpy suele no tener todavía wheel publicado en
# PyPI para esa versión de Python — pip intenta compilarlo desde source y
# falla en "Encountered error while generating package metadata" (falta el
# toolchain BLAS/Fortran completo en Termux, no es un problema de pip en sí).
# El paquete nativo `python-numpy` del repo de Termux trae un numpy YA
# compilado para este dispositivo/arquitectura — instalándolo antes, cuando
# pip resuelve la dependencia numpy de cactus-needle la encuentra ya
# satisfecha y no intenta bajar/compilar el wheel de PyPI.
step "PASO 2 — Instalando numpy nativo (pkg, evita compilar vía pip)"
if check_done "numpy_pkg" && python3 -c "import numpy" 2>/dev/null; then
  log "numpy ya instalado [checkpoint]"
else
  info "Ejecutando: pkg install -y python-numpy"
  pkg_update_with_fallback
  pkg install -y python-numpy 2>/dev/null
  if python3 -c "import numpy" 2>/dev/null; then
    log "numpy $(python3 -c 'import numpy; print(numpy.__version__)' 2>/dev/null) instalado (nativo)"
    mark_done "numpy_pkg"
  else
    warn "python-numpy no disponible/no importó — pip intentará resolverlo igual (puede fallar en Python muy reciente)"
  fi
fi

# ── PASO 3 — cactus-needle (pip nativo, con fallback automático a
#    proot-distro/glibc si el pip nativo no puede — ver nota de arquitectura
#    arriba: jaxlib no tiene wheel compatible con Bionic libc) ─────────────
step "PASO 3 — Instalando cactus-needle vía pip"
if check_done "needle_pkg" && _needle_importa_ya; then
  log "cactus-needle ya instalado [checkpoint, runtime=$CACTUS_RUNTIME]"
else
  # En Termux el python del sistema exige --break-system-packages (PEP 668) —
  # mismo flag que ya usa python.sh para todos sus paquetes pip.
  info "Ejecutando: python3 -m pip install --break-system-packages cactus-needle"
  python3 -m pip install --break-system-packages cactus-needle 2>&1 | tail -5
  if python3 -c "import needle" 2>/dev/null; then
    CACTUS_RUNTIME="pip"
    log "cactus-needle $(python3 -c 'import needle; print(getattr(needle, "__version__", "2.x"))' 2>/dev/null) instalado (pip nativo)"
  else
    warn "cactus-needle no importa vía pip nativo — causa conocida: jaxlib no publica wheels compatibles con Bionic libc (Termux), solo manylinux/glibc (ver nota de arquitectura arriba)"
    _install_cactus_glibc_fallback || error "cactus-needle no importa ni por pip nativo ni por el fallback proot-distro/glibc automático — revisá manualmente con 'proot-distro login $GLIBC_DISTRO' y 'python3 -m pip install cactus-needle' dentro del contenedor"
    log "cactus-needle instalado dentro de la distro glibc '$GLIBC_DISTRO' (runtime=proot)"
  fi
  mark_done "needle_pkg"
fi

# ── PASO 4 — Motor cactus_engine.py + wrapper cactus ───────────
step "PASO 4 — Instalando motor y wrapper"
if check_done "engine"; then
  log "Motor cactus ya instalado [checkpoint]"
else
  mkdir -p "$HOME/scripts/cactus"

  cat > "$HOME/scripts/cactus/cactus_engine.py" << 'PYEOF'
#!/data/data/com.termux/files/usr/bin/python3
# -*- coding: utf-8 -*-
"""
cactus_engine.py — motor de Cactus Needle 2 para Kairos.

Catalogo de tools (needle declara el schema, este modulo ejecuta):
  run_bash        - ejecuta un comando bash y devuelve stdout/rc
  run_python      - ejecuta codigo python y devuelve stdout
  read_file       - lee un archivo de texto
  write_file      - escribe un archivo de texto
  list_dir        - lista un directorio
  system_info     - SO, kernel, arquitectura, uptime
  engram_remember - guarda una memoria persistente vía `engram save`
  engram_recall    - busca en la memoria persistente vía `engram search`

DOS MODOS:
  run  : needle decide la tool call directo y ejecuta (SIN razonador).
  ai   : un razonador local (Ollama o llama-server, endpoint OpenAI)
         interpreta el pedido primero y needle traduce a tool calls.
         Si no hay razonador disponible, cae a modo directo.

EXTRACCION ESTRUCTURADA (extract):
  needle trata la extraccion como tool-calling con una sola tool declarada
  (ver README needle-main, seccion "Extraction") — el catalogo del schema
  elegido se declara como unica tool, needle emite una unica llamada cuyos
  "arguments" son los campos extraidos del texto (garantizado por la
  gramatica, no solo "pedido"). EXTRACT_SCHEMAS trae 8 schemas: "invoice" y
  "receipt" son los ejemplos reales del README (seccion "Quickstart"/
  "Extraction"); los otros 6 (purchase_order/quote/business_card/contact/
  meeting_notes/event, agregados 2026-08-25) son casos de uso nuevos sobre
  el mismo mecanismo generico (cualquier schema JSON valido funciona igual),
  organizados por categoria. `cactus schemas` lista los disponibles.

CONTRATO needle:
  respuesta JSON con type=call → function_calls[] {name, arguments};
  type=respond con [] indica fin. confidence calibrada — bajo el umbral
  se pide confirmacion (si hay tty) o se aborta (modo --auto).

USO:
  cactus run     "pedido en lenguaje natural" [--json-only]
  cactus ai      "pedido ..." [--model qwen2.5:1.5b] [--auto] [--json-only]
  cactus extract <schema> "texto a analizar" [--json-only]
  cactus schemas
  cactus tools
  cactus status
  cactus serve   [--port 8977]  # POST /run — ver docs/modulos/CACTUS.md seccion API HTTP

--json-only: suprime los prints intermedios de progreso y emite un
único JSON final en stdout (para consumo programático, ej. ChatFragment
de Kairos). Implica --auto (sin tty disponible no se puede confirmar
baja confianza). Sin este flag, el comportamiento interactivo original
no cambia.
"""
import json
import os
import shlex
import shutil
import subprocess
import sys
import urllib.request

TOOLS = [
    {
        "name": "run_bash",
        "description": "Execute a bash command in Termux and return its stdout and exit code.",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "the full bash command to run"}
            },
            "required": ["command"],
        },
    },
    {
        "name": "run_python",
        "description": "Execute a python3 code snippet and return its stdout.",
        "parameters": {
            "type": "object",
            "properties": {
                "code": {"type": "string", "description": "the python3 code to run"}
            },
            "required": ["code"],
        },
    },
    {
        "name": "read_file",
        "description": "Read a text file and return its contents.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "absolute path of the file"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Write text content to a file (creates or overwrites).",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "absolute path of the file"},
                "content": {"type": "string", "description": "the full text content to write"},
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "list_dir",
        "description": "List the entries of a directory.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "absolute path of the directory"}
            },
            "required": ["path"],
        },
    },
    {
        "name": "system_info",
        "description": "Return OS, kernel, architecture, hostname and uptime.",
        "parameters": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "engram_remember",
        "description": "Save a persistent memory note via Engram (title + content).",
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "short title for the memory"},
                "content": {"type": "string", "description": "the memory content to save"},
                "project": {"type": "string", "description": "optional project name to scope the memory"},
            },
            "required": ["title", "content"],
        },
    },
    {
        "name": "engram_recall",
        "description": "Search saved memories via Engram by query text.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "search text"},
                "project": {"type": "string", "description": "optional project name to scope the search"},
            },
            "required": ["query"],
        },
    },
]

# Schemas para `cactus extract` — extraccion estructurada de texto (needle la trata como
# tool-calling con una unica tool declarada, ver README needle-main seccion "Extraction").
# "invoice" y "receipt" son los ejemplos reales del README ("Quickstart"/"Extraction" — vendor/
# total/due_date y merchant/total/currency/line_items respectivamente). Los 6 restantes se
# agregaron 2026-08-25 (pedido explicito del usuario: "en cactus toca agregar mas plantillas") —
# confirmado en el propio README (linea ~10: "a byte-level grammar compiled from your schemas")
# y seccion "Extraction" (linea ~166) que el mecanismo es generico, CUALQUIER schema JSON valido
# funciona igual que invoice/receipt, no hay una lista cerrada de schemas soportados por el
# motor real — son casos de uso nuevos, no capacidades nuevas del engine. Organizados por
# categoria (agrupador visual real en CactusFragment.kt, prefijo de categoria en cada entrada
# para que la UI arme los grupos sin un mapeo separado que se pueda desincronizar).
EXTRACT_SCHEMAS = {
    "invoice": {
        "name": "invoice",
        "category": "Documentos comerciales",
        "label": "Factura",
        "description": "An invoice or bill shared as text",
        "parameters": {
            "type": "object",
            "properties": {
                "vendor": {"type": "string"},
                "total": {"type": "number"},
                "due_date": {"type": "string", "description": "due date, as written in the source text"},
            },
            "required": ["vendor", "total", "due_date"],
        },
    },
    "receipt": {
        "name": "receipt",
        "category": "Documentos comerciales",
        "label": "Recibo",
        "description": "A purchase receipt shared as text",
        "parameters": {
            "type": "object",
            "properties": {
                "merchant": {"type": "string"},
                "total": {"type": "number"},
                "currency": {"type": "string"},
                "line_items": {"type": "array", "items": {"type": "object"}},
            },
            "required": ["merchant", "total"],
        },
    },
    "purchase_order": {
        "name": "purchase_order",
        "category": "Documentos comerciales",
        "label": "Orden de compra",
        "description": "A purchase order document shared as text",
        "parameters": {
            "type": "object",
            "properties": {
                "vendor": {"type": "string"},
                "po_number": {"type": "string", "description": "purchase order number/reference"},
                "items": {"type": "array", "items": {"type": "object"}},
                "total": {"type": "number"},
            },
            "required": ["vendor", "po_number", "total"],
        },
    },
    "quote": {
        "name": "quote",
        "category": "Documentos comerciales",
        "label": "Presupuesto",
        "description": "A price quote or estimate shared as text",
        "parameters": {
            "type": "object",
            "properties": {
                "client": {"type": "string"},
                "items": {"type": "array", "items": {"type": "object"}},
                "total": {"type": "number"},
                "valid_until": {"type": "string", "description": "quote expiry date, as written in the source text"},
            },
            "required": ["client", "total"],
        },
    },
    "business_card": {
        "name": "business_card",
        "category": "Identificacion y contacto",
        "label": "Tarjeta de presentacion",
        "description": "Text transcribed from a business card",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "company": {"type": "string"},
                "title": {"type": "string", "description": "job title/role"},
                "email": {"type": "string"},
                "phone": {"type": "string"},
            },
            "required": ["name"],
        },
    },
    "contact": {
        "name": "contact",
        "category": "Identificacion y contacto",
        "label": "Contacto (firma/mensaje)",
        "description": "Contact details embedded in free text, e.g. an email signature or a message",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "email": {"type": "string"},
                "phone": {"type": "string"},
                "company": {"type": "string"},
            },
            "required": ["name"],
        },
    },
    "meeting_notes": {
        "name": "meeting_notes",
        "category": "Productividad",
        "label": "Notas de reunion",
        "description": "Notes taken during or after a meeting",
        "parameters": {
            "type": "object",
            "properties": {
                "topic": {"type": "string"},
                "date": {"type": "string", "description": "meeting date, as written in the source text"},
                "attendees": {"type": "array", "items": {"type": "string"}},
                "action_items": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["topic"],
        },
    },
    "event": {
        "name": "event",
        "category": "Productividad",
        "label": "Evento/invitacion",
        "description": "An event invitation or announcement shared as text",
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "date": {"type": "string", "description": "event date, as written in the source text"},
                "location": {"type": "string"},
                "organizer": {"type": "string"},
            },
            "required": ["title", "date"],
        },
    },
}


def _run(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
        out = (r.stdout or "").strip()
        err = (r.stderr or "").strip()
        return {"exit_code": r.returncode, "stdout": out, "stderr": err}
    except subprocess.TimeoutExpired:
        return {"exit_code": -1, "stdout": "", "stderr": "timeout 180s"}
    except Exception as e:
        return {"exit_code": -1, "stdout": "", "stderr": str(e)}


def _normalize_result(result):
    if not isinstance(result, dict):
        return {"exit_code": 0, "stdout": str(result), "stderr": ""}
    if "stdout" in result and "stderr" in result and "exit_code" in result:
        return result
    normalized = dict(result)
    normalized.setdefault("exit_code", 0 if result.get("ok", True) else 1)
    normalized.setdefault("stderr", str(result.get("error", "")) if not result.get("ok", True) else "")
    if "stdout" not in normalized:
        extra = {k: v for k, v in result.items() if k not in ("exit_code", "stderr")}
        normalized["stdout"] = json.dumps(extra, ensure_ascii=False)
    return normalized


def exec_tool(name, args):
    if name == "run_bash":
        return _run(["bash", "-c", args.get("command", "")])
    if name == "run_python":
        return _run(["python3", "-c", args.get("code", "")])
    if name == "read_file":
        path = args.get("path", "")
        try:
            with open(os.path.expanduser(path), "r", encoding="utf-8") as f:
                return {"ok": True, "content": f.read()}
        except Exception as e:
            return {"ok": False, "error": str(e)}
    if name == "write_file":
        path = os.path.expanduser(args.get("path", ""))
        try:
            # Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales
            # reales por ADB): con un nombre de archivo sin directorio (ej. "cactus_ok.txt"),
            # os.path.dirname() devuelve "" — os.makedirs("", exist_ok=True) revienta con
            # "FileNotFoundError: [Errno 2] No such file or directory: ''" en vez de escribir
            # en el directorio actual (el comportamiento esperado para una ruta relativa sin
            # subcarpeta). Confirmado en dispositivo: needle interpretando un pedido simple
            # ("crea un archivo llamado cactus_ok.txt...") ya genera este path.
            parent = os.path.dirname(path)
            if parent:
                os.makedirs(parent, exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(args.get("content", ""))
            return {"ok": True, "path": path}
        except Exception as e:
            return {"ok": False, "error": str(e)}
    if name == "list_dir":
        path = os.path.expanduser(args.get("path", os.path.expanduser("~")))
        try:
            return {"ok": True, "entries": sorted(os.listdir(path))}
        except Exception as e:
            return {"ok": False, "error": str(e)}
    if name == "system_info":
        uname = _run(["uname", "-a"])
        uptime = _run(["uptime"])
        return {"uname": uname.get("stdout", ""), "uptime": uptime.get("stdout", "")}
    if name in ("engram_remember", "engram_recall"):
        if not shutil.which("engram"):
            return {"exit_code": -1, "stdout": "", "stderr": "engram no está instalado"}
        project = args.get("project")
        if name == "engram_remember":
            cmd = ["engram", "save", args.get("title", ""), args.get("content", "")]
        else:
            cmd = ["engram", "search", args.get("query", "")]
        if project:
            cmd += ["--project", project]
        return _run(cmd)
    return {"ok": False, "error": "tool desconocida: %s" % name}


def ask_confirmation(msg):
    try:
        sys.stdout.write(msg + " (s/n): ")
        sys.stdout.flush()
        line = sys.stdin.readline().strip().lower()
        return line in ("s", "si", "y", "yes")
    except Exception:
        return False


def _system_facts():
    """Facts de entorno (contrato needle: `system=` declara hechos, no instrucciones —
    ver README needle-main, seccion "System facts"). Le da a needle fecha real y tipo
    de dispositivo para resolver lenguaje relativo ("mañana", "en una hora") sin que
    el motor tenga que adivinar la fecha actual."""
    import datetime
    date_str = datetime.datetime.now().strftime("%Y-%m-%d %a %H:%M")
    return "date: %s; device: phone" % date_str


def _needle_run_core(query, auto=False, min_confidence=0.45, json_only=False):
    """Nucleo real de needle run/ai — extraido de run_needle() (antes solo imprimia, no
    devolvia nada) para que `cactus serve` (modo HTTP, ver cmd_serve mas abajo) pueda
    reusar EXACTAMENTE la misma logica sin duplicarla, en vez de re-implementarla o
    invocar el CLI como subproceso desde si mismo."""
    import needle as nd

    effective_auto = auto or json_only  # sin tty no hay forma de confirmar baja confianza

    # tool_index_path: con 8 tools declaradas (> 5) needle activa retrieval —
    # solo las 5 tools de mayor score entran al contexto de cada turno (ver
    # README needle-main, seccion "Tool retrieval"). Persistir los embeddings
    # en disco evita re-embeber las 8 tools en cada invocacion de `cactus`
    # (el wrapper es un proceso nuevo por llamada, no un servicio persistente).
    tool_index_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tools.idx")
    agent = nd.Needle(tools=TOOLS, system=_system_facts(), tool_index_path=tool_index_path)
    response = agent.complete(query)
    results = []
    while response.get("type") == "call":
        confidence = float(response.get("confidence", 0.0) or 0.0)
        calls = response.get("function_calls") or []
        if not calls:
            return {"type": "no_call", "confidence": confidence}
        if confidence < min_confidence and not effective_auto:
            if not ask_confirmation(
                "Confianza %.2f por debajo de %.2f — ¿ejecutar de todas formas?" % (confidence, min_confidence)
            ):
                return {"type": "aborted", "reason": "baja confianza", "confidence": confidence}
        step_results = []
        for call in calls:
            name = call.get("name", "")
            arguments = call.get("arguments", {})
            result = _normalize_result(exec_tool(name, arguments))
            step_results.append({"name": name, "arguments": arguments, "result": result})
            if not json_only:
                print("→ %s %s" % (name, json.dumps(arguments, ensure_ascii=False)))
                print(json.dumps(result, ensure_ascii=False))
        results.extend(step_results)
        response = agent.complete(json.dumps([r["result"] for r in step_results], ensure_ascii=False))
    # error/error_code: campos reales del contrato de needle (README needle-main,
    # "response format") que antes se descartaban silenciosamente — si needle
    # devuelve un error no nulo (esquema/gramatica invalido, etc.) el usuario no
    # se enteraba (auditoria referencia/ia externa, 2026-08-19).
    return {"type": response.get("type", "done"), "confidence": response.get("confidence"),
            "results": results, "reasoning": response.get("reasoning", ""),
            "error": response.get("error"), "error_code": response.get("error_code")}


def run_needle(query, auto=False, min_confidence=0.45, json_only=False):
    result = _needle_run_core(query, auto=auto, min_confidence=min_confidence, json_only=json_only)
    print(json.dumps(result, ensure_ascii=False))


def _extract_core(schema_name, text):
    """Nucleo real de `extract` — extraido de run_extract() por el mismo motivo que
    _needle_run_core() arriba (reuso real desde cmd_serve, no duplicacion)."""
    import needle as nd

    schema = EXTRACT_SCHEMAS.get(schema_name)
    if not schema:
        return {"type": "error", "error": "esquema desconocido: %s (opciones: %s)" % (
            schema_name, ", ".join(sorted(EXTRACT_SCHEMAS)))}
    agent = nd.Needle(tools=[schema], system=_system_facts())
    response = agent.complete(text)
    calls = response.get("function_calls") or []
    fields = calls[0].get("arguments", {}) if calls else {}
    return {
        "type": response.get("type", "respond"),
        "schema": schema_name,
        "confidence": response.get("confidence"),
        "fields": fields,
        "reasoning": response.get("reasoning", ""),
    }


def run_extract(schema_name, text, json_only=False):
    """Extraccion estructurada — declara EXTRACT_SCHEMAS[schema_name] como unica tool y
    pasa `text` como query (mismo patron que el ejemplo "receipt" del README needle-main:
    con una sola tool declarada la gramatica admite exactamente una llamada de ese nombre,
    asi que los "arguments" de esa llamada SON los campos extraidos)."""
    result = _extract_core(schema_name, text)
    if not json_only and result.get("type") != "error":
        print("→ extract:%s" % schema_name)
    print(json.dumps(result, ensure_ascii=False))


def reasoner_interpret(pedido, model):
    """Razonador local (Ollama 11434 o llama-server 8085) — interpreta el pedido
    a una instruccion corta y ejecutable que needle pueda traducir."""
    base = os.environ.get("CACTUS_REASONER_URL", "")
    candidates = [
        base,
        "http://127.0.0.1:11434/v1/chat/completions",
        "http://127.0.0.1:8085/v1/chat/completions",
    ]
    if not model:
        model = os.environ.get("CACTUS_REASONER_MODEL", "")
    for url in candidates:
        if not url:
            continue
        try:
            payload = {
                "model": model or "qwen2.5:1.5b",
                "messages": [
                    {"role": "system", "content": "You translate a user request into a concise, unambiguous "
                     "instruction that a tool-calling model can act on. Reply with ONLY the instruction, "
                     "no preamble, no markdown."},
                    {"role": "user", "content": pedido},
                ],
                "temperature": 0.2,
                "max_tokens": 200,
            }
            req = urllib.request.Request(
                url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = json.loads(resp.read().decode())
                return data["choices"][0]["message"]["content"].strip()
        except Exception:
            continue
    return None


SERVE_TOKEN_PATH = os.path.expanduser("~/.cactus_http_token")


def _serve_token():
    """Token de acceso para `cactus serve` — mismo patron que NubeServer.kt (.nube_token):
    se genera una sola vez (secrets, no random) y se persiste, para que la URL/llamada de
    n8n no cambie entre reinicios del servidor. El servidor solo escucha en 127.0.0.1 (ver
    cmd_serve) pero sigue siendo un endpoint que ejecuta bash/python vía needle — el token
    es una segunda capa, no la unica defensa."""
    import secrets
    try:
        if os.path.exists(SERVE_TOKEN_PATH):
            existing = open(SERVE_TOKEN_PATH, "r", encoding="utf-8").read().strip()
            if existing:
                return existing
    except Exception:
        pass
    generated = secrets.token_hex(24)
    try:
        with open(SERVE_TOKEN_PATH, "w", encoding="utf-8") as f:
            f.write(generated)
    except Exception:
        pass
    return generated


def cmd_serve(port):
    """Servidor HTTP MUY liviano y opt-in (ver docs/arquitectura/
    PROPUESTA_ORQUESTACION_CRUZADA_2026-08-25.md seccion 2, opcion "a") — expone
    `POST /run` para que n8n (u otro cliente HTTP) dispare una tarea de Cactus sin pasar
    por el CLI. Usa unicamente `http.server`/`BaseHTTPRequestHandler` de la stdlib de
    Python (sin dependencias nuevas, mismo criterio "sin librerias nuevas" de CLAUDE.md) —
    reusa _needle_run_core()/_extract_core()/reasoner_interpret() tal cual, el motor real
    (needle) no cambia, solo se le agrega esta capa de transporte.

    Body esperado (JSON): {"mode": "directo"|"ia"|"extract", "query": "...", "model": "..."
    (opcional, solo modo ia), "schema": "invoice" (solo modo extract), "text": "..." (solo
    modo extract)}. Mismos 3 modos que ya soporta `cactus` por CLI (run/ai/extract) — este
    endpoint no agrega comportamiento nuevo, solo un transporte HTTP para el mismo motor.
    """
    import http.server
    import socketserver

    token = _serve_token()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass  # silencia el log de acceso por request (ya queda en el log de tmux/stdout del proceso)

        def _reply(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self):
            provided = self.headers.get("X-Cactus-Token", "")
            import hmac
            return hmac.compare_digest(provided, token)

        def do_POST(self):
            if self.path != "/run":
                self._reply(404, {"error": "not found — unico endpoint disponible: POST /run"})
                return
            if not self._authorized():
                self._reply(403, {"error": "token invalido o ausente (header X-Cactus-Token)"})
                return
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length) if length > 0 else b""
            try:
                body = json.loads(raw.decode("utf-8")) if raw else {}
            except Exception as e:
                self._reply(400, {"error": "JSON invalido: %s" % e})
                return
            mode = body.get("mode", "directo")
            try:
                if mode == "extract":
                    schema = body.get("schema", "")
                    text = body.get("text", "")
                    if not schema or not text:
                        self._reply(400, {"error": "faltan 'schema'/'text' para mode=extract"})
                        return
                    result = _extract_core(schema, text)
                else:
                    query = body.get("query", "")
                    if not query:
                        self._reply(400, {"error": "falta 'query'"})
                        return
                    if mode == "ia":
                        interpreted = reasoner_interpret(query, body.get("model"))
                        if interpreted:
                            query = interpreted
                    # sin tty en un handler HTTP — siempre auto/json_only, igual que --json-only por CLI.
                    result = _needle_run_core(query, auto=True, json_only=True)
                self._reply(200, result)
            except Exception as e:
                self._reply(500, {"error": str(e)})

    # 127.0.0.1 nomas — igual criterio que ollama/llama-server: alcanzable desde otros
    # procesos del mismo namespace de red (n8n en udocker/PRoot comparte el del host, ver
    # doc de la propuesta) sin exponerlo a la LAN.
    httpd = socketserver.ThreadingTCPServer(("127.0.0.1", port), Handler)
    httpd.daemon_threads = True
    print("cactus serve — escuchando en http://127.0.0.1:%d/run (token en %s)" % (port, SERVE_TOKEN_PATH))
    httpd.serve_forever()


def cmd_status():
    import needle as nd
    info = {
        "needle": getattr(nd, "__version__", "2.x"),
        "tools": [t["name"] for t in TOOLS],
    }
    reasoner_url = os.environ.get("CACTUS_REASONER_URL", "")
    if not reasoner_url:
        for cand in ("http://127.0.0.1:11434", "http://127.0.0.1:8085"):
            try:
                with urllib.request.urlopen(cand + "/api/tags", timeout=4) as r:
                    if r.status == 200:
                        reasoner_url = cand
                        break
            except Exception:
                continue
    info["reasoner"] = reasoner_url if reasoner_url else "no detectado (usa `cactus ai`)"
    print(json.dumps(info, ensure_ascii=False, indent=2))


def main():
    args = sys.argv[1:]
    if not args:
        print("uso: cactus [run|ai|extract|tools|schemas|status|serve] \"pedido\" [--model M] [--auto] [--json-only]")
        sys.exit(1)
    cmd = args[0]
    if cmd == "tools":
        print(json.dumps(TOOLS, ensure_ascii=False, indent=2))
        return
    if cmd == "schemas":
        print(json.dumps(EXTRACT_SCHEMAS, ensure_ascii=False, indent=2))
        return
    if cmd == "status":
        cmd_status()
        return
    if cmd == "serve":
        rest = args[1:]
        port = int(os.environ.get("CACTUS_SERVE_PORT", "8977"))
        if "--port" in rest:
            idx = rest.index("--port")
            if idx + 1 < len(rest):
                port = int(rest[idx + 1])
        cmd_serve(port)
        return
    if cmd == "extract":
        rest = args[1:]
        json_only = "--json-only" in rest
        rest = [a for a in rest if a != "--json-only"]
        if len(rest) < 2:
            print("uso: cactus extract <schema> \"texto\" [--json-only]  (schemas: %s)"
                  % ", ".join(sorted(EXTRACT_SCHEMAS)))
            sys.exit(1)
        run_extract(rest[0], " ".join(rest[1:]), json_only=json_only)
        return
    if cmd in ("run", "ai"):
        rest = args[1:]
        model = None
        auto = False
        json_only = False
        query_parts = []
        i = 0
        while i < len(rest):
            a = rest[i]
            if a == "--model":
                i += 1
                if i < len(rest):
                    model = rest[i]
            elif a == "--auto":
                auto = True
            elif a == "--json-only":
                json_only = True
            else:
                query_parts.append(a)
            i += 1
        if not query_parts:
            print("falta el pedido: cactus %s \"tu pedido\"" % cmd)
            sys.exit(1)
        query = " ".join(query_parts)
        if cmd == "ai":
            interpreted = reasoner_interpret(query, model)
            if interpreted:
                query = interpreted
            elif not json_only:
                print("[aviso] sin razonador disponible — modo directo")
        run_needle(query, auto=auto, json_only=json_only)
        return
    print("comando desconocido: %s" % cmd)
    sys.exit(1)


if __name__ == "__main__":
    main()
PYEOF

  chmod +x "$HOME/scripts/cactus/cactus_engine.py"

  # Wrapper cactus en PATH (igual que hermes/engram: shim que delega en python3).
  # Con runtime=proot delega en el python3 de la distro glibc (--shared-home
  # expone $HOME/scripts/cactus/cactus_engine.py del host en la misma ruta
  # dentro de la distro — mismo patrón que pdrun en entorno.sh — así el motor
  # no se duplica, solo cambia qué intérprete lo corre).
  if [ "$CACTUS_RUNTIME" = "proot" ]; then
    cat > "$TERMUX_PREFIX/bin/cactus" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
# runtime=proot — cactus-needle corre dentro de la distro glibc '$GLIBC_DISTRO'
# porque jaxlib no tiene wheel compatible con Bionic libc (ver cactus.sh, nota
# de arquitectura). El motor sigue siendo el mismo archivo del host.
exec proot-distro login "$GLIBC_DISTRO" --shared-tmp --shared-home -- \\
  python3 "$HOME/scripts/cactus/cactus_engine.py" "\$@"
WRAPPER
  else
    cat > "$TERMUX_PREFIX/bin/cactus" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:\$PATH"
exec python3 "$HOME/scripts/cactus/cactus_engine.py" "\$@"
WRAPPER
  fi
  chmod +x "$TERMUX_PREFIX/bin/cactus"

  log "Motor + wrapper instalados"
  mark_done "engine"
fi

# ── PASO 5 — Scripts de control del servidor HTTP opt-in (start/stop) ───
# `cactus serve` (PASO 4, cactus_engine.py) es MUY liviano y apagado por defecto —
# estos 2 scripts son lo que la app arranca/detiene con el switch "Activar servidor
# HTTP" de CactusFragment.kt, mismo patrón (tmux + script start/stop + puerto fijo)
# que ya usa llamaserver.sh (ver $HOME/scripts/llamaserver/start.sh|stop.sh) — el
# wrapper "cactus" ya resuelve pip vs proot internamente, así que estos scripts no
# necesitan saber el runtime activo.
step "PASO 5 — Scripts de control del servidor HTTP"
if check_done "serve_scripts"; then
  log "Scripts de servidor HTTP ya instalados [checkpoint]"
else
  mkdir -p "$HOME/scripts/cactus"

  cat > "$HOME/scripts/cactus/start.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
SESSION="cactus-server"
LOG="\$HOME/kairos_logs/cactus_serve.log"
PORT="\${CACTUS_SERVE_PORT:-8977}"
mkdir -p "\$HOME/kairos_logs"

if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] cactus serve ya corriendo en tmux sesión: \$SESSION"
  exit 0
fi

tmux new-session -d -s "\$SESSION" \\
  "'$TERMUX_PREFIX/bin/cactus' serve --port \$PORT > '\$LOG' 2>&1"

sleep 2
if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] cactus serve iniciado en 127.0.0.1:\$PORT"
else
  echo "[ERROR] No se pudo iniciar cactus serve — log en ~/kairos_logs/cactus_serve.log: \$(tail -c 200 "\$LOG" 2>/dev/null)"
  exit 1
fi
SCRIPT
  chmod +x "$HOME/scripts/cactus/start.sh"

  cat > "$HOME/scripts/cactus/stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="cactus-server"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  echo "[OK] cactus serve detenido"
else
  echo "[OK] cactus serve no estaba corriendo"
fi
SCRIPT
  chmod +x "$HOME/scripts/cactus/stop.sh"

  log "Scripts de control del servidor HTTP creados"
  mark_done "serve_scripts"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
if [ "$CACTUS_RUNTIME" = "proot" ]; then
  _VER_FINAL=$(proot-distro login "$GLIBC_DISTRO" --shared-tmp --shared-home -- \
    python3 -c 'import needle; print(getattr(needle, "__version__", "2.x"))' 2>/dev/null || echo "2.x")
else
  _VER_FINAL=$(python3 -c 'import needle; print(getattr(needle, "__version__", "2.x"))' 2>/dev/null || echo "2.x")
fi
_DATE=$(date +%Y-%m-%d)
registry_write cactus \
  "installed=true" \
  "version=${_VER_FINAL}" \
  "arch=${_ARCH}" \
  "engine=cactus_engine.py" \
  "tools=run_bash,run_python,read_file,write_file,list_dir,system_info,engram_remember,engram_recall" \
  "modes=directo,ia,extract" \
  "extract_schemas=invoice,receipt,purchase_order,quote,business_card,contact,meeting_notes,event" \
  "http_server_port=8977" \
  "runtime=${CACTUS_RUNTIME}" \
  "glibc_distro=${GLIBC_DISTRO}" \
  "install_date=${_DATE}"

notify_event "cactus" "install_done" "$_VER_FINAL"
log "cactus-needle instalado — probalo con: cactus run \"lista este directorio\""
rm -f "$CHECKPOINT"
exit 0