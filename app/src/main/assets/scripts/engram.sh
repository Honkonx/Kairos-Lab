#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · engram.sh (silent mode)
#  Instala Engram — sistema de memoria persistente para agentes de IA
#  (Go, compilado desde fuente) en Termux ARM64
#
#  USO DESDE APP (KairosApp):
#    bash engram.sh --silent
#
#  USO MANUAL (standalone):
#    bash install_engram.sh
#
#  FLAGS:
#    --silent      Sin preguntas, instala todo directo
#    --force       Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ golang, git, sqlite (si faltan)
#    ✅ Engram — clonado de Gentleman-Programming/engram + compilado con go build
#    ✅ Registry actualizado
#
#  QUÉ ES:
#    Engram le da a agentes de IA (Claude Code, OpenCode, etc. corriendo en
#    esta misma app) memoria persistente entre sesiones — guarda contexto en
#    SQLite local, sin depender de ningún servicio externo. Adaptado de
#    core-termux (DevCoreXOfficial, MIT) — ver
#    docs/REFERENCIA_ENGRAM.md y docs/humano/humano14.md.
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  ORIGEN: github.com/Gentleman-Programming/engram (MIT), empaquetado por
#          core-termux (github.com/DevCoreXOfficial/core-termux, MIT)
#  VERSIÓN: 1.0.0 | Julio 2026
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
{"id":"engram","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Un solo binario Go compilado
# desde fuente (go build) — caso simple, sin parche, sin runtime extra
# (Go compila estático por default).
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/bin/engram" \
    --arg verify "test -x \"$TERMUX_PREFIX/bin/engram\" && \"$TERMUX_PREFIX/bin/engram\" --version >/dev/null 2>&1" \
    '{
      id: "engram", supports_describe_files: true, variant: null,
      package_name: "kairos-module-engram",
      version_registry_key: "engram.version",
      files: [{path: $p1, required: true, note: "Binario Go compilado desde fuente (go build -C cmd/engram)"}],
      file_globs: [], dependencies: [],
      verify_cmd: $verify,
      patch_cmd: "",
      not_covered: ["No se empaqueta el código fuente clonado ($HOME/.engram-src) ni datos de memoria persistente del usuario — solo el binario final compilado"]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_engram_checkpoint"
ENGRAM_REPO="https://github.com/Gentleman-Programming/engram"
ENGRAM_SRC_DIR="$HOME/.engram-src"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^engram_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "engram_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^engram_${1}=done" "$CHECKPOINT" 2>/dev/null; }


if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◆ ENGRAM — Instalador                  ║"
  echo "  ║  Memoria persistente para agentes IA     ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
if command -v engram &>/dev/null && ! $FORCE; then
  log "Engram ya instalado"
  exit 0
fi
$FORCE && { rm -f "$CHECKPOINT"; rm -rf "$ENGRAM_SRC_DIR"; rm -f "$TERMUX_PREFIX/bin/engram"; }

if ! $SILENT; then
  echo -n "  ¿Instalar Engram? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

export GOPATH="$HOME/.local/go"
export GOCACHE="$HOME/.cache/go"
export GOMODCACHE="$GOPATH/pkg/mod"

# ── PASO 1 — Dependencias (golang, git, sqlite) ──────────────
step "PASO 1 — Instalando dependencias"
if check_done "deps"; then
  log "Dependencias ya instaladas [checkpoint]"
else
  info "Instalando golang, git, sqlite..."
  pkg_update_with_fallback
  pkg install -y golang git sqlite 2>&1 | tail -3
  command -v go &>/dev/null || error "golang no disponible tras instalación"
  command -v git &>/dev/null || error "git no disponible tras instalación"
  log "Dependencias OK: $(go version 2>/dev/null)"
  mark_done "deps"
fi

# ── PASO 2 — Clonar repositorio ──────────────────────────────
step "PASO 2 — Clonando engram"
if check_done "clone"; then
  log "Repositorio ya clonado [checkpoint]"
else
  rm -rf "$ENGRAM_SRC_DIR"
  ENGRAM_CLONE_OUTPUT=$(git clone --depth 1 "$ENGRAM_REPO" "$ENGRAM_SRC_DIR" 2>&1)
  echo "$ENGRAM_CLONE_OUTPUT" | tail -3
  if [ ! -d "$ENGRAM_SRC_DIR" ]; then
    warn "Salida completa de 'git clone':"
    echo "$ENGRAM_CLONE_OUTPUT"
    error "No se pudo clonar $ENGRAM_REPO — repo inaccesible o sin red"
  fi
  [ -d "$ENGRAM_SRC_DIR/cmd/engram" ] || error "Clonado pero no se encontró cmd/engram — la estructura real del repo $ENGRAM_REPO cambió respecto a lo esperado por este script"
  log "Repositorio clonado"
  mark_done "clone"
fi

# ── PASO 3 — Compilar ─────────────────────────────────────────
step "PASO 3 — Compilando binario"
if check_done "build"; then
  log "Binario ya compilado [checkpoint]"
else
  info "go build (puede tardar 1-2 min la primera vez)..."
  # Bug real reportado (2026-08-04, ver docs/humano/humano57.md — "engram... da error"): igual
  # que expo.sh, esto pipeaba directo a `tail -10` y perdía el motivo real de un `go build`
  # fallido (dependencia de módulo no resuelta, versión de Go sin el flag -C, error de
  # compilación real) en el log de instalación. Ahora se guarda la salida completa y solo se
  # descarta si el binario se generó de verdad.
  ENGRAM_BUILD_OUTPUT=$(go build -C "$ENGRAM_SRC_DIR/cmd/engram" -o "$TERMUX_PREFIX/bin/engram" 2>&1)
  echo "$ENGRAM_BUILD_OUTPUT" | tail -10
  if [ ! -x "$TERMUX_PREFIX/bin/engram" ]; then
    warn "Salida completa de 'go build':"
    echo "$ENGRAM_BUILD_OUTPUT"
    error "go build no generó el binario"
  fi
  # Verificación real de ejecución, no solo que el archivo exista (mismo
  # patrón que codex.sh/ssh.sh — un binario compilado puede no correr).
  engram --version &>/dev/null || engram --help &>/dev/null || warn "engram se compiló pero no se pudo verificar que ejecute (puede no tener --version/--help)"
  log "Binario compilado en $TERMUX_PREFIX/bin/engram"
  mark_done "build"
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
registry_write engram "installed=true" "install_date=$(date +%Y-%m-%d)"

notify_event "engram" "install_done" ""
log "Engram instalado correctamente"
rm -f "$CHECKPOINT"
