# Expo

**Módulo Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación manejada por la app vía `ProcessBuilder` → `modulos/expo.sh`; la interacción en runtime (build, login, git push) corre directo desde `ExpoFragment.kt`.

---

**App:** Kairos (fork termux-app)
**Script:** `modulos/expo.sh` — espejo en `app/src/main/assets/scripts/expo.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/ExpoFragment.kt`
**`id` en `modules.json`:** `expo` — `hasSwitch: false` (herramienta CLI, sin proceso propio)

---

## 1. Descripción General

Expo (vía **EAS CLI**, Expo Application Services) permite compilar apps React Native/Expo **en la nube** desde el propio teléfono — el build real corre en los servidores de Expo, no en el dispositivo, así que no compite por CPU/RAM con el resto del stack. Sirve para desarrollar/mantener apps React Native directamente desde Termux sin necesitar una PC.

No tiene proceso persistente (`hasSwitch: false`) — es un CLI que se invoca bajo demanda para build/status/submit/push.

## 2. Permisos

- **Android**: ninguno específico — no requiere almacenamiento extra más allá del wizard, no abre puertos, no necesita overlay/notificaciones.
- **Termux interno**: ninguno especial — usa `npm`/`node`/`git` ya instalados por el propio script.
- **Cuenta externa**: requiere login en `expo.dev` (`eas login`, interactivo — se abre en la terminal, la app no automatiza credenciales).

## 3. Lógica de instalación completa (`modulos/expo.sh`)

Script de 5 pasos con checkpoints (`$HOME/.install_expo_checkpoint`):

| Paso | Qué hace | Checkpoint |
|---|---|---|
| 1 | `pkg update` (con fallback a 2 mirrors) — se salta si el instalador global ya lo hizo (`$ANDROID_SERVER_READY`) | `termux_update` |
| 2 | Node.js (instala si falta, o actualiza si `< 18`) + `git` | `nodejs_git` |
| 3 | `npm install -g eas-cli` | `eas_install` |
| 4 | Genera 5 scripts de control en `~/scripts/expo/`: `eas_build.sh` (build con perfil preview/production, valida login y `package.json` antes de arrancar, corre `eas build:configure` si falta `eas.json`), `eas_status.sh` (`eas build:list`), `eas_submit.sh`, `git_push.sh` (add+commit+push del proyecto activo), `expo_info.sh` (JSON con node/npm/eas/git/usuario) | `expo_scripts` |
| 5 | Aliases (`expo-build`, `expo-status`, `expo-submit`, `expo-push`, `expo-login`, `expo-info`) + registry | `expo_aliases` |

**Flags soportados**: `--silent`, `--force`, `--describe` (`{"id":"expo",...,"variants":[],"variant_required":false}`).

**Lo que NO hace a propósito**: no pide login en `expo.dev` durante la instalación (queda para después, desde la app), no pide permiso de almacenamiento (lo maneja el wizard de Kairos).

## 4. Detección de estado

- **Instalación**: `command -v eas` (el script no marca ningún estado especial más allá de esto — sale temprano si `eas` ya existe y no hay `--force`).
- **"Corriendo"**: no aplica — `hasSwitch: false`.

## 5. Pantalla real de la app (`ExpoFragment.kt`)

Card "ESTADO" (versión EAS CLI, versión Node, usuario expo.dev, proyecto activo) + botones:

| Botón | Acción real |
|---|---|
| 🔨 Build APK preview | `eas build --platform android --profile preview --non-interactive` (env `EAS_SKIP_AUTO_FINGERPRINT=1`) sobre el proyecto activo |
| 📦 Build producción (AAB) | Igual, perfil `production` |
| 🌐 Ver builds activos | `eas build:list --platform android --limit 5 --json` (fallback a texto plano si el JSON falla) |
| 🗂 Perfiles de build (eas.json) | Lee `eas.json["build"]` real del proyecto activo (mapa de perfiles nombrados, `docs.expo.dev/eas/json/`) y los lista en diálogo — tocar un perfil dispara el mismo `runExpoAction("build", <perfil>)` que los botones fijos de arriba, cubriendo cualquier perfil custom más allá de preview/production |
| 🩺 Diagnóstico (expo-doctor) | `npx expo-doctor` sobre el proyecto activo, resultado en diálogo (`showDoctorDialog()`) |
| 🚀 Publicar update OTA | `eas update --branch <rama> --message <mensaje> --non-interactive` sobre el proyecto activo — publica cambios de JS/assets sin build nativo nuevo ni revisión de tienda. Diálogo con 2 campos (rama, default `production`; mensaje) vía `showEasUpdateDialog()` |
| 📮 EAS Submit (enviar a tienda) | `launchTerminalCommand("eas submit --platform android")` — va a terminal (no `--non-interactive` como Build/Update) porque en la mayoría de los casos necesita más contexto interactivo (selección de build/credenciales) |
| 👤 Login en expo.dev | `launchTerminalCommand("eas login")` — interactivo, en la terminal adaptada |
| 🔓 Cerrar sesión expo.dev | `launchTerminalCommand("eas logout")` |
| ℹ Info / estado general | `eas --version`, `node --version`, `eas whoami`, más el proyecto activo guardado |
| 📁 Configurar proyecto activo | Lista carpetas de `~/proyectos` (`File.listFiles()` directo, sin subproceso) y guarda la elegida en `~/.eas_active_project` |
| ⬆ Git push (proyecto activo) | `git add .` + `git status --short` + `git commit -m <mensaje>` + `git push` sobre el proyecto activo |
| ⬆ Actualizar EAS CLI | `launchTerminalCommand("npm install -g eas-cli@latest")` |

