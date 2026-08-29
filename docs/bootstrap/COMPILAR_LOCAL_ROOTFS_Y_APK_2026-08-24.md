# Compilar el rootfs y el APK localmente, sin depender de GitHub Releases

El pipeline de integración continua para el rootfs embebido (generación → publicación como
Release → descarga en tiempo de build del APK) existe porque un flujo de CI necesita un lugar
intermedio de donde bajar el artefacto entre dos jobs separados que corren en máquinas efímeras.
**Para compilar en una máquina de desarrollo local, ese paso intermedio no hace falta** — se
puede generar el rootfs y embeberlo directamente sin publicar nada ni depender de tokens de
autenticación.

## Concepto importante: qué SÍ y qué NO embebe el rootfs

El rootfs embebido **no** incluye los módulos de la aplicación (agentes de IA, servicios,
utilidades) — esos siguen descargándose bajo demanda cuando el usuario los activa. Lo que embebe
son únicamente los paquetes base del sistema (Git, Python, Node.js, herramientas de compilación,
utilidades del sistema) que el asistente de primer arranque necesita instalar antes de dejar el
entorno listo para usar.

## Por qué no hace falta pasar por un repositorio remoto para un build local

- El generador del rootfs es Python 3 puro (usa solo librería estándar — sin `dpkg-deb` ni
  dependencias de compilación) — corre igual en cualquier entorno Linux/WSL que en un runner de
  CI.
- La tarea de compilación que descarga el rootfs desde un repositorio remoto solo se activa si
  una variable de entorno específica está presente; si no lo está, el sistema de build nunca
  toca el artefacto de rootfs, ni para descargarlo ni para tocarlo (con una excepción: una
  limpieza completa del build sí lo borra si existe, ver más abajo).
- El instalador del rootfs, en tiempo de ejecución en el dispositivo, decide si usarlo
  únicamente comprobando si el archivo `.tar.xz` existe entre los recursos empaquetados del
  APK — no depende de ningún flag de compilación. **Si el archivo está ahí, la app lo usa, sin
  importar cómo llegó.**

Conclusión: alcanza con generar el `.tar.xz` a mano y copiarlo directo a la carpeta de recursos
de la app antes de compilar — sin publicar nada, sin token de acceso, sin tocar releases.

## Paso 1 — Generar el rootfs

Requisitos: Python 3 (normalmente ya presente en cualquier distribución Linux/WSL moderna),
`tar`, y `xz-utils` (instalar si falta el binario `xz`).

```bash
# Desde la raíz del repositorio
mkdir -p /tmp/rootfs_staging /tmp/rootfs_cache
python3 tools/rootfs/build_rootfs.py /tmp/rootfs_staging /tmp/rootfs_cache

# Empaquetar (mismos comandos que usa el pipeline de CI, sin el paso de publicación)
tar -cJf /tmp/kairos-rootfs-aarch64.tar.xz -C /tmp/rootfs_staging .
sha256sum /tmp/kairos-rootfs-aarch64.tar.xz
ls -lh /tmp/kairos-rootfs-aarch64.tar.xz
```

El script debería listar la cantidad de paquetes raíz declarados, resolver la clausura
transitiva de dependencias (varios cientos de paquetes, según la lista vigente), descargar cada
`.deb` con verificación de checksum, y terminar sin errores. Si algún paquete raíz no aparece en
el índice de paquetes, es un nombre mal escrito en la lista de paquetes — un hallazgo real a
corregir antes de continuar.

## Paso 2 — Compilar el APK sin rootfs embebido (variante liviana)

Confirmar primero que el artefacto de rootfs **no** existe todavía en la carpeta de recursos de
la app (si quedó de una compilación anterior, eliminarlo). Después, compilar de forma normal con
las variables de entorno habituales del proyecto.

Este es el APK de referencia — sin rootfs embebido, sirve como base de comparación de tamaño y
para confirmar que generar el rootfs en el Paso 1 no rompió nada del build normal.

## Paso 3 — Compilar el APK con rootfs embebido

Copiar el `.tar.xz` generado en el Paso 1 al lugar exacto que el instalador espera dentro de la
carpeta de recursos de la app, y compilar con el mismo comando de siempre — no hace falta setear
ninguna variable de entorno adicional ni token de acceso: esas variables solo controlan la
**descarga automática** desde un repositorio remoto, no si un archivo ya presente en la carpeta
de recursos se empaqueta (eso lo hace el sistema de build siempre, como cualquier otro recurso
del módulo de la app).

Vale la pena medir el tamaño real del APK resultante en este paso y compararlo contra el del Paso
2, para tener una cifra concreta de cuánto pesa el rootfs embebido en el APK final (el `.tar.xz`
en sí más el overhead de empaquetado).

## Paso 4 — Verificación en un dispositivo real

Instalar el APK compilado en un dispositivo físico y abrir el asistente de primer arranque (o
reinstalar limpio si ya existía una instalación previa) para confirmar que el rootfs embebido se
extrae e instala sin necesidad de red. Compilar sin errores no es suficiente para dar por cerrado
este mecanismo — la verificación real en dispositivo es la que confirma que el flujo completo
funciona de punta a punta.

## Sobre el tamaño del APK y las variantes de arquitectura

Un build "universal" (que empaqueta binarios nativos para las cuatro arquitecturas de Android
soportadas — ARM64, ARM, x86, x86_64) es notablemente más pesado que uno restringido a una sola
arquitectura, porque incluye el bootstrap mínimo de Termux y las bibliotecas nativas del proyecto
para cada una de las cuatro. El rootfs embebido en sí es exclusivo de una arquitectura (ARM64,
que es la que usan los dispositivos Android reales soportados), así que no es el rootfs el que
crece con una variante universal — es el resto del contenido nativo del APK. Restringir el build
a una sola arquitectura debería producir un ahorro real y medible frente al build universal.

## Qué NO hace falta para este flujo

- **No hace falta un entorno de contenedores** — el generador del rootfs no compila nada, solo
  descarga paquetes binarios ya compilados del repositorio oficial de Termux.
- **No hace falta ningún token de autenticación** ni publicar ningún artefacto remoto — eso solo
  es necesario para que un pipeline de CI (que corre en una máquina efímera sin el repositorio ya
  en disco) pueda pasar el artefacto entre dos jobs separados.
- **No hace falta recompilar los paquetes de Termux desde su código fuente** — eso solo sería
  necesario para un escenario mucho más profundo (cambiar la identidad de paquete de la
  aplicación), fuera del alcance de este mecanismo.
