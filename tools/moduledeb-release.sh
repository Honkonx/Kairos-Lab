#!/usr/bin/env bash
# ============================================================
#  tools/moduledeb-release.sh — empaqueta TODOS los módulos de Kairos que
#  soportan --describe-files (via `modulos/moduledeb.sh pack <id>`) en un
#  solo lote, arma un manifest.json + checksums + .tar.xz, y (opcionalmente)
#  lo publica como asset de una GitHub Release.
#
#  ⚠️  HERRAMIENTA PREPARADA PARA USO FUTURO — NO ES PARTE DEL PIPELINE DE
#  CI ACTUAL. Ningún workflow de .github/workflows/ ni .gitlab-ci.yml la
#  invoca — es standalone, se corre a mano cuando se decida activar la
#  distribución de módulos pre-empaquetados. Correrla NO instala ni
#  modifica nada del propio Kairos: solo lee módulos ya instalados en este
#  device (modo pack) o copia .deb ya existentes (modo bundle), y escribe
#  archivos nuevos bajo --out-dir. Ver
#  docs/arquitectura/PAQUETES_MODULOS_RELEASE_2026-08-26.md para el
#  contexto completo y qué falta decidir antes de usarla de verdad
#  (repo/Release destino).
#
#  DOS SUPERFICIES DE USO (mismo script, mismo bash — el pedido original
#  fue "WSL Debian y Android", esto es lo que realmente distingue a cada
#  uno dado cómo funciona moduledeb.sh):
#
#  1) ANDROID/TERMUX — modo "pack" (default, sin --debs-dir):
#     Corre DENTRO de Termux/Kairos, en un dispositivo real donde los
#     módulos YA ESTÁN INSTALADOS (mismo supuesto que `moduledeb.sh pack`
#     — empaqueta una instalación ya hecha, no instala nada de cero). Este
#     script descubre todos los módulos con --describe-files y llama
#     `modulos/moduledeb.sh pack <id>` para cada uno, uno por uno —
#     cualquier módulo no instalado (o con archivos requeridos faltantes)
#     se SALTEA con un aviso, sin abortar el resto del lote.
#
#  2) WSL DEBIAN (u otra PC cualquiera) — modo "bundle" (con --debs-dir):
#     WSL Debian no tiene el filesystem de Termux (las rutas $PREFIX de
#     Android bajo /data/data/com.termux/... no existen ahí) — no puede
#     empaquetar en frío un módulo que no está instalado en ESE entorno.
#     En este modo el script NO llama a `moduledeb.sh pack`: toma un
#     directorio con .deb ya generados (ej. copiados desde un dispositivo
#     Android real vía `adb pull`/rsync tras correr el modo 1 ahí) y solo
#     hace el bundling (manifest.json + checksums + tar.xz) y,
#     opcionalmente, la subida a GitHub Release. El bundling en sí (tar,
#     xz, sha256sum, gh) es Linux estándar — corre igual en WSL Debian que
#     en Termux, no hace falta distinguirlo en el código, solo en cuál de
#     los dos modos tiene sentido usar en cada máquina.
#
#  USO:
#    tools/moduledeb-release.sh [--out-dir DIR] [--only id1,id2,...]
#                                [--debs-dir DIR]
#                                [--upload --release-tag TAG]
#
#  FLAGS:
#    --out-dir DIR       Carpeta de salida (default: build/module-release/
#                         relativo a la raíz del repo)
#    --only id1,id2      Solo procesa esos módulos, separados por coma
#                         (default: todos los que soportan --describe-files,
#                         descubiertos vía grep sobre modulos/*.sh)
#    --debs-dir DIR      Modo "bundle": usa los .deb ya generados en DIR en
#                         vez de invocar `moduledeb.sh pack` (ver modo 2
#                         arriba) — pensado para WSL Debian / cualquier PC
#                         sin módulos instalados localmente
#    --upload            Sube el resultado (tar.xz + checksum + manifest)
#                         a una GitHub Release real con `gh release
#                         create`/`gh release upload`. SIN este flag el
#                         script NUNCA toca ningún Release — solo genera
#                         archivos locales bajo --out-dir. Requiere `gh`
#                         autenticado (variable GITHUB_TOKEN en el entorno,
#                         o `gh auth login` ya hecho — mismo criterio que
#                         usa build-rootfs.yml para publicar el rootfs)
#    --release-tag TAG   Tag de la Release a crear/actualizar. Requerido
#                         junto con --upload
#    -h, --help           Muestra esta ayuda y sale
#
#  SALIDA (bajo --out-dir):
#    debs/                      — copia de todos los .deb empaquetados/bundleados
#    kairos-modules.manifest.json — módulo → {package, version, deb, sha256}
#                                  (mismo espíritu que tools/rootfs/build_rootfs.py
#                                  → manifest.json, pero por módulo en vez de por
#                                  paquete apt)
#    kairos-modules.tar.xz       — debs/ + el manifest, comprimido junto
#    kairos-modules.tar.xz.sha256 — checksum del tar.xz (mismo patrón que
#                                  build-rootfs.yml)
#    kairos-modules-index.json   — índice liviano (id/filename/version/date/
#                                  built_on por paquete), para que el APK
#                                  sepa qué módulos hay en el Release SIN
#                                  adivinar nombres de archivo. Se genera
#                                  siempre, sin flag — no es el manifest
#                                  interno de arriba, es un catálogo aparte
#    index.md                    — misma info que el índice JSON, en tabla
#                                  Markdown para lectura humana
#    pack.log                    — salida completa de cada `moduledeb.sh pack`
#                                  corrida (solo en modo pack)
#
#  Reusa el mismo patrón ya usado por tools/rootfs/build_rootfs.py +
#  .github/workflows/build-rootfs.yml para el rootfs embebido: armar un
#  manifest.json trazable + tar.xz + checksum, publicable como asset de una
#  GitHub Release separada del pipeline de build de la app — pero para
#  paquetes de MÓDULOS individuales (via modulos/moduledeb.sh) en vez del
#  rootfs completo (via tools/rootfs/build_rootfs.py).
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 (standalone, sin ejecutar todavía — script preparado
#  para uso futuro) | Agosto 2026
# ============================================================

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODULOS_DIR="$REPO_ROOT/modulos"
MODULEDEB_SCRIPT="$MODULOS_DIR/moduledeb.sh"
ANDROID_MODULEDEB_OUT_DIR="/storage/emulated/0/Download/kairos"

OUT_DIR="$REPO_ROOT/build/module-release"
DEBS_SRC_DIR=""
DO_UPLOAD=false
RELEASE_TAG=""
ONLY_IDS=""

log()  { printf '[moduledeb-release] %s\n' "$*"; }
warn() { printf '[moduledeb-release] AVISO: %s\n' "$*" >&2; }
die()  { printf '[moduledeb-release] ERROR: %s\n' "$*" >&2; exit 1; }

print_usage() {
  awk '/^# {2}USO:/{p=1} p{print substr($0,3)} /^# {2}VERSIÓN:/{exit}' "$SCRIPT_DIR/moduledeb-release.sh"
}

# ── Parseo de argumentos ────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir)
      [ $# -ge 2 ] || die "--out-dir requiere un valor"
      OUT_DIR="$2"; shift 2 ;;
    --only)
      [ $# -ge 2 ] || die "--only requiere un valor (ej: --only claude,opencode)"
      ONLY_IDS="$2"; shift 2 ;;
    --debs-dir)
      [ $# -ge 2 ] || die "--debs-dir requiere un valor"
      DEBS_SRC_DIR="$2"; shift 2 ;;
    --upload)
      DO_UPLOAD=true; shift ;;
    --release-tag)
      [ $# -ge 2 ] || die "--release-tag requiere un valor"
      RELEASE_TAG="$2"; shift 2 ;;
    -h|--help)
      print_usage; exit 0 ;;
    *)
      die "Flag desconocido: '$1' (ver --help)" ;;
  esac
