# IDE de terminal (nvim + NvChad)

**Módulo Kairos** — gestionado vía la UI de Kairos (`IdeFragment.kt`). Instalación y estado los
maneja la app vía `ProcessBuilder` → `modulos/ide.sh`. No confundir con **Estudio** (el IDE
gráfico embebido de Kairos, `app/src/main/java/com/termux/app/ui/studio/`) — este módulo es un
IDE de **terminal** (Neovim), pensado para quien prefiere trabajar directo en la terminal
adaptada en vez de la UI gráfica de Estudio.

---

**Script:** `modulos/ide.sh` — copia sincronizada en `app/src/main/assets/scripts/ide.sh`.
**Registry:** prefijo `ide.*`
**CLI resultante:** `nvim`

---

## 1. Descripción general

Instala **Neovim + NvChad** (framework de configuración de Neovim) con **GitHub Copilot** y
**CodeCompanion** ya integrados en la config que trae NvChad — un IDE completo de terminal, sin
salir de Termux. Se abre corriendo `nvim` desde cualquier sesión de terminal de Kairos.

## 2. Cómo se instala (pasos reales de `ide.sh`)

1. **Neovim + dependencias** (`pkg install`): `git neovim nodejs-lts python perl curl wget
   lua-language-server ripgrep stylua tree-sitter` — el set completo que NvChad necesita para
   LSP, formateo y syntax highlighting vía tree-sitter.
2. **NvChad**: clona un fork/adaptación de NvChad para Termux en `~/.local/share/kairos-nvchad`,
   copia su config a `~/.config/nvim`, y sincroniza plugins de forma no interactiva: `nvim
   --headless "+Lazy! sync" +qa`, seguido de `+Lazy! clean nvim-treesitter` + `+Lazy! install
   nvim-treesitter` (limpia e reinstala tree-sitter para evitar parsers a medio bajar de una
   corrida anterior).

Soporta `--silent`/`--force`/`--describe`. Detección de "ya instalado": `nvim` en PATH +
`~/.config/nvim` existe.

## 3. Qué trae ya configurado

- **GitHub Copilot** y **CodeCompanion** — vienen incluidos en la config de NvChad que se
  clona, no son un paso de instalación separado de `ide.sh`. Requieren su propio login/API key
  dentro de Neovim (`:Copilot auth`, config de CodeCompanion) — `ide.sh` no lo automatiza.
- LSP vía `lua-language-server` + lo que NvChad configure por lenguaje detectado en el proyecto.

## 4. UI en Kairos (`IdeFragment.kt`)

`IdeFragment.kt` tiene pantalla propia dedicada con controles reales:

| Control | Qué hace | Por qué |
|---|---|---|
| Card ESTADO | Solo lectura: Editor=nvim, Framework=NvChad, IA integrada=Copilot/CodeCompanion | — |
| "📝 Abrir nvim aquí" | `launchTerminalCommand("nvim")` — abre en el directorio actual (home) | — |
| "📂 Abrir proyecto en nvim" | `showProjectsMenu()` (mismo patrón symlink/copiar que otros módulos) → `cd '$path' && nvim .` | Mismo mecanismo `cd + CLI` que ya usan otros módulos de terminal |
| "🗂 Gestionar proyectos" | `showProjectsMenu()` sin lanzar nvim — solo crear/elegir/borrar proyectos | — |
| "🩺 Diagnóstico (checkhealth)" | `nvim --headless -c 'checkhealth' -c 'qa!' > archivo 2>&1`, muestra el resultado en un `AlertDialog` con scroll | `--headless` no dibuja ningún buffer — el output de `:checkhealth` (interactivo por defecto) solo llega redirigido a archivo, nunca a un buffer visible |
| "🔄 Actualizar plugins (Lazy sync)" | `nvim --headless "+Lazy! sync" +qa` (vía terminal, no diálogo) | Comando literal idéntico al que `modulos/ide.sh` usa en su propia instalación — reinvocarlo sincroniza plugins nuevos sin reinstalar todo el módulo |
| "↑ Instalar / reinstalar" | `reinstallModuleService()` | — |

## 5. Gestión de plugins/LSP con Mason — no implementado

NvChad soporta Mason (`:MasonInstallAll`, `:Mason`) para gestionar plugins/LSPs/temas de forma
interactiva. Kairos no expone esto como botón todavía — la sintaxis headless exacta para invocar
Mason sin abrir la UI interactiva de nvim no está confirmada con evidencia suficientemente sólida
(a diferencia de `checkhealth`/`Lazy! sync`, que sí tienen precedente directo y confirmado). Queda
como funcionalidad pendiente de evaluar.
