# FFmpeg

**Módulo Kairos** — sin Fragment propio ni switch, CLI puro invocado por el usuario vía terminal,
mismo patrón que `rclone`/`restic`.

---

**App:** Kairos (fork termux-app)
**Script:** `modulos/ffmpeg.sh`
**Categoría:** multimedia
**`hasSwitch`:** `false` — sin ON/OFF, sin proceso persistente en segundo plano

---

## 1. Qué es

FFmpeg es la herramienta de línea de comandos de referencia para conversión y edición de
audio/video: recortar, comprimir, extraer audio, generar miniaturas, cambiar de formato — sobre
cualquier archivo grabado o descargado en el propio dispositivo.

## 2. Instalación

Paquete nativo de Termux instalado directo (`pkg install ffmpeg`), sin compilación ni pasos
extra — un binario ya empaquetado para ARM64 por el propio proyecto Termux.

## 3. Uso

FFmpeg es una herramienta de línea de comandos por diseño — no tiene una pantalla propia dentro
de Kairos. Se usa desde la terminal integrada, con la sintaxis estándar del propio FFmpeg
(`ffmpeg -i entrada.mp4 salida.mp3`, etc.).
