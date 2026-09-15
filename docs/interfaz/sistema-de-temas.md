# Sistema de temas

Kairos tiene dos sistemas de tema independientes por diseño: el del panel principal de la app
(`KairosThemePrefs`, 3 variantes) y el del IDE integrado ("Estudio", `StudioThemePrefs`, 2
variantes). No se mezclan — cada uno tiene su propio selector inline en su pantalla
correspondiente.

## 1. Tema del panel principal — `?attr/kairos*` (`Theme.Kairos.*`)

3 variantes seleccionables desde Config → 🎨 Tema. "Oscuro" es el tema por defecto.

| Atributo | Oscuro | Señal | Claro |
|---|---|---|---|
| `kairosBg` | `#050505` | `#0A0E14` | `#F7F6F3` |
| `kairosBg2` | `#0A0A0A` | `#10151D` | `#FFFFFF` |
| `kairosBg3` | `#111111` | `#161C26` | `#EFEDE8` |
| `kairosBgSurface` | `#0D0D0D` | `#0D131A` | `#FBFAF8` |
| `kairosNavBg` | `#080808` | `#080C11` | `#FFFFFF` |
| `kairosText` | `#E8E8E8` | `#E4EAF2` | `#1A1A1C` |
| `kairosText2` | `#888888` | `#7C8B9E` | `#5B5B60` |
| `kairosText3` | `#555555` | `#4A5568` | `#8C8C90` |
| `kairosBlue` | `#3B82F6` | `#4FD1C5` (cian) | `#2563EB` |
| `kairosGreen` | `#22C55E` | `#48BB78` | `#16A34A` |
| `kairosRed` | `#EF4444` | `#F56565` | `#DC2626` |
| `kairosAmber` | `#F59E0B` | `#ECC94B` | `#D97706` |
| `kairosBorder` | `#1F1F1F` | `#1E2733` | `#E4E2DC` |
| `kairosStatusRunning` | `#22C55E` | `#48BB78` | `#16A34A` |
| `kairosStatusStopped` | `#555555` | `#4A5568` | `#9CA3AF` |
| `kairosStatusInstalling` | `#3B82F6` | `#4FD1C5` | `#2563EB` |
| `kairosStatusError` | `#EF4444` | `#F56565` | `#DC2626` |
| `kairosStatusNotInstalled` | `#333333` | `#2A323D` | `#C7C5BF` |

**Diseño de cada variante** — el criterio detrás de la paleta, no solo los valores:

- **Oscuro**: negro casi puro con acentos saturados clásicos. El tema por defecto histórico.
- **Señal**: "azul-negro tipo panel de control" — un solo acento cian en vez de varios colores
  saturados distintos, tonos más fríos en general.
- **Claro**: modo claro diseñado a propósito, no una inversión mecánica del oscuro — fondo
  cálido `#F7F6F3` en vez de blanco puro, acentos más oscuros para mantener contraste legible
  sobre fondo claro. También ajusta el color de la barra de estado y de navegación del sistema.

## 2. Tema del IDE integrado — `?attr/studio*` (`Theme.Studio.*`)

Sistema independiente del tema del panel principal. Solo 3 atributos en fase actual (fondo raíz,
toolbar, texto del título) — el resto de la interfaz del IDE (barra lateral, pestañas, resaltado
de sintaxis) usa una paleta de colores fijos ("Kairos Ink", ver abajo).

| Atributo | Oscuro | Claro |
|---|---|---|
| `studioBg` | `#12151C` | `#F4F5F8` |
| `studioSurface` | `#1B1F2A` | `#FFFFFF` |
| `studioTextPrimary` | `#E8EAF0` | `#1B1F2A` |

Existe una tercera opción en el selector, "Sincronizar con la app": no es un tema propio, sino
que aproxima por polaridad al tema del panel principal activo (si el panel está en Oscuro o
Señal usa el tema Oscuro del IDE; si está en Claro usa el tema Claro del IDE).

### Paleta "Kairos Ink" (colores fijos del IDE, no theming)

Colores fijos usados directamente por el layout del IDE — superficies (barra lateral, barra de
pestañas, barra de estado en tonos oscuros `#161A23`/`#0D1016`), un acento único verde-agua
(`#5CE0C6`), y una paleta de 12 colores por lenguaje de programación (violeta para Kotlin
`#B39BFF`, naranja para Java `#F2A65A`, verde para Python `#7FCB8F`, etc.) usada en los íconos
del árbol de archivos. El resaltado de sintaxis reutiliza los mismos matices donde tiene sentido
(por ejemplo, las palabras clave comparten el violeta del ícono de Kotlin).
