#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · git.sh (silent mode)
#  Módulo de propósito general Git/GitHub — paquetes nativos de
#  Termux (pkg install git gh).
#
#  ORIGEN: hasta esta versión, "git push" vivía embebido dentro de
#  expo.sh (acoplado al proyecto Expo activo) — pedido explícito
#  del usuario: "sobre expo creo que podemos sacar git y github a
#  un modulo independiente, crear un modulo llamado git/github"
#  (ver docs/humano*.md de esta ronda). expo.sh sigue instalando
#  git como dependencia propia (lo necesita para sus propios
#  flujos EAS) — este módulo es la pantalla de propósito general
#  para CUALQUIER proyecto (status/commit/push/pull/clonar +
#  acciones básicas de GitHub CLI), no solo Expo.
#
#  USO DESDE APP (KairosApp):
#    bash git.sh --silent
#    bash git.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ git (si falta)
#    ✅ gh — GitHub CLI (si falta)
#
#  NO HACE EN MODO SILENCIOSO:
#    ❌ Login interactivo (gh auth login) — queda para que el
#       usuario lo haga manualmente después (GitFragment.kt lo
#       abre en la terminal), no se puede automatizar sin
#       credenciales.
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Septiembre 2026
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

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"git","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. git y gh son paquetes apt
# completos instalados vía install_single_pkg() — mismo criterio que
# gh.sh/golang.sh/clang.sh: files:[] deliberado, no un gap sin investigar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "git", supports_describe_files: true, variant: null,
    package_name: "kairos-module-git",
    version_registry_key: "git.version",
    files: [], file_globs: [],
    dependencies: [
      {id: "pkg:git", check_cmd: "command -v git", install_hint: "pkg install -y git"},
      {id: "pkg:gh", check_cmd: "command -v gh", install_hint: "pkg install -y gh"}
    ],
    verify_cmd: "command -v git >/dev/null 2>&1 && git --version >/dev/null 2>&1",
    patch_cmd: "",
    not_covered: ["git y gh son enteramente paquetes apt — ya gestionados correctamente por pkg. No vale la pena empaquetarlos como .deb propio de Kairos — reinstalar via pkg es mas simple y correcto que un snapshot de archivos", "No automatiza el login de GitHub (gh auth login) — queda manual, GitFragment.kt lo abre en la terminal"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_git_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# install_single_pkg() ya es idempotente (command -v primero, no reinstala si ya
# está) y ya deja registry_install() con la clave "$_id.installed=true" — acá se
# usa dos veces con IDs distintos ("git"/"gh") para que ambos binarios queden
# verificados por separado, sin pisar el registry del módulo "gh" independiente
# (modulos/gh.sh) que ya existe en el catálogo — misma clave, mismo criterio de
# instalación, sin conflicto real entre ambos módulos.
install_single_pkg "git" "git" git
install_single_pkg "gh" "gh" gh

notify_event "git" "install_done" ""
exit 0
