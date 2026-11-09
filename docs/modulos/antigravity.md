# Antigravity CLI — Módulo de Kairos

**Módulo de Kairos** — instalación gestionada por la app vía `ModuleController.installModule()` → `ProcessBuilder` → `modulos/antigravity.sh`. Sin switch on/off (CLI puro, sin proceso persistente).

---

**Script:** `modulos/antigravity.sh` (275 líneas; v1.0.0, adaptado del script interactivo `install_antigravity.sh` v1.1.0)
**Fragment:** `AntigravityFragment.kt` (343 líneas)
**`modules.json`:** `id: "antigravity"`, `hasSwitch: false`, icono `✦`
**Registry prefix:** `antigravity.*`

---

## 1. Descripción general

CLI de Google Antigravity (`agy`), binario nativo ARM64 empaquetado para Termux por el fork `Honkonx/antigravity-cli-termux`. Corre sobre `glibc-runner`. Es el único módulo de Kairos con una pantalla de "Iniciar sesión con Google" implementada dentro de la propia app (no delegada a la terminal) — ver sección 6.

**Clasificación: MÓDULO FUNCIONAL** (pantalla propia, `ModuleDetailNavigator.kt:25`): login OAuth propio (servidor loopback + navegador), prompt directo con modelo, continuar sesión, proyectos. ~11 controles. Sin servicio ni puerto persistente (el loopback OAuth es temporal).

## 2. Permisos

Ninguno de Android además de los generales del wizard. El login OAuth abre el navegador del sistema (`Intent.ACTION_VIEW`) para la pantalla de Google — no requiere ningún permiso especial declarado en el manifest más allá de poder lanzar Activities externas (estándar).

## 3. Variantes

Ninguna — un solo canal de instalación (`--describe` reporta `"variants":[]`).

## 4. Lógica de instalación (paso a paso)

Flags (`antigravity.sh:48-51`): `--silent`, `--force`, `--describe`, `--describe-files`.

1. **PASO 1 — Dependencias del sistema** (checkpoint `deps`):
   - Detecta si falta `glibc-runner` (`$PREFIX/glibc/lib/ld-linux-aarch64.so.1`) — si falta, instala `glibc-repo` y **luego** corre `pkg update` ANTES de instalar `glibc-runner` en sí (sin el `pkg update` intermedio, `glibc-runner` todavía no aparece en los índices recién agregados y la instalación falla con "paquete no encontrado"; la secuencia es repo → update → paquete).
   - Instala también `curl`, `ca-certificates`, `resolv-conf` si faltan.
   - **Detección de LSE atomics** (`grep atomics /proc/cpuinfo`) — si el CPU no lo soporta, exige `qemu-user-aarch64` como fallback (aborta con instrucciones si no está, en vez de fallar silenciosamente más adelante).
2. **PASO 2 — Descarga e instalación de binarios** (checkpoint `binaries`):
   - Descarga `antigravity-termux-standalone.tar.gz` del fork `Honkonx/antigravity-cli-termux` (release `latest`, `curl -fL https://github.com/Honkonx/antigravity-cli-termux/releases/latest/download/antigravity-termux-standalone.tar.gz`), a un workdir temporal en `$HOME/.agy_install` (nunca `/tmp/`).
   - Extrae específicamente `agy` y `agy.va39` del tarball.
   - Instala ambos en `$PREFIX/bin/` con `install -m 0755`.
   - `trap '_agy_cleanup' EXIT` — si el script termina sin haber marcado `AGY_INSTALL_OK=1`, borra el workdir de trabajo (no deja basura a medio instalar).
   - Sin sha256/checksum y sin versión fijada (`latest`).
3. **PASO 3 — Verificación y registro**: `verify_binary_installed agy` (ejecuta `agy --version`); si falla escribe `installed=false reason=binary_no_ejecuta`. Si `agy --version` no responde, **no** se escribe un placeholder tipo `"installed"` en el campo de versión del registry — queda vacío a propósito (un string como `"installed"` se concatenaría tal cual como `"vinstalled"` en la card del módulo; la UI ya filtra versión vacía con `isNotEmpty()`).

## 5. Detección de estado

"Ya instalado" = `command -v agy` Y existen ambos archivos `$PREFIX/bin/agy` + `$PREFIX/bin/agy.va39`. Si ya está instalado y no hay `--force`, sale con `exit 0` sin re-sincronizar el registry.

Del lado de la app, módulo **sin switch** — `isModuleInstalled()` gatea la UI.

## 6. Login "Sign in with Google" — pieza de Kairos, no del script

El login de Antigravity **no se hace desde la terminal** — está implementado como OAuth nativo dentro de la propia app (`com.termux.app.oauth.AntigravityOAuth`, portado de un proyecto de código abierto bajo GPLv3, licencia compatible con Kairos).

- El refresh token se guarda en texto plano en `~/.config/agy/oauth_refresh_token` — ruta definida por Kairos (no hay convención previa). Se escribe con `File.writeText`, sin chmod explícito (`AntigravityFragment.kt:262-263`); el home de Termux es privado a la app. La UI no lo muestra.
- **Riesgo asumido a propósito, documentado en el propio código** (`AntigravityFragment.kt`, comentario completo en `AntigravityOAuth.kt`): impersonar el cliente OAuth de Antigravity puede violar los Términos de Servicio de Google.
- `AntigravitySecrets.kt` **no tiene credenciales reales cargadas por defecto** (`CLIENT_ID`/`CLIENT_SECRET` vacíos) — el login falla en el intercambio de token (`invalid_client`) hasta que se completen esos valores a mano.
- Flujo: `startGoogleSignIn()` → `AntigravityOAuth().signIn { url -> abre el navegador }` → al volver, guarda el refresh token → `refreshUi()` recompone la pantalla mostrando "conectada".
- El token guardado solo lo lee `AntigravityFragment`; ningún script ni el binario `agy` lo consume (inferencia: `agy` hace su propio login al primer arranque; `antigravity.sh` imprime "Autenticación: la primera vez que ejecutes 'agy'").

