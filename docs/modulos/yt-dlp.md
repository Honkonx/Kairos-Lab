# yt-dlp — Módulo de Kairos

**Módulo de Kairos** — sin Fragment propio ni switch (`hasSwitch: false`), CLI puro invocado
por el usuario vía terminal (`terminalCommand: "yt-dlp"` en `modules.json`).

> Verificado contra el índice real de `termux-packages` (`packages/python-yt-dlp/build.sh`,
> paquete normal, no root) antes de escribir el script.

---

**Script:** `modulos/yt-dlp.sh` (v1.0.0) — copia sincronizada en
`app/src/main/assets/scripts/yt-dlp.sh`.
**Registry:** prefijo `yt-dlp.*`
**Puerto:** ninguno (CLI puro, sin servicio de fondo).

---

## 1. Descripción general

Descargador de video/audio de YouTube y ~1800 sitios más (fork activo de youtube-dl). Se instala
con un CLI simple sin configuración — el usuario elige URL/formato/carpeta destino en cada
invocación desde la terminal.

## 2. Particularidad del nombre de paquete

**El nombre del paquete apt real es `python-yt-dlp`, NO `yt-dlp` a secas** — no existe ningún
paquete llamado `yt-dlp` en el índice de `termux-packages`. El binario resultante sí se llama
`yt-dlp` (por eso `terminalCommand`/`check_cmd` usan ese nombre, solo el argumento de
`pkg install`/`install_single_pkg()` usa el nombre real del paquete). Se
confirmó contra `packages/python-yt-dlp/build.sh` (`TERMUX_PKG_PROVIDES='yt-dlp'`) antes de
escribir el script, no se asumió por el nombre de la herramienta upstream.

## 3. Instalación

Paquete apt nativo instalado vía `install_single_pkg()` — `install_single_pkg "yt-dlp" "yt-dlp" python-yt-dlp`
(id="yt-dlp", check_cmd="yt-dlp", paquete real="python-yt-dlp").

## 4. Dependencia opcional: ffmpeg

El `build.sh` real de `python-yt-dlp` declara `TERMUX_PKG_RECOMMENDS="ffmpeg, yt-dlp-ejs"` — yt-dlp
funciona sin `ffmpeg` pero lo necesita para remux/extracción de audio y algunos formatos. Kairos
tiene un módulo `ffmpeg` propio (categoría `multimedia`) que cubre esa dependencia opcional — no
se duplica lógica de instalación acá, solo se documenta la relación.

## 5. Uso (queda en terminal, por diseño de la herramienta upstream)

La descarga en sí (URL, formato, carpeta destino) es inherentemente un comando por descarga.
Sin UI propia todavía.
