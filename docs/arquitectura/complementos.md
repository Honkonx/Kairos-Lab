# Plan: "complementos" de Kairos — qué son, dónde viven y cómo se instalan (2026-10-08)

> Este documento es el **plan**; lo marcado IMPLEMENTADO ya existe en código, lo demás es propuesta pendiente de aprobación.

## 1. Definición

- **Módulo**: tiene entrada en `modules.json`, aparece en la Tienda/lista de módulos y tiene su
  propia pantalla con estado, servicio u opciones (ej. Ollama, n8n, Remote).
- **Complemento**: una capacidad que **otra pantalla invoca**. No aparece como módulo, no tiene
  entrada en el catálogo; vive dentro de su **anfitrión** (la pantalla que lo usa) y el usuario lo
  ve como una opción, pestaña o botón de ese anfitrión. Si el anfitrión no lo necesita, no existe.
  Ejemplos: en el gestor de archivos se pueden usar complementos de 7z, zip, tar.xz; en Ciberseguridad se puede incluir Tor.
- **Extensión**: lo mismo, pero que añade opciones a un módulo ya existente (ej. el proveedor IA
  local dentro de un CLI). Para efectos de código se trata igual que un complemento.

## 2. Mapa de complementos (2026-10-08)

| Complemento | Anfitrión | Estado |
|---|---|---|
| Tor (proxy SOCKS 9050) | Ciberseguridad (pestaña "Tor") | **IMPLEMENTADO** (`util/TorManager.kt`, `CiberseguridadFragment.kt`) |
| Syncthing | Homelab (Servicios → Complementos) | en curso (ver §5) |
| Restic | Homelab (Servicios → Complementos) | en curso (ver §5) |
| 7z / zip / tar.zst | Gestor de archivos | **IMPLEMENTADO** (`util/ArchiveManager.kt`, motor propio sin binarios) |
| yt-dlp + ffmpeg | Descargador unificado (`media-downloader`) | **IMPLEMENTADO** como módulo único (`media_downloader.sh`) |
| rclone | — | eliminado por completo (sin módulo ni UI) |
| Wine/Hangover | — | retirado del catálogo; queda como trabajo futuro |

## 3. Contrato propuesto para un complemento (data-driven, adaptable)

Para no repetir el patrón de `TorManager` una vez por herramienta, un complemento se describe con
datos y un motor común (decisión de diseño: no cerrar puertas a futuro):

```
Addon(
  id, name, descriptionRes,       // identidad y texto (es/en)
  host,                           // "ciberseguridad" | "homelab" | "archivos" | ...
  pkg, bin,                       // paquete pkg y ejecutable real a verificar con --version
  service?: (tmuxSession, port, serveArgs, guiUrl?),   // solo si es un daemon
  actions: [...]                  // acciones de UI reales (cada una = una operación del programa)
)
```
Motor común: `install()` (pkg + verificación real de que el binario corre), `status()`, `start()/stop()`
(tmux + puerto), `uninstall()`. El anfitrión solo pinta las tarjetas. Registro de estado:
claves `addon.<id>.*` en `~/.android_server_registry` (mismo mecanismo que los módulos).

Reglas que heredan todos: sin proceso en segundo plano sin un interruptor visible; los secretos
(contraseñas de repositorio, tokens) nunca se guardan ni se vuelven a mostrar; cada botón corresponde a una
operación real y verificada del programa.

## 4. Cómo llegan al dispositivo ("rootfs o wizard")

1. **Bajo demanda desde su anfitrión** (hoy): el botón "Instalar" hace `pkg install` y verifica el
   binario. Funciona sin tocar nada más.
2. **Dentro del rootfs embebido**: agregar el paquete a `tools/rootfs/package_list.txt`
   (lo consume `tools/rootfs/build_rootfs.py`, que baja los `.deb` de Termux sin compilar; ver
   [`../bootstrap/rootfs-embebido.md`](../bootstrap/rootfs-embebido.md)). Ventaja:
   quedan instalables sin red. Costo: tamaño del APK con rootfs (hoy ~533 MB). **Pendiente de medir**
   el peso real de tor/syncthing/restic antes de decidir.
3. **Wizard**: `WizardActivity` ofrecería una pantalla "Complementos opcionales" con casillas que
   encolan los `pkg install`. **No se leyó todavía el código del wizard**; el diseño exacto queda
   para cuando se confirme qué complementos van ahí.

## 5. Siguiente paso concreto (en curso)

Syncthing y Restic pasan a la pestaña **Servicios** de Homelab como sección "Complementos", con el
motor común de §3. Syncthing es un daemon con interfaz web (`syncthing serve --no-browser
--no-restart --gui-address=127.0.0.1:8384`, según el antiguo `modulos/syncthing.sh`); Restic es una
CLI sin servicio (`restic -r <repo> init|backup|snapshots`, contraseña por la variable
`RESTIC_PASSWORD`, nunca guardada).

## 6. Integraciones "que otros invocan" — ideas, NO implementadas

- Herramientas de Ciberseguridad por Tor: `sqlmap` tiene opciones propias de proxy/Tor (verificar en
  su ayuda real antes de exponerlas); `nmap` necesitaría `proxychains` (no confirmado en Termux).
- Automatizaciones de Homelab: "iniciar Syncthing al arrancar" reutilizando el mecanismo existente
  de disparadores.
- Nube/Restic: usar un repositorio restic como destino de copias de la carpeta Nube (diseño pendiente).

## 7. Preguntas abiertas

1. ¿Qué complementos van dentro del rootfs y cuáles solo descargables en el wizard?
2. ¿Los complementos aparecen listados en algún lugar central (una pantalla "Complementos") o solo
   dentro de cada anfitrión, como ahora?
3. De los módulos con ≤3 opciones (clang, kotlin, livesrv, markserv, localtunnel): ¿cuáles
   pasan a complemento y de qué anfitrión? (Siguen pendientes de confirmación.)


## Aclaración 2026-10-08

- **Docker no es un complemento**: es un módulo que además requiere root. Quedó fuera de cualquier fusión de módulos chicos.
- **Complementos en rootfs vs. wizard**: el rootfs solo sirve para acortar descargas; el wizard corre *después* de extraerlo, así que lo que vaya en el rootfs le ahorra tiempo al wizard. Hay cosas que no pueden ir en el rootfs. Tanto el código del rootfs como el del wizard deben **detectar qué paquetes/complementos ya están instalados** y continuar con el resto. Investigación de qué paquetes caben: pendiente.
