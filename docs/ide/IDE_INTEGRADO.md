# IDE_INTEGRADO.md — Estudio: el IDE vive dentro del APK de Kairos

## La decisión, en una frase

Estudio (el editor de código/IDE de Kairos) **no es una app separada** — es una pantalla más
dentro del mismo APK, exactamente igual que Chat, Módulos, Sistema o Archivos. Compila junto con
el resto de la app, corre en el mismo proceso, y comparte el mismo motor Termux que ya usa el
resto de Kairos. La razón de fondo es la comunicación directa y total con el entorno Termux y con
los proveedores de IA — algo que una app externa separada solo puede lograr por mecanismos
indirectos (Intents, sockets a localhost), mientras que una pantalla dentro de la misma app lo
tiene de forma nativa, porque ya está en el mismo proceso.

Antes de llegar a este diseño se exploró la alternativa de un IDE Android como proyecto
independiente — ver [`IDE_EXTERNO.md`](IDE_EXTERNO.md) para esa historia y por qué se descartó.

## Dónde vive Estudio en la navegación de la app

Kairos usa una barra de navegación inferior con slots fijos (Módulos, Chat IA, Sistema, Config,
Más) — el quinto ("Más") abre un menú secundario con pantallas adicionales (Monitor, Archivos,
Túnel, Nube, Plugins, X11). Estudio es una entrada más de ese menú (`nav_studio`), no ocupa un
ícono fijo de la barra principal.

### Relación con el editor rápido del gestor de archivos

Kairos ya tenía, antes de Estudio, un editor de texto simple (`EditorFragment.kt`) usado por la
pantalla de Archivos — basado en `sora-editor` con resaltado de sintaxis TextMate real (12+
lenguajes). Estudio y ese editor rápido **coexisten**, con roles distintos:

- El editor rápido del gestor de archivos sirve para abrir-editar-guardar un archivo suelto,
  sin la sobrecarga de un proyecto completo.
- Estudio es la experiencia completa: sidebar de archivos, pestañas, integración con Git,
  búsqueda en proyecto, atajos de teclado, y un chat de IA — pensado para "modo desarrollo",
  con un proyecto abierto como workspace.

También existe, separado de ambos, un módulo que ejecuta Neovim (con NvChad y plugins de
finalización de código) dentro de una sesión de terminal — un editor de tipo TUI, no gráfico,
para quien prefiere ese flujo desde la línea de comandos.

## Cómo ejecuta comandos — en el mismo proceso, sin permisos especiales

Estudio ejecuta comandos de shell (compilar con Gradle, correr Git, instalar herramientas de
lenguaje) directamente dentro del proceso de la app, usando el mismo mecanismo que el resto de
Kairos usa para lanzar procesos en el entorno Termux embebido: se invoca el binario `bash` real
del entorno, con las variables de entorno del entorno Termux ya resueltas (rutas de binarios,
`HOME`, etc.), y se captura stdout/stderr/código de salida.

Esto es deliberadamente distinto de un enfoque de app externa comunicándose por Intents — no hace
falta ningún permiso especial ni ninguna app externa instalada; el motor de ejecución ya forma
parte del mismo APK.

## Estructura del código

El código de Estudio vive en un paquete propio dentro de la app, organizado por responsabilidad:

