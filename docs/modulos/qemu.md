# QEMU (`qemu`) — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (pestaña Módulos/Tienda). Instalación manejada por la app vía `ProcessBuilder` → `modulos/qemu.sh`; la pantalla (`QemuFragment.kt`) es un Fragment dedicado (no cae en `GenericModuleFragment`).

---

**Proyecto:** Kairos
**Script:** `modulos/qemu.sh` (v1.4.0) — espejado byte a byte en `app/src/main/assets/scripts/qemu.sh` (mismo contrato `lib.sh`)
**Fragment:** `app/src/main/java/com/termux/app/ui/QemuFragment.kt`
**`id` en `modules.json`:** `qemu` — `hasSwitch: false` (no es un servicio persistente con proceso de fondo; ver §4), `type: "Nativo"`, `arch: "bionic"`, `category: "dev"`, `size: "~150MB"`, `estimate: "~3 min"`, `terminalCommand: "qemu-system-x86_64"`, `requires: []`, `installMethods: ["pkg"]`, `port: ""`

> Fuentes de esta ficha: `modulos/qemu.sh` (520 líneas), `app/src/main/assets/modules.json`, `QemuFragment.kt` (1607 líneas), `ModuleDetailNavigator.kt:41` y `QmpClient.kt` (`app/src/main/java/com/termux/app/qemu/`). La copia `app/src/main/assets/scripts/qemu.sh` es idéntica (`cmp`).

---

## 1. Qué es y para qué sirve

Caja de herramientas QEMU sin root: (a) `qemu-user` (x86_64/arm) para ejecutar binarios estáticos de otra arquitectura; (b) `qemu-system` headless **sin KVM** (TCG por software, `/dev/kvm` no existe sin root) para bootear Alpine/Debian/Ubuntu en consola serie o con pantalla vía VNC embebido, con control en caliente por QMP y acceso SSH por `hostfwd` (puerto host 2222 → 22 del guest).

Hallazgos que condicionan el diseño (documentados en la cabecera del script):

- Termux tiene paquetes reales de QEMU en su repo (algunos vía `x11-repo`): `qemu-system-x86-64(-headless)`, `qemu-system-aarch64`, `qemu-system-i386`, `qemu-system-arm`, `qemu-utils`, y los paquetes `qemu-user-<arch>` (`qemu-user-x86-64`, `qemu-user-arm`, etc.).
- **Android no expone `/dev/kvm` a apps sin root.** No hay forma de dar aceleración de hardware a `qemu-system` sin rootear el dispositivo. Sin KVM, `qemu-system` corre en **TCG** (Tiny Code Generator — traducción de instrucciones por software): funciona, pero muy por debajo de nativo. Útil para probar un binario o bootear una distro headless liviana; **no** apto para un uso "de escritorio" fluido — una VM gráfica pesada (ej. Windows) sería prácticamente inusable.
- `qemu-user` (`qemu-x86_64`, `qemu-arm`, etc.) sí es genuinamente útil sin root: corre un binario **estático** de otra arquitectura directo (ej. un binario x86_64 en un teléfono ARM64), sin necesitar una VM completa ni `binfmt_misc` (que sí requiere root) — se invoca explícito: `qemu-x86_64 ./mi_binario_x86_64`. Es el caso de uso más sólido de QEMU en Termux sin root.

| | Capacidad |
|---|---|
| ✅ | `qemu-user-x86-64` + `qemu-user-arm` — correr binarios estáticos de otra arquitectura (rápido, uso real, sin root) |
| ✅ | `qemu-system-*-headless` + `qemu-utils` — bootear una VM **sin KVM**, por consola serie o con pantalla vía VNC. Software puro, lento (minutos de boot, no segundos) |
| ❌ | No incluye ninguna imagen/ISO de sistema operativo por defecto en el script (el catálogo descargable vive en la UI) |
| ❌ | No es una alternativa a una VM de escritorio con GPU |

El Fragment repite el mismo criterio: no hay UI de "VM gráfica" ni selector de aceleración KVM/HAX — prometerlo sería UI aspiracional sin respaldo real.

## 2. Clasificación

