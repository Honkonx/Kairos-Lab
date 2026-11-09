#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · moduledeb.sh — empaqueta un MÓDULO ya instalado
#  (Claude Code, OpenCode, n8n, ...) como .deb reutilizable, con
#  un manifest JSON por módulo que describe qué archivos incluir
#  y qué verificar/parchear al "instalar" ese .deb en otro lado.
#
#  DISTINTO de `repo.sh pack <paquete-apt>`: repo.sh empaqueta
#  paquetes del propio apt/pkg de Termux (dpkg -L sobre algo que
#  YA es un paquete dpkg). moduledeb.sh empaqueta el resultado de
#  un INSTALADOR DE KAIROS (claude.sh/opencode.sh/n8n.sh/...) que
#  no pasa por dpkg — descarga binarios sueltos, los parchea
#  (ej. patchelf en Claude native) y escribe scripts propios. El
#  postinst de este .deb corre SOLO las verificaciones/parches
#  descritos en el manifest, nunca el instalador completo — así
#  se reutiliza una instalación ya hecha sin re-descargar/re-parchear
#  desde cero (pedido explícito del usuario, ver docs/humano/ ronda
#  de "gestión de instalaciones .deb por módulo").
#
#  SUBCOMANDOS:
#    moduledeb pack <id>            Empaqueta el módulo <id> — el manifest
#                                    sale de `<id>.sh --describe-files`
#                                    (generación dinámica, ver
#                                    docs/arquitectura/MODULEDEB_GENERICO.md),
#                                    con fallback a modulos/manifests/<id>.json
#                                    si ese script todavía no implementa el
#                                    flag. Usa la instalación YA HECHA en
#                                    este device.
#    moduledeb inspect <ruta.deb>   PRIMER paso para cualquier .deb (v2): lee SOLO manifest.json
#                                    + SHA256SUMS.txt (sin extraer el payload ni ejecutar nada)
#                                    y devuelve un JSON con el veredicto y lo que el paquete
#                                    necesita (paquetes, complementos, módulos). rc 2 = no instalar.
#    moduledeb reseal <deb> [out]   Convierte un .deb v1 (sin SHA256SUMS.txt) al formato v2.
#    moduledeb extract <ruta.deb>   Fase A: extrae control+data a un dir
#                                    temporal aislado. NADA se ejecuta ni
#                                    se copia a su destino final todavía.
#    moduledeb verify <dir>         Fase B: compara el manifest extraído
#                                    contra el estado REAL del device
#                                    (dependencias, archivos nuevo/igual/
#                                    sobrescribir, verify_cmd) — solo
#                                    informa, no aplica nada.
#    moduledeb apply <dir>          Fase C: copia los archivos a su destino
#                                    real + corre verify_cmd/patch_cmd. Corre
#                                    verify internamente primero si no se
#                                    corrió ya en esta invocación.
#    moduledeb install <ruta.deb>   Azúcar sintáctica: encadena extract →
#                                    verify (solo loggea, no bloquea) →
#                                    apply, para uso desde terminal/scripts
#                                    donde no hace falta el punto de pausa
#                                    manual. La UI nueva llama las 3 fases
#                                    por separado para mostrar el resumen
#                                    de verify ANTES de ofrecer aplicar.
#    moduledeb list                  Lista manifests a mano disponibles en
#                                    modulos/manifests/*.json (fallback).
#
#  FLAGS:
#    --silent      Sin preguntas (modo app)
#    --describe    Manifiesto JSON de una línea
#
#  MANIFEST (salida de `<id>.sh --describe-files`, o fallback
#  modulos/manifests/<id>.json) — campos:
#    id, supports_describe_files, variant, package_name, files[]
#    (path/required/note), file_globs[] (pattern/required/note),
#    dependencies[] (id/check_cmd/install_hint), verify_cmd, patch_cmd,
#    version_registry_key, not_covered[] (limitaciones honestas)
#
# Mecanismo genérico implementado 2026-08-23 —
#  cubre HOY los 3 módulos que ya tienen --describe-files (claude, opencode,
#  n8n, migrados 1:1 desde el piloto a mano original, borrado como código
# muerto en — ver docs/arquitectura/MODULEDEB_GENERICO.md §5.1).
#  Expandir a más módulos = agregarles su propio bloque --describe-files,
#  sin tocar este script (plan de migración completo en esa misma sección).
#
#  Reusa el MISMO mecanismo de empaquetado .deb que ya existe en
#  repo.sh (estructura DEBIAN/control + dpkg-deb -b, con fallback
#  manual ar+tar) — reimplementado acá de forma independiente
#  (_moduledeb_build_deb) en vez de source-ar repo.sh, porque repo.sh
#  parsea "$@" del proceso que lo invoca para su propio dispatch de
#  subcomandos y sourcearlo pisaría ese parseo.
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 2.0.0 (generación dinámica + instalación en 3 fases) | Agosto 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

_SCRIPT_DIR="$(dirname "${BASH_SOURCE[0]}")"
MANIFEST_DIR="$_SCRIPT_DIR/manifests"

# Subcomando + argumento = primeros no-flags (mismo patrón que repo.sh)
_SUBCMD="" _SUBARG="" _SUBARG2=""
for _a in "$@"; do
  case "$_a" in
    --silent|--describe) continue ;;
    *)
      [ -z "$_SUBCMD" ] && { _SUBCMD="$_a"; continue; }
      if [ -z "$_SUBARG" ]; then _SUBARG="$_a"; continue; fi
      [ -z "$_SUBARG2" ] && _SUBARG2="$_a"
      ;;
  esac
done

SILENT=false
DESCRIBE=false
for _a in "$@"; do
  case "$_a" in
    --silent)   SILENT=true ;;
    --describe) DESCRIBE=true ;;
  esac
done

if $DESCRIBE; then
  cat << 'JSON'
{"id":"moduledeb","supports_silent":true,"supports_force":false,"variants":[],"variant_required":false,"note":"empaqueta modulos de Kairos (no paquetes apt) como .deb via manifest JSON — ver modulos/manifests/"}
JSON
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
source "$_SCRIPT_DIR/lib.sh"

command -v jq &>/dev/null || error "jq no disponible — moduledeb.sh requiere jq (paquete core de Kairos) para leer los manifests JSON"

# Carpeta pública (2026-08-23, pedido explícito del usuario,): antes
# vivían en $HOME/kairos_local/ (privado de la app, invisible fuera de Termux) — ahora en
# Download/kairos/ (memoria interna PÚBLICA) para que el usuario pueda verlos/moverlos/
# compartirlos con cualquier explorador de archivos, no solo desde dentro de Kairos.
MODULEDEB_OUT_DIR="/storage/emulated/0/Download/kairos"
MODULEDEB_BUILD_DIR="$HOME/kairos_local/module_deb_build"
mkdir -p "$MODULEDEB_OUT_DIR" 2>/dev/null || true

