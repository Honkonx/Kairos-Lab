# udocker — Módulo de Kairos

## 0. Módulo con pantalla dedicada

udocker es un módulo standalone independiente con su propia pantalla en la app, además de
usarse internamente como pieza de otros módulos (n8n en su variante udocker, y Entorno). Instala
el paquete `udocker` + `udockertools` (usando mirrors fijos, ya que el origen dinámico de la
instalación de `udockertools` falla seguido en redes móviles/CGNAT), fuerza el modo de ejecución
`P2`, y genera wrappers de línea de comandos (bajar imagen, correr, listar, borrar) para no
tener que memorizar los flags de udocker.

La pantalla dedicada de la app incluye:
- Lista de contenedores — tocar uno abre acciones: abrir terminal (con diálogo opcional de
  montajes de volumen y variables de entorno), inspeccionar, exportar a `.tar`, eliminar.
- Instalar distro — grid con imágenes reales de Docker Hub (Alpine/Ubuntu/Debian) más una
  opción para cualquier referencia de imagen manual.
- Terminal en contenedor — selector de contenedores ya creados.
- Importar imagen desde `.tar`.
- Lista de imágenes descargadas — tocar una abre: inspeccionar, guardar a `.tar`, eliminar.

**Permisos:** ninguno especial de Android — corre 100% en espacio de usuario de Termux vía
PRoot, sin root, sin permisos adicionales al asistente de configuración general.

## 1. ¿Qué es udocker?

udocker es una herramienta en Python que permite ejecutar imágenes Docker sin root, sin kernel
modificado y sin daemon. Funciona envolviendo el contenedor en un entorno tipo chroot usando
PRoot — el mismo motor que usa proot-distro en Termux.

**No es Docker real.** Es un emulador de contenedores en espacio de usuario.

### Cómo funciona internamente

```
Termux (Android)
  └── udocker (Python)
        └── PRoot (interceptación de syscalls vía ptrace)
              └── Rootfs del contenedor en ~/.udocker/containers/<id>/ROOT/
```

El contenedor **no tiene su propio namespace de red**. Corre directamente sobre la red del host
(Termux/Android). Esta es la diferencia más importante respecto a Docker real.

### Modo soportado en Termux

Según la documentación oficial de udocker, en Termux/Android solo el modo **P (PRoot)** está
soportado. Los modos F (Fakechroot), R (runc) y S (Singularity) requieren namespaces de kernel
que Android no expone sin root.

## 2. Red y networking

### udocker en modo PRoot no tiene aislamiento de red

A diferencia de Docker real, udocker con PRoot **comparte el stack de red del host**:

- El contenedor ve `localhost` exactamente como Termux lo ve.
- `127.0.0.1` dentro del contenedor = `127.0.0.1` de Android.
- No hay bridge network, no hay NAT, no hay namespace separado.
- Los puertos que expone el contenedor se mapean directamente al host.

**Consecuencia práctica:** todo servicio corriendo en Termux es accesible desde dentro del
contenedor usando `localhost` o `127.0.0.1`, y viceversa.

## 3. Comunicación con otros servicios del stack

### Ollama

Un servicio dentro de udocker puede llamar a Ollama (corriendo en Termux) usando
`localhost:11434`, siempre que Ollama escuche en `0.0.0.0` y no solo en `127.0.0.1`:

```bash
export OLLAMA_HOST=0.0.0.0
ollama serve
```

### SQLite

SQLite es un archivo. Compartirlo entre udocker y Termux se hace montando un volumen:

```bash
udocker run \
  --volume=/data/data/com.termux/files/home/data:/data \
  <imagen>
```

**Regla crítica en Android:** nunca usar `/tmp/` — suele ser noexec en versiones recientes.
Usar siempre rutas bajo `$HOME` (`/data/data/com.termux/files/home/`).

## 4. Limitaciones de udocker en Termux/Android

### Heredadas de PRoot

| Limitación | Impacto práctico |
|-----------|---------------------|
| Sin puertos < 1024 | Se remapean automáticamente (ej. `:80` → `:2080`) |
| Sin `su` ni cambio real de UID | Los procesos corren como usuario normal |
| Sin mount de filesystems | Usar `--volume` en su lugar |
| Sin `docker-compose` | Solo contenedores independientes |
| Sin red entre contenedores | Usar `localhost` para comunicación |
| Sin `docker exec` en un proceso vivo | Solo acceso vía volúmenes o HTTP |

