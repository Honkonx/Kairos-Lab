#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · typescript.sh (silent mode)
#  TypeScript — npm install -g typescript (requiere Node.js, se instala solo si falta).
#
#  FUENTE: referencia/termux/core-termux-main/core/tools/npm/typescript/install.sh
#  (mismo comando real: npm install -g typescript) — ver ronda "paquetes
#  adicionales core-termux" en docs/humano/.
#
#  USO DESDE APP (KairosApp):
#    bash typescript.sh --silent
#    bash typescript.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ Node.js LTS (solo si no está ya instalado por otro módulo)
#    ✅ TypeScript (tsc).
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

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
{"id":"typescript","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Instalación en 2 capas: el
# wrapper/symlink npm de "tsc" (resuelto por PATH, mismo patrón que freebuff.sh)
# + el árbol completo de node_modules/typescript (file_globs — TypeScript 7.x
# reescribió tsc en Go nativo y el loader busca un paquete de plataforma
# "@typescript/typescript-android-arm64" copiado a mano desde el paquete
# linux-arm64 real, ver el patch inline más abajo en este mismo script).
if $DESCRIBE_FILES; then
  TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  _bin=$(command -v tsc 2>/dev/null || echo "$TERMUX_PREFIX/bin/tsc")
  _nm=$(npm root -g 2>/dev/null || echo "$TERMUX_PREFIX/lib/node_modules")
  jq -n \
    --arg path "$_bin" \
    --arg glob1 "$_nm/typescript/**" \
    --arg glob2 "$_nm/@typescript/**" \
    --arg verify "command -v tsc >/dev/null 2>&1 && tsc --version >/dev/null 2>&1" \
    '{
      id: "typescript",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-typescript",
      version_registry_key: "typescript.version",
      files: [{path: $path, required: true, note: "Wrapper/symlink npm de tsc, resuelto por PATH al momento de empaquetar"}],
      file_globs: [
        {pattern: $glob1, required: true, note: "árbol npm global de typescript"},
        {pattern: $glob2, required: false, note: "paquete de plataforma @typescript/typescript-android-arm64 — copia parcheada del paquete linux-arm64 real, ver patch inline en este script (TypeScript 7.x tsc nativo en Go)"}
      ],
      dependencies: [{id: "node", check_cmd: "command -v node >/dev/null 2>&1", install_hint: "pkg install -y nodejs-lts"}],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "No reinstala Node.js — asume que el device destino ya tiene el mismo Termux base",
        "Si TypeScript sube de mayor versión, el nombre exacto del paquete de plataforma linux-arm64 puede cambiar — el patch real vive en este script, no en el manifest"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_typescript_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
# antes esto era "install_npm_global typescript typescript tsc" seguido del fix de plataforma
# de abajo — pero install_npm_global() YA verifica "tsc --version" internamente (lib.sh,
# verify_binary_installed) ANTES de devolver el control acá, y esa verificación SIEMPRE falla
# (ver el bug de plataforma explicado abajo) — install_npm_global() llama a error() (exit 1)
# ahí mismo, así que el fix de abajo NUNCA se llegaba a ejecutar: código muerto, confirmado en
# dispositivo real (mismo patrón exacto ya encontrado y corregido para localtunnel.sh esta
# misma ronda). Fix: no usar install_npm_global() de punta a punta acá — instalar+parchear+
# verificar en el orden correcto, a mano.
if command -v tsc &>/dev/null && [ "${FORCE:-false}" != "true" ]; then
  log "typescript ya instalado"
  registry_write typescript "installed=true"
else
  ensure_node_installed
  if ! npm install -g typescript &>/dev/null; then
    error "No se pudo instalar typescript (npm install -g typescript falló)"
  fi
  command -v tsc &>/dev/null || error "typescript no disponible tras la instalación (npm)"
  fix_npm_shebang_wrapper "tsc" "typescript"

# TypeScript 7.x reescribió tsc en Go nativo ("tsgo") — el JS wrapper (getExePath.js) resuelve
# el binario real vía un paquete de plataforma "@typescript/typescript-<platform>-<arch>", y
# para "process.platform==='android'" ese paquete nunca existe ("@typescript/
# typescript-android-arm64" no publicado) — mismo patrón sistémico ya visto en copilotcli/
# codebuff/kilo esta sesión. A diferencia de esos casos, el binario Go (package/lib/tsc) es
# ESTÁTICO (confirmado con readelf -l, sin PT_INTERP) — corre directo sobre Bionic sin
# glibc/patchelf. Fix: copiar el paquete linux-arm64 real a un directorio nombrado
# "typescript-android-arm64" (el nombre que el loader busca en este device) + corregir el
# campo "name" de su package.json (Node valida el nombre interno al resolver el módulo).
if ! tsc --version &>/dev/null; then
  info "tsc no ejecuta (falta el paquete de plataforma) — instalando @typescript/typescript-linux-arm64 a mano..."
  _TS_NM="$(npm root -g 2>/dev/null)/@typescript"
  _TS_VER=$(node -e "console.log(require('$_TS_NM/../typescript/package.json').version)" 2>/dev/null)
  if [ -n "$_TS_VER" ]; then
    npm install -g "@typescript/typescript-linux-arm64@${_TS_VER}" --force 2>&1 | tail -5
  fi
  if [ -d "$_TS_NM/typescript-linux-arm64" ]; then
    rm -rf "$_TS_NM/typescript-android-arm64"
    cp -r "$_TS_NM/typescript-linux-arm64" "$_TS_NM/typescript-android-arm64"
    node -e "
      const fs=require('fs');
      const p='$_TS_NM/typescript-android-arm64/package.json';
      const j=JSON.parse(fs.readFileSync(p,'utf8'));
      j.name='@typescript/typescript-android-arm64';
      fs.writeFileSync(p, JSON.stringify(j,null,2));
    " 2>/dev/null
    chmod +x "$_TS_NM/typescript-android-arm64/lib/tsc" 2>/dev/null
  fi
  tsc --version &>/dev/null || error "tsc no ejecuta tras instalar @typescript/typescript-linux-arm64 (revisá manualmente: tsc --version)"
  log "tsc ejecuta correctamente tras instalar el paquete de plataforma"
fi

  verify_binary_installed "tsc" || error "typescript no ejecuta tras la instalación (revisá manualmente: tsc --version)"
  registry_install typescript "$(npm ls -g typescript --depth=0 2>/dev/null | sed -nE 's#.*typescript@([0-9.A-Za-z-]+).*#\1#p')"
  log "typescript instalado"
fi

notify_event "typescript" "install_done" ""
exit 0
