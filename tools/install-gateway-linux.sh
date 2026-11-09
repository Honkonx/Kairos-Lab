#!/usr/bin/env bash
# Instala Kairos Gateway en Linux de escritorio -- pedido explicito del usuario:
# "en linux mejor un script" (en vez de un instalador grafico tipo .deb/.rpm, mas simple y portable
# entre distros). Compila el binario desde native/gateway (Go puro, sin CGO) si no se pasa uno ya
# compilado, lo instala en ~/.local/bin (sin sudo, XDG estandar), y crea un servicio de usuario de
# systemd opcional para que arranque solo -- nunca se activa sin que la persona lo pida (mismo
# criterio que el instalador de Windows, ver .claude/rules/kairos-casi-ilimitado-permisos.md).
#
# Uso:
#   tools/install-gateway-linux.sh                 # compila con `go build` y instala
#   tools/install-gateway-linux.sh --binary ruta   # usa un binario ya compilado en vez de `go build`
#   tools/install-gateway-linux.sh --with-dashboard # ademas compila e instala kairos-dashboard (ver abajo)
#   tools/install-gateway-linux.sh --uninstall      # desinstala (binario + servicio systemd)
#
# Requiere `go` 1.24+ en el PATH si no se pasa --binary. No requiere root -- instala solo para el
# usuario actual.
#
# --with-dashboard (2026-10-31): ademas del binario headless, compila
# kairos-dashboard -- la version con ventana nativa (CGO + GTK3/WebKitGTK, mismo codigo que el
# portable de Windows), con el "mini adb" de Google embebido para la pantalla de ADB por USB.
# Confirmado real en esta ronda: Debian 13 (trixie) ya no trae el paquete `webkit2gtk-4.0`
# (renombrado a 4.1 por upstream, mismas APIs/ABI) -- la dependencia Go `webview_go` todavia pide
# el nombre pkg-config viejo, asi que hace falta un symlink de compatibilidad antes de compilar.
# Requiere: libgtk-3-dev, libwebkit2gtk-4.1-dev (o -4.0-dev en distros que todavia lo tengan),
# pkg-config, gcc -- se instalan con sudo si faltan y hay apt-get disponible.
set -euo pipefail

BIN_DIR="$HOME/.local/bin"
TARGET="$BIN_DIR/kairos-gateway"
DASHBOARD_TARGET="$BIN_DIR/kairos-dashboard"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
SERVICE_FILE="$SYSTEMD_USER_DIR/kairos-gateway.service"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WITH_DASHBOARD=0

uninstall() {
  systemctl --user disable --now kairos-gateway.service 2>/dev/null || true
  rm -f "$SERVICE_FILE" "$TARGET"
  systemctl --user daemon-reload 2>/dev/null || true
  echo "Kairos Gateway desinstalado ($TARGET, $SERVICE_FILE)"
  exit 0
}

BINARY_OVERRIDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --uninstall) uninstall ;;
    --binary) BINARY_OVERRIDE="$2"; shift 2 ;;
    --with-dashboard) WITH_DASHBOARD=1; shift ;;
    *) echo "Argumento desconocido: $1" >&2; exit 1 ;;
  esac
done

mkdir -p "$BIN_DIR"
VERSION="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)"

if [ -n "$BINARY_OVERRIDE" ]; then
  [ -f "$BINARY_OVERRIDE" ] || { echo "No existe: $BINARY_OVERRIDE" >&2; exit 1; }
  cp "$BINARY_OVERRIDE" "$TARGET"
