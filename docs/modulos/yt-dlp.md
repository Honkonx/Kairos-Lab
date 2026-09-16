# yt-dlp

**Módulo Kairos** — sin Fragment propio ni switch, CLI puro invocado por el usuario vía terminal,
mismo patrón que `rclone`/`restic`.

---

**App:** Kairos (fork termux-app)
**Script:** `modulos/yt-dlp.sh`
**Categoría:** multimedia
**`hasSwitch`:** `false` — sin ON/OFF, sin proceso persistente en segundo plano

---

## 1. Qué es

Descargador de video y audio de YouTube y alrededor de 1800 sitios más (fork activo del clásico
youtube-dl). Se instala como un CLI simple, sin configuración previa — el usuario elige URL,
formato y carpeta destino en cada descarga desde la terminal.

## 2. Instalación

Paquete nativo de Termux, instalado directo — un único binario listo para usar.

## 3. Dependencia opcional: FFmpeg

yt-dlp funciona sin FFmpeg, pero lo necesita para algunas conversiones (extraer solo el audio,
remuxear a otro contenedor, ciertos formatos combinados). Kairos ya trae `ffmpeg` como módulo
propio — instalarlo cubre esta dependencia opcional sin nada adicional que configurar.

## 4. Uso

La descarga en sí (URL, formato, carpeta destino) queda en la terminal integrada, con la sintaxis
estándar de yt-dlp — no tiene pantalla propia dentro de Kairos todavía.
