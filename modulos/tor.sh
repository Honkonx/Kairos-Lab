#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · tor.sh (silent mode)
#  Tor — paquete nativo de Termux (pkg install tor). Red de anonimato/
#  enrutamiento cebolla (SOCKS proxy local) — complementa al módulo
#  `ciberseguridad` (que ya cubre pentesting, no anonimato de red), ver
#  .claude/rules/kairos-vnc-scope.md para el criterio de qué SÍ/NO se mezcla
#  con Ciberseguridad (VNC ahí sigue fuera de alcance; Tor es un módulo propio
#  aparte, no una opción dentro de ciberseguridad.sh).
#
#  Propuesto y verificado en la ronda 2026-09-15 (MEJORAS_PENDIENTES.md, sección
#  "Módulos nuevos — ronda 2026-09-15") contra el índice real de termux-packages
#  (github.com/termux/termux-packages/packages/tor/build.sh, paquete normal —
#  NO root-packages, no necesita root; TERMUX_PKG_VERSION="0.4.9.12" al momento
#  de verificar). El build.sh usa TERMUX_PKG_SERVICE_SCRIPT (framework de
#  termux-services, "sv-enable tor") — Kairos NO usa ese framework, este script
#  arranca/detiene tor con el mismo patrón tmux que el resto de módulos con
#  switch (ver PASO 3).
#
#  PUERTO — comportamiento real del binario, no asumido por Linux de escritorio:
#  el torrc.sample que trae el propio Tor upstream (post-instalación,
#  termux_step_post_make_install lo copia a $PREFIX/etc/tor/torrc) documenta
#  textualmente que Tor abre un SOCKS proxy en el puerto 9050 por default
#  incluso sin una línea SocksPort explícita en el config — comportamiento del
#  binario tor en sí (independiente de Android/Termux), sin parches en el
#  build.sh que lo alteren. Para no depender de un default implícito, este
#  script PARCHEA torrc agregando "SocksPort 9050" de forma explícita si no
#  hay ya una línea SocksPort activa (PASO 2) — mismo espíritu que el parcheo
#  de puerto de gitea.sh.
#
#  USO DESDE APP (KairosApp):
#    bash tor.sh --silent
#    bash tor.sh --silent --force
#
#  QUÉ INSTALA:
#    ✅ tor (binario CLI + daemon)
#    ✅ $PREFIX/etc/tor/torrc parcheado (SocksPort 9050 explícito)
#    ✅ ~/scripts/tor/start.sh|stop.sh — mismo patrón (tmux + puerto fijo) que
#       syncthing.sh/gitea.sh, para que la app prenda/apague el servicio con
#       el switch estándar de módulos hasSwitch (ver GenericModuleFragment.kt,
#       genérico — este módulo no necesita Fragment propio).
#    ✅ Registry actualizado (tor.*)
#
#  Sin webviewUrl (el SOCKS proxy no es HTTP navegable) — el usuario apunta
#  cualquier app/CLI compatible con SOCKS5 a 127.0.0.1:9050 una vez el switch
#  está en ON.
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

