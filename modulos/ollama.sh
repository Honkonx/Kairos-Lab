#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · ollama.sh (silent mode)
#  Instala Ollama en Termux nativo (ARM64, sin root)
#
#  USO DESDE APP (KairosApp):
#    bash ollama.sh --silent --variant gpu
#    bash ollama.sh --silent --variant standard
#
#  USO MANUAL (standalone):
#    bash install_ollama.sh
#
#  FLAGS:
#    --silent              Sin preguntas, instala todo directo
#    --force               Reinstala aunque ya esté
#    --variant <tipo>      gpu (Termux npm GPU) | standard (pkg CPU)
#
#  VARIANTES:
#    gpu|termux_npm  — npm @mmmbuto/ollama-termux (GPU Vulkan, ARM64 optimizado)
#    standard|pkg    — pkg install ollama (ARM64 genérico, CPU-only)
#
#  QUÉ INSTALA:
#    ✅ Ollama (variante elegida)
#    ✅ Paquetes Vulkan/GPU (solo variante gpu)
#    ✅ tmux + curl + wget
#    ✅ Scripts: ollama_start.sh, ollama_stop.sh
#    ✅ Config inferencia (~/.ollama_user_config)
#    ✅ Aliases en .bashrc (incluye OLLAMA_VULKAN=1)
#    ✅ Registry actualizado
#
#  NO HACE:
#    ❌ Descargar modelos (lo maneja la app via kairos_manager.py)
#    ❌ Instalar Pillow (lo maneja install_python.sh)
#    ❌ Crear scripts de visión (lo maneja install_python.sh)
#
#  OUTPUT (modo --silent):
#    [STEP] N/6 Descripción
#    [OK] mensaje
#    [ERROR] mensaje (exit 1)
#
#  REPO: https://github.com/Honkonx/termux-ai-stack
#  VERSIÓN: 5.0.0 | Junio 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
INSTALL_MODE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    --variant)  shift; INSTALL_MODE="$1" ;;
  esac
  shift
done

# ── Manifiesto declarativo (--describe) ───────────────────────
# Ver docs/viejo/PROPUESTA_SCRIPTS_MODULOS.md — contrato real del script,
# para que ModuleController.kt no tenga que adivinar convenciones.
if $DESCRIBE; then
  cat << 'JSON'
{"id":"ollama","supports_silent":true,"supports_force":true,"variants":["termux_npm","standard"],"variant_aliases":{"termux_npm":["gpu","termux"],"standard":["pkg","cpu"]},"variant_required":true}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. La ruta real del binario
# "ollama" difiere según la variante instalada (pkg vs npm/GitHub Release,
# ver PASO 2 más abajo) — se resuelve por PATH al momento de empaquetar,
# mismo patrón que freebuff.sh. La variante activa se lee del registry
# (ollama.install_mode, escrito por update_registry() de este script).
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _REGISTRY="$HOME/.android_server_registry"
  _variant=$(grep -m1 '^ollama\.install_mode=' "$_REGISTRY" 2>/dev/null | cut -d= -f2 | tr -d '\r\n')
  [ -z "$_variant" ] && _variant="null" || _variant="\"$_variant\""
  _bin=$(command -v ollama 2>/dev/null || echo "$TERMUX_PREFIX/bin/ollama")
  # Bug real confirmado por ADB (2026-08-29, docs/humano285.md/286.md, pregunta directa del
  # usuario sobre por qué el .deb de ollama pesaba KB en vez de los ~123MB reales que pesa
  # el runtime de github.com/DioNanos/ollama-termux (fork propio: github.com/Honkonx/
  # ollama-termux)): la variante termux_npm/GPU instala un WRAPPER en
  # $TERMUX_PREFIX/bin/ollama que hace exec de $TERMUX_PREFIX/lib/ollama/ollama (el binario
  # real, 123MB) + libs Vulkan en $TERMUX_PREFIX/lib/ollama/vulkan/ — el manifiesto solo
  # empaquetaba el wrapper (unos pocos KB), documentado como "no cubierto" en vez de
  # arreglado. Mismo patrón de bug ya encontrado y corregido hoy en kilo/freebuff/mimocode/
  # codebuff/copilotcli/hermes. Se agrega el glob real, solo requerido si la variante activa
  # es termux_npm (la variante "standard"/pkg no tiene este directorio — su binario real ya
  # es el único archivo que necesita, viene de dpkg/pkg y ni se reinstala vía este .deb).
  _runtime_required="false"
  [ "$_variant" = "\"termux_npm\"" ] && _runtime_required="true"
  jq -n \
    --arg path "$_bin" \
    --arg glob "$HOME/scripts/ollama/**" \
    --arg glob_runtime "$TERMUX_PREFIX/lib/ollama/**" \
    --argjson runtime_required "$_runtime_required" \
    --argjson variant "$_variant" \
    --arg verify "command -v ollama >/dev/null 2>&1 && ollama --version >/dev/null 2>&1" \
    '{
      id: "ollama",
      supports_describe_files: true,
      variant: $variant,
      package_name: "kairos-module-ollama",
      version_registry_key: "ollama.version",
      files: [{path: $path, required: true, note: "Binario/wrapper ollama, resuelto por PATH — pkg (variante standard) o wrapper que exec-a el runtime real (variante termux_npm/GPU, ver file_globs)"}],
      file_globs: [
        {pattern: $glob, required: true, note: "scripts de control generados (ollama_start.sh, ollama_stop.sh)"},
        {pattern: $glob_runtime, required: $runtime_required, note: "runtime real de ollama-termux (binario ollama de ~120MB + libs Vulkan) que el wrapper de bin/ollama exec-a — sin esto el wrapper queda roto, solo existe/requerido en la variante termux_npm (GPU)"}
      ],
      dependencies: [
        {id: "pkg:tmux", check_cmd: "command -v tmux >/dev/null 2>&1", install_hint: "pkg install -y tmux"}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "No empaqueta ~/.ollama_user_config (config editable por el usuario desde la app) ni los modelos descargados (~/.ollama/models) — son datos de usuario",
        "No reinstala los paquetes Vulkan/GPU (vulkan-tools, mesa-vulkan-icd-freedreno, etc.) de la variante GPU — son paquetes apt gestionados por pkg, no snapshoteados"
      ]
    }'
  exit 0
