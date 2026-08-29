#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · docker.sh (silent mode)
#  Módulo Docker — NO instala Docker real. Explica por qué y
#  dirige al módulo udocker (alternativa real ya en Kairos).
#
#  INVESTIGACIÓN REAL (antes de escribir esto — mismo criterio de
#  honestidad que modulos/mimocode.sh y modulos/qemu.sh):
#    Un daemon Docker real (dockerd) necesita del kernel de Linux:
#    namespaces (aislamiento de procesos/red/mount) y cgroups (límites
#    de recursos) accedidos directo, más overlayfs para las capas de
#    imagen. El kernel de Android para apps SIN ROOT tiene esas
#    funciones deshabilitadas/inaccesibles por diseño de seguridad del
#    sandbox de la plataforma — no es una limitación de Termux, es una
#    barrera del sistema operativo. No hay forma de "activarlas" desde
#    userspace sin rootear el dispositivo, y root está fuera de scope
#    para KairosApp (pensada para uso sin root).
#    Con root SÍ es técnicamente posible (namespaces/cgroups reales
#    quedan accesibles) pero exige rootear el teléfono — algo que este
#    módulo NO va a asumir ni a empujar al usuario a hacer.
#
#  ALTERNATIVA REAL YA EN KAIROS: udocker (modulos/udocker.sh) — corre
#  imágenes de contenedores (Docker Hub y compatibles) en userspace vía
#  PRoot, SIN namespaces/cgroups reales (aislamiento más débil que
#  Docker real, pero funcional para correr la mayoría de imágenes) y
#  SIN root. Es lo que ya usa el módulo n8n internamente (variante
#  udocker) y ahora también existe como módulo de propósito general.
#
#  QUÉ HACE ESTE SCRIPT:
#    - NO intenta instalar/simular Docker de ninguna forma
#    - Imprime la explicación de arriba
#    - Detecta (best-effort, informativo) si el dispositivo parece
#      rooteado — solo para el mensaje, no cambia el comportamiento
#    - Registra docker.installed=false + docker.reason en el registry
#      (para que la UI no muestre "instalado" nunca)
#    - Sale con éxito (exit 0) — no es un error del script, es el
#      comportamiento correcto y esperado
#
#  USO DESDE APP (KairosApp):
#    bash docker.sh --silent
#
#  FLAGS:
#    --silent   Sin preguntas (igual imprime la explicación)
#    --describe Manifiesto declarativo
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

SILENT=false
DESCRIBE=false
DESCRIBE_FILES=false
for arg in "$@"; do
  case "$arg" in
    --silent)   SILENT=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    --force)    : ;;  # aceptado por consistencia con el resto de modulos/, sin efecto acá
  esac
done

# ── Manifiesto declarativo (--describe) ─────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"docker","supports_silent":true,"supports_force":false,"variants":[],"variant_required":false,"experimental":true,"note":"Docker real (dockerd) NO es posible sin root en Android/Termux — el kernel no expone namespaces/cgroups a apps sin privilegios. Este script no instala nada, explica el motivo y dirige a modulos/udocker.sh como alternativa real ya disponible en Kairos"}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Este módulo NUNCA instala nada
# real (registry_write docker "installed=false" siempre, ver más abajo) —
# files:[] deliberado, no hay nada que empaquetar.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "docker", supports_describe_files: true, variant: null,
    package_name: "kairos-module-docker",
    version_registry_key: "docker.version",
    files: [], file_globs: [], dependencies: [],
    verify_cmd: "false",
    patch_cmd: "",
    not_covered: ["Este módulo nunca instala Docker real (imposible sin root en Android/Termux, ver cabecera del script) — solo imprime una explicación y registra docker.installed=false. No hay nada que empaquetar; alternativa real: modulos/udocker.sh"]
  }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh" 2>/dev/null || {
  echo "Error: lib.sh no encontrado"
  exit 1
}

# ── Detección best-effort de root (solo informativo) ────────
_IS_ROOTED=false
if command -v su &>/dev/null && su -c "id" &>/dev/null; then
  _IS_ROOTED=true
fi

EXPLICACION="Docker real necesita namespaces + cgroups del kernel de Linux, accedidos directo por un daemon con privilegios (dockerd). Android bloquea eso a apps sin root por diseño del sandbox de la plataforma -- no es una limitacion de Termux, es del sistema operativo. Sin rootear el dispositivo no hay forma de sortearlo. Alternativa real ya en Kairos: el modulo udocker (bash udocker.sh) corre imagenes de contenedores en userspace via PRoot, sin namespaces/cgroups reales pero sin necesitar root -- es lo que ya usa n8n internamente en su variante udocker."

if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   kairos-app · Docker                        ║
  ║   No soportado sin root · v1.0.0             ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
fi

echo "[INFO] Docker real no es posible en Android sin root."
echo "[INFO] $EXPLICACION"
if $_IS_ROOTED; then
  echo "[INFO] Se detectó 'su' funcional en este dispositivo — aun así, este módulo"
  echo "[INFO] no instala Docker (fuera de scope de Kairos, pensada para uso sin root)."
  echo "[INFO] Con root, Docker real podría funcionar instalándolo manualmente fuera de Kairos."
else
  echo "[INFO] Este dispositivo no parece rooteado — udocker es la vía recomendada."
fi
echo "[INFO] Alternativa recomendada: bash udocker.sh --silent (módulo 'udocker' en Kairos)"

# ── Registry: nunca "installed=true" para este módulo ────────
registry_write docker "installed=false" "reason=no_namespaces_cgroups_unrooted_android" "alternative=udocker" "rooted_device=$_IS_ROOTED"

notify_event "docker" "explained" "not_supported_use_udocker"
log "Docker: explicación mostrada — usá el módulo udocker como alternativa real"
exit 0
