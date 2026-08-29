# Menú de módulos dentro del escritorio gráfico

## Objetivo

Dar a los módulos instalados de Kairos (agentes de IA, herramientas de desarrollo, etc.) un punto
de acceso real dentro del escritorio gráfico X11 embebido — un menú de inicio, al estilo del menú
"Inicio" de Windows, no solo una serie de íconos sueltos en el fondo de pantalla. El objetivo es
poder lanzar cualquier módulo instalado (por ejemplo un agente de IA en terminal) dentro del
escritorio, mientras el resto del entorno gráfico sigue funcionando normalmente (navegador
abierto, otras ventanas activas), tanto en el modo nativo como dentro de una distro Linux vía
proot.

## Estado previo a esta funcionalidad

Kairos ya generaba archivos `.desktop` (formato freedesktop.org, Desktop Entry Specification)
para los módulos instalados, pero únicamente en la carpeta `~/Desktop` — la carpeta que
`xfdesktop` (el gestor de íconos de escritorio de XFCE4, y equivalentes en LXQt/MATE) escanea
para dibujar íconos sobre el fondo de pantalla. Es el equivalente al escritorio de Windows, no al
menú Inicio. Consecuencias reales de esta limitación:

- Los módulos solo eran visibles si el usuario veía el fondo de pantalla — con una ventana
  maximizada tapándolo, no había forma de acceder a ellos sin minimizar todo.
- No existía ningún menú de aplicaciones nativo (el botón "Aplicaciones" del panel de XFCE4, por
  ejemplo) mostrando los módulos agrupados por categoría.
- El campo `Categories=` de cada archivo `.desktop` ya se escribía correctamente, pero no tenía
  ningún efecto porque el archivo vivía en la ruta equivocada para que un menú de aplicaciones lo
  leyera.

## Cómo XFCE4/LXQt/MATE construyen su menú de aplicaciones real

Los tres entornos de escritorio (y prácticamente cualquier entorno compatible con
freedesktop.org) siguen el mismo estándar: **XDG Desktop Menu Specification** + **Desktop Entry
Specification**.

- El menú "Aplicaciones" se construye escaneando archivos `.desktop` en un conjunto fijo de
  directorios, en orden de prioridad:
  1. `~/.local/share/applications/` — por usuario, sin requerir permisos especiales.
  2. `/usr/share/applications/` — sistema, instalado por paquetes.
  3. Otras rutas de `$XDG_DATA_DIRS`.
- El campo `Categories=` decide en qué submenú/categoría aparece cada entrada, siguiendo las
  categorías estándar del spec (`Development`, `Utility`, `System`, `Network`, etc.).
- El menú se regenera automáticamente al agregar o quitar archivos en
  `~/.local/share/applications/` — no hace falta reiniciar el escritorio ni correr ningún comando
  manual de refresco.

## Solución adoptada

Se extendió la generación de archivos `.desktop` que Kairos ya hacía (para los CLIs con wrapper
propio, para el catálogo genérico de módulos, y para aplicaciones instaladas dentro de una distro
proot) para que escriban el mismo contenido **también** en `~/.local/share/applications/`, además
de en `~/Desktop`. Es una extensión de bajo esfuerzo sobre una función que ya existía y ya
funcionaba, sin dependencias nuevas ni superficie de bugs adicional — reutiliza el mismo
contenido `.desktop` que Kairos ya sabía construir. Este mismo mecanismo se aplica igual dentro de
una distro Linux corriendo vía proot (el `$HOME` de la distro tiene su propio directorio de
aplicaciones XDG, escrito por el mismo mecanismo que ya usa Kairos para entrar a esa distro).

Se descartaron dos alternativas más costosas evaluadas en la investigación inicial:

- **Un panel/launcher nativo propio (C/Xlib)**, tipo `dmenu`/`rofi` construido a medida — daría
  control total del diseño, pero implica escribir un cliente X11 completo desde cero (parseo de
  eventos, dibujo sin ninguna toolkit, sincronización con el catálogo de módulos desde un binario
  C separado), con una superficie de bugs nativos nueva y sin ningún proyecto reutilizable como
  base. Se descartó por esfuerzo alto sin garantía de superar la UX que el menú nativo del
  escritorio ya ofrece gratis.
- **Un panel HTML/JS embebido** — también descartado por el mismo criterio: el menú nativo del
  escritorio ya resuelve el problema real con cero dependencias nuevas.

## Categorización real por módulo

Cada módulo del catálogo de Kairos tiene una categoría real (IA, desarrollo, lenguajes,
seguridad, base de datos, sistema, herramientas), que se traduce a las categorías XDG estándar
correspondientes (`Utility;Development;`, `Development;`, `System;Network;`, etc.) al generar el
archivo `.desktop`. El menú de aplicaciones por defecto que trae XFCE4 (sin necesitar
extensiones adicionales) ya agrupa/filtra por esas categorías en submenús — funcionalmente
equivalente a un filtro por categoría con un clic, sin tener que escanear una lista larga de
íconos sin agrupar.

Las aplicaciones GUI instaladas dentro de una distro proot (elegidas libremente por el usuario,
no provenientes del catálogo curado de módulos de Kairos) usan una categoría genérica, porque no
hay un origen curado del que derivar una categoría más específica.

## Actualización de los lanzadores

Los lanzadores se regeneran automáticamente al arrancar el escritorio, y también con una acción
manual "Actualizar lanzadores" desde la pantalla de configuración del entorno gráfico. Un módulo
instalado mientras el escritorio ya está abierto no aparece en el menú hasta la próxima
regeneración — una limitación conocida, con una vía de mejora futura de bajo esfuerzo (disparar
la regeneración automáticamente al terminar cada instalación de módulo, no solo al abrir el
escritorio).

## Ver también

- [X11_EMBEBIDO.md](X11_EMBEBIDO.md) — servidor X11 embebido, contexto del display que usa este
  mecanismo.
