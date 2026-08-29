# PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md — Rediseño de Estudio: multi-proyecto, temas y LSP

Este documento describe el proceso de rediseño de Estudio a partir de una revisión de qué
funcionalidades ya existían, qué aportaban otros editores/IDEs Android open source como
referencia, y qué se implementó como resultado.

## Punto de partida

Antes de este rediseño, Estudio ya tenía una base sólida: chat de IA con proveedor configurable,
panel de Git, paleta de comandos, motor de búsqueda propio, configuración de editor (incluido un
selector de tema de sintaxis TextMate), barra de teclado virtual con atajos, panel de log de
build, y sistema de pestañas. Las dos limitaciones reales identificadas fueron:

1. Estudio solo podía tener **un proyecto abierto a la vez** — abrir un proyecto nuevo
   reemplazaba al anterior, sin ninguna noción de "proyecto en segundo plano".
2. Solo el **texto del código** tenía un selector de tema propio (vía el tema de sintaxis
   TextMate) — el resto de la interfaz de Estudio (barras de herramientas, fondos de panel,
   diálogos) heredaba el tema general de la app, sin un tema independiente propio.

## Qué aportan otros editores/IDEs Android open source como referencia

- Los forks activos de AndroidIDE demuestran una **Gradle Tooling API real** funcionando en
  dispositivo (proceso Java separado comunicado por sockets), **plantillas de proyecto** por tipo
  de Activity, y un **selector de IA multi-proveedor "trae tu propia clave"** — patrón ya
  replicado en la pantalla de configuración de proveedor de IA de Estudio.
- Un parser de salida de compilador (para javac/aapt2/analizadores de Kotlin) que traduce texto
  crudo de build a diagnósticos estructurados (severidad, archivo, línea, columna, mensaje) —
  patrón aplicable al panel de log de build de Estudio, que hasta entonces mostraba texto sin
  estructurar.
- Un editor de código con **conexión LSP real** (protocolo de servidor de lenguaje estándar)
  cableada al editor gráfico — el hallazgo más relevante para dar autocompletado real por
  lenguaje, más allá del resaltado de sintaxis léxico que ya existía.

Ningún proyecto de referencia resuelve de forma directamente reutilizable el soporte de **varios
proyectos abiertos simultáneamente** — esa parte se diseñó desde cero para Estudio.

## Soporte multi-proyecto — implementado

Se extrajo el estado de "proyecto actual" (antes campos sueltos del Fragment principal de
Estudio) a una sesión de proyecto propia, que encapsula la carpeta del proyecto, sus pestañas
abiertas y su posición de scroll. Estudio ahora sostiene una lista de hasta **3 sesiones de
proyecto** simultáneas en vez de un único proyecto.

- Un selector de proyecto (fila de chips) aparece sobre las pestañas de archivo cuando hay 2 o
  más proyectos abiertos — tocar un chip cambia la sesión activa sin destruir las demás, que
  quedan en memoria como pestañas de navegador.
- La gestión de proyectos recientes y de restauración de sesión se extendió para persistir la
  lista completa de sesiones abiertas, no solo la última.
- Al abrir un cuarto proyecto, la sesión menos usada recientemente se cierra automáticamente.
- En vez de preguntar por cada pestaña con cambios sin guardar al cambiar de proyecto, se optó por
  **guardar automáticamente y en silencio** todas las pestañas modificadas antes del cambio — más
  simple y más seguro por defecto (nunca se pierde una edición sin guardar, a costa de no poder
  "descartar cambios" explícitamente al cambiar de proyecto).

**Pendiente real**: el límite de 3 proyectos simultáneos es un valor propuesto, no medido contra
el uso real de memoria en un dispositivo de gama media — validar con las herramientas de
diagnóstico de memoria de Android antes de subir el límite.

## Diagnósticos de compilación clickeables — implementado

El panel de log de build ya resaltaba las líneas por severidad; se agregó la capacidad de tocar
una línea con ubicación de archivo reconocida para abrirla directamente en el editor, en la línea
y columna exactas — usando la misma resolución de ruta real que ya usaba el flujo de "abrir
resultado de búsqueda".

**Limitación conocida**: el mapeo de línea de log a diagnóstico asume que cada línea lógica del
log ocupa una única línea visual en pantalla — una línea de log muy larga que hace salto de línea
visual puede resolver al diagnóstico equivocado al tocarla.

## Sistema de temas independiente para la interfaz de Estudio

**Propuesta de diseño** (no implementada todavía en el momento de escribir este documento):
un tema de interfaz propio para Estudio (barras de herramientas, fondos de panel, diálogos),
separado tanto del tema general de la app como del tema de sintaxis del editor — con una opción
de "sincronizar con el tema de la app" para quien prefiera un único tema global, y opciones
propias de Estudio para quien quiera distinguirlos. El selector de tema de Estudio reutilizaría el
mismo componente visual que el selector de tema general de la app, en vez de crear una segunda
estética de selector.

