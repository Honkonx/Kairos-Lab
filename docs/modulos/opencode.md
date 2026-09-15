# OpenCode

**Módulo de Kairos** — gestionado desde la UI (pestaña Módulos). Instalación, arranque/detención y estado los maneja la app vía `ProcessBuilder` → `modulos/opencode.sh`.

---

**Script:** `modulos/opencode.sh` — copia sincronizada en `app/src/main/assets/scripts/opencode.sh`.
**Puerto:** `:3000` (servidor web principal) + `:4096` (segunda instancia opcional).
**Registry:** prefijo `opencode.*`
**Sin switch en la lista de módulos:** es un CLI/servidor sin toggle en la lista general, pero sí tiene arranque/detención propio desde su propia pantalla.

---

## 1. Descripción general

OpenCode es un editor de código con IA (TUI + servidor web) — soporta Ollama local como proveedor. El binario oficial está compilado con glibc y no corre directamente sobre la libc nativa de Android: Kairos usa el mismo enfoque que Claude Code/Codex/Antigravity, glibc de Termux sin proot ni distro completa.

## 2. Permisos

No requiere permisos Android especiales — corre completamente dentro de Termux (glibc de Termux, sin proot, sin acceso a almacenamiento externo salvo lo que el usuario ya concedió globalmente en el asistente de configuración).

## 3. Instalación — `modulos/opencode.sh`

Acepta `--silent [--force] [--describe]`. Con `--describe` devuelve un manifiesto JSON declarativo, sin variantes.

### Pasos (6 total, con checkpoints)

```
PASO 1/6  Dependencias glibc: glibc-repo, glibc, openssl-glibc, ncurses
PASO 2/6  Detectar última versión — GitHub API, con reintento
PASO 3/6  Descargar paquete (.pkg.tar.xz, fallback .deb) del release detectado
PASO 4/6  Instalar en Termux (extraer y copiar a $PREFIX, o dpkg -i según formato)
PASO 5/6  Scripts de control: opencode_start.sh, opencode_stop.sh → ~/scripts/opencode/
PASO 6/6  Aliases en .bashrc + Registry
```

`ncurses` es una dependencia obligatoria (la TUI usa `@opentui/solid`) — sin ella, `opencode --version` funciona igual pero `opencode .` (TUI en terminal) falla en runtime. El stop-script mata cualquier sesión `opencode`/`opencode-*` (cubre tanto el servidor de `:3000` como la instancia opcional de `:4096`), más un `pkill -f 'opencode web'` de seguridad. El output real del servidor web se redirige a `~/kairos_logs/opencode_web.log` de forma persistente.

## 4. Detección de estado — `ModuleController.kt`

- `getTmuxSession("opencode")` → `"opencode"` — `isRunning()` chequea `tmux has-session -t opencode`.
- `getModulePort("opencode")` → `3000` — usado por `waitForPortOpen()` para confirmar arranque real antes de reportar éxito a la UI.
- `getModuleStartScript("opencode")` → `$HOME/scripts/opencode/opencode_start.sh`; `getModuleStopInfo("opencode")` → `.../opencode_stop.sh`.
- La instancia `:4096` no tiene entrada en `ModuleController.kt` ni en `modules.json` — se maneja aparte, directo desde `OpenCodeFragment` vía `OpenCodeNative.kt` (Kotlin puro, sin script intermedio).

## 5. Pantalla de la app — `OpenCodeFragment.kt`

Card ESTADO:
- Variante: `native·glibc` (fijo).
- Versión.
- Pill "Web server" — estado real.
- Pill "Terminal TUI" — estado de sesión de terminal minimizada.

Dropdown + switch de "Servidor web" (`:3000` / `:4096`): el dropdown elige puerto, el switch arranca/para en ese puerto — bloqueado mientras está encendido, hay que apagar antes de cambiar puerto.

Botones:
- **"TUI en terminal"** — corre `cd ~ && opencode .` (no `opencode` a secas).
- **"Abrir"** (visible solo si el servidor corre) — abre el WebView al puerto elegido.
- **"Gestionar proyectos"** — mismo menú compartido de 4 opciones (symlink/importar copia/eliminar/sincronizar todos) que el resto de los CLIs, sobre `~/proyectos`.
- **Card PROMPT DIRECTO — "Enviar prompt (no interactivo)"** — diálogo con prompt de texto libre, checkbox "Continuar la última sesión (--continue)" y campo de modelo opcional (formato `provider/model`). Ejecuta `opencode run [--continue] '<prompt>' [--model '<modelo>']` en la terminal — distinto del botón "TUI en terminal", que sí abre la interfaz interactiva completa.
- **Card CUENTA — "Configurar proveedor (auth login)"** — lanza `opencode auth login`: configura API keys de proveedores en la nube (Anthropic, OpenAI, etc.), distinto de "Configurar Ollama/llama-server local" (que solo escriben `opencode.json` apuntando a un endpoint local, sin login ni API key).
- **"Ver servidores MCP"** — panel con lista, toggle enabled/disabled, ver detalle por servidor. OpenCode soporta un campo `enabled` plano por servidor en su schema real, a diferencia de otros CLIs.
- **"Configurar Ollama local"** — lee modelos reales de Ollama y escribe `~/.config/opencode/opencode.json` con el proveedor elegido — falla con mensaje claro si Ollama no está corriendo.
- **"Configurar llama-server local"** — mismo mecanismo `baseURL` genérico compatible OpenAI, apuntando al puerto de `llama-server` (`:8085`) en vez del de Ollama; lista los `.gguf` ya descargados (llama-server no tiene un endpoint para listar modelos disponibles como Ollama).
- **"Detener servidor"** — mata todas las sesiones `opencode`/`opencode-*`, no solo la de `:3000`.
- **"Reinstalar / actualizar"** — reinstalación completa.
- **"Desinstalar"** (card mantenimiento).

## 6. Registry (`~/.android_server_registry`)

```
opencode.installed=true
opencode.version=<versión real de "opencode --version">
opencode.install_date=YYYY-MM-DD
opencode.location=termux_native
opencode.port=3000
```

## 7. Comandos de referencia

```bash
opencode-web          # alias -> opencode_start.sh (tmux "opencode", puerto 3000)
opencode-stop         # alias -> opencode_stop.sh (mata todas las sesiones opencode*)
opencode-status       # tmux has-session -t opencode
opencode-tui          # alias -> opencode (TUI directo)
```
