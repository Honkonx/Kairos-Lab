# Antigravity CLI

**Módulo de Kairos** — instalación gestionada por la app vía `ModuleController.installModule()` → `ProcessBuilder` → `modulos/antigravity.sh`. Sin switch on/off (CLI puro, sin proceso persistente).

---

**Script:** `modulos/antigravity.sh`
**Fragment:** `AntigravityFragment.kt`
**`modules.json`:** `id: "antigravity"`, `hasSwitch: false`, icono `✦`
**Registry prefix:** `antigravity.*`

---

## 1. Descripción general

CLI de Google Antigravity (`agy`), binario nativo. Único módulo de Kairos con login real "Sign in with Google" implementado dentro de la propia app (no delegado a la terminal) — ver sección 6.

## 2. Permisos

Ninguno de Android además de los generales del asistente de primer uso. El login OAuth abre el navegador del sistema (`Intent.ACTION_VIEW`) para la pantalla de Google — no requiere ningún permiso especial declarado en el manifest más allá de poder lanzar Activities externas (estándar).

## 3. Variantes

Ninguna — un solo canal de instalación (`--describe` reporta `"variants":[]`).

## 4. Lógica de instalación (paso a paso)

1. **PASO 1 — Dependencias del sistema** (checkpoint `deps`):
   - Detecta si falta `glibc-runner` (`$PREFIX/glibc/lib/ld-linux-aarch64.so.1`) — si falta, instala `glibc-repo` y **luego** corre `pkg update` antes de instalar `glibc-runner` en sí (sin ese paso intermedio, el paquete todavía no aparece en los índices recién agregados y la instalación falla con "paquete no encontrado").
   - Instala también `curl`, `ca-certificates`, `resolv-conf` si faltan.
   - **Detección de LSE atomics** (`grep atomics /proc/cpuinfo`) — si el CPU no lo soporta, exige `qemu-user-aarch64` como fallback (aborta con instrucciones si no está, en vez de fallar silenciosamente más adelante).
2. **PASO 2 — Descarga e instalación de binarios** (checkpoint `binaries`):
   - Descarga `antigravity-termux-standalone.tar.gz` del fork `Honkonx/antigravity-cli-termux` (release `latest`), a un workdir temporal en `$HOME/.agy_install` (nunca `/tmp/`).
   - Extrae específicamente `agy` y `agy.va39` del tarball.
   - Instala ambos en `$PREFIX/bin/` con `install -m 0755`.
   - `trap '_agy_cleanup' EXIT` — si el script termina sin haber marcado `AGY_INSTALL_OK=1`, borra el workdir de trabajo (no deja basura a medio instalar).
3. **PASO 3 — Verificación y registro**: corre `agy --version`; si no responde, no escribe un placeholder tipo `"installed"` en el campo de versión del registry — lo deja vacío a propósito (la UI ya filtra versión vacía con `isNotEmpty()`, pero un string como `"installed"` se concatenaría tal cual como `"vinstalled"` en la card del módulo).

## 5. Detección de estado

`_check_installed()`: `command -v agy` y existen ambos archivos `$PREFIX/bin/agy` + `$PREFIX/bin/agy.va39`. Si ya está instalado y no hay `--force`, sale con `exit 0` sin re-sincronizar el registry.

Del lado de la app, módulo **sin switch** — `isModuleInstalled()` gatea la UI.

## 6. Login "Sign in with Google"

A diferencia de Claude/Codex, el login de Antigravity **no se hace desde la terminal** — está implementado como OAuth nativo dentro de la propia app (`com.termux.app.oauth.AntigravityOAuth`).

- El refresh token se guarda en texto plano en `~/.config/agy/oauth_refresh_token`.
- **Riesgo asumido a propósito, documentado en el propio código**: impersonar el cliente OAuth de Antigravity viola los Términos de Servicio de Google.
- `AntigravitySecrets.kt` no tiene credenciales reales cargadas por defecto — el login falla en el intercambio de token (`invalid_client`) hasta que se completen esos valores a mano.
- Flujo: `startGoogleSignIn()` → `AntigravityOAuth().signIn { url -> abre el navegador }` → al volver, guarda el refresh token → `refreshUi()` recompone la pantalla mostrando "conectada".
- El flujo de login abre el navegador externo (`ACTION_VIEW`) y el usuario sale de la app un rato real; `loginRunning` se libera siempre (no depende de que el Fragment siga adjunto) para no dejar el sign-in "trabado" si el usuario vuelve después.

## 7. Pantalla de la app (`AntigravityFragment.kt`)

- **Card ESTADO**: método (`native·binario`), versión (`antigravity.version` del registry), pill de estado, pill "Cuenta Google" (conectada/sin conectar según si existe el token file), pill de terminal.
- **"⌨ Abrir en terminal (agy)"** — `launchTerminalCommand("agy")`.
- **"💬 Prompt directo (agy -p)"** — diálogo de texto libre → `launchTerminalCommand("agy -p '<prompt>'")`. El flag `-p`/`--print`/`--prompt` está confirmado en la documentación oficial de Antigravity.
- **"📁 Abrir en proyecto"** — mismo `~/proyectos` compartido que Claude/Codex/OpenCode.
- **"🗂 Gestionar proyectos"** — symlink/importar/eliminar/sincronizar.
- **Conectar memoria de Engram** — `engramSetupButton("antigravity-cli")`.
- **"🔑 Iniciar sesión con Google" / "🚪 Cerrar sesión con Google"** — condicional según `loggedIn` (existencia del token file).
- **"↻ Actualizar (agy update)"** — reinstala vía `ModuleController.installModule()`. El propio CLI no tiene un subcomando `update` real — el instalador re-ejecuta la instalación completa con `AGY_MODE=update`.
- **"↻ Continuar última sesión"** — `agy --continue`, retoma la última conversación.
- **Card "MANTENIMIENTO" — "🗑 Desinstalar"** — `confirmUninstallModule()`.

## 8. Registry

```
antigravity.installed=true
antigravity.version=<x.y[.z]>       # vacío si no se pudo verificar, nunca un placeholder tipo "installed"
antigravity.install_date=<YYYY-MM-DD>
antigravity.location=termux_native
antigravity.binary=<ruta al binario agy>
```

## 9. Modelos y MCP

`agy --model "<nombre>"` — 8 modelos reales soportados (familia Gemini 3.x, Claude Sonnet 4.6, Claude Opus 4.6, GPT-OSS 120B). Sin subcomando real tipo "agy models list" para poblar un selector cerrado — se ofrece como campo de texto libre opcional dentro del diálogo de "Prompt directo".

MCP: Antigravity reusa los MCP servers ya importados de Gemini CLI (mismo directorio `~/.gemini/`, ya que Antigravity es el sucesor de Gemini CLI) — sin subcomando propio de gestión MCP más allá de eso.

## 10. Gotchas conocidos

- **Riesgo de OAuth explícito** — ver sección 6, decisión consciente de diseño.
- **El botón "Actualizar" reinstala completo** cada vez que se toca, no hay forma de forzar solo verificación de versión sin reinstalar.
