# Git / GitHub (`git`) — Módulo de Kairos

> Fuentes: `modulos/git.sh`, `app/src/main/java/com/termux/app/ui/GitFragment.kt` y la entrada de
> `app/src/main/assets/modules.json`. Lo marcado "inferencia" no fue verificado en dispositivo.

## 1. Qué es y para qué sirve
Módulo de propósito general Git + GitHub CLI: opera sobre CUALQUIER carpeta de `~/proyectos`
(status / commit+push / pull / clonar) y expone cuatro acciones básicas de `gh`. Se extrajo de
`ExpoFragment` (`git.sh:7-15`, `GitFragment.kt:16-23`). Id `git`, nombre "Git / GitHub",
script `git.sh`, `category: dev`, `requires: []`, `catalogVersion 1.0.0` (`modules.json`).

## 2. Clasificación
**MÓDULO FUNCIONAL** (UI propia con estado y operaciones reales), aunque liviano: no tiene
servicio/puerto, pero sí pantalla dedicada (`ModuleDetailNavigator.kt:33` → `GitFragment`), una
card de estado (versión git, versión gh, proyecto activo — `GitFragment.kt:46-50`) y **9 botones
propios** (gestionar proyectos, clonar, status, commit+push, pull, gh auth status, gh login,
gh repo create, gh pr list) + la card de mantenimiento común (`addMaintenanceCard`, línea 75).
Aprox. 9-12 opciones de UI.

## 3. Instalación
- `git.sh --silent [--force]`; `--describe`/`--describe-files` devuelven manifiestos (`git.sh:53-79`).
- Instala con `install_single_pkg "git" "git" git` y `install_single_pkg "gh" "gh" gh`
  (`git.sh:91-92`): `pkg install -y`, idempotente por `command -v` (salvo `--force`), verificación
  real con `verify_binary_installed` (corre `--version`) y `registry_install` (`lib.sh:510-528`).
- Sin descargas directas: todo apt de Termux, firmado por el repo oficial (no hay sha256 propio).
- Escribe en el registry las claves `git.*` y `gh.*` (la clave `gh` es compartida con el módulo `gh`).
- `--describe-files` declara `files: []` a propósito (no se empaqueta como .deb propio, `git.sh:61-77`).

## 4. Ejecución
No es un servicio: no hay start/stop, puerto, tmux ni proceso persistente (`hasSwitch:false`,
`port:""`). Cada acción corre un `bash -c` puntual vía `ManagerNativeUtils.runShell(cmd, timeout)`
(`GitFragment.kt:98,124`) con timeouts de 10-120 s, mostrado en un AlertDialog "Ejecutando…".

## 5. Funciones de la UI (qué hay detrás)
| Control | Operación real |
|---|---|
| Gestionar proyectos | `showProjectsMenu` (symlink/copiar/sincronizar/eliminar) y fija el proyecto activo en `~/.git_active_project` |
| Clonar | `mkdir -p ~/proyectos && git clone <url>` (120 s) |
| Status / Pull | `cd <proyecto> && git status` / `git pull` |
| Commit + Push | `git add . && git commit -m <msg> ; git push` (el `;` es deliberado, línea 150-153) |
| gh auth status | `gh auth status` |
| gh login | abre `gh auth login` en la terminal (interactivo) |
| gh repo create | pide nombre y visibilidad; con proyecto activo ejecuta `gh repo create … --source=. --remote=origin --push` |
| gh pr list | `gh pr list` en el proyecto activo |

## 6. Archivos y rutas
`~/.git_active_project` (ruta del proyecto activo), `~/proyectos/` (destino de clones),
`$PREFIX/bin/git` y `gh` (paquetes apt), registry `~/.android_server_registry`
(`git.*`, `gh.*`), checkpoint `~/.install_git_checkpoint`.

## 7. Seguridad
- Entradas de usuario (ruta, URL, mensaje de commit, nombre de repo) pasan por `shellQuote(...)`
  en todos los comandos (`GitFragment.kt:142-236`): no hay concatenación sin escapar.
- No almacena tokens: `gh auth login` queda en la terminal y gh guarda sus credenciales en su
  propio directorio de config (comportamiento upstream, no verificado aquí; inferencia).
- Sin `curl | bash` ni descargas arbitrarias.

## 8. Limitaciones conocidas
- Ni `git.sh` ni `GitFragment` configuran `user.name`/`user.email`; un primer `git commit` en un
  dispositivo limpio falla ("Please tell me who you are") y, por el `;`, el flujo sigue a
  `git push` con "nothing to push". Falta un paso/diálogo de identidad.
- `git add .` agrega todo sin revisión ni `.gitignore` asistido; no hay selección de rama ni
  `git diff/log/branch/checkout`.
- Las acciones de `gh` están acotadas a 4 (el comentario `GitFragment.kt:63-67` declara pendiente:
  issues, PR crear/mergear, listar repos propios).
- La salida larga se muestra solo en un diálogo de texto sin scroll dedicado ni copia.
- Inferencia: `git clone` por HTTPS a repos privados requerirá credenciales (helper de gh) que la
  UI no configura (`gh auth setup-git` no se invoca).
- `gh` figura además como módulo `internal` separado (ver `GH.md`) con la misma clave de
  registry; la duplicación es consciente (`git.sh:85-90`).

## 9. Fecha de alta
`modulos/git.sh`: **2026-09-08**.
