# IDE_EXTERNO.md — Nota histórica: por qué Estudio no es una app Android separada

> Este documento describe una alternativa de diseño que se evaluó y descartó. La arquitectura
> vigente es la descrita en [`IDE_INTEGRADO.md`](IDE_INTEGRADO.md) — léase ese documento primero.
> Este archivo se conserva porque documenta el análisis de proyectos open source de referencia y
> el razonamiento detrás de la decisión final, que sigue siendo relevante para entender por qué
> Estudio está construido como está.

## El planteo original: dos APKs independientes

En una etapa temprana de diseño se consideró que el editor de código fuera un **proyecto Android
completamente independiente**, con su propio `applicationId`, su propio ciclo de build y de
release, sin compartir proceso con Kairos. La relación entre ambos sería puramente opcional y por
descubrimiento en runtime: si Kairos estaba instalado y corriendo un motor de IA local (Ollama o
un servidor compatible con la API de OpenAI), el IDE externo podría usarlo como backend de IA sin
traer su propio motor de inferencia; si no, funcionaría igual con proveedores cloud "trae tu
propia clave" o sin IA. Nunca habría una dependencia dura de compilación ni de runtime entre los
dos APKs.

La comunicación con el entorno Termux de Kairos se plantearía vía el mecanismo estándar de Intent
`RUN_COMMAND` que usan las apps complementarias de Termux — el mismo patrón que usan integraciones
de terceros tipo Tasker/Widget.

## Por qué se descartó

Esta interpretación resultó ser una lectura equivocada de la intención real: el objetivo siempre
fue tener **comunicación directa y en el mismo proceso** con Termux y con los proveedores de IA,
no un puente de Intents entre dos apps instaladas por separado. Un IDE integrado como pantalla más
del mismo APK logra esa comunicación directa de forma nativa; una app externa solo puede
aproximarla con mecanismos indirectos (Intents, sockets a localhost), con limitaciones reales:
depende de un permiso explícito de "permitir apps externas" en la configuración del entorno
Termux, y cada operación pasa por una capa de Intent/BroadcastReceiver en vez de una llamada
directa en el mismo proceso.

El trabajo de código hecho durante esta exploración (editor con resaltado de sintaxis TextMate,
árbol de archivos, sistema de pestañas, integración con Git, búsqueda en proyecto, atajos de
teclado físico, paleta de comandos, selector de tema) no se perdió — es la base que se portó al
diseño integrado descrito en `IDE_INTEGRADO.md`, adaptando únicamente la capa de "cómo se
comunica con Termux" (que se simplificó radicalmente al pasar a ejecución en el mismo proceso).

## Auditoría de proyectos IDE Android de referencia

Como parte de esta investigación se evaluaron varios proyectos Android open source que implementan
un IDE completo (editor + compilación con Gradle) para decidir si convenía partir de un fork en
vez de escribir desde cero.

### AndroidIDE y sus forks

**AndroidIDE** (y forks activos del mismo proyecto) es la referencia más completa encontrada: un
proyecto Android real y compilable por su cuenta, con módulos Gradle propios, que usa la
**Gradle Tooling API real** — un proceso Java separado que corre el build y se comunica por
sockets con la app principal — para compilar proyectos Android directamente en el dispositivo, sin
root. El SDK de Android se gestiona desde una terminal integrada con las herramientas estándar; el
JDK viene embebido en el propio APK. Trae además un wizard de proyecto nuevo con plantillas por
tipo de Activity, un panel de build con logs en tiempo real, un diseñador de UI visual, y un chat
de IA "trae tu propia clave" con varios proveedores cloud.

Es un proyecto real, con desarrollo activo, licenciado bajo **GPLv3** — cualquier fork que se
distribuya hereda esa obligación de licencia (código fuente disponible, misma licencia para el
derivado).

### Otros proyectos evaluados

- Un editor de texto con grammars TextMate ya portados (mismo tipo de licencia GPLv3) — útil como
  referencia de resaltado de sintaxis, pero sin capacidad de compilar proyectos Gradle: es un
  editor, no un IDE completo.
- Un editor web (Monaco) pensado para ejecutar contra un servicio remoto de evaluación de código
  — sin componente Android/Termux real, no aplicable.
- Un proyecto Flutter con un agente de IA propio embebido con ejecución de herramientas y
  sandboxing por ruta de archivo — de stack distinto (Flutter, no Kotlin/Java nativo) y con un
  diseño de agente de IA que choca con el enfoque de Kairos (delegar la ejecución de herramientas
  a CLIs externos ya existentes, en vez de reimplementar un agente propio).

### Conclusión de esa etapa

Se decidió **no vendorizar** ninguno de estos proyectos completos dentro del repositorio (por el
peso del código y las obligaciones de licencia GPLv3 de heredar un módulo entero), y en cambio
construir un editor propio, liviano, desde cero — usando los proyectos de referencia únicamente
como inspiración de patrones de UI y arquitectura, nunca copiando código de aplicación
directamente. Los grammars TextMate (datos de resaltado de sintaxis, no código de aplicación, de
origen MIT estándar) sí se reutilizaron con atribución completa, ya que son datos declarativos, no
lógica de programa.

## Patrón de selección de proveedor de IA "trae tu propia clave"

Uno de los patrones de UI confirmados como útil en esta investigación fue el de una pantalla
dedicada de selección de proveedor de IA, con la clave de API guardada de forma cifrada y separada
por proveedor — el mismo concepto multi-proveedor (varios proveedores cloud + fallback a un motor
de IA local) que ya usa el chat principal de Kairos. Este patrón se implementó en Estudio con
almacenamiento cifrado (`EncryptedSharedPreferences`), a diferencia de algunas de las referencias
auditadas, que guardaban la clave en texto plano.

## Funcionalidades resultantes de esta etapa de exploración

El trabajo de esta etapa terminó produciendo, entre otras cosas, las siguientes piezas — todas
portadas y en uso dentro del diseño integrado actual:

- Editor con resaltado de sintaxis TextMate real para varios lenguajes (Kotlin, Java, Python,
  JavaScript/TSX, XML, JSON, Gradle/Groovy, Shell, Markdown), con temas de color propios.
- Árbol de archivos navegable con operaciones de archivo (crear, renombrar, eliminar, copiar
  ruta), carga perezosa, y filtro de nombre.
- Sistema de pestañas de archivos abiertos con indicador de cambios sin guardar.
- Panel de Git real (estado, commit, push/pull, log, diff) construido armando y parseando la
  salida de comandos `git` estándar.
- Gestión de proyectos recientes y restauración de sesión (qué pestañas estaban abiertas) al
  reabrir la app.
- Buscar/reemplazar en el archivo activo y en todo el proyecto, con topes de seguridad para no
  colgar la app en proyectos grandes.
- Atajos de teclado físico/Bluetooth (Ctrl+S, Ctrl+F, Ctrl+P, Ctrl+Tab, etc.) y una barra de
  teclado virtual con modificadores "pegajosos" para dispositivos táctiles sin teclado físico.
- Paleta de comandos (Ctrl+P) con una lista de acciones registradas y filtro de texto.
- Varios temas de color para el editor, con recarga en tiempo de ejecución.

Todas estas piezas siguen vigentes hoy dentro de Estudio — ver
[`IDE_INTEGRADO.md`](IDE_INTEGRADO.md) para la arquitectura actual y
[`PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md`](PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md) para las
mejoras posteriores (multi-proyecto, autocompletado real vía LSP).
