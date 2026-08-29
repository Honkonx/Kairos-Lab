#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · repo.sh — repo apt local + empaquetado .deb
#
#  Crea/actualiza un repositorio apt LOCAL dentro del device
#  ($PREFIX/../kairos-repo, es decir /data/data/com.termux/files/kairos-repo)
#  y empaqueta lo que un módulo ya instaló como un .deb real
#  (kairos-<id>_<version>_aarch64.deb). El usuario agrega la línea
#  que imprime `repo source` a $PREFIX/etc/apt/sources.list.d/ y puede
#  hacer `pkg install kairos-<id>` desde cualquier sesión de Termux.
#
#  SUBCOMANDOS:
#    repo init               Crea la estructura del repo + Release/Packages
#    repo add <módulo_id>    Empaqueta el binario + scripts de un módulo
#                            instalado en un .deb (dpkg-deb -b, o fallback
#                            manual con ar+tar si dpkg-deb no existe)
#    repo pack <paquete>     Empaqueta un paquete apt/pkg YA INSTALADO (de
#                            cualquiera, no solo módulos de Kairos) como .deb
#                            real desde sus archivos en disco (dpkg -L +
#                            dpkg-deb -b), preservando scripts de
#                            mantenimiento (postinst/prerm/postrm/preinst) y
#                            conffiles REALES leídos de
#                            $PREFIX/var/lib/dpkg/info/<paquete>.* cuando ese
#                            directorio existe (fallback a solo-datos con
#                            warn() si no) — registra la entrada en
#                            $HOME/kairos_local/repo_registry.json (paquete,
#                            versión, fecha, ruta del .deb, scripts_preserved)
#                            para poder reinstalarlo más adelante sin
#                            re-descargar. NO usa dpkg-repack: no está
#                            empaquetado para Termux (confirmado, no existe
#                            en termux-packages) — este subcomando es el
#                            equivalente manual real (mismo mecanismo interno
#                            que dpkg-repack usa: dpkg -L para listar los
#                            archivos ya instalados + info/ de dpkg para los
#                            scripts de mantenimiento + dpkg-deb -b para
#                            reconstruir el .deb).
#    repo publish            Regenera Packages/Packages.gz + Release
#    repo remove <paquete>   Elimina el/los .deb de <paquete> del repo local
#                            (busca "<paquete>_*.deb" y "kairos-<paquete>_*.deb"
#                            en binary-aarch64), lo saca del registry JSON si
#                            estaba (repo pack) y regenera Packages/Release —
#                            deja el repo consistente sin tocar nada a mano.
#    repo source             Imprime la línea "deb [trusted=yes] file://..."
#                            (o "deb [signed-by=...]" si el repo ya está
#                            firmado, ver `repo sign`) para sources.list.d/
#    repo keys                Lista las claves GPG secretas disponibles
#                            (gpg --list-secret-keys --keyid-format LONG) —
#                            solo lectura, no crea ni modifica nada.
#    repo sign <KEYID>       Firma el Release actual con una clave GPG YA
#                            EXISTENTE del usuario (InRelease + Release.gpg +
#                            exporta la clave pública a repo-key.gpg/.asc).
#                            NUNCA genera una clave nueva — si no hay
#                            ninguna, el usuario tiene que crearla él mismo
#                            con `gpg --full-generate-key` en una terminal
#                            real (paso interactivo, fuera del alcance de
#                            este script a propósito: pedirle passphrase/
#                            entropía a un proceso no interactivo violaría el
#                            control del usuario sobre su propia clave
#                            privada). Requiere volver a firmar después de
#                            cada `repo publish` (Release cambia).
#
#  FLAGS:
#    --silent      Sin preguntas (modo app)
#    --force       Reinit/rebuild aunque ya exista
#    --describe    Manifiesto JSON de una línea
#
#  PATRÓN VALIDADO de i-Haklab/termux-oracle (ivam3/termux-oracle, GPLv3,
#  ver docs/referencias/REFERENCIA_TERMUX_ORACLE.md y
#  AUDITORIA_CATEGORIA_CIBERSEGURIDAD.md hallazgo #6): repo apt PROPIO +
#  .deb por herramienta, para que los agentes/usuarios instalen módulos de
#  Kairos con el pkg-manager nativo en vez de scripts sueltos. No estaba
#  implementado antes en Kairos — este módulo es la adopción.
#
#  NOTA arquitectura: los .deb de Termux llevan rutas absolutas de prefijo
#  (./data/data/com.termux/files/usr/...) dentro de data.tar — así el dpkg
#  de Termux los instala directo a $PREFIX. Este script replica esa
#  convención para que `pkg install kairos-<id>` instale igual que los
#  paquetes del repo oficial.
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.4.0 | Agosto 2026 (nuevo `repo sign <KEYID>`/`repo keys`: firma
#  GPG real y OPT-IN del Release del repo — InRelease clearsign +
#  Release.gpg detached, vía `gpg --clearsign`/`gpg -abs`, siguiendo el
#  mecanismo estándar de Debian, ver docs/arquitectura/
#  AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_OFICIAL_2026-08-19.md sección
#  "Flags/opciones oficiales NO expuestas" → repo. NUNCA genera una clave
#  nueva ni la guarda embebida — usa una clave secreta que el usuario ya
#  creó por su cuenta (gpg --full-generate-key, interactivo, en terminal
#  real). Sin firma, el repo sigue funcionando exactamente igual que antes
#  con [trusted=yes] — firmar es aditivo, nunca obligatorio. `repo source`
#  ahora imprime `signed-by=<ruta a repo-key.gpg>` en vez de `[trusted=yes]`
#  automáticamente cuando detecta que el repo ya está firmado.)
#
#  VERSIÓN ANTERIOR: 1.3.0 (nuevo subcomando `repo remove <paquete>`:
#  borra el/los .deb de un paquete del repo local — busca tanto
#  "<paquete>_*.deb" (de `repo pack`) como "kairos-<paquete>_*.deb" (de
#  `repo add`), lo saca de repo_registry.json si tenía entrada y republica
#  el índice automáticamente. Antes la única forma de sacar un .deb del
#  repo local era borrarlo a mano por terminal y re-correr `repo publish`)
#
#  VERSIÓN ANTERIOR: 1.2.0 (`repo pack` preserva scripts de mantenimiento
#  reales — postinst/prerm/postrm/preinst — y conffiles leyendo
#  $PREFIX/var/lib/dpkg/info/<paquete>.* — antes solo copiaba datos vía
#  dpkg -L; `dpkg -e` no sirve para esto porque opera sobre un archivo .deb
#  real, no sobre un paquete instalado por nombre)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# Subcomando + argumento = primeros argumentos que NO son flags (cualquier
# orden: `repo add ollama --silent` o `repo --silent add ollama`).
_SUBCMD="" _SUBARG=""
for _a in "$@"; do
  case "$_a" in
    --silent|--force|--describe|--describe-files) continue ;;
    *)
      [ -z "$_SUBCMD" ] && { _SUBCMD="$_a"; continue; }
      [ -z "$_SUBARG" ] && _SUBARG="$_a"
      ;;
  esac
