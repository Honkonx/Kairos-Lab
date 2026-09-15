# Syncthing

**Módulo Kairos** — sin Fragment propio, gestionado por la pantalla genérica de módulo (la app
ya tiene todo lo necesario para instalar/iniciar/detener/abrir la UI web sin necesitar una
pantalla dedicada).

---

**App:** Kairos (fork termux-app)
**Script:** `modulos/syncthing.sh`
**Puerto:** `8384` (UI web, solo accesible desde el propio dispositivo — Kairos la abre embebida
dentro de la app)

---

## 1. Qué es

Sincronización P2P de archivos entre dispositivos, sin depender de ningún proveedor cloud — a
diferencia de `rclone`, que sincroniza CONTRA un proveedor externo (Drive, S3, Dropbox, etc.).
Complementa al módulo `rclone`, no lo reemplaza.

## 2. Instalación

Paquete nativo de Termux, instalado directo con el mismo mecanismo que `rclone`/`ffmpeg`.

## 3. Arranque y detención

El módulo tiene switch ON/OFF real: al activarlo, arranca el servidor de Syncthing en segundo
plano con su UI web escuchando solo en el propio dispositivo (`127.0.0.1:8384`). Al
desactivarlo, se detiene el proceso.

## 4. Configuración

El pareo de dispositivos (compartir el ID del dispositivo con otro Syncthing) y la elección de
qué carpetas sincronizar son pasos inherentemente interactivos, por diseño de la propia
herramienta — se configuran desde la UI web de Syncthing, que Kairos abre embebida
automáticamente dentro de la app en cuanto el switch está en ON, sin necesitar un navegador
aparte.
