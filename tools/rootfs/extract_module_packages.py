#!/usr/bin/env python3
"""Lista los paquetes que los scripts de modulos/*.sh instalan con `pkg install` / `apt install`.

Sirve para decidir qué agregar al rootfs (regla kairos-rootfs-maximize.md: "se agrega todo lo que se pueda").
Ignora comentarios y las líneas que corren dentro de una distro (`proot-distro login`, `chroot`), porque esas
instalan paquetes de Debian/Ubuntu y no de Termux.

Uso: python3 tools/rootfs/extract_module_packages.py [--json]
"""
import collections
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INSTALL_RE = re.compile(r"(?:pkg|apt|apt-get)\s+install\s+((?:-[A-Za-z-]+\s+|-o\s+\S+\s+)*)([^\n;&|#`]*)")
NAME_RE = re.compile(r"[a-z0-9][a-z0-9+.\-]*")
QUOTES = "\"'\\"


def collect():
    use = collections.defaultdict(set)
    for path in sorted(glob.glob(os.path.join(ROOT, "modulos", "*.sh"))):
        module = os.path.basename(path)[:-3]
        with open(path, encoding="utf-8", errors="replace") as handle:
            for raw in handle:
                line = raw.strip()
                if line.startswith("#") or "proot-distro login" in line or "chroot" in line:
                    continue
                for match in INSTALL_RE.finditer(line):
                    for token in match.group(2).split():
                        token = token.strip(QUOTES)
                        if token.startswith("-") or not NAME_RE.fullmatch(token):
                            continue
                        use[token].add(module)
    return use


def main():
    use = collect()
    rows = sorted(use.items(), key=lambda kv: (-len(kv[1]), kv[0]))
    if "--json" in sys.argv:
        print(json.dumps({name: sorted(mods) for name, mods in rows}, ensure_ascii=False, indent=1))
        return
    for name, mods in rows:
        print(f"{name}\t{len(mods)}\t{','.join(sorted(mods))}")


if __name__ == "__main__":
    main()