done

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
# FORCE se parsea por consistencia con el resto de modulos/ pero no tiene
# ningún efecto real en este script — bug de manifiesto confirmado en la
# auditoría 2026-08-27 (docs/arquitectura/AUDITORIA_MODULOS_2026-08-27.md):
# --describe declaraba "supports_force":true pese a que $FORCE nunca se lee
# en ningún subcomando (init/add/pack/publish/remove/source/keys/sign son
# todos idempotentes por diseño — 'repo init' recrea la estructura siempre,
# 'repo publish' regenera el índice siempre, no hay una rama "ya hecho,
# saltar salvo --force" que forzar). Mismo criterio que docker.sh (acepta
# --force por consistencia de CLI, documentado como sin efecto). El manifiesto
# se corrige a "supports_force":false más abajo para que sea honesto.
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
for _a in "$@"; do
  case "$_a" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;  # aceptado por consistencia con el resto de modulos/, sin efecto acá (ver comentario arriba)
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
  esac
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"repo","supports_silent":true,"supports_force":false,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. OJO: repo.sh es el mecanismo HERMANO
# de moduledeb.sh (ver cabecera de moduledeb.sh) — administra un repo apt local
# (dpkg -L sobre paquetes reales), no un instalador propio de Kairos. Su propio
# --describe-files cubre SOLO la infraestructura del repo en sí (registry JSON),
# NUNCA los paquetes .deb que administra (esos son estado dinámico, crecen con cada
# 'repo add'/'repo pack' — no tiene sentido "reinstalar repo.sh" para recrearlos,
# se generan corriendo esos subcomandos de nuevo).
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$HOME/kairos_local/repo_registry.json" \
    '{
      id: "repo",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-repo",
      version_registry_key: "repo.version",
      files: [
        {path: $p1, required: false, note: "Índice de paquetes .deb generados por repo add/pack — metadata, no los .deb en sí"}
      ],
      file_globs: [],
      dependencies: [
        {id: "dpkg", check_cmd: "command -v dpkg >/dev/null 2>&1", install_hint: "dpkg viene con el bootstrap de Termux — si falta, algo más grave está roto"}
      ],
      verify_cmd: "true",
      patch_cmd: "true",
      not_covered: [
        "Los .deb reales del repo (REPO_DEB, REPO_BIN) NO se empaquetan acá — son estado dinámico que crece con cada uso, se regeneran corriendo repo add/pack de nuevo, no tiene sentido snapshotearlos como si fueran una instalación fija",
        "El repo apt en sí (dists/, Release, Packages) tampoco se empaqueta — mismo motivo"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_repo_checkpoint"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^repo_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "repo_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^repo_${1}=done" "$CHECKPOINT" 2>/dev/null; }

# ── Constantes del repo ───────────────────────────────────────
REPO_ROOT="$TERMUX_PREFIX/../kairos-repo"
REPO_DEB="$REPO_ROOT/deb"
REPO_BIN="$REPO_ROOT/dists/stable/main/binary-aarch64"
REPO_COMP="$REPO_ROOT/dists/stable/main"
REPO_RELEASE="$REPO_ROOT/dists/stable/Release"
REPO_MAINTAINER="KairosApp <kairos@localhost>"
REPO_ARCH="aarch64"

# Registro JSON de paquetes empaquetados con `repo pack` (no confundir con
# $REGISTRY, que es el registry global de MODULOS de Kairos en texto plano
# key=valor) — array de {package, version, date, deb_path}, uno por paquete
# (upsert: un pack nuevo del mismo paquete reemplaza la entrada anterior).
REPO_REGISTRY_JSON="$HOME/kairos_local/repo_registry.json"

# Binario real de un módulo en $PREFIX/bin (algunos ids difieren del binario,
# ej. remote→ssh). Si el binario no existe se empaquetan solo los scripts.
_repo_binary_name() {
  case "$1" in
    remote) echo "ssh" ;;
    *)      echo "$1" ;;
  esac
}