fi

# Normalizar variante
case "$INSTALL_MODE" in
  gpu|termux_npm|termux) INSTALL_MODE="termux_npm" ;;
  standard|pkg|cpu)      INSTALL_MODE="standard" ;;
  "")
    if $SILENT; then
      echo "[ERROR] Falta --variant (gpu|standard)"
      exit 1
    fi
    ;;
  *)
    # Bug real (fix 2026-07-30): un valor de --variant que no matcheaba
    # ningún patrón de arriba (ej. "termux gpu" con espacio, portado desde
    # un bug de la UI que armaba el flag a partir del texto del botón)
    # dejaba INSTALL_MODE sin normalizar. El case del PASO 2 tampoco lo
    # reconocía, así que no instalaba NADA — pero seguía marcando el
    # checkpoint "ollama_install" como hecho, dejando el registry con
    # ollama.installed=true y una versión sacada de `pkg show` (que
    # responde aunque el paquete no esté instalado). Ahora aborta fuerte
    # en vez de fallar en silencio.
    echo "[ERROR] --variant desconocido: '$INSTALL_MODE' (usa gpu|standard)"
    exit 1
    ;;
esac

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_ollama_checkpoint"
OLLAMA_SCRIPTS="$HOME/scripts/ollama"

# ── log/warn/error/info/step + check_done/mark_done compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

update_registry() {
  local version="$1"
  local mode="$2"
  registry_install ollama "$version" "install_mode=$mode" "commands=ollama serve,ollama run,ollama list,ollama pull,ollama rm" "port=11434" "location=termux_native"
}

# ── Detección CPU features ────────────────────────────────────
detect_cpu_features() {
  local features result="base"
  features=$(grep -m1 -i "^Features" /proc/cpuinfo 2>/dev/null)
  [ -z "$features" ] && features=$(grep -i "features" /proc/cpuinfo 2>/dev/null | head -1)
  echo "$features" | grep -q "i8mm" && result="i8mm"
  echo "$features" | grep -qE "dotprod|asimddp" && {
    [ "$result" = "base" ] && result="dotprod" || result="${result}+dotprod"
  }
  echo "$features" | grep -q "sve" && result="${result}+sve"
  echo "$result"
}

HW_CPU=$(detect_cpu_features)

# Reintenta un comando hasta 3 veces con una pausa corta — la variante GPU (termux_npm,
# PASO 2 abajo) encadena varias operaciones de red seguidas (nodejs-lts, npm, descarga del
# binario real vía ollama-termux) sin ningún reintento, a diferencia de PASO 1 (que sí
# reintenta con mirrors alternativos si pkg update falla). Bug real reportado (ver
# docs/humano/humano57.md): "en ocasiones se instala la version gpu y en otras no" con el
# mismo dispositivo/red — consistente con un fallo transitorio de red en alguno de esos
# pasos encadenados sin reintento, algo que la variante standard (un solo "pkg install") no
# sufre casi nunca.
retry_cmd() {
  local attempts=3 delay=3 n=1
  while [ "$n" -le "$attempts" ]; do
    if "$@"; then return 0; fi
    [ "$n" -lt "$attempts" ] && warn "Intento $n/$attempts falló — reintentando en ${delay}s..."
    sleep "$delay"
    n=$((n + 1))
  done
  return 1
}

