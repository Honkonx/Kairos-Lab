# Hermes Agent — Módulo de Kairos

**Módulo de Kairos** — Este módulo se gestiona desde la UI de Kairos (pestaña Módulos). La instalación, el start/stop del gateway y las comprobaciones de estado los maneja la app vía ProcessBuilder → scripts bash y código Kotlin.

---

**Proyecto:** Kairos
**Agente upstream:** `NousResearch/hermes-agent` (MIT, Nous Research). La versión del agente depende del `main` clonado en cada instalación (el comentario del script cita v0.21.0; versiones anteriores usaron v0.16.0)
**Script:** `modulos/hermes.sh` (v2.1.0, 609 líneas; idéntico a `app/src/main/assets/scripts/hermes.sh`)
**UI:** `HermesFragment.kt` (463 líneas), `HermesGatewayFragment.kt` (102 líneas), `HermesNative.kt` (241 líneas)
**Fecha de alta:** 2026-07-19

---

## 1. Descripción General

Hermes Agent es un framework de agente IA de código abierto (MIT) desarrollado por Nous Research. A diferencia de herramientas similares como OpenClaw o OpenCode, Hermes corre de forma **nativa en Termux** (nunca tuvo modo proot) sin contenedores Debian, sin Node.js en proot, y sin root.

Su arquitectura está basada en Python con un entorno virtual (`venv`) instalado en `~/.hermes/hermes-agent/venv/` (`pip install -e`), con shim en `$PREFIX/bin/hermes`. Expone una interfaz TUI/REPL/chat interactiva, un gateway de mensajería multicanal (Telegram, Discord, Slack, SMS, Signal; `hermes gateway`) con tokens configurados en `~/.hermes/.env`, y una API server OpenAI-compatible opcional en `:8642` (ni `modules.json` ni el código de Kairos la usan).

Dentro del stack, Hermes se clasifica junto a n8n y OpenClaw, ya que su función principal es actuar como gateway de comunicación hacia plataformas de mensajería.

**Clasificación: MÓDULO FUNCIONAL** (de naturaleza mixta: gran parte de sus opciones solo abren la terminal). Tiene pantalla propia (`HermesFragment`, `ModuleDetailNavigator.kt:28`) y subpantalla de servicio (`HermesGatewayFragment`, alcanzada por `navigateTo(HermesGatewayFragment())`, `HermesFragment.kt:110`), estado en vivo (versión/gateway/proveedor), switch real de start/stop del gateway, escritura real de config (`.env`, `hermes config set`) y gestión de proyectos. `modules.json` lo declara `hasSwitch:false`, `port:""`, sin `tmuxSession`, y `ModuleController.kt` no tiene ninguna entrada de `hermes`; el ciclo de vida del gateway lo maneja `HermesNative` por su cuenta. ~24 botones: `HermesFragment` ~21 y `HermesGatewayFragment` 3 (switch, estado, logs). De esos, ~13 son un simple `launchTerminalCommand("hermes …")`; ~6 tienen operación nativa propia (switch/detener gateway, proveedor rápido, proveedor local, estado, Engram, proyectos).

---

## 2. Arquitectura en el Stack

```
Kairos
│
├── Servicios
│     ├── n8n          :5678  proot Debian   — automatización workflows
│     ├── OpenClaw     :18789 proot Debian   — gateway IA multi-proveedor
│     └── Hermes Agent        NATIVO Termux  — agente IA + gateway mensajería
│
├── Code Tools
│     ├── Claude Code         NATIVO Termux
│     └── OpenCode     :3000  proot Debian
│
├── Ollama             :11434 NATIVO Termux  — modelos IA local
└── ...
```

### Diferencias clave vs OpenClaw