# Versión detectada del módulo desde el registry (default 1.0.0).
_repo_version() {
  local _v
  _v=$(grep "^${1}\.version=" "$REGISTRY" 2>/dev/null | tail -1 | cut -d= -f2)
  [ -z "$_v" ] && _v="1.0.0"
  echo "$_v"
}

# Campo de un .deb (control), vía dpkg-deb si existe o manual (ar+tar).
_repo_deb_field() {
  local _deb="$1" _field="$2" _p
  if command -v dpkg-deb &>/dev/null; then
    dpkg-deb -f "$_deb" "$_field" 2>/dev/null
    return
  fi
  for _p in "$_deb" "${_deb%.deb}.tar"*; do
    [ -f "$_p" ] && ar p "$_p" control.tar.* 2>/dev/null | tar -xO ./control 2>/dev/null | grep -E "^${_field}:" | head -1 | cut -d' ' -f2-
  done
}

# ── repo init — estructura + Release ─────────────────────────
_repo_init() {
  step "Inicializando repo apt local en $REPO_ROOT"
  mkdir -p "$REPO_DEB" "$REPO_BIN"

  # Packages vacío (los .deb van en binary-aarch64)
  [ -f "$REPO_BIN/Packages" ] || : > "$REPO_BIN/Packages"

  _repo_write_release
  log "Repo creado: $REPO_ROOT"

  registry_write repo \
    "installed=true" \
    "version=1.0.0" \
    "root=$REPO_ROOT" \
    "arch=$REPO_ARCH" \
    "last_command=init" \
    "install_date=$(date +%Y-%m-%d)"
  notify_event "repo" "init_done" "$REPO_ROOT"
}

# Release (dists/stable/Release) con checksums de Packages/Gz.
_repo_write_release() {
  {
    echo "Origin: KairosApp"
    echo "Label: KairosApp local repo"
    echo "Suite: stable"
    echo "Codename: stable"
    echo "Version: 1.0.0"
    echo "Architectures: $REPO_ARCH"
    echo "Components: main"
    echo "Description: Repositorio apt local de modulos KairosApp"
    echo "Date: $(date -u +'%a, %d %b %Y %H:%M:%S UTC')"
    echo ""
    echo "MD5Sum:"
    _repo_release_checksums MD5 md5sum
    echo ""
    echo "SHA1:"
    _repo_release_checksums SHA1 sha1sum
    echo ""
    echo "SHA256:"
    _repo_release_checksums SHA256 sha256sum
  } > "$REPO_RELEASE"
}

