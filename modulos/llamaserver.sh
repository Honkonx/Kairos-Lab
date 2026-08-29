#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · llamaserver.sh (silent mode)
#  Instala el binario llama-server (servidor HTTP compatible OpenAI de
#  llama.cpp) — para que OpenCode/Hermes/OpenClaw puedan usar llama.cpp
#  además de Ollama (el wrapper JNI del tab Chat IA no tiene puerto, es
#  en proceso — ver docs/ia-local/LLAMA_CPP_EMBEBIDO.md y
#  docs/referencias/REFERENCIA_OLLAMASERVER.md).
#
#  Rama "llama-server-and-terminal-ux" (2026-08-05, ver docs/humano/humano71.md) —
#  primera implementación, sin confirmar en dispositivo real todavía.
#
#  USO DESDE APP (KairosApp):
#    bash llamaserver.sh --silent
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Binario llama-server (ya extraído por KairosBootstrap.kt a
#       ~/scripts/install/llama-server) copiado a $PREFIX/bin/llama-server
#    ✅ Scripts de control: llama_server_start.sh, llama_server_stop.sh
#    ✅ Config (~/.llamaserver_user_config) — modelo elegido + puerto
#    ✅ Registry actualizado
#
#  NO INSTALA:
#    ❌ Modelos .gguf — reusa los que ya haya en el directorio de modelos
#       del tab Chat IA (LocalModelManager.modelsDir(), mismo filesystem/UID
#       por sharedUserId, ruta fija más abajo)
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | 2026-08-05 (nuevo, primera versión)
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

if $DESCRIBE; then
  cat << 'JSON'
{"id":"llamaserver","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_llamaserver_checkpoint"
LLAMASERVER_SCRIPTS="$HOME/scripts/llamaserver"
LLAMASERVER_BIN="$TERMUX_PREFIX/bin/llama-server"
# Mismo directorio que LocalModelManager.modelsDir() (context.filesDir/models) —
# comparten filesystem/UID por sharedUserId, así que un script de Termux puede leerlo
# por ruta absoluta sin ningún permiso especial.
MODELS_DIR="/data/data/com.termux/files/models"

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. NO empaqueta modelos .gguf (viven en
# MODELS_DIR, compartidos con el Chat IA, potencialmente varios GB — a propósito).
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$LLAMASERVER_BIN" \
    --arg p2 "$LLAMASERVER_SCRIPTS/start.sh" \
    --arg p3 "$LLAMASERVER_SCRIPTS/stop.sh" \
    --arg p4 "$HOME/.llamaserver_user_config" \
    --arg glob "$TERMUX_PREFIX/lib/libggml*.so" \
    --arg verify "timeout 5 \"$LLAMASERVER_BIN\" --version >/dev/null 2>&1" \
    --arg patch "chmod 755 \"$LLAMASERVER_BIN\" \"$LLAMASERVER_SCRIPTS/\"*.sh 2>/dev/null || true" \
    '{
      id: "llamaserver",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-llamaserver",
      version_registry_key: "llamaserver.version",
      files: [
        {path: $p1, required: true, note: "Binario llama-server, copiado desde el bootstrap de KairosBootstrap.kt"},
        {path: $p2, required: true, note: "Arranca en tmux, lee modelo/puerto/flags de la config"},
        {path: $p3, required: true, note: "Detiene la sesión tmux"},
        {path: $p4, required: false, note: "Config editable (modelo, puerto, ctx-size, threads, ngl, api-key, embeddings, parallel, LAN)"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "Bibliotecas .so dependientes (libggml-base/libllama/libllama-common/libmtmd/libllama-server-impl) — sin ellas el binario falla con CANNOT LINK EXECUTABLE"}
      ],
      dependencies: [],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "No empaqueta modelos .gguf (MODELS_DIR, compartido con el Chat IA) — pueden pesar varios GB, a propósito fuera de alcance",
        "El repackage no confirma que las .so copiadas correspondan exactamente a la misma versión del binario — si el device destino ya tenía otras .so de una build distinta, podría haber un mismatch (no verificado, riesgo bajo en la práctica porque el binario viene siempre del mismo bootstrap)"
      ]
    }'
  exit 0
fi

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry() {
  local version="$1"
  registry_install llamaserver "$version" "port=8085"
}

# ── Verificar si ya está instalado ────────────────────────────
if [ -x "$LLAMASERVER_BIN" ] && ! $FORCE; then
  log "llama-server ya instalado"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo -n "  ¿Instalar llama-server (servidor HTTP de llama.cpp)? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=3

# ============================================================
# PASO 1 — Copiar binario
# ============================================================
step "1/$TOTAL_STEPS Instalando binario llama-server"

if check_done "llamaserver_bin"; then
  log "Binario ya copiado [checkpoint]"
else
  SRC_BIN="$HOME/scripts/install/llama-server"
  [ ! -f "$SRC_BIN" ] && error "llama-server no está en $SRC_BIN — KairosBootstrap no lo extrajo (¿build sin el binario? ver docs/humano/humano71.md)"
  cp "$SRC_BIN" "$LLAMASERVER_BIN" || error "No se pudo copiar $SRC_BIN a $LLAMASERVER_BIN"
  chmod 755 "$LLAMASERVER_BIN" || error "No se pudo hacer ejecutable $LLAMASERVER_BIN"

  # Bug real (2026-08-06, ver docs/humano/humano83.md): "CANNOT LINK EXECUTABLE: library
  # libllama-server-impl.so not found" — llama-server es un binario dinámicamente linkeado
  # (BUILD_SHARED_LIBS=ON) contra varias bibliotecas propias (libggml-base.so, libllama.so,
  # libllama-common.so, libmtmd.so, libllama-server-impl.so). KairosBootstrap.kt las extrae al
  # mismo directorio que el binario (scripts/install/) — se copian a $PREFIX/lib/, donde el
  # linker dinámico de Termux ya las busca por defecto, sin necesitar tocar LD_LIBRARY_PATH.
  shopt -s nullglob
  _SO_FILES=("$HOME/scripts/install/"*.so)
  shopt -u nullglob
  if [ "${#_SO_FILES[@]}" -gt 0 ]; then
    cp "${_SO_FILES[@]}" "$TERMUX_PREFIX/lib/" || warn "Alguna biblioteca .so no se pudo copiar a $TERMUX_PREFIX/lib/ — llama-server puede no arrancar"
    log "Bibliotecas dependientes copiadas: ${#_SO_FILES[@]} .so"
  else
    warn "No se encontraron .so dependientes en scripts/install/ — llama-server puede no arrancar"
  fi

  # timeout defensivo (auditoría 2026-08-05, ver docs/humano/humano73.md): si "--version" no
  # se reconociera como se espera y el binario intentara arrancar el servidor de verdad sin
  # modelo, esto evita que el instalador quede colgado para siempre esperando una conexión que
  # nunca llega — mismo criterio que notify_event() en lib.sh.
  timeout 5 "$LLAMASERVER_BIN" --version &>/dev/null || warn "llama-server copiado pero '--version' no respondió — puede seguir funcionando igual (algunos builds no soportan ese flag)"
  log "Binario instalado: $LLAMASERVER_BIN"
  mark_done "llamaserver_bin"
fi

# ============================================================
# PASO 2 — Scripts de control
# ============================================================
step "2/$TOTAL_STEPS Creando scripts de control"

if check_done "llamaserver_scripts"; then
  log "Scripts ya creados [checkpoint]"
else
  mkdir -p "$LLAMASERVER_SCRIPTS"

  cat > "$LLAMASERVER_SCRIPTS/start.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
SESSION="llamaserver"
REGISTRY="\$HOME/.android_server_registry"
CFG="\$HOME/.llamaserver_user_config"
LOG="\$HOME/kairos_logs/llamaserver_serve.log"
MODELS_DIR="$MODELS_DIR"
BIN="$LLAMASERVER_BIN"
mkdir -p "\$HOME/kairos_logs"

if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] llama-server ya corriendo en tmux sesión: \$SESSION"
  exit 0
