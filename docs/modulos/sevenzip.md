# 7-Zip (sevenzip) — Módulo de Kairos

> **Obsoleto como módulo.** La operación 7z ahora pertenece al Administrador de
> Archivos vía `ArchiveManager`; la ficha/instalador independiente ya no se distribuye. Se
> conserva este documento como referencia técnica, no como instrucción de reinstalación.

**Módulo de Kairos** — sin Fragment propio ni switch (`hasSwitch: false`), CLI puro invocado
por el usuario vía terminal (`terminalCommand: "7zz"` en `modules.json`).

> El script se escribió tras verificar el índice real de `termux-packages`
> (`packages/7zip/build.sh`, `TERMUX_PKG_VERSION="26.03"`, paquete normal, no root).

---

**Script:** `modulos/sevenzip.sh` (v1.0.0) — copia sincronizada en
`app/src/main/assets/scripts/sevenzip.sh`.
**Registry:** prefijo `sevenzip.*`
**Puerto:** ninguno (CLI puro, sin servicio de fondo).

---

## 1. Descripción general

Archivador con alta compresión (formato `.7z`) — además lee/escribe `.zip`/`.tar`/`.gz`/`.xz` y
lee (no escribe) `.rar`. Cubre el hueco de "formato de archivo universal" que ni `zstd` (solo
compresión de un stream, sin contenedor de múltiples archivos) ni `restic` (backup, no archivado
puntual) resuelven.

## 2. Particularidad del nombre de paquete (mismo patrón que `yt-dlp`)

El id de este módulo se llama **`sevenzip`**, no `7zip` — el resto del catálogo de Kairos usa
ids que empiezan con letra, no con dígito. El paquete apt real instalado sigue siendo
literalmente `7zip` (confirmado contra `packages/7zip/build.sh` en `termux-packages`): el
nombre `p7zip` está deprecado en el índice real y hoy es solo un alias
(`TERMUX_PKG_REPLACES`/`TERMUX_PKG_PROVIDES`) que resuelve hacia el paquete `7zip` — `pkg
install p7zip` funciona por ese alias, pero el `build.sh` real vive en `packages/7zip/`.

## 3. Instalación

Paquete apt nativo — instalado directo con `pkg install -y 7zip` (no vía `install_single_pkg()`
genérico porque la verificación post-instalación necesita un chequeo propio, ver punto 4).

## 4. Particularidad de verificación — el CLI no soporta `--version` ni `--help`

Según la documentación de 7-Zip (manual de línea de comandos), el binario
`7zz` **no** soporta `--version` ni `--help` (ambos devuelven `Command Line Error:`, exit code
7) — el flag por defecto de `verify_binary_installed()` (`--version`) habría marcado como
fallida una instalación exitosa. En cambio, `7zz` siempre imprime su banner de versión
(`"7-Zip ..."`) a stdout **antes** del error de línea de comando, sin importar el resultado del
comando — el script verifica ese banner (`7zz 2>&1 | grep 7-Zip`) en vez de depender del exit
code o de un flag estándar.

## 5. Uso (queda en terminal, por diseño de la herramienta upstream)

Binarios instalados: `7zz` (recomendado, todas las funciones), `7z`/`7za`/`7zr` (variantes más
livianas/legacy). `7zz a archivo.7z carpeta/` (comprimir), `7zz x archivo.7z` (extraer con
rutas), `7zz l archivo.7z` (listar contenido) — uso puntual del usuario vía terminal.
