#!/usr/bin/env python3
"""Agrega a package_list.txt (y a sus dos copias sincronizadas) el bloque de la ampliación 2026-10-09.

Idempotente: si el bloque ya está, no lo duplica. Las tres copias deben quedar idénticas (ver cabecera de
package_list.txt): tools/rootfs/package_list.txt, modulos/rootfs_package_list.txt y
app/src/main/assets/scripts/rootfs_package_list.txt.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
COPIES = [
    "tools/rootfs/package_list.txt",
    "modulos/rootfs_package_list.txt",
    "app/src/main/assets/scripts/rootfs_package_list.txt",
]
MARK = "# ── Ampliación 2026-10-09"

BLOCK = """
# ── Ampliación 2026-10-09 (regla.claude/rules/kairos-rootfs-maximize.md, mensaje 6) ──
# "Se agrega todo lo que se pueda": todo lo que los módulos instalan con pkg install, más los complementos.
# build_rootfs.py resuelve tres repos (main + glibc + x11) y revisa conflictos antes de descargar
# (python3 tools/rootfs/build_rootfs.py --check). Tamaños aproximados de los .deb (aarch64, 2026-10-09).

# Repos extra de Termux y runtime glibc — los usan 12 módulos independientes (claude, antigravity, kilo,
# copilotcli, cursor, mimocode, ohmypi, openclaw, freebuff, codebuff, codegraph, opencode).
glibc-repo
x11-repo
glibc
glibc-runner
patchelf-glibc
openssl-glibc

# Android / Kotlin / JVM (módulo apk, kotlin) — openjdk-17 ~96 MB, kotlin ~87 MB
aapt
aapt2
apksigner
apktool
d8
openjdk-17
kotlin

# Lenguajes y herramientas de desarrollo (módulos golang, php, ruby, ide, languages)
golang
php
composer
ruby
neovim
lua-language-server
stylua
tree-sitter
glow

# Base de datos y servicios (módulo db, stacks)
mariadb
postgresql
redis

# Red y seguridad (ciberseguridad, remote, n8n, nube)
clamav
dirb
mosh
nmap
netcat-openbsd
cloudflared

# IA y ciencia de datos (ollama, hf, python)
ollama
python-numpy
python-yt-dlp

# Virtualización (módulo qemu)
qemu-system-aarch64-headless
qemu-system-x86-64-headless
qemu-user-aarch64
qemu-user-x86-64

# GPU / Vulkan y gráficos (entorno, ollama, llamaserver)
vulkan-tools
mesa
mesa-vulkan-icd-freedreno
virglrenderer-android
angle-android
dbus
pulseaudio

# Escritorio nativo de Mini PC (repo x11): xfce4 y VNC
xfce4
xfce4-goodies
xfce4-whiskermenu-plugin
tigervnc
x11vnc
pavucontrol
mesa-demos

# Complementos (Tor en Ciberseguridad; Syncthing y Restic en Homelab): ~3,4 + 8,5 + 9,1 MB
tor
syncthing
restic
"""


def main():
    for rel in COPIES:
        path = os.path.join(ROOT, rel)
        with open(path, encoding="utf-8", newline="") as handle:
            text = handle.read()
        if MARK in text:
            print(f"ya aplicado: {rel}")
            continue
        newline = "\r\n" if "\r\n" in text else "\n"
        text = text.rstrip("\r\n") + newline + BLOCK.replace("\n", newline)
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        print(f"actualizado: {rel}")


if __name__ == "__main__":
    main()