fi

# Modelo y puerto se leen del config en runtime (mismo patrón que
# OLLAMA_LAN en ollama_start.sh) — así elegir otro modelo desde la app no
# requiere reinstalar el módulo. Sin comillas/eval, solo grep+cut, mismo
# criterio de seguridad que ollama_start.sh (evita inyección si el valor
# tuviera espacios o caracteres de shell).
MODEL_FILE=\$(grep -m1 '^LLAMA_SERVER_MODEL=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
PORT=\$(grep -m1 '^LLAMA_SERVER_PORT=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ -z "\$PORT" ] && PORT=8085

# Contexto/threads (pedido de auditoría 2026-08-17): antes el binario arrancaba SIEMPRE con
# los defaults de llama-server (-c/--ctx-size 0 = tamaño de entrenamiento del modelo, -t/
# --threads auto-detectado) sin que el usuario pudiera ajustarlos, a diferencia de Ollama
# (OLLAMA_NUM_CTX ya configurable desde la app). Flags reales confirmados contra
# tools/server/README.md de ggml-org/llama.cpp — "0" vacío/no seteado deja que el binario
# use su propio default, no se fuerza ningún valor si el usuario no lo tocó.
CTX_SIZE=\$(grep -m1 '^LLAMA_SERVER_CTX_SIZE=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
THREADS=\$(grep -m1 '^LLAMA_SERVER_THREADS=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
EXTRA_ARGS=""
[ -n "\$CTX_SIZE" ] && [ "\$CTX_SIZE" != "0" ] && EXTRA_ARGS="\$EXTRA_ARGS -c \$CTX_SIZE"
[ -n "\$THREADS" ] && [ "\$THREADS" != "0" ] && EXTRA_ARGS="\$EXTRA_ARGS -t \$THREADS"

# GPU offload (-ngl/--n-gpu-layers, flag real confirmado contra tools/server/README.md de
# ggml-org/llama.cpp) — gap real encontrado en auditoría 2026-08-19: este mismo binario se
# compila con -DGGML_VULKAN=ON (ver llama-engine/build.gradle, mismo flag que usa el motor
# embebido del Chat IA), pero llamaserver.sh nunca pasaba -ngl — el backend Vulkan quedaba
# disponible en el binario sin que ninguna capa de offload a GPU se activara nunca. "0" o
# vacío = todo en CPU (comportamiento anterior, sin cambios si el usuario no lo toca).
NGL=\$(grep -m1 '^LLAMA_SERVER_NGL=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ -n "\$NGL" ] && [ "\$NGL" != "0" ] && EXTRA_ARGS="\$EXTRA_ARGS -ngl \$NGL"

# --api-key / --embeddings / --parallel / bind LAN (ronda de continuación 2026-08-19, ver
# AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md § Actualización) — los 4 flags confirmados
# reales contra tools/server/README.md de ggml-org/llama.cpp. LAN se implementa igual que
# OLLAMA_LAN (host 0.0.0.0 en vez de 127.0.0.1) — deliberadamente se recomienda setear
# LLAMA_SERVER_API_KEY antes de habilitar LAN (ver comentario en el config generado), pero no se
# bloquea a nivel script: la app ya avisa en el toggle de UI, mismo criterio que Ollama.
API_KEY=\$(grep -m1 '^LLAMA_SERVER_API_KEY=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ -n "\$API_KEY" ] && EXTRA_ARGS="\$EXTRA_ARGS --api-key \$API_KEY"

EMBEDDINGS=\$(grep -m1 '^LLAMA_SERVER_EMBEDDINGS=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ "\$EMBEDDINGS" = "1" ] && EXTRA_ARGS="\$EXTRA_ARGS --embeddings"

PARALLEL=\$(grep -m1 '^LLAMA_SERVER_PARALLEL=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ -n "\$PARALLEL" ] && [ "\$PARALLEL" != "0" ] && EXTRA_ARGS="\$EXTRA_ARGS --parallel \$PARALLEL"

HOST="127.0.0.1"
LAN=\$(grep -m1 '^LLAMA_SERVER_LAN=' "\$CFG" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ "\$LAN" = "1" ] && HOST="0.0.0.0"

if [ -z "\$MODEL_FILE" ] || [ ! -f "\$MODELS_DIR/\$MODEL_FILE" ]; then
  echo "[ERROR] No hay modelo .gguf configurado (o no se encuentra en \$MODELS_DIR) — elegí uno desde Kairos (Chat IA / llama-server)."
  exit 1
fi

# Bug real (2026-08-06, ver docs/humano/humano86.md y humano88.md): llama-server arrancaba
# y llegaba hasta intentar cargar el modelo, pero fallaba con "no backends are loaded" —
# ggml_backend_load_all() (BUILD_SHARED_LIBS=ON + GGML_BACKEND_DL=ON, ver
# llama-engine/build.gradle) busca los .so de los backends (libggml-cpu-*.so,
# libggml-vulkan.so). Fix real de humano86 (GGML_BACKEND_DIR) NO FUNCIONABA — confirmado
# leyendo el código fuente real de llama.cpp (ggml/src/ggml-backend-reg.cpp):
# GGML_BACKEND_DIR es una macro de COMPILACIÓN (#ifdef), nunca una variable de entorno de
# runtime, así que exportarla acá no hacía nada. Las rutas que SÍ se consultan sin
# condición en runtime son get_executable_path() (directorio del propio binario, \$PREFIX/bin)
# y fs::current_path() (directorio de trabajo del proceso al arrancar) — ninguna de las 2
# es \$PREFIX/lib, donde están los .so. Fix real: "cd" a \$PREFIX/lib antes de lanzar el
# binario — los paths de modelo/log de acá abajo ya son absolutos, cambiar el cwd no rompe
# nada más.
tmux new-session -d -s "\$SESSION" \
  "cd '$TERMUX_PREFIX/lib' && '\$BIN' -m '\$MODELS_DIR/\$MODEL_FILE' --host \$HOST --port \$PORT\$EXTRA_ARGS > '\$LOG' 2>&1"

sleep 2
if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] llama-server iniciado en \$HOST:\$PORT (modelo: \$MODEL_FILE)"
else
  echo "[ERROR] No se pudo iniciar llama-server — log en ~/kairos_logs/llamaserver_serve.log: \$(tail -c 200 "\$LOG" 2>/dev/null)"
  exit 1
fi
SCRIPT
  chmod +x "$LLAMASERVER_SCRIPTS/start.sh"

  cat > "$LLAMASERVER_SCRIPTS/stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="llamaserver"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  echo "[OK] llama-server detenido"
else
  echo "[OK] llama-server no estaba corriendo"
fi
SCRIPT
  chmod +x "$LLAMASERVER_SCRIPTS/stop.sh"

  log "Scripts de control creados"
  mark_done "llamaserver_scripts"
fi

# ============================================================
# PASO 3 — Config + registry
# ============================================================
step "3/$TOTAL_STEPS Configuración"

if [ ! -f "$HOME/.llamaserver_user_config" ]; then
  cat > "$HOME/.llamaserver_user_config" << 'UCFG'
# kairos-app · ~/.llamaserver_user_config
# Editable desde la app (pantalla de llama-server) o a mano.

# Nombre de archivo .gguf dentro del directorio de modelos del Chat IA —
# vacío hasta que el usuario elija uno desde la app.
LLAMA_SERVER_MODEL=

# Puerto local — mismo criterio que Ollama (127.0.0.1, no expuesto a la
# red local por ahora; no hay pedido explícito de esa opción todavía).
LLAMA_SERVER_PORT=8085

# Contexto (tokens) y threads CPU — 0/vacío = default del binario (context size de
# entrenamiento del modelo / auto-detección de núcleos, ver -c/-t en
# tools/server/README.md de llama.cpp). Editable desde la app (IA Local → Parámetros).
LLAMA_SERVER_CTX_SIZE=0
LLAMA_SERVER_THREADS=0

# Capas de GPU offload (-ngl/--n-gpu-layers) — 0/vacío = todo en CPU. El binario está
# compilado con backend Vulkan (GGML_VULKAN=ON, mismo flag que el motor embebido del Chat
# IA — ver llama-engine/build.gradle), así que un valor > 0 en dispositivos con GPU Vulkan
# funcional puede acelerar la inferencia. Editable desde la app (IA Local → Parámetros).
LLAMA_SERVER_NGL=0

# API key opcional (--api-key) — vacío = servidor sin autenticar, igual que hoy. Recomendado
# setearla antes de habilitar LLAMA_SERVER_LAN=1 (ver abajo), aunque no es obligatorio.
LLAMA_SERVER_API_KEY=

# 1 = restringe el servidor a modo embeddings (--embeddings, endpoint /v1/embeddings). 0 =
# comportamiento actual (chat completions normal).
LLAMA_SERVER_EMBEDDINGS=0

# Slots concurrentes (--parallel/-np) — 0/vacío = default del binario (auto). Subirlo permite
# atender varias requests en simultáneo a costa de más RAM por slot.
LLAMA_SERVER_PARALLEL=0

# 1 = expone el servidor en la red local (0.0.0.0) en vez de solo 127.0.0.1 — mismo criterio
# que OLLAMA_LAN. Setear LLAMA_SERVER_API_KEY antes de activar esto si el dispositivo está en
# una red no confiable.
LLAMA_SERVER_LAN=0
UCFG
  log "~/.llamaserver_user_config creado"
else
  log "~/.llamaserver_user_config ya existe — conservado"
fi

LLAMASERVER_VER=$(timeout 5 "$LLAMASERVER_BIN" --version 2>&1 | head -1)
[ -z "$LLAMASERVER_VER" ] && LLAMASERVER_VER="unknown"
update_registry "$LLAMASERVER_VER"

rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  llama-server instalado ✓${NC}"
  echo ""
  echo "  Puerto:   8085 (127.0.0.1)"
  echo "  Modelos:  $MODELS_DIR (los mismos del Chat IA)"
  echo ""
  echo "  Elegí un modelo desde la app antes de iniciar el servidor."
  echo ""
fi

log "Instalación de llama-server completada"
exit 0
