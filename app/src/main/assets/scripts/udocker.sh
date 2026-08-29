#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · udocker.sh (silent mode)
#  Módulo standalone de udocker — runtime de contenedores sin root
#  (userspace, vía PRoot) usable de forma general, no solo como
#  pieza interna de otros módulos.
#
#  CONTEXTO: udocker ya se usaba DENTRO de modulos/n8n.sh (variante
#  --variant udocker, PASO 0) pero no existía como módulo propio con
#  cara de usuario — este script expone udocker como herramienta de
#  propósito general. La lógica de instalación (mirrors del tarball
#  de udockertools + verificación real vía $HOME/.udocker/lib/VERSION
#  + forzado de execmode P2) es una copia literal de esa parte de
#  n8n.sh, no una reimplementación — mismos bugs ya corregidos ahí
#  (ver comentarios originales en n8n.sh PASO 0), para no divergir.
#
#  USO DESDE APP (KairosApp):
#    bash udocker.sh --silent
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala udockertools aunque ya esté
#    --describe Manifiesto declarativo
#
#  QUÉ INSTALA:
#    ✅ pkg install udocker (binario base)
#    ✅ udockertools (vía mirrors fijos — el origen dinámico de
#       "udocker install" falla seguido en red móvil/CGNAT, mismo
#       problema y mismo fix que en n8n.sh)
#    ✅ execmode P2 forzado (proot puro — F1-F3 dependen de
#       fakechroot interceptando libc, poco confiable en Bionic)
#    ✅ Scripts wrapper en ~/scripts/udocker/: pull.sh, run.sh,
#       list.sh, rm.sh — cubren el uso general de contenedores
#       (bajar una imagen, correrla, listar, borrar) sin memorizar
#       flags de udocker
#
#  QUÉ NO HACE:
#    ❌ No instala Docker real (eso no es posible sin root — ver
#       modulos/docker.sh para la explicación honesta y por qué
#       este módulo, udocker, es la alternativa real en Kairos)
#    ❌ No baja ninguna imagen por su cuenta — el usuario elige qué
#       imagen correr con pull.sh/run.sh
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ───────────────────────────────────────────
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

# ── Manifiesto declarativo (--describe) ─────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"udocker","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"runtime de contenedores sin root (userspace, via PRoot) — no es Docker real, ver modulos/docker.sh; ya se usaba dentro de n8n.sh, este modulo lo expone como herramienta de proposito general con wrappers pull/run/list/rm"}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. udocker (binario base, pkg) +
# udockertools ($HOME/.udocker/, runtime descargado de mirrors fijos) + los 4
# wrappers propios de este módulo ($HOME/scripts/udocker/*.sh).
if $DESCRIBE_FILES; then
  # Bug real encontrado 2026-08-29 empaquetando en dispositivo real: el glob
  # "$HOME/.udocker/**" original recorría TODO $HOME/.udocker/, incluyendo
  # $HOME/.udocker/containers/ (contenedores extraídos por el usuario vía
  # "udocker run"/pull de imágenes) — en el device de prueba eran 132.354 de
  # 132.462 archivos totales (99.9%), contradiciendo el propio "not_covered"
  # de este manifest ("No empaqueta ninguna imagen de contenedor descargada
  # por el usuario") y haciendo que dpkg-deb -b tardara órdenes de magnitud
  # más de lo razonable. Fix: 3 globs específicos al runtime real de
  # udockertools (bin/, lib/, doc/ — 108 archivos), excluyendo
  # containers/, layers/ y repos/ (todos datos de imágenes descargadas por
  # el usuario, no parte de la instalación del runtime).
  jq -n \
    --arg glob1 "$HOME/scripts/udocker/**" \
    --arg glob2 "$HOME/.udocker/bin/**" \
    --arg glob3 "$HOME/.udocker/lib/**" \
    --arg glob4 "$HOME/.udocker/doc/**" \
    --arg verify "command -v udocker >/dev/null 2>&1 && [ -s \"$HOME/.udocker/lib/VERSION\" ]" \
    '{
      id: "udocker",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-udocker",
      version_registry_key: "udocker.version",
      files: [],
      file_globs: [
        {pattern: $glob1, required: true, note: "wrappers de propósito general (pull.sh, run.sh, list.sh, rm.sh)"},
        {pattern: $glob2, required: true, note: "udockertools — binarios del runtime descargado de mirrors fijos (jorge-lip/udocker-builds)"},
        {pattern: $glob3, required: true, note: "udockertools — librerías/VERSION del runtime, execmode P2 forzado"},
        {pattern: $glob4, required: false, note: "udockertools — documentación embebida del runtime"}
      ],
      dependencies: [{id: "pkg:udocker", check_cmd: "command -v udocker >/dev/null 2>&1", install_hint: "pkg install -y udocker"}],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "No empaqueta ninguna imagen de contenedor descargada por el usuario (udocker pull) — eso es estado de usuario, no parte de la instalación del runtime"
      ]
    }'
  exit 0