| Aspecto | OpenClaw | Hermes |
|---|---|---|
| Runtime | proot Debian + Node.js | Termux nativo Python |
| Proceso | Daemon HTTP permanente | TUI interactiva + gateway opcional |
| Puerto | `:18789` fijo | Gateway `:8642` opcional / no expone por defecto |
| Status check | `curl :18789` | `pgrep -f hermes` + `tmux has-session -t hermes-gw` |
| Telegram | Integración vía n8n | Integración nativa directa |
| Instalación | proot + npm | pip en venv nativo Termux |

---

## 3. Rutas y Estructura de Archivos

```
~/.hermes/                          # Directorio raíz de datos (HERMES_HOME configurable por entorno)
├── config.yaml                     # Configuración principal (proveedor, modelo, agente)
├── .env                            # Claves API y tokens (chmod 600)
├── SOUL.md                         # Personalidad del agente (carga en cada mensaje)
├── hermes-agent/                   # Código fuente (git clone)
│   ├── venv/                       # Entorno virtual Python
│   │   └── bin/hermes              # Binario real del agente
│   ├── constraints-termux.txt      # Constraints de pip para Termux/Android
│   └── scripts/
│       └── install_psutil_android.py  # Parche psutil ARM64
├── sessions/                       # Historial de sesiones por ID
├── memories/                       # Memoria persistente del agente
├── skills/                         # Skills cargadas (built-in + custom, p. ej. custom/engram-memory/SKILL.md)
├── logs/                           # Logs del gateway
│   └── gateway.log
├── cron/                           # Tareas programadas
├── hooks/                          # Hooks de eventos
├── pairing/
├── image_cache/                    # Caché de imágenes procesadas
└── audio_cache/                    # Caché de audio (TTS/STT)

$PREFIX/bin/hermes                  # Shim lanzador (chmod +x)
~/.install_hermes_checkpoint        # claves hermes_<paso>=done
```

### Shim lanzador (`$PREFIX/bin/hermes`)

El instalador no crea un symlink directo sino un **shim bash** que limpia variables de entorno heredadas que rompen el venv:

```bash
#!/data/data/com.termux/files/usr/bin/bash
unset PYTHONPATH
unset PYTHONHOME
exec "/data/data/com.termux/files/home/.hermes/hermes-agent/venv/bin/hermes" "$@"
```

Esto es crítico en Termux porque sesiones anidadas (tmux, proot) pueden heredar `PYTHONPATH` de otras instalaciones Python del stack, haciendo que Hermes importe módulos incorrectos.

---

## 4. Configuración Principal — `~/.hermes/config.yaml`

### Estructura correcta para Hermes v0.16+

Hermes v0.16 requiere configuración de modelo en **bloque YAML anidado**. El formato plano (`model: ollama/nombre`) de versiones anteriores ya no es válido.

```yaml
# Proveedor cloud (OpenRouter, Anthropic, Gemini, etc.)
model:
  provider: openrouter
  default: google/gemini-flash-1.5

# Proveedor Ollama local
model:
  provider: custom
  base_url: http://127.0.0.1:11434/v1
  default: qwen2.5:7b
  ollama_num_ctx: 65536
  context_length: 65536
```

> **Error común:** Si se escribe `model: ollama/nombre` (una sola línea), Hermes v0.16 lo ignora y usa el proveedor por defecto, generando el error `No models provided` contra OpenRouter.

### Variables de entorno — `~/.hermes/.env`

```bash
# ── Telegram ───────────────────────────────
TELEGRAM_BOT_TOKEN=<token-de-BotFather>
TELEGRAM_ALLOWED_USERS=<tu-ID-numérico-Telegram>
TELEGRAM_HOME_CHANNEL=<tu-ID-numérico-Telegram>

# ── Proveedores IA ──────────────────────────
OPENROUTER_API_KEY=sk-or-v1-...
GOOGLE_API_KEY=AIza...
# ANTHROPIC_API_KEY=sk-ant-...

# ── Gateway API server (opcional) ───────────
# API_SERVER_ENABLED=true
# API_SERVER_KEY=tu-clave-secreta
# API_SERVER_PORT=8642

# ── Home Assistant (opcional) ───────────────
# HASS_TOKEN=...
# HASS_URL=http://homeassistant.local:8123
```

