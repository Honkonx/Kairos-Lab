# QEMU

**Módulo de Kairos** — gestionado desde la UI (pestaña Módulos/Tienda). Instalación manejada por la app vía `ProcessBuilder` → `modulos/qemu.sh`; la pantalla (`QemuFragment.kt`) es un Fragment dedicado.

---

**Script:** `modulos/qemu.sh` — espejado byte a byte en `app/src/main/assets/scripts/qemu.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/QemuFragment.kt`
**`id` en `modules.json`:** `qemu` — sin switch (no es un servicio persistente con proceso de fondo; ver §4), tipo nativo, categoría desarrollo, tamaño ~150MB, comando de terminal `qemu-system-x86_64`

---

## 1. Descripción general

Módulo de emulación de CPU/binarios vía QEMU, diseñado con un criterio de honestidad explícito: no prometer nada que un entorno Android sin root no pueda cumplir.

Hallazgos relevantes sobre el entorno:

- Termux tiene paquetes reales de QEMU en su repo (algunos vía `x11-repo`): `qemu-system-x86-64(-headless)`, `qemu-system-aarch64`, `qemu-system-i386`, `qemu-system-arm`, `qemu-utils`, y los paquetes `qemu-user-<arch>` (`qemu-user-x86-64`, `qemu-user-arm`, etc.).
- **Android no expone `/dev/kvm` a apps sin root.** No hay forma de dar aceleración de hardware a `qemu-system` sin rootear el dispositivo. Sin KVM, `qemu-system` corre en **TCG** (Tiny Code Generator — traducción de instrucciones por software): funciona, pero muy por debajo de nativo. Útil para probar un binario o bootear una distro headless liviana; no apto para un uso "de escritorio" fluido.
- `qemu-user` (`qemu-x86_64`, `qemu-arm`, etc.) sí es genuinamente útil sin root: corre un binario **estático** de otra arquitectura directo (ej. un binario x86_64 en un teléfono ARM64), sin necesitar una VM completa ni `binfmt_misc` (que sí requiere root) — se invoca explícito: `qemu-x86_64 ./mi_binario_x86_64`. Es el caso de uso más sólido de QEMU en Termux sin root.

Qué expone el módulo:

| | Capacidad |
|---|---|
| Sí | `qemu-user-x86-64` + `qemu-user-arm` — correr binarios estáticos de otra arquitectura (rápido, uso real, sin root) |
| Sí | `qemu-system-x86-64-headless` + `qemu-utils` — bootear una VM x86_64 sin gráficos (`-nographic`, consola serie), útil para probar un kernel/ISO/imagen liviana. Sin KVM → software puro, lento (minutos de boot, no segundos) |
| No | No incluye ninguna imagen/ISO de sistema operativo por defecto en el script (el catálogo descargable real vive en la UI — ver §6) |
| No | No es una alternativa a una VM de escritorio con GPU |

El Fragment repite el mismo criterio: se descartó a propósito cualquier UI de "VM gráfica" o selector de aceleración KVM/HAX.

## 2. Permisos

- **Android**: ninguno específico más allá del asistente de configuración genérico de la app (almacenamiento). No requiere root, no requiere `INTERNET` extra propio (la descarga de imágenes del catálogo usa `HttpURLConnection` desde Kotlin, dentro del mismo proceso de la app).
- **Termux interno**: corre completamente en espacio de usuario, sin `/dev/kvm`, sin `binfmt_misc` (ambos requieren root en Android). `qemu-user` no necesita ningún permiso adicional al invocar el binario extranjero directo.

## 3. Lógica de instalación (`modulos/qemu.sh`)

| Flag | Qué hace |
|---|---|
| `--silent` | Modo app: sin prompts |
| `--force` | Reinstala aunque ya esté |
| `--describe` | Manifiesto JSON — user-mode + system-mode sin KVM |

No implementa `--status`, `--uninstall` ni `--start`/`--stop` (no hay proceso de fondo persistente — ver §4).

**Guard de "ya instalado"**: si `qemu-x86_64` y `qemu-system-x86_64` existen y no se pasó `--force`, loguea la versión y sale sin hacer nada.

### Pasos de instalación (3 pasos con checkpoint, cada uno idempotente)