fi

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_udocker_checkpoint"
UDOCKER_SCRIPTS="$HOME/scripts/udocker"

# ── log/warn/error/info/step + check_done/mark_done/registry_write compartidos ──
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh" 2>/dev/null || {
  echo "Error: lib.sh no encontrado"
  exit 1
}

# Mismos mirrors fijos que n8n.sh (ver comentario original ahí): el origen
# dinámico de "udocker install" falla intermitente en red móvil/CGNAT.
UDOCKER_TARBALL_MIRRORS=(
  "https://raw.githubusercontent.com/jorge-lip/udocker-builds/master/tarballs/udocker-englib-1.2.11.tar.gz"
  "https://download.a.incd.pt/udocker/udocker-englib-1.2.11.tar.gz"
)

# Verifica que el udocker instalado sea REALMENTE el fork con el patch de plataforma
# android->linux — no basta con el marcador "$HOME/.udocker_fork_installed" (bug real
# confirmado por ADB en dispositivo real 2026-08-29, investigación a fondo pedida por el
# usuario tras seguir viendo "no image found in manifest for platform (android/arm64)" pese
# al fix anterior de detección de fork): el marcador se toca la primera vez que
# "pip install git+..." devuelve exit 0, pero eso puede pasar aunque el paquete resultante NO
# tenga el patch — confirmado en el dispositivo real que "pip show udocker" reportaba
# Version 1.3.17 (mismo número que la versión oficial de PyPI) y el archivo instalado
# udocker/helper/hostinfo.py NO tenía el mapeo "android"->"linux" (era el código original de
# indigo-dc/udocker, byte a byte) pese a que el marcador SÍ existía en el dispositivo. Causa
# raíz real más probable: pip resuelve "git+https://.../udocker.git" (sin pin de commit) contra
# la build cacheada de una instalación anterior con la misma URL — sin "--no-cache-dir
# --force-reinstall" pip puede reusar el wheel ya cacheado de una clonación vieja del fork (de
# antes de que el patch de HostInfo.osversion() se subiera ahí) en vez de volver a clonar y
# reconstruir. Con el marcador solo, el corto-circuito de más abajo nunca vuelve a intentar la
# instalación aunque el binario real siga siendo el código sin parchear. Chequeo real en vez de
# confiar en el marcador: HostInfo().osversion() en el Python de Termux SIEMPRE devuelve
# "android" (platform.system() sin parchear) — si el patch del fork está activo, el método lo
# mapea a "linux" antes de devolverlo; si no, devuelve "android" tal cual. Mismo principio que
# .claude/rules/empirical-verification-before-fix.md: verificar la post-condición real, no el
# exit code de la instalación ni un marcador que solo prueba que un comando corrió una vez.
udocker_fork_patch_active() {
  command -v udocker &>/dev/null || return 1
  "$TERMUX_PREFIX/bin/python3" -c '
from udocker.helper.hostinfo import HostInfo
import sys
sys.exit(0 if HostInfo().osversion() != "android" else 1)
' 2>/dev/null
}

