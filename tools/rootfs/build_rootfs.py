#!/usr/bin/env python3
"""
build_rootfs.py — arma el rootfs embebido de Kairos: descarga los paquetes
.deb REALES de Termux (aarch64) que necesita modulos/kairos.sh y los módulos,
sin extraerlos.

Repos (desde 2026-10-09, regla .claude/rules/kairos-rootfs-maximize.md — "se agrega todo lo que se pueda"):
  main   https://packages.termux.dev/apt/termux-main   (dists/stable/main)
  glibc  https://packages.termux.dev/apt/termux-glibc  (dists/glibc/stable) — glibc, glibc-runner, patchelf-glibc…
  x11    https://packages.termux.dev/apt/termux-x11    (dists/x11/main)    — xfce4, tigervnc, x11vnc…
Si un nombre existe en varios repos gana el primero de esa lista (main).

Pasos:
  1. Descarga el índice Packages.gz de cada repo.
  2. Resuelve la clausura transitiva de dependencias (Depends/Pre-Depends) a partir de
     tools/rootfs/package_list.txt.
  3. Revisa que ningún par de paquetes de la clausura se excluya (Conflicts:/Breaks:) — un
     `apt install ./*.deb` con dos paquetes excluyentes falla ENTERO.
  4. Descarga cada .deb (con verificación de SHA256 contra el índice) y lo copia tal cual
     (sin extraer) al staging dir.
  5. Escribe manifest.json (paquete -> versión) para trazabilidad.

Los .deb quedan SIN extraer a propósito — el dispositivo los instala con
`apt install ./*.deb` real (ver RootfsInstaller.kt), no con un tar suelto.
Si se extrajeran acá y solo se copiaran los archivos, dpkg/apt en el
dispositivo nunca sabría que esos paquetes están "instalados" (no quedan en
su base de datos) — rompería cualquier chequeo futuro de "hay actualización
disponible" (pkg list --upgradable), que es justamente parte del pedido
original de esta función.

Uso:
  build_rootfs.py <staging_dir> <cache_dir>   arma el rootfs
  build_rootfs.py --check                     solo resuelve, mide y busca conflictos (no descarga .deb)

No instala nada, no toca el sistema — solo arma archivos en staging_dir. El
tar.xz final y la subida como Release asset las hace el propio workflow
(build-rootfs.yml) o el runbook local, no este script. Corre bien tanto en Ubuntu (CI) como en
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

BASE = "https://packages.termux.dev/apt"
# (nombre, base del repo, ruta del índice). El orden es la prioridad si un nombre se repite.
REPOS = [
    ("main", f"{BASE}/termux-main", "dists/stable/main/binary-aarch64/Packages.gz"),
    ("glibc", f"{BASE}/termux-glibc", "dists/glibc/stable/binary-aarch64/Packages.gz"),
    ("x11", f"{BASE}/termux-x11", "dists/x11/main/binary-aarch64/Packages.gz"),
]

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
    """Índice combinado de todos los repos; cada entrada recuerda de qué repo viene."""
    index = {}
    for repo_name, repo_base, path in REPOS:
        url = f"{repo_base}/{path}"
        log(f"Descargando índice {repo_name}: {url}")
        with urllib.request.urlopen(url, timeout=60) as resp:
            raw = gzip.decompress(resp.read()).decode("utf-8", "replace")
        part = parse_packages_index(raw, repo_name, repo_base)
        added = 0
        for name, info in part.items():
            if name in index:
                continue  # gana el repo anterior (main primero)
            index[name] = info
            added += 1
        log(f"  {repo_name}: {len(part)} paquetes ({added} nuevos en el índice combinado)")
    return index


def _split_alternatives(raw):
    """"a (>= 1) | b, c" -> [["a", "b"], ["c"]] (solo nombres, sin versión)."""
    groups = []
    for clause in raw.split(","):
        clause = clause.strip()
        if not clause:
            continue
        names = []
        for choice in clause.split("|"):
            dep_name = re.split(r"[\s(]", choice.strip(), 1)[0].strip()
            if dep_name:
                names.append(dep_name)
        if names:
            groups.append(names)
    return groups


def _split_relations(raw):
    """Lista de (nombre, tiene_restriccion_de_version) para Conflicts/Breaks/Provides."""
    out = []
    for clause in raw.split(","):
        clause = clause.strip()
        if not clause:
            continue
        name = re.split(r"[\s(]", clause, 1)[0].strip()
        if name:
            out.append((name, "(" in clause))
    return out


def parse_packages_index(raw, repo_name="main", repo_base=None):
    """Parsea el formato de control de apt (bloques separados por línea en blanco)."""
    repo_base = repo_base or REPOS[0][1]
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
            # "pkgname (>= 1.0) | otherpkg" -> guarda TODAS las alternativas (no solo
            # la primera) — resolve_closure() elige cuál usar según lo ya pedido en
            # package_list.txt, para no arrastrar una alternativa que choca
            # (Conflicts:) con la que el usuario pidió explícito (bug real confirmado
            # 2026-08-27: nodejs-lts en package_list.txt + algún paquete con
            # "Depends: nodejs | nodejs-lts" tomando ciegamente "nodejs" como primera
            # alternativa -> ambos terminaban en el rootfs, "apt install" fallaba con
            # "Conflicts: nodejs-lts" código 100,).
            deps = _split_alternatives(fields.get("Depends", "")) + _split_alternatives(fields.get("Pre-Depends", ""))
            try:
                size = int(fields.get("Size", "0"))
            except ValueError:
                size = 0
            index[name] = {
                "version": fields.get("Version", ""),
                "filename": fields.get("Filename", ""),
                "sha256": fields.get("SHA256", ""),
                "size": size,
                "depends": deps,
                "conflicts": _split_relations(fields.get("Conflicts", "")),
                "breaks": _split_relations(fields.get("Breaks", "")),
                "provides": [n for n, _ in _split_relations(fields.get("Provides", ""))],
                "repo": repo_name,
                "repo_base": repo_base,
            }

    for line in raw.split("\n"):
        if line == "":
            flush()
            block_lines = []
        else:
            block_lines.append(line)
    flush()
    return index


def build_provides(index):
    """nombre virtual -> [paquetes reales que lo proveen]."""
    provides = {}
    for name, info in index.items():
        for virtual in info.get("provides", []):
            provides.setdefault(virtual, []).append(name)
    return provides


def resolve_closure(roots, index):
    """BFS de dependencias transitivas. Paquete raíz faltante en el índice = error
    duro (typo real); dependencia transitiva faltante = warning (puede ser un
    virtual package o algo ya provisto por el sistema base) — no bloquea el build.

    Cada entrada de "depends" es una LISTA de alternativas ("pkgA | pkgB" en el
    Packages real) — se elige la alternativa ya presente en `roots` (lo que
    package_list.txt pidió explícito) si hay alguna, para no arrastrar una
    alternativa que choca (Conflicts:) con la pedida a propósito; si ninguna
    alternativa es un root, se usa la primera como antes (comportamiento
    default sin cambios para el caso común sin ambigüedad real). Un nombre virtual
    (Provides:) se resuelve al paquete real que lo provee."""
    roots_set = set(roots)
    provides = build_provides(index)
    resolved = set()
    queue = list(roots)
    missing_roots = []

    while queue:
        name = queue.pop(0)
        if name in resolved:
            continue
        info = index.get(name)
        if info is None:
            if name in roots_set:
                missing_roots.append(name)
            elif name in provides:
                # virtual: si algún proveedor ya está en la clausura/raíces, no hace falta nada más
                if not any(p in resolved or p in roots_set for p in provides[name]):
                    queue.append(sorted(provides[name])[0])
            continue
        resolved.add(name)
        for alt_names in info["depends"]:
            chosen = next((a for a in alt_names if a in roots_set), alt_names[0])
            if chosen not in resolved:
                queue.append(chosen)

    if missing_roots:
        raise SystemExit(
            f"ERROR: paquetes de package_list.txt no encontrados en el índice: {sorted(set(missing_roots))}"
        )
    return resolved


def find_conflicts(resolved, index):
    """Pares (A, B, motivo) de la clausura que `apt install ./*.deb` no podría instalar juntos.

    Solo cuentan los Conflicts: SIN restricción de versión (con restricción, p. ej.
    "Conflicts: foo (<< 1.2)", se evalúa contra una versión vieja que la clausura no trae) y los
    Breaks: sin versión. Un paquete nunca choca consigo mismo ni con lo que él mismo provee."""
    provides = build_provides(index)
    found = []
    seen = set()
    for name in sorted(resolved):
        info = index[name]
        for relation, entries in (("Conflicts", info["conflicts"]), ("Breaks", info["breaks"])):
            for target, versioned in entries:
                if versioned:
                    continue
                candidates = [target] if target in resolved else []
                candidates += [p for p in provides.get(target, []) if p in resolved]
                for other in candidates:
                    # Un paquete que a la vez "Conflicts:" y "Provides:" un nombre es excluyente con el paquete REAL de
                    # ese nombre (caso vulkan-loader-generic vs vulkan-loader-android, detectado por apt el 2026-10-09).
                    if other == name:
                        continue
                    key = tuple(sorted((name, other)))
                    if key in seen:
                        continue
                    seen.add(key)
                    found.append((name, other, f"{name} {relation}: {target}"))
    return found


def check_only():
    roots = read_package_list(os.environ.get("KAIROS_PACKAGE_LIST", PACKAGE_LIST_FILE))
    index = fetch_packages_index()
    resolved = resolve_closure(roots, index)
    total = sum(index[n]["size"] for n in resolved)
    by_repo = {}
    for n in resolved:
        by_repo.setdefault(index[n]["repo"], []).append(index[n]["size"])
    log(f"{len(roots)} raíces -> {len(resolved)} paquetes, {total / 1e6:.1f} MB de .deb")
    for repo, sizes in sorted(by_repo.items()):
        log(f"  {repo}: {len(sizes)} paquetes, {sum(sizes) / 1e6:.1f} MB")
    biggest = sorted(resolved, key=lambda n: -index[n]["size"])[:12]
    log("Más pesados: " + ", ".join(f"{n} {index[n]['size'] / 1e6:.1f}MB" for n in biggest))
    conflicts = find_conflicts(resolved, index)
    if conflicts:
        log(f"CONFLICTOS ({len(conflicts)}): apt install ./*.deb fallaría")
        for a, b, why in conflicts:
            log(f"  - {a} <-> {b}   ({why})")
        sys.exit(2)
    log("Sin conflictos entre paquetes de la clausura.")


def download_deb(name, info, cache_dir):
    filename = info["filename"]
    if not filename:
        log(f"  ! {name}: sin Filename en el índice, se salta")
        return None
    url = f"{info['repo_base']}/{filename}"
    dest = os.path.join(cache_dir, os.path.basename(filename))
    if os.path.exists(dest) and _sha256_of(dest) == info["sha256"]:
        return dest

    log(f"  descargando {name} ({info['version']}, {info['repo']})...")
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
    if len(sys.argv) == 2 and sys.argv[1] == "--check":
        check_only()
        return
    if len(sys.argv) != 3:
        print("Uso: build_rootfs.py <staging_dir> <cache_dir>   |   build_rootfs.py --check")
        sys.exit(1)
    staging_dir, cache_dir = sys.argv[1], sys.argv[2]
    os.makedirs(staging_dir, exist_ok=True)
    os.makedirs(cache_dir, exist_ok=True)

    roots = read_package_list(os.environ.get("KAIROS_PACKAGE_LIST", PACKAGE_LIST_FILE))
    log(f"{len(roots)} paquetes raíz en package_list.txt")

    index = fetch_packages_index()
    log(f"Índice combinado: {len(index)} paquetes disponibles")

    resolved = resolve_closure(roots, index)
    log(f"Clausura transitiva resuelta: {len(resolved)} paquetes totales (incluye dependencias)")

    conflicts = find_conflicts(resolved, index)
    if conflicts:
        for a, b, why in conflicts:
            log(f"CONFLICTO: {a} <-> {b} ({why})")
        raise SystemExit("ERROR: la clausura tiene paquetes excluyentes; apt install ./*.deb fallaría. Corregí package_list.txt")

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
        manifest[name] = {"version": info["version"], "repo": info["repo"]}

    manifest_path = os.path.join(staging_dir, "..", "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
    log(f"manifest.json escrito ({len(manifest)} paquetes) en {manifest_path}")
    log(f"{len(manifest)} archivos .deb listos en {staging_dir} (sin extraer)")
    log("Listo.")


if __name__ == "__main__":
    main()
