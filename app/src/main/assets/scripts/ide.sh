#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · ide.sh (silent mode)
#  IDE directo en Termux — Neovim + NvChad + Copilot + CodeCompanion.
#  Puerta de entrada: `nvim` desde la terminal de KairosApp.
#
#  FUENTE: core-termux (referencia/termux/core-termux-main) —
#  core/modules/editor.sh + core/tools/editor/{neovim,nvchad}/install.sh.
#  Se replica aquí adaptado al contrato de scripts de KairosApp
#  (modulos/*.sh → lib.sh, registry, checkpoints).
#
#  USO DESDE APP (KairosApp):
#    bash ide.sh --silent
#    bash ide.sh --silent --force
#
#  USO MANUAL (standalone):
#    bash ide.sh
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ Neovim + deps (nodejs-lts, python, perl, curl, wget,
#       lua-language-server, ripgrep, stylua, tree-sitter)
#    ✅ NvChad (framework de Neovim) desde nvchad-termux
#    ✅ GitHub Copilot + CodeCompanion (vienen en la config NvChad)
#    ✅ Registry actualizado (ide.*)
#
#  CLI RESULTANTE:
#    nvim        → abre el IDE (NvChad)
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.0.0 | Agosto 2026 (pedido 2026-08-13: "en core-termux tiene
#  un IDE que es directo en termux podemos ponerlo" — ver humano101)
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
{"id":"ide","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. `nvim` en sí NO se empaqueta
# (es un pkg de Termux — dpkg/apt ya lo gestiona, reinstalar el .deb de
# Kairos sin nvim disponible es justo lo que dependencies[] detecta y avisa).
# Lo real y propio de Kairos para repackaging es el árbol de config NvChad
# clonado + sincronizado ($HOME/.config/nvim/**) — variable en tamaño según
# qué plugins Lazy sincronizó, por eso vía file_globs en vez de files[] fijo.
if $DESCRIBE_FILES; then
  jq -n \
    --arg glob "$HOME/.config/nvim/**" \
    --arg dep1_check "command -v nvim >/dev/null 2>&1" \
    --arg dep1_hint "pkg install -y git neovim nodejs-lts python perl curl wget lua-language-server ripgrep stylua tree-sitter" \
    --arg verify "command -v nvim >/dev/null 2>&1 && [ -d \"$HOME/.config/nvim\" ]" \
    '{
      id: "ide",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-ide",
      version_registry_key: "",
      files: [],
      file_globs: [
        {pattern: $glob, required: true, note: "config NvChad clonada de nvchad-termux + plugins sincronizados via Lazy — árbol completo, tamaño variable"}
      ],
      dependencies: [
        {id: "neovim", check_cmd: $dep1_check, install_hint: $dep1_hint}
      ],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: [
        "nvim en sí no se empaqueta — es un paquete pkg/apt de Termux, gestionado por dependencies[] (avisa si falta, no lo instala automáticamente el .deb)",
        "Los plugins de Lazy que se descargan/compilan en la primera sincronización (\"Lazy! sync\") pueden tardar minutos — el .deb los incluye tal como quedaron en ESTE device al momento de empaquetar, no garantiza que sigan compatibles con una versión de Neovim distinta en el device destino"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_ide_checkpoint"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^ide_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "ide_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^ide_${1}=done" "$CHECKPOINT" 2>/dev/null; }


if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◍ IDE EN TERMUX · Neovim + NvChad      ║"
  echo "  ║  con GitHub Copilot y CodeCompanion      ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
if command -v nvim &>/dev/null && [[ -d "$HOME/.config/nvim" ]] && ! $FORCE; then
  log "IDE (nvim + NvChad) ya instalado"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar el IDE? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Neovim + dependencias ─────────────────────────────
step "PASO 1 — Neovim + dependencias"
if check_done "neovim" && command -v nvim &>/dev/null; then
  log "Neovim ya instalado [checkpoint]"
else
  info "Instalando: git neovim nodejs-lts python perl curl wget lua-language-server ripgrep stylua tree-sitter"
  pkg_update_with_fallback
  # Bug real (auditoría 2026-08-27, ver docs/humano266.md): antes solo se redirigía
  # stderr ("2>/dev/null") — el stdout completo de "pkg install" (progreso de
  # descarga/extracción de 10 paquetes) quedaba sin silenciar y ModuleController.kt
  # captura stdout+stderr combinados (redirectErrorStream=true) al log persistente
  # ~/kairos_logs/install_ide.log, confirmado real como la causa más probable del
  # log de 1.7MB reportado en una ronda anterior. "&>/dev/null" silencia ambos —
  # el resultado real se sigue verificando después con "command -v nvim".
  pkg install git neovim nodejs-lts python perl curl wget lua-language-server ripgrep stylua tree-sitter -y &>/dev/null \
    || error "No se pudieron instalar las dependencias de Neovim"
  command -v nvim &>/dev/null || error "nvim no disponible tras la instalación"
  log "Neovim $(nvim --version | head -1)"
  mark_done "neovim"
fi

# ── PASO 2 — NvChad (framework) ────────────────────────────────
step "PASO 2 — NvChad (framework de Neovim)"
NVCHAD_REPO="https://github.com/DevCoreXOfficial/nvchad-termux.git"
NVCHAD_DIR="$HOME/.local/share/kairos-nvchad"
if check_done "nvchad" && [[ -d "$HOME/.config/nvim" ]]; then
  log "NvChad ya instalado [checkpoint]"
else
  info "Descargando nvchad-termux..."
  rm -rf "$NVCHAD_DIR"
  git clone --depth 1 "$NVCHAD_REPO" "$NVCHAD_DIR" 2>/dev/null \
    || error "No se pudo clonar nvchad-termux (¿red?)"
  mkdir -p "$HOME/.config"
  cp -r "$NVCHAD_DIR/nvim" "$HOME/.config/nvim" 2>/dev/null \
    || error "No se pudo copiar la configuración NvChad"

  info "Sincronizando plugins (Lazy + nvim-treesitter)... esto toma unos minutos"
  # Mismo fix que el "pkg install" de arriba — Lazy imprime una línea de progreso por
  # cada plugin sincronizado (stdout), sin silenciarla también terminaba en el log
  # persistente del módulo. "&>/dev/null" en vez de "2>/dev/null".
  nvim --headless "+Lazy! sync" +qa &>/dev/null
  nvim --headless "+Lazy! clean nvim-treesitter" +qa &>/dev/null
  nvim --headless "+Lazy! install nvim-treesitter" +qa &>/dev/null

  log "NvChad configurado (Copilot + CodeCompanion incluidos)"
  mark_done "nvchad"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_DATE=$(date +%Y-%m-%d)
registry_write ide \
  "installed=true" \
  "editor=nvim" \
  "framework=NvChad" \
  "ai=Copilot,CodeCompanion" \
  "install_date=${_DATE}"

notify_event "ide" "install_done" ""
log "IDE listo — abrí la terminal del módulo y ejecutá: nvim"
rm -f "$CHECKPOINT"
exit 0