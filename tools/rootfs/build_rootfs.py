#!/usr/bin/env python3
"""
build_rootfs.py — arma el rootfs embebido de Kairos: descarga los paquetes
.deb REALES de Termux (repo termux-main, aarch64) que necesita modulos/kairos.sh,
sin extraerlos.

Pasos:
  1. Descarga el índice Packages.gz del repo termux-main (aarch64).
  2. Resuelve la clausura transitiva de dependencias (Depends:) a partir de
     tools/rootfs/package_list.txt.
  3. Descarga cada .deb (con verificación de SHA256 contra el índice) y lo
     copia tal cual (sin extraer) al staging dir.
  4. Escribe manifest.json (paquete -> versión) para trazabilidad.

Los .deb quedan SIN extraer a propósito — el dispositivo los instala con
`apt install ./*.deb` real (ver RootfsInstaller.kt), no con un tar suelto.
Si se extrajeran acá y solo se copiaran los archivos, dpkg/apt en el
dispositivo nunca sabría que esos paquetes están "instalados" (no quedan en
su base de datos) — rompería cualquier chequeo futuro de "hay actualización
disponible" (pkg list --upgradable), que es justamente parte del pedido
original de esta función.

No instala nada, no toca el sistema — solo arma archivos en staging_dir. El
tar.xz final y la subida como Release asset las hace el propio workflow
(build-rootfs.yml), no este script. Corre bien tanto en Ubuntu (CI) como en
cualquier Python 3 con acceso a internet — ya no depende de dpkg-deb.
"""
import gzip
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.request

REPO_BASE = "https://packages.termux.dev/apt/termux-main"
PACKAGES_URL = f"{REPO_BASE}/dists/stable/main/binary-aarch64/Packages.gz"

HERE = os.path.dirname(os.path.abspath(__file__))
PACKAGE_LIST_FILE = os.path.join(HERE, "package_list.txt")


def log(msg):
    print(f"[build_rootfs] {msg}", flush=True)


def read_package_list(path):
    names = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            names.append(line)
    return names


def fetch_packages_index():
    log(f"Descargando índice: {PACKAGES_URL}")
    with urllib.request.urlopen(PACKAGES_URL, timeout=30) as resp:
        raw = gzip.decompress(resp.read()).decode("utf-8", "replace")
    return parse_packages_index(raw)


def parse_packages_index(raw):
    """Parsea el formato de control de apt (bloques separados por línea en blanco)."""
    index = {}
    block_lines = []

    def flush():
        if not block_lines:
            return
        fields = {}
        current_key = None
        for line in block_lines:
            if line.startswith(" ") and current_key:
                fields[current_key] += "\n" + line
                continue
            if ":" not in line:
                continue
            key, _, val = line.partition(":")
            current_key = key.strip()
            fields[current_key] = val.strip()
        name = fields.get("Package")
        if name:
            depends_raw = fields.get("Depends", "")
            deps = []
            if depends_raw:
                for alt in depends_raw.split(","):
                    alt = alt.strip()
                    if not alt:
                        continue
                    # "pkgname (>= 1.0) | otherpkg" -> guarda TODAS las alternativas (no solo
                    # la primera) — resolve_closure() elige cuál usar según lo ya pedido en
                    # package_list.txt, para no arrastrar una alternativa que choca
                    # (Conflicts:) con la que el usuario pidió explícito (bug real confirmado
                    # 2026-08-27: nodejs-lts en package_list.txt + algún paquete con
                    # "Depends: nodejs | nodejs-lts" tomando ciegamente "nodejs" como primera
                    # alternativa -> ambos terminaban en el rootfs, "apt install" fallaba con
                    # "Conflicts: nodejs-lts" código 100, ver docs/humano259.md).
                    alt_names = []
                    for choice in alt.split("|"):
                        dep_name = re.split(r"[\s(]", choice.strip(), 1)[0].strip()
                        if dep_name:
                            alt_names.append(dep_name)
                    if alt_names:
                        deps.append(alt_names)
            index[name] = {
                "version": fields.get("Version", ""),
                "filename": fields.get("Filename", ""),
                "sha256": fields.get("SHA256", ""),
                "depends": deps,
            }

    for line in raw.split("\n"):
        if line == "":
            flush()
            block_lines = []
        else:
            block_lines.append(line)
    flush()
    return index


