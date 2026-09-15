# rclone

**Módulo Kairos** — sin Fragment propio ni switch, CLI puro invocado por el usuario vía
terminal, mismo patrón que `ffmpeg`/`restic`.

---

**App:** Kairos (fork termux-app)
**Script:** `modulos/rclone.sh`
**Categoría:** nube
**`hasSwitch`:** `false` — sin ON/OFF, sin proceso persistente en segundo plano

---

## 1. Qué es

Conocido como "rsync para la nube": sincroniza o monta más de 70 proveedores de almacenamiento
(Google Drive, Dropbox, S3, WebDAV, SFTP, y muchos más) directo desde el propio dispositivo, sin
salir de Kairos para tener el binario disponible. Complementa al módulo `restic` (que respalda
CON historial de versiones y cifrado) y a `syncthing` (que sincroniza entre dispositivos propios,
sin pasar por ningún proveedor externo).

## 2. Instalación

Paquete nativo de Termux (`pkg install rclone`) — un único binario Go estático, sin
dependencias extra.

## 3. Configuración y uso

La configuración de un remote (`rclone config`) es interactiva por diseño de la propia
herramienta — se pide nombre del proveedor, credenciales, y varias opciones específicas de cada
servicio, un flujo que no tiene sentido reducir a un formulario genérico sin perder
flexibilidad. Por eso, tanto la configuración como el uso día a día (`rclone sync`, `rclone
mount`, `rclone copy`, etc.) quedan en la terminal integrada de Kairos, con la sintaxis
estándar de rclone.

Un remote ya configurado con rclone puede reusarse como destino de `restic` (ver el módulo
`restic`), sin tener que configurar el acceso al proveedor dos veces.
