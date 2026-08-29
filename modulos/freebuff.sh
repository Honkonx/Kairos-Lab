#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · freebuff.sh (silent mode)
#  Instala Freebuff (CodebuffAI) en Termux ARM64
#
#  USO DESDE APP (KairosApp):
#    bash freebuff.sh --silent
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA (según arquitectura, 3 métodos en cascada en aarch64):
#    ✅ MÉTODO 1 (preferido, aarch64): runtime Bun NATIVO Bionic desde
#       github.com/Honkonx/bun-termux (fork de Hope2333/bun-termux) — un
#       build real de Bun para Android/Bionic (PIE linkeado contra
#       /system/bin/linker64), SIN glibc, SIN patchelf para el runtime `bun`
#       en sí. Se usa `bun install -g freebuff@latest --os=linux
#       --backend=copyfile` (ver BUG REAL #1 más abajo) para traer el
#       paquete npm de Freebuff — que a su vez es solo un LAUNCHER JS que
#       baja su propio binario nativo real (glibc, no Bionic) en el primer
#       run; ese binario SÍ necesita glibc+patchelf igual que el MÉTODO 2
#       (ver BUG REAL #2) — confirmado funcional 3 veces seguidas en
#       dispositivo real, a diferencia del binario que baja el MÉTODO 2.
#    ✅ MÉTODO 2 (fallback si el 1 falla, aarch64): el BINARIO NATIVO de
#       GitHub Releases de CodebuffAI (freebuff-linux-arm64.tar.gz) + glibc
#       + patchelf — patrón de core-termux (ver termux-ai-stack-dev/
#       comparativas/core-termux-main/core/tools/ai/freebuff/install.sh).
#       Es el método que confirmadamente segfaulteó en una ronda anterior en
#       algún dispositivo real ("panic(main thread): Segmentation fault ...
#       Bun has crashed") — se conserva como alternativa, no se elimina.
#    ✅ MÉTODO 3 (fallback final, y único camino en no-aarch64): CLI vía
#       npm (freebuff@latest) sobre Node.js.
#
#  NOTA IMPORTANTE (x86 vs arm):
#    El método npm puede traer un binario x86_64 embebido que NO corre en
#    ARM64 — por eso en aarch64 se prefieren los métodos nativos (1 y 2)
#    antes que npm.
#
#  BUG REAL #1 (confirmado por ADB en dispositivo real, 2026-08-29): `bun
#  install -g freebuff@latest` a secas se saltea el paquete por completo
#  ("Skip installing freebuff - os mismatch") — el runtime bun nativo
#  reporta `process.platform === "android"`, y el package.json real de
#  freebuff en el registry solo declara `os:["darwin","linux","win32"]`
#  (sin "android"), así que bun lo descarta pese a ser JS puro sin binario
#  nativo empaquetado. `--os=linux` fuerza la instalación; con eso solo, el
#  backend default ("hardlink") deja node_modules vacío con "EACCES:
#  Permission denied" silencioso en este dispositivo — `--backend=copyfile`
#  lo resuelve. Ver comentario completo en _freebuff_install_via_native_bun().
#
#  BUG REAL #2 (mismo día): el launcher instalado (index.js) baja su propio
#  binario nativo (glibc) recién al primer run, y el chequeo de plataforma
#  se repite ahí ("Unsupported platform: android arm64") — se fuerza con
#  `FREEBUFF_BINARY_TARGET=linux-arm64` (override real que expone el propio
#  launcher.js). El binario descargado queda en
#  `$HOME/.config/manicode/freebuff` (ruta hardcodeada en el launcher,
#  branding legado "manicode") sin interpreter Bionic-compatible — mismo fix
#  que el MÉTODO 2: patchelf apuntando al ld.so de glibc de Termux.
#  Confirmado funcional (sin segfault, `freebuff --version` → "0.0.160",
#  exit 0) repetido 3 veces en este dispositivo, con el binario real que baja
#  el propio launcher — no se investigó si difiere del asset de GitHub que
#  usa el MÉTODO 2 (que sí segfaulteó en una ronda anterior).
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Ningún login — Freebuff es gratuito, sin cuenta/API key requerida
#       (github.com/CodebuffAI/freebuff, confirmado 2026-08-12)
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.3.0 | Agosto 2026 (MÉTODO 1 arreglado de verdad: bun install -g
#  freebuff quedaba silenciosamente vacío por "os mismatch" + EACCES; ahora
#  usa --os=linux --backend=copyfile + fuerza la descarga del binario nativo
#  real vía FREEBUFF_BINARY_TARGET + patchelf, igual patrón que MÉTODO 2)
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
{"id":"freebuff","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# 2 canales posibles (nativo ARM64 glibc, o npm) — se resuelve el binario
# real vía `command -v` en vez de asumir una ruta fija, ya que difiere
# según el canal (ver "channel=" en el registry, docs/arquitectura/
# MODULEDEB_GENERICO.md §5.2 punto 2 "CLIs simples de un solo binario").
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _bin=$(command -v freebuff 2>/dev/null || echo "$TERMUX_PREFIX/bin/freebuff")
  jq -n \
    --arg path "$_bin" \
    --arg glob_native "$HOME/.local/share/freebuff/**" \
    --arg glob_bun_install "$HOME/.bun/**" \
    --arg glob_bun_runtime "$TERMUX_PREFIX/lib/bun-termux/**" \
    --arg verify "command -v freebuff >/dev/null 2>&1 && freebuff --version >/dev/null 2>&1" \
    '{
      id: "freebuff",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-freebuff",
      version_registry_key: "freebuff.version",
      files: [{path: $path, required: true, note: "Wrapper/binario real de freebuff, resuelto por PATH al momento de empaquetar (nativo ARM64 con patchelf, runtime Bun nativo, o symlink npm — según channel= en el registry)"}],
      file_globs: [
        {pattern: $glob_native, required: false, note: "canal nativo glibc+patchelf: binario real que el wrapper exec-a (~/.local/share/freebuff/<binario>) — solo existe si channel=native_glibc"},
        {pattern: $glob_bun_install, required: false, note: "canal Bun nativo: freebuff instalado globalmente vía bun install -g (~/.bun/bin/freebuff + soporte) — solo existe si channel=bun_native"},
        {pattern: $glob_bun_runtime, required: false, note: "canal Bun nativo: runtime real de bun-termux copiado del .deb ($TERMUX_PREFIX/lib/bun-termux/bun) que $TERMUX_PREFIX/bin/bun exec-a — sin esto el propio bun (y por lo tanto freebuff) queda roto, solo existe si channel=bun_native"}
      ],
      dependencies: [],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "Si el canal instalado fue npm, el árbol de node_modules global no se empaqueta — solo el wrapper/symlink resuelto por PATH",
        "No reinstala Node.js/glibc — asume que el device destino ya tiene el mismo Termux base",
        "Los 3 globs de canal se incluyen todos (optional) porque el manifiesto no lee channel= del registry en este punto — solo el glob del canal realmente instalado va a tener contenido, los otros se omiten en silencio (moduledeb.sh no falla sobre globs opcionales vacíos)"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_freebuff_checkpoint"
FREEBUFF_PKG="freebuff@latest"
FREE_DIR="$HOME/.local/share/freebuff"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^freebuff_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "freebuff_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^freebuff_${1}=done" "$CHECKPOINT" 2>/dev/null; }

# ── Detección de arquitectura ─────────────────────────────────
# El punto clave: freebuff no es igual para x86 y para arm — los npm
# packages pueden traer binario x86_64 que no corre en ARM64. En aarch64
# se usa el binario nativo (glibc + patchelf); en otras, npm.
_ARCH=$(uname -m 2>/dev/null || echo "unknown")
_NATIVE=false
[ "$_ARCH" = "aarch64" ] && _NATIVE=true

get_installed_ver() {
  local v=""
  command -v freebuff &>/dev/null && v=$(freebuff --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$v" ] && [ -x "$FREE_DIR/freebuff" ] && v=$("$FREE_DIR/freebuff" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  echo "$v"
}

# Descarga el binario nativo ARM64 + glibc + patchelf (patrón core-termux).
#
# BUGS REALES corregidos 2026-08-16 (investigación real contra
# github.com/CodebuffAI, no supuestos):
#  1. El repo real con releases es CodebuffAI/freebuff — "codebuff-community"
#     es "a showcase of Codebuff projects created by our community" (sin
#     releases propios). La API a ese repo devolvía vacío/404 y el script
#     caía siempre al fallback npm.
#  2. Todas las releases de CodebuffAI/freebuff son prerelease (tags tipo
#     "v1.0.420-beta.185") — /releases/latest de GitHub IGNORA prereleases y
#     devuelve 404 si no hay ninguna release "estable" marcada. Hay que pedir
#     la lista /releases (ya viene ordenada, más nueva primero) y tomar la
#     primera entrada.
#  3. El tag real no tiene el prefijo "freebuff-v" que asumía el regex viejo
#     (releases/latest?tag_name esperaba "freebuff-vX.Y.Z") — el tag real es
#     literalmente "vX.Y.Z-beta.NNN".
#  4. El binario dentro del tarball se llama "codecane" (rebrand interno del
#     proyecto, confirmado en el nombre real de los assets:
#     codecane-linux-arm64.tar.gz), NO "freebuff" — el chequeo viejo de
#     "$FREE_DIR/freebuff" tras extraer siempre fallaba.
# En vez de volver a hardcodear nombres (que ya cambiaron una vez), se
# descubren en vivo desde el JSON real de la release: tag_name real +
# cualquier asset "*-linux-arm64.tar.gz" + el único binario ejecutable
# resultante de extraerlo.
_freebuff_download_native() {
  info "Consultando releases reales (GitHub API CodebuffAI/freebuff)..."
  local releases_json
  releases_json=$(curl -fsSL "https://api.github.com/repos/CodebuffAI/freebuff/releases?per_page=5" 2>/dev/null)
  [ -z "$releases_json" ] && return 1

  local latest url
  latest=$(echo "$releases_json" | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')
  [ -z "$latest" ] && return 1
  FREE_LATEST="$latest"

  url=$(echo "$releases_json" | grep -o '"browser_download_url": *"[^"]*linux-arm64\.tar\.gz"' | \
    head -1 | grep -o 'https://[^"]*')
  [ -z "$url" ] && return 1

  local tarball; tarball=$(basename "$url")
  local tmp="$HOME/tmp/freebuff_${latest//\//_}_$$"
  mkdir -p "$tmp" "$FREE_DIR"

  info "Descargando binario nativo ARM64 ($tarball, release $latest)..."
  curl -fsSL "$url" -o "$tmp/$tarball" 2>/dev/null || { rm -rf "$tmp"; return 1; }
  tar -zxf "$tmp/$tarball" -C "$FREE_DIR" 2>/dev/null || { rm -rf "$tmp"; return 1; }
  rm -rf "$tmp"

  # No asumir el nombre del binario extraído (ya cambió de "freebuff" a
  # "codecane" una vez) — tomar el único archivo ejecutable real del
  # directorio de destino.
  local _real_bin
  _real_bin=$(find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz" | head -1)
  [ -z "$_real_bin" ] && return 1
  chmod +x "$_real_bin"
  FREE_REAL_BIN="$_real_bin"

  # patchelf: el binario oficial está linkeado contra el loader glibc de
  # Linux — apuntar el ELF interpreter al glibc de Termux (mismo patrón que
  # antigravity/claude/openclaw/cursor).
  #
  # BUG REAL (2026-08-16, log real: "[WARN] patchelf falló" seguido de
  # "[OK] instalado correctamente" — falso positivo): "patchelf-glibc" es el
  # NOMBRE DEL PAQUETE de Termux, no el nombre del comando/binario que
  # instala — el binario real queda en $PREFIX/glibc/bin/patchelf. claude.sh
  # ya lo hace bien (PATCHELF="$TERMUX_PREFIX/glibc/bin/patchelf", ver
  # también el comentario idéntico en install_claude.sh). Acá se invocaba
  # "patchelf-glibc" como comando — no existe, "command not found" silencioso
  # por el 2>/dev/null, y el script seguía como si nada.
  local _patchelf_bin="$TERMUX_PREFIX/glibc/bin/patchelf"
  if [ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ]; then
    info "Aplicando patchelf al binario nativo..."
    if [ -x "$_patchelf_bin" ]; then
      "$_patchelf_bin" --set-interpreter "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" \
        "$_real_bin" 2>/dev/null || \
        warn "patchelf falló — el binario puede requerir ajuste manual"
    else
      warn "patchelf no encontrado en $_patchelf_bin — el binario puede requerir ajuste manual"
    fi
  fi

  # Wrapper en $PREFIX/bin (igual que los demás módulos glibc) — apunta al
  # binario real detectado (puede llamarse distinto al módulo, ver arriba).
  cat > "$TERMUX_PREFIX/bin/freebuff" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
unset LD_PRELOAD
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:\$PATH"
exec "$_real_bin" "\$@"
WRAPPER
  chmod +x "$TERMUX_PREFIX/bin/freebuff"

  # Verificación FUNCIONAL real (no solo "los pasos no tiraron error") — mismo
  # principio ya aplicado a mistralvibe/n8n/XFCE4 (ver docs/humano/humano121.md):
  # si el binario nativo (con o sin patchelf) no responde --version, el método
  # nativo se declara fallido de verdad y se cae a npm, en vez de marcar [OK]
  # con un binario que en realidad no corre.
  "$TERMUX_PREFIX/bin/freebuff" --version >/dev/null 2>&1 || {
    warn "Binario nativo instalado pero no responde a --version — descartando método nativo"
    return 1
  }
  return 0
}

# ── Método 1 (preferido) — runtime Bun NATIVO Bionic (bun-termux) ───
# A diferencia de _freebuff_download_native() (arriba), este método NO
# descarga el binario standalone "codecane" de CodebuffAI (el que
# segfaultea) — descarga el runtime `bun` real, compilado para Android/
# Bionic (PIE contra /system/bin/linker64, sin glibc), y usa ese runtime
# para instalar/correr Freebuff como paquete npm/bun normal. Se prueban 2
# repos en orden: el fork del proyecto (Honkonx/bun-termux) primero, y si
# no tiene el asset todavía adjunto a su release (forks de GitHub no
# copian los assets del repo original automáticamente), el upstream real
# (Hope2333/bun-termux) como fallback dentro del propio método 1 — no
# confundir con el fallback al método 2, que es un método distinto.
BUN_SOURCE_REPO=""
_bun_download_native_runtime() {
  command -v dpkg-deb &>/dev/null || { warn "dpkg-deb no disponible — no se puede extraer el paquete .deb de bun nativo"; return 1; }
  command -v curl &>/dev/null || return 1

  local _repo _releases_json _asset_url=""
  for _repo in "Honkonx/bun-termux" "Hope2333/bun-termux"; do
    _releases_json=$(curl -fsSL "https://api.github.com/repos/${_repo}/releases?per_page=5" 2>/dev/null)
    [ -z "$_releases_json" ] && continue
    _asset_url=$(echo "$_releases_json" | grep -o '"browser_download_url": *"[^"]*_aarch64\.deb"' | \
      head -1 | grep -o 'https://[^"]*')
    if [ -n "$_asset_url" ]; then BUN_SOURCE_REPO="$_repo"; break; fi
  done
  if [ -z "$_asset_url" ]; then
    warn "No se encontró un asset .deb aarch64 de bun nativo (ni en Honkonx/bun-termux ni en el upstream Hope2333/bun-termux)"
    return 1
  fi

  local _tmp="$HOME/tmp/bun_native_$$"
  local _deb="$_tmp/$(basename "$_asset_url")"
  mkdir -p "$_tmp"
  info "Descargando runtime bun nativo Bionic ($(basename "$_asset_url"), repo $BUN_SOURCE_REPO)..."
  curl -fsSL "$_asset_url" -o "$_deb" 2>/dev/null || { rm -rf "$_tmp"; return 1; }

  local _extract="$_tmp/extract"
  mkdir -p "$_extract"
  dpkg-deb -x "$_deb" "$_extract" 2>/dev/null || { rm -rf "$_tmp"; return 1; }

  # Bug real confirmado por ADB en dispositivo real (2026-08-28, docs/humano281.md), con el
  # .deb real descargado y extraído a mano para confirmar el layout exacto: dpkg-deb -x deja
  # el árbol bajo la RUTA ABSOLUTA COMPLETA "$_extract/data/data/com.termux/files/usr/..."
  # (así empaqueta Termux sus .deb — no relocatable, la ruta absoluta va adentro del propio
  # data.tar), NO bajo "$_extract/usr/" como asumía el código anterior. Ese código buscaba un
  # archivo suelto llamado "bun" con `find` y lo copiaba solo — el .deb real tiene un WRAPPER
  # en usr/bin/bun que ejecuta el binario real en usr/lib/bun-termux/bun (paquete con
  # wrapper+payload separados), así que copiar un solo archivo dejaba al wrapper sin su
  # dependencia: "bun: line 2: .../lib/bun-termux/bun: No such file or directory". Fix real:
  # copiar el árbol usr/ COMPLETO desde la ruta absoluta real extraída (preserva la relación
  # wrapper→lib intacta), no un archivo suelto — mismo resultado que "dpkg -i" real.
  local _extract_usr="$_extract/data/data/com.termux/files/usr"
  if [ ! -d "$_extract_usr" ]; then
    # Fallback defensivo por si una versión futura del paquete cambia el layout a uno
    # relocatable (usr/ en la raíz del archivo, sin la ruta absoluta de Termux adentro).
    _extract_usr="$_extract/usr"
  fi
  if [ ! -d "$_extract_usr" ]; then
    warn "El .deb de bun nativo no tiene ningún layout usr/ reconocible tras la extracción"
    rm -rf "$_tmp"
    return 1
  fi
  cp -a "$_extract_usr/." "$TERMUX_PREFIX/" || {
    warn "No se pudo copiar el árbol usr/ del .deb de bun nativo a $TERMUX_PREFIX"
    rm -rf "$_tmp"
    return 1
  }
  chmod +x "$TERMUX_PREFIX/bin/bun" 2>/dev/null
  find "$TERMUX_PREFIX/lib/bun-termux" -maxdepth 1 -type f -exec chmod +x {} \; 2>/dev/null
  rm -rf "$_tmp"

  # Verificación FUNCIONAL real del runtime en sí (no solo que la copia haya
  # funcionado) — mismo principio de post-condición real que el resto del
  # script (.claude/rules/empirical-verification-before-fix.md).
  "$TERMUX_PREFIX/bin/bun" --version >/dev/null 2>&1 || {
    warn "bun nativo Bionic instalado pero no responde a --version — descartando runtime nativo"
    return 1
  }
  return 0
}

# Instala Freebuff arriba del runtime bun nativo ya confirmado funcional.
#
# BUG REAL confirmado por ADB en dispositivo real (2026-08-29): `bun install -g
# freebuff@latest` a secas se saltea el paquete por completo — log real:
# "Skip installing freebuff - os mismatch". Causa raíz: el runtime bun nativo
# Bionic reporta `process.platform === "android"` (confirmado con
# `bun -e "console.log(process.platform)"`), y el package.json real de
# freebuff en el registry (registry.npmjs.org/freebuff/latest) declara
# `"os":["darwin","linux","win32"]` — sin "android", así que bun lo descarta
# como incompatible. El chequeo es innecesariamente estricto: freebuff es un
# paquete JS puro (`"bin":{"freebuff":"index.js"}`, sin ningún binario nativo
# empaquetado en el propio paquete npm — el binario nativo real lo baja el
# propio index.js/launcher.js en el primer `run`, ver más abajo). `--os=linux`
# fuerza a bun a tratarlo como compatible. Con `--os=linux` solo, el backend
# de instalación default ("hardlink") deja node_modules/freebuff VACÍO con
# "EACCES: Permission denied" silencioso en este dispositivo (confirmado, no
# hipotético) — `--backend=copyfile` lo resuelve copiando los archivos en vez
# de hardlinkearlos desde el store de bun.
#
# Con eso resuelto, freebuff SÍ se instala (index.js + launcher.js reales en
# disco) pero index.js es solo un LAUNCHER — al primer `freebuff --version`
# intenta bajar el binario nativo real (~49MB) desde codebuff.com y falla con
# "Unsupported platform: android arm64" (mismo chequeo de os, ahora dentro del
# propio launcher). `FREEBUFF_BINARY_TARGET=linux-arm64` (override real que
# el propio launcher.js expone, ver función getTargetOverride()) fuerza la
# descarga del binario linux-arm64 real — ahí SÍ baja (confirmado, 131.4MB) y
# queda en `$HOME/.config/manicode/freebuff` (ruta hardcodeada dentro del
# propio launcher.js — branding legado "manicode", no configurable por env).
# Ese binario baja sin permiso de ejecución real utilizable directo (glibc,
# no Bionic) — falla con `posix_spawn ENOENT` al intentar correrlo (síntoma
# real de un ELF interpreter inexistente, no de un archivo faltante). Mismo
# fix que el MÉTODO 2 (glibc + patchelf) de más abajo: patchelf apuntando el
# intérprete al ld.so de glibc de Termux — **confirmado funcional 3 veces
# seguidas en este dispositivo real** (`freebuff --version` → "0.0.160",
# exit 0, sin segfault) usando el binario real bajado por el propio launcher
# — a diferencia del binario descargado por _freebuff_download_native()
# (MÉTODO 2, vía GitHub releases de CodebuffAI/freebuff) que sí segfaulteaba
# en una ronda anterior (docs de esta ronda) — o el binario cambió entre
# releases, o difiere algo real entre el asset de GitHub y el de
# codebuff.com; no se investigó más a fondo, la vía de este método (launcher
# oficial + patchelf) es la que se confirmó estable acá.
#
# Se ejecuta el binario nativo patcheado DIRECTO desde el wrapper (sin pasar
# por bun/node en cada invocación) — más rápido, y es exactamente lo que
# corrimos en la verificación real de arriba.
_freebuff_install_via_native_bun() {
  info "Instalando Freebuff con el runtime bun nativo (bun install -g ${FREEBUFF_PKG})..."
  export BUN_INSTALL="$HOME/.bun"
  mkdir -p "$BUN_INSTALL/bin"
  "$TERMUX_PREFIX/bin/bun" install -g "$FREEBUFF_PKG" --os=linux --backend=copyfile >/dev/null 2>&1 || {
    warn "'bun install -g ${FREEBUFF_PKG} --os=linux --backend=copyfile' falló"
    return 1
  }

  local _launcher_js="$BUN_INSTALL/install/global/node_modules/freebuff/index.js"
  if [ ! -f "$_launcher_js" ]; then
    _launcher_js=$(find "$BUN_INSTALL/install/global/node_modules/freebuff" -maxdepth 1 -type f -name "index.js" 2>/dev/null | head -1)
  fi
  if [ -z "$_launcher_js" ] || [ ! -f "$_launcher_js" ]; then
    warn "freebuff/index.js no se encontró en $BUN_INSTALL tras 'bun install -g --os=linux'"
    return 1
  fi

  # glibc + patchelf: mismas dependencias que el MÉTODO 2 (glibc), necesarias
  # acá también porque el binario nativo real que baja el launcher es glibc,
  # no Bionic. Se instalan acá mismo (no se depende del bloque PASO 2, que
  # solo corre si este método falla).
  local _glibc_ld="$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1"
  local _patchelf_bin="$TERMUX_PREFIX/glibc/bin/patchelf"
  if [ ! -f "$_glibc_ld" ] || [ ! -x "$_patchelf_bin" ]; then
    local _MISSING_DEPS=()
    [ -f "$_glibc_ld" ] || _MISSING_DEPS+=("glibc-repo" "glibc")
    [ -x "$_patchelf_bin" ] || _MISSING_DEPS+=("patchelf-glibc")
    info "Instalando dependencias glibc para el binario nativo de Freebuff: ${_MISSING_DEPS[*]}"
    pkg_update_with_fallback
    pkg install -y "${_MISSING_DEPS[@]}" \
      -o Dpkg::Options::="--force-confdef" \
      -o Dpkg::Options::="--force-confold" 2>/dev/null || {
      warn "No se pudieron instalar las dependencias glibc para Freebuff"
      return 1
    }
    [ -f "$_glibc_ld" ] && [ -x "$_patchelf_bin" ] || {
      warn "glibc/patchelf no disponibles tras la instalación"
      return 1
    }
  fi

  # Disparar la descarga del binario nativo real (linux-arm64) — se ignora el
  # exit code: en este punto el binario todavía no tiene el interpreter
  # parcheado, así que SIEMPRE falla al intentar correrlo (posix_spawn ENOENT
  # esperado) — lo único que importa acá es que el archivo haya quedado en
  # disco.
  local _native_bin="$HOME/.config/manicode/freebuff"
  rm -f "$_native_bin"
  FREEBUFF_BINARY_TARGET=linux-arm64 "$TERMUX_PREFIX/bin/bun" "$_launcher_js" --version >/dev/null 2>&1
  if [ ! -f "$_native_bin" ]; then
    warn "El launcher de Freebuff no descargó el binario nativo linux-arm64 en $_native_bin"
    return 1
  fi
  chmod +x "$_native_bin"

  info "Aplicando patchelf al binario nativo de Freebuff..."
  "$_patchelf_bin" --set-interpreter "$_glibc_ld" "$_native_bin" 2>/dev/null || {
    warn "patchelf falló sobre el binario nativo de Freebuff"
    return 1
  }

  cat > "$TERMUX_PREFIX/bin/freebuff" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
unset LD_PRELOAD
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:\$PATH"
exec "$_native_bin" "\$@"
WRAPPER
  chmod +x "$TERMUX_PREFIX/bin/freebuff"

  # Verificación FUNCIONAL real — confirma que Freebuff corre de verdad
  # (no solo que "bun install -g"/la descarga/patchelf salieron con exit 0).
  "$TERMUX_PREFIX/bin/freebuff" --version >/dev/null 2>&1 || {
    warn "Freebuff instalado vía bun nativo + patchelf pero no responde a --version — descartando este método"
    return 1
  }
  return 0
}

if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ▶ FREEBUFF — Instalador                ║"
  echo "  ║  CodebuffAI · Termux ARM64               ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
_INSTALLED_VER=$(get_installed_ver)
if [ -n "$_INSTALLED_VER" ] && ! $FORCE; then
  log "Freebuff ya instalado (v${_INSTALLED_VER})"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar Freebuff? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

if ! $_NATIVE; then
  warn "Arquitectura '$_ARCH' detectada — método npm (el paquete npm puede traer binario x86_64 que no corre en ARM64; el binario nativo de releases es arm64)"
fi

# ── PASO 1 — runtime Bun NATIVO Bionic (método preferido, aarch64) ──
_NATIVE_BUN=false
if $_NATIVE; then
  step "PASO 1 — Runtime Bun nativo Bionic (bun-termux, sin glibc/patchelf)"
  if check_done "native_bun_install"; then
    log "Freebuff (runtime bun nativo) ya instalado [checkpoint]"
    _NATIVE_BUN=true
  else
    if _bun_download_native_runtime && _freebuff_install_via_native_bun; then
      log "Freebuff instalado vía runtime Bun nativo Bionic (bun-termux)"
      mark_done "native_bun_install"
      _NATIVE_BUN=true
    else
      warn "Falló el runtime Bun nativo Bionic — fallback al binario glibc+patchelf de CodebuffAI"
    fi
  fi
fi

# ── PASO 2 — glibc (fallback nativo aarch64, método CodebuffAI) ─────
if $_NATIVE && ! $_NATIVE_BUN; then
  step "PASO 2 — Capa de compatibilidad glibc (binario nativo ARM64)"
  if check_done "glibc"; then
    log "glibc ya verificado [checkpoint]"
  else
    _MISSING_DEPS=()
    [ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] || _MISSING_DEPS+=("glibc-repo" "glibc")
    [ -x "$TERMUX_PREFIX/glibc/bin/patchelf" ] || _MISSING_DEPS+=("patchelf-glibc")
    command -v curl &>/dev/null || _MISSING_DEPS+=("curl")

    if [ ${#_MISSING_DEPS[@]} -gt 0 ]; then
      info "Instalando: ${_MISSING_DEPS[*]}"
      pkg_update_with_fallback
      pkg install -y "${_MISSING_DEPS[@]}" \
        -o Dpkg::Options::="--force-confdef" \
        -o Dpkg::Options::="--force-confold" 2>/dev/null || \
        error "No se pudieron instalar las dependencias glibc"
      [ -f "$TERMUX_PREFIX/glibc/lib/ld-linux-aarch64.so.1" ] || \
        error "glibc ld.so no encontrado tras la instalación"
    fi

    mark_done "glibc"
    log "Capa glibc verificada"
  fi
fi

# ── PASO 3 — binario nativo ARM64 CodebuffAI (glibc + patchelf) ─────
if $_NATIVE && ! $_NATIVE_BUN; then
  step "PASO 3 — Instalando Freebuff nativo ARM64 (glibc + patchelf, binario CodebuffAI)"
  if check_done "native_install"; then
    log "Freebuff nativo ya instalado [checkpoint]"
  else
    if _freebuff_download_native; then
      log "Freebuff nativo ARM64 instalado (glibc + patchelf)"
      mark_done "native_install"
    else
      warn "Falló el método nativo glibc+patchelf (descarga o verificación funcional) — fallback a npm"
      _NATIVE=false
    fi
  fi
fi

# ── PASO 4 — Node.js (solo método npm, último fallback) ──────
if ! $_NATIVE && ! $_NATIVE_BUN; then
  step "PASO 4 — Verificando Node.js"
  if check_done "node"; then
    log "Node.js ya verificado [checkpoint]"
  else
    if command -v node &>/dev/null && command -v npm &>/dev/null; then
      log "Node.js detectado: $(node --version 2>/dev/null)"
      mark_done "node"
    else
      info "Instalando nodejs-lts..."
      pkg_update_with_fallback
      pkg install nodejs-lts -y 2>/dev/null || error "No se pudo instalar Node.js"
      command -v node &>/dev/null || error "Node.js no disponible tras instalación"
      log "Node.js instalado: $(node --version)"
      mark_done "node"
    fi
  fi
fi

# ── PASO 5 — npm install (último fallback) ────────────────────
if ! $_NATIVE && ! $_NATIVE_BUN; then
  step "PASO 5 — Instalando Freebuff vía npm"
  if check_done "npm_install"; then
    log "Freebuff ya instalado [checkpoint]"
  else
    info "Ejecutando: npm install -g ${FREEBUFF_PKG}"
    npm install -g "$FREEBUFF_PKG" --force 2>&1 | tail -5; [ ${PIPESTATUS[0]} -eq 0 ] || error "npm install falló"
    # Bug real confirmado por ADB (docs/humano269.md, auditoría 2026-08-27, mismo patrón ya
    # documentado en lib.sh/expo.sh): el symlink que "npm install -g" genera no ejecuta directo
    # en este dispositivo — aplicar el wrapper ANTES del chequeo de abajo, no después.
    # Bug real (auditoría freebuff/minimax 2026-08-28): se pasaba "$FREEBUFF_PKG" tal cual
    # ("freebuff@latest") — fix_npm_shebang_wrapper busca el paquete en
    # "$(npm root -g)/$_npm_pkg", así que con el sufijo "@latest" ese directorio nunca existe
    # y siempre cae al fallback (dirname del symlink resuelto) en vez de leer el "bin" real de
    # package.json. Mismo patrón ya usado en minimaxcli.sh/kimi.sh/qwencode.sh/copilotcli.sh/
    # pi.sh: pasar el nombre de paquete sin "@latest".
    fix_npm_shebang_wrapper freebuff "${FREEBUFF_PKG%@latest}"
    # verify_binary_installed() en vez de command -v a secas (2026-08-22, ver docs/humano/humano201.md).
    verify_binary_installed freebuff || error "freebuff no ejecuta tras la instalación (revisá manualmente: freebuff --version)"
    log "Freebuff instalado"
    mark_done "npm_install"
  fi
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_VER_FINAL=$(get_installed_ver)
_CHANNEL="npm"
$_NATIVE && _CHANNEL="native_arm64_glibc"
$_NATIVE_BUN && _CHANNEL="native_arm64_bun_bionic"
_DATE=$(date +%Y-%m-%d)
registry_write freebuff \
  "installed=true" \
  "version=${_VER_FINAL:-${FREE_LATEST:-?}}" \
  "channel=${_CHANNEL}" \
  "arch=${_ARCH}" \
  "install_date=${_DATE}"

notify_event "freebuff" "install_done" "$_VER_FINAL"
log "Freebuff instalado correctamente (v${_VER_FINAL:-${FREE_LATEST:-?}}, método ${_CHANNEL})"
rm -f "$CHECKPOINT"
exit 0