- **`ai/`** — cliente HTTP para proveedores de IA (varios proveedores cloud "trae tu propia
  clave" + fallback a un motor de IA local si Kairos lo tiene corriendo), pantalla de
  configuración de proveedor.
- **`filetree/`** — árbol de archivos del proyecto (carga perezosa vía el framework de acceso a
  almacenamiento de Android, iconos por tipo de archivo, operaciones de archivo: crear, renombrar,
  eliminar, copiar ruta).
- **`tabs/`** — sistema de pestañas de archivos abiertos sobre un editor de código compartido.
- **`git/`** — panel de Git: estado, commit, push/pull, log, diff — arma comandos `git` reales y
  parsea su salida.
- **`search/`** — búsqueda y reemplazo, tanto dentro de un archivo como en todo el proyecto.
- **`keyboard/`** — atajos de teclado físico/Bluetooth (Ctrl+S, Ctrl+F, Ctrl+P, etc.) y una barra
  de teclado virtual con modificadores "pegajosos" (Ctrl/Alt/Shift) para dispositivos táctiles.
- **`palette/`** — paleta de comandos al estilo VS Code (Ctrl+P), con una lista de acciones
  registradas y filtro de texto.
- **`settings/`** — preferencias del editor (tamaño de fuente, tabulación, ajuste de línea,
  números de línea, tema de sintaxis).
- **`build/`** — panel de log de compilación en tiempo real.
- **`editor/`** — resaltado de sintaxis TextMate y controlador de búsqueda dentro del editor.
- **`lsp/`** — integración con servidores de lenguaje reales (ver más abajo).

El editor de texto en sí se basa en la librería open source `sora-editor`, la misma que usa el
editor rápido del gestor de archivos, con grammars TextMate para resaltado de sintaxis por
lenguaje.

## Autocompletado real vía LSP (Protocolo de Servidor de Lenguaje)

Estudio conecta servidores de lenguaje reales (protocolo LSP estándar, no una heurística propia)
para dar autocompletado, diagnósticos, hover, ir-a-definición y renombrado de símbolos reales —
no solo resaltado de sintaxis. Cobertura actual:

- **Bash/shell** (`.sh`, `.bash`) vía `bash-language-server`.
- **Python** (`.py`, `.pyw`) vía `pylsp` (`python-lsp-server`).

Ambos se eligieron porque son los lenguajes en los que están escritos los propios scripts que
Kairos gestiona (los módulos de la app son scripts bash, y varias herramientas internas son
Python). Kotlin/Java quedó deliberadamente fuera del alcance inicial: no existe un servidor de
lenguaje liviano instalable sin un JDK completo y un modelo de proyecto Gradle resuelto — un
esfuerzo aparte.

El servidor de lenguaje correspondiente se instala solo, en segundo plano, la primera vez que se
abre un archivo del lenguaje soportado (mostrando solo un aviso breve, sin diálogo bloqueante) —
siempre que el runtime necesario (Node.js para el server de bash, Python para `pylsp`) ya esté
disponible.

**Limitaciones conocidas del soporte LSP actual:**
- Solo funciona con proyectos cuya carpeta resuelve a una ruta real de almacenamiento primario del
  dispositivo — un proyecto abierto únicamente vía un proveedor de almacenamiento externo (sin
  ruta de archivo real) no tiene LSP.
- Estudio usa un único editor de código compartido entre pestañas — la pestaña activa tiene
  binding de UI completo; una pestaña en segundo plano sigue conectada al servidor de lenguaje
  pero no muestra diagnósticos en vivo hasta que vuelve a estar activa.
- Autocompletado y diagnósticos (subrayado de errores) vienen "gratis" al conectar; hover,
  ir-a-definición y renombrado de símbolo están cableados a un menú contextual que aparece con
  toque prolongado sobre el editor; búsqueda de referencias y formateo automático todavía no
  tienen interfaz propia (la librería subyacente los soporta a nivel de protocolo).

## Multi-proyecto

Estudio soporta hasta 3 proyectos abiertos simultáneamente. Un selector de proyecto (fila de
chips) aparece sobre las pestañas de archivo cuando hay 2 o más proyectos abiertos a la vez —
cambiar de proyecto activo conserva las pestañas y el estado de los demás proyectos en memoria,
en vez de cerrarlos. Al superar el límite, el proyecto abierto hace más tiempo se cierra
automáticamente para dar lugar al nuevo. Todas las pestañas con cambios sin guardar se guardan
automáticamente antes de cambiar de proyecto activo.

## Diagnósticos de compilación clickeables

El panel de log de build resalta las líneas de error/warning por severidad y permite tocar una
línea con ubicación de archivo reconocida para abrir ese archivo directamente en el editor, en la
línea y columna exactas.

## Estado actual y limitaciones honestas

- El chat de IA de Estudio usa su propio cliente, separado del chat principal de la app — una
  futura unificación en una capa compartida es una mejora posible, no bloqueante.
- Cerrar una pestaña con cambios sin guardar ahora pregunta explícitamente
  (guardar/descartar/cancelar) antes de perder contenido — corregido tras auditoría de código
  (ver [`AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md`](AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md)).
- Los modificadores "pegajosos" del teclado virtual (Ctrl/Alt/Shift bloqueados con toque
  prolongado) se resetean automáticamente al pasar la app a segundo plano o al abrir un proyecto
  nuevo, para evitar que queden "pegados" indefinidamente sin que el usuario lo note.
- Reordenar pestañas por arrastre, diff visual por bloque en el panel de Git, y minimap del editor
  siguen sin implementar — quedaron fuera de alcance del rediseño más reciente (ver
  [`PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md`](PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md)).
