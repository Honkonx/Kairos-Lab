# Expo — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación manejada por la app vía `ProcessBuilder` → `modulos/expo.sh` (438 líneas); la interacción en runtime (build, login, git push) corre directo desde `ExpoFragment.kt` (467 líneas).

---

**Proyecto:** Kairos
**Script:** `modulos/expo.sh` (v2.0.0) — espejo en `app/src/main/assets/scripts/expo.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/ExpoFragment.kt` (`ModuleDetailNavigator.kt:32`)
**`modules.json`:** `id: "expo"`, `type: "Nativo"`, `requires: ["nodejs"]`, `size ~350MB`, `hasSwitch: false` (herramienta CLI, sin proceso propio, sin puerto)

---

## 1. Descripción General

Expo (vía **EAS CLI**, Expo Application Services, paquete npm `eas-cli`) permite compilar apps React Native/Expo **en la nube** desde el propio teléfono — el build real corre en los servidores de Expo, no en el dispositivo, así que no compite por CPU/RAM con el resto del stack. Sirve para desarrollar/mantener apps React Native directamente desde Termux sin necesitar una PC.

**Clasificación: MÓDULO FUNCIONAL.** Card de estado (versión EAS, Node, usuario Expo, proyecto activo) y ~13 botones (build preview, build production, ver builds, perfiles de build, doctor, publicar OTA, submit, login, logout, info, elegir proyecto, git push, actualizar CLI) + card de mantenimiento. Sin servicio; opera sobre un proyecto elegido. No tiene proceso persistente — es un CLI que se invoca bajo demanda para build/status/submit/push.

## 2. Permisos

- **Android**: ninguno específico — no requiere almacenamiento extra más allá del wizard, no abre puertos, no necesita overlay/notificaciones.
- **Termux interno**: ninguno especial — usa `npm`/`node`/`git` ya instalados por el propio script.
- **Cuenta externa**: requiere login en `expo.dev` (`eas login`, interactivo — se abre en la terminal, la app no automatiza credenciales).

## 3. Lógica de instalación completa (`modulos/expo.sh`)

Script de 5 pasos con checkpoints (`$HOME/.install_expo_checkpoint`):

| Paso | Qué hace | Checkpoint |
|---|---|---|
| 1 | `pkg update` con su propio fallback de mirrors — reescribe `$PREFIX/etc/apt/sources.list` con un mirror fijo (`expo.sh:~145-160`) en vez de usar `pkg_update_with_fallback`; se salta si `instalar.sh`/`kairos.sh` ya lo hizo (`$ANDROID_SERVER_READY`) | `termux_update` |
| 2 | Node.js (pkg `nodejs`; instala si falta, o actualiza si `< 18`) + `git` | `nodejs_git` |
| 3 | `npm install -g eas-cli` (sin versión fija, sin sha256), reintenta `command -v eas` con `hash -r` y aplica `fix_npm_shebang_wrapper eas eas-cli` | `eas_install` |
| 4 | Genera 5 scripts de control en `~/scripts/expo/`: `eas_build.sh` (build con perfil preview/production, valida login y `package.json` antes de arrancar, corre `eas build:configure` si falta `eas.json`), `eas_status.sh` (`eas build:list`), `eas_submit.sh`, `git_push.sh` (add+commit+push del proyecto activo), `expo_info.sh` (JSON con node/npm/eas/git/usuario) | `expo_scripts` |
| 5 | Aliases en `.bashrc` (`expo-build`, `expo-status`, `expo-submit`, `expo-push`, `expo-login`, `expo-info`) + registry | `expo_aliases` |

Cierre: `verify_binary_installed eas` (ejecuta `--version`) y `registry_install expo`.

**Flags soportados**: `--silent`, `--force`, `--describe` (`{"id":"expo",...,"variants":[],"variant_required":false}`).

**Lo que NO hace a propósito**: no pide login en `expo.dev` durante la instalación (queda para después, desde la app), no pide permiso de almacenamiento (lo maneja el wizard de Kairos).

## 4. Detección de estado

- **Instalación**: `command -v eas`. Sale temprano si `eas` ya existe y no hay `--force`, **sin reparar el registry** (si `eas` existe pero falta `expo.installed`, la app lo muestra "no instalado" hasta una reinstalación forzada).
- **"Corriendo"**: no aplica — `hasSwitch: false`.

## 5. Pantalla de la app (`ExpoFragment.kt`)

Card "ESTADO" (versión EAS CLI, versión Node, usuario expo.dev, proyecto activo — se carga al abrir con `buildInfoJson()`) + botones:

| Botón | Acción real |
|---|---|
| 🔨 Build APK preview | `eas build --platform android --profile preview --non-interactive` (env `EAS_SKIP_AUTO_FINGERPRINT=1`) sobre el proyecto activo; corre `eas build:configure -p android` (60 s) primero si falta `eas.json` |
| 📦 Build producción (AAB) | Igual, perfil `production` |
| 🌐 Ver builds activos | `eas build:list --platform android --limit 5 --json` (fallback a texto plano si el JSON falla), diálogo con lista real |
| 🗂 Perfiles de build (eas.json) | Lee `eas.json["build"]` real del proyecto activo (mapa de perfiles nombrados, `docs.expo.dev/eas/json/`) y los lista en un diálogo — tocar un perfil dispara el mismo `runExpoAction("build", <perfil>)` que los botones fijos |
| 🩺 Diagnóstico (expo-doctor) | `npx expo-doctor` (90 s) sobre el proyecto activo (`docs.expo.dev/develop/expo-doctor/`), resultado en diálogo (`showDoctorDialog()`) |
| 🚀 Publicar update OTA | `eas update --branch <rama> --message <mensaje> --non-interactive` — publica cambios de JS/assets sin build nativo nuevo ni revisión de tienda. Diálogo con 2 campos (rama, default `production`; mensaje) vía `showEasUpdateDialog()` |
| 📮 EAS Submit (enviar a tienda) | `launchTerminalCommand("eas submit --platform android")` — va a terminal (no `--non-interactive`) porque en la mayoría de los casos necesita más contexto interactivo (selección de build/credenciales) |
| 👤 Login en expo.dev | `launchTerminalCommand("eas login")` — interactivo, en la terminal adaptada |
| 🔓 Cerrar sesión expo.dev | `launchTerminalCommand("eas logout")` |
| ℹ Info / estado general | `eas --version`, `node --version`, `eas whoami`, más el proyecto activo guardado |
| 📁 Configurar proyecto activo | Lista carpetas de `~/proyectos` (`File.listFiles()` directo, sin subproceso) y guarda la elegida en `~/.eas_active_project` |
| ⬆ Git push (proyecto activo) | `git add .` + `git status --short` + `git commit -m <mensaje>` + `git push` sobre el proyecto activo |
| ⬆ Actualizar EAS CLI | `launchTerminalCommand("npm install -g eas-cli@latest")` — comando npm suelto sin interacción |
| Card MANTENIMIENTO | `addMaintenanceCard()` completo: Actualizar (vía `expo.sh --silent`) + Desinstalar |

El "proyecto activo" se guarda en un archivo plano (`~/.eas_active_project`, solo la ruta) — no hay un registry de symlinks como el que usan Claude Code/OpenCode/Antigravity (`ProjectsManager.kt`); Expo usa carpetas reales de `~/proyectos` directo.

**Ejecución**: sin servicio. La app ejecuta comandos puntuales con su propio `runCommand` (ProcessBuilder con `LD_PRELOAD=libtermux-exec-ld-preload.so` para resolver el shebang de npm, con timeouts por acción, `ExpoFragment.kt:423-460`): `eas --version`, `eas whoami`, `eas build:configure -p android`, `eas build …`, `eas build:list --json`, `npx expo-doctor`, `eas update`. Login/logout/submit/actualizar CLI abren la terminal (`launchTerminalCommand`). Cada argumento va como elemento separado de `ProcessBuilder` (sin shell de por medio), así que no hace falta escapar valores como un mensaje de commit con apóstrofe.

## 6. Registry (`~/.android_server_registry`)

```
expo.installed=true
expo.version=<versión de eas-cli>
expo.install_date=<YYYY-MM-DD>
expo.commands=expo-build,expo-status,expo-submit,expo-push,expo-login,expo-info
expo.port=none
expo.location=termux_native
```

## 7. Archivos

`~/scripts/expo/*.sh`, `~/.bashrc` (aliases), `~/.eas_active_project` (proyecto activo), paquete npm global `eas-cli`, registry `expo.*`, checkpoint `~/.install_expo_checkpoint`.

## 8. Notas técnicas

- **`ACTION_VIEW` con extra "command" no funciona**: `TermuxActivity` nunca lee ese extra, así que el mecanismo real para abrir comandos en la terminal es `launchTerminalCommand()` (`BaseModuleFragment`), no un Intent con extras (comentario en `openLoginSession()`).
- **Shebang de `eas` tras `npm install -g`**: `expo.sh` hace su propio `npm install -g eas-cli` a mano (no pasa por el helper genérico `install_npm_global()` de `lib.sh`, porque necesita su propia lógica de reintentos + log completo), por lo que debe llamar explícitamente a `fix_npm_shebang_wrapper eas eas-cli` después del install. Sin eso, el symlink que npm crea para el binario `eas` puede no ejecutar directo en este dispositivo (el shebang `#!/usr/bin/env node` no corre en runtime; mismo bug de fondo documentado en `lib.sh` para OpenClaw/Cursor): `command -v eas` pasa (el archivo existe y es ejecutable) pero `verify_binary_installed eas`, que invoca el binario de verdad, falla. El problema puede ser intermitente según cómo Android reescriba el symlink en esa instalación puntual.

## 9. Seguridad

Sin `curl|bash`. Las credenciales de Expo (`eas login`) las guarda eas-cli en su propia config; Kairos no las toca ni las muestra. Parámetros de usuario (branch, mensaje, ruta) van por `ProcessBuilder` con lista de argumentos (sin shell). `git_push.sh` hace `git add .` sin revisión. Reescribir `sources.list` con un mirror fijo es un cambio persistente del sistema de paquetes.

## 10. Limitaciones conocidas

- `EXPO_SCRIPTS` (los 5 scripts de `~/scripts/expo/`) y los alias `expo-*` solo los usa la terminal; la UI actual no los llama (usa `runCommand` directo). Código duplicado/heredado.
- `git_push.sh` conserva un push propio ya extraído al módulo `git` (`GIT.md`).
- El mirror fijo de PASO 1 (`sources.list`) contradice la centralización de `lib.sh`; riesgo de dejar mirrors desactualizados.
- `eas submit` (flujo interactivo) y `eas login` solo por terminal: no hay UI propia para credenciales (decisión coherente con no manejar secretos, pero deja esas acciones fuera de la UI).
- Metadatos inconsistentes: `catalogVersion 1.0.0` / cabecera `2.0.0` / banner `v2.0.0` y repo `termux-ai-stack` en la cabecera.
- Inferencia: `eas build` en la nube no se probó (requiere cuenta Expo y red).

## 11. Fecha de alta

`modulos/expo.sh`: **2026-07-19**.