# ── Verificar si ya está instalado ────────────────────────────
# Bug real (auditoría 2026-08-27): este chequeo usaba solo "command -v ollama", pero PASO 2
# más abajo (variante termux_npm) ya documenta y resuelve exactamente el caso de un wrapper
# roto en PATH (bug #2 EEXIST: "npm install -g" deja $PREFIX/bin/ollama escrito con flag
# exclusivo "wx" — command -v lo encuentra igual aunque el binario real nunca se haya
# descargado) con su propia verify_binary_installed()-equivalente (ollama_binary_works()).
# Ese chequeo real solo se alcanzaba SI esta corrida pasaba de largo el "ya instalado" de
# arriba — con una instalación previa a medias, "command -v ollama" sigue encontrando el
# wrapper roto, y el script salía acá sin darle nunca la chance a PASO 2 de auto-repararlo.
if command -v ollama &>/dev/null && verify_binary_installed ollama && ! $FORCE; then
  log "Ollama ya instalado — $(ollama --version 2>/dev/null | head -1)"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: menú de variante + confirmación ─────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   termux-ai-stack · Ollama Installer        ║
  ║   Termux ARM64 · sin root · v5.0.0         ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"

  # Si no se pasó variante, preguntar
  if [ -z "$INSTALL_MODE" ]; then
    printf "  CPU: %s\n\n" "$HW_CPU"
    echo "  [1] Estándar  — pkg install (CPU-only)"
    echo -e "  ${GREEN}[2] Termux GPU  — npm optimizado (Vulkan) ★${NC}"
    echo ""
    echo -n "  Opción [1/2]: "
    read -r VERSION_CHOICE < /dev/tty
    case "$VERSION_CHOICE" in
      1) INSTALL_MODE="standard" ;;
      *) INSTALL_MODE="termux_npm" ;;
    esac
  fi

  echo ""
  echo "  Variante: $INSTALL_MODE"
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

TOTAL_STEPS=6

# ============================================================
# PASO 1 — Termux update + dependencias
# ============================================================
step "1/$TOTAL_STEPS Verificando Termux y dependencias"

if [ -n "$ANDROID_SERVER_READY" ]; then
  log "Termux preparado por instalar.sh [skip]"
  # tmux es crítico para ollama-start
  if ! command -v tmux &>/dev/null; then
    info "Instalando tmux..."
    # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
    pkg_update_with_fallback
    pkg install tmux -y \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold"
  fi
elif check_done "termux_update"; then
  log "Termux ya actualizado [checkpoint]"
else
  info "Actualizando Termux..."
  # Quick win de la auditoría de referencia/ (2026-08-05, ver docs/humano70.md) — antes
  # probaba solo 2 mirrors fijos en orden; ahora comparte la selección por velocidad
  # real centralizada en lib.sh (mismo criterio que entorno.sh/kairos.sh).
  pkg_update_with_fallback

  # Dependencias base
  for dep in curl wget tmux; do
    if ! command -v "$dep" &>/dev/null; then
      info "Instalando $dep..."
      pkg install "$dep" -y \
        -o Dpkg::Options::="--force-confdef" \
        -o Dpkg::Options::="--force-confold" 2>/dev/null
    fi
  done

  log "Termux actualizado"
  mark_done "termux_update"
fi

# ============================================================
# PASO 2 — Instalar Ollama
# ============================================================
step "2/$TOTAL_STEPS Instalando Ollama (variante: $INSTALL_MODE)"

if check_done "ollama_install"; then
  log "Ollama ya instalado [checkpoint]"