done

if $DO_UPLOAD && [ -z "$RELEASE_TAG" ]; then
  die "--upload requiere --release-tag TAG (no se sube nada sin un tag explícito)"
fi

mkdir -p "$OUT_DIR" || die "no se pudo crear el directorio de salida: $OUT_DIR"
DEBS_OUT_DIR="$OUT_DIR/debs"
mkdir -p "$DEBS_OUT_DIR" || die "no se pudo crear: $DEBS_OUT_DIR"
PACK_LOG="$OUT_DIR/pack.log"
: > "$PACK_LOG"

# ── Descubrir módulos empaquetables ─────────────────────────────
# Mismo criterio que usa modulos/moduledeb.sh internamente: un módulo es
# empaquetable si su script implementa el flag --describe-files (case
# real, no solo texto mencionado en un comentario). moduledeb.sh en sí
# mismo se excluye — es el empaquetador, no un módulo empaquetable.
discover_module_ids() {
  local f id
  for f in "$MODULOS_DIR"/*.sh; do
    [ -f "$f" ] || continue
    id="$(basename "$f" .sh)"
    [ "$id" = "moduledeb" ] && continue
    grep -q -- '--describe-files)' "$f" 2>/dev/null && printf '%s\n' "$id"
  done
}

if [ -n "$ONLY_IDS" ]; then
  MODULE_IDS="$(printf '%s' "$ONLY_IDS" | tr ',' '\n')"
else
  MODULE_IDS="$(discover_module_ids)"
fi
[ -n "$MODULE_IDS" ] || die "no se encontró ningún módulo empaquetable (¿corriste esto fuera del repo de Kairos?)"

PACKED_IDS=""
SKIPPED_IDS=""

# ── Extrae la ruta del .deb generado de la salida de `moduledeb pack` ──
# moduledeb.sh imprime "[OK] Deb creado: <ruta> (<n> archivos)" al final de
# un pack exitoso (ver modulos/lib.sh log()) — más robusto que adivinar
# "el .deb más nuevo" en una carpeta que puede tener corridas viejas.
extract_deb_path_from_log() {
  local text="$1"
  printf '%s\n' "$text" \
    | grep -o 'Deb creado: [^ ]*\.deb' \
    | sed 's/^Deb creado: //' \
    | tail -1
}

pack_one_module() {
  local id="$1" output rc deb_path
  log "Empaquetando módulo '$id' via 'moduledeb.sh pack $id'..."
  {
    echo "===== $id ($(date -Iseconds 2>/dev/null || date)) ====="
  } >> "$PACK_LOG"

  output="$(bash "$MODULEDEB_SCRIPT" pack "$id" --silent 2>&1)"
  rc=$?
  printf '%s\n' "$output" >> "$PACK_LOG"

  if [ "$rc" -ne 0 ]; then
    warn "'$id' — moduledeb pack falló (rc=$rc). Probable causa: el módulo no está instalado en este device. Detalle en $PACK_LOG"
    SKIPPED_IDS="$SKIPPED_IDS $id"
    return 0
  fi

  deb_path="$(extract_deb_path_from_log "$output")"
  if [ -z "$deb_path" ] || [ ! -f "$deb_path" ]; then
    warn "'$id' — moduledeb pack terminó con éxito pero no se pudo confirmar la ruta del .deb resultante — ver $PACK_LOG"
    SKIPPED_IDS="$SKIPPED_IDS $id"
    return 0
  fi

  cp -a "$deb_path" "$DEBS_OUT_DIR/" || { warn "'$id' — no se pudo copiar $deb_path a $DEBS_OUT_DIR"; SKIPPED_IDS="$SKIPPED_IDS $id"; return 0; }
  log "'$id' empaquetado: $(basename "$deb_path")"
  PACKED_IDS="$PACKED_IDS $id"
}

if [ -n "$DEBS_SRC_DIR" ]; then
  # ── Modo "bundle" (pensado para WSL Debian): reusar .deb ya generados ──
  [ -d "$DEBS_SRC_DIR" ] || die "--debs-dir no existe: $DEBS_SRC_DIR"
  log "Modo bundle: usando .deb ya generados en '$DEBS_SRC_DIR' (no se empaqueta nada nuevo, no se requiere moduledeb.sh ni módulos instalados localmente)"
  found_any=false
  while IFS= read -r -d '' deb; do
    cp -a "$deb" "$DEBS_OUT_DIR/" || { warn "no se pudo copiar $deb"; continue; }
    id="$(basename "$deb" | sed -E 's/_[0-9][^_]*_aarch64\.deb$//')"
    PACKED_IDS="$PACKED_IDS $id"
    found_any=true
  done < <(find "$DEBS_SRC_DIR" -maxdepth 1 -type f -name '*.deb' -print0)
  $found_any || die "no se encontró ningún .deb en $DEBS_SRC_DIR"
else
  # ── Modo "pack" (pensado para Android/Termux): empaquetar en vivo ──────
  [ -f "$MODULEDEB_SCRIPT" ] || die "no se encontró $MODULEDEB_SCRIPT"
  command -v jq >/dev/null 2>&1 || die "jq no disponible en PATH — requerido por modulos/moduledeb.sh (paquete 'jq' de Termux/apt)"
  if [ ! -d "$(dirname "$ANDROID_MODULEDEB_OUT_DIR")" ] 2>/dev/null; then
    warn "este entorno no parece tener /storage/emulated/0 (típico de WSL/Linux normal, no Android) — moduledeb.sh pack casi seguro va a fallar para todos los módulos por archivos $PREFIX faltantes. Para WSL Debian usá --debs-dir en vez de este modo (ver cabecera del script)."
  fi
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    pack_one_module "$id"
  done <<EOF_IDS
$MODULE_IDS
EOF_IDS
fi

PACKED_IDS="$(printf '%s' "$PACKED_IDS" | xargs -n1 2>/dev/null | sort -u | tr '\n' ' ')"
SKIPPED_IDS="$(printf '%s' "$SKIPPED_IDS" | xargs -n1 2>/dev/null | sort -u | tr '\n' ' ')"

deb_count="$(find "$DEBS_OUT_DIR" -maxdepth 1 -type f -name '*.deb' | wc -l | tr -d ' ')"
[ "$deb_count" -gt 0 ] || die "0 .deb generados/copiados — nada para empaquetar. Empaquetados: [${PACKED_IDS:-ninguno}] / Salteados: [${SKIPPED_IDS:-ninguno}]"

log "Total de .deb listos: $deb_count — empaquetados/copiados: [${PACKED_IDS:-ninguno}]"
[ -n "$SKIPPED_IDS" ] && warn "Módulos salteados (no empaquetados): [${SKIPPED_IDS# }]"

# ── Detección real del entorno (para el campo "built_on" del índice) ───
# No confiar en $PREFIX a secas (puede quedar seteado por herencia de shell
# en entornos raros) — confirmar que además el directorio existe, mismo
# criterio empírico que ya usa el script para distinguir Android/WSL más
# arriba (chequeo de /storage/emulated/0).
detect_built_on() {
  if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ]; then
    printf 'android'
  elif [ -d "/data/data/com.termux/files/usr" ]; then
    printf 'android'
  else
    printf 'wsl-debian'
  fi
}
BUILT_ON="$(detect_built_on)"

# ── Extrae id/versión del nombre de archivo del .deb ────────────────────
# Convención real de moduledeb.sh: "<id>_<version>_aarch64.deb" — mismo
# patrón que ya usa el modo bundle más arriba para reconstruir el id.
extract_id_from_filename() {
  printf '%s' "$1" | sed -E 's/_[0-9][^_]*_aarch64\.deb$//'
}
extract_version_from_filename() {
  printf '%s' "$1" | sed -E 's/^.*_([0-9][^_]*)_aarch64\.deb$/\1/'
}

# ── Manifest.json (mismo espíritu que tools/rootfs/build_rootfs.py) ────
MANIFEST_FILE="$OUT_DIR/kairos-modules.manifest.json"
{
  printf '{\n'
  printf '  "generated_at": "%s",\n' "$(date -Iseconds 2>/dev/null || date)"
  printf '  "generator": "tools/moduledeb-release.sh",\n'
  printf '  "mode": "%s",\n' "$([ -n "$DEBS_SRC_DIR" ] && echo bundle || echo pack)"
  printf '  "packages": [\n'
  first=true
  for deb in "$DEBS_OUT_DIR"/*.deb; do
    [ -f "$deb" ] || continue
    name="$(basename "$deb")"
    sha="$(sha256sum "$deb" 2>/dev/null | awk '{print $1}')"
    size="$(wc -c < "$deb" | tr -d ' ')"
    $first || printf ',\n'
    first=false
    printf '    {"file": "%s", "sha256": "%s", "size_bytes": %s}' "$name" "$sha" "$size"
  done
  printf '\n  ]\n'
  printf '}\n'
} > "$MANIFEST_FILE"
log "Manifest escrito: $MANIFEST_FILE"

# ── Índice liviano (JSON + Markdown) — distinto del manifest interno ───
# El manifest de arriba es el detalle técnico de ESTE paquete (checksums,
# tamaños en bytes). El índice es lo que el APK/un humano necesitan ANTES
# de descargar nada: qué módulos existen en este Release y con qué nombre
# de archivo exacto — sin tener que adivinar convenciones de nombre ni
# descomprimir el tar.xz primero. Se genera siempre (no hay flag que lo
# desactive) porque es información gratis a partir de lo que el script ya
# sabe de cada .deb empaquetado.
INDEX_JSON_FILE="$OUT_DIR/kairos-modules-index.json"
INDEX_MD_FILE="$OUT_DIR/index.md"
GENERATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date)"

{
  printf '{\n'
  printf '  "generated_at": "%s",\n' "$GENERATED_AT"
  printf '  "packages": [\n'
  first=true
  for deb in "$DEBS_OUT_DIR"/*.deb; do
    [ -f "$deb" ] || continue
    name="$(basename "$deb")"
    id="$(extract_id_from_filename "$name")"
    version="$(extract_version_from_filename "$name")"
    date_str="$(date -u +%Y-%m-%d 2>/dev/null || date)"
    $first || printf ',\n'
    first=false
    printf '    {"id": "%s", "filename": "%s", "version": "%s", "date": "%s", "built_on": "%s"}' \
      "$id" "$name" "$version" "$date_str" "$BUILT_ON"
  done
  printf '\n  ]\n'
  printf '}\n'
} > "$INDEX_JSON_FILE"
log "Índice JSON escrito: $INDEX_JSON_FILE"

{
  printf '# Índice de módulos — Kairos\n\n'
  printf 'Catálogo liviano de los paquetes `.deb` de módulos incluidos en este Release. '
  printf 'A diferencia de `kairos-modules.manifest.json` (checksums/tamaños del `.tar.xz` '
  printf 'generado en esta corrida), este archivo es para saber de antemano qué módulos '
  printf 'existen y con qué nombre de archivo exacto — sin descomprimir nada primero.\n\n'
  printf 'Generado: `%s` — release tag: `%s` — generador: `tools/moduledeb-release.sh`\n\n' \
    "$GENERATED_AT" "${RELEASE_TAG:-(sin --release-tag, generación local)}"
  printf '| Módulo | Archivo | Versión | Fecha | Origen |\n'
  printf '|---|---|---|---|---|\n'
  for deb in "$DEBS_OUT_DIR"/*.deb; do
    [ -f "$deb" ] || continue
    name="$(basename "$deb")"
    id="$(extract_id_from_filename "$name")"
    version="$(extract_version_from_filename "$name")"
    date_str="$(date -u +%Y-%m-%d 2>/dev/null || date)"
    printf '| %s | %s | %s | %s | %s |\n' "$id" "$name" "$version" "$date_str" "$BUILT_ON"
  done
} > "$INDEX_MD_FILE"
log "Índice Markdown escrito: $INDEX_MD_FILE"

# ── tar.xz + checksum (mismo patrón que build-rootfs.yml) ──────────────
TARBALL="$OUT_DIR/kairos-modules.tar.xz"
tar -cJf "$TARBALL" -C "$OUT_DIR" debs kairos-modules.manifest.json \
  || die "falló armar $TARBALL"
sha256sum "$TARBALL" 2>/dev/null | awk '{print $1}' > "$TARBALL.sha256" \
  || die "falló calcular el checksum de $TARBALL"
log "Tarball listo: $TARBALL"
log "Checksum: $(cat "$TARBALL.sha256") ($TARBALL.sha256)"

# ── Subida opcional a GitHub Release ────────────────────────────────────
if $DO_UPLOAD; then
  command -v gh >/dev/null 2>&1 || die "--upload requiere 'gh' (GitHub CLI) en PATH"
  log "Subiendo a la Release '$RELEASE_TAG' del repo actual con 'gh release create/upload'..."
  if gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
    gh release upload "$RELEASE_TAG" "$TARBALL" "$TARBALL.sha256" "$MANIFEST_FILE" \
      "$INDEX_JSON_FILE" "$INDEX_MD_FILE" --clobber \
      || die "gh release upload falló"
  else
    gh release create "$RELEASE_TAG" "$TARBALL" "$TARBALL.sha256" "$MANIFEST_FILE" \
      "$INDEX_JSON_FILE" "$INDEX_MD_FILE" \
      --title "Kairos modules — $RELEASE_TAG" \
      --notes "Paquetes .deb de módulos de Kairos, generados por tools/moduledeb-release.sh. No es una release de la app — ver docs/arquitectura/PAQUETES_MODULOS_RELEASE_2026-08-26.md." \
      --prerelease \
      || die "gh release create falló"
  fi
  log "Publicado en la Release '$RELEASE_TAG'."
else
  log "Modo local (sin --upload) — nada se subió a ningún Release. Archivos listos en: $OUT_DIR"
fi

log "Listo."
