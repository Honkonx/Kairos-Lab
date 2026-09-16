# Accesos rápidos fuera de la app

**Feature nativa de Kairos** — dos mecanismos de Android para prender/apagar o abrir un módulo
favorito sin entrar a la app primero. Antes de esto, el único camino era abrir Kairos, ir a
Módulos y navegar hasta la pantalla correspondiente cada vez.

---

**App:** Kairos (fork termux-app)
**Lógica compartida:** `QuickAccessPrefs.kt`

---

## 1. Accesos directos del ícono (long-press en el launcher)

Marcando la estrella (☆ → ★) que aparece en el header de la pantalla de cualquier módulo, ese
módulo queda disponible como acceso directo al mantener presionado el ícono de Kairos en el
launcher — hasta 4 favoritos a la vez. Tocar un acceso directo abre Kairos directo en la pantalla
de ese módulo, con el mismo camino de navegación que tocar la fila a mano desde la lista de
módulos.

Si un módulo favorito se desinstala o deja de existir, su acceso directo se limpia solo en el
siguiente arranque de la app, sin afectar al resto de los favoritos.

**Limitación de esta primera versión:** el acceso directo solo navega a la pantalla del módulo
— no lo enciende/apaga por sí solo. Encender/apagar sigue siendo un toque una vez dentro de la
pantalla.

## 2. Tile de ajustes rápidos

Un tile real en el panel de ajustes rápidos de Android (el que se desliza desde arriba de la
pantalla) que muestra si un módulo elegido está activo o no, y lo enciende/apaga con un solo tap
— sin abrir la app en absoluto. Qué módulo controla se elige desde Ajustes → "Accesos rápidos".

**Limitación de esta primera versión:** un solo tile fijo, controla un módulo a la vez (Android
no permite tiles dinámicos por módulo sin declarar cada uno por separado) — para controlar otro
módulo hay que volver a Ajustes y cambiar la selección.

## 3. Qué queda para más adelante

- Un ícono distinto por módulo en los accesos directos (hoy todos comparten un ícono genérico).
- Encender/apagar directo desde el acceso directo del launcher, sin pasar por la pantalla del
  módulo.
- Más de un tile de ajustes rápidos simultáneo.
