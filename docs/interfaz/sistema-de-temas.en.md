# Theme system

Kairos has two independent theme systems by design: the main app panel's
(`KairosThemePrefs`, 3 variants) and the integrated IDE's ("Studio", `StudioThemePrefs`, 2
variants). They don't mix — each has its own inline picker on its corresponding screen.

## 1. Main panel theme — `?attr/kairos*` (`Theme.Kairos.*`)

3 variants selectable from Config → 🎨 Theme. "Dark" is the default theme.

| Attribute | Dark | Signal | Light |
|---|---|---|---|
| `kairosBg` | `#050505` | `#0A0E14` | `#F7F6F3` |
| `kairosBg2` | `#0A0A0A` | `#10151D` | `#FFFFFF` |
| `kairosBg3` | `#111111` | `#161C26` | `#EFEDE8` |
| `kairosBgSurface` | `#0D0D0D` | `#0D131A` | `#FBFAF8` |
| `kairosNavBg` | `#080808` | `#080C11` | `#FFFFFF` |
| `kairosText` | `#E8E8E8` | `#E4EAF2` | `#1A1A1C` |
| `kairosText2` | `#888888` | `#7C8B9E` | `#5B5B60` |
| `kairosText3` | `#555555` | `#4A5568` | `#8C8C90` |
| `kairosBlue` | `#3B82F6` | `#4FD1C5` (cyan) | `#2563EB` |
| `kairosGreen` | `#22C55E` | `#48BB78` | `#16A34A` |
| `kairosRed` | `#EF4444` | `#F56565` | `#DC2626` |
| `kairosAmber` | `#F59E0B` | `#ECC94B` | `#D97706` |
| `kairosBorder` | `#1F1F1F` | `#1E2733` | `#E4E2DC` |
| `kairosStatusRunning` | `#22C55E` | `#48BB78` | `#16A34A` |
| `kairosStatusStopped` | `#555555` | `#4A5568` | `#9CA3AF` |
| `kairosStatusInstalling` | `#3B82F6` | `#4FD1C5` | `#2563EB` |
| `kairosStatusError` | `#EF4444` | `#F56565` | `#DC2626` |
| `kairosStatusNotInstalled` | `#333333` | `#2A323D` | `#C7C5BF` |

**Design of each variant** — the reasoning behind the palette, not just the values:

- **Dark**: near-pure black with classic saturated accents. The historical default theme.
- **Signal**: "control-panel-style blue-black" — a single cyan accent instead of several
  distinct saturated colors, generally cooler tones.
- **Light**: a deliberately designed light mode, not a mechanical inversion of Dark — a warm
  `#F7F6F3` background instead of pure white, darker accents to keep contrast legible against a
  light background. It also adjusts the system status bar and navigation bar color.

## 2. Integrated IDE theme — `?attr/studio*` (`Theme.Studio.*`)

A system independent of the main panel's theme. Only 3 attributes in the current phase (root
background, toolbar, title text) — the rest of the IDE's interface (sidebar, tabs, syntax
highlighting) uses a fixed color palette ("Kairos Ink", see below).

| Attribute | Dark | Light |
|---|---|---|
| `studioBg` | `#12151C` | `#F4F5F8` |
| `studioSurface` | `#1B1F2A` | `#FFFFFF` |
| `studioTextPrimary` | `#E8EAF0` | `#1B1F2A` |

There is a third option in the picker, "Sync with app": it isn't a theme of its own, but rather
approximates the active main panel theme by polarity (if the panel is set to Dark or Signal, it
uses the IDE's Dark theme; if it's set to Light, it uses the IDE's Light theme).

### "Kairos Ink" palette (fixed IDE colors, not theming)

Fixed colors used directly by the IDE layout — surfaces (sidebar, tab bar, status bar in dark
tones `#161A23`/`#0D1016`), a single teal accent (`#5CE0C6`), and a 12-color-per-programming-
language palette (violet for Kotlin `#B39BFF`, orange for Java `#F2A65A`, green for Python
`#7FCB8F`, etc.) used in the file-tree icons. Syntax highlighting reuses the same hues where it
makes sense (for example, keywords share the same violet as the Kotlin icon).
