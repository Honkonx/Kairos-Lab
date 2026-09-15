# Codex CLI

**Módulo de Kairos** — instalación gestionada por la app vía `ModuleController.installModule()` → `ProcessBuilder` → `modulos/codex.sh`. Sin switch on/off (CLI puro, sin proceso persistente).

---

**Script:** `modulos/codex.sh`
**Fragment:** `CodexFragment.kt`
**`modules.json`:** `id: "codex"`, `hasSwitch: false`
**Registry prefix:** `codex.*`

---

## 1. Descripción general

Codex CLI es el asistente de código de OpenAI en terminal. Kairos ofrece 2 canales completamente independientes: `termux` (default, vía npm) y `native` (binario ARM64 prebuilt, sin Node.js).

## 2. Permisos

Ninguno específico de Android. No expone puerto ni corre en segundo plano.

## 3. Variantes/canales

| Canal | Paquete/fuente | Node.js necesario | Notas |
|---|---|---|---|
| `termux` (default) | Paquete npm mantenido por un tercero | Sí (`nodejs-lts`) | No es un paquete oficial de OpenAI |
| `native` (`--variant native`) | Binario ARM64 prebuilt, versión pineada | No | Repo de un solo mantenedor — riesgo asumido a propósito, versión fija (no `latest`) por eso mismo |

## 4. Lógica de instalación

### Canal `native` (rama separada, sale antes de tocar Node.js)
1. Descarga el binario ARM64 a un directorio de trabajo dedicado (nunca `/tmp/` — algunas versiones de Android lo montan `noexec`).
2. Extrae, busca el binario, lo copia a una ruta propia y crea un symlink en `$PREFIX/bin/codex`.
3. **Verificación real de ejecución** (`codex --version`), no solo que el archivo exista — un binario ARM64 no siempre corre sobre Bionic. Si falla, borra el binario y el symlink y aborta.
4. Registry con `channel=native`.

### Canal `termux` (default)
1. **PASO 1 — Node.js**: instala `nodejs-lts` si no está.
2. **PASO 2 — instalación vía npm** — captura el exit code real del `npm install` (no el del pipe intermedio de logging) para no confiar en un exit code falso de un comando aguas abajo, aborta si `npm` realmente falló.
3. **PASO 3 — Login**: en modo `--silent`, se omite explícitamente (no se puede automatizar sin credenciales). En modo interactivo, ofrece correr `codex login` ahí mismo.
4. Registry con `channel=termux`, versión real leída de `codex --version`.

## 5. Elección de canal npm — nota importante sobre dist-tags

El script instala explícitamente el dist-tag `@latest` del paquete, nunca `@next` — la
documentación del paquete describe `next` como versiones candidatas publicadas tras validación de
CI (todavía no promovidas) y recomienda `@latest` para usuarios finales. En este paquete puntual
`next` puede quedar por debajo de `latest` (al revés de la convención usual de otros paquetes
npm), así que instalar `@next` a ciegas puede terminar en una versión más vieja de lo esperado.

## 6. Detección de estado

Detecta la versión real corriendo `codex --version` y parseando el semver de la salida. Si ya hay una versión detectada y no hay `--force`, el script sale inmediatamente sin resincronizar el registry.

Del lado de la app, módulo sin switch (`hasSwitch: false`) — la detección de instalación gatea la UI, sin concepto de "corriendo".

## 7. Pantalla de la app (`CodexFragment.kt`)

- **Card ESTADO**: canal, versión (leída del registry), pills de estado/terminal.
- **"⌨ Abrir en terminal"** — abre el CLI directo.
- **"📁 Abrir en proyecto"** — lista proyectos reales sobre `~/proyectos` (misma carpeta compartida que Claude/Antigravity/OpenCode).
- **"🗂 Gestionar proyectos"** — symlink/importar copia desde Download, eliminar, sincronizar todos.
- **"🔑 codex login"** — abre el flujo de login en terminal.
- **Card PROMPT DIRECTO — "💬 Enviar prompt (no interactivo)"** — diálogo de texto libre para el prompt, un diálogo de texto libre para el modelo (acepta nombre literal, vacío = default), selector de sandbox (`read-only`/`workspace-write`/`danger-full-access`/default) y selector de aprobación (`untrusted`/`on-request`/`never`/default) — corre `codex exec` en background con `--output-last-message` para separar el mensaje final del ruido de progreso.
- **Card SESIÓN** — "🕒 Reanudar última sesión" → `codex resume --last` (retoma la sesión más reciente grabada para el directorio actual; si nunca se corrió Codex antes desde ese mismo directorio, el propio CLI responde que no hay sesión).
- **"⚙ Instalar / cambiar canal"** — reinstala vía `ModuleController.installModule()`.

## 8. Registry

```
codex.installed=true
codex.version=<x.y.z>
codex.channel=termux|native
codex.install_date=<YYYY-MM-DD>
```
