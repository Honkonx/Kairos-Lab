# Componentes UI reusables

Inventario de los helpers compartidos que evitan reimplementar el mismo control en cada
pantalla de módulo.

## `BaseModuleFragment` — helpers compartidos por las pantallas de módulo

Clase base que la mayoría de pantallas de módulo (Ollama, n8n, Claude Code, etc.) extiende — sus
helpers quedan disponibles directo sin reimplementar nada.

### `createActionButton(text, style, onClick): View`

Botón de acción genérico, 3 estilos:

- **`PRIMARY`**: fondo con esquinas redondeadas, relleno azul de acento al 14% de opacidad más
  borde al 43%, efecto ripple, texto azul en negrita.
- **`DANGER`**: fondo rojo tenue, texto rojo de acento.
- **`GHOST`** (default): fondo neutro, texto estándar — el botón "neutro" de la paleta.

### `pill(text, isActive): View`

Badge de estado chico (pastilla) — fondo verde tenue si está activo, texto acorde. Se usa para
mostrar un estado corto (por ejemplo "● Corriendo"/"○ Detenido") al lado de un título de
sección, no como botón interactivo.

### `switchRow(label, initialOn, onToggled): SwitchRow`

Fila con un switch a la derecha — el patrón más simple, un solo toggle con etiqueta.

### `dropdownRow(label, options, initialIndex, onOptionChosen): DropdownRow`

Fila que despliega un menú emergente con opciones — sin switch, solo selección entre varias
alternativas.

### `dropdownSwitchRow(label, options, initialIndex, initialOn, onOptionChosen, onSwitchToggled): DropdownSwitchRow`

Combinación de las dos anteriores — desplegable de opciones más switch en la misma fila (por
ejemplo "Destino: Nativo/Distro/Contenedor" con switch de encendido). El componente más
completo, usado cuando un módulo necesita elegir una variante y encender/apagar en el mismo
control.

### `setupTabs(tabNames, parent, tabMode): TabbedSectionsBuilder`

Helper para pantallas con pestañas de categorías (`TabLayout` más contenido por pestaña).
Reconstruye el contenido de la pestaña activa en cada cambio en vez de mantener todas las vistas
vivas simultáneamente — más simple de mantener para la mayoría de los casos.

**Firma real:**

```kotlin
protected fun setupTabs(
    tabNames: List<String>,
    parent: LinearLayout = container,
    tabMode: Int = TabLayout.MODE_SCROLLABLE
): TabbedSectionsBuilder

protected inner class TabbedSectionsBuilder {
    fun tab(index: Int, block: (LinearLayout) -> Unit): TabbedSectionsBuilder
    fun build(initialIndex: Int = 0): TabbedSections
}

protected inner class TabbedSections {
    val activeIndex: Int
    fun render(index: Int)       // limpia el contenido y reconstruye la pestaña [index]
    fun renderActive()           // atajo: render(activeIndex) — refrescos de estado
}
```

**Uso:**

```kotlin
setupTabs(listOf("Motores", "SQLite", "Backups"), tabMode = TabLayout.MODE_FIXED)
    .tab(0) { parent -> buildMotoresTab(parent) }
    .tab(1) { parent -> buildSqliteTab(parent) }
    .tab(2) { parent -> buildBackupsTab(parent) }
    .build()
```

`addCard(title, parent, block)` acepta un `parent` opcional (por defecto el contenedor
principal), lo que permite agregar tarjetas dentro del contenedor propio de cada pestaña en vez
de siempre en el contenedor raíz.

Para pantallas con mucho estado en vivo entrelazado entre pestañas (sondeo de estado, switches
que se actualizan desde un hilo de fondo), existe también un patrón alternativo que construye
todas las pestañas una sola vez y alterna su visibilidad en lugar de reconstruirlas — útil
cuando reconstruir la vista completa en cada cambio de pestaña sería más frágil que solo
ocultarla.

#### Agrupación de módulos consolidados en pestañas

La base compartida de las pantallas de "Lenguajes" y "Paquetes" (herramientas de desarrollo
agrupadas) admite un hook opcional que, cuando una subclase lo implementa, agrupa sus elementos
en pestañas reales en vez de una única tarjeta. Por ejemplo, la pantalla de Paquetes agrupa sus
herramientas npm en "Formato y calidad" / "Servidores y túneles" / "Deploy y frameworks".

## `InlineThemePicker`

Componente standalone (independiente de `BaseModuleFragment`) que reemplaza el patrón clásico de
diálogo modal con lista de opciones por un menú emergente anclado que aplica el cambio al toque,
sin diálogo modal. Recibe una lista de opciones genérica, desacoplada de cualquiera de los dos
sistemas de tema — así el mismo componente sirve tanto para el selector de tema del panel
principal como para el selector de tema del IDE integrado ("un solo componente, dos
instancias").

## Abrir una TUI en la carpeta elegida

Otra extensión standalone de `Fragment` (mismo criterio que `InlineThemePicker`, no vive dentro
de `BaseModuleFragment`) que, antes de abrir la terminal de un CLI, muestra un diálogo de 2
opciones: "Carpeta por defecto (~)" o "Elegir carpeta…". Si el usuario elige la segunda opción,
reusa el mismo navegador de carpetas del explorador de Archivos (Termux home + almacenamiento
interno) y arranca el comando anteponiendo `cd '<carpeta>' &&`, en vez de arrancar siempre en el
directorio home.

No todos los módulos con terminal propia usan este helper: algunos (Claude Code, Codex CLI,
Antigravity CLI, OpenClaw, Hermes) resuelven el mismo problema con dos botones separados en su
propia pantalla — uno para la carpeta por defecto y otro para elegir un proyecto ya importado —
en vez de un único diálogo de 2 opciones. Ambos patrones conviven en la app y logran el mismo
resultado funcional.