**MÓDULO FUNCIONAL.** Fragment dedicado con estado vivo (`refreshStatus()` mide `command -v` real), ciclo de vida de una VM (arrancar `bootVm()`, reconectar, detener `stopVm()`), gestión de imágenes (crear/listar/importar/info/resize/convert con `qemu-img`), descarga con progreso, control QMP y SSH. No tiene `hasSwitch` ni puerto TCP, así que no usa el interruptor de servicio estándar; el "servicio" es la sesión tmux de la VM. **Opciones de UI aproximadas: ~30** (2 ejecutar binario + 4 imágenes + 6 descargas + 3 chips arch/RAM/res + campo ruta/RAM + pick/boot/reconectar/detener + 6 QMP + SSH + instalar paquete + terminal + mantenimiento).

## 3. Permisos

- **Android**: ninguno específico más allá del wizard genérico de la app (almacenamiento). No requiere root ni `INTERNET` extra propio (la descarga de imágenes del catálogo usa `HttpURLConnection` desde Kotlin, dentro del mismo proceso de la app).
- **Termux interno**: corre 100% en espacio de usuario, sin `/dev/kvm`, sin `binfmt_misc` (ambos requieren root en Android).

## 4. Instalación (`modulos/qemu.sh`)

`bash qemu.sh --silent` (flags `--silent --force --describe --describe-files`). Contrato `lib.sh` estándar (`log`/`warn`/`error`/`info`/`step` + `check_done`/`mark_done`/`registry_write`). Corto-circuito si ya existe `qemu-x86_64` y algún `qemu-system-*` y no se pasó `--force`.

| Flag | Qué hace |
|---|---|
| `--silent` | Modo app: sin prompts |
| `--force` | Borra el checkpoint y reinstala aunque ya esté |
| `--describe` | Manifiesto JSON (`{"id":"qemu","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false,"note":"..."}`) |
| `--describe-files` | Manifiesto de archivos para `moduledeb` |

Pasos con checkpoint (idempotentes vía `check_done`/`mark_done`):

1. **`1/3 x11-repo`** — algunos paquetes `qemu-system` viven en `x11-repo`; seguido de `pkg_update_with_fallback`.
2. **`2/3 qemu-user-x86-64 qemu-user-arm`**.
3. **`3/3 qemu-system-aarch64-headless` + `qemu-system-x86-64-headless` + `qemu-utils`**. aarch64 es el guest RECOMENDADO (mismo arch que el host; usa `-machine virt -cpu max -bios edk2-aarch64-code.fd`, `virtio-blk-pci`, `virtio-gpu-pci` en VNC); x86_64 usa `-machine q35`.

Los fallos de `pkg install` solo hacen `warn` (no abortan): filosofía "degradado, no todo o nada", y `qemu-user` sigue funcionando aunque falle el paso 3. Por eso **el módulo puede terminar con `qemu.installed=true` aunque ningún `qemu-system` haya quedado**; el Fragment lo compensa con `refreshStatus()` y un diálogo. No usa `verify_binary_installed` (solo `command -v`). Sin sha256 propio: la integridad es la de apt/dpkg de Termux.

### Scripts wrapper generados (`~/scripts/qemu/`)

- **`run_user.sh <arch> <binario> [args...]`** — valida argumentos, verifica que `qemu-$ARCH` exista, y hace `exec "qemu-$ARCH" "$BIN" "$@"`. `<arch>` soportados: `x86_64` | `arm`.
- **`run_vm.sh <imagen> [ram] [ssh_port] [console|vnc] [WxH] [x86_64|aarch64]`** — RAM por defecto `512` MB, puerto SSH host por defecto `2222`, modo por defecto `console`. Valida que la imagen exista, avisa que sin KVM el boot va a tardar, e intenta primero `-drive file=$IMG,format=qcow2` y luego `-cdrom` (cubre imágenes de disco e ISOs). Sin flags de aceleración (`-enable-kvm`/`-accel`) en ningún modo.
  - **Modo `console`** — `-nographic` (consola serie en la terminal, `exec`).
  - **Modo `vnc`** — pantalla servida por **socket unix** `$HOME/scripts/qemu/vncsocket` (`-vnc unix:`); arranca en sesión tmux `kairos_qemu_vnc` con wrapper `.vm_vnc_cmd.sh` y log `~/kairos_logs/qemu_vm_vnc.log`. Sin `-nographic`, la salida real pasa a ser el framebuffer gráfico.
  - En todos los modos se abre además un **socket QMP** `$HOME/scripts/qemu/qmpsocket`.

### Registry (`~/.android_server_registry`)

