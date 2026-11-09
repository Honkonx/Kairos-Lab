# Zstandard (zstd) — Módulo de Kairos

> **Obsoleto como módulo.** La compresión/descompresión TAR+Zstandard ahora forma
> parte del Administrador de Archivos. Se conserva este documento como referencia técnica, no
> como instrucción de reinstalación de un módulo independiente.

**Módulo de Kairos** — sin Fragment propio ni switch (`hasSwitch: false`), CLI puro invocado
por el usuario vía terminal (`terminalCommand: "zstd"` en `modules.json`).

> El script se escribió tras verificar el índice real de `termux-packages`
> (`packages/zstd/build.sh`, `TERMUX_PKG_VERSION="1.5.7"`, paquete normal, no root).

---

**Script:** `modulos/zstd.sh` (v1.0.0) — copia sincronizada en
`app/src/main/assets/scripts/zstd.sh`.
**Registry:** prefijo `zstd.*`
**Puerto:** ninguno (CLI puro, sin servicio de fondo).

---

## 1. Descripción general

Compresión moderna (algoritmo Zstandard, de Facebook/Meta) — mucho más rápida que gzip/xz a
ratios comparables o mejores, con soporte de multi-hilo (`-T0` usa todos los núcleos
disponibles). Útil para comprimir backups grandes antes de subirlos a un destino remoto, o como paso
manual antes/después de un `restic backup` de archivos que no se benefician de la
deduplicación propia de restic (ej. un volcado de base de datos grande).

## 2. Instalación

Paquete apt nativo instalado vía `install_single_pkg()` (mismo helper que `golang.sh`) — depende
de `liblzma`/`zlib`, resueltos automáticamente por `pkg install`.

## 3. Uso (queda en terminal, por diseño de la herramienta upstream)

`zstd <archivo>` / `unzstd <archivo>.zst` / `zstd -T0 -19 <archivo>` (máxima compresión,
multi-hilo) son comandos puntuales del usuario — no hay nada que configurar de forma
persistente.
