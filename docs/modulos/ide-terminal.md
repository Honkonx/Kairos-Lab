# IDE de terminal (nvim + NvChad)

**Módulo de Kairos** — gestionado vía la UI de Kairos (`IdeFragment.kt`). Instalación y estado
los maneja la app vía `ProcessBuilder` → `modulos/ide.sh`. No confundir con **Estudio** (el IDE
gráfico embebido de Kairos, `app/src/main/java/com/termux/app/ui/studio/`) — este módulo es un
IDE de **terminal** (Neovim), pensado para quien prefiere trabajar directo en la terminal
adaptada en vez de la UI gráfica de Estudio.

---

**Script:** `modulos/ide.sh` (v1.0.0) — copia sincronizada en `app/src/main/assets/scripts/ide.sh`.
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
2. **NvChad**: clona `https://github.com/DevCoreXOfficial/nvchad-termux.git` (fork/adaptación de
   NvChad para Termux) en `~/.local/share/kairos-nvchad`, copia su config a `~/.config/nvim`, y
   sincroniza plugins de forma no interactiva: `nvim --headless "+Lazy! sync" +qa`, seguido de
   `+Lazy! clean nvim-treesitter` + `+Lazy! install nvim-treesitter` (limpia e reinstala
   tree-sitter para evitar parsers a medio bajar de una corrida anterior). Exige además que
   `~/.local/share/nvim/lazy` no quede vacío (post-condición real, `ide.sh:185-188`).

Soporta `--silent`/`--force`/`--describe`. Detección de "ya instalado": `nvim` en PATH +
`~/.config/nvim` existe (`ide.sh:121`; sale con exit 0 sin tocar el registry). Registry `ide.*`
por `registry_write` (`editor=nvim`, `framework=NvChad`). Sin sha256: clona HEAD sin pin de
commit/tag. `requires: ["git"]` en el catálogo aunque el script instala git por su cuenta.

## 3. Qué trae ya configurado

- **GitHub Copilot** y **CodeCompanion** — vienen incluidos en la config de NvChad que se
  clona, no son un paso de instalación separado de `ide.sh`. Requieren su propio login/API key
  dentro de Neovim (`:Copilot auth`, config de CodeCompanion) — `ide.sh` no lo automatiza.
- LSP vía `lua-language-server` + lo que NvChad configure por lenguaje detectado en el proyecto.

## 4. Ejecución

Sin servicio, puerto ni tmux. `nvim` se abre en la terminal de Kairos vía `launchTerminalCommand`.
`terminalCommand: "nvim"` en `modules.json`. Clasificación: complemento/extensión con pantalla
propia (`IdeFragment`, enrutado desde `ModuleDetailNavigator.kt:45`); ~7 opciones de UI.

## 5. UI en Kairos (`IdeFragment.kt`)

`IdeFragment.kt` tiene pantalla propia dedicada (no `GenericModuleFragment`) con 6 botones y
3 filas informativas estáticas:

| Control | Qué hace | Por qué |
|---|---|---|
| Card ESTADO | Solo lectura: Editor=nvim, Framework=NvChad, IA integrada=Copilot/CodeCompanion (texto fijo, no lee estado real ni versión de nvim) | — |
| "📝 Abrir nvim aquí" | `promptOpenLocation`: `nvim` en el directorio actual (home) o `cd <carpeta> && nvim .` | CLI resultante: `nvim` |
| "📂 Abrir proyecto en nvim" | `showProjectsMenu()` (mismo patrón symlink/copiar que otros módulos) → `cd '$path' && nvim .` | Mismo mecanismo `cd + CLI` que ya usan otros módulos de terminal |
| "🗂 Gestionar proyectos" | `showProjectsMenu()` sin lanzar nvim — solo crear/elegir/borrar proyectos | — |
| "🩺 Diagnóstico (checkhealth)" | `nvim --headless -c 'checkhealth' -c 'qa!' > archivo 2>&1`, muestra el resultado en un `AlertDialog` con scroll (`IdeFragment.kt:109-149`) | `--headless` no dibuja ningún buffer — el output de `:checkhealth` (interactivo por defecto) solo llega redirigido a archivo, nunca a un buffer visible |
| "🔄 Actualizar plugins (Lazy sync)" | `nvim --headless "+Lazy! sync" +qa` (vía sesión de terminal, no diálogo) | Comando literal idéntico al que `modulos/ide.sh` usa en su propia instalación — reinvocarlo sincroniza plugins nuevos sin reinstalar todo el módulo |
| "↑ Instalar / reinstalar" | `reinstallModuleService()` | — |

## 6. Archivos y rutas

`~/.config/nvim/**`, `~/.local/share/kairos-nvchad` (clon), `~/.local/share/nvim/lazy` (plugins),
`$PREFIX/tmp/kairos_ide_checkhealth.txt`, checkpoint `~/.install_ide_checkpoint`, registry `ide.*`.

## 7. Seguridad

Supply-chain: Neovim ejecuta Lua de un repo de terceros sin pinear versión. Copilot requiere
autenticación propia dentro de nvim (no gestionada por Kairos). Sin secretos en el script. Sin
`curl | bash`.

## 8. Gestión de plugins/LSP con Mason — no implementado

NvChad soporta Mason (`:MasonInstallAll`, `:Mason`) para gestionar plugins/LSPs/temas de forma
interactiva. Kairos no expone esto como botón todavía — la sintaxis headless exacta para invocar
Mason sin abrir la UI interactiva de nvim no está confirmada con evidencia suficientemente sólida
(a diferencia de `checkhealth`/`Lazy! sync`, que sí tienen precedente directo y confirmado). Queda
como funcionalidad pendiente de evaluar.

## 9. Limitaciones conocidas

- `cp -r "$NVCHAD_DIR/nvim" "$HOME/.config/nvim"` (`ide.sh:166`) no borra un destino existente;
  con `--force` o una instalación parcial previa, `cp -r` anida el contenido como
  `~/.config/nvim/nvim`. No verificado en dispositivo.
- La salida "ya instalado" (`ide.sh:121-124`) no repara ni escribe el registry.
- El diagnóstico `checkhealth` corre sin timeout (`process.waitFor()`, línea 118) y puede colgar
  el hilo si nvim bloquea.
- "Sincronizar plugins" es fire-and-forget en terminal, sin confirmación de éxito.

## 10. Alta

`modulos/ide.sh` agregado el 2026-08-13.
