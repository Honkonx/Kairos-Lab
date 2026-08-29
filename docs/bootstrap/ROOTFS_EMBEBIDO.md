# Rootfs embebido — mecanismo real

Kairos puede embeber en el propio APK un conjunto de paquetes base de Termux (el "rootfs" del
asistente de primer arranque), para que el wizard de instalación inicial no dependa de red móvil
o Wi-Fi lenta la primera vez que se abre la app. Este documento describe el mecanismo completo:
de dónde salen los paquetes, cómo se empaquetan, cómo se instalan en el dispositivo y qué
decisiones de diseño llevaron al esquema actual.

## Qué NO es el rootfs embebido

Es importante separar dos capas que se prestan a confusión:

| Capa | Qué instala | De dónde sale | Cuándo corre |
|---|---|---|---|
| **Bootstrap mínimo** | `bash`, `dpkg`, `apt`, coreutils — lo mínimo para que exista `$PREFIX` y `apt` funcione | Zip oficial de `termux-packages`, descargado y verificado por checksum en tiempo de build, compilado dentro de una librería nativa del APK | Primer arranque, siempre — no hay instalación de Kairos sin esto |
| **Rootfs embebido** | ~190 paquetes adicionales (`git`, `python`, `nodejs-lts`, herramientas de compilación, utilidades multimedia) que el asistente de primer arranque necesita | Un artefacto propio de Kairos, generado a partir del índice público de `packages.termux.dev` | Wizard de instalación, después del bootstrap mínimo |

El rootfs embebido **no** incluye los módulos de la aplicación (Ollama, n8n, agentes de IA,
etc.) — esos se siguen descargando bajo demanda cuando el usuario los activa, exactamente igual
con o sin rootfs embebido.

## Por qué no simplemente "extraer y copiar" los paquetes

La primera versión de este mecanismo extraía los `.deb` en el pipeline de build y copiaba los
archivos ya descomprimidos directo a `$PREFIX` en el dispositivo. Es el enfoque más rápido, pero
tiene un problema real: `dpkg`/`apt` en el dispositivo nunca se enteran de que esos paquetes
están "instalados". Cualquier comprobación de actualizaciones (`apt list --upgradable`) o de
paquetes ya presentes deja de funcionar para todo lo que llegó por esa vía, y cualquier script
que dependa de `dpkg -l`/`dpkg -L` para saber qué hay instalado queda ciego a esos paquetes.

**Diseño adoptado**: el pipeline de generación descarga los `.deb` reales tal cual (sin
extraerlos) y los empaqueta en un único `.tar.xz`. En el dispositivo, ese `.tar.xz` se extrae a
una carpeta temporal y se instala con `apt install -y <lista de .deb>` de verdad — el mismo
mecanismo que usaría una instalación manual, solo que sin necesidad de red porque los `.deb` ya
están en el dispositivo. El resultado: `dpkg`/`apt` quedan con el registro correcto y las
comprobaciones de actualizaciones funcionan con las herramientas normales de Termux, sin inventar
ningún sistema paralelo de bookkeeping.

Se evaluó también un enfoque híbrido — pre-generar en el pipeline de build tanto los archivos
extraídos como los fragmentos correspondientes de la base de datos de `dpkg`
(`/var/lib/dpkg/status`, listas de archivos) para copiarlos directamente sin invocar `apt` en el
dispositivo. Es una optimización real y válida a futuro, pero no se implementó: antes de invertir
en ella hacía falta confirmar en la práctica si el tiempo de `apt install` sobre ~190 paquetes
locales era realmente un problema perceptible, y resultó no serlo.

## Generación del rootfs (`build_rootfs.py`)

Script en Python 3 puro (solo librería estándar — `urllib`, `gzip`, `hashlib`), sin ninguna
dependencia externa ni necesidad de `dpkg-deb`. Flujo:

1. **Descarga el índice de paquetes** (`Packages.gz`) del repositorio público
   `packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/`.
