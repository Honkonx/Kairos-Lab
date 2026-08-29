#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · expo.sh (silent mode)
#  Instala EAS CLI (Expo Application Services) en Termux
#
#  USO DESDE APP (KairosApp):
#    bash expo.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_expo.sh
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Node.js >= 18 y git (si no están)
#    ✅ eas-cli vía npm
#    ✅ Scripts de control (build/status/submit/push/info)
#    ✅ Aliases en .bashrc
#    ✅ Registry actualizado
#
#  NO HACE:
#    ❌ Login en expo.dev (lo maneja la app después)
#    ❌ Permiso de almacenamiento (lo maneja la app)
#
#  OUTPUT (modo --silent):
#    [STEP] N/6 Descripción
#    [OK] mensaje
#    [ERROR] mensaje (exit 1)
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 2.0.0 | Junio 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
for arg in "$@"; do
  case "$arg" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
  esac
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"expo","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. eas-cli es paquete npm global
# (glob sobre el paquete completo, ruta depende de npm root -g) + 5 scripts
# de control propios (eas_build/status/submit, expo_info, git_push).
if $DESCRIBE_FILES; then
  _df_npm_root=$(npm root -g 2>/dev/null)
  jq -n \
    --arg p1 "$HOME/scripts/expo/eas_build.sh" \
    --arg p2 "$HOME/scripts/expo/eas_status.sh" \
    --arg p3 "$HOME/scripts/expo/eas_submit.sh" \
    --arg p4 "$HOME/scripts/expo/expo_info.sh" \
    --arg p5 "$HOME/scripts/expo/git_push.sh" \
    --arg glob "${_df_npm_root}/eas-cli/**" \
    '{
      id: "expo", supports_describe_files: true, variant: null,
      package_name: "kairos-module-expo",
      version_registry_key: "expo.version",
      files: [
        {path: $p1, required: false, note: "eas build"},
        {path: $p2, required: false, note: "eas build:list / status"},
        {path: $p3, required: false, note: "eas submit"},
        {path: $p4, required: false, note: "info del proyecto Expo actual"},
        {path: $p5, required: false, note: "git push helper"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "paquete npm eas-cli completo — ruta depende de npm root -g del device que empaqueta"}
      ],
      dependencies: [
        {id: "node", check_cmd: "command -v node", install_hint: "pkg install -y nodejs-lts"}
      ],
      verify_cmd: "command -v eas >/dev/null 2>&1",
      patch_cmd: "",
      not_covered: ["El wrapper bin de npm (symlink creado por npm en $PREFIX/bin/eas) no se captura como archivo discreto — solo el paquete completo vía file_globs"]
    }'
  exit 0
fi

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_expo_checkpoint"
EXPO_SCRIPTS="$HOME/scripts/expo"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry() {
  local version="$1"
  registry_install expo "$version" "commands=expo-build,expo-status,expo-submit,expo-push,expo-login,expo-info" "port=none" "location=termux_native"
}