else
  case "$INSTALL_MODE" in

    standard)
      info "Instalando Ollama vía pkg (ARM64 genérico, CPU-only)..."
      # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
      pkg_update_with_fallback
      pkg install ollama -y \
        -o Dpkg::Options::="--force-confdef" \
        -o Dpkg::Options::="--force-confold" || \
        error "Error instalando Ollama. Verifica conexión."
      log "Ollama instalado: $(ollama --version 2>/dev/null | head -1)"
      ;;

    termux_npm)
      # Paquetes Vulkan/GPU
      info "Instalando paquetes Vulkan/GPU..."
      # Bug real, mismo patrón que bug #21 (VNC), ver docs/humano/humano193.md.
      pkg_update_with_fallback
      pkg install vulkan-tools vulkan-loader-android -y \
        -o Dpkg::Options::="--force-confdef" \
        -o Dpkg::Options::="--force-confold" 2>/dev/null || \
        warn "vulkan-tools/loader no disponibles — continuando"

      # Detección de vendor de GPU (mismo criterio que entorno.sh:_check_gpu(),
      # via ro.board.platform) — bug real reportado (ver docs/humano66.md): esta
      # rama SOLO instalaba el driver Turnip/Freedreno de Adreno sin importar el
      # hardware real, así que en dispositivos Mali o Xclipse (Exynos) llama.cpp
      # podía tener Vulkan funcional a nivel de sistema pero Ollama nunca
      # instalaba el paquete de driver que necesita para ese vendor específico.
      # Bug real (2026-08-06, ver docs/humano/humano77.md): "local" fuera de una
      # función es un error de bash ("local: can only be used in a function",
      # confirmado en log real de dispositivo) — este bloque vive en un "case"
      # a nivel de script, no dentro de un "function ... { }". Además la lista
      # de codenames Adreno estaba incompleta: no cubría "cape" (Snapdragon 7+
      # Gen 2 / SM7475, el chip real del POCO F5 usado para probar esta
      # sesión — confirmado "GPU detectada: unknown" en el log real) ni otros
      # codenames Qualcomm recientes (kalama=8 Gen2, taro=8 Gen1,
      # pineapple=8 Gen3, sun=8 Elite). Se agregan a título de mejor cobertura
      # — no hay forma de confirmar 100% el valor exacto de ro.board.platform
      # sin el dispositivo a mano, así que se amplía la lista en vez de
      # apostar a un único codename.
      OLLAMA_GPU_VENDOR=$(getprop ro.board.platform 2>/dev/null)
      case "$OLLAMA_GPU_VENDOR" in
        *sm*|*kona*|*lahaina*|*shima*|*cape*|*kalama*|*taro*|*pineapple*|*sun*|*parrot*|*khaje*|*monaco*) OLLAMA_GPU_VENDOR="adreno" ;;
        *mt*|*t618*|*g610*|*g720*)     OLLAMA_GPU_VENDOR="mali" ;;
        *s5e*|*exynos*)                OLLAMA_GPU_VENDOR="xclipse" ;;
        *)                              OLLAMA_GPU_VENDOR="unknown" ;;
      esac
      info "GPU detectada: $OLLAMA_GPU_VENDOR"

      case "$OLLAMA_GPU_VENDOR" in
        adreno)
          # Turnip (Freedreno) — driver Vulkan nativo Adreno
          pkg install mesa-vulkan-icd-freedreno -y \
            -o Dpkg::Options::="--force-confdef" \
            -o Dpkg::Options::="--force-confold" 2>/dev/null && \
            log "Turnip (Freedreno) instalado" || \
            warn "mesa-vulkan-icd-freedreno no disponible"
          ;;
        mali|xclipse)
          # Mesa + virglrenderer-android — mismos paquetes que entorno.sh usa
          # para GPU Mali/Xclipse (ver _install_gpu_native()), no hay un ICD
          # Vulkan nativo Mesa para estos vendors en Termux todavía.
          pkg install mesa virglrenderer-android -y \
            -o Dpkg::Options::="--force-confdef" \
            -o Dpkg::Options::="--force-confold" 2>/dev/null && \
            log "mesa + virglrenderer-android instalados ($OLLAMA_GPU_VENDOR)" || \
            warn "mesa/virglrenderer-android no disponibles ($OLLAMA_GPU_VENDOR)"
          ;;
        *)
          # Vendor desconocido: vulkan-loader-android ya instalado arriba puede
          # alcanzar solo si el vendor expone su propio ICD vía el HAL de
          # Android — no hay paquete Mesa específico que instalar a ciegas.
          warn "Vendor de GPU no reconocido ($OLLAMA_GPU_VENDOR) — usando solo vulkan-loader-android genérico"
          ;;
      esac

      # nodejs-lts requerido por instalador npm
      info "Instalando nodejs-lts..."
      retry_cmd pkg install nodejs-lts -y \
        -o Dpkg::Options::="--force-confdef" \
        -o Dpkg::Options::="--force-confold" || \
        error "Error instalando nodejs-lts (3 intentos) — revisá la conexión"

      # Bug real confirmado (auditoría 2026-08-05, ver docs/humano65.md/humano66.md):
      # "npm install -g npm" sobreescribe el npm que trae "pkg install nodejs-lts" —
      # ese SÍ viene con el shebang parcheado para Termux (sin /usr/bin/env, que acá
      # no existe), pero el npm genérico bajado del registry no. El resultado es
      # "/usr/bin/env: bad interpreter" en la siguiente línea (npm install -g
      # @mmmbuto/ollama-termux), abortando toda la variante GPU. El npm que ya trae
      # nodejs-lts es suficiente — no hace falta reinstalarlo.

      info "Instalando Ollama Termux vía npm..."
      retry_cmd npm install -g @mmmbuto/ollama-termux@latest || \
        error "Error en npm install (3 intentos). Prueba --variant standard"

      # Bug real confirmado (fix 2026-07-31, reporte del usuario con los comandos
      # manuales exactos que sí le funcionaron — coincide con el README real de
      # DioNanos/ollama-termux, el proyecto detrás de este paquete npm): el
      # "npm install -g" de arriba SOLO deja instalado el wrapper/launcher CLI
      # ("ollama-termux") — el binario real "ollama" (bin/ollama + runtime
      # lib/ollama) se baja de un Release de GitHub y se verifica por SHA256
      # recién cuando se corre "ollama-termux" al menos una vez. En npm moderno
      # esto NO pasa solo con el install porque los postinstall scripts vienen
      # bloqueados por defecto (allow-scripts) — el propio README lo dice
      # explícito: "running ollama-termux after the install is the reliable
      # path". Sin este paso, "ollama" nunca aparece en PATH sin importar
      # cuántas veces se reinstale — coincide exacto con "instalé Ollama y se
      # instaló pero nunca se inicia".
      # Bug real #2 confirmado (auditoría 2026-08-01, contra el install.js real de
      # DioNanos/ollama-termux): ese script escribe el wrapper de $PREFIX/bin/ollama con
      # flag exclusivo de Node ("wx" — falla si el archivo YA existe). Si un intento
      # anterior de instalar la variante GPU quedó a medias (ej. se cortó la red bajando
      # el Release de GitHub después de que el wrapper ya se había escrito), CUALQUIER
      # reintento de "ollama-termux" revienta con EEXIST sin volver a bajar el binario
      # real — y "command -v ollama" igual reporta encontrado (el wrapper existe y tiene
      # +x), aunque apunte a un binario real ausente/corrupto. Antes esto se detectaba
      # solo con "command -v", así que un reintento después de una descarga cortada
      # quedaba marcado como instalación exitosa sin que "ollama" funcionara nunca —
      # coincide con "la variante Vulkan no se descarga ni se activa".
      ollama_binary_works() { command -v ollama &>/dev/null && ollama --version &>/dev/null; }

      # Reintenta hasta 3 veces, re-chequeando el criterio REAL de éxito
      # (ollama_binary_works) entre intentos — no el exit code de ollama-termux, que ya
      # se documentó arriba que no es confiable. Cada intento fallido borra el wrapper
      # roto antes de reintentar, mismo criterio que el bug #2 de EEXIST de arriba.
      if ! ollama_binary_works; then
        DOWNLOAD_ATTEMPT=1
        while [ "$DOWNLOAD_ATTEMPT" -le 3 ] && ! ollama_binary_works; do
          [ -e "$TERMUX_PREFIX/bin/ollama" ] && rm -f "$TERMUX_PREFIX/bin/ollama"
          info "Corriendo el instalador real (ollama-termux) — baja el binario del último Release (intento $DOWNLOAD_ATTEMPT/3)..."
          ollama-termux 2>&1 | tail -5
          if ! ollama_binary_works && [ "$DOWNLOAD_ATTEMPT" -lt 3 ]; then
            warn "El binario real todavía no funciona — reintentando en 3s..."
            sleep 3
          fi
          DOWNLOAD_ATTEMPT=$((DOWNLOAD_ATTEMPT + 1))
        done
      fi

      if ollama_binary_works; then
        log "Ollama Termux instalado: $(ollama --version 2>/dev/null | head -1)"
      else
        # Si ollama-termux tampoco lo dejó funcionando (falla de red al bajar el
        # Release, GitHub rate-limit, etc.) — abortar en vez de marcar el
        # checkpoint como hecho (mismo criterio que el resto de scripts de
        # este proyecto: nunca dar por instalado algo sin verificar el binario
        # real, ver docs/humano*.md).
        error "Binario 'ollama' no funciona ni tras correr ollama-termux — probá --variant standard, o revisá conexión/GitHub"
      fi

      if command -v vulkaninfo &>/dev/null; then
        VK_DEV=$(vulkaninfo 2>/dev/null | grep "deviceName" | head -1 | sed 's/.*= //')
        [ -n "$VK_DEV" ] && log "Vulkan activo: $VK_DEV" || \
          warn "vulkaninfo sin dispositivo detectado"
      fi
      ;;

    *)
      # Red de seguridad: si por lo que sea INSTALL_MODE llega hasta acá sin
      # normalizar, abortar en vez de caer al final del case sin instalar
      # nada y dejar mark_done marcando un checkpoint falso (ver fix del
      # normalizador arriba, mismo bug).
      error "Variante de instalación desconocida: '$INSTALL_MODE'"
      ;;

  esac

  mark_done "ollama_install"
