# GitHub CLI (`gh`) — Módulo de Kairos (interno)

> Fuentes: `modulos/gh.sh`, entrada de `modules.json`, `ModuleDetailNavigator.kt`, `GenericModuleFragment.kt`.

## 1. Qué es y para qué sirve
Instala el cliente oficial de GitHub (`gh`, paquete apt de Termux) para repos/PRs/issues desde
terminal.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**. Solo instala una herramienta y ofrece "abrir en terminal"
(`terminalCommand: "gh"`). Además está marcado `"internal": true` en `modules.json`: no aparece en
la Tienda ni en la lista de módulos (`ModuleInfo.kt:50`). No tiene Fragment propio: no figura en
`ModuleDetailNavigator.kt`, cae en `GenericModuleFragment` (estado, "Abrir en terminal",
"Gestionar proyectos", mantenimiento). ~4-5 opciones de UI genéricas. La experiencia real de
`gh` vive en el módulo `git` (`GitFragment`, ver `GIT.md`), que también instala `gh`.

## 3. Instalación
`gh.sh --silent [--force]` → `install_single_pkg "gh" "gh" gh` (`gh.sh:167`): `pkg install -y gh`,
idempotente (`command -v`), `verify_binary_installed gh` (ejecuta `gh --version`) y
`registry_install`. Sin variantes, sin dependencias, sin descargas directas (apt oficial, sin
sha256 propio). `--describe-files` con `files: []` deliberado. No hace login (`gh auth login`
queda manual, `gh.sh:12-14`).

## 4. Ejecución
No es servicio (sin puerto, tmux ni switch). Solo CLI bajo demanda.

## 5. Funciones de la UI
Las del `GenericModuleFragment`: estado instalado/no, botón abrir terminal con `gh` (con elección
de carpeta), gestionar proyectos, actualizar / diagnosticar / desinstalar.

## 6. Archivos y rutas
`$PREFIX/bin/gh`; registry `gh.*`; checkpoint `~/.install_gh_checkpoint`. Credenciales: las
guarda `gh` en su config (no gestionadas por Kairos).

## 7. Seguridad
Sin secretos manejados por Kairos, sin `curl|bash`, sin descargas fuera de apt. Riesgo mínimo.

## 8. Limitaciones conocidas
- Módulo oculto (`internal`) pero con script, catálogo y manifiestos propios: es
  funcionalmente un subconjunto de `git.sh`, que ya instala `gh` (duplicación consciente).
- No existe flujo de login en UI propia (queda en `GitFragment` → "gh login" por terminal).
- Inferencia: al ser `internal`, un usuario no puede instalarlo solo desde la Tienda; llega vía `git`.

## 9. Fecha de alta
`modulos/gh.sh` agregado el **2026-08-31**.
