#!/usr/bin/env bash
# Compila el gateway de Kairos (Go) para Android arm64 y lo deja como librería nativa del APK:
#   app/src/main/jniLibs/arm64-v8a/libkairos_gateway.so
#
# Mismo método que libcloudflared.so (ver .github/workflows/build-app.yml): GOOS=android + CGO + clang
# del NDK, para usar getaddrinfo() de Bionic en vez del resolver puro de Go (que en Android no
# encuentra /etc/resolv.conf). Android extrae el .so al instalar con permiso de ejecución.
#
# Uso: tools/build-gateway.sh [--test]
#   --test   corre `go vet` y `go test` antes de compilar.
# Variables: ANDROID_HOME / ANDROID_SDK_ROOT (SDK), NDK_VERSION (por defecto 27.2.12479018), API (26).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for guess in "$LOCALAPPDATA/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -d "$guess" ] && SDK="$guess" && break
  done
fi
[ -n "$SDK" ] || { echo "No encuentro el SDK de Android (define ANDROID_HOME)"; exit 1; }

NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
API="${API:-26}"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64"; EXT=".cmd" ;;
  Darwin*)              HOST_TAG="darwin-x86_64";  EXT="" ;;
  *)                    HOST_TAG="linux-x86_64";   EXT="" ;;
esac
CLANG="$SDK/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android${API}-clang${EXT}"
[ -e "$CLANG" ] || { echo "NDK clang no encontrado: $CLANG"; exit 1; }

cd "$ROOT/native/gateway"
if [ "${1:-}" = "--test" ]; then
  go vet ./...
  go test -count=1 ./...
fi

VERSION="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libkairos_gateway.so"
mkdir -p "$(dirname "$OUT")"
GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$CLANG" \
  go build -trimpath -ldflags "-s -w -X main.version=$VERSION" -o "$OUT" ./cmd/kairos-gateway
chmod +x "$OUT" 2>/dev/null || true
ls -l "$OUT"
command -v file >/dev/null && file "$OUT" || true