fi

# ============================================================
# PASO 3 — Scripts de control
# ============================================================
step "3/$TOTAL_STEPS Creando scripts de control"

if check_done "ollama_scripts"; then
  log "Scripts ya creados [checkpoint]"
else
  mkdir -p "$OLLAMA_SCRIPTS"

  cat > "$OLLAMA_SCRIPTS/ollama_start.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="ollama-server"
REGISTRY="$HOME/.android_server_registry"
LOG="$HOME/kairos_logs/ollama_serve.log"
mkdir -p "$HOME/kairos_logs"

if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "[OK] Ollama ya corriendo en tmux sesión: $SESSION"
  exit 0
fi

# OLLAMA_LAN se lee de ~/.ollama_user_config (clave escrita desde la app,
# ver OllamaConfigFragment/OllamaApiClient.writeConfigValue) — se resuelve acá
# en runtime, no al generar este script, para que cambiar el toggle no
# requiera reinstalar el módulo. Default 0 (solo localhost) si el archivo no
# existe todavía o no trae la clave: antes esto SIEMPRE bindeaba a 0.0.0.0
# sin que el usuario lo pidiera — exponer la API a toda la red local por
# defecto es una superficie de ataque que nadie eligió explícitamente.
#
# A propósito NO se hace ". ~/.ollama_user_config" (sourcing) — ese archivo
# también guarda OLLAMA_SYSTEM_PROMPT, texto libre editable desde la app y
# nunca escrito con comillas (OllamaApiClient.writeConfigValue() hace
# "$fullKey=$value" tal cual). Un prompt con espacios ya rompe la sintaxis de
# un source (bash trata la primera palabra después del "=" como el valor y
# el resto como un comando aparte), y uno con "; $() ` etc. sería inyección
# de comandos real al arrancar Ollama. Se extrae solo el valor de OLLAMA_LAN
# con grep/cut, sin evaluar el resto del archivo como shell.
OLLAMA_LAN=$(grep -m1 '^OLLAMA_LAN=' "$HOME/.ollama_user_config" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ "$OLLAMA_LAN" = "1" ] || OLLAMA_LAN=0
OLLAMA_BIND_HOST="127.0.0.1"
[ "$OLLAMA_LAN" = "1" ] && OLLAMA_BIND_HOST="0.0.0.0"