# Versión desde el registry (mismo criterio que repo.sh _repo_version), con
# fallback al manifest y de ahí a 0.0.0.
_moduledeb_version() {
  local _id="$1" _key="$2" _v
  [ -n "$_key" ] && _v=$(grep "^${_key}=" "$REGISTRY" 2>/dev/null | tail -1 | cut -d= -f2)
  [ -z "$_v" ] && _v="0.0.0"
  # Sanitización real para el campo "Version:" de dpkg-deb — bug real confirmado en
  # dispositivo (2026-08-29, /285.md): el valor crudo del registry NO
  # siempre es un número de versión Debian válido — dpkg exige que empiece con un dígito
  # y no tenga espacios embebidos. Ejemplos reales que rompían "dpkg-deb -b":
  #   ssh.version=OpenSSH_10.5p1        -> "version number does not start with digit"
  #   llamaserver.version="version: 1 (e1a1abb)" -> "version string has embedded spaces"
  #   ohmypi.version=v18.0.10           -> "version number does not start with digit"
  #   n8n.version=latest                -> "version number does not start with digit"
  # Fix genérico (no por módulo): 1) espacios -> "-", 2) recortar cualquier prefijo no
  # numérico hasta el primer dígito, 3) si sigue sin arrancar con dígito (ej. "latest"
  # sin ningún número adentro), usar "0.0.0" con el valor crudo agregado como sufijo del
  # nombre del paquete via upstream-version (no rompe dpkg, conserva el dato original).
  _v="${_v// /-}"
  if ! [[ "$_v" =~ ^[0-9] ]]; then
    local _digits
    _digits=$(echo "$_v" | grep -oE '[0-9].*' | head -1)
    if [ -n "$_digits" ]; then
      _v="$_digits"
    else
      _v="0.0.0+${_v}"
    fi
  fi
  # Alfabeto válido real de dpkg para "Version:" (Debian Policy §5.6.12): dígitos, letras,
  # y . + ~ - : — cualquier otro carácter (paréntesis, etc., ej. "1-(e1a1abb)" de
  # llamaserver.version) rompe "dpkg-deb -b" con "invalid character in revision number"
  # aunque ya empiece con dígito. Filtrado al final, después de la lógica de arriba.
  _v="${_v//[^a-zA-Z0-9.+~:-]/}"
  echo "$_v"
}