El archivo tiene permisos `600` — solo el usuario propietario puede leerlo (se crea así en el script, `:554`, y en `configSetProvider`).

---

## 5. Instalación (`modulos/hermes.sh`, v2.1.0)

El proceso adapta el instalador oficial de Nous Research para las restricciones de Android 15 + Termux ARM64. Flags (`:71-74`): `--silent`, `--force`, `--describe`, `--describe-files`; `--describe` declara `variants: []`. Sin variantes. `set -e` global (`:55`); desactiva `PYTHONPATH/PYTHONHOME`, `UV_NO_CONFIG=1`.

### Diferencias vs instalador oficial

| Aspecto | Oficial | Kairos |
|---|---|---|
| Gestor de paquetes Python | `uv` | `pip` directo (uv no disponible en Termux) |
| Directorios temporales | `mktemp` → `/tmp/` | Evitado — noexec en Android 15 |
| Prompts interactivos | `read -r -p` libre | Siempre `read -r ... < /dev/tty` |
| Recuperación de fallos | Sin checkpoint | Checkpoint por paso en `~/.install_hermes_checkpoint` |
| Integración de sistema | Sin registro | Escribe en `~/.android_server_registry` |
| Wizard al finalizar | Automático | No lo lanza (cabecera `:25-26`: lo maneja la app después); el usuario lo abre con el botón "Wizard completo (hermes setup)" |
| Gateway post-instalación | Ofrece instalar como servicio | Omitido — se gestiona desde la app |

### Pasos del instalador (con checkpoint)

```
PASO 1/6  Paquetes del sistema (:235-282)
          pkg install python git clang rust make pkg-config
                      libffi openssl ca-certificates curl ripgrep ffmpeg nodejs-lts
          (si falla, solo avisa y NO marca el checkpoint: no aborta)

PASO 2/6  Clonar repositorio (:284-307)
          git clone --depth 1 --branch main (SSH con BatchMode primero, fallback HTTPS)
          → ~/.hermes/hermes-agent/   (sin fijar commit/tag)

PASO 3/6  Entorno virtual Python (:309-327)
          python -m venv ~/.hermes/hermes-agent/venv

PASO 3.5  Wheel prebuilt (:329-349)
          intenta descargar firecrawl-anydoc==0.2.4 desde Releases de
          Honkonx/kairos-lab antes del pip install (si no existe, compila desde fuente)

PASO 4/6  Dependencias Python (3 niveles de fallback, todos con
          --ignore-requires-python -c constraints-termux.txt)
          pip install -e '.[termux-all]'   ← intento 1
          pip install -e '.[termux]'       ← fallback 2
          pip install -e '.'              ← fallback 3
          * python-psutil / python-cryptography del sistema + symlink de cryptography al venv
          * psutil precompilado con parche Android (install_psutil_android.py) antes de pip

PASO 5/6  Shim en $PREFIX/bin/hermes (:492-530)
          Protege PYTHONPATH/PYTHONHOME; falla duro si `hermes --version` no responde

PASO 6/6  Archivos de configuración (:543-590)
          ~/.hermes/.env (copiado de .env.example, chmod 600) · config.yaml · SOUL.md
          Skills sync via tools/skills_sync.py
          registry_install hermes <ver> install_dir model="no configurado" location=termux_native
```