# OLLAMA_KEEP_ALIVE / OLLAMA_NUM_PARALLEL / OLLAMA_MAX_LOADED_MODELS (ronda de continuación
# 2026-08-19, ver AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md § Actualización) — los 3
# confirmados reales contra github.com/ollama/ollama/envconfig/config.go + docs.ollama.com/faq:
# tiempo que un modelo queda cargado en RAM antes de descargarse solo (default real "5m",
# negativo = infinito, "0" = sin keep-alive), requests concurrentes por modelo (default 1) y
# modelos cargados en simultáneo (default 1, sujeto a RAM disponible) — mismo mecanismo grep+cut
# sin sourcing que ya usa OLLAMA_LAN arriba (razón documentada ahí: OLLAMA_SYSTEM_PROMPT es
# texto libre no apto para "source"). Vacío = no se exporta la variable, así el binario usa su
# propio default sin que Kairos fuerce nada si el usuario no tocó el campo.
KEEP_ALIVE_ENV=""
_KEEP_ALIVE=$(grep -m1 '^OLLAMA_KEEP_ALIVE=' "$HOME/.ollama_user_config" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ -n "$_KEEP_ALIVE" ] && KEEP_ALIVE_ENV="OLLAMA_KEEP_ALIVE=${_KEEP_ALIVE} "

NUM_PARALLEL_ENV=""
_NUM_PARALLEL=$(grep -m1 '^OLLAMA_NUM_PARALLEL=' "$HOME/.ollama_user_config" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ -n "$_NUM_PARALLEL" ] && [ "$_NUM_PARALLEL" != "0" ] && NUM_PARALLEL_ENV="OLLAMA_NUM_PARALLEL=${_NUM_PARALLEL} "

MAX_LOADED_ENV=""
_MAX_LOADED=$(grep -m1 '^OLLAMA_MAX_LOADED_MODELS=' "$HOME/.ollama_user_config" 2>/dev/null | cut -d= -f2 | tr -d '"'"'"' \r\n')
[ -n "$_MAX_LOADED" ] && [ "$_MAX_LOADED" != "0" ] && MAX_LOADED_ENV="OLLAMA_MAX_LOADED_MODELS=${_MAX_LOADED} "

# La variante GPU (termux_npm, ver ollama.sh PASO 2) necesita OLLAMA_VULKAN=1 en el
# PROCESO REAL de "ollama serve" para activar el backend Vulkan (confirmado contra el
# README real de DioNanos/ollama-termux, el proyecto detrás del paquete npm). Antes esto
# SOLO se exportaba en ~/.bashrc (PASO 5 de este instalador) y dependía de que la sesión
# de tmux (bare, sin comando) spawneara un shell interactivo que sourceara ese .bashrc
# antes de recibir el "ollama serve" por send-keys — nunca garantizado (ver bug #1 abajo).
# Ahora se lee el modo de instalación real del registry y se exporta explícito, sin
# depender de ningún archivo de arranque de shell.
INSTALL_MODE=$(grep -m1 '^ollama.install_mode=' "$REGISTRY" 2>/dev/null | cut -d= -f2 | tr -d '\r\n')
VULKAN_ENV=""
[ "$INSTALL_MODE" = "termux_npm" ] && VULKAN_ENV="OLLAMA_VULKAN=1 "

