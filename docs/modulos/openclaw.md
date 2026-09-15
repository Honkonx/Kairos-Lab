# OpenClaw

## 1. Qué es

OpenClaw es un gateway de agente IA (npm, `openclaw@latest`) con interfaz web y TUI propias, compatible con múltiples proveedores (Ollama local, cloud). En Kairos corre **exclusivamente en modo nativo** (glibc + npm), sin proot ni distro completa.

`modules.json` lo describe así: gateway de IA local compatible con Claude, versión nativa glibc. Puerto `18789`, tamaño estimado `~60MB`, con switch de arranque/detención.

## 2. Permisos

Ninguno de Android específico para OpenClaw en sí — usa la infraestructura ya presente de Termux (almacenamiento, ejecución de procesos vía `ProcessBuilder`) que la app ya pide en el asistente de configuración inicial. No requiere permisos de grabación de audio, notificaciones, ni ningún permiso propio declarado en el manifest a nombre de este módulo.

## 3. Arquitectura de instalación — nativo, sin proot

```
Termux nativo (sin proot)
  ~/.npm-global/bin/openclaw          → paquete npm real
  ~/.openclaw-android/bin/node        → wrapper bash → ld.so (glibc-runner) → node.real
  ~/.openclaw-android/bin/npm, npx    → wrappers equivalentes
  ~/.openclaw/glibc-compat.js         → --require en NODE_OPTIONS (fix os.networkInterfaces/homedir)
```

Node.js oficial `linux-arm64` (versión objetivo compatible con el mínimo exigido por OpenClaw, ver `docs.openclaw.ai/install/node`) corre sobre el cargador ELF de `glibc-runner`, sin entrar a ningún entorno proot.

## 4. Lógica de instalación (`modulos/openclaw.sh`, 8 pasos)

Acepta `--silent` (sin prompts, modo app) y `--force` (reinstala aunque ya esté). También `--describe` (imprime un manifiesto JSON declarativo).

1. **Infraestructura glibc + Node** — si ya hay un Node con versión mínima exigida (sistema o wrapper propio) con npm funcional, lo reusa. Si no, instala `glibc-repo` → `glibc-runner`/`patchelf-glibc` → descarga Node oficial `linux-arm64` → genera wrappers `node`/`npm`/`npx` que sanean `NODE_OPTIONS` heredado antes de invocar el binario real.
2. **Verificar Node + npm** — chequeo de versión mínima real (mayor.menor.parche, no solo el número mayor).
3. **Instalar openclaw** — `npm install -g openclaw@latest --allow-scripts=openclaw` (permite el postinstall propio de openclaw, que aplica un hotfix real a la librería `baileys`). Antes de saltar este paso por checkpoint, verifica que el paquete siga existiendo en disco.
4. **Patches Android** — `glibc-compat.js` (fix `os.networkInterfaces()`/`os.homedir()`), stub de `koffi` (módulo nativo no compilado para `android-arm64`), stub de `clipboardy`, parche de rutas `/tmp` → `$HOME/tmp` y `/bin/npm` → ruta real del wrapper, dentro del bundle instalado.
5. **Scripts de control** — genera `openclaw_start.sh`/`openclaw_stop.sh` en `~/scripts/openclaw/` (detalle en la sección 7).
6. **Aliases** — agrega bloque `# OpenClaw` a `~/.bashrc` (`openclaw-start`, `openclaw-stop`, `openclaw-status`, `openclaw-tui`).
7. **Registry** — actualiza `~/.android_server_registry`, con lectura de `openclaw --version` tolerante a fallos (si el comando falla, cae a `"unknown"` en vez de abortar y dejar el registry desactualizado).
8. **Limpieza** — borra el archivo de checkpoint.

## 5. Detección de estado (`ModuleController.kt`)

Módulo con switch de arranque/detención y sesión tmux real (`"openclaw"`). `ModuleController` resuelve:
- Script de arranque: `$HOME/scripts/openclaw/openclaw_start.sh`
- Script de detención: `$HOME/scripts/openclaw/openclaw_stop.sh`
- Nombre de sesión tmux: `"openclaw"`
- Puerto: `18789`

"Corriendo" se determina vía `tmux has-session` (o polling de puerto según el flujo que invoque la verificación) — no solo lectura de registry.

## 6. Pantalla de la app (`OpenClawFragment.kt`)

Workspace propio: OpenClaw usa `$HOME/.openclaw/workspace` (no la carpeta compartida `~/proyectos` de Claude/OpenCode/Codex/Antigravity).

Tarjeta "ESTADO": variante (`native·glibc`), versión, estado del gateway, token (enmascarado), modelo activo, pill de estado de terminal.

El gateway se controla con un switch (`gatewaySwitch`): `on=true` → arranca (`openclaw_start.sh`); `on=false` → detiene (`openclaw_stop.sh`).

