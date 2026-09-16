#!/usr/bin/env bash
set -euo pipefail

# tools/serve-update-server.sh — infraestructura de prueba local del mecanismo de
# auto-actualización (AppUpdater.kt/AppInstaller.kt, puerto de techjarves/Mobile-Harness, ver
# docs/referencias/herramientas/AUDITORIA_MOBILE_HARNESS_2026-09-09.md). Compila un APK debug
# con un versionCode/versionName de PRUEBA arbitrarios, genera el manifiesto
# "kairos-update.json" con su SHA-256 real, y lo sirve con `python3 -m http.server` expuesto
# por un túnel HTTPS temporal — para probar el flujo completo (check → download → verify-hash →
# verify-firma → install) SIN tocar la Release real de GitHub (Honkonx/kairos-lab).
#
# Herramienta de DESARROLLO — corre en la PC, no en el dispositivo. No confundir con
# app/src/main/jniLibs/*/libcloudflared.so (el cloudflared nativo embebido en el propio APK,
# ver TunnelManager.kt / docs/ssh/TUNEL_CLOUDFLARED_NATIVO.md): ese binario es un .so ARM64
# pensado para correr DENTRO de Android — no es ejecutable en el host x86_64/arm64 de
# escritorio donde corre este script. Se usa el `cloudflared` del SISTEMA del desarrollador en
# su lugar (decisión de conveniencia, no fue posible reusar el binario embebido tal cual porque
# nunca fue pensado para correr fuera de Android) — si no está instalado, el script sigue
# funcionando en modo solo-local (sin túnel) y avisa cómo instalarlo.
#
# Uso:
#   tools/serve-update-server.sh <versionCode> <versionName> [puerto]
#   tools/serve-update-server.sh 128 0.1.1-test 8085
#
# Requiere: bash, python3, ./gradlew (build local — no necesita empaquetar el rootfs
# embebido para este flujo de prueba).

if [ "${1:-}" = "" ] || [ "${2:-}" = "" ]; then
    echo "Uso: $0 <versionCode> <versionName> [puerto]" >&2
    echo "Ej.:  $0 128 0.1.1-test 8085" >&2
    exit 1
fi

VERSION_CODE="$1"
VERSION_NAME="$2"
PORT="${3:-8085}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$REPO_ROOT/tools/.update-server"
mkdir -p "$OUT_DIR"

echo "[1/4] Compilando APK debug de prueba (versionCode=$VERSION_CODE versionName=$VERSION_NAME)..."
cd "$REPO_ROOT"
./gradlew :app:assembleDebug -PappVersionCode="$VERSION_CODE" -PappVersionName="$VERSION_NAME"