# ── Verificar si ya está instalado ────────────────────────────
if command -v eas &>/dev/null && ! $FORCE; then
  log "EAS CLI ya instalado — $(eas --version 2>/dev/null | head -1)"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: cabecera y confirmación ──────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · Expo / EAS Installer    ║
  ║   React Native Cloud Build · v2.0.0        ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Este script instala:"
  echo "  ▸ Node.js >= 18 y git (si no están)"
  echo "  ▸ eas-cli vía npm (compilación en la nube)"
  echo "  ▸ Scripts de control: build, status, submit, push"
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRMAR < /dev/tty
  [ "$CONFIRMAR" != "s" ] && [ "$CONFIRMAR" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=5

# ============================================================
# PASO 1 — Termux update (solo standalone)
# ============================================================
step "1/$TOTAL_STEPS Verificando Termux"

if [ -n "$ANDROID_SERVER_READY" ]; then
  log "Termux preparado por instalar.sh [skip]"
elif check_done "termux_update"; then
  log "Termux ya actualizado [checkpoint]"
else
  info "Actualizando Termux..."
  MIRRORS=(
    "https://packages.termux.dev/apt/termux-main"
    "https://mirror.accum.se/mirror/termux.dev/apt/termux-main"
  )
  OUT=$(pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1)
  if echo "$OUT" | grep -q "unexpected size\|Mirror sync in progress\|Err:2"; then
    for m in "${MIRRORS[@]}"; do
      echo "deb $m stable main" > "$TERMUX_PREFIX/etc/apt/sources.list"
      OUT=$(pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" 2>&1)
      echo "$OUT" | grep -q "unexpected size\|Mirror sync" || { log "Mirror OK"; break; }
    done
  fi
  # Antes esto quedaba en "log Termux actualizado" siempre, incluso si los 2 mirrors de
  # respaldo también fallaron — un falso positivo que dejaba el checkpoint marcado con el
  # índice de paquetes roto, haciendo fallar en cascada el "pkg install nodejs" del paso 2
  # sin que quedara ningún rastro claro del motivo real.
  if echo "$OUT" | grep -q "unexpected size\|Mirror sync in progress\|Err:2"; then
    warn "pkg update siguió fallando tras probar los mirrors de respaldo — el índice de paquetes puede quedar desactualizado, los pasos siguientes pueden fallar por esto."
  else
    log "Termux actualizado"
  fi
  mark_done "termux_update"
fi

# ============================================================
# PASO 2 — Node.js y git
# ============================================================
step "2/$TOTAL_STEPS Instalando Node.js y git"

if check_done "nodejs_git"; then
  log "Node.js y git ya verificados [checkpoint]"
else
  # Node.js
  if command -v node &>/dev/null; then
    NODE_MAJOR=$(node --version 2>/dev/null | sed 's/v//' | cut -d'.' -f1)
    if [ "$NODE_MAJOR" -ge 18 ] 2>/dev/null; then
      log "Node.js $(node --version) ✓"
    else
      info "Node.js $(node --version) < 18, actualizando..."
      # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md — el
      # fallback de mirrors del PASO 1 de este script no detecta "No mirror
      # or mirror group selected" (solo pkg_update_with_fallback de lib.sh).
      pkg_update_with_fallback
      pkg install nodejs -y \
        -o Dpkg::Options::="--force-confdef" \
        -o Dpkg::Options::="--force-confold"
      # Bug real confirmado — causa raíz de "Expo no se instala" (auditoría 2026-08-05,
      # ver docs/humano65.md/humano66.md): "npm install -g npm" sobreescribe el npm
      # parcheado para Termux (shebang sin /usr/bin/env, que acá no existe) con uno
      # genérico del registry — cualquier npm posterior revienta con "bad interpreter".
      # El npm que trae "pkg install nodejs" ya alcanza.
      log "Node.js actualizado: $(node --version)"
    fi
  else
    info "Instalando Node.js..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install nodejs -y \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" || \
      error "Falló instalación de Node.js"
    log "Node.js $(node --version) instalado"
  fi

  # git
  if command -v git &>/dev/null; then
    log "git $(git --version | cut -d' ' -f3) ✓"
  else
    info "Instalando git..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install git -y \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" || \
      error "Falló instalación de git"
    log "git instalado"
  fi

  mark_done "nodejs_git"
fi

# ============================================================
# PASO 3 — Instalar EAS CLI
# ============================================================
step "3/$TOTAL_STEPS Instalando EAS CLI"

if check_done "eas_install"; then
  log "EAS CLI ya instalado [checkpoint]"
else
  info "Instalando eas-cli desde npm..."
  # Bug real reportado (2026-08-04, ver docs/humano/humano57.md — "expo... da error"): antes
  # esto pipeaba directo a `tail -3`, así que si `npm install` fallaba con un error real más
  # arriba en su output (permisos, memoria, dependencia nativa que no compila en Termux), el
  # log de instalación (~/kairos_logs/install_expo.log) solo veía las últimas 3 líneas —
  # muchas veces genéricas ("npm ERR! A complete log of this run..."), sin el motivo real.
  # Ahora se guarda la salida completa en una variable y solo se descarta si la instalación
  # sí funcionó — si falla, el output completo llega al log para poder diagnosticar de verdad.
  EAS_NPM_OUTPUT=$(npm install -g eas-cli 2>&1)
  echo "$EAS_NPM_OUTPUT" | tail -3
  # Bug real (2026-08-07, ver docs/humano/humano90.md): "npm install -g eas-cli" terminaba
  # bien ("changed 510 packages", sin errores) pero el "command -v eas" de acá abajo fallaba
  # igual, de forma intermitente — mismo patrón de falso negativo ya visto en otros módulos
  # de esta sesión (ollama_binary_works() con reintentos). "hash -r" fuerza a bash a olvidar
  # su tabla interna de rutas ya resueltas antes de cada intento, por si el binario recién
  # creado quedó "invisible" para una resolución cacheada de antes del install.
  EAS_FOUND=false
  for _eas_attempt in 1 2 3; do
    hash -r
    command -v eas &>/dev/null && { EAS_FOUND=true; break; }
    sleep 1
  done
  if ! $EAS_FOUND; then
    warn "Salida completa de 'npm install -g eas-cli':"
    echo "$EAS_NPM_OUTPUT"
    error "EAS CLI no se instaló correctamente"
  fi
  # Bug real confirmado por ADB (docs/humano269.md, auditoría 2026-08-27): expo.sh hace su
  # propio "npm install -g" a mano (no usa install_npm_global() de lib.sh, por la lógica de
  # reintentos/log completo de arriba) y nunca aplicaba fix_npm_shebang_wrapper() — el symlink
  # que npm crea para "eas" no ejecuta directo en este dispositivo (mismo bug ya documentado en
  # lib.sh para openclaw/cursor), así que "command -v eas" pasaba pero verify_binary_installed
  # de más abajo fallaba igual con "eas-cli no ejecuta tras la instalación".
  fix_npm_shebang_wrapper eas eas-cli
  log "EAS CLI $(eas --version 2>/dev/null | head -1)"
  mark_done "eas_install"
fi

# ============================================================
# PASO 4 — Scripts de control
# ============================================================
step "4/$TOTAL_STEPS Creando scripts de control"

if check_done "expo_scripts"; then
  log "Scripts ya creados [checkpoint]"
else
  mkdir -p "$EXPO_SCRIPTS"

  # --- eas_build.sh ---
  cat > "$EXPO_SCRIPTS/eas_build.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# USO: expo-build [ruta_proyecto] [preview|production]
PROYECTO="${1:-$(pwd)}"
PERFIL="${2:-preview}"

echo "[INFO] Proyecto: $PROYECTO"
echo "[INFO] Perfil: $PERFIL"

[ -d "$PROYECTO" ]             || { echo "[ERROR] No existe: $PROYECTO"; exit 1; }
[ -f "$PROYECTO/package.json" ] || { echo "[ERROR] No es un proyecto Expo: $PROYECTO"; exit 1; }

cd "$PROYECTO"

eas whoami &>/dev/null || {
  echo "[ERROR] No estás logueado — ejecuta: expo-login"
  exit 1
}

echo "[OK] Usuario: $(eas whoami)"

[ ! -f "eas.json" ] && {
  echo "[INFO] eas.json no encontrado — configurando proyecto..."
  eas build:configure
}

echo "[INFO] Iniciando build en la nube de Expo..."
eas build --platform android --profile "$PERFIL"
SCRIPT
  chmod +x "$EXPO_SCRIPTS/eas_build.sh"

  # --- eas_status.sh ---
  cat > "$EXPO_SCRIPTS/eas_status.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
eas build:list --platform android --limit 5
SCRIPT
  chmod +x "$EXPO_SCRIPTS/eas_status.sh"

  # --- eas_submit.sh ---
  cat > "$EXPO_SCRIPTS/eas_submit.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
PROYECTO="${1:-$(pwd)}"
cd "$PROYECTO" || { echo "[ERROR] No existe: $PROYECTO"; exit 1; }
eas submit --platform android
SCRIPT
  chmod +x "$EXPO_SCRIPTS/eas_submit.sh"

  # --- git_push.sh ---
  cat > "$EXPO_SCRIPTS/git_push.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# USO: expo-push [ruta_proyecto] ["mensaje del commit"]
PROYECTO="${1:-$(pwd)}"
MENSAJE="${2:-"update: cambios desde Android"}"

cd "$PROYECTO" || { echo "[ERROR] No existe: $PROYECTO"; exit 1; }

git add .
git status --short
git commit -m "$MENSAJE"
git push
echo "[OK] Cambios subidos al repositorio"
SCRIPT
  chmod +x "$EXPO_SCRIPTS/git_push.sh"

  # --- expo_info.sh ---
  cat > "$EXPO_SCRIPTS/expo_info.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
echo "{\"node\":\"$(node --version 2>/dev/null || echo 'none')\",\"npm\":\"$(npm --version 2>/dev/null || echo 'none')\",\"eas\":\"$(eas --version 2>/dev/null | head -1 || echo 'none')\",\"git\":\"$(git --version 2>/dev/null | cut -d' ' -f3 || echo 'none')\",\"user\":\"$(eas whoami 2>/dev/null || echo 'none')\"}"
SCRIPT
  chmod +x "$EXPO_SCRIPTS/expo_info.sh"

  log "Scripts de control creados"
  mark_done "expo_scripts"
fi

# ============================================================
# PASO 5 — Aliases + Registry
# ============================================================
# NOTA: hasta esta versión existía un PASO 5 previo que descargaba
# "watcher.sh" (https://raw.githubusercontent.com/.../scripts/watcher.sh)
# para un mecanismo IPC vía /sdcard/termux_stack/registry pensado para
# la vieja UI React Native de Kairos. Se eliminó porque: (1) esa URL
# devuelve 404 en el repo real hoy (confirmado con
# `curl -sI https://raw.githubusercontent.com/Honkonx/termux-ai-stack/main/scripts/watcher.sh`
# → "404 Not Found"), (2) el archivo watcher.sh no existe en ningún
# lado del repo (ni en termux-ai-stack-dev/ ni en el clon real
# termux-ai-stack/), y (3) `grep` sobre app/src/main/java confirma que
# ningún código Kotlin/Java actual lee termux_stack/registry ni invoca
# watcher.sh — Kairos migró a UI nativa (ver CLAUDE.md, "React Native
# removed"). El paso solo generaba un [WARN] de descarga fallida en
# cada instalación y dejaba 3 aliases (watcher-start/-stop/-status) en
# .bashrc que fallan al usarse porque ~/watcher.sh nunca existe.
step "5/$TOTAL_STEPS Finalizando"

if ! check_done "expo_aliases"; then
  BASHRC="$HOME/.bashrc"
  [ -f "$BASHRC" ] && grep -v "expo-build\|expo-status\|expo-submit\|expo-push\|expo-login\|expo-info\|# Expo" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"

  cat >> "$BASHRC" << 'ALIASES'

# ════════════════════════════════
#  Expo / EAS · aliases
# ════════════════════════════════
alias expo-build='bash ~/scripts/expo/eas_build.sh'
alias expo-status='bash ~/scripts/expo/eas_status.sh'
alias expo-submit='bash ~/scripts/expo/eas_submit.sh'
alias expo-push='bash ~/scripts/expo/git_push.sh'
alias expo-login='eas login'
alias expo-info='bash ~/scripts/expo/expo_info.sh'
ALIASES

  log "Aliases configurados"
  mark_done "expo_aliases"
fi

# Verificación funcional real antes de marcar installed=true (.claude/rules/
# empirical-verification-before-fix.md — expo.sh era uno de los 2 únicos módulos sin ningún
# chequeo --version antes de registry_install, confirmado en la auditoría
# docs/arquitectura/AUDITORIA_CONSISTENCIA_MODULOS_2026-08-26.md § 3). El PASO 3 ya reintenta
# "command -v eas" con hash -r por el falso negativo intermitente de humano90.md, pero nunca
# confirmaba que el binario respondiera de verdad — "--version" es el flag default de
# verify_binary_installed(), eas-cli lo soporta sin problema.
verify_binary_installed eas || error "eas-cli no ejecuta tras la instalación"

# Registry
EAS_VER=$(eas --version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
[ -z "$EAS_VER" ] && EAS_VER="unknown"
update_registry "$EAS_VER"

# ── Limpieza ──────────────────────────────────────────────────
rm -f "$CHECKPOINT"

# ── Resumen (solo modo manual) ────────────────────────────────
if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  Expo / EAS CLI instalado ✓${NC}"
  echo ""
  echo "  EAS CLI:  $(eas --version 2>/dev/null | head -1)"
  echo "  Node.js:  $(node --version)"
  echo "  git:      $(git --version | cut -d' ' -f3)"
  echo "  Usuario:  $(eas whoami 2>/dev/null || echo 'no logueado — usa: expo-login')"
  echo ""
fi

notify_event "expo" "install_done" "$EAS_VER"
log "Instalación de Expo completada"
exit 0