- Atajo "ya instalado": `command -v hermes` y `$INSTALL_DIR` existen sin `--force` → sale (`:196-199`) SIN reescribir el registry; si el registry quedara en false con binario sano, no se repara.
- `--force` borra `INSTALL_DIR` y el shim (`:201-207`), no `.env` ni `config.yaml`.
- Si la versión no se obtiene cae a `"unknown"` y registra igual (`:588`).
- Verificación de integridad del wheel prebuilt: solo si existe `HERMES_FIRECRAWL_PREBUILT_SHA256` (variable no definida), así que `verify_sha256` solo loguea el hash y retorna 0 sin pinnear (`lib.sh:595-606`). El propio script reconoce que todavía puede no existir un Release con ese asset (`:46-52`).
- `modules.json`: `requires: [python3, nodejs]`, `installMethods: [npm, venv]`, `arch: bionic`, `catalogVersion "0.x"`, `size ~90MB`, `estimate ~1 min` (la instalación real compila/descarga bastante más; el tiempo es solo una etiqueta).

### Variables de entorno críticas para ARM64

```bash
ANDROID_API_LEVEL=35          # Requerido para wheels Rust/maturin (psutil, jiter)
VIRTUAL_ENV=~/.hermes/hermes-agent/venv
UV_NO_CONFIG=1                # Evita que uv herede configuración rota
```

---

## 6. Integración con la app

### 6.1 Componentes del módulo

| Componente | Función |
|---|---|
| `modulos/hermes.sh` | Solo INSTALA (6 pasos + PASO 3.5); no contiene lógica de start/stop del gateway (el propio `--describe` dice "sin gateway/tmux implementado todavia", `modulos/hermes.sh:81`) |
| `app/src/main/java/com/termux/app/util/HermesNative.kt` | Lógica Kotlin: `info()` (registry + `.env` + `pgrep`), `gatewayStart()`, `gatewayStop()`, `configSetProvider`, `configSetLocalProvider`, `ollamaModels`, `installEngramSkill` |
| `HermesFragment.kt` + `HermesGatewayFragment.kt` | UI real del módulo (ver 6.3); `ModulesFragment.kt` es solo la lista genérica |

### 6.2 Detección de estado

Estado "corriendo" = `pgrep -f` (`ManagerNativeUtils.pgrepF`, `:50-51`) sobre `hermes.*gateway`, o `tmux has-session -t hermes-gw`. Estados posibles: no instalado, detenido, corriendo (gateway activo en tmux `hermes-gw`). La card ESTADO se puebla con `HermesNative.info()`: versión, gateway, proveedor (`AI_PROVIDER`/`HERMES_PROVIDER` del `.env`). Inferencia: el `pgrep` lanzado desde el proceso de la app puede no ver procesos del árbol de la sesión de terminal bajo `ptrace_scope=1`; como el tmux lo lanza la propia app podría ser visible, pero no está confirmado, y hay riesgo de falso negativo en `running`.

### 6.3 Pantalla de la app

El header muestra siempre el proveedor/modelo real leído desde `~/.hermes/config.yaml` (no el valor del registry, que puede quedar desactualizado).

**HermesFragment (pantalla principal):**