```
qemu.installed=true
qemu.version=1.4.0
qemu.kvm=false
qemu.mode=tcg_software
qemu.user_mode=<true|false>
qemu.system_mode_aarch64=<true|false>
qemu.system_mode_x86_64=<true|false>
```

`user_mode`/`system_mode_*` reflejan el resultado real de la instalación al momento de correr el script; la UI (`QemuFragment.refreshStatus()`) re-chequea en vivo con `command -v` cada vez que se abre la pantalla, así que puede divergir del registry si el usuario desinstaló un paquete a mano.

## 5. Ejecución

No hay start/stop de servicio: es una **caja de herramientas** que se instala una vez y se invoca bajo demanda.

- Modo consola: `bootVmConsole()` abre una sesión de terminal con `run_vm.sh ... console`.
- Modo VNC: `bootVmVnc()` ejecuta `run_vm.sh ... vnc`, que lanza tmux `kairos_qemu_vnc` y sale; la app espera el socket (`waitForQemuVncSocket()`) y abre `VncViewerActivity`.
- Detener: `stopVm()` = `tmux kill-session -t kairos_qemu_vnc; pkill -f qemu-system-x86_64`.
- Control: `QmpClient` por `LocalSocket` (powerdown, stop/cont, screendump `.ppm`, change/eject medium).

## 6. Pantalla real de la app (`QemuFragment.kt`)

`getModuleId()` → `"qemu"`, `getModuleName()` → `"QEMU"`. Una card de **Estado** y **4 pestañas** (Binario, Imágenes, VM, Avanzado; `buildTabLayout()`). Si el módulo no está instalado (`isModuleInstalled()` falso), muestra la pantalla estándar "Módulo no instalado".

- **Estado** (antes de las pestañas): filas `qemu-user (x86_64/arm)` y `qemu-system (headless)` (refrescadas por `refreshStatus()`: `command -v qemu-x86_64 || command -v qemu-arm`, y `command -v qemu-system-x86_64`) + fila fija "Aceleración KVM: No disponible sin root".
- **Binario**: campo de ruta + "▶ Ejecutar como x86_64 (terminal)" / "▶ Ejecutar como arm (terminal)" → `launchTerminalCommand("qemu-<arch> '<ruta>'")`, abre una sesión de terminal real (no ejecuta en background silencioso).
- **Imágenes** (carpeta `~/qemu_images`):
  - "＋ Crear imagen de disco": nombre + tamaño → `qemu-img create -f <qcow2|raw> <archivo> <tamaño>` (formato según extensión). `normalizeQemuImgSize()` acepta `1gb`/`1GB`/`1g`/`1G` y normaliza al formato real que acepta `qemu-img` (sufijo de una letra `K`/`M`/`G`/`T`, sin `b` final); si el texto no es reconocible, lo pasa tal cual y `qemu-img` da su propio error.
  - "☰ Listar imágenes": archivos `qcow2`/`img`/`iso`/`raw`; tocar uno completa la ruta de la VM.
  - "🛠 Info / redimensionar / convertir imagen": `qemu-img info`/`resize`/`convert`. El diálogo de resize avisa que achicar (`-`) puede destruir datos si el filesystem interno no encoge también; agrandar (`+`) es seguro.
  - Importar desde almacenamiento (SAF → copia a ruta real).
  - **Catálogo de descarga (6 imágenes)**: Alpine 3.24.1 virt, Debian 12 generic y Ubuntu 22.04 cloudimg, cada una en **aarch64 y x86_64**. Las URLs son de dominios oficiales por HTTPS (p. ej. `dl-cdn.alpinelinux.org`, `cloud.debian.org`, `cloud-images.ubuntu.com`). La descarga (`downloadDiskImage`/`downloadFileWithProgress`) muestra progreso real (%, velocidad, ETA cada 500 ms), usa `HttpURLConnection` con redirects, timeout 15 s/30 s, descarga a un `.part` y hace `renameTo()` solo si el archivo final tiene al menos el 95% del tamaño esperado.
  - Nota: Alpine bootea directo a login root sin cloud-init; las imágenes cloud de Debian/Ubuntu están pensadas para `cloud-init` y pueden requerir un ISO de semilla con usuario/contraseña para loguear por consola — la app no automatiza esa parte.
