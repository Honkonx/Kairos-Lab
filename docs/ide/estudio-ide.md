# Estudio — el IDE integrado de Kairos

Estudio es el entorno de desarrollo integrado de Kairos: un tab más dentro de la app, con el
mismo nivel de integración directa con el motor Termux y con los proveedores de IA que
cualquier otra pantalla de la aplicación — sin capas intermedias de comunicación entre procesos.

## Qué es

Una pantalla completa de edición de código dentro del panel principal de la app, pensada para
trabajar en un proyecto real (no solo abrir un archivo suelto). Convive con el editor de texto
simple del explorador de archivos: ese sigue siendo la vía rápida para abrir-editar-guardar un
único archivo, mientras que Estudio es el "modo desarrollo" completo, con barra lateral de
archivos, pestañas, integración de git, búsqueda en el proyecto y terminal integrada.

## Características principales

- **Multi-proyecto**: hasta 3 proyectos abiertos simultáneamente, con un selector de chips para
  cambiar entre ellos cuando hay más de uno abierto.
- **Editor con resaltado de sintaxis real** (TextMate, biblioteca `sora-editor`), con un catálogo
  de 14 temas de color (los 3 temas propios de Kairos más Dracula, Nord, Gruvbox, Solarized,
  Monokai, One Dark, Atom One Light y Ayu, estos últimos en variantes claro/oscuro donde
  corresponde) — el tema se puede cambiar tanto desde ajustes del editor como desde la propia
  paleta de comandos.
- **Soporte de lenguaje vía LSP** (Language Server Protocol) para Bash y Python — autocompletado
  real basado en el protocolo estándar, no una heurística propia. El servidor de lenguaje
  correspondiente se instala automáticamente, en segundo plano, la primera vez que se abre un
  archivo del lenguaje soportado.
- **Panel de git**: estado del repositorio, diffs, stash y operaciones básicas sobre el proyecto
  abierto. La autenticación con GitHub para push/pull usa el flujo OAuth de dispositivo (login
  desde el navegador, sin pegar credenciales a mano) — una vez autenticado, el token nunca vuelve
  a mostrarse en pantalla, solo se puede usar, reemplazar o cerrar sesión.
- **Terminal integrada**: acceso directo a una sesión de shell dentro del mismo tab, sin salir
  del flujo de edición.
- **Log de compilación con diagnósticos clickeables**: errores y advertencias de build muestran
  archivo, línea y columna reales, y al tocarlos abren el editor directamente en esa ubicación.
- **Asistencia de IA integrada**: soporte para varios proveedores de IA en la nube (con clave
  propia del usuario) y para el motor de IA local embebido de Kairos, dentro del propio flujo de
  edición.
- **Búsqueda en el proyecto**: búsqueda y reemplazo tanto dentro de un archivo como a través de
  todo el proyecto abierto, con toggle de sensibilidad a mayúsculas/minúsculas.
- **Deshacer/rehacer** con atajos de teclado dedicados, además de los atajos físicos/Bluetooth
  generales de navegación.
- **Paleta de comandos** (activable con Ctrl+P) para navegación y acciones rápidas — los comandos
  que no aplican al contexto actual (por ejemplo, sin proyecto o archivo abierto) aparecen
  deshabilitados en vez de fallar al tocarlos.

## Cómo se ejecutan los comandos

Estudio ejecuta comandos de shell (git, compilación, ejecución de scripts) directamente dentro
del mismo proceso de la app, usando el mismo mecanismo que el resto de los módulos de Kairos
para invocar binarios del entorno Termux — sin permisos especiales ni intents externos. Esto
significa que cualquier operación de build, control de versiones o ejecución de proyecto tiene
la misma latencia y confiabilidad que el resto de la aplicación.

## Limitaciones actuales

- El soporte LSP funciona solo cuando el proyecto abierto resuelve a una ruta de archivos real
  del sistema, no a una referencia de solo lectura a través del selector de documentos de
  Android.
- El editor de código es un componente compartido entre las pestañas de archivo abiertas — solo
  la pestaña activa tiene diagnósticos en vivo en cada momento; una pestaña en segundo plano
  sigue conectada a su servidor de lenguaje, pero no actualiza diagnósticos hasta volver a ella.
- Funciones más avanzadas del protocolo LSP (información al pasar el cursor, ir a definición,
  renombrado, formateo automático) están soportadas por la biblioteca subyacente pero todavía no
  tienen interfaz propia cableada en Estudio.