if $DESCRIBE; then
  cat << 'JSON'
{"id":"tor","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. El binario tor en sí es un
# paquete apt normal (ya gestionado por pkg) — los archivos propios de Kairos
# son el torrc parcheado (SocksPort, PASO 2) y los wrappers start.sh/stop.sh
# (mismo criterio que syncthing.sh/gitea.sh, que ya los listan).
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/etc/tor/torrc" \
    --arg p2 "$HOME/scripts/tor/start.sh" \
    --arg p3 "$HOME/scripts/tor/stop.sh" \
    --arg patch "chmod +x \"$HOME/scripts/tor/start.sh\" \"$HOME/scripts/tor/stop.sh\" 2>/dev/null || true" \
    '{
      id: "tor", supports_describe_files: true, variant: null,
      package_name: "kairos-module-tor",
      version_registry_key: "tor.version",
      files: [
        {path: $p1, mode: "0644"},
        {path: $p2, mode: "0755"},
        {path: $p3, mode: "0755"}
      ],
      file_globs: [],
      dependencies: [{id: "pkg:tor", check_cmd: "command -v tor >/dev/null 2>&1", install_hint: "pkg install -y tor"}],
      verify_cmd: "command -v tor >/dev/null 2>&1 && tor --version >/dev/null 2>&1",
      patch_cmd: $patch,
      not_covered: ["Estado de circuitos/red de Tor en tiempo de ejecución no es estado empaquetable — solo el torrc parcheado (config) es propio de Kairos"]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_tor_checkpoint"
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

step "PASO 1 — Instalando tor"
install_single_pkg "tor" "tor" tor

# ── PASO 2 — Asegurar SocksPort 9050 explícito en torrc ─────────────────
# Ver bloque de comentarios arriba: el binario abre 9050 por default aunque
# no esté en el config, pero se fija explícito para no depender de un
# comportamiento implícito no documentado en el config instalado.
step "PASO 2 — Configurando SocksPort"
TORRC="$TERMUX_PREFIX/etc/tor/torrc"
if check_done "torrc_patch"; then
  log "torrc ya parcheado [checkpoint]"
elif [ -f "$TORRC" ] && grep -qE "^SocksPort" "$TORRC" 2>/dev/null; then
  log "torrc ya tiene una línea SocksPort propia — no se toca"
  mark_done "torrc_patch"
elif [ -f "$TORRC" ]; then
  {
    echo ""
    echo "# Agregado por Kairos (modulos/tor.sh) — explícito en vez de depender"
    echo "# del default implícito del binario (ver comentarios del script)."
    echo "SocksPort 9050"
  } >> "$TORRC"
  log "torrc parcheado: SocksPort 9050"
  mark_done "torrc_patch"
else
  error "No se encontró $TORRC tras instalar el paquete tor"
fi

# ── PASO 3 — Scripts de control del servicio (start/stop) ──────────────
# Mismo patrón (tmux + script start/stop + puerto fijo) que syncthing.sh/
# gitea.sh — lo que la app arranca/detiene con el switch estándar de módulos
# hasSwitch (ver GenericModuleFragment.kt).
step "PASO 3 — Scripts de control del servicio"
if check_done "serve_scripts"; then
  log "Scripts de servicio ya instalados [checkpoint]"
else
  mkdir -p "$HOME/scripts/tor"

  cat > "$HOME/scripts/tor/start.sh" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
SESSION="tor-server"
LOG="\$HOME/kairos_logs/tor_serve.log"
mkdir -p "\$HOME/kairos_logs"

if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] tor ya corriendo en tmux sesión: \$SESSION"
  exit 0
fi

tmux new-session -d -s "\$SESSION" \\
  "'$TERMUX_PREFIX/bin/tor' -f '$TERMUX_PREFIX/etc/tor/torrc' > '\$LOG' 2>&1"

sleep 3
if tmux has-session -t "\$SESSION" 2>/dev/null; then
  echo "[OK] tor iniciado — proxy SOCKS en 127.0.0.1:9050"
else
  echo "[ERROR] No se pudo iniciar tor — log en ~/kairos_logs/tor_serve.log: \$(tail -c 200 "\$LOG" 2>/dev/null)"
  exit 1
fi
SCRIPT
  chmod +x "$HOME/scripts/tor/start.sh"

  cat > "$HOME/scripts/tor/stop.sh" << 'SCRIPT'
#!/data/data/com.termux/files/usr/bin/bash
SESSION="tor-server"
if tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  echo "[OK] tor detenido"
else
  echo "[OK] tor no estaba corriendo"
fi
SCRIPT
  chmod +x "$HOME/scripts/tor/stop.sh"

  log "Scripts de control del servicio creados"
  mark_done "serve_scripts"
fi

registry_install "tor" "$(tor --version 2>&1 | head -1)" "socks_port=9050"

notify_event "tor" "install_done" ""
log "tor instalado — activá el switch del módulo para levantar el proxy SOCKS en 127.0.0.1:9050"
rm -f "$CHECKPOINT"
exit 0