# ── Ya instalado ────────────────────────────────────────────
# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# el binario "udocker" y "$HOME/.udocker/lib/VERSION" pueden existir SIN que este módulo haya
# corrido nunca — n8n.sh (variante --variant udocker) instala el mismo binario/runtime base
# como paso interno (PASO 0), pero NUNCA genera los wrappers $HOME/scripts/udocker/*.sh (son
# específicos de ESTE módulo). Con el chequeo viejo (solo binario + VERSION), la primera vez
# que un usuario abría "udocker" en la app DESPUÉS de ya tener n8n instalado, el módulo se
# reportaba "ya instalado" y salía ANTES de llegar al bloque que crea pull.sh/run.sh/list.sh/
# rm.sh (más abajo) — dejando la pantalla del módulo sin ningún wrapper funcional pese a decir
# instalado. Se agrega el chequeo de que los 4 wrappers ya existan al corto-circuito.
#
# Bug real #2 encontrado 2026-08-28 (auditoría de un agente de investigación, ver
# docs/humano278.md): cualquier dispositivo que ya tuviera "udocker" instalado ANTES de
# adoptar el fork github.com/Honkonx/udocker (línea ~207 más abajo) — sea porque corrió este
# módulo antes del 2026-08-27, o porque n8n.sh --variant udocker lo instaló como paso interno —
# quedaba con el udocker oficial de PyPI (sin el fix de HostInfo.osversion() "android"->"linux")
# y el corto-circuito de acá abajo lo reportaba "ya instalado" para siempre, sin volver a pasar
# nunca por el bloque que instala el fork — las descargas de imágenes seguían fallando con
# "no image found in manifest for platform (android/arm64)" indefinidamente. Se agrega el
# marcador "$HOME/.udocker_fork_installed" (creado solo cuando el pip install desde el fork
# tiene éxito, ver PASO 1 más abajo) al corto-circuito, para forzar el re-intento de instalación
# del fork en cualquier dispositivo que no lo tenga todavía.
if command -v udocker &>/dev/null && [ -s "$HOME/.udocker/lib/VERSION" ] \
   && [ -f "$HOME/scripts/udocker/pull.sh" ] && [ -f "$HOME/scripts/udocker/run.sh" ] \
   && [ -f "$HOME/scripts/udocker/list.sh" ] && [ -f "$HOME/scripts/udocker/rm.sh" ] \
   && udocker_fork_patch_active \
   && ! $FORCE; then
  log "udocker ya instalado — $(udocker version 2>/dev/null | head -1)"
  # Extraer solo el número de versión (patrón N.N.N), no la primera línea que contenga
  # la palabra "udocker" — bug real confirmado (ver captura de dispositivo, campo
  # "udocker.version" del registry con la URL completa del tarball en vez de "1.2.11"):
  # "udocker version" puede imprimir líneas de instalación/config (con la URL del
  # mirror de udockertools) antes de la línea de versión real, y "grep -m1 udocker |
  # awk '{print $2}'" tomaba el campo equivocado de esa línea. Mismo patrón ya usado
  # en termux-ai-stack-dev/scripts/menu_entorno.sh:823 para lo mismo.
  registry_install udocker "$(udocker version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)" "execmode=P2"
  exit 0
fi

$FORCE && rm -f "$CHECKPOINT"

# ── Modo manual: cabecera y confirmación ────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   kairos-app · udocker Installer             ║
  ║   Contenedores sin root (PRoot) · v1.0.0     ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Instala udocker — corré imágenes de contenedores (Docker Hub"
  echo "  y compatibles) sin root, vía PRoot (execmode P2). NO es un"
  echo "  daemon Docker real: sin namespaces/cgroups del kernel, mismo"
  echo "  motor que ya usa el módulo n8n en su variante udocker."
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ============================================================
# PASO 1 — udocker (binario base + udockertools)
# ============================================================
step "1/2 Instalando udocker"

export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot 2>/dev/null || echo "$TERMUX_PREFIX/bin/proot")

if check_done "udocker_install"; then
  log "udocker ya instalado [checkpoint]"
