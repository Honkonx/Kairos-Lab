#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · verificar.sh (silent mode)
#  Verificación EN VIVO de módulos instalados — 6 estrategias.
#
#  USO DESDE APP (KairosApp):
#    bash verificar.sh --silent
#    bash verificar.sh --silent --force
#
#  USO MANUAL (standalone):
#    bash verificar.sh
#    bash verificar.sh <id1> <id2> ...   (solo esos módulos)
#    bash verificar.sh --all             (todos los módulos del registry)
#
#  QUÉ HACE:
#    Lee el registry (~/.android_server_registry) para saber qué módulos declaran
#    installed=true y verifica CADA UNO en vivo con la estrategia que le corresponde.
#    Detectar "instalado en el papel pero binario ausente" (ej. borraron la carpeta
#    o un pkg remove rompió la instalación) es el objetivo — el registry solo dice
#    lo que el instalador escribió al terminar, no la verdad del filesystem ahora.
#
#  6 ESTRATEGIAS (port de core-termux::list.sh, referencia/termux/core-termux-main/
#  core/cli/commands/list.sh — helpers _check_cmd/_check_pkg/_check_dir/_check_file/
#  _check_plugin/_check_grep_config):
#    cmd      → command -v <binario>            (CLIs de npm/instaladores)
#    pkg      → dpkg -s <paquete>               (paquetes pkg/apt de Termux)
#    dir      → test -d <carpeta>               (rootfs, configs, plugins)
#    file     → test -f <archivo>               (wrappers, keystores, tokens)
#    plugin   → test -d <dir> (plugins zsh, ~/.zsh-plugins/<name>)
#    config   → grep -qF <patrón> <archivo>     (configs con línea marcadora)
#
#  OUTPUT (modo --silent):
#    [OK]   <id>  <estrategia> <blanco>     — verificación real OK
#    [WARN] <id>  registry dice installed=true pero la verificación falla
#    [SKIP] <id>  sin estrategia definida (no se puede verificar en vivo)
#    Resumen final: total/ok/warn/skip
#
#  OUTPUT (modo --json, combinable con --silent/--all):
#    Un único objeto JSON en stdout (nada más — sin texto libre mezclado):
#    {"total":N,"ok":N,"warn":N,"skip":N,
#     "modules":[{"id":"ollama","status":"ok","strategy":"cmd","target":"ollama"}, ...]}
#    status ∈ "ok" | "warn" | "skip". Para "skip", strategy/target son "".
#
#  NO MODIFICA NADA: solo lee registry + filesystem. El registry_write solo se
#  usa en el modo --all para repintar installed=true si la verificación da OK
#  (permite "re-endosar" un módulo que quedó en el registry sin binario real).
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.1.0 | Agosto 2026 (C7 humano123 — port de core-termux list.sh,
#  ver docs/humano/humano123.md | v1.1.0: agrega MOD_STRAT para udocker/qemu/
#  repo + los 11 módulos "tools" (typescript/nestjs/prettier/livesrv/
#  localtunnel/vercel/markserv/psqlformat/ncu/ngrok) — todos caían en SKIP
#  porque MOD_STRAT no tenía entrada, aunque core-termux-main::list.sh
#  (referencia/termux/core-termux-main/core/cli/commands/list.sh, _list_npm)
#  ya define el mismo mapeo id→binario que se copia acá; ver auditoría
#  docs/viejo/AUDITORIA_MODULOS_SISTEMA_VS_REFERENCIA_2026-08-19.md)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
ALL=false
JSON=false
ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    --all)      ALL=true ;;
    --json)     JSON=true; SILENT=true ;;
    -h|--help)  HELP=true ;;
    *)          ARGS+=("$1") ;;
  esac
  shift
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"verificar","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Mínimo a propósito (pedido explícito
# de la directiva de esta ronda): verificar.sh es una herramienta de diagnóstico
# transversal — no instala ningún binario/wrapper propio más allá de sí mismo (el
# script que ya vive en scripts/install/, ejecutado directo por VerificarFragment.kt).
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$(dirname "${BASH_SOURCE[0]}")/verificar.sh" \
    --arg verify "test -x \"$(dirname "${BASH_SOURCE[0]}")/verificar.sh\"" \
    '{
      id: "verificar",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-verificar",
      version_registry_key: "verificar.version",
      files: [
        {path: $p1, required: true, note: "El propio script — VerificarFragment.kt lo corre directo, no instala ningún wrapper/binario aparte"}
      ],
      file_globs: [],
      dependencies: [
        {id: "jq", check_cmd: "command -v jq >/dev/null 2>&1", install_hint: "pkg install -y jq"}
      ],
      verify_cmd: $verify,
      patch_cmd: "chmod +x \"" + $p1 + "\" 2>/dev/null || true",
      not_covered: [
        "No hay estado propio que empaquetar más allá del script — es una herramienta de diagnóstico, no un servicio instalado"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_verificar_checkpoint"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^verificar_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "verificar_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^verificar_${1}=done" "$CHECKPOINT" 2>/dev/null; }

if ! $SILENT && ! $JSON; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  🔍 VERIFICACIÓN EN VIVO DE MÓDULOS      ║"
  echo "  ║  cmd · pkg · dir · file · plugin · config ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── 6 estrategias de verificación (port core-termux list.sh) ──
# Todas devuelven 0 (OK) / 1 (falla). Con --silent escriben directo;
# sin --silent usan los colores del lib.sh.
_ver_cmd()     { command -v "$1" &>/dev/null; }
_ver_pkg()     { dpkg -s "$1" 2>/dev/null | grep -q "Status: install ok installed"; }
_ver_dir()     { [ -d "$1" ]; }
_ver_file()    { [ -f "$1" ]; }
_ver_plugin()  { [ -d "$1" ]; }   # core-termux: ~/.zsh-plugins/<name>
_ver_config()  { [ -f "$2" ] && grep -qF "$1" "$2"; }

# ── Tabla módulo → estrategia ────────────────────────────────
# Formato: "id|estrategia|blanco". El blanco de 'config' es "patrón|archivo".
# Basado en qué instala realmente cada modulos/*.sh (verificado 2026-08-13).
declare -A MOD_STRAT
MOD_STRAT[ollama]="cmd|ollama"
MOD_STRAT[n8n]="cmd|n8n"
MOD_STRAT[python]="cmd|python3"
MOD_STRAT[claude]="cmd|claude"
MOD_STRAT[codex]="cmd|codex"
MOD_STRAT[antigravity]="cmd|agy"
MOD_STRAT[openclaw]="cmd|openclaw"
MOD_STRAT[opencode]="cmd|opencode"
MOD_STRAT[hermes]="cmd|hermes"
MOD_STRAT[remote]="cmd|cloudflared"
MOD_STRAT[ssh]="cmd|cloudflared"
# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# el binario real que instala expo.sh es "eas" (EAS CLI, @expo/eas-cli) — "expo" nunca fue el
# comando real, así que este chequeo SIEMPRE reportaba "registry dice installed pero falla"
# aunque el módulo estuviera perfectamente instalado y funcional (confirmado: "eas --version"
# real funciona). Mismo patrón ya corregido para antigravity (línea 161, "agy").
MOD_STRAT[expo]="cmd|eas"
MOD_STRAT[engram]="cmd|engram"
MOD_STRAT[freebuff]="cmd|freebuff"
MOD_STRAT[codebuff]="cmd|codebuff"
MOD_STRAT[copilotcli]="cmd|copilot"
MOD_STRAT[minimaxcli]="cmd|mmx"
MOD_STRAT[mimocode]="cmd|mimo"
MOD_STRAT[mistralvibe]="cmd|vibe"
MOD_STRAT[qwencode]="cmd|qwen"
MOD_STRAT[ciberseguridad]="cmd|nmap"
MOD_STRAT[entorno]="cmd|proot-distro"
MOD_STRAT[db]="cmd|mariadbd"
MOD_STRAT[stacks]="cmd|proot-distro"
MOD_STRAT[udocker]="cmd|udocker"
MOD_STRAT[qemu]="cmd|qemu-x86_64"
MOD_STRAT[repo]="dir|$TERMUX_PREFIX/../kairos-repo"
MOD_STRAT[typescript]="cmd|tsc"
MOD_STRAT[nestjs]="cmd|nest"
MOD_STRAT[prettier]="cmd|prettier"
MOD_STRAT[livesrv]="cmd|live-server"
MOD_STRAT[localtunnel]="cmd|lt"
MOD_STRAT[vercel]="cmd|vercel"
MOD_STRAT[markserv]="cmd|markserv"
MOD_STRAT[psqlformat]="cmd|psqlformat"
MOD_STRAT[ncu]="cmd|ncu"
MOD_STRAT[ngrok]="cmd|ngrok"
MOD_STRAT[llamaserver]="cmd|llama-server"
MOD_STRAT[cactus]="cmd|cactus"
MOD_STRAT[ide]="cmd|nvim"
MOD_STRAT[apk]="cmd|compil-apk-termux"
MOD_STRAT[kimi]="cmd|kimi"
MOD_STRAT[kilo]="cmd|kilo"
MOD_STRAT[cursor]="cmd|cursor-agent"
MOD_STRAT[hf]="cmd|hf"
MOD_STRAT[kairos]="dir|$HOME/kairos"
# Bug real reportado por el usuario 2026-08-25 ("verificar funciona a medias"): codegraph/
# ohmypi/pi son módulos reales agregados 2026-08-17/18 (ver CliToolFragment.kt) con binario
# real confirmado (baseCommand de cada uno) pero nunca se sumaron acá — "Verificar todos los
# módulos" los reportaba SIEMPRE como sin estrategia, aunque estuvieran perfectamente
# instalados. Mismo patrón exacto que el bug ya arreglado de expo/antigravity más arriba.
MOD_STRAT[codegraph]="cmd|codegraph"
MOD_STRAT[ohmypi]="cmd|omp"
MOD_STRAT[pi]="cmd|pi"
# Mismo motivo: mysql/postgres/sqlite/redis (sub-servicios del módulo "db", cada uno con su
# propia clave <id>.installed=true en el registry — ver modulos/db.sh) tampoco tenían
# estrategia — el registry los declara individualmente, así que verificar.sh --all los toma
# como IDs propios, no solo "db" agrupado. Binarios reales confirmados en modulos/db.sh.
MOD_STRAT[mysql]="cmd|mariadbd"
MOD_STRAT[postgres]="cmd|postgres"
MOD_STRAT[sqlite]="cmd|sqlite3"
MOD_STRAT[redis]="cmd|redis-server"

# ── JSON: escapar comillas/backslashes para valores de string ─
_json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

JSON_MODULES=()
_json_record() {
  # $1=id $2=status(ok|warn|skip) $3=strategy $4=target
  JSON_MODULES+=("{\"id\":\"$(_json_escape "$1")\",\"status\":\"$2\",\"strategy\":\"$(_json_escape "$3")\",\"target\":\"$(_json_escape "$4")\"}")
}

# ── Verificar un módulo ───────────────────────────────────────
# $1 = id del módulo. Devuelve 0=OK, 1=WARN (registry dice sí pero falta), 2=SKIP.
verify_one() {
  local id="$1"
  local spec="${MOD_STRAT[$id]}"
  if [ -z "$spec" ]; then
    if $JSON; then _json_record "$id" "skip" "" ""
    elif $SILENT; then echo "[SKIP] $id — sin estrategia definida"
    else warn "SKIP $id — sin estrategia definida"; fi
    return 2
  fi
  local strat="${spec%%|*}"
  local target="${spec#*|}"
  local ok=1
  case "$strat" in
    cmd)    _ver_cmd "$target"; ok=$? ;;
    pkg)    _ver_pkg "$target"; ok=$? ;;
    dir)    _ver_dir "$target"; ok=$? ;;
    file)   _ver_file "$target"; ok=$? ;;
    plugin) _ver_plugin "$target"; ok=$? ;;
    config)
      local pat="${target%%|*}" f="${target#*|}"
      _ver_config "$pat" "$f"; ok=$? ;;
  esac
  if [ $ok -eq 0 ]; then
    if $JSON; then _json_record "$id" "ok" "$strat" "$target"
    elif $SILENT; then echo "[OK]   $id  ($strat: $target)"
    else log "OK $id  ($strat: $target)"; fi
    return 0
  fi
  if $JSON; then _json_record "$id" "warn" "$strat" "$target"
  elif $SILENT; then echo "[WARN] $id  registry dice installed pero falla ($strat: $target)"
  else warn "WARN $id  registry dice installed pero falla ($strat: $target)"; fi
  return 1
}