| Control | Qué hace | Por qué |
|---|---|---|
| "🤖 Abrir Hermes (TUI)" | `hermes --tui` (con selector de carpeta; NO `hermes` a secas) | El binario sin flags abre el REPL clásico (prompt_toolkit) — un modo distinto |
| "💬 Chat (hermes chat)" | `hermes chat` | 3er entry point distinto del mismo binario (REPL/TUI/chat), no duplicado |
| "📡 Gateway" | Navega a `HermesGatewayFragment` | — |
| "🗂 Gestionar proyectos" | `showProjectsMenu()` | — |
| "📖 Comandos — referencia" | Diálogo con la lista completa de comandos y qué hace cada uno | — |
| "🔀 Elegir modelo/proveedor (hermes model)" | `launchTerminalCommand("hermes model")` | Selector 100% interactivo por TTY (flechas), sin flag `--list`/`--json` documentado — no se inventa un parseo que el binario no soporta |
| "🔌 Configurar proveedor IA (rápido, sin terminal)" | Diálogo con proveedor + API key → `configSetProvider` escribe `AI_PROVIDER` y `<PROVIDER>_API_KEY` en `~/.hermes/.env` directo (`chmod 600`, `upsertKeyValueLine`, sin shell) | Más rápido que el wizard real, pero sin su validación/formato de `config.yaml`. Los campos arrancan vacíos y nunca se vuelve a mostrar la key guardada (`HermesFragment.kt:339-366`) |
| Dropdown "Proveedor IA local" (Ollama local / llama-server local) + "🔌 Configurar proveedor IA local" | Elige cuál backend local usa Hermes; el botón despacha a `useOllamaLocal()`/`useLlamaServerLocal()`. `configSetLocalProvider` hace 3 llamadas `hermes config set model.provider\|base_url\|default` (`HermesNative.kt:136-`); lista modelos de Ollama (`ollamaModels`, cliente HTTP) o los GGUF de `LocalModelManager` | Decisión excluyente sin ON/OFF asociado — encaja en `dropdownRow()` |
| "📤 Enviar mensaje (hermes send)" | Diálogo con mensaje → `hermes send` en terminal | Envía tal cual a la plataforma configurada (Telegram/Discord/SMS) — **no** pasa por el LLM |
| "🤖 Prompt directo al agente (hermes -z)" | Diálogo con prompt → `hermes -z` en terminal | Distinto del de arriba: sí pasa por el LLM y devuelve respuesta, sin abrir TUI |
| "⏹ Detener gateway" | `HermesNative.gatewayStop()` | — |
| "🔍 Estado y diagnóstico" | Diálogo con instalado/versión/gateway/config/proveedor | — |
| "⚙ Wizard completo (hermes setup)" | `launchTerminalCommand("hermes setup")` | — |
| "🩺 Diagnóstico (hermes doctor)" | Terminal — salida de texto libre, no JSON | No tiene sentido envolverlo en un diálogo nativo |
| "📋 Tablero de tareas (kanban)" | `launchTerminalCommand("hermes kanban")` | — |
| "⏰ Tareas programadas (hermes cron)" | `launchTerminalCommand("hermes cron")` | Subcomando documentado en la referencia oficial de comandos ("Scheduled job management") |
| "↑ Actualizar Hermes (hermes update)" | Camino liviano, solo cae a reinstalar si falla | Distinto de "Instalar/reinstalar" |
| "↑ Instalar / reinstalar" | `reinstallModuleService()` | — |
| "🔗 Conectar memoria de Engram (skill)" | `HermesNative.installEngramSkill()` — escribe `~/.hermes/skills/custom/engram-memory/SKILL.md` | Hermes no usa slug MCP como otros — tiene un sistema de Skills que el agente carga solo cuando aplica |
| "🗑 Desinstalar" (card MANTENIMIENTO) | `confirmUninstallModule()` | No duplica "Actualizar"/"Instalar/reinstalar" de arriba |

"Enviar mensaje" y "Prompt directo" abren una terminal con el comando en lugar de mostrar la respuesta en la UI; los mensajes se escapan con `shellQuote` (`ProcessBuilderExt.kt:254`). `view.filterTouchesWhenObscured = true` (`HermesFragment.kt:31-34`).

**HermesGatewayFragment ("Gateway"):**

| Control | Qué hace | Por qué |
|---|---|---|
| Switch "Gateway" | `hermes gateway` en tmux (`hermes-gw`) / lo detiene | Reemplaza botones separados Iniciar/Detener. Termux es "Tier 2, best-effort" en la doc oficial de Hermes — no probado en dispositivo real |
| "Ver estado" | Diálogo con corriendo/proveedor/config guardada | — |
| "Ver logs" | `tmux attach -t hermes-gw` | Sin ruta de log estático conocida — se conecta a la sesión en vivo |

### 6.4 Control del Gateway

El gateway se ejecuta en una sesión `tmux` llamada `hermes-gw`. El comando que lanza Kairos es `hermes gateway` (sin `run`): `HermesNative.kt` `gatewayStart()` ejecuta `tmux new-session -d -s hermes-gw "hermes gateway"` y espera 2 s fijos (`Thread.sleep(2000)`) antes de verificar con `pgrep -f "hermes.*gateway"` o `tmux has-session`; `gatewayStop()` mata la sesión y hace `pkill -f "hermes.*gateway"`. No se verifica que el gateway realmente conecte.