### Específicas de Android

- **`/tmp/` puede ser noexec:** nunca escribir scripts ahí; usar `$HOME/`.
- **Puertos < 1024:** se remapean automáticamente.
- **`$HOME` en `--volume`:** udocker no expande `$HOME` — usar siempre la ruta absoluta.

### Lo que udocker no puede hacer sin root

- Docker Compose (requiere un daemon)
- Red entre contenedores (bridge/overlay networking)
- Volúmenes gestionados de Docker (usar `--volume` manual)
- Contenedores privilegiados
- Paso directo de GPU (GPU passthrough)
- `docker exec` en un contenedor vivo

## 5. Comandos de referencia rápida

### Instalación y setup

```bash
pkg install udocker
udocker install
export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)
```

### Gestión de imágenes y contenedores

```bash
udocker pull <imagen>              # Descargar imagen
udocker create --name=<nombre> <imagen>
udocker ps                         # Listar contenedores
udocker images                     # Listar imágenes
udocker rm <nombre>                # Eliminar contenedor
udocker inspect -p <nombre>        # Ruta del contenedor en disco
udocker export -o <archivo.tar> <nombre>   # Exportar contenedor
udocker save -o <archivo.tar> <imagen>     # Guardar imagen
udocker import <archivo.tar> <repo/imagen:tag>  # Importar imagen
```

### Ejecutar contenedores

```bash
udocker run \
  --publish=<host>:<contenedor> \
  --volume=/ruta/absoluta/host:/ruta/contenedor \
  --env=VARIABLE=valor \
  <imagen>

# Shell interactivo
udocker run --user=root <imagen> /bin/bash

# Comando único
udocker run <imagen> <comando> --version
```

### Cambiar modo de ejecución

```bash
udocker setup n8n                       # Ver modo actual
udocker setup --execmode=P2 n8n         # Más lento, más compatible
udocker setup --execmode=P1 n8n         # Default, más rápido
```

## 6. Solución de problemas

**Error "invalid host volume path":** udocker no expande `$HOME` — usar siempre la ruta absoluta
(`/data/data/com.termux/files/home/...`).

**"this container exposes privileged TCP/IP ports":** advertencia informativa, no es un error
fatal — los puertos por debajo de 1024 se remapean automáticamente.

**Un servicio arranca pero no se puede conectar:** verificar que escuche en todas las interfaces
(`0.0.0.0`, no solo `127.0.0.1`) y comprobar el puerto con `ss -tlnp`.

**Contenedor no arranca (falla el modo P1):** probar con `udocker setup --execmode=P2` o exportar
`UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)` explícitamente.

**Los datos no persisten:** verificar permisos del directorio host montado como volumen y
confirmar que el `--volume` usa una ruta absoluta.

## 7. Estructura de archivos

```
$HOME/
├── .udocker/
│   ├── bin/                    # Binarios de udocker (proot, etc.)
│   ├── containers/
│   │   └── <uuid>/
│   │       ├── ROOT/           # Rootfs del contenedor
│   │       └── container.json  # Metadata
│   └── repos/                  # Imágenes descargadas (capas)
└── scripts/udocker/            # Wrappers: pull.sh, run.sh, list.sh, rm.sh
```

## 8. ¿udocker o una distro proot completa?

### Comparativa orientativa

| Factor | Servicio en distro proot completa | Servicio en udocker |
|--------|--------------------|----|
| Instalación | Más lenta (instalación desde fuente/paquetes) | Más rápida (descarga de imagen ya armada) |
| Versión | Fijada en la instalación | Siempre la que trae la imagen `latest` |
| Actualización | Reinstalación del paquete dentro de la distro | `udocker pull` + recrear el contenedor |
| RAM | Mayor overhead (distro completa) | Menor overhead (solo el contenedor) |
| Personalización | Alta (acceso a un sistema completo) | Menor (limitada a la imagen) |

### Recomendación general

- **udocker** — instalación más simple, imagen oficial siempre actualizada, menos overhead.
- **Distro proot completa** — cuando se necesita personalización profunda o el stack ya está
  configurado ahí.