**Card MANTENIMIENTO**: `addMaintenanceCard()` completo (Actualizar vía `expo.sh --silent` + Desinstalar).

El "proyecto activo" se guarda en un archivo plano (`~/.eas_active_project`, solo la ruta) — no hay un registry de symlinks tipo el que usan Claude Code/OpenCode/Antigravity (`ProjectsManager.kt`), Expo usa carpetas reales de `~/proyectos` directo.

Toda la lógica de runtime vive en Kotlin (`ExpoFragment.kt`) — sin pasar por ningún script Python intermedio, sin necesidad de escapado manual de shell (cada argumento va como elemento separado de `ProcessBuilder`, no hay shell de por medio que pueda reinterpretar caracteres especiales).

## 6. Registry (`~/.android_server_registry`)

```
expo.installed=true
expo.version=<versión de eas-cli>
expo.install_date=<YYYY-MM-DD>
expo.commands=expo-build,expo-status,expo-submit,expo-push,expo-login,expo-info
expo.port=none
expo.location=termux_native
```

## 7. Notas de implementación

- **`ACTION_VIEW` con extra "command" no funciona**: `TermuxActivity` nunca lee ese extra — el mecanismo real es `launchTerminalCommand()` (`BaseModuleFragment`), no un Intent con extras.
- Kotlin invoca los binarios directo por `ProcessBuilder` con cada argumento como elemento de lista, sin shell de por medio, evitando cualquier problema de escapado en mensajes de commit u otros valores con caracteres especiales.
- **`eas-cli` puede instalarse pero no ejecutar**: en Termux/Android, el symlink que `npm` genera para un binario Node (shebang `#!/usr/bin/env node`) a veces no queda ejecutable directo aunque el archivo exista con permiso de ejecución — por eso la instalación verifica que `eas` responda de verdad (`eas --version`) en vez de solo confirmar que el archivo esté presente, y reescribe el wrapper si hace falta.

## Controles de la pantalla

| Control | Qué hace | Por qué |
|---|---|---|
| Card ESTADO (EAS CLI/Node/Usuario/Proyecto) | Solo lectura, se carga al abrir | — |
| "🔨 Build APK preview" | `eas build --profile preview --non-interactive` | Corre `eas build:configure` primero si falta `eas.json` |
| "📦 Build producción (AAB)" | Mismo camino, perfil `production` | — |
| "🌐 Ver builds activos" | `eas build:list --json`, diálogo con lista real | — |
| "🗂 Perfiles de build (eas.json)" | Lee perfiles reales del `eas.json` del proyecto, tocar uno dispara un build con ese perfil | Cubre cualquier perfil custom, no solo los 2 hardcodeados |
| "🩺 Diagnóstico (expo-doctor)" | `npx expo-doctor`, diálogo con el reporte | — |
| "🚀 Publicar update OTA" | Diálogo (rama + mensaje) → `eas update --non-interactive` | Flujo completo de EAS (build + update) |
| "📮 EAS Submit (enviar a tienda)" | Terminal — `eas submit --platform android` | Necesita más contexto interactivo (selección de build/credenciales) que un diálogo simple |
| "👤 Login en expo.dev" / "🔓 Cerrar sesión" | `eas login` / `eas logout` en terminal | — |
| "ℹ Info / estado general" | Re-corre `buildInfoJson()` | — |
| "📁 Configurar proyecto activo" | Lista carpetas de `~/proyectos`, elegir una escribe `~/.eas_active_project` | — |
| "⬆ Git push (proyecto activo)" | `git add . && git commit && git push` sobre el proyecto activo | — |
| "⬆ Actualizar EAS CLI" | Terminal — `npm install -g eas-cli@latest` | Comando npm suelto sin interacción, no justifica una acción dedicada |
| Card MANTENIMIENTO completa (no solo Desinstalar) | Actualizar + Desinstalar | — |