```bash
# Iniciar (lo que usa la app)
tmux new-session -d -s "hermes-gw" "hermes gateway"
# (`hermes gateway run` es el modo foreground del uso manual por terminal)

# Ver logs
tmux attach-session -t "hermes-gw"   # Ctrl+B D para salir sin matar

# Detener
tmux kill-session -t "hermes-gw"
pkill -f "hermes gateway"
```

### 6.5 Comandos del Módulo — referencia ejecutable

| Opción | Comando | Descripción |
|---|---|---|
| [1] | `hermes` | TUI interactiva principal |
| [2] | `hermes chat` | Chat interactivo |
| [3] | `hermes model` | Cambiar proveedor/modelo |
| [4] | `hermes setup` | Wizard completo |
| [5] | `hermes status` | Estado agente + auth |
| [6] | `hermes doctor` | Diagnóstico del sistema |
| [7] | `hermes version` | Versión instalada |
| [8] | `hermes send` | Envío one-shot a Telegram/Discord/SMS |
| [9] | `hermes kanban` | Tablero de tareas |

### 6.6 Configurar Ollama local

Para Hermes v0.16+ la estructura YAML correcta es:

```yaml
model:
  provider: custom
  base_url: http://127.0.0.1:11434/v1
  default: qwen2.5:7b
  ollama_num_ctx: 65536
  context_length: 65536
```

Se recomienda verificar el context window del modelo con `curl http://127.0.0.1:11434/api/show` — Hermes requiere al menos 64k tokens para tool calling confiable.

### 6.7 Registry — entradas de Hermes

`modulos/hermes.sh` escribe (vía `registry_install`) `installed`, `version`, `install_date`, `install_dir`, `model=no configurado` y `location=termux_native` (`:590`):

```
hermes.installed=true
hermes.version=<versión>
hermes.install_date=<YYYY-MM-DD>
hermes.install_dir=/data/data/com.termux/files/home/.hermes/hermes-agent
hermes.model=no configurado
hermes.location=termux_native
```

---

## 7. Integración con Telegram

### Requisitos

- Bot creado via `@BotFather` en Telegram — genera el `TELEGRAM_BOT_TOKEN`
- ID numérico del usuario via `@userinfobot` — va en `TELEGRAM_ALLOWED_USERS`
- Gateway corriendo (`hermes gateway`)

### Configuración en `~/.hermes/.env`

```bash
TELEGRAM_BOT_TOKEN=<token-de-BotFather>
TELEGRAM_ALLOWED_USERS=<tu-ID-numérico-Telegram>
TELEGRAM_HOME_CHANNEL=<tu-ID-numérico-Telegram>
```

`TELEGRAM_ALLOWED_USERS` es la lista de control de acceso. Sin este campo configurado el gateway acepta la conexión pero descarta todos los mensajes silenciosamente — el log muestra `Channel directory built: 0 target(s)`.

### Modo de conexión

Hermes usa **long polling** (no webhook) — el gateway hace peticiones periódicas a la API de Telegram. No requiere URL pública, no requiere cloudflared, funciona con cualquier conexión a internet.

### Comandos slash disponibles en Telegram

| Comando | Función |
|---|---|
| `/help` | Mostrar comandos disponibles |
| `/new` | Nueva sesión (borra historial) |
| `/status` | Ver estado de la sesión actual |
| `/sessions` | Navegar sesiones anteriores |
| `/model` | Cambiar modelo para esta sesión |
| `/stop` | Detener procesos en background |
| `/update` | Actualizar Hermes desde el bot |
| `/commands` | Ver todos los comandos (paginado) |

---

## 8. Ollama Local con Hermes

### Requisito crítico