# Construye el .deb desde el staging — mismo formato que repo.sh
# _repo_build_deb (dpkg-deb -b, fallback ar+tar), reimplementado acá para
# no sourcear repo.sh (ver comentario de cabecera).
_moduledeb_build_deb() {
  local _staging="$1" _out="$2"
  mkdir -p "$(dirname "$_out")"

  if command -v dpkg-deb &>/dev/null; then
    info "Empaquetando con dpkg-deb -b ..."
    dpkg-deb -b "$_staging" "$_out"
    [ $? -eq 0 ] || error "dpkg-deb -b falló"
    [ -f "$_out" ] || error "dpkg-deb no generó el .deb"
    return 0
  fi

  info "dpkg-deb no disponible — empaquetando manual con ar+tar ..."
  command -v ar &>/dev/null || error "ni dpkg-deb ni ar disponibles — instalá el paquete 'dpkg' o 'binutils'"

  local _tmp="${_staging}.ar"
  mkdir -p "$_tmp/control"
  printf '2.0\n' > "$_tmp/debian-binary"
  cp "$_staging/DEBIAN/control" "$_tmp/control/control"
  for _f in preinst postinst prerm postrm; do
    [ -f "$_staging/DEBIAN/$_f" ] && cp "$_staging/DEBIAN/$_f" "$_tmp/control/$_f"
  done

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

# Resuelve el manifest de un módulo: primero intenta generación dinámica
# (`<id>.sh --describe-files`, ver docs/arquitectura/MODULEDEB_GENERICO.md
# §2-3), y si el script todavía no reconoce ese flag (no imprime JSON válido
# — caso 1 de §2.3), cae al manifest a mano en modulos/manifests/<id>.json
# si existe. Escribe el JSON resuelto en $1 (archivo temporal) y devuelve 0,
# o hace `error` (sale del script) si ninguna de las 2 vías funciona.
_moduledeb_resolve_manifest() {
  local _id="$1" _out_file="$2"
  local _module_script="$_SCRIPT_DIR/${_id}.sh"

  if [ -f "$_module_script" ]; then
    local _raw
    _raw=$(bash "$_module_script" --describe-files --silent 2>/dev/null)
    if [ -n "$_raw" ] && echo "$_raw" | jq empty 2>/dev/null; then
      echo "$_raw" > "$_out_file"
      info "Manifest generado dinámicamente por '$_id.sh --describe-files'"
      return 0
    fi
  fi

  local _fallback="$MANIFEST_DIR/${_id}.json"
  if [ -f "$_fallback" ] && jq empty "$_fallback" 2>/dev/null; then
    cp "$_fallback" "$_out_file"
    warn "'$_id' todavía no implementa --describe-files — usando manifest a mano de fallback: $_fallback"
    return 0
  fi

  error "'$_id' todavía no implementa --describe-files — ver plan de migración en docs/arquitectura/MODULEDEB_GENERICO.md, o escribí $MANIFEST_DIR/${_id}.json a mano como fallback"
}

# Expande files[] + file_globs[] del manifest resuelto a una lista plana de
# rutas reales (ver §2.2 del diseño) — cada patrón de file_globs[] se
# resuelve con `find` sobre el estado REAL del device al momento de empaquetar,
# nunca sobre una foto vieja. Escribe una ruta por línea a $2 (required=true
# marcado con prefijo "R:", el resto con "O:"), sin duplicados (bug real
# confirmado en dispositivo 2026-08-23: un path en files[] que además cae
# dentro de un file_globs[] se copiaba 2 veces).
#
# Bug real #2 encontrado 2026-08-24 (ver pruebas funcionales
# reales por ADB, pedido explícito del usuario de probar el ciclo completo de
# empaquetado) — `_path`/`_pattern` ANTES pasaban por `eval echo "$_raw_*"`,
# heredado de una era donde el manifest a mano (modulos/manifests/*.json, ya
# borrado como código muerto en) podía traer placeholders tipo
# "$HOME"/"$PREFIX" a expandir en runtime. Los manifests reales de hoy
# vienen SIEMPRE de --describe-files vía `jq --arg` (que ya resuelve el
# valor real al generar el JSON, sin placeholders de shell), así que esa
# expansión nunca hacía falta — y para file_globs[] activamente rompía
# todo: "eval echo" no solo expande variables, TAMBIÉN hace pathname
# expansion (globbing) sobre el resultado, así que un patrón real como
# ".../codegraph-linux-arm64/**" se expandía CONTRA EL FILESYSTEM (bash
# tratando "**" como un glob real de directorio) en vez de quedar como el
# string literal que el resto de la función espera — confirmado en vivo:
# el .deb de codegraph solo traía el wrapper de 1 archivo, los 913
# archivos/279MB del runtime real quedaban afuera SIN ningún error visible
# (moduledeb reportaba "[OK] Deb creado" igual). Fix: usar $_raw_path/
# $_raw_pattern directo, sin eval — son valores ya resueltos.
#
# Bug real #3 encontrado 2026-08-29 (ADB en dispositivo real, `bash
# ~/moduledeb.sh pack openclaw` colgado indefinidamente sin salida, a
# diferencia de cualquier otro módulo). El dedup ANTES vivía en un archivo
# "$_seen_file" chequeado con `grep -qxF "$_f" "$_seen_file"` por CADA archivo
# encontrado por `find` — O(n²): un `grep` (fork+exec de un proceso nuevo)
# por archivo, escaneando una lista que crece hasta n líneas. openclaw es el
# único módulo cuyo file_globs[] cubre un árbol npm global completo con
# node_modules propios (`.npm-global/lib/node_modules/openclaw/**`) —
# confirmado en dispositivo real: 32.027 archivos. Eso es ~32.027 forks de
# `grep` escaneando hasta 32.027 líneas cada uno (hasta ~500M comparaciones
# de línea + 32k spawns de proceso), que en un ARM64 sin root tarda muchísimo
# más que cualquier timeout razonable — otros módulos (codegraph, ~913
# archivos) no lo mostraban porque su árbol es ~35x más chico. Fix: dedup con
# un array asociativo bash (`declare -A _seen`) — lookup O(1) en memoria del
# propio proceso, sin fork ni escaneo lineal por archivo.
_moduledeb_expand_files() {
  local _manifest="$1" _out_file="$2"
  local -A _seen
  : > "$_out_file"

  local _n_files _i
  _n_files=$(jq '.files | length' "$_manifest")
  _i=0
  while [ "$_i" -lt "$_n_files" ]; do
    local _raw_path _required _path
    _raw_path=$(jq -r ".files[$_i].path" "$_manifest")
    _required=$(jq -r ".files[$_i].required" "$_manifest")
    _path="$_raw_path"
    if [ "$_required" = "true" ]; then echo "R:$_path" >> "$_out_file"; else echo "O:$_path" >> "$_out_file"; fi
    _seen["$_path"]=1
    _i=$((_i+1))
  done

  local _n_globs
  _n_globs=$(jq '.file_globs // [] | length' "$_manifest")
  _i=0
  while [ "$_i" -lt "$_n_globs" ]; do
    local _raw_pattern _pattern _base _name_pattern
    _raw_pattern=$(jq -r ".file_globs[$_i].pattern" "$_manifest")
    _pattern="$_raw_pattern"
    if [[ "$_pattern" == *"/**" ]]; then
      # Árbol completo: "$PREFIX/lib/opencode/**" — recorre TODO bajo la raíz.
      _base="${_pattern%/\*\*}"
      _name_pattern=""
    else
      # Patrón con comodín en el nombre de archivo dentro de UN directorio, sin
      # bajar a subcarpetas: "$PREFIX/lib/libggml*.so" — la raíz real es todo lo
      # que hay antes de la ÚLTIMA "/" (bug real confirmado 2026-08-23,
      #: la versión anterior solo soportaba patrones "/**",
      # cortar en el primer "*" dejaba un _base tipo ".../lib/libggml" que nunca
      # es un directorio real — el glob nunca expandía nada).
      _base="${_pattern%/*}"
      _name_pattern="${_pattern##*/}"
    fi
    if [ -d "$_base" ]; then
      while IFS= read -r _f; do
        [ -n "${_seen[$_f]+x}" ] && continue
        echo "O:$_f" >> "$_out_file"
        _seen["$_f"]=1
      done < <(if [ -n "$_name_pattern" ]; then find "$_base" -maxdepth 1 -type f -name "$_name_pattern" 2>/dev/null; else find "$_base" -type f 2>/dev/null; fi)
    fi
    _i=$((_i+1))
  done
}

# ════════════════════════════════════════════════════════════════════
# SISTEMA.deb v2 (2026-10-08, ver docs/paquetes_deb/ y)
#  Orden de lectura OBLIGATORIO antes de instalar nada (pedido explícito del usuario):
#    1) DEBIAN/manifest.json  — qué es, qué archivos/rutas, qué necesita (paquetes,
#       complementos, módulos), cómo verificar.
#    2) DEBIAN/SHA256SUMS.txt — hash de manifest.json y de CADA archivo del paquete.
#    3) recién entonces se compara contra el sistema/APK (verify) y se instala (apply).
#  Un .deb sin los dos archivos NO se instala (formato v1 viejo: usar `moduledeb reseal`).
# ════════════════════════════════════════════════════════════════════
MODULEDEB_SCHEMA=2
_MD_HOME="${KAIROS_TERMUX_HOME:-/data/data/com.termux/files/home}"
_MD_PREFIX="${KAIROS_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"

# Enriquece el manifest del módulo: marca de paquete Kairos, schema, y para cada dependencia
# decide su "kind" (package = solo `pkg install -y <nombres>` verificable sin ejecutar nada
# ajeno; addon/module = declarados a mano; other = comando libre que pide confirmación).
_moduledeb_write_manifest_v2() {
  local _src="$1" _dst="$2"
  jq --arg date "$(date -Iseconds 2>/dev/null || date)" --argjson schema "$MODULEDEB_SCHEMA" '
    . + {kairos_package: true, schema: $schema, pack_date: $date, checksums_file: "SHA256SUMS.txt"}
    | .dependencies = ((.dependencies // []) | map(
        . as $d
        | ((($d.install_hint // "") | split("&&") | map(gsub("^\\s+|\\s+$"; "")))) as $segs
        | (($segs | length) > 0 and ($segs | all(test("^pkg install -y [A-Za-z0-9+._-]+( [A-Za-z0-9+._-]+)*$")))) as $isPkg
        | $d + {
            kind: (if ($d.kind // null) != null then $d.kind elif $isPkg then "package" else "other" end),
            packages: (if $isPkg then ($segs | map(sub("^pkg install -y "; "") | split(" ")) | add) else [] end)
          }
      ))' "$_src" > "$_dst"
}

# SHA256SUMS.txt dentro del área de control: línea de manifest.json + una por archivo del payload
# + comentarios "# symlink <ruta> -> <destino>". Formato estándar de sha256sum (verificable a mano).
_moduledeb_write_sums() {
  local _staging="$1" _out="$1/DEBIAN/SHA256SUMS.txt"
  {
    echo "# Kairos package checksums (sha256). Formato: <sha256>  <ruta relativa a />. '#' = comentario."
    ( cd "$_staging/DEBIAN" && sha256sum manifest.json | sed 's|  |  DEBIAN/|' )
    ( cd "$_staging" && find . -path ./DEBIAN -prune -o -type f -print0 | xargs -0 -r sha256sum | sed 's|  \./|  |' | LC_ALL=C sort -k2 )
    ( cd "$_staging" && find . -path ./DEBIAN -prune -o -type l -print | LC_ALL=C sort | while IFS= read -r _l; do
        printf '# symlink %s -> %s\n' "${_l#./}" "$(readlink "$_l")"
      done )
  } > "$_out"
}

# Extrae SOLO el área de control (manifest.json, SHA256SUMS.txt, control, postinst) — sin tocar
# el payload. Es lo único que se lee antes de decidir si el paquete se instala.
_moduledeb_extract_control() {
  local _deb="$1" _dest="$2"
  mkdir -p "$_dest"
  if command -v dpkg-deb &>/dev/null; then
    dpkg-deb -e "$_deb" "$_dest" >&2 || return 1
  else
    command -v ar &>/dev/null || return 1
    local _tmp; _tmp="$(mktemp -d)"
    ( cd "$_tmp" && ar x "$_deb" ) >&2 || { rm -rf "$_tmp"; return 1; }
    [ -f "$_tmp/control.tar.xz" ] && tar -xJf "$_tmp/control.tar.xz" -C "$_dest" 2>/dev/null
    [ -f "$_tmp/control.tar.gz" ] && tar -xzf "$_tmp/control.tar.gz" -C "$_dest" 2>/dev/null
    rm -rf "$_tmp"
  fi
  return 0
}

# Valida el área de control (JSON + .txt). Imprime motivos de rechazo (uno por línea) y devuelve 1
# si el paquete NO debe instalarse.
_moduledeb_check_control() {
  local _ctl="$1" _bad=0
  if [ ! -f "$_ctl/manifest.json" ]; then echo "falta DEBIAN/manifest.json (el JSON del paquete es obligatorio)"; _bad=1; fi
  if [ ! -f "$_ctl/SHA256SUMS.txt" ]; then echo "falta DEBIAN/SHA256SUMS.txt (el .txt de hashes es obligatorio)"; _bad=1; fi
  [ "$_bad" -eq 1 ] && return 1
  if ! jq empty "$_ctl/manifest.json" 2>/dev/null; then echo "manifest.json no es un JSON válido"; return 1; fi
  [ "$(jq -r '.kairos_package // false' "$_ctl/manifest.json")" = "true" ] || { echo "manifest.json sin kairos_package:true (no es un paquete Kairos)"; _bad=1; }
  [ -n "$(jq -r '.id // empty' "$_ctl/manifest.json")" ] || { echo "manifest.json sin id"; _bad=1; }
  local _schema; _schema=$(jq -r '.schema // 0' "$_ctl/manifest.json")
  [ "$_schema" -ge 2 ] 2>/dev/null || { echo "manifest.json con schema $_schema (se requiere >= 2)"; _bad=1; }
  local _exp _act
  _exp=$(grep -E '  DEBIAN/manifest\.json$' "$_ctl/SHA256SUMS.txt" | head -1 | cut -d' ' -f1)
  _act=$(sha256sum "$_ctl/manifest.json" | cut -d' ' -f1)
  if [ -z "$_exp" ]; then echo "SHA256SUMS.txt no lista a manifest.json"; _bad=1
  elif [ "$_exp" != "$_act" ]; then echo "el hash de manifest.json no coincide con SHA256SUMS.txt (manifest alterado)"; _bad=1; fi
  [ "$_bad" -eq 0 ]
}

# ¿La ruta absoluta de destino es aceptable? Solo bajo $HOME o $PREFIX, sin "..", y los archivos
# de arranque del shell / .ssh solo si el manifest los declara EXACTOS en files[].
# Usa los arrays globales _MD_DECLARED (rutas exactas de files[]) y _MD_GLOBS (file_globs[]).
_moduledeb_path_allowed() {
  local _p="$1"
  case "$_p" in *"/../"*|*"/.."|"../"*) return 1 ;; esac
  case "$_p" in "$_MD_HOME"/*|"$_MD_PREFIX"/*) ;; *) return 1 ;; esac
  if [ -n "${_MD_DECLARED[$_p]+x}" ]; then return 0; fi
  case "$_p" in
    "$_MD_HOME"/.bashrc|"$_MD_HOME"/.profile|"$_MD_HOME"/.bash_profile|"$_MD_HOME"/.zshrc|"$_MD_HOME"/.ssh|"$_MD_HOME"/.ssh/*) return 1 ;;
  esac
  local _g
  for _g in "${_MD_GLOBS[@]}"; do
    if [[ "$_g" == */\*\* ]]; then
      [[ "$_p" == "${_g%\*\*}"* ]] && return 0
    else
      # shellcheck disable=SC2053
      [[ "$_p" == $_g ]] && return 0
    fi
  done
  return 1
}

# Integridad del payload ya extraído en $1 (con su DEBIAN/ de control): el conjunto de archivos y
# sus hashes debe ser EXACTAMENTE el de SHA256SUMS.txt, sin archivos extra ni especiales, y toda
# ruta de destino debe estar declarada en el manifest y permitida. Devuelve 1 y explica si no.
_moduledeb_check_payload() {
  local _dir="$1" _ctl="$1/DEBIAN" _tmp; _tmp="$(mktemp -d)"
  local _manifest="$_ctl/manifest.json"
  find "$_dir" -path "$_dir/DEBIAN" -prune -o ! -type f ! -type l ! -type d -print 2>/dev/null | head -1 > "$_tmp/special"
  if [ -s "$_tmp/special" ]; then echo "el paquete contiene archivos especiales no permitidos: $(cat "$_tmp/special")"; rm -rf "$_tmp"; return 1; fi
  grep -vE '^#|  DEBIAN/' "$_ctl/SHA256SUMS.txt" | LC_ALL=C sort -k2 > "$_tmp/expected"
  ( cd "$_dir" && find . -path ./DEBIAN -prune -o -type f -print0 | xargs -0 -r sha256sum | sed 's|  \./|  |' | LC_ALL=C sort -k2 ) > "$_tmp/actual"
  if ! diff -q "$_tmp/expected" "$_tmp/actual" >/dev/null 2>&1; then
    echo "el contenido del paquete no coincide con SHA256SUMS.txt:"
    diff "$_tmp/expected" "$_tmp/actual" | head -6
    rm -rf "$_tmp"; return 1
  fi
  grep -E '^# symlink ' "$_ctl/SHA256SUMS.txt" | LC_ALL=C sort > "$_tmp/exp_links"
  ( cd "$_dir" && find . -path ./DEBIAN -prune -o -type l -print | sed 's|^\./||' | LC_ALL=C sort | while IFS= read -r _l; do
      printf '# symlink %s -> %s\n' "$_l" "$(readlink "$_dir/$_l")"; done ) > "$_tmp/act_links"
  if ! diff -q "$_tmp/exp_links" "$_tmp/act_links" >/dev/null 2>&1; then
    echo "los enlaces simbólicos del paquete no coinciden con SHA256SUMS.txt"; rm -rf "$_tmp"; return 1
  fi
  # Política de rutas
  declare -gA _MD_DECLARED=()
  declare -ga _MD_GLOBS=()
  local _p
  while IFS= read -r _p; do [ -n "$_p" ] && _MD_DECLARED["$_p"]=1; done < <(jq -r '.files // [] | .[].path' "$_manifest")
  while IFS= read -r _p; do [ -n "$_p" ] && _MD_GLOBS+=("$_p"); done < <(jq -r '.file_globs // [] | .[].pattern' "$_manifest")
  local _rel _abs
  ( cut -c67- "$_tmp/actual"; sed 's/^# symlink \(.*\) -> .*/\1/' "$_tmp/act_links" ) > "$_tmp/all_paths"
  while IFS= read -r _rel; do
    _abs="/${_rel}"
    if ! _moduledeb_path_allowed "$_abs"; then
      echo "ruta no permitida o no declarada en el manifest: $_abs"
      rm -rf "$_tmp"; return 1
    fi
  done < "$_tmp/all_paths"
  rm -rf "$_tmp"
  return 0
}

# ── moduledeb inspect <ruta.deb> ───────────────────────────────
# Lo PRIMERO que se corre sobre cualquier .deb (GitHub, local o recién creado): lee SOLO el JSON y
# el .txt, no extrae el payload ni ejecuta ningún comando del paquete. Imprime un JSON con el
# veredicto y con lo que el paquete necesita; rc 0 = estructura válida, rc 2 = no instalar.
_moduledeb_inspect() {
  local _deb="$1"
  [ -z "$_deb" ] && error "Uso: moduledeb inspect <ruta.deb>"
  [ -f "$_deb" ] || error "No existe: $_deb"
  local _ctl; _ctl="$(mktemp -d "${TMPDIR:-/tmp}/mdinspect.XXXXXX")"
  if ! _moduledeb_extract_control "$_deb" "$_ctl"; then
    rm -rf "$_ctl"; jq -n '{ok:false, reasons:["no se pudo leer el área de control del .deb (¿archivo corrupto?)"]}'; return 2
  fi
  local _reasons; _reasons=$(_moduledeb_check_control "$_ctl")
  if [ $? -ne 0 ]; then
    jq -n --arg r "$_reasons" '{ok:false, reasons: ($r | split("\n") | map(select(length>0)))}'
    rm -rf "$_ctl"; return 2
  fi
  local _m="$_ctl/manifest.json" _reqs="[]" _i=0 _n _d
  _n=$(jq '.dependencies // [] | length' "$_m")
  while [ "$_i" -lt "$_n" ]; do
    _d=$(jq -c ".dependencies[$_i]" "$_m")
    local _kind _present="unknown" _pk _all=true
    _kind=$(echo "$_d" | jq -r '.kind // "other"')
    if [ "$_kind" = "package" ]; then
      for _pk in $(echo "$_d" | jq -r '.packages[]?'); do
        dpkg -s "$_pk" >/dev/null 2>&1 || _all=false
      done
      $_all && _present="present" || _present="missing"
    fi
    _reqs=$(echo "$_reqs" | jq --argjson d "$_d" --arg p "$_present" '. + [{id: $d.id, kind: ($d.kind // "other"), packages: ($d.packages // []), install_hint: ($d.install_hint // ""), status: $p}]')
    _i=$((_i+1))
  done
  jq -n --slurpfile m "$_m" --argjson reqs "$_reqs" \
    --arg pkg "$(grep -E '^Package:' "$_ctl/control" 2>/dev/null | head -1 | cut -d' ' -f2)" \
    --arg ver "$(grep -E '^Version:' "$_ctl/control" 2>/dev/null | head -1 | cut -d' ' -f2)" \
    --argjson nfiles "$(grep -cvE '^#|  DEBIAN/' "$_ctl/SHA256SUMS.txt")" '
    $m[0] as $x | {ok: true, id: $x.id, package: $pkg, version: $ver, schema: $x.schema, variant: ($x.variant // null),
      files_count: $nfiles, declared_paths: ([$x.files[]?.path] + [$x.file_globs[]?.pattern]),
      requires: $reqs, runs_commands: {verify_cmd: (($x.verify_cmd // "") != ""), patch_cmd: (($x.patch_cmd // "") != ""),
      verify_cmd_text: ($x.verify_cmd // ""), patch_cmd_text: ($x.patch_cmd // ""),
      other_install_hints: [$reqs[] | select(.kind == "other") | .install_hint]}, not_covered: ($x.not_covered // [])}'
  rm -rf "$_ctl"
  return 0
}

# ── moduledeb reseal <ruta.deb> [salida.deb] ───────────────────
# Convierte un .deb v1 (sin SHA256SUMS.txt) al formato v2: recalcula los hashes del payload actual
# y reescribe el manifest. OJO: sella lo que el archivo contiene HOY — sirve para paquetes propios
# viejos, no para validar autenticidad de un .deb ajeno.
_moduledeb_reseal() {
  local _deb="$1" _out="${2:-$1}"
  [ -z "$_deb" ] && error "Uso: moduledeb reseal <ruta.deb> [salida.deb]"
  [ -f "$_deb" ] || error "No existe: $_deb"
  command -v dpkg-deb &>/dev/null || error "reseal requiere dpkg-deb"
  local _w; _w="$(mktemp -d "${TMPDIR:-/tmp}/mdreseal.XXXXXX")"
  dpkg-deb -R "$_deb" "$_w/pkg" || { rm -rf "$_w"; error "dpkg-deb -R falló"; }
  [ -f "$_w/pkg/DEBIAN/manifest.json" ] || { rm -rf "$_w"; error "el .deb no trae manifest.json — no es un paquete Kairos"; }
  _moduledeb_write_manifest_v2 "$_w/pkg/DEBIAN/manifest.json" "$_w/manifest.new" || { rm -rf "$_w"; error "no se pudo reescribir el manifest"; }
  mv "$_w/manifest.new" "$_w/pkg/DEBIAN/manifest.json"
  _moduledeb_write_sums "$_w/pkg"
  _moduledeb_build_deb "$_w/pkg" "$_w/out.deb"
  mv -f "$_w/out.deb" "$_out"
  rm -rf "$_w"
  log "Paquete sellado (schema $MODULEDEB_SCHEMA): $_out"
}

# ── moduledeb pack <id> ───────────────────────────────────────
_moduledeb_pack() {
  local _id="$1"
  [ -z "$_id" ] && error "Uso: moduledeb pack <id> (ej: moduledeb pack claude)"

  local _manifest="$MODULEDEB_BUILD_DIR/.manifest_${_id}_$$.json"
  mkdir -p "$MODULEDEB_BUILD_DIR"
  _moduledeb_resolve_manifest "$_id" "$_manifest"
  trap 'rm -f "$_manifest"' RETURN

  step "Empaquetando módulo '$_id' (manifest resuelto: $_manifest)"

  local _pkgname _desc _verkey _ver _variant
  _pkgname=$(jq -r '.package_name' "$_manifest")
  # description no es campo del contrato --describe-files (§2.1) — el manifest a mano
  # viejo sí lo tenía, este cae a un default legible si falta.
  _desc=$(jq -r '.description // empty' "$_manifest")
  [ -z "$_desc" ] && _desc="Módulo Kairos '$_id' (repackaged desde una instalación ya hecha)"
  _verkey=$(jq -r '.version_registry_key // empty' "$_manifest")
  _ver=$(_moduledeb_version "$_id" "$_verkey")

  # Bug real confirmado 2026-09-11 (ver MEJORAS_PENDIENTES.md "moduledeb: variant en
  # nombre de .deb"): el campo "variant" del manifest (ya documentado en el schema del
  # comment de cabecera de este archivo, nunca leído acá) se ignoraba por completo — dos
  # .deb de variantes DISTINTAS del mismo módulo (ej. opencode instalado con glibc vs con
  # bionic, ollama gpu vs standard) salían con EXACTAMENTE el mismo nombre de archivo y se
  # pisaban entre sí al subirlos a Kairos-Lab, sin forma de distinguirlos. Se sanitiza a
  # caracteres válidos de nombre de paquete Debian (regex real: ^[a-z0-9][a-z0-9+.-]*$,
  # ver man deb-control) por si algún módulo guarda su variante con "_" (ej.
  # ollama.install_mode=termux_npm) — dpkg-deb rechaza "_" en Package:.
  _variant=$(jq -r '.variant // empty' "$_manifest")
  if [ -n "$_variant" ] && [ "$_variant" != "null" ]; then
    _variant=$(printf '%s' "$_variant" | tr '[:upper:]_' '[:lower:]-' | tr -cd 'a-z0-9.+-')
    [ -n "$_variant" ] && _pkgname="${_pkgname}-${_variant}"
  fi

  local _staging="$MODULEDEB_BUILD_DIR/.build_${_id}_$$"
  rm -rf "$_staging"
  mkdir -p "$_staging/DEBIAN"
  # Bug real confirmado en dispositivo (2026-08-23,): el umask de
  # Termux en algunos devices deja DEBIAN/ con 777 — dpkg-deb -b exige <=0775, error
  # "control directory has bad permissions 777" sin este chmod explícito.
  chmod 0755 "$_staging/DEBIAN"
  trap 'rm -rf "$_staging"' EXIT

  # ── Copiar archivos reales (files[] + file_globs[] expandidos) ──────
  local _filelist="$MODULEDEB_BUILD_DIR/.filelist_${_id}_$$"
  _moduledeb_expand_files "$_manifest" "$_filelist"

  # Copia BULK con tar en vez de "mkdir -p + cp -a" por archivo (2026-08-29, bug real
  # confirmado: openclaw/hermes/markserv/nestjs/vercel con árboles node_modules/pip de
  # decenas de miles de archivos hacían que este loop tardara minutos/nunca terminara —
  # cada iteración forkea mkdir + cp, dos procesos nuevos por archivo). Primero se valida
  # existencia (barato, solo "[ -e ]") y se arma una lista limpia de rutas reales; la copia
  # en sí es UN solo "tar | tar" para todo el lote — tar sin --absolute-names pela el "/"
  # inicial de cada miembro al crear el archivo, que es exactamente la misma convención que
  # ya usaba el loop viejo ("_rel=${_path#/}"), así que la estructura resultante en
  # "$_staging" es idéntica, solo que en una fracción del tiempo.
  local _collected=0 _missing_required=0
  local _copylist="$MODULEDEB_BUILD_DIR/.copylist_${_id}_$$"
  : > "$_copylist"
  while IFS= read -r _entry; do
    [ -z "$_entry" ] && continue
    local _required="false" _path="${_entry#??}"
    [ "${_entry:0:1}" = "R" ] && _required="true"

    if [ -e "$_path" ] || [ -L "$_path" ]; then
      printf '%s\n' "$_path" >> "$_copylist"
      _collected=$((_collected+1))
    elif [ "$_required" = "true" ]; then
      warn "Falta archivo REQUERIDO: $_path (¿'$_id' está realmente instalado en este device?)"
      _missing_required=$((_missing_required+1))
    else
      info "Opcional ausente, se omite: $_path"
    fi
  done < "$_filelist"
  rm -f "$_filelist"

  if [ "$_collected" -gt 0 ]; then
    info "Copiando $_collected archivo(s) en bloque..."
    if ! tar -cf - -T "$_copylist" 2>/dev/null | (cd "$_staging" && tar -xf -); then
      rm -f "$_copylist"; rm -rf "$_staging"; trap - EXIT
      error "Falló la copia en bloque (tar) — revisá permisos/espacio en $_staging"
    fi
    log "Copiados $_collected archivo(s) (bulk tar)"
  fi
  rm -f "$_copylist"

  if [ "$_missing_required" -gt 0 ]; then
    rm -rf "$_staging"; trap - EXIT
    error "$_missing_required archivo(s) requerido(s) no encontrados — instalá/ejecutá '$_id' primero antes de empaquetarlo"
  fi
  [ "$_collected" -eq 0 ] && { rm -rf "$_staging"; trap - EXIT; error "Nada para empaquetar (0 archivos encontrados)"; }

  # ── control ──────────────────────────────────────────────────
  cat > "$_staging/DEBIAN/control" << EOF
Package: $_pkgname
Version: $_ver
Section: kairos-module
Priority: optional
Architecture: aarch64
Maintainer: KairosApp <kairos@localhost>
Description: $_desc
EOF

  # ── postinst — SOLO corre dependencies[].check_cmd (avisa si falta) +
  # verify_cmd + patch_cmd del manifest, NUNCA el instalador completo
  # (claude.sh/opencode.sh/n8n.sh). Se embebe el manifest completo en el
  # staging (DEBIAN/manifest.json) y el postinst lo relee con jq en el
  # device destino, en vez de expandir los comandos a texto plano acá —
  # así el mismo postinst sirve para cualquier device sin fragilidad de
  # escaping por heredoc anidado. "kairos_package":true + "pack_date" son un
  # marcador explícito (pedido del usuario,) para que
  # 'moduledeb verify' pueda confirmar que un .deb es un paquete Kairos
  # genuino ANTES de instalar nada, en vez de asumirlo por la sola presencia
  # del archivo manifest.json.
  _moduledeb_write_manifest_v2 "$_manifest" "$_staging/DEBIAN/manifest.json" \
    || { rm -rf "$_staging"; trap - EXIT; error "no se pudo escribir DEBIAN/manifest.json"; }
  cat > "$_staging/DEBIAN/postinst" << 'POSTINST'
#!/data/data/com.termux/files/usr/bin/bash
# Generado por moduledeb.sh — corre SOLO verificación/parche liviano,
# nunca la instalación completa del módulo.
set -u
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"
# dpkg copia cualquier archivo no-estándar del área de control (DEBIAN/) a
# /var/lib/dpkg/info/<paquete>.<archivo> — manifest.json queda como
# <paquete>.manifest.json junto a este mismo postinst ($0 = .../info/<paquete>.postinst).
MANIFEST="${0%.postinst}.manifest.json"
[ -f "$MANIFEST" ] || MANIFEST="$(dirname "$0")/manifest.json"

command -v jq &>/dev/null || { echo "[moduledeb] jq no disponible — no se puede verificar el manifest, archivos ya copiados sin verificar"; exit 0; }
[ -f "$MANIFEST" ] || { echo "[moduledeb] manifest.json no encontrado junto al postinst — archivos copiados sin verificar"; exit 0; }

_id=$(jq -r '.id' "$MANIFEST")
echo "[moduledeb] Verificando dependencias de '$_id'..."

_missing=0
_n_deps=$(jq '.dependencies | length' "$MANIFEST")
_i=0
while [ "$_i" -lt "$_n_deps" ]; do
  _check=$(jq -r ".dependencies[$_i].check_cmd" "$MANIFEST")
  _hint=$(jq -r ".dependencies[$_i].install_hint" "$MANIFEST")
  _depid=$(jq -r ".dependencies[$_i].id" "$MANIFEST")
  if eval "$_check" >/dev/null 2>&1; then
    echo "[moduledeb]   OK: $_depid"
  else
    echo "[moduledeb]   FALTA: $_depid — sugerido: $_hint"
    _missing=$((_missing+1))
  fi
  _i=$((_i+1))
done

_verify=$(jq -r '.verify_cmd // empty' "$MANIFEST")
if [ -n "$_verify" ] && eval "$_verify" >/dev/null 2>&1; then
  echo "[moduledeb] '$_id' verificado OK — no hace falta parchear"
  exit 0
fi

if [ "$_missing" -gt 0 ]; then
  echo "[moduledeb] '$_id' tiene $_missing dependencia(s) faltante(s) — resolvelas antes de reintentar (o instalá '$_id' con su script normal)"
  exit 0
fi

_patch=$(jq -r '.patch_cmd // empty' "$MANIFEST")
if [ -n "$_patch" ]; then
  echo "[moduledeb] Aplicando parche/reparación liviana de '$_id'..."
  if eval "$_patch"; then
    echo "[moduledeb] '$_id' parcheado — verificando de nuevo..."
    if [ -z "$_verify" ] || eval "$_verify" >/dev/null 2>&1; then
      echo "[moduledeb] '$_id' listo"
    else
      echo "[moduledeb] '$_id' se parcheó pero la verificación sigue fallando — revisar manualmente"
    fi
  else
    echo "[moduledeb] parche de '$_id' falló — revisar manualmente"
  fi
else
  echo "[moduledeb] '$_id' no tiene patch_cmd definido y la verificación falló — revisar manualmente"
fi
exit 0
POSTINST
  chmod 0755 "$_staging/DEBIAN/postinst"
  # SHA256SUMS.txt: segundo archivo obligatorio del área de control (formato v2). Se genera DESPUÉS
  # de tener manifest.json y todo el payload en el staging, así cubre exactamente lo que se empaqueta.
  _moduledeb_write_sums "$_staging"

  local _out="$MODULEDEB_OUT_DIR/${_pkgname}_${_ver}_aarch64.deb"
  _moduledeb_build_deb "$_staging" "$_out"

  rm -rf "$_staging"
  trap - EXIT
  log "Deb creado: $_out ($_collected archivos)"

  registry_write moduledeb \
    "installed=true" \
    "last_command=pack" \
    "last_pack=$_id" \
    "last_deb=$_out" \
    "install_date=$(date +%Y-%m-%d)"
  notify_event "moduledeb" "pack_done" "$_id"
}

# ── Fase A: moduledeb extract <ruta.deb> ───────────────────────
# Extrae control+data a un dir temporal AISLADO — nada se ejecuta ni se
# copia a su destino final todavía (diseño §4 Fase A). Imprime la ruta del
# dir extraído en stdout (única línea) para que el llamador (verify/apply,
# o la UI) la capture.
_moduledeb_extract() {
  local _deb="$1"
  [ -z "$_deb" ] && error "Uso: moduledeb extract <ruta.deb>"
  [ -f "$_deb" ] || error "No existe: $_deb"

  local _dir="$HOME/kairos_local/module_deb_extract/$(basename "$_deb" .deb)_$$"
  rm -rf "$_dir"; mkdir -p "$_dir/DEBIAN"

  # 1) PRIMERO solo el área de control: manifest.json + SHA256SUMS.txt. Si falta alguno o el hash
  #    del manifest no cuadra, NO se extrae el payload (pedido explícito del usuario, formato v2).
  _moduledeb_extract_control "$_deb" "$_dir/DEBIAN" || { rm -rf "$_dir"; error "no se pudo leer el área de control del .deb"; }
  local _why; _why=$(_moduledeb_check_control "$_dir/DEBIAN")
  if [ $? -ne 0 ]; then rm -rf "$_dir"; error "El .deb no se instala: $(echo "$_why" | tr '\n' ';')"; fi

  # 2) Recién ahora el payload, y se exige que sea EXACTAMENTE lo que lista SHA256SUMS.txt.
  if command -v dpkg-deb &>/dev/null; then
    dpkg-deb -x "$_deb" "$_dir" >&2 || { rm -rf "$_dir"; error "dpkg-deb -x falló"; }
  else
    local _tmp="$_dir/.ar"
    mkdir -p "$_tmp"
    ( cd "$_tmp" && ar x "$_deb" ) >&2
    [ -f "$_tmp/data.tar.xz" ] && tar -xJf "$_tmp/data.tar.xz" -C "$_dir" 2>/dev/null
    [ -f "$_tmp/data.tar.gz" ] && tar -xzf "$_tmp/data.tar.gz" -C "$_dir" 2>/dev/null
    rm -rf "$_tmp"
  fi
  _why=$(_moduledeb_check_payload "$_dir")
  if [ $? -ne 0 ]; then rm -rf "$_dir"; error "El .deb no se instala: $(echo "$_why" | tr '\n' ';')"; fi
  : > "$_dir/.kairos_checked"
  echo "$_dir"
}

# ── Fase B: moduledeb verify <dir_extraído> ────────────────────
# Compara el manifest.json extraído contra el estado REAL del device (diseño
# §4 Fase B) — nada se aplica todavía. Exit code: 0="hay algo que hacer",
# 1="ya está todo OK", 2="dependencias faltantes o manifest inválido, no se
# puede continuar". Imprime un JSON de resumen en stdout para que la UI lo
# consuma sin reparsear texto libre (formato exacto de docs/arquitectura/
# MODULEDEB_GENERICO.md §4, campo extra "kairos_package_valid" para el
# chequeo de autenticidad pedido por el usuario).
_moduledeb_verify() {
  local _dir="$1"
  [ -z "$_dir" ] && error "Uso: moduledeb verify <dir_extraído_por_extract>"
  local _manifest="$_dir/DEBIAN/manifest.json"
  [ -f "$_manifest" ] || error "No hay DEBIAN/manifest.json en $_dir — corré 'extract' primero"

  # Validación de autenticidad (pedido explícito del usuario, ver
  #): un.deb cualquiera puede TENER un archivo
  # manifest.json de casualidad — se confirma que es un paquete Kairos
  # genuino por la marca kairos_package:true que pack() agrega siempre, más
  # los campos mínimos que todo manifest real tiene.
  local _valid=true
  jq empty "$_manifest" 2>/dev/null || _valid=false
  if $_valid; then
    [ "$(jq -r '.kairos_package // false' "$_manifest")" = "true" ] || _valid=false
    [ -n "$(jq -r '.id // empty' "$_manifest")" ] || _valid=false
  fi
  if ! $_valid; then
    jq -n '{kairos_package_valid: false, error: "manifest.json presente pero no tiene la forma de un paquete Kairos real (falta kairos_package:true o id) — no instalar"}'
    return 2
  fi

  local _id; _id=$(jq -r '.id' "$_manifest")
  local _already_ok=false
  local _verify_cmd; _verify_cmd=$(jq -r '.verify_cmd // empty' "$_manifest")
  if [ -n "$_verify_cmd" ] && eval "$_verify_cmd" >/dev/null 2>&1; then
    _already_ok=true
  fi

  local _deps_json="[]" _missing_deps=0
  local _n_deps; _n_deps=$(jq '.dependencies // [] | length' "$_manifest")
  local _i=0
  while [ "$_i" -lt "$_n_deps" ]; do
    local _check _depid _hint _ok="true"
    _check=$(jq -r ".dependencies[$_i].check_cmd" "$_manifest")
    _depid=$(jq -r ".dependencies[$_i].id" "$_manifest")
    _hint=$(jq -r ".dependencies[$_i].install_hint" "$_manifest")
    eval "$_check" >/dev/null 2>&1 || { _ok="false"; _missing_deps=$((_missing_deps+1)); }
    _deps_json=$(echo "$_deps_json" | jq --arg id "$_depid" --argjson ok "$_ok" --arg hint "$_hint" '. + [{id: $id, ok: $ok, install_hint: $hint}]')
    _i=$((_i+1))
  done

  local _files_json="[]"
  local _n_files; _n_files=$(jq '.files // [] | length' "$_manifest")
  _i=0
  while [ "$_i" -lt "$_n_files" ]; do
    local _raw_path _required _path _action
    _raw_path=$(jq -r ".files[$_i].path" "$_manifest")
    _required=$(jq -r ".files[$_i].required" "$_manifest")
    _path="$_raw_path"
    local _rel="${_path#/}" _staged="$_dir/$_rel"
    # NOTA (2026-08-23,): en pruebas reales de dispositivo esta
    # comparación a veces reporta "overwrite" para un archivo genuinamente idéntico
    # (posible diferencia de metadata que cmp trata como distinto, no confirmado del
    # todo — el dispositivo se desconectó a mitad de la depuración). NO afecta la
    # corrección de _moduledeb_apply (copia igual, sobreescribir con contenido
    # idéntico es un no-op seguro) — es solo una imprecisión cosmética en el resumen
    # que le muestra la UI al usuario. Revisar con más tiempo de dispositivo real.
    if [ ! -e "$_staged" ]; then
      _action="not_in_package"
    elif [ ! -e "$_path" ]; then
      _action="install"
    elif "${TERMUX_PREFIX}/bin/cmp" -s "$_staged" "$_path" 2>/dev/null; then
      _action="skip_identical"
    else
      _action="overwrite"
    fi
    _files_json=$(echo "$_files_json" | jq --arg p "$_path" --arg a "$_action" --argjson r "$_required" '. + [{path: $p, action: $a, required: $r}]')
    _i=$((_i+1))
  done

  jq -n \
    --arg id "$_id" \
    --argjson already_ok "$_already_ok" \
    --argjson deps "$_deps_json" \
    --argjson files "$_files_json" \
    --argjson missing "$_missing_deps" \
    '{kairos_package_valid: true, id: $id, already_ok: $already_ok, dependencies: $deps, files: $files, missing_dependencies: $missing}'

  $_already_ok && return 1
  [ "$_missing_deps" -gt 0 ] && return 2
  return 0
}

# ── Fase C: moduledeb apply <dir_extraído> ─────────────────────
# Copia los archivos a su destino real + corre verify_cmd/patch_cmd (diseño
# §4 Fase C). Corre verify() internamente primero si no se corrió ya —
# nunca aplica sin haber calculado el resumen al menos una vez.
_moduledeb_apply() {
  local _dir="$1"
  [ -z "$_dir" ] && error "Uso: moduledeb apply <dir_extraído_por_extract>"
  local _manifest="$_dir/DEBIAN/manifest.json"
  [ -f "$_dir/.kairos_checked" ] || error "apply solo acepta un directorio producido por 'moduledeb extract' (integridad verificada)"

  local _summary; _summary=$(_moduledeb_verify "$_dir")
  local _rc=$?
  local _id; _id=$(echo "$_summary" | jq -r '.id // empty')

  if [ "$_rc" -eq 2 ]; then
    error "'$_id' no se puede instalar: manifest inválido o dependencias faltantes — $(echo "$_summary" | jq -r '.error // "ver dependencies[] en la salida de verify"')"
  fi
  if [ "$_rc" -eq 1 ]; then
    log "'$_id' ya está OK — no se copia ni se parchea nada"
    notify_event "moduledeb" "apply_done" "$_id"
    return 0
  fi

  step "Aplicando '$_id' — copiando archivos a su destino real"
  # Bug real #3 encontrado 2026-08-24 (ver mismo ciclo de prueba real
  # que encontró el bug de "eval echo" en _moduledeb_expand_files): esta función solo
  # copiaba .files[] del manifest, IGNORANDO .file_globs[] por completo — a diferencia de
  # _moduledeb_expand_files() (usada en pack/verify), que sí procesa ambos. Con el fix de
  # arriba, el .deb de codegraph ya empaquetaba correctamente sus 913 archivos de runtime
  # vía file_globs[], pero "apply" seguía restaurando SOLO el wrapper (1 archivo) — el
  # binario quedaba instalado pero roto (MODULE_NOT_FOUND al ejecutar), confirmado en vivo
  # en dispositivo real. Fix: en vez de re-leer files[]/file_globs[] y re-resolverlos (ya
  # resueltos una vez al empaquetar, redundante y con la misma superficie de bugs), se
  # copia TODO lo que el extract realmente dejó en el staging ($_dir), que es exactamente
  # el conjunto real empaquetado — más simple y no puede desincronizarse del contenido real
  # del .deb. Se excluye $_dir/DEBIAN (área de control, nunca un archivo de destino real).
  while IFS= read -r -d '' _staged; do
    local _rel="${_staged#"$_dir"/}"
    local _path="/$_rel"
    mkdir -p "$(dirname "$_path")"
    cp -a "$_staged" "$_path"
    log "Copiado: $_path"
  done < <(find "$_dir" -path "$_dir/DEBIAN" -prune -o -type f -print0 -o -type l -print0)

  local _verify_cmd _patch_cmd
  _verify_cmd=$(jq -r '.verify_cmd // empty' "$_manifest")
  if [ -n "$_verify_cmd" ] && eval "$_verify_cmd" >/dev/null 2>&1; then
    log "'$_id' listo — instalado sin necesitar parche"
  else
    _patch_cmd=$(jq -r '.patch_cmd // empty' "$_manifest")
    if [ -n "$_patch_cmd" ]; then
      info "Aplicando parche/reparación liviana de '$_id'..."
      if eval "$_patch_cmd"; then
        if [ -z "$_verify_cmd" ] || eval "$_verify_cmd" >/dev/null 2>&1; then
          log "'$_id' parcheado y verificado"
        else
          warn "'$_id' se parcheó pero la verificación sigue fallando — revisar manualmente"
        fi
      else
        warn "Parche de '$_id' falló — revisar manualmente"
      fi
    else
      warn "'$_id' no tiene patch_cmd definido y la verificación falló — revisar manualmente"
    fi
  fi

  rm -rf "$_dir"
  notify_event "moduledeb" "apply_done" "$_id"
}

# ── moduledeb install <ruta.deb> ── azúcar sintáctica: extract → verify
# (solo loggea, no bloquea salvo rc=2) → apply, encadenadas. Para uso desde
# terminal/scripts donde no hace falta el punto de pausa manual — la UI
# nueva llama las 3 fases por separado (diseño §4, "Compatibilidad").
_moduledeb_install() {
  local _deb="$1"
  [ -z "$_deb" ] && error "Uso: moduledeb install <ruta.deb> (generado por 'moduledeb pack')"
  [ -f "$_deb" ] || error "No existe: $_deb"

  step "Instalando $_deb (extract → verify → apply encadenados)"
  local _dir; _dir=$(_moduledeb_extract "$_deb")
  local _summary; _summary=$(_moduledeb_verify "$_dir")
  info "Resumen: $_summary"
  _moduledeb_apply "$_dir"
  log "Instalación aplicada: $_deb"
}

_moduledeb_list() {
  echo "Manifests disponibles en $MANIFEST_DIR:"
  for _f in "$MANIFEST_DIR"/*.json; do
    [ -f "$_f" ] || continue
    local _id _desc
    _id=$(jq -r '.id' "$_f" 2>/dev/null)
    _desc=$(jq -r '.description' "$_f" 2>/dev/null)
    echo "  - $_id: $_desc"
  done
}

case "$_SUBCMD" in
  pack)    _moduledeb_pack "$_SUBARG" ;;
  inspect) _moduledeb_inspect "$_SUBARG" ;;
  reseal)  _moduledeb_reseal "$_SUBARG" "$_SUBARG2" ;;
  extract) _moduledeb_extract "$_SUBARG" ;;
  verify)  _moduledeb_verify "$_SUBARG" ;;
  apply)   _moduledeb_apply "$_SUBARG" ;;
  install) _moduledeb_install "$_SUBARG" ;;
  list)    _moduledeb_list ;;
  "")
    if $SILENT; then
      info "Sin subcomando — listando manifests disponibles"
      _moduledeb_list
    else
      error "Uso: moduledeb {pack <id>|inspect <deb>|reseal <deb> [salida]|extract <deb>|verify <dir>|apply <dir>|install <deb>|list}"
    fi
    ;;
  *)
    error "Subcomando desconocido: $_SUBCMD"
    ;;
esac
# Bug real confirmado en dispositivo (2026-08-23,): un "exit 0"
# incondicional acá pisaba el exit code real de 'verify' (0=hay algo que hacer,
# 1=ya está OK, 2=dependencias faltantes/manifest inválido) — la UI/wrapper que llama
# 'moduledeb verify' necesita ese código real para decidir qué mostrar, no siempre 0.
exit $?