- **VM**: ruta de imagen, chips de arquitectura del guest (aarch64/x86_64), RAM (+ presets, recomendación `recommendedRamMb()`), resolución (presets, solo x86_64), "📂 Elegir imagen de la lista", "▶ Arrancar VM…" (diálogo consola/VNC), "Reconectar VNC", "Detener VM", 6 controles QMP (apagar ACPI, pausar, reanudar, captura de pantalla, cambiar/expulsar medio), "Conectar por SSH" (`connectSsh()`) e "Instalar paquete en la VM" (apt/apk por SSH).
  - "Consola / SSH" (default): `run_vm.sh ... console` en una sesión de terminal, con toast "Arrancando sin KVM — el boot va a tardar (TCG software)".
  - "VNC": `run_vm.sh ... vnc` y apertura automática de `VncViewerActivity` apuntando al socket unix; reusa el visor VNC nativo existente.
  - La opción "X11 nativo" (`-display gtk/sdl` sobre el X11 embebido) no está implementada: depende de que el paquete headless soporte GTK/SDL, hecho no confirmado.
- **Avanzado**: "Abrir terminal" en `~/qemu_images` + mantenimiento (actualizar/desinstalar).

## 7. Limitaciones de rendimiento (documentadas en el script, el Fragment y la UI)

- **Sin `/dev/kvm`** → `qemu-system` corre en **TCG**, **no** acelerado por hardware.
- El boot de una VM headless tarda **minutos, no segundos**; el script lo advierte en `run_vm.sh` y la UI lo repite en el toast de "Arrancar VM" y en el texto de la card de descargas.
- **No recomendado** para una VM gráfica pesada (ej. Windows).
- `qemu-user` no sufre esta limitación — no es una VM completa, solo traduce instrucciones de un binario estático al vuelo.

## 8. Comandos de referencia rápida

```bash
# Correr un binario estático x86_64 en un teléfono ARM64
bash ~/scripts/qemu/run_user.sh x86_64 ./mi_binario_x86_64 [args...]

# Correr un binario estático ARM
bash ~/scripts/qemu/run_user.sh arm ./mi_binario_arm

# Crear una imagen de disco vacía
qemu-img create -f qcow2 ~/qemu_images/disco1.qcow2 4G

# Arrancar una VM en modo consola (sin gráficos, sin KVM — lento)
bash ~/scripts/qemu/run_vm.sh ~/qemu_images/alpine-virt-3.24.1-x86_64.iso 512

# Arrancar una VM en modo VNC (socket unix, sesión tmux kairos_qemu_vnc)
bash ~/scripts/qemu/run_vm.sh ~/qemu_images/alpine-virt-3.24.1-x86_64.iso 512 2222 vnc x86_64
```

## 9. Archivos y rutas

`~/scripts/qemu/{run_user.sh,run_vm.sh,.vm_vnc_cmd.sh,vncsocket,qmpsocket}`, `~/qemu_images/` (imágenes, capturas `.ppm`), `~/kairos_logs/qemu_vm_vnc.log`, `~/kairos_logs/install_qemu.log`, `~/.install_qemu_checkpoint`, registry.

## 10. Seguridad

- VNC y QMP son sockets unix locales sin contraseña (protegidos por los permisos del `$HOME` de la app, no por autenticación).
- El `hostfwd` SSH abre `tcp::2222-:22` en la interfaz por defecto de SLIRP (escucha en todas las interfaces del host salvo que se restrinja; inferencia: no se verificó si Android lo expone fuera del dispositivo).
- Descargas de imágenes por HTTPS desde dominios oficiales, **sin verificación de sha256/firma** (solo chequeo de tamaño ≥95%).
- Sin `curl | bash`. Las contraseñas SSH no se guardan en este Fragment (se piden en el cliente SSH de la terminal).

## 11. Problemas / limitaciones

1. `stopVm()` mata por patrón solo `qemu-system-x86_64`; una VM aarch64 arrancada en modo CONSOLA no se detiene con "Detener VM" (en modo VNC sí, por tmux).
2. La instalación puede dar éxito con paquetes faltantes (§4); no usa `verify_binary_installed`.
3. Catálogo sin sha256; versión de imagen fijada (`3.24.1`; el `latest` de Debian cambia).
4. `terminalCommand: qemu-system-x86_64` en `modules.json` contrasta con el guest recomendado aarch64.
5. Un solo socket/sesión fijos: una sola VM a la vez (por diseño, documentado en `run_vm.sh`).
6. Inferencia: que aarch64 sin KVM sea "usable" para escritorio; no se midió rendimiento.

## 12. Fecha de alta

`modulos/qemu.sh`: **2026-08-16**.