# Bug real #1 (reporte de dispositivo 2026-08-01, confirmado por captura: instalación
# con versión detectada, botón "Iniciar" tocado, toast "Iniciando Ollama..." — el estado
# nunca pasa de "○ Inactivo"): la versión anterior creaba una sesión tmux VACÍA
# ("tmux new-session -d -s $SESSION", sin comando) y recién después le "tipeaba" el
# comando real con "send-keys". Eso depende de que tmux resuelva su "default-shell" (no
# garantizado — Termux no tiene /bin/sh, y $SHELL nunca llegaba seteado desde el
# ProcessBuilder de Kairos, ver ProcessBuilderExt.kt) y de que ese shell sea interactivo
# para sourcear ~/.bashrc (de donde salía OLLAMA_VULKAN=1) antes de ejecutar las teclas.
# Mismo patrón ya identificado y corregido en n8n.sh/opencode.sh (ver opencode_start.sh):
# pasar el comando DIRECTO a "tmux new-session" evita depender de esa resolución — el pane
# ejecuta el comando como su propio proceso, sin un shell intermedio esperando input.
# También: el output real de "ollama serve" (antes se perdía en cuanto moría el pane, si
# moría) ahora queda en un log persistente para poder diagnosticar un fallo real del
# binario en vez de quedar a ciegas otra vez.
tmux new-session -d -s "$SESSION" \
  "${VULKAN_ENV}${KEEP_ALIVE_ENV}${NUM_PARALLEL_ENV}${MAX_LOADED_ENV}OLLAMA_HOST=${OLLAMA_BIND_HOST}:11434 ollama serve > '$LOG' 2>&1"

sleep 2
if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "[OK] Ollama iniciado en ${OLLAMA_BIND_HOST}:11434"
else
  echo "[ERROR] No se pudo iniciar Ollama — log en ~/kairos_logs/ollama_serve.log: $(tail -c 200 "$LOG" 2>/dev/null)"
  exit 1
fi

# Detección de fallback silencioso a CPU (hallazgo de investigación de foros/GitHub,
# ver docs/humano/humano194.md): en la variante GPU (termux_npm) el usuario espera aceleración
# Vulkan real, pero si Ollama no logra usarla (driver ausente, dispositivo no soportado)
# cae a `llvmpipe`/CPU sin avisar en ningún lado visible — solo queda en este log de
# arranque, que nadie lee en uso normal. Formato real confirmado contra el código fuente
# de Ollama (gpu/types.go, server/sched.go): cada backend de inferencia detectado se
# loguea como una línea "inference compute" con el campo `library=` — "cpu" cuando no
# hay GPU utilizable, "vulkan"/"cuda"/"rocm" cuando sí. Se espera unos segundos más a que
# el servidor termine de imprimir esa línea (aparece apenas arranca, antes de aceptar
# requests) y se escribe un marcador simple en $HOME que la UI (OllamaFragment.kt) puede
# leer sin tener que parsear el log completo cada vez.
if [ "$INSTALL_MODE" = "termux_npm" ]; then
  sleep 3
  BACKEND_STATUS="unknown"
  if grep -qE 'library=(vulkan|cuda|rocm)' "$LOG" 2>/dev/null; then
    BACKEND_STATUS="gpu"
  elif grep -q 'library=cpu' "$LOG" 2>/dev/null; then
    BACKEND_STATUS="cpu"
  fi
  echo "$BACKEND_STATUS" > "$HOME/.ollama_backend_status"
fi
SCRIPT
  chmod +x "$OLLAMA_SCRIPTS/ollama_start.sh"

  cat > "$OLLAMA_SCRIPTS/ollama_stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="ollama-server"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  echo "[OK] Ollama detenido"
else
  echo "[OK] Ollama no estaba corriendo"
fi
SCRIPT
  chmod +x "$OLLAMA_SCRIPTS/ollama_stop.sh"

  log "Scripts de control creados"
  mark_done "ollama_scripts"
fi

# ============================================================
# PASO 4 — Config inferencia
# ============================================================
step "4/$TOTAL_STEPS Configuración de inferencia"

if [ ! -f "$HOME/.ollama_user_config" ]; then
  cat > "$HOME/.ollama_user_config" << 'UCFG'
# termux-ai-stack · ~/.ollama_user_config
# Parámetros de inferencia — editables desde la app o manualmente

# ── Parámetros de inferencia ─────────────────────────────────
OLLAMA_TEMP=0.7
OLLAMA_TOP_P=0.9
OLLAMA_TOP_K=40
OLLAMA_REP_PENALTY=1.1
OLLAMA_NUM_CTX=2048
OLLAMA_NUM_PREDICT=2048

# ── Red ───────────────────────────────────────────────────────
# 0 = solo localhost (127.0.0.1, default) · 1 = expone la API a la red
# local (0.0.0.0) — leído por ollama_start.sh en cada arranque, ver
# comentario en ese script. Editable desde la app (Ollama → Parámetros
# de inferencia → Red) o a mano acá.
OLLAMA_LAN=0