else
  if udocker_fork_patch_active; then
    log "udocker (fork con patch de plataforma android->linux) ya disponible"
  else
    command -v udocker &>/dev/null && log "udocker sin el patch de plataforma (oficial/viejo/build cacheada), reinstalando desde el fork..."
    info "Instalando udocker..."
    # Bug real confirmado por ADB (docs/humano246.md, 2026-08-26): "udocker" NO es un paquete
    # del repo apt de Termux — "pkg install udocker" fallaba siempre con "Unable to locate
    # package" (oculto por el "2>/dev/null" de la versión anterior, que solo dejaba ver el
    # "[ERROR] No se pudo instalar udocker" genérico).
    # Bug real #2 confirmado por ADB (docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md,
    # 2026-08-27): el fix anterior (descarga directa de udocker.py desde
    # raw.githubusercontent.com/indigo-dc/udocker/main/) quedó roto — el proyecto upstream
    # reestructuró el repo, "udocker.py" ya no existe como script único en la raíz (ahora es
    # un paquete udocker/ con maincmd.py + módulos), la URL devuelve 404 siempre. La vía
    # soportada oficialmente en docs/installation_manual.md del proyecto es "pip install
    # udocker" (paquete real en PyPI) — probado en dispositivo real, deja el binario
    # funcional en $PREFIX/bin/udocker (confirmado "udocker --version" -> 1.3.17).
    # Fork propio (2026-08-27, docs/humano276.md, pedido explicito del usuario): el udocker
    # oficial de PyPI SIEMPRE falla al bajar cualquier imagen en Termux -- HostInfo.osversion()
    # usa platform.system() para armar el selector de plataforma del manifest OCI, y el Python
    # de Termux devuelve "Android" (no "Linux") ahi, asi que udocker pide el manifest de
    # "android/arm64" a Docker Hub, que no existe (confirmado real: "no image found in manifest
    # for platform (android/arm64)"). Mitigado en n8n.sh con --platform=linux/arm64 explicito
    # en cada `udocker pull`, pero eso solo cubre los call-sites que Kairos controla -- un
    # `udocker pull` corrido a mano por el usuario (ej. desde el modulo "udocker" generico de
    # la app) seguia roto. Fix real en el origen: github.com/Honkonx/udocker (fork de
    # indigo-dc/udocker con HostInfo.osversion() mapeando "android"->"linux") -- se instala
    # desde ahi en vez de PyPI oficial, sin necesitar el flag --platform en ningun lado.
    # --no-cache-dir --force-reinstall: sin esto pip puede reusar una build cacheada de una
    # clonación vieja del fork (de antes de que el patch se subiera ahí) en vez de volver a
    # clonar y reconstruir contra el HEAD real — ver comentario largo de
    # udocker_fork_patch_active() arriba, causa raíz confirmada en dispositivo real.
    if pip3 install --quiet --upgrade --no-cache-dir --force-reinstall "git+https://github.com/Honkonx/udocker.git" 2>/dev/null || \
       pip install --quiet --upgrade --no-cache-dir --force-reinstall "git+https://github.com/Honkonx/udocker.git" 2>/dev/null; then
      if udocker_fork_patch_active; then
        touch "$HOME/.udocker_fork_installed"
      else
        rm -f "$HOME/.udocker_fork_installed"
        warn "udocker instalado desde el fork pero el patch de plataforma android->linux no quedó activo — las descargas de imágenes pueden seguir fallando, revisar github.com/Honkonx/udocker manualmente"
      fi
    elif pip3 install --quiet --upgrade --no-cache-dir --force-reinstall udocker 2>/dev/null || pip install --quiet --upgrade --no-cache-dir --force-reinstall udocker 2>/dev/null; then
      rm -f "$HOME/.udocker_fork_installed"
      warn "udocker instalado desde PyPI oficial (el fork no estuvo disponible) — las descargas de imágenes van a fallar con 'no image found in manifest for platform (android/arm64)' hasta que se reintente la instalación"
    else
      error "No se pudo instalar udocker (ni desde el fork ni desde PyPI oficial)"
    fi
  fi

  # Verificación real contra el filesystem (no el exit code de "udocker
  # install", que puede ser 0 aunque la extracción del tarball haya fallado
  # a medias — mismo criterio que n8n.sh).
  #
  # Bug real #3 confirmado por ADB (docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md,
  # 2026-08-27): pasarle una URL remota directo a UDOCKER_TARBALL falla SIEMPRE en este
  # dispositivo aunque la URL responda 200 OK y "curl -o archivo <url>" manual descargue el
  # tarball completo sin problema — el downloader interno de udocker (paquete pip, no el
  # udocker.py viejo) no completa la descarga por esa vía en este entorno. Confirmado con
  # prueba real: "UDOCKER_TARBALL=/ruta/local/ya-descargado.tgz udocker install --force" SÍ
  # funciona. Fix: descargar el tarball nosotros mismos con curl a un archivo temporal y
  # apuntar UDOCKER_TARBALL a esa ruta local, no a la URL remota.
  _UDOCKER_READY=false
  # Nombre único por PID — bug real confirmado (docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md,
  # 2026-08-27): udocker.sh y n8n.sh corriendo en paralelo (misma tanda de instalación
  # concurrente) compartían este mismo nombre fijo y se pisaban el archivo temporal a mitad
  # de descarga, corrompiendo ambas instalaciones.
  _udocker_tmp_tarball="$HOME/.udocker_tarball_tmp.$$.tar.gz"
  for _mirror in "${UDOCKER_TARBALL_MIRRORS[@]}"; do
    info "Descargando udockertools (mirror: ${_mirror##*/})..."
    rm -f "$_udocker_tmp_tarball" 2>/dev/null
    if curl -fsSL -m 120 -o "$_udocker_tmp_tarball" "$_mirror" && [ -s "$_udocker_tmp_tarball" ]; then
      export UDOCKER_TARBALL="$_udocker_tmp_tarball"
      info "Inicializando udockertools (mirror: ${_mirror##*/})..."
      udocker install --force &>/dev/null
      if [ -s "$HOME/.udocker/lib/VERSION" ]; then
        _UDOCKER_READY=true
        log "udockertools instalado (mirror: ${_mirror##*/})"
        break
      fi
    fi
    warn "Mirror ${_mirror##*/} no funcionó — probando siguiente..."
  done
  rm -f "$_udocker_tmp_tarball" 2>/dev/null

  $_UDOCKER_READY || error "No se pudo instalar udockertools (probados ${#UDOCKER_TARBALL_MIRRORS[@]} mirrors) — revisá la conexión e intentá de nuevo"

  # P2 (proot puro) — único modo realista sin root/fakechroot confiable.
  touch "$HOME/.udocker_force_p2"
  mark_done "udocker_install"