## 7. Pantalla de la app (`AntigravityFragment.kt`)

| Control | Qué hace | Notas |
|---|---|---|
| Card ESTADO (Método/Versión/Estado/Cuenta Google/Terminal) | Solo lectura: método (`native·binario`), versión (`antigravity.version` del registry), pill de estado, pill "Cuenta Google" (según exista `~/.config/agy/oauth_refresh_token`), pill de terminal + cerrar | — |
| "⌨ Abrir en terminal (agy)" | ¿Dónde abrir? → `launchTerminalCommand("agy")` | — |
| "💬 Prompt directo (agy -p)" | Diálogo con prompt + modelo opcional en texto libre → `agy [--model 'x'] -p '<prompt>'` en terminal (`runDirectPrompt()`) | El flag `-p`/`--print`/`--prompt` existe en el CLI. Prompt y modelo con `shellEscape` |
| "↻ Continuar última sesión" | `agy --continue` (con diálogo de carpeta) | — |
| "📁 Abrir en proyecto" | Lista proyectos reales de `~/proyectos` (carpeta compartida con Claude/Codex/OpenCode) y abre `cd ... && agy` | — |
| "🗂 Gestionar proyectos" | Menú compartido symlink/importar/copiar/sincronizar/eliminar | — |
| Conectar memoria de Engram | `engramSetupButton("antigravity-cli")` (ver `ENGRAM.md`) | Ejecuta `engram setup antigravity-cli \|\| engram setup`: si el slug es rechazado, cae al selector interactivo de Engram en la misma sesión de terminal |
| "🔑 Iniciar sesión con Google" / "🚪 Cerrar sesión con Google" | Condicional según `loggedIn` (existencia del token file). Login: OAuth `AntigravityOAuth` (hilo + `ServerSocket` loopback + `ACTION_VIEW`); cerrar sesión borra el token file | Ver riesgos y estado de credenciales en la sección 6 |
| "↻ Actualizar (agy update)" | Reinstala vía `ModuleController.installModule()` / `reinstallModuleService()` | El CLI no tiene subcomando `update` propio; la actualización re-ejecuta el instalador completo. Ver gotchas |
| "🗑 Desinstalar" | `confirmUninstallModule()` (botón suelto, no la card de mantenimiento completa, para no duplicar "Actualizar") | — |

**Modelos**: `agy --model "<nombre>"` acepta 8 modelos (familia Gemini 3.x, Claude Sonnet 4.6, Claude Opus 4.6, GPT-OSS 120B). No hay subcomando tipo "agy models list" para poblar un selector cerrado, así que el modelo es un campo de texto libre opcional (el nombre exacto puede variar entre versiones, ej. "Gemini 3.1 Pro (High)").

**MCP**: Antigravity reusa los MCP servers ya importados de Gemini CLI (mismo `~/.gemini/` como home real); no hay subcomando propio de gestión MCP documentado, por lo que Kairos no agrega nada.

**Detalle no obvio**: el flujo de login abre el navegador externo (`ACTION_VIEW`) y el usuario sale de la app un rato; es el flujo más propenso a que el Fragment se desadjunte a mitad de camino, por eso `loginRunning` se libera SIEMPRE (no depende de que el Fragment siga adjunto) para no dejar el sign-in "trabado" si el usuario vuelve después.

## 8. Registry

```
antigravity.installed=true
antigravity.version=<x.y[.z]>       # vacío si no se pudo verificar, nunca un placeholder tipo "installed"
antigravity.install_date=<YYYY-MM-DD>
antigravity.location=termux_native
antigravity.binary=<ruta al binario agy>
```

## 9. Archivos y rutas

`$PREFIX/bin/agy`, `$PREFIX/bin/agy.va39`, `~/.config/agy/oauth_refresh_token`, `~/.agy_install` (temporal), checkpoint `~/.install_antigravity_checkpoint`.

## 10. Seguridad

- Refresh token de Google guardado en texto plano sin permisos restrictivos explícitos (depende del umask y del sandbox de Android; riesgo moderado). La UI no lo muestra.
- El login impersona el cliente OAuth del IDE Antigravity (riesgo de ToS declarado en el propio código).
- Descarga sin checksum desde un fork propio (confianza en `Honkonx/antigravity-cli-termux`).

## 11. Gotchas y limitaciones conocidas

- **Login Google inoperante tal como está el repo**: `AntigravitySecrets.CLIENT_ID/CLIENT_SECRET` están vacíos, así que el botón visible siempre falla en el intercambio de token. Es una decisión consciente documentada, útil recordarla si Google bloquea el cliente.
- **"Actualizar" es efectivamente un no-op si ya está instalado**: reinstala sin `--force` y el script sale temprano si ya está instalado (`:124`). Mismo gap que Claude/Codex/OpenCode.
- Sin checksum ni versión pineada.
- Inferencia: otros módulos glibc de Kairos requieren `unset LD_PRELOAD` al lanzar; aquí la UI llama `agy` directo (`AntigravityFragment.kt:117`). Si el binario fallara por ese motivo sería un bug; no verificado en dispositivo.

## 12. Fecha de alta

`modulos/antigravity.sh`: **2026-07-24**.