def resolve_closure(roots, index):
    """BFS de dependencias transitivas. Paquete raíz faltante en el índice = error
    duro (typo real); dependencia transitiva faltante = warning (puede ser un
    virtual package o algo ya provisto por el sistema base) — no bloquea el build.

    Cada entrada de "depends" es una LISTA de alternativas ("pkgA | pkgB" en el
    Packages real) — se elige la alternativa ya presente en `roots` (lo que
    package_list.txt pidió explícito) si hay alguna, para no arrastrar una
    alternativa que choca (Conflicts:) con la pedida a propósito; si ninguna
    alternativa es un root, se usa la primera como antes (comportamiento
    default sin cambios para el caso común sin ambigüedad real)."""
    roots_set = set(roots)
    resolved = set()
    queue = list(roots)
    missing_roots = []

    while queue:
        name = queue.pop(0)
        if name in resolved:
            continue
        info = index.get(name)
        if info is None:
            if name in roots:
                missing_roots.append(name)
            continue
        resolved.add(name)
        for alt_names in info["depends"]:
            chosen = next((a for a in alt_names if a in roots_set), alt_names[0])
            if chosen not in resolved:
                queue.append(chosen)

    if missing_roots:
        raise SystemExit(
            f"ERROR: paquetes de package_list.txt no encontrados en el índice: {missing_roots}"
        )
    return resolved


def download_deb(name, info, cache_dir):
    filename = info["filename"]
    if not filename:
        log(f"  ! {name}: sin Filename en el índice, se salta")
        return None
    url = f"{REPO_BASE}/{filename}"
    dest = os.path.join(cache_dir, os.path.basename(filename))
    if os.path.exists(dest) and _sha256_of(dest) == info["sha256"]:
        return dest

    log(f"  descargando {name} ({info['version']})...")
    urllib.request.urlretrieve(url, dest)

    if info["sha256"]:
        actual = _sha256_of(dest)
        if actual != info["sha256"]:
            os.remove(dest)
            raise SystemExit(f"ERROR: checksum inválido para {name}: esperado {info['sha256']}, real {actual}")
    return dest


def _sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    if len(sys.argv) != 3:
        print("Uso: build_rootfs.py <staging_dir> <cache_dir>")
        sys.exit(1)
    staging_dir, cache_dir = sys.argv[1], sys.argv[2]
    os.makedirs(staging_dir, exist_ok=True)
    os.makedirs(cache_dir, exist_ok=True)

    roots = read_package_list(PACKAGE_LIST_FILE)
    log(f"{len(roots)} paquetes raíz en package_list.txt")

    index = fetch_packages_index()
    log(f"Índice cargado: {len(index)} paquetes disponibles en termux-main")

    resolved = resolve_closure(roots, index)
    log(f"Clausura transitiva resuelta: {len(resolved)} paquetes totales (incluye dependencias)")

    # IMPORTANTE: NO se extraen los .deb acá (ya no se usa dpkg-deb -x). Los .deb
    # quedan intactos, tal cual, dentro del staging_dir — el dispositivo los instala
    # con "apt install ./*.deb" real en runtime (ver RootfsInstaller.kt). Motivo:
    # extraer los archivos a mano deja el sistema de paquetes de Termux (dpkg/apt)
    # sin ningún registro de que esos paquetes están "instalados" — "pkg list
    # --upgradable" nunca los vería, y la comprobación de actualizaciones pedida
    # por el usuario no funcionaría. Instalando con apt/dpkg real, quedan
    # registrados exactamente como si el usuario hubiera hecho pkg install a mano.
    manifest = {}
    for name in sorted(resolved):
        info = index[name]
        deb_path = download_deb(name, info, cache_dir)
        if deb_path is None:
            continue
        dest = os.path.join(staging_dir, os.path.basename(info["filename"]))
        if not os.path.exists(dest):
            shutil.copy2(deb_path, dest)
        manifest[name] = info["version"]

    manifest_path = os.path.join(staging_dir, "..", "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
    log(f"manifest.json escrito ({len(manifest)} paquetes) en {manifest_path}")
    log(f"{len(manifest)} archivos .deb listos en {staging_dir} (sin extraer)")
    log("Listo.")


if __name__ == "__main__":
    main()
