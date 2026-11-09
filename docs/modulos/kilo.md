# Kilo Code — ficha de módulo (`kilo`)

> Ficha basada en la lectura del código. **Confirmado** = leído en el código citado (archivo:línea); **Sin verificar** = inferencia no comprobada en dispositivo.

## 1. Qué es y para qué sirve
CLI de código (fork del motor OpenCode, `Kilo-Org/kilocode`) con su propio ecosistema de proveedores; comando `kilo` (`kilo.sh:3-17`, `:39-43`). Se mantiene como módulo separado de `opencode`.

## 2. Clasificación
**COMPLEMENTO / EXTENSIÓN**

Justificación (confirmado): sin servicio, sin estado propio, sin función nativa; acciones = lanzadores de terminal.

Opciones de UI (aprox.): **~9** (5 comunes + Login + Enviar prompt (con modelo opcional) + Continuar sesión + Uso y costos).

## 3. Instalación (`modulos/kilo.sh`)
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`kilo.sh:63-71`). Versión 2.0.0 (binario nativo + glibc, sin npm, `:20-33`).
- PASO 1 instala `glibc-repo glibc patchelf-glibc curl` si faltan (`:251-273`). PASO 2 `_kilo_download_native` (`:151-224`): lee `releases?per_page=20` de `Kilo-Org/kilocode`, toma el primer asset `*linux-arm64.tar.gz` (evita las releases del plugin JetBrains, `:135-150`), extrae en `~/.local/share/kilo`, prefiere el archivo llamado `kilo` (el tarball trae también `bwrap`, `kilo-sandbox-*`, `:174-188`), `patchelf --set-interpreter` y wrapper `$PREFIX/bin/kilo` con `unset LD_PRELOAD` y `TMPDIR=$PREFIX/tmp` (`:203-217`).
- Verificación: `kilo --version` funcional (`:219`). Registry: `installed, version, channel=native_arm64, install_date` (`:302-306`).
- Descarga **sin sha256** (`curl -fsSL`, `:170`).
- `modules.json`: `installMethods: ["npm"]`, `requires: ["nodejs"]`, `arch: "bionic"`.

## 4. Ejecución
- Comando: `kilo` (`terminalCommand` en `modules.json`; `baseCommand` en `CLI_MODULE_CONFIGS`, `CliToolFragment.kt:273-284`).
- Fragment: `CliToolFragment` (`app/src/main/java/com/termux/app/ui/CliToolFragment.kt`, enrutado desde `ModuleDetailNavigator.kt:59`).
Todas las acciones de la UI terminan en `launchCliTerminalCommand()` (`CliToolFragment.kt:451-454`) -> `BaseModuleFragment.launchTerminalCommand()` (`BaseModuleFragment.kt:994`) -> `TermuxActivity.openTerminalWithCommand(cmd, sessionName = nombre del módulo)`. Es decir: la app **no** ejecuta el CLI por `ProcessBuilder` ni captura su salida; abre una sesión de terminal real y escribe el comando. No hay servicio de fondo, ni puerto (`port: ""`), ni switch on/off (`hasSwitch: false`) en `modules.json`. El estado "instalado" se detecta por binario en PATH (`ModuleInstalled.kt`, `LiveCheck.PATH_BINARY`).
- `needsGlibcCompat = true` -> `unset LD_PRELOAD; ` antepuesto (`CliToolFragment.kt:281`, `:452`); el propio wrapper ya hace `unset LD_PRELOAD`.

## 5. Funciones y opciones de la UI
Controles comunes a todos los CLI de `CliToolFragment` (`CliToolFragment.kt:466-620`):
- Card ESTADO (`:472-476`): id, versión leída del registry (`<id>.version`, `:622-628`) y comando base. Solo lectura.
- Card USAR (`:478-497`): **Abrir en terminal** (`promptOpenLocation` -> comando por defecto o `cd '<carpeta>' && kilo`) y **Gestionar proyectos** (`showProjectsMenu`, lanza `cd '<proyecto>' && kilo`).
- Card MANTENIMIENTO (`:604-619`): **Actualizar** (`updateModuleService`), **Diagnosticar** (`runModuleDoctorForThis`) y **Desinstalar** (diálogo con checkbox "desinstalación profunda", `confirmUninstall` `:863-897`).

Controles específicos de este módulo (`CliToolFragment.kt:273-284`):
- **Iniciar sesión**: `kilo auth login` (`:276`).
- **Enviar prompt**: `kilo run '<prompt>'`; con modelo `kilo run '<prompt>' -m '<modelo>'` (formato proveedor/modelo en texto libre, `:278-280`).
- **Continuar última sesión**: `kilo --continue` (con diálogo de carpeta, `:282`).
- **Uso y costos**: `kilo stats` (solo lectura, `:283`).

## 6. Archivos y rutas que crea
- `~/.local/share/kilo/` (binario `kilo` parcheado, más `bwrap`, `kilo-sandbox-seccomp`, scripts .js del tarball).
- `$PREFIX/bin/kilo` (wrapper bash).
- `~/tmp/kilo_*` temporal (borrado). `~/.install_kilo_checkpoint` (borrado). Registry: `kilo.*`.
- Config/estado de `kilo` en su propio directorio (no creado por el script).

## 7. Seguridad
- Descarga de binario ejecutable sin verificación de integridad; se ejecuta con permisos de la app. Los binarios auxiliares del tarball (`bwrap`, seccomp) quedan sin parchear y no se invocan desde el wrapper.
- Prompt/modelo escapados con `shellEscape`.
- Sin `curl | bash`. Las credenciales de proveedor las maneja el propio CLI (`kilo auth login`), la app no las guarda.

## 8. Problemas y gaps detectados
- Confirmado: `modules.json` sigue declarando `installMethods: ["npm"]`, `requires: ["nodejs"]` y `arch: "bionic"`, pero `kilo.sh` v2.0.0 no usa npm y el binario es glibc parcheado: el catálogo está desalineado con el script.
- Confirmado: no hay plan en `deepUninstallPlan` para `kilo`; la desinstalación profunda solo resetea estado (`ModuleController.kt:1067-1068`) y deja `~/.local/share/kilo` y el wrapper.
- Confirmado: falta sha256 del tarball.
- Confirmado: el modelo se introduce en texto libre; no se valida ni se lista (`kilo models` existe pero requiere parámetros y se dejó fuera, comentario `CliToolFragment.kt:270-272`).
- Sin verificar: la dependencia de `GET api.github.com` sin token (límite de 60 req/h por IP) puede hacer fallar la instalación en redes compartidas.

## 9. Fecha de alta
2026-08-14.