fi

# ============================================================
# PASO 2 — Scripts wrapper de uso general
# ============================================================
step "2/2 Creando scripts wrapper"

mkdir -p "$UDOCKER_SCRIPTS"

cat > "$UDOCKER_SCRIPTS/pull.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# USO: pull.sh <imagen>   (ej: pull.sh alpine:latest)
export UDOCKER_USE_PROOT_EXECUTABLE="${PREFIX:-/data/data/com.termux/files/usr}/bin/proot"
# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# sin TMPDIR, udocker (Python) cae al fallback "/tmp" para archivos temporales que genera para
# el bind-mount de un contenedor (ej. un "passwd" sintético) — "/tmp" no existe como filesystem
# real en Termux, y udocker revienta con "Error: invalid host volume path: /tmp/udocker-...".
# Mismo patrón/causa raíz que TMPDIR en applyTermuxEnv() (ProcessBuilderExt.kt), acá aplicado a
# estos wrappers porque también se usan desde una terminal real (login shell), no solo desde la
# app — un login shell de Termux tampoco exporta TMPDIR por defecto.
export TMPDIR="${PREFIX:-/data/data/com.termux/files/usr}/tmp"
mkdir -p "$TMPDIR"
IMG="$1"
[ -z "$IMG" ] && { echo "uso: pull.sh <imagen>" >&2; exit 1; }
udocker pull "$IMG"
SCRIPT
chmod +x "$UDOCKER_SCRIPTS/pull.sh"