# Par <checksum> <ruta> por cada archivo indexado (Packages, Packages.gz).
_repo_release_checksums() {
  local _field="$1" _cmd="$2" _f _sum _size _base
  for _f in "$REPO_COMP/Packages" "$REPO_COMP/Packages.gz"; do
    [ -f "$_f" ] || continue
    _sum=$(${_cmd} "$_f" 2>/dev/null | cut -d' ' -f1)
    _size=$(stat -c %s "$_f" 2>/dev/null || wc -c < "$_f")
    _base=${_f#"$REPO_COMP"/}
    printf ' %s %s %s\n' "$_sum" "$_size" "$_base"
  done
}

# ── repo add <id> — empaqueta un módulo instalado como .deb ─
_repo_add() {
  local _id="$1"
  [ -z "$_id" ] && error "Uso: repo add <módulo_id> (ej: repo add ollama)"

  step "Empaquetando módulo '$_id' como .deb"

  local _ver _bin _binfile _scripts
  _ver=$(_repo_version "$_id")
  _bin=$(_repo_binary_name "$_id")
  _binfile="$TERMUX_PREFIX/bin/$_bin"
  _scripts="$HOME/scripts/$_id"

  local _staging="$REPO_DEB/.build_${_id}_$$"
  rm -rf "$_staging"
  mkdir -p "$_staging/DEBIAN" "$_staging/data/data/com.termux/files/usr/bin"
  # limpieza también si error() corta a mitad (evita acumular staging en $REPO_DEB)
  trap 'rm -rf "$_staging"' EXIT

  # Archivar binario(s) instalados en $PREFIX/bin (el wrapper/nombre del
  # módulo + sus helpers -*/_* si existen, ej. n8n-start).
  local _collected=0 _f
  if [ -f "$_binfile" ] || [ -L "$_binfile" ]; then
    cp -a "$_binfile" "$_staging/data/data/com.termux/files/usr/bin/"
    _collected=$((_collected+1))
  fi
  for _f in "$TERMUX_PREFIX/bin/${_bin}-"* "$TERMUX_PREFIX/bin/${_bin}_"*; do
    [ -f "$_f" ] || [ -L "$_f" ] || continue
    cp -a "$_f" "$_staging/data/data/com.termux/files/usr/bin/"
    _collected=$((_collected+1))
  done

  # Archivar scripts del módulo en ~/scripts/<id>/ si existen.
  if [ -d "$_scripts" ]; then
    mkdir -p "$_staging/data/data/com.termux/files/home/scripts"
    cp -a "$_scripts" "$_staging/data/data/com.termux/files/home/scripts/"
    _collected=$((_collected+1))
  fi

  if [ "$_collected" = "0" ]; then
    warn "No se encontró binario ni scripts de '$_id' — el .deb será un paquete vacío (solo metadata)"
  fi

  # control file real (Package kairos-<id>, Version detectada, Architecture aarch64)
  cat > "$_staging/DEBIAN/control" << EOF
Package: kairos-$_id
Version: $_ver
Section: kairos
Priority: optional
Architecture: $REPO_ARCH
Maintainer: $REPO_MAINTAINER
Description: Modulo $_id de KairosApp — empaquetado desde la instalacion actual ($_bin) por 'repo add'
EOF

  local _out="$REPO_BIN/kairos-${_id}_${_ver}_${REPO_ARCH}.deb"
  _repo_build_deb "$_staging" "$_out"

  rm -rf "$_staging"
  trap - EXIT
  log "Deb creado: $_out"

  registry_write repo \
    "installed=true" \
    "last_command=add" \
    "last_add=$_id" \
    "last_deb=$_out" \
    "install_date=$(date +%Y-%m-%d)"
  notify_event "repo" "add_done" "kairos-$_id"
}

# Upsert de una entrada {package, version, date, deb_path} en
# $REPO_REGISTRY_JSON — usa jq (paquete core de Kairos, ver kairos.sh paso
# "Paquetes core") para no armar JSON a mano con string concat. Si jq no
# está disponible (dispositivo con el core roto/incompleto) se avisa y NO se
# escribe nada a medias — mejor un registro ausente y avisado que uno
# corrupto por concatenación manual de strings.
_repo_registry_upsert() {
  local _pkg="$1" _ver="$2" _deb="$3" _scripts_preserved="${4:-false}"
  mkdir -p "$(dirname "$REPO_REGISTRY_JSON")"
  [ -f "$REPO_REGISTRY_JSON" ] || echo '[]' > "$REPO_REGISTRY_JSON"

  if ! command -v jq &>/dev/null; then
    warn "jq no disponible — no se pudo actualizar $REPO_REGISTRY_JSON (el .deb se generó igual)"
    return 1
  fi

  local _tmp="${REPO_REGISTRY_JSON}.tmp"
  if jq --arg pkg "$_pkg" --arg ver "$_ver" --arg date "$(date +%Y-%m-%d)" --arg deb "$_deb" \
      --argjson scripts_preserved "$_scripts_preserved" \
      '([.[] | select(.package != $pkg)]) + [{package:$pkg, version:$ver, date:$date, deb_path:$deb, scripts_preserved:$scripts_preserved}]' \
      "$REPO_REGISTRY_JSON" > "$_tmp" 2>/dev/null && [ -s "$_tmp" ]; then
    mv "$_tmp" "$REPO_REGISTRY_JSON"
  else
    rm -f "$_tmp"
    warn "jq falló actualizando $REPO_REGISTRY_JSON — el .deb se generó igual"
    return 1
  fi
}

# ── repo pack <paquete> — empaqueta CUALQUIER paquete apt/pkg ya
#    instalado (no solo módulos de Kairos) como .deb real ─────────────────
# Mecanismo: dpkg-repack NO está empaquetado para Termux (confirmado — no
# existe en termux-packages/termux/termux-packages, ni como paquete pip/npm
# equivalente) — así que se replica manualmente lo que dpkg-repack hace por
# dentro: `dpkg -L <paquete>` para listar los archivos que YA están en disco
# (instalados por ese paquete) + `dpkg-deb -b` para reempaquetarlos.
#
# Preservación de conffiles/scripts de mantenimiento (investigado esta
# ronda): `dpkg -e|--control <archivo.deb> [dir]` NO sirve acá — extrae la
# carpeta control de un .deb REAL en disco, no de un paquete instalado por
# nombre (no existe el .deb original, ese es justo el problema que este
# subcomando resuelve). El mecanismo correcto y confirmado es leer
# directamente $TERMUX_PREFIX/var/lib/dpkg/info/<paquete>.{preinst,postinst,
# prerm,postrm,conffiles} — ahí es donde el propio dpkg de Termux vuelca los
# scripts de mantenimiento y la lista de conffiles al momento de instalar
# cualquier paquete (mismo directorio de donde sale el `dpkg -L` que ya
# usábamos). Si esos archivos existen se copian tal cual al `DEBIAN/` del
# staging antes de `dpkg-deb -b` — así el .deb resultante queda funcionalmente
# igual al original (mismos hooks, mismos conffiles declarados). Si el
# directorio `info/` no existe o el paquete no tiene esos archivos (dpkg
# roto/incompleto, o el paquete real nunca tuvo scripts), se cae al método
# anterior (solo datos vía dpkg -L) con un warn() explícito — nunca se rompe
# el caso simple que ya funcionaba.
_repo_pack() {
  local _pkg="$1"
  [ -z "$_pkg" ] && error "Uso: repo pack <paquete> (paquete YA instalado, ej: repo pack nano)"

  command -v dpkg &>/dev/null || error "dpkg no disponible en este Termux"
  dpkg -s "$_pkg" &>/dev/null || error "'$_pkg' no está instalado (dpkg -s no lo encuentra) — instalalo primero con: pkg install $_pkg"

  step "Empaquetando paquete instalado '$_pkg' como .deb (dpkg -L + dpkg-deb, sin dpkg-repack)"

  local _ver _arch _maintainer _desc
  _ver=$(dpkg-query -W -f='${Version}' "$_pkg" 2>/dev/null)
  _arch=$(dpkg-query -W -f='${Architecture}' "$_pkg" 2>/dev/null)
  _maintainer=$(dpkg-query -W -f='${Maintainer}' "$_pkg" 2>/dev/null)
  _desc=$(dpkg-query -W -f='${binary:Summary}' "$_pkg" 2>/dev/null)
  [ -z "$_ver" ] && _ver="0.0.0"
  [ -z "$_arch" ] && _arch="$REPO_ARCH"
  [ -z "$_maintainer" ] && _maintainer="$REPO_MAINTAINER"
  [ -z "$_desc" ] && _desc="Repack de $_pkg (dpkg -L, ya instalado) via 'repo pack'"

  local _staging="$REPO_DEB/.build_pack_${_pkg}_$$"
  rm -rf "$_staging"
  mkdir -p "$_staging/DEBIAN"
  trap 'rm -rf "$_staging"' EXIT

  # ── Scripts de mantenimiento + conffiles reales, desde el info/ de dpkg
  local _dpkg_info="$TERMUX_PREFIX/var/lib/dpkg/info"
  local _scripts_preserved=false
  local _has_conffiles_report
  _has_conffiles_report=$(dpkg-query -W -f='${Conffiles}' "$_pkg" 2>/dev/null)

  if [ -d "$_dpkg_info" ]; then
    local _mscript _found_conffiles=false _found_any=false
    for _mscript in preinst postinst prerm postrm conffiles; do
      [ -f "$_dpkg_info/${_pkg}.${_mscript}" ] || continue
      cp -a "$_dpkg_info/${_pkg}.${_mscript}" "$_staging/DEBIAN/${_mscript}"
      _found_any=true
      if [ "$_mscript" = "conffiles" ]; then
        _found_conffiles=true
      else
        chmod 0755 "$_staging/DEBIAN/${_mscript}"
      fi
    done
    _scripts_preserved=true
    if [ -n "$_has_conffiles_report" ] && ! $_found_conffiles; then
      warn "'$_pkg' reporta conffiles (dpkg-query) pero no se encontró $_dpkg_info/${_pkg}.conffiles — revisar manualmente antes de confiar en el .deb generado"
    fi
    $_found_any && info "Scripts de mantenimiento/conffiles reales copiados desde $_dpkg_info/${_pkg}.*" || info "'$_pkg' no tiene scripts de mantenimiento ni conffiles registrados en dpkg (paquete simple) — nada que preservar además de los datos"
  else
    _scripts_preserved=false
    warn "$_dpkg_info no existe — no se pudieron extraer scripts de mantenimiento reales (postinst/prerm/postrm/preinst), fallback al método anterior (solo archivos de datos vía dpkg -L)"
    if [ -n "$_has_conffiles_report" ]; then
      warn "'$_pkg' tiene conffiles y NO se preservaron — este repack los trata como archivos de datos normales, no como conffiles reales"
    fi
  fi

  local _collected=0 _f _rel
  while IFS= read -r _f; do
    [ -z "$_f" ] && continue
    { [ -f "$_f" ] || [ -L "$_f" ]; } || continue   # dpkg -L también lista directorios, se saltan
    _rel="${_f#/}"
    mkdir -p "$_staging/$(dirname "$_rel")"
    cp -a "$_f" "$_staging/$_rel"
    _collected=$((_collected+1))
  done < <(dpkg -L "$_pkg" 2>/dev/null)

  if [ "$_collected" = "0" ]; then
    rm -rf "$_staging"; trap - EXIT
    error "'$_pkg' no tiene archivos regulares (dpkg -L vacío) — nada para empaquetar"
  fi

  # Package real (no prefijado kairos-, a diferencia de `repo add`): el
  # objetivo de `pack` es reinstalar el MISMO paquete original más adelante
  # (pkg install <paquete> desde este repo), no crear una variante propia.
  cat > "$_staging/DEBIAN/control" << EOF
Package: $_pkg
Version: $_ver
Section: kairos-repack
Priority: optional
Architecture: $_arch
Maintainer: $_maintainer
Description: $_desc
EOF

  local _out="$REPO_BIN/${_pkg}_${_ver}_${_arch}.deb"
  _repo_build_deb "$_staging" "$_out"

  rm -rf "$_staging"
  trap - EXIT
  log "Deb creado: $_out ($_collected archivos)"

  _repo_registry_upsert "$_pkg" "$_ver" "$_out" "$_scripts_preserved"

  registry_write repo \
    "installed=true" \
    "last_command=pack" \
    "last_pack=$_pkg" \
    "last_deb=$_out" \
    "last_pack_scripts_preserved=$_scripts_preserved" \
    "install_date=$(date +%Y-%m-%d)"
  notify_event "repo" "pack_done" "$_pkg"
}

# Construye el .deb desde el staging (DEBIAN/control + data):
#   dpkg-deb -b si está (paquete dpkg de Termux), si no fallback manual
#   con ar + tar (debian-binary + control.tar.xz + data.tar.xz).
_repo_build_deb() {
  local _staging="$1" _out="$2"

  if command -v dpkg-deb &>/dev/null; then
    info "Empaquetando con dpkg-deb -b ..."
    dpkg-deb -b "$_staging" "$_out" 2>&1 | tail -2
    [ "${PIPESTATUS[0]}" -eq 0 ] || error "dpkg-deb -b falló"
    [ -f "$_out" ] || error "dpkg-deb no generó el .deb"
    return 0
  fi

  # Fallback manual sin dpkg-deb: ar (binutils) + tar. Es el mismo formato
  # ar de Debian: [debian-binary][control.tar.xz][data.tar.xz].
  info "dpkg-deb no disponible — empaquetando manual con ar+tar ..."
  command -v ar &>/dev/null || error "ni dpkg-deb ni ar disponibles — instalá el paquete 'dpkg' o 'binutils'"

  local _tmp="$REPO_DEB/.ar_$$"
  mkdir -p "$_tmp/control"
  printf '2.0\n' > "$_tmp/debian-binary"
  cp "$_staging/DEBIAN/control" "$_tmp/control/control"

  # md5sums (opcional pero estándar)
  ( cd "$_staging" && find . -path ./DEBIAN -prune -o -type f -print | \
      sed 's|^\./||' | while read -r f; do
        md5sum "$f" | sed "s|  \./|  |"
      done ) > "$_tmp/control/md5sums" 2>/dev/null

  tar -C "$_tmp/control" -cJf "$_tmp/control.tar.xz" .
  tar -C "$_staging" --exclude=DEBIAN -cJf "$_tmp/data.tar.xz" .
  if ! ar rcs "$_out" "$_tmp/debian-binary" "$_tmp/control.tar.xz" "$_tmp/data.tar.xz"; then
    rm -rf "$_tmp"
    error "ar rcs falló"
  fi
  rm -rf "$_tmp"
  [ -f "$_out" ] || error "no se generó el .deb manual"
}

# ── repo remove <paquete> — saca uno o más .deb del repo local ──
# Busca tanto "<paquete>_*.deb" (generado por `repo pack`) como
# "kairos-<paquete>_*.deb" (generado por `repo add`) para que un solo
# comando/botón sirva para ambos orígenes sin que el usuario tenga que saber
# cuál usó. Si el paquete tenía entrada en repo_registry.json (solo los de
# `repo pack` la tienen) también se borra ahí. Termina republicando el
# índice (Packages/Packages.gz/Release) para que el repo quede consistente.
_repo_remove() {
  local _pkg="$1"
  [ -z "$_pkg" ] && error "Uso: repo remove <paquete>"
  [ -d "$REPO_BIN" ] || error "Repo no inicializado — corré primero: repo init"

  step "Eliminando '$_pkg' del repo local"

  local _removed=0 _deb
  for _deb in "$REPO_BIN/${_pkg}_"*.deb "$REPO_BIN/kairos-${_pkg}_"*.deb; do
    [ -f "$_deb" ] || continue
    rm -f "$_deb"
    _removed=$((_removed+1))
  done

  [ "$_removed" = "0" ] && error "No se encontró ningún .deb de '$_pkg' en $REPO_BIN"

  if [ -f "$REPO_REGISTRY_JSON" ] && command -v jq &>/dev/null; then
    local _tmp="${REPO_REGISTRY_JSON}.tmp"
    if jq --arg pkg "$_pkg" '[.[] | select(.package != $pkg)]' "$REPO_REGISTRY_JSON" > "$_tmp" 2>/dev/null && [ -s "$_tmp" ]; then
      mv "$_tmp" "$REPO_REGISTRY_JSON"
    else
      rm -f "$_tmp"
      warn "jq falló actualizando $REPO_REGISTRY_JSON tras el remove — el/los .deb ya se borraron igual"
    fi
  fi

  _repo_publish

  log "'$_pkg' eliminado del repo local ($_removed .deb borrado(s))"
  registry_write repo \
    "installed=true" \
    "last_command=remove" \
    "last_remove=$_pkg" \
    "install_date=$(date +%Y-%m-%d)"
  notify_event "repo" "remove_done" "$_pkg"
}

# ── repo publish — Packages + Packages.gz + Release ─────────
_repo_publish() {
  step "Publicando índice (Packages/Packages.gz/Release)"
  local _dir="$REPO_BIN"
  [ -d "$_dir" ] || error "Repo no inicializado — corré primero: repo init"

  local _packages="$_dir/Packages"
  : > "$_packages"
  local _deb _pkg _ver _arch _desc _size _md5 _sha
  local _count=0
  for _deb in "$_dir"/*.deb; do
    [ -f "$_deb" ] || continue
    _count=$((_count+1))
    _pkg=$(_repo_deb_field "$_deb" "Package")
    _ver=$(_repo_deb_field "$_deb" "Version")
    _arch=$(_repo_deb_field "$_deb" "Architecture")
    _desc=$(_repo_deb_field "$_deb" "Description")
    _size=$(stat -c %s "$_deb" 2>/dev/null || wc -c < "$_deb")
    _md5=$(md5sum "$_deb" 2>/dev/null | cut -d' ' -f1)
    _sha=$(sha256sum "$_deb" 2>/dev/null | cut -d' ' -f1)
    {
      echo "Package: $_pkg"
      echo "Version: $_ver"
      echo "Architecture: $_arch"
      echo "Maintainer: $REPO_MAINTAINER"
      echo "Description: $_desc"
      echo "Filename: ./$(basename "$_deb")"
      echo "Size: $_size"
      echo "MD5sum: $_md5"
      echo "SHA256: $_sha"
      echo ""
    } >> "$_packages"
  done

  gzip -c "$_packages" > "$_dir/Packages.gz" 2>/dev/null || error "gzip falló"
  _repo_write_release

  # El Release recién escrito invalida cualquier firma GPG previa (InRelease/
  # Release.gpg firman el Release VIEJO, con checksums desactualizados) — se
  # borran para no dejar una firma inconsistente circulando; el usuario tiene
  # que volver a correr `repo sign <KEYID>` si quiere el repo firmado de
  # nuevo. `repo source` ya detecta la ausencia y vuelve a [trusted=yes] solo.
  if [ -f "$REPO_ROOT/dists/stable/InRelease" ] || [ -f "$REPO_ROOT/dists/stable/Release.gpg" ]; then
    rm -f "$REPO_ROOT/dists/stable/InRelease" "$REPO_ROOT/dists/stable/Release.gpg"
    warn "Índice republicado — la firma GPG anterior quedó desactualizada y se borró. Corré 'repo sign <KEYID>' para volver a firmar."
  fi

  log "Índice publicado: $_count paquetes"

  registry_write repo \
    "installed=true" \
    "last_command=publish" \
    "packages=$_count" \
    "install_date=$(date +%Y-%m-%d)"
  notify_event "repo" "publish_done" "packages=$_count"
}

# ── repo keys — lista claves GPG secretas disponibles (solo lectura) ────
_repo_keys() {
  command -v gpg &>/dev/null || error "gpg no está instalado — instalá el paquete 'gnupg' primero (pkg install gnupg)"
  gpg --list-secret-keys --keyid-format LONG 2>/dev/null
}

# ── repo sign <KEYID> — firma el Release actual con una clave GPG YA
#    EXISTENTE del usuario. Nunca genera una clave — si no hay ninguna, el
#    usuario la crea él mismo con `gpg --full-generate-key` en una terminal
#    real (paso interactivo por diseño, ver nota de cabecera). ─────────────
_repo_sign() {
  local _keyid="$1"
  [ -z "$_keyid" ] && error "Uso: repo sign <KEYID> (corré 'repo keys' para listar las claves disponibles)"
  command -v gpg &>/dev/null || error "gpg no está instalado — instalá el paquete 'gnupg' primero (pkg install gnupg)"
  [ -f "$REPO_RELEASE" ] || error "No hay Release para firmar — corré primero: repo init / repo publish"
  gpg --list-secret-keys "$_keyid" &>/dev/null || error "No se encontró ninguna clave secreta con id '$_keyid' — corré 'repo keys' para ver las disponibles, o creá una con 'gpg --full-generate-key' en una terminal"

  step "Firmando repo con GPG (clave $_keyid)"

  # InRelease: firma in-line (clearsign), la que usan los clientes apt
  # modernos (>= 1.1). Release.gpg: firma separada ASCII-armored (-abs =
  # --armor --detach-sign --sign), para compatibilidad con clientes viejos.
  # Mismo mecanismo que documenta Debian (wiki.debian.org/SecureApt /
  # apt-secure(8)) — no es un formato inventado acá.
  gpg --batch --yes --default-key "$_keyid" --clearsign \
    -o "$REPO_ROOT/dists/stable/InRelease" "$REPO_RELEASE" \
    || error "gpg --clearsign falló generando InRelease"
  gpg --batch --yes --default-key "$_keyid" -abs \
    -o "$REPO_ROOT/dists/stable/Release.gpg" "$REPO_RELEASE" \
    || error "gpg -abs falló generando Release.gpg"

  # Clave pública exportada junto al repo: binaria (repo-key.gpg, para
  # signed-by= de sources.list — apt prefiere binario ahí, no armored) +
  # ASCII-armored (repo-key.asc, para que el usuario la inspeccione/importe
  # a mano en otro dispositivo con `gpg --import`).
  gpg --batch --yes --export "$_keyid" > "$REPO_ROOT/repo-key.gpg" 2>/dev/null
  gpg --batch --yes --export --armor "$_keyid" > "$REPO_ROOT/repo-key.asc" 2>/dev/null

  log "Repo firmado: InRelease + Release.gpg generados, clave pública exportada a $REPO_ROOT/repo-key.gpg"

  registry_write repo \
    "installed=true" \
    "last_command=sign" \
    "signed_key=$_keyid" \
    "install_date=$(date +%Y-%m-%d)"
  notify_event "repo" "sign_done" "$_keyid"
}

# ── repo source — línea para sources.list.d ─────────────────
_repo_source() {
  local _line
  if [ -f "$REPO_ROOT/dists/stable/InRelease" ] && [ -f "$REPO_ROOT/repo-key.gpg" ]; then
    _line="deb [signed-by=$REPO_ROOT/repo-key.gpg] file://$REPO_ROOT stable main"
    echo "$_line"
    echo ""
    info "Agregala a $TERMUX_PREFIX/etc/apt/sources.list.d/kairos.list, luego:"
    echo "  pkg update"
    echo "  pkg install kairos-<id>"
    echo ""
    info "Repo firmado con GPG — la línea usa signed-by= (formato moderno, no apt-key) en vez de [trusted=yes]."
    info "Para instalar desde OTRO dispositivo: copiá también $REPO_ROOT/repo-key.gpg (o .asc) ahí, ajustando la ruta de signed-by= a donde lo dejes."
  else
    _line="deb [trusted=yes] file://$REPO_ROOT stable main"
    echo "$_line"
    echo ""
    info "Agregala a $TERMUX_PREFIX/etc/apt/sources.list.d/kairos.list, luego:"
    echo "  pkg update"
    echo "  pkg install kairos-<id>"
    echo ""
    info "Repo sin firma GPG — la línea usa [trusted=yes] a propósito (repo local propio)."
    info "Si querés evitar [trusted=yes], podés firmarlo con 'repo sign <KEYID>' usando una clave GPG que ya tengas (repo keys para listarlas) — es opcional, nunca obligatorio."
  fi
}

# ── Dispatch ─────────────────────────────────────────────────
if ! $SILENT; then
  echo ""
  echo "  KairosApp · repo apt local ($REPO_ROOT)"
fi

case "$_SUBCMD" in
  init)    _repo_init ;;
  add)     _repo_add "$_SUBARG" ;;
  pack)    _repo_pack "$_SUBARG" ;;
  publish) _repo_publish ;;
  remove)  _repo_remove "$_SUBARG" ;;
  source)  _repo_source ;;
  keys)    _repo_keys ;;
  sign)    _repo_sign "$_SUBARG" ;;
  "")
    # Sin subcomando: el botón genérico "Instalar" de la app (GenericModuleFragment,
    # mismo flujo que verificar.sh/apk.sh) llama al script sin argumentos —
    # "init" es el paso natural de "instalar/configurar este módulo" (crea el
    # repo vacío); add/publish/source son subcomandos de uso posterior desde
    # terminal. Bug real reportado 2026-08-16: "Instalar" tiraba
    # "[ERROR] Uso: repo {init|add|publish|source}" en vez de hacer algo.
    if $SILENT; then
      info "Sin subcomando — ejecutando 'repo init' (primer paso natural de instalación)"
      _repo_init
    else
      error "Uso: repo {init|add <módulo_id>|pack <paquete>|publish|remove <paquete>|source|keys|sign <KEYID>}"
    fi
    ;;
  *)
    error "Subcomando desconocido: $_SUBCMD"
    ;;
esac

rm -f "$CHECKPOINT"
exit 0