## Autocompletado real vía LSP — implementado como versión mínima viable

La pieza de mayor esfuerzo del rediseño: Estudio ahora conecta servidores de lenguaje reales para
dar autocompletado, diagnósticos, hover, ir-a-definición y renombrado de símbolos — no solo
resaltado de sintaxis léxico.

### Decisión de librería

Se optó por usar el módulo LSP oficial de la misma librería de editor (`sora-editor`) que Estudio
ya usa para el resaltado de sintaxis, publicado como paquete independiente en el repositorio
central de Maven — en vez de reimplementar el protocolo JSON-RPC de LSP desde cero, o vendorizar
el código fuente de otro proyecto.

### Lenguajes cubiertos

- **Bash/shell** (`.sh`, `.bash`) vía `bash-language-server`, instalado a través del gestor de
  paquetes de Node.js.
- **Python** (`.py`, `.pyw`) vía `pylsp` (`python-lsp-server`), instalado a través de `pip`.

Elegidos por uso real dentro del propio proyecto: los scripts de módulo de Kairos son bash, y
varias de sus herramientas internas son Python. Kotlin/Java quedó fuera de esta primera versión —
no existe un servidor de lenguaje liviano instalable sin un JDK completo y un modelo de proyecto
Gradle resuelto.

### Instalación silenciosa

El servidor de lenguaje correspondiente se instala en segundo plano, con solo un aviso breve (sin
diálogo bloqueante), la primera vez que se abre un archivo del lenguaje soportado con el runtime
necesario ya presente. Si el runtime del lenguaje del servidor falta, se avisa una vez por sesión
sin reintentar en cada tecla siguiente.

### Hover, ir a definición y renombrar — cableados a un menú táctil

La librería LSP subyacente soporta estas tres operaciones a nivel de protocolo, pero no las
cablea a ninguna interfaz táctil por sí sola (su wiring de fábrica asume mouse/hover, no toque).
Se implementó un menú contextual propio que aparece con **toque prolongado** sobre el editor,
cuando el archivo activo tiene un servidor de lenguaje soportado, con tres opciones:

- **Info (hover)** — muestra la información de tipo/documentación en el punto tocado.
- **Ir a definición** — resuelve la ubicación real del símbolo y abre ese archivo en la línea
  correspondiente, reusando el mismo mecanismo de navegación que los diagnósticos de build
  clickeables.
- **Renombrar símbolo** — pide confirmación con una vista previa (cuántos archivos, cuántos
  cambios) antes de tocar cualquier archivo; si algún archivo afectado no se puede resolver a una
  ubicación real (por ejemplo, una dependencia externa fuera del proyecto abierto), no se escribe
  ningún archivo, para evitar dejar un renombrado aplicado a medias.

### Limitaciones reales del soporte LSP

1. Solo funciona con proyectos cuya carpeta resuelve a una ruta real de almacenamiento, no con
   proyectos abiertos únicamente a través de un proveedor de almacenamiento externo.
2. Estudio comparte un único editor de código visible entre pestañas — la pestaña en segundo
   plano sigue conectada al servidor de lenguaje, pero sin diagnósticos en vivo hasta volver a
   ella.
3. Búsqueda de referencias y formateo automático todavía no tienen interfaz propia, aunque la
   librería los soporta a nivel de protocolo.
4. No se confirmó en todos los casos si un servidor de lenguaje dado anuncia soporte para cada
   operación (por ejemplo, si el servidor de bash soporta "ir a definición") — el comportamiento
   esperado es que, en ese caso, se avise "no soportado" en vez de fallar en silencio.

## Funcionalidades evaluadas y explícitamente no implementadas

- **Minimap del editor**: no se pudo confirmar con certeza que la versión de la librería de editor
  en uso exponga esta funcionalidad de forma nativa — queda pendiente de confirmación.
- **Reordenar pestañas por arrastre**: el sistema de pestañas de Estudio usa un componente de
  pestañas estándar de Material Design, no una lista reordenable — implementarlo requeriría
  migrar el sistema de pestañas a otro componente, un refactor mayor fuera de alcance de este
  rediseño.
- **Diff visual por bloque en el panel de Git**: identificado como mejora deseable pero no
  implementado todavía.
- **Agente de IA propio con ejecución de herramientas dentro del editor**: evaluado y
  descartado — choca con la decisión de diseño de Kairos de delegar la ejecución de tareas
  complejas de IA a CLIs externos ya especializados en eso, en vez de reimplementar un agente
  propio dentro del editor.