cat > "$UDOCKER_SCRIPTS/run.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# USO: run.sh <nombre_contenedor> <imagen> [-- comando...]
# Crea (si hace falta) y corre <imagen> con nombre <nombre_contenedor>,
# forzando execmode P2. Args tras "--" se pasan como comando dentro del
# contenedor; sin ellos corre el ENTRYPOINT/CMD de la imagen.
export UDOCKER_USE_PROOT_EXECUTABLE="${PREFIX:-/data/data/com.termux/files/usr}/bin/proot"
# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# sin TMPDIR, udocker (Python) cae al fallback "/tmp" para archivos temporales que genera para
# el bind-mount de un contenedor (ej. un "passwd" sintético) — "/tmp" no existe como filesystem
# real en Termux, y udocker revienta con "Error: invalid host volume path: /tmp/udocker-...".
# Mismo patrón/causa raíz que TMPDIR en applyTermuxEnv() (ProcessBuilderExt.kt), acá aplicado a
# estos wrappers porque también se usan desde una terminal real (login shell), no solo desde la
# app — un login shell de Termux tampoco exporta TMPDIR por defecto.
export TMPDIR="${PREFIX:-/data/data/com.termux/files/usr}/tmp"
mkdir -p "$TMPDIR"
NAME="$1"; IMG="$2"; shift 2 2>/dev/null
[ -z "$NAME" ] || [ -z "$IMG" ] && { echo "uso: run.sh <nombre> <imagen> [-- comando...]" >&2; exit 1; }
[ "$1" = "--" ] && shift
if ! udocker inspect "$NAME" &>/dev/null; then
  udocker images 2>/dev/null | grep -q "$IMG" || udocker pull "$IMG" || { echo "[ERROR] No se pudo bajar $IMG" >&2; exit 1; }
  udocker create --name="$NAME" "$IMG" || { echo "[ERROR] No se pudo crear el contenedor" >&2; exit 1; }
fi
udocker setup --execmode=P2 "$NAME" 2>/dev/null || true
udocker run "$NAME" "$@"
SCRIPT
chmod +x "$UDOCKER_SCRIPTS/run.sh"

cat > "$UDOCKER_SCRIPTS/list.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
echo "── Imágenes ──"
udocker images
echo ""
echo "── Contenedores ──"
# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# "udocker ps -a" — "-a" NO es un flag real de este udocker (confirmado con "udocker ps --help":
# solo admite -m/-s/-p) — Docker CLI sí tiene "-a" (mostrar contenedores parados también), pero
# udocker no lo replica; con un flag inválido, "udocker ps" fallaba con "Error: syntax error at:
# -a" y nunca mostraba nada. "udocker ps" sin flags ya lista TODOS los contenedores (no separa
# corriendo/parado como Docker real), así que no hace falta ningún flag acá.
udocker ps
SCRIPT
chmod +x "$UDOCKER_SCRIPTS/list.sh"

cat > "$UDOCKER_SCRIPTS/rm.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
# USO: rm.sh <nombre_contenedor>
NAME="$1"
[ -z "$NAME" ] && { echo "uso: rm.sh <nombre_contenedor>" >&2; exit 1; }
udocker rm "$NAME" && echo "[OK] Contenedor '$NAME' eliminado"
SCRIPT
chmod +x "$UDOCKER_SCRIPTS/rm.sh"

log "Scripts wrapper creados en $UDOCKER_SCRIPTS (pull.sh, run.sh, list.sh, rm.sh)"

# ── Registry ─────────────────────────────────────────────────
# Ver comentario arriba (PASO 0 → rama "ya instalado") — mismo fix, misma causa raíz.
UDOCKER_VER=$(udocker version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
registry_install udocker "${UDOCKER_VER:-unknown}" "execmode=P2" "scripts=$UDOCKER_SCRIPTS"

rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  udocker instalado ✓${NC}"
  echo ""
  echo "  Bajar imagen:     bash ~/scripts/udocker/pull.sh alpine:latest"
  echo "  Correr:           bash ~/scripts/udocker/run.sh mi_alpine alpine:latest -- sh"
  echo "  Listar:           bash ~/scripts/udocker/list.sh"
  echo "  Eliminar:         bash ~/scripts/udocker/rm.sh mi_alpine"
  echo ""
fi

notify_event "udocker" "install_done" "${UDOCKER_VER:-unknown}"
log "Instalación de udocker completada"
exit 0
