#!/usr/bin/env python3
"""Recomprime los .deb de un rootfs con xz -9e (regla .claude/rules/kairos-rootfs-maximize.md: "comprimirlo más").

Un .deb es un archivo `ar` con tres miembros: debian-binary, control.tar.* y data.tar.*. Solo se recomprime
`data.tar.*`: se descomprime a un tar crudo (bytes idénticos) y se vuelve a comprimir con xz al máximo. El contenido
instalado no cambia, los metadatos de control tampoco; dpkg lee ambos formatos igual.

Uso:
  recompress_debs.py <dir_origen> --dry-run [--limit N]    solo mide el ahorro (no escribe nada)
  recompress_debs.py <dir_origen> <dir_destino> [--jobs N]  escribe los .deb recomprimidos (solo si salen más chicos)
"""
import argparse
import bz2
import concurrent.futures
import gzip
import lzma
import os
import sys

AR_MAGIC = b"!<arch>\n"
PRESET = 9 | lzma.PRESET_EXTREME


def parse_ar(data):
    if not data.startswith(AR_MAGIC):
        raise ValueError("no es un archivo ar")
    pos, members = len(AR_MAGIC), []
    while pos + 60 <= len(data):
        header = data[pos:pos + 60]
        name = header[:16].decode("ascii").rstrip()
        size = int(header[48:58].decode("ascii").strip())
        body = data[pos + 60: pos + 60 + size]
        members.append((header, name.rstrip("/"), body))
        pos += 60 + size + (size % 2)
    return members


def build_ar(members):
    out = bytearray(AR_MAGIC)
    for header, name, body in members:
        out += header[:48] + str(len(body)).ljust(10).encode("ascii") + header[58:60] + body
        if len(body) % 2:
            out += b"\n"
    return bytes(out)


def decompress(name, body):
    if name.endswith(".xz"):
        return lzma.decompress(body)
    if name.endswith(".gz"):
        return gzip.decompress(body)
    if name.endswith(".bz2"):
        return bz2.decompress(body)
    if name.endswith(".tar"):
        return body
    return None  # zst u otro: no se toca


def recompress(path):
    """Devuelve (bytes_nuevos | None, tamaño_original)."""
    original = open(path, "rb").read()
    members = parse_ar(original)
    out = []
    changed = False
    for header, name, body in members:
        if name.startswith("data.tar") and not name.endswith(".zst"):
            raw = decompress(name, body)
            if raw is not None:
                new_body = lzma.compress(raw, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, preset=PRESET)
                if len(new_body) < len(body):
                    new_name = "data.tar.xz"
                    header = new_name.ljust(16).encode("ascii") + header[16:]
                    body, name, changed = new_body, new_name, True
        out.append((header, name, body))
    return (build_ar(out) if changed else None), len(original)


def work(args):
    src, dst = args
    new, orig = recompress(src)
    if new is not None and len(new) < orig:
        if dst:
            with open(dst, "wb") as handle:
                handle.write(new)
        return os.path.basename(src), orig, len(new)
    if dst:
        with open(src, "rb") as a, open(dst, "wb") as b:
            b.write(a.read())
    return os.path.basename(src), orig, orig


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("src")
    parser.add_argument("dst", nargs="?")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--limit", type=int, default=0, help="solo los N .deb más grandes (para medir rápido)")
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    args = parser.parse_args()
    if not args.dry_run and not args.dst:
        sys.exit("Falta <dir_destino> (o usar --dry-run)")
    debs = sorted((f for f in os.listdir(args.src) if f.endswith(".deb")),
                  key=lambda f: -os.path.getsize(os.path.join(args.src, f)))
    if args.limit:
        debs = debs[: args.limit]
    if args.dst:
        os.makedirs(args.dst, exist_ok=True)
    jobs = [(os.path.join(args.src, f), None if args.dry_run else os.path.join(args.dst, f)) for f in debs]
    before = after = 0
    with concurrent.futures.ProcessPoolExecutor(args.jobs) as pool:
        for name, orig, new in pool.map(work, jobs):
            before += orig
            after += new
            if orig != new:
                print(f"{name}: {orig / 1e6:.1f} -> {new / 1e6:.1f} MB ({100 * (orig - new) / orig:.1f}% menos)", flush=True)
    print(f"TOTAL: {before / 1e6:.1f} MB -> {after / 1e6:.1f} MB ({100 * (before - after) / max(before, 1):.2f}% menos, {len(debs)} .deb)")


if __name__ == "__main__":
    main()