Hermes requiere un modelo con **mínimo 64,000 tokens de contexto** para tool calling confiable. Modelos con ventana menor son rechazados al iniciar con el mensaje:

```
Ollama loaded <modelo> with only 32,768 tokens of runtime context,
but Hermes needs at least 64,000 tokens for reliable tool use.
```

### Modelos compatibles por dispositivo

| Modelo | RAM base | Context | Dispositivo 11GB | Dispositivo 16GB |
|---|---|---|---|---|
| `qwen2.5:7b` | ~5 GB | 128k nativo | ⚠️ ajustado | ✅ |
| `qwen2.5:14b` | ~10 GB | 128k nativo | ❌ | ✅ |
| `llama3.1:8b` | ~6 GB | 128k nativo | ⚠️ ajustado | ✅ |
| `deepseek-r1:7b` | ~5 GB | 64k nativo | ⚠️ ajustado | ✅ |
| `qwen2.5:3b` | ~2 GB | 32k nativo | ⚠️ con Modelfile | ✅ |
| `qwen2.5:0.5b` | ~400 MB | 32k | ❌ ctx insuf. | ❌ |
| `moondream:1.8b` | ~1.5 GB | 2k | ❌ ctx insuf. | ❌ |

### Configurar context window via Modelfile

Cuando el modelo carga con contexto insuficiente por defecto, se crea un Modelfile que fuerza `num_ctx`:

```bash
cat > ~/qwen25_7b_65k.modelfile << 'EOF'
FROM qwen2.5:7b
PARAMETER num_ctx 65536
EOF

ollama create qwen2.5:7b-65k -f ~/qwen25_7b_65k.modelfile
```

Luego en `~/.hermes/config.yaml`:

```yaml
model:
  provider: custom
  base_url: http://127.0.0.1:11434/v1
  default: qwen2.5:7b-65k
  ollama_num_ctx: 65536
  context_length: 65536
```

### Limitación en dispositivos de 11 GB

Con el stack completo corriendo (n8n, Ollama, gateway Hermes), la RAM disponible para el modelo es ~3-4 GB. Esto hace que `num_ctx: 131072` cause segfault del proceso `llama-server` en ARM64. El valor seguro es `65536` (mínimo requerido).

Para dispositivos de 16 GB RAM, `qwen2.5:14b` con `num_ctx: 65536` es la opción recomendada — contexto suficiente, calidad superior, tool calling más preciso.

---

## 9. Comandos de Referencia Rápida

```bash
# Estado
hermes version
hermes status
hermes doctor

# Uso
hermes                          # TUI interactiva
hermes chat                     # Chat interactivo
hermes -z "responde solo OK"    # One-shot no interactivo (test)

# Configuración
hermes model                    # Wizard de proveedor/modelo
hermes setup                    # Wizard completo
hermes config set model.provider custom
hermes config set model.base_url http://127.0.0.1:11434/v1
hermes config set model.default qwen2.5:7b-65k

# Gateway
hermes gateway run              # Foreground (recomendado en uso manual por terminal)
hermes gateway status
hermes gateway stop

# Actualización
hermes update

# Envío directo (sin agente, sin LLM)
hermes send "mensaje"           # Envía a plataforma configurada

# Mantenimiento
hermes kanban                   # Tablero de tareas
hermes cron                     # Tareas programadas
hermes migrate                  # Migrar config a nuevo formato
```

---

## 10. Desinstalación

El módulo incluye la opción de desinstalar Hermes Agent, que ejecuta:

```bash
# Detener gateway
tmux kill-session -t "hermes-gw" 2>/dev/null
pkill -f "hermes gateway" 2>/dev/null

# Eliminar código (conserva config)
rm -rf ~/.hermes/hermes-agent/
rm -rf ~/.hermes/venv/
rm -f  $PREFIX/bin/hermes
rm -f  ~/.local/bin/hermes

# Limpiar registry
grep -v "^hermes\." ~/.android_server_registry > /tmp/reg.tmp
mv /tmp/reg.tmp ~/.android_server_registry
```