| Control | Acción |
|---|---|
| Switch **Gateway** | Arranca/detiene el gateway |
| Reiniciar gateway | Detiene y vuelve a iniciar |
| Ver logs | Abre el visor de logs sobre `~/openclaw-logs/runtime.log` |
| Mostrar URL con token | Lee `gateway.auth.token` de `~/.openclaw/openclaw.json` (con el log de runtime como fallback), arma `http://localhost:18789/#token=...`. El token existe una vez que el gateway corrió al menos una vez |
| Abrir interfaz web (local) | Si no está corriendo, lo arranca primero; luego abre el WebView interno sobre `http://localhost:18789` (con el token ya incluido en la URL) |
| Abrir TUI (terminal) | Lanza `openclaw tui` en una sesión de terminal |
| Onboarding | Lanza `openclaw onboard` (wizard interactivo) — configura un proveedor de IA real; no es un prerequisito del arranque del gateway |
| Proveedor IA / Modelo | Ver, configurar Ollama, proveedor personalizado, o restaurar backup de la config |
| Configurar canales | Diálogo con los 4 canales confirmados reales (Discord/Telegram/WhatsApp/Slack) — switch "Canal activo" + campo de token, guarda/elimina en `~/.openclaw/openclaw.json` con backup automático antes de escribir |
| Gestionar workspaces | Symlink/importar desde Descargas, eliminar, sincronizar todos |
| Abrir workspace en TUI | Lista workspaces ya importados, abre `openclaw tui` en el que se elija |
| Instalar / actualizar | Reinstalación completa |
| Conectar memoria de Engram (MCP) | `openclaw mcp add` — OpenClaw es cliente MCP real y documentado; si el gateway ya corre, ofrece reiniciarlo para tomar el nuevo servidor |

**Detalle no obvio**: el diálogo "Proveedor personalizado" pide 5 campos (nombre, base URL, API key, ID de modelo, context window) para proveedores OpenAI-compatible (DeepSeek, LM Studio, etc.) — Gemini/Anthropic con OAuth van por "Onboarding" en cambio, no por este diálogo.

## 7. Runtime — scripts generados

`openclaw_start.sh`:
- Si el gateway ya responde en `:18789`, sale inmediatamente.
- Mata cualquier proceso/sesión previa, arranca una sesión tmux nueva pasando el comando directo a `tmux new -d -s openclaw` (con `TMPDIR`/`NODE_OPTIONS` seteados inline, heap dinámico — ver abajo).
- Acepta `--no-wait` (dispara y sale sin esperar, para arranque no bloqueante).
- Sin `--no-wait`: health-check HTTP real (`curl` contra `:18789`, hasta 6 intentos de 2s).
- **Heap dinámico**: calcula el heap de Node como 60% de `MemAvailable` (clamp 1024–5632MB) en vez de un valor fijo, para evitar que el sistema mate el proceso por falta de memoria justo cuando Node carga el bundle completo por primera vez.

`openclaw_stop.sh`: mata el proceso y la sesión tmux, confirma con un `curl` que el gateway ya no responde.

## 8. Configuración — `~/.openclaw/openclaw.json`

`OpenClawNative.providersList()` busca la config en dos rutas posibles (primero `~/.openclaw/config.json`, luego `~/.config/openclaw/config.json`, este segundo es solo fallback legacy) y devuelve el JSON crudo tal cual — la app no tiene un editor estructurado completo de este archivo, "Proveedor IA / Modelo" solo lo muestra de solo lectura salvo la parte cubierta por el editor de canales.

El archivo real y vigente es `~/.openclaw/openclaw.json` (confirmado contra `docs.openclaw.ai/gateway/configuration`). Cada canal de mensajería (Discord, Telegram, WhatsApp, Slack, Signal, iMessage, WebChat, y más vía plugins) tiene su propia sección bajo `channels.<provider>` en ese mismo archivo (auth, control de acceso, multi-cuenta, mention gating).

El editor de canales de Kairos cubre el schema mínimo (`enabled`/`token`), que es el caso de uso más común (bot de Discord/Telegram). Campos avanzados por proveedor (multi-cuenta, mention gating, control de acceso fino) no tienen editor propio todavía — si se necesitan, hay que tocar `~/.openclaw/openclaw.json` a mano.

El campo `gateway.mode` debe estar en `"local"` para que el gateway arranque y auto-genere su propio `gateway.auth.token` — Kairos pre-siembra este campo en el config durante la instalación y lo refuerza en cada arranque, así que el usuario no necesita completar el onboarding interactivo (elección de proveedor de IA) solo para que el gateway funcione y el token exista; el onboarding sigue disponible para configurar un proveedor real cuando el usuario lo quiera, mientras eso no es un prerequisito del gateway en sí.

## 9. Registry

```
openclaw.installed=true
openclaw.version=<versión real, o "unknown" si openclaw --version no respondió>
openclaw.install_date=<fecha>
openclaw.location=nativo_termux
openclaw.port=18789
```

## 10. Puerto

`18789` — fijo, sin variantes.

## 11. Alcance de la carpeta de proyectos

OpenClaw usa su propio workspace (`$HOME/.openclaw/workspace`, separado de la carpeta compartida `~/proyectos` que usan Claude/OpenCode/Codex/Antigravity). Para "agregar un proyecto", las únicas fuentes que ofrece el selector son: symlink/copiar desde Descargas, symlink/copiar desde almacenamiento externo, o symlink desde un proyecto que ya está en `~/proyectos` — no hay opción para navegar/symlinkear una carpeta arbitraria del `$HOME` de Termux. Esto es una limitación de diseño del selector compartido de proyectos, no de OpenClaw en sí — el proceso real de OpenClaw no tiene ningún directorio de trabajo fijo impuesto por el binario.