else
  command -v go >/dev/null || { echo "Falta 'go' en el PATH (instalalo o usa --binary con un .exe ya compilado)" >&2; exit 1; }
  ( cd "$ROOT/native/gateway" && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=$VERSION" -o "$TARGET" ./cmd/kairos-gateway )
fi
chmod +x "$TARGET"
echo "Binario instalado en $TARGET"

if [ "$WITH_DASHBOARD" = "1" ]; then
  if [ "$(uname)" != "Linux" ]; then
    echo "--with-dashboard solo tiene sentido en Linux (uname=$(uname)) -- se omite." >&2
  else
    PC="pkg-config"
    command -v "$PC" >/dev/null || { echo "Falta pkg-config -- instalalo (apt-get install pkg-config) antes de usar --with-dashboard" >&2; exit 1; }

    # Symlink de compatibilidad 4.1 -> 4.0 (ver comentario de cabecera) -- solo si hace falta y
    # solo si hay sudo disponible (nunca se asume root silenciosamente).
    if ! "$PC" --exists webkit2gtk-4.0 2>/dev/null; then
      if "$PC" --exists webkit2gtk-4.1 2>/dev/null; then
        PCDIR="$(dirname "$("$PC" --variable=pcfiledir webkit2gtk-4.1)")"
        echo "webkit2gtk-4.0 no existe pero sí webkit2gtk-4.1 -- creando symlink de compatibilidad en $PCDIR (necesita sudo)."
        sudo ln -sf "$PCDIR/webkit2gtk-4.1.pc" "$PCDIR/webkit2gtk-4.0.pc"
        sudo ln -sf "$PCDIR/javascriptcoregtk-4.1.pc" "$PCDIR/javascriptcoregtk-4.0.pc"
      else
        echo "Falta libwebkit2gtk-4.1-dev (o -4.0-dev) -- instalalo: sudo apt-get install -y libwebkit2gtk-4.1-dev libgtk-3-dev pkg-config" >&2
        exit 1
      fi
    fi
    "$PC" --exists gtk+-3.0 2>/dev/null || { echo "Falta libgtk-3-dev -- instalalo: sudo apt-get install -y libgtk-3-dev" >&2; exit 1; }
    command -v gcc >/dev/null || command -v cc >/dev/null || { echo "Falta un compilador C (gcc) -- instalalo: sudo apt-get install -y gcc" >&2; exit 1; }

    # mini adb (Google platform-tools, Apache-2.0) embebido -- mismo criterio que
    # tools/build-gateway-windows.ps1: se descarga fresco, nunca se commitea el binario real.
    EMBED_DIR="$ROOT/native/gateway/cmd/kairos-dashboard/embed"
    ADB_TMP="$(mktemp -d)"
    trap 'rm -rf "$ADB_TMP"' EXIT
    echo "Descargando mini adb (Google platform-tools, Linux)..."
    curl -fsSL -o "$ADB_TMP/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
    ( cd "$ADB_TMP" && unzip -q platform-tools.zip )
    cp "$ADB_TMP/platform-tools/adb" "$EMBED_DIR/adb-linux"
    chmod +x "$EMBED_DIR/adb-linux"

    echo "Compilando kairos-dashboard (CGO + GTK3/WebKitGTK)..."
    ( cd "$ROOT/native/gateway" && CGO_ENABLED=1 go build -trimpath -ldflags "-s -w -X main.version=$VERSION" -o "$DASHBOARD_TARGET" ./cmd/kairos-dashboard )
    chmod +x "$DASHBOARD_TARGET"

    # Restaura el placeholder de texto en el repo -- nunca se commitea el binario real de adb
    # (mismo criterio que embed_adb_windows.go,).
    git -C "$ROOT" checkout -- "native/gateway/cmd/kairos-dashboard/embed/adb-linux" 2>/dev/null || true

    echo "kairos-dashboard instalado en $DASHBOARD_TARGET"
  fi
fi

case "$(uname)" in
  Linux)
    if command -v systemctl >/dev/null; then
      read -r -p "¿Crear servicio de usuario systemd para que arranque con la sesión? [s/N] " resp
      if [ "${resp,,}" = "s" ]; then
        mkdir -p "$SYSTEMD_USER_DIR"
        cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Kairos Gateway
After=network.target

[Service]
ExecStart=$TARGET --listen 127.0.0.1:8973
Restart=on-failure

[Install]
WantedBy=default.target
EOF
        systemctl --user daemon-reload
        systemctl --user enable --now kairos-gateway.service
        echo "Servicio activo: systemctl --user status kairos-gateway.service"
      fi
    else
      echo "systemctl no disponible en este sistema -- correr manualmente: $TARGET --listen 127.0.0.1:8973"
    fi
    ;;
  *)
    echo "No es Linux (uname=$(uname)) -- binario instalado, sin servicio de arranque automático."
    ;;
esac

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "Nota: $BIN_DIR no está en tu PATH -- agregalo en ~/.bashrc o ~/.profile: export PATH=\"\$PATH:$BIN_DIR\"" ;;
esac

echo "Listo. Abrir panel: $TARGET --version  /  correr: $TARGET"