2. **Parsea el formato de control de `apt`** (bloques separados por línea en blanco, campos
   `Clave: valor`) con un parser propio. Para el campo `Depends:`, cuando una dependencia ofrece
   varias alternativas (`pkgA | pkgB`), el resolutor prioriza la alternativa que ya está en la
   lista de paquetes pedidos explícitamente — evita que una dependencia transitiva ambigua
   arrastre un paquete que entra en conflicto con lo que ya se pidió a propósito (ver la sección
   de incidentes conocidos más abajo).
3. **Resuelve la clausura transitiva de dependencias** con una búsqueda en anchura a partir de
   una lista canónica de paquetes raíz. Un paquete raíz que no aparece en el índice es un error
   duro (normalmente indica un nombre mal escrito); una dependencia transitiva ausente es solo
   una advertencia, porque puede tratarse de un paquete virtual o de algo ya provisto por el
   sistema base.
4. **Descarga cada `.deb`** verificando el SHA-256 contra el que publica el propio índice de
   `apt` (nunca un hash escrito a mano) — si no coincide, el build falla. Los archivos ya
   descargados con el hash correcto no se vuelven a bajar entre corridas.
5. **Copia los `.deb` sin extraer** a la carpeta de salida y escribe un `manifest.json`
   (paquete → versión) para trazabilidad.

El propio pipeline (no el script) empaqueta la carpeta resultante en un `.tar.xz` y calcula el
checksum publicado junto al artefacto.

### Paquetes cubiertos y su alcance real

La lista de paquetes raíz refleja los pasos de instalación base del asistente de primer
arranque, agrupados por etapa: un grupo "core" (Python, Node.js LTS, Git, herramientas de red,
SSH, `proot`/`proot-distro`, utilidades de compresión, SQLite, etc.), un grupo de herramientas de
compilación (`build-essential`, `clang`, `make`, Rust, `pkg-config`, OpenSSL) y un grupo de
utilidades multimedia/GPU (FFmpeg, librerías de imagen, drivers Vulkan genéricos, utilidades de
sistema).

**Limitación deliberada, documentada desde el diseño original**: los paquetes de soporte glibc
(usados por binarios prebuilt que no corren directamente sobre Bionic) viven en un repositorio
APT completamente distinto al índice principal (`termux-glibc`, no `termux-main`), y el generador
actual solo consulta un único índice. Cubrir esos paquetes exigiría un segundo fetch de índice y
una segunda resolución de dependencias — es una extensión real del script, no un simple agregado
de líneas a la lista de paquetes, y no está implementada. El impacto práctico es acotado: ese
paso de instalación sigue funcionando con descarga normal la primera vez, simplemente sin el
beneficio de "sin red" que sí tienen los demás grupos de paquetes.

## Instalación en el dispositivo

En tiempo de ejecución, el instalador decide si usar el rootfs embebido comprobando únicamente si
el artefacto `.tar.xz` está presente entre los recursos empaquetados del APK — no depende de
ningún flag de compilación. Si está presente, lo extrae con una librería Java de
descompresión/desempaquetado real (sin invocar binarios externos de `tar`), instala los `.deb`
con `apt install -y` y, si todo sale bien, deja pre-marcados los checkpoints correspondientes a
cada grupo de paquetes en el archivo de progreso del asistente de instalación — de forma que ese
asistente los detecta ya resueltos y los salta con su propio mecanismo existente, sin ningún
cambio de lógica en él.

Si la instalación del rootfs embebido falla por cualquier motivo (artefacto ausente, `.deb`
corrupto, error de `apt`), el fallo es silencioso y no bloqueante: simplemente no se marca ningún
checkpoint y el asistente de instalación continúa con la descarga normal paquete por paquete, vía
red.

## Dos variantes de APK

- **APK sin rootfs embebido** (variante liviana): el asistente de instalación descarga los
  paquetes bajo demanda, con red, como en un Termux normal.
- **APK con rootfs embebido**: incluye el `.tar.xz` como recurso del APK; el asistente de
  instalación lo detecta y lo usa automáticamente, sin red, para los grupos de paquetes que
  cubre.

Ambas variantes comparten exactamente el mismo código de instalación — la única diferencia es si
el artefacto está o no presente en el paquete de la aplicación.

## Incidentes reales resueltos durante el desarrollo

Documentados aquí porque son útiles para cualquiera que quiera adaptar o depurar este mecanismo:

- **Variables de entorno del proceso hijo armadas a mano**: una primera versión de la rutina que
  invoca al script de configuración post-rootfs construía las variables de entorno del proceso
  (`HOME`, `PREFIX`, `PATH`, etc.) manualmente en lugar de usar el helper compartido del resto de
  la app, y terminaba usando el `HOME` del proceso Android en vez del `HOME` real de Termux —
  causaba un error de "no se puede ejecutar bash". Corregido reutilizando el helper existente.
- **Resolución de binarios por nombre relativo poco confiable justo después de extraer el
  bootstrap**: invocar `apt`/`bash` por nombre (dependiendo de que `PATH` ya esté bien resuelto)
  fallaba de forma intermitente inmediatamente después de que el bootstrap terminaba de
  extraerse, en al menos un dispositivo real. Corregido usando siempre rutas absolutas a los
  binarios, con un reintento y una pequeña pausa como salvaguarda ante una posible demora
  transitoria del sistema de archivos.
- **Conflicto real entre dos paquetes del propio índice de Termux**: durante una prueba de punta
  a punta, `apt install` de los ~190 paquetes falló con un conflicto declarado entre dos variantes
  de Node.js (una LTS y una no-LTS) — ambas terminaban en la clausura de dependencias porque el
  resolutor tomaba ciegamente la primera alternativa de cualquier dependencia con formato
  `pkgA | pkgB`, sin comprobar si la otra alternativa ya era, precisamente, la que se había
  pedido a propósito en la lista de paquetes raíz. Causa raíz corregida en el resolutor de
  dependencias (ver el punto 2 de la sección anterior): ahora prioriza la alternativa que ya
  forma parte de lo pedido explícitamente.
- **Recompresión innecesaria del artefacto por el empaquetador de recursos de Android**: sin una
  exclusión explícita, la herramienta de empaquetado de Android puede recomprimir con DEFLATE un
  archivo `.tar.xz` ya comprimido al incluirlo como recurso — un desperdicio de build y un riesgo
  de rendimiento en tiempo de ejecución para archivos binarios grandes. Se excluyó la extensión
  `.xz` de la recompresión de recursos como medida de higiene, independientemente de si era la
  causa de algún incidente puntual.
- **Script de configuración post-rootfs apuntando a una ruta de biblioteca de funciones
  equivocada**: sin verificación de errores estricta, algunos pasos del script fallaban en
  silencio (comandos de utilidad no encontrados) sin que el fallo se notara a simple vista.
  Corregido apuntando a la ruta real del archivo de funciones compartidas.
- **Timeout en la primera pasada de `apt install`**: en al menos un intento, la primera pasada de
  instalación de los ~190 paquetes tardó varios minutos y venció por timeout (posiblemente un
  prompt de configuración de algún paquete quedando colgado); el reintento automático ya
  existente completó la instalación casi de inmediato porque el trabajo de la primera pasada ya
  había dejado casi todo instalado. No bloqueante gracias al reintento, pero queda como mejora
  pendiente identificar qué paquete específico produce el prompt colgado.

Con todos estos fixes aplicados, el flujo completo — extracción del rootfs embebido sin red,
instalación real vía `apt`, y arranque exitoso del script de configuración posterior — quedó
confirmado funcionando de punta a punta en un dispositivo real.

## Por qué este diseño (resumen de decisiones)

- **Sin parser de `.deb`/`ar` propio en ningún punto crítico**: la generación solo resuelve
  dependencias y descarga; la instalación real la hace `apt` en el dispositivo. La única
  extracción de archivo binario que hace código propio (el `.tar.xz` contenedor) usa librerías de
  descompresión estándar y probadas, no un parser escrito a mano.
- **Checksum publicado junto al artefacto**, no incrustado en el código fuente — evita tener que
  actualizar un hash a mano cada vez que se regenera el rootfs.
- **El script de configuración post-rootfs no necesita ningún cambio de código** — el mecanismo
  de rootfs embebido solo pre-marca los checkpoints que ese script ya sabe leer.
- **Fallback silencioso y no bloqueante** ante cualquier fallo del rootfs embebido — el asistente
  de instalación nunca se queda bloqueado por este mecanismo; en el peor caso, simplemente pierde
  la aceleración "sin red".