> `~/.hermes/config.yaml` y `~/.hermes/.env` se **conservan intencionalmente** para no perder las claves API y tokens de Telegram al reinstalar.

---

## 11. Seguridad

- `~/.hermes/.env` guarda claves API y tokens de plataformas: se crea con `chmod 600`. `configSetModel` reescribe el `.env` con `writeText` sin volver a aplicar `chmod 600` (`HermesNative.kt`; no tiene callers en la UI).
- `HermesNative.configGet()` devolvería los valores del `.env` en JSON (incluidas keys) y `soulGet/soulSet` exponen/escriben SOUL.md; ninguno tiene llamadas desde la UI, por lo que hoy no hay fuga, pero son superficie latente (~60 líneas de código sin uso; no hay editor de SOUL.md ni de `config.yaml` en la UI).
- Supply chain: `git clone` de `main` sin pin ni verificación de firma, `pip install -e` sin hashes, wheel prebuilt de `kairos-lab` sin SHA pinneado. El gateway conecta a Telegram/Discord/SMS/Signal con tokens del `.env`.
- Inyección: los argumentos de usuario a la terminal pasan por `shellQuote` (send/-z); `launchTerminalCommand("cd '$path' && hermes --tui")` (`:96,118`) interpola la ruta entre comillas simples sin escapar (inferencia: rutas de selector/`ProjectsManager`, no texto libre).
- El agente ejecuta herramientas con acceso a terminal (skills, `hermes send`, cron): riesgo inherente a un agente con herramientas, no mitigado por Kairos.

---

## 12. Problemas Conocidos y Soluciones

| Problema | Causa | Solución |
|---|---|---|
| `No models provided` (HTTP 400) | `config.yaml` con formato plano | Usar estructura YAML anidada (sección 4) |
| `Channel directory built: 0 target(s)` | `TELEGRAM_ALLOWED_USERS` no configurado | Agregar ID numérico en `~/.hermes/.env` |
| Segfault llama-server | `num_ctx` demasiado alto para la RAM disponible | Reducir a `65536`, detener otros servicios |
| `API call failed: No models provided` | OpenRouter configurado sin modelo default | `hermes config set model.default google/gemini-flash-1.5` |
| Gateway no arranca tras reinicio | tmux session no persiste sin Termux:Boot | Iniciar manualmente desde la app |
| `hermes: command not found` tras instalar | Shim en `$PREFIX/bin` no en PATH activo | `source ~/.bashrc` o abrir nueva sesión Termux |

### Limitaciones conocidas

- `modules.json` dice `hasSwitch:false` y `port:""` aunque la pantalla tiene un switch de gateway; la lista de Módulos genérica no puede mostrar su estado como a otros.
- Sin verificación de que el gateway realmente conecte (solo espera 2 s y mira proceso/tmux).
- El instalador no repara el registry en la rama "ya instalado"; el PASO 1 continúa aunque falle.
- `catalogVersion "0.x"` vs script v2.1.0: sin una fuente de versión coherente.
- Inferencia: instalar `.[termux-all]` compilando Rust (firecrawl-anydoc, cryptography) en dispositivo puede fallar o tardar mucho; el estimado "~1 min" de `modules.json` parece irreal; el wheel prebuilt puede no existir.
- Inferencia: Termux es "Tier 2, best-effort" según la doc upstream; el gateway no está probado en dispositivo real según los comentarios de `HermesGatewayFragment.kt`.

---

## 13. Pendientes y Mejoras Futuras

- Agregar `hermes gateway run` al autostart de Termux:Boot.
- Evaluar Open WebUI como frontend web para Hermes (requiere resolver incompatibilidad Python 3.13).
- Probar `qwen2.5:7b-65k` en dispositivo de 16GB para validar Ollama local completo.
- Documentar integración Hermes + Home Assistant (`HASS_TOKEN` ya en `.env`).