1. **Habilitando x11-repo** — algunos paquetes `qemu-system` viven en `x11-repo`. Si falla, solo avisa (no aborta).
2. **Instalando qemu-user (x86_64 + arm)** — `qemu-user-x86-64 qemu-user-arm`. Verifica el binario para loguear OK/aviso, pero no aborta si falla.
3. **Instalando qemu-system-x86-64-headless + qemu-utils** — si falla, avisa que puede no estar disponible para esta arquitectura de Termux, aclarando que `qemu-user` (paso 2) sigue funcionando igual aunque este paso falle. Ningún paso del script es bloqueante para los otros — filosofía "degradado, no todo o nada".

### Scripts wrapper generados (`~/scripts/qemu/`)

- **`run_user.sh <arch> <binario> [args...]`** — valida `arch`/`binario` no vacíos, verifica que `qemu-$ARCH` exista, y ejecuta `qemu-$ARCH "$BIN" "$@"`. `<arch>` soportados: `x86_64` | `arm`.
- **`run_vm.sh <imagen.qcow2|iso> [ram_MB] [ssh_port_host] [console|vnc]`** — RAM por defecto `512` MB, puerto SSH host por defecto `2222`, modo por defecto `console`. Valida que la imagen exista. Imprime avisos recordando que sin KVM el boot va a tardar. Intenta primero `-drive file=$IMG,format=qcow2`; si falla, reintenta con `-cdrom "$IMG"` — cubre tanto imágenes de disco (`.qcow2`) como ISOs de instalación/boot. Sin ningún flag de aceleración en ningún modo.
  - **Modo `console`** (default) — `-nographic` (consola serie redirigida a la terminal, sin ventana gráfica).
  - **Modo `vnc`** — reemplaza `-nographic` por `-vnc 127.0.0.1:2` (servidor VNC de QEMU en el puerto **5902**, display `:2` — el visor VNC nativo de Kairos ya usa `:1`/5901 para Mini PC/Entorno, así que QEMU usa el display siguiente para no colisionar). Sin `-nographic`, la salida real pasa a ser el framebuffer gráfico servido por VNC.

### Registry

`registry_install qemu "1.0.0" "kvm=false" "mode=tcg_software" "user_mode=<true|false>" "system_mode=<true|false>"` — los dos últimos campos se calculan en el momento con verificación del binario, reflejando el resultado real de la instalación.

## 4. Detección de estado — no es un módulo con proceso de fondo

QEMU no tiene un daemon persistente que arrancar/detener desde un switch. Es una caja de herramientas: se instala una vez y después se invoca bajo demanda (ejecutar un binario, o arrancar una VM que corre en la terminal mientras el usuario la usa).

`QemuFragment.refreshStatus()` chequea instalación real en un hilo separado, sin depender del registry:
- **user mode**: presencia de `qemu-x86_64` o `qemu-arm`
- **system mode**: presencia de `qemu-system-x86_64`

Cada resultado se pinta en su propia fila de estado. Si el módulo entero no está instalado, el Fragment muestra directamente la pantalla "Módulo no instalado".

## 5. Pantalla de la app (`QemuFragment.kt`)

Seis cards:

**ESTADO** — filas `qemu-user (x86_64/arm)`, `qemu-system (headless)` + fila fija informativa "Aceleración KVM: No disponible sin root".

**EJECUTAR BINARIO DE OTRA ARQUITECTURA** — un campo para la ruta del binario + dos botones "Ejecutar como x86_64 (terminal)" / "Ejecutar como arm (terminal)", que abren una sesión de terminal real.

**IMÁGENES DE DISCO (qemu-img)** — fila informativa con la carpeta `~/qemu_images` + tres botones:
- "Crear imagen de disco" — diálogo con nombre y tamaño; corre `qemu-img create -f <qcow2|raw> '<target>' <size>` (formato se decide por extensión).
- "Listar imágenes" — lista archivos en `~/qemu_images` con extensión `qcow2`/`img`/`iso`/`raw`, seleccionar uno completa el campo de ruta de la VM.
- "Info / redimensionar / convertir imagen" — elige imagen → `qemu-img info`/`resize`/`convert`. El diálogo de resize avisa explícitamente que achicar puede destruir datos si el filesystem interno no encoge también — agrandar siempre es seguro.

**Normalización de tamaño**: la función normaliza formatos como `"1gb"`/`"1GB"`/`"1g"`/`"1G"` → `"1G"`, `"512mb"`/`"512M"` → `"512M"` — el formato real que acepta `qemu-img create` es un sufijo de una sola letra (`K`/`M`/`G`/`T`), sin `b` final; si el texto no matchea el patrón reconocible, se devuelve tal cual.

**IMÁGENES PARA DESCARGAR** — catálogo curado de 3 imágenes de sistema operativo x86_64 reales, con URLs y tamaños confirmados:

| Imagen | Archivo | Tamaño confirmado | URL |
|---|---|---|---|
| Alpine Linux 3.24 (virt, x86_64) | `alpine-virt-3.24.1-x86_64.iso` | ~66 MB | `https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-virt-3.24.1-x86_64.iso` |
| Debian 12 (Bookworm) cloud, amd64 | `debian-12-generic-amd64.qcow2` | ~427 MB | `https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-amd64.qcow2` |
| Ubuntu 22.04 LTS (Jammy) cloud, amd64 | `ubuntu-22.04-server-cloudimg-amd64.img` | ~700 MB | `https://cloud-images.ubuntu.com/releases/22.04/release/ubuntu-22.04-server-cloudimg-amd64.img` |

Alpine bootea directo a login root sin cloud-init (ideal para probar la VM headless rápido); las imágenes cloud de Debian/Ubuntu están pensadas para `cloud-init` y pueden requerir un ISO de semilla con usuario/contraseña para loguear por consola — la app no automatiza esa parte.

La descarga usa el mismo patrón que el resto de descargadores de la app (progreso real con %, velocidad, ETA cada 500ms, timeout de conexión 15s/lectura 30s), sin la validación de magic bytes GGUF (acá el archivo es `.iso`/`.qcow2`, no un modelo). Descarga a un temporal y renombra al nombre final solo si la descarga se completó.

**VM HEADLESS (sin KVM — TCG software, lento)** — campo de ruta de imagen + campo de RAM en MB (default 512), botón "Elegir imagen de la lista" y "Arrancar VM…" que abre un selector de **modo de visualización**:

| Opción | Qué hace |
|---|---|
| "Consola / SSH" (default) | Corre `run_vm.sh '<path>' <ram> 2222 console` en terminal, con aviso "Arrancando sin KVM — el boot va a tardar (TCG software)" |
| "VNC" | Corre `run_vm.sh '<path>' <ram> 2222 vnc` en terminal (fire-and-forget), y tras un margen de unos segundos (tiempo para que QEMU levante el servidor) abre automáticamente el visor VNC nativo apuntado al puerto 5902 |

La opción "X11 nativo" (sobre el X11 embebido) no se implementó — depende de si el paquete headless soporta GTK/SDL, algo no confirmado.

**MANTENIMIENTO** — "Actualizar QEMU" invoca la actualización estándar del módulo.

## 6. Limitaciones de rendimiento

- **Sin `/dev/kvm`** (Android no lo expone a apps sin root) → `qemu-system` corre en **TCG** (traducción de instrucciones por software), no acelerado por hardware.
- Consecuencia práctica: el boot de una VM headless tarda minutos, no segundos.
- Explícitamente no recomendado para una VM gráfica pesada (ej. Windows) — sería prácticamente inusable en este entorno.
- `qemu-user` no sufre esta limitación — no es una VM completa, solo traduce instrucciones de un binario estático al vuelo, uso real y confiable sin root.

## 7. Comandos de referencia rápida

```bash
# Correr un binario estático x86_64 en un teléfono ARM64
bash ~/scripts/qemu/run_user.sh x86_64 ./mi_binario_x86_64 [args...]

# Correr un binario estático ARM en cualquier host soportado
bash ~/scripts/qemu/run_user.sh arm ./mi_binario_arm

# Crear una imagen de disco vacía
qemu-img create -f qcow2 ~/qemu_images/disco1.qcow2 4G

# Arrancar una VM headless en modo consola (sin gráficos, sin KVM — lento)
bash ~/scripts/qemu/run_vm.sh ~/qemu_images/alpine-virt-3.24.1-x86_64.iso 512

# Arrancar una VM headless en modo VNC (servidor QEMU en 127.0.0.1:5902)
bash ~/scripts/qemu/run_vm.sh ~/qemu_images/alpine-virt-3.24.1-x86_64.iso 512 2222 vnc
```

## 8. Registry (`~/.android_server_registry`)

```
qemu.installed=true
qemu.version=1.0.0
qemu.kvm=false
qemu.mode=tcg_software
qemu.user_mode=<true|false>
qemu.system_mode=<true|false>
```

`user_mode`/`system_mode` reflejan el resultado real de la instalación al momento de correr el script (no se re-verifican después salvo que el usuario vuelva a correr el módulo) — la UI en cambio sí re-chequea en vivo cada vez que se abre la pantalla, así que puede divergir del registry si, por ejemplo, el usuario desinstaló un paquete manualmente desde la terminal.