# ── Concurrencia / memoria (ronda de continuación 2026-08-19) ─
# Vacío/0 = usar el default real del binario (ver ollama_start.sh para el
# detalle de cada default). Editable desde la app (Ollama → Parámetros de
# inferencia → Servidor).
# Cuánto tiempo queda un modelo cargado en RAM sin uso antes de descargarse
# solo — acepta duraciones tipo "5m"/"30m"/"1h", "-1" = infinito, "0" = sin
# keep-alive (descarga apenas termina cada respuesta). Default real: "5m".
OLLAMA_KEEP_ALIVE=
# Requests concurrentes que un mismo modelo puede procesar a la vez. Default
# real: 1. Subirlo consume más RAM (escala con el contexto configurado).
OLLAMA_NUM_PARALLEL=0
# Modelos distintos cargados en RAM al mismo tiempo. Default real: 1,
# limitado igual por la RAM disponible del dispositivo.
OLLAMA_MAX_LOADED_MODELS=0

# ── System prompt ────────────────────────────────────────────
OLLAMA_SYSTEM_PROMPT="Eres un asistente técnico especializado en programación y trading. Responde siempre en español. Sé directo y conciso. Si no sabes algo, dilo sin inventar."

# ── Campos meta (usados si OLLAMA_SYSTEM_PROMPT está vacío) ──
OLLAMA_ROLE="Asistente técnico especializado"
OLLAMA_GOAL="Ayudar al usuario con programación, trading y automatización"
OLLAMA_TONE="profesional, directo, amigable"
OLLAMA_DELIVERABLE="Código funcional o respuesta clara y útil"
UCFG
  log "~/.ollama_user_config creado"
else
  log "~/.ollama_user_config ya existe — conservado"
fi

# ============================================================
# PASO 5 — Aliases
# ============================================================
step "5/$TOTAL_STEPS Configurando aliases"

if check_done "ollama_aliases"; then
  log "Aliases ya configurados [checkpoint]"
else
  BASHRC="$HOME/.bashrc"
  [ -f "$BASHRC" ] && grep -v "ollama-start\|ollama-stop\|ollama-list\|ollama-run\|ollama-pull\|ollama-status\|OLLAMA_HOST\|OLLAMA_VULKAN\|# Ollama" \
    "$BASHRC" > "$BASHRC.tmp" 2>/dev/null && mv "$BASHRC.tmp" "$BASHRC"

  cat >> "$BASHRC" << 'ALIASES'

# ════════════════════════════════
#  Ollama · aliases + entorno
# ════════════════════════════════
export OLLAMA_VULKAN=1
alias ollama-start='bash ~/scripts/ollama/ollama_start.sh'
alias ollama-stop='bash ~/scripts/ollama/ollama_stop.sh'
alias ollama-status='curl -s http://localhost:11434 && echo " (corriendo)" || echo "Ollama no responde en :11434"'
alias ollama-list='ollama list'
alias ollama-run='ollama run'
alias ollama-pull='ollama pull'
alias ollama-lan='OLLAMA_HOST=0.0.0.0 ollama serve'
ALIASES

  log "Aliases configurados"
  mark_done "ollama_aliases"
fi

# ============================================================
# PASO 6 — Registry
# ============================================================
step "6/$TOTAL_STEPS Actualizando registry"

OLLAMA_VER=$(ollama --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+[-a-z.0-9]*' | head -1)
[ -z "$OLLAMA_VER" ] && OLLAMA_VER=$(pkg show ollama 2>/dev/null | grep "^Version:" | awk '{print $2}')
[ -z "$OLLAMA_VER" ] && OLLAMA_VER="unknown"

update_registry "$OLLAMA_VER" "$INSTALL_MODE"

# ── Limpieza ──────────────────────────────────────────────────
rm -f "$CHECKPOINT"

# ── Resumen (solo modo manual) ────────────────────────────────
if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  Ollama instalado ✓${NC}"
  echo ""
  echo "  Versión:  $(ollama --version 2>/dev/null | head -1)"
  echo "  Variante: $INSTALL_MODE"
  echo "  Puerto:   11434"
  echo "  CPU:      $HW_CPU"
  [ "$INSTALL_MODE" = "termux_npm" ] && \
    echo -e "  GPU:      ${GREEN}Vulkan habilitado (OLLAMA_VULKAN=1)${NC}"
  echo ""
  echo "  Descarga modelos desde la app o con: ollama pull <modelo>"
  echo ""
fi

# Aviso real a la app (bridge sin polling, ver modulos/lib.sh notify_event()) — quick win
# de la auditoría de referencia/ (2026-08-05, ver docs/humano70.md y REFERENCIA_PODROID.md,
# patrón ya probado en entorno.sh): antes solo Entorno avisaba, el resto de módulos dependía
# de que el usuario volviera a abrir la app para ver que ya terminó.
notify_event "ollama" "install_done" "$INSTALL_MODE"

log "Instalación de Ollama completada"
exit 0