# Preferí el .apk arm64 real (nombre real: "Kairos_<versionName>_..._armv8.apk", ver
# friendlyAbiName en app/build.gradle) — cae a cualquier .apk sin firmar del build debug si por
# algún motivo el split ABI no generó ese nombre exacto (ej. splits deshabilitados a mano).
APK_SRC=""
for candidate in app/build/outputs/apk/debug/Kairos_*armv8.apk app/build/outputs/apk/debug/*.apk; do
    if [ -f "$candidate" ]; then
        APK_SRC="$candidate"
        break
    fi
done
if [ -z "$APK_SRC" ]; then
    echo "ERROR: no se encontró ningún .apk en app/build/outputs/apk/debug/" >&2
    exit 1
fi

APK_DEST="$OUT_DIR/kairos-update.apk"
cp "$APK_SRC" "$APK_DEST"
echo "APK de prueba: $APK_SRC -> $APK_DEST"

echo "[2/4] Calculando SHA-256..."
if command -v sha256sum >/dev/null 2>&1; then
    SHA256="$(sha256sum "$APK_DEST" | awk '{print $1}')"
else
    SHA256="$(shasum -a 256 "$APK_DEST" | awk '{print $1}')"
fi
if command -v stat >/dev/null 2>&1 && stat -c%s "$APK_DEST" >/dev/null 2>&1; then
    SIZE_BYTES="$(stat -c%s "$APK_DEST")"
else
    SIZE_BYTES="$(stat -f%z "$APK_DEST")"
fi

echo "[3/4] Generando manifiesto kairos-update.json..."
cat > "$OUT_DIR/kairos-update.json" <<JSON
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "notes": "Build de prueba local (tools/serve-update-server.sh) — NO es una Release real de Kairos.",
  "artifacts": {
    "default": {
      "url": "http://127.0.0.1:$PORT/kairos-update.apk",
      "sha256": "$SHA256",
      "sizeBytes": $SIZE_BYTES
    }
  }
}
JSON

echo "[4/4] Sirviendo $OUT_DIR en :$PORT ..."
cd "$OUT_DIR"
python3 -m http.server "$PORT" >"$OUT_DIR/http-server.log" 2>&1 &
HTTP_PID=$!

TUNNEL_PID=""
cleanup() {
    kill "$HTTP_PID" >/dev/null 2>&1 || true
    if [ -n "$TUNNEL_PID" ]; then
        kill "$TUNNEL_PID" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

CLOUDFLARED_BIN="$(command -v cloudflared || true)"
if [ -z "$CLOUDFLARED_BIN" ]; then
    echo ""
    echo "cloudflared no está instalado en esta PC (el .so nativo embebido en el APK es ARM64"
    echo "Android, no corre en el host de desarrollo — ver comentario de cabecera de este"
    echo "script). Instalalo (https://github.com/cloudflare/cloudflared/releases) para exponer"
    echo "un túnel HTTPS automático, o abrí uno a mano (ngrok, otra herramienta) apuntando a"
    echo "http://127.0.0.1:$PORT/ y editá manualmente la URL 'url' dentro de"
    echo "$OUT_DIR/kairos-update.json con la URL pública real antes de usarla como override de"
    echo "debug (fila '[DEBUG] URL del manifiesto de prueba' en Ajustes -> Mantenimiento, solo"
    echo "visible en builds debug)."
    echo ""
    echo "Servidor local activo en http://127.0.0.1:$PORT/ — Ctrl+C para detener."
    wait "$HTTP_PID"
    exit 0
fi

"$CLOUDFLARED_BIN" tunnel --url "http://127.0.0.1:$PORT" --no-autoupdate >"$OUT_DIR/cloudflared.log" 2>&1 &
TUNNEL_PID=$!

echo "Esperando la URL pública del túnel..."
PUBLIC_URL=""
for _ in $(seq 1 30); do
    PUBLIC_URL="$(grep -oE 'https://[a-zA-Z0-9.-]+trycloudflare\.com' "$OUT_DIR/cloudflared.log" 2>/dev/null | head -n1 || true)"
    if [ -n "$PUBLIC_URL" ]; then
        break
    fi
    sleep 1
done

if [ -z "$PUBLIC_URL" ]; then
    echo "No se pudo detectar la URL pública del túnel — revisá $OUT_DIR/cloudflared.log a mano." >&2
else
    echo ""
    echo "Túnel activo: $PUBLIC_URL"
    echo "Reescribiendo la URL del artifact en el manifiesto con la URL pública real..."
    python3 - "$OUT_DIR/kairos-update.json" "$PUBLIC_URL/kairos-update.apk" <<'PY'
import json
import sys

path, url = sys.argv[1], sys.argv[2]
with open(path) as f:
    data = json.load(f)
data["artifacts"]["default"]["url"] = url
with open(path, "w") as f:
    json.dump(data, f, indent=2)
PY
    echo ""
    echo "Manifiesto de prueba: $PUBLIC_URL/kairos-update.json"
    echo "En un build DEBUG instalado en el dispositivo, pegá ese link en Ajustes ->"
    echo "Mantenimiento -> '[DEBUG] URL del manifiesto de prueba' y tocá 'Buscar actualización'"
    echo "para probar el flujo completo de punta a punta."
fi

echo ""
echo "Presioná Ctrl+C para detener el servidor y el túnel."
wait "$HTTP_PID"
