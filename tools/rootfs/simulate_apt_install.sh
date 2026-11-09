#!/usr/bin/env bash
# Simula (sin instalar nada) `apt install ./*.deb` de TODOS los .deb de un rootfs con el resolvedor real de apt.
# Detecta conflictos, dependencias sin satisfacer y paquetes excluyentes que el verificador de build_rootfs.py
# (--check) solo aproxima. Corre en WSL Debian / Linux con apt; no toca el sistema (estado en un directorio temporal).
#
# Uso: tools/rootfs/simulate_apt_install.sh <carpeta_con_debs>
# Salida: "SIMULACION OK (N paquetes)" o el error de apt (código distinto de 0).
set -uo pipefail

DEBS_DIR="${1:?Uso: simulate_apt_install.sh <carpeta_con_debs>}"
WORK="$(mktemp -d /tmp/aptsim.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/state/lists/partial" "$WORK/cache/archives/partial" "$WORK/etc"
: > "$WORK/status"
: > "$WORK/etc/sources.list"

# Los .deb de Termux declaran "Architecture: aarch64" (no "arm64"): se le dice a apt/dpkg que esa es la arquitectura.
COMMON=(
  -o "Dir::State=$WORK/state" -o "Dir::State::status=$WORK/status" -o "Dir::Cache=$WORK/cache"
  -o "Dir::Etc::sourcelist=$WORK/etc/sources.list" -o "Dir::Etc::sourceparts=$WORK/etc/none"
  -o "APT::Architecture=aarch64" -o "APT::Architectures=aarch64" -o "Debug::NoLocking=1"
  -o "APT::Get::Assume-Yes=true" -o "APT::Install-Recommends=false" -o "Dir::Etc::trustedparts=$WORK/etc/none"
)

COUNT=$(ls "$DEBS_DIR"/*.deb | wc -l)
echo "Simulando la instalación conjunta de $COUNT paquetes de $DEBS_DIR ..."
OUT="$WORK/apt.out"
apt-get "${COMMON[@]}" -s install "$DEBS_DIR"/*.deb > "$OUT" 2>&1
RC=$?
if [ $RC -eq 0 ]; then
  echo "SIMULACION OK ($(grep -c '^Inst ' "$OUT") paquetes a instalar)"
else
  echo "SIMULACION FALLO (rc=$RC). Últimas líneas de apt:"
  tail -40 "$OUT"
fi
exit $RC