# ── Reunir IDs a verificar ────────────────────────────────────
IDS=()
collect_installed() {
  if [ -f "$REGISTRY" ]; then
    while IFS= read -r line; do
      local id="${line%%.*}"
      case " ${IDS[*]} " in
        *" $id "*) ;; # ya agregado
        *) IDS+=("$id") ;;
      esac
    done < <(grep "installed=true" "$REGISTRY")
  fi
}

if [ ${#ARGS[@]} -gt 0 ] && ! $ALL; then
  IDS=("${ARGS[@]}")
elif $ALL; then
  # Todos los módulos con installed=true en el registry (lo que la app muestra).
  collect_installed
  # Sin registry, fallback a todos los conocidos.
  [ ${#IDS[@]} -eq 0 ] && IDS=("${!MOD_STRAT[@]}")
else
  # Default: todos los que declaran installed=true; si no hay, todos los conocidos.
  collect_installed
  [ ${#IDS[@]} -eq 0 ] && IDS=("${!MOD_STRAT[@]}")
fi

if [ ${#IDS[@]} -eq 0 ]; then
  if $JSON; then
    echo '{"total":0,"ok":0,"warn":0,"skip":0,"modules":[]}'
  elif $SILENT; then
    echo "[SKIP] nada para verificar (registry vacío y sin argumentos)"
  else
    warn "Nada para verificar"
  fi
  exit 0
fi

# ── Ejecutar ──────────────────────────────────────────────────
if ! $SILENT && ! $JSON; then
  echo ""
  echo "Verificando ${#IDS[@]} módulo(s)..."
  echo ""
fi
OK=0; WARN=0; SKIP=0
for id in "${IDS[@]}"; do
  verify_one "$id"
  case $? in
    0) OK=$((OK+1)) ;;
    1) WARN=$((WARN+1)) ;;
    2) SKIP=$((SKIP+1)) ;;
  esac
done

# ── Resumen ───────────────────────────────────────────────────
if $JSON; then
  IFS=,
  MODULES_JOINED="${JSON_MODULES[*]}"
  unset IFS
  echo "{\"total\":${#IDS[@]},\"ok\":$OK,\"warn\":$WARN,\"skip\":$SKIP,\"modules\":[$MODULES_JOINED]}"
elif $SILENT; then
  echo "[STEP] RESUMEN: $OK ok, $WARN warn, $SKIP skip (${#IDS[@]} total)"
else
  echo ""
  echo "═══════════════════════════════════════════"
  echo -e "${GREEN}$OK OK${NC} · ${YELLOW}$WARN WARN${NC} · ${DIM}$SKIP SKIP${NC} · de ${#IDS[@]}"
fi

# Exit code: 0 si no hay warns; 1 si algo declarado instalado falla en vivo.
[ "$WARN" -gt 0 ] && exit 1
exit 0