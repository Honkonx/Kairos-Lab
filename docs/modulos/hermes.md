# Hermes Agent

**Módulo Kairos** — Gestionado vía la UI de Kairos (tab Módulos). Instalación, start/stop, y status checks manejados por la app vía ProcessBuilder → bash scripts.

---

**App:** Kairos (fork termux-app)
**Versión documentada:** Hermes Agent v0.16.0
**Autor original:** Nous Research
**Estado:** Producción — Gateway Telegram activo

---

## 1. Descripción General

Hermes Agent es un framework de agente IA de código abierto (MIT) desarrollado por Nous Research. A diferencia de herramientas similares como OpenClaw o OpenCode, Hermes corre de forma **nativa en Termux** sin necesidad de proot, sin contenedores Debian, sin Node.js en proot, y sin root.

Su arquitectura está basada en Python con un entorno virtual (`venv`) instalado en `~/.hermes/hermes-agent/venv/`. Expone una interfaz TUI interactiva, un gateway de mensajería multicanal (Telegram, Discord, Slack, SMS, Signal) y una API server OpenAI-compatible opcional en `:8642`.

Dentro del stack, Hermes se clasifica en el módulo **[1] Servicios** junto a n8n y OpenClaw, ya que su función principal es actuar como gateway de comunicación hacia plataformas de mensajería.

---

## 2. Arquitectura en el Stack

```
Kairos
│
├── [1] Servicios
│     ├── n8n          :5678  proot Debian   — automatización workflows
│     ├── OpenClaw     :18789 proot Debian   — gateway IA multi-proveedor
│     └── Hermes Agent        NATIVO Termux  — agente IA + gateway mensajería
│
├── [2] Code Tools
│     ├── Claude Code         NATIVO Termux
│     └── OpenCode     :3000  proot Debian
│
├── [3] Ollama         :11434 NATIVO Termux  — modelos IA local
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
~/.hermes/                          # Directorio raíz de datos
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
├── skills/                         # Skills cargadas (built-in + custom)
├── logs/                           # Logs del gateway
│   └── gateway.log
├── cron/                           # Tareas programadas
├── hooks/                          # Hooks de eventos
├── image_cache/                    # Caché de imágenes procesadas
└── audio_cache/                    # Caché de audio (TTS/STT)

$PREFIX/bin/hermes                  # Shim lanzador (chmod +x)
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

El archivo tiene permisos `600` — solo el usuario propietario puede leerlo.

---

## 5. Instalación

La instalación se maneja vía Kairos. El proceso adapta el instalador oficial de Nous Research para las restricciones de Android + Termux ARM64:

### Diferencias vs instalador oficial

| Aspecto | Oficial | Kairos |
|---|---|---|
| Gestor de paquetes Python | `uv` | `pip` directo (uv no disponible en Termux) |
| Directorios temporales | `mktemp` → `/tmp/` | Evitado — noexec en Android |
| Prompts interactivos | `read -r -p` libre | Siempre `read -r ... < /dev/tty` |
| Recuperación de fallos | Sin checkpoint | Checkpoint por paso en `~/.install_hermes_checkpoint` |
| Integración de sistema | Sin registro | Escribe en `~/.android_server_registry` |
| Wizard al finalizar | Automático | Pregunta antes de lanzar |
| Gateway post-instalación | Ofrece instalar como servicio | Omitido — se gestiona desde el Module Manager |

### Pasos del instalador (con checkpoint)

```
PASO 1/6  Paquetes del sistema
          pkg install python git clang rust make pkg-config
                      libffi openssl curl ripgrep ffmpeg nodejs

PASO 2/6  Clonar repositorio
          git clone --depth 1 (SSH primero, fallback HTTPS)
          → ~/.hermes/hermes-agent/

PASO 3/6  Entorno virtual Python
          python -m venv ~/.hermes/hermes-agent/venv

PASO 4/6  Dependencias Python (3 niveles de fallback)
          pip install -e '.[termux-all]'   ← intento 1
          pip install -e '.[termux]'       ← fallback 2
          pip install -e '.'              ← fallback 3
          * psutil precompilado con parche Android antes de pip

PASO 5/6  Shim en $PREFIX/bin/hermes
          Protege PYTHONPATH/PYTHONHOME

PASO 6/6  Archivos de configuración
          ~/.hermes/.env · config.yaml · SOUL.md
          Skills sync via tools/skills_sync.py

WIZARD    hermes setup (interactivo vía /dev/tty)
          → Selección de proveedor IA y API key
```

### Variables de entorno críticas para ARM64

```bash
ANDROID_API_LEVEL=35          # Requerido para wheels Rust/maturin (psutil, jiter)
VIRTUAL_ENV=~/.hermes/hermes-agent/venv
UV_NO_CONFIG=1                # Evita que uv herede configuración rota
```

---

## 6. Integración en el Module Manager

### 6.1 Componentes del módulo

| Componente | Función |
|---|---|
| `modulos/hermes.sh` | Script wrapper — status (`check_hermes()`), start/stop gateway, config |
| Registro de módulos de Kairos | Registro de entradas para Hermes (versión, modelo, estado) |
| `HermesFragment.kt` / `HermesGatewayFragment.kt` | UI del módulo Hermes con controles de estado y del gateway de mensajería |

### 6.2 Detección de estado — `check_hermes()`

```bash
check_hermes() {
  command -v hermes &>/dev/null || { echo "not_installed||"; return; }
  local ver; ver=$(get_reg hermes version)
  [ -z "$ver" ] && \
    ver=$(hermes version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
  [ -z "$ver" ] && ver="?"
  tmux has-session -t "hermes-gw" 2>/dev/null \
    && echo "running|${ver}|gw" \
    || echo "stopped|${ver}|"
}
```

Estados posibles: `not_installed`, `stopped`, `running` (gateway activo en tmux `hermes-gw`).

### 6.3 Módulo en el Module Manager

El módulo de Hermes en Kairos presenta el estado actual y las siguientes opciones:

| Opción | Descripción |
|---|---|
| Abrir Hermes (TUI) | Lanza la TUI interactiva de Hermes |
| Gateway (Telegram/Discord/SMS) | Inicia/detiene el gateway de mensajería en tmux |
| Comandos — referencia ejecutable | Lista de comandos Hermes ejecutables |
| Configurar proveedor IA | Cambiar proveedor/modelo IA |
| Proveedor IA local (dropdown) + Configurar | Dropdown ("Ollama local" / "llama-server local") + botón "Configurar proveedor IA local" que despacha a `useOllamaLocal()`/`useLlamaServerLocal()` según la opción elegida |
| Estado y diagnóstico | Ver estado del agente y ejecutar diagnóstico |
| Wizard completo (hermes setup) | Ejecutar el wizard de configuración interactivo |
| Actualizar Hermes | Actualizar a la última versión |
| Instalar / reinstalar | Instalar o reinstalar Hermes |

El header del módulo muestra siempre el proveedor/modelo real leído desde `~/.hermes/config.yaml`.

### 6.4 Control del Gateway

El gateway se ejecuta en una sesión `tmux` llamada `hermes-gw` usando `hermes gateway run` (modo foreground recomendado para Termux, sin systemd).

```bash
# Iniciar
tmux new-session -d -s "hermes-gw" "hermes gateway run"

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

### 6.6 Configurar Ollama local — `_hermes_set_ollama()`

Helper centralizado que genera la estructura YAML correcta para Hermes v0.16:

```bash
_hermes_set_ollama "qwen2.5:7b"
# Genera en ~/.hermes/config.yaml:
#   model:
#     provider: custom
#     base_url: http://127.0.0.1:11434/v1
#     default: qwen2.5:7b
#     ollama_num_ctx: 65536
#     context_length: 65536
```

Incluye verificación del context window del modelo vía `curl http://127.0.0.1:11434/api/show` — advierte si el modelo tiene menos de 64k tokens (requisito mínimo de Hermes para tool calling confiable).

### 6.7 `_hermes_read_config()`

Lee el modelo activo real desde `~/.hermes/config.yaml` con parseo Python sin dependencia de PyYAML. El header del módulo muestra siempre el proveedor/modelo real, no el valor del registry que puede quedar desactualizado.

### 6.8 Registry — entradas de Hermes

```
hermes.installed=true
hermes.version=0.16.0
hermes.install_date=YYYY-MM-DD
hermes.install_dir=/data/data/com.termux/files/home/.hermes/hermes-agent
hermes.model=openrouter/google/gemini-flash-1.5
```

---

## 7. Integración con Telegram

### Requisitos

- Bot creado vía `@BotFather` en Telegram — genera el `TELEGRAM_BOT_TOKEN`
- ID numérico del usuario vía `@userinfobot` — va en `TELEGRAM_ALLOWED_USERS`
- Gateway corriendo (`hermes gateway run`)

### Configuración en `~/.hermes/.env`

```bash
TELEGRAM_BOT_TOKEN=7123456789:AAH1bGci...
TELEGRAM_ALLOWED_USERS=6254844983
TELEGRAM_HOME_CHANNEL=6254844983
```

`TELEGRAM_ALLOWED_USERS` es la lista de control de acceso. Sin este campo configurado el gateway acepta la conexión pero descarta todos los mensajes silenciosamente — el log muestra `Channel directory built: 0 target(s)`.

### Modo de conexión

Hermes usa **long polling** (no webhook) — el gateway hace peticiones periódicas a la API de Telegram. No requiere URL pública, no requiere túnel, funciona con cualquier conexión a internet.

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

### Configurar context window vía Modelfile

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

### Limitación en dispositivos de gama media

Con el stack completo corriendo (n8n, Ollama, gateway Hermes), la RAM disponible para el modelo puede quedar en ~3-4 GB. Esto hace que `num_ctx: 131072` cause segfault del proceso `llama-server` en ARM64. El valor seguro es `65536` (mínimo requerido).

Para dispositivos con más RAM disponible, `qwen2.5:14b` con `num_ctx: 65536` es la opción recomendada — contexto suficiente, calidad superior, tool calling más preciso.

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
hermes gateway run              # Foreground (recomendado Termux)
hermes gateway status
hermes gateway stop

# Actualización
hermes update

# Envío directo (sin agente, sin LLM)
hermes send "mensaje"           # Envía a plataforma configurada

# Mantenimiento
hermes kanban                   # Tablero de tareas
hermes migrate                  # Migrar config a nuevo formato
hermes cron                     # Gestión de tareas programadas
```

---

## 10. Desinstalación

El Module Manager incluye la opción de desinstalar Hermes Agent, que ejecuta:

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

## 11. Problemas Conocidos y Soluciones

| Problema | Causa | Solución |
|---|---|---|
| `No models provided` (HTTP 400) | `config.yaml` con formato plano | Usar estructura YAML anidada (sección 4) |
| `Channel directory built: 0 target(s)` | `TELEGRAM_ALLOWED_USERS` no configurado | Agregar ID numérico en `~/.hermes/.env` |
| Segfault llama-server | `num_ctx` demasiado alto para la RAM disponible | Reducir a `65536`, detener otros servicios |
| `API call failed: No models provided` | OpenRouter configurado sin modelo default | `hermes config set model.default google/gemini-flash-1.5` |
| Gateway no arranca tras reinicio | tmux session no persiste sin gestor de arranque | Iniciar manualmente desde el Module Manager |
| `hermes: command not found` tras instalar | Shim en `$PREFIX/bin` no en PATH activo | `source ~/.bashrc` o abrir nueva sesión Termux |

---

## Controles de la pantalla

### HermesFragment (pantalla principal)

| Control | Qué hace | Por qué |
|---|---|---|
| "🤖 Abrir Hermes (TUI)" | `hermes --tui` (NO `hermes` a secas) | El binario sin flags abre el REPL clásico (prompt_toolkit) — un modo distinto |
| "💬 Chat (hermes chat)" | `hermes chat` | 3er entry point distinto del mismo binario (REPL/TUI/chat) |
| "📡 Gateway" | Navega a `HermesGatewayFragment` | — |
| "🗂 Gestionar proyectos" | `showProjectsMenu()` | — |
| "📖 Comandos — referencia" | Diálogo con la lista completa de comandos y qué hace cada uno | — |
| "🔀 Elegir modelo/proveedor (hermes model)" | `launchTerminalCommand("hermes model")` | Selector 100% interactivo por TTY (flechas), sin flag `--list`/`--json` documentado |
| "🔌 Configurar proveedor IA (rápido, sin terminal)" | Diálogo con proveedor + API key → escribe `~/.hermes/.env` directo | Más rápido que el wizard real, pero sin su validación/formato de `config.yaml` |
| Dropdown "Proveedor IA local" (Ollama/llama-server) + "🔌 Configurar proveedor IA local" | Elige cuál backend local usa Hermes, el botón despacha al flujo real | Decisión excluyente sin ON/OFF asociado |
| "📤 Enviar mensaje (hermes send)" | Diálogo con mensaje → `hermes send` | Envía tal cual a la plataforma configurada (Telegram/Discord/SMS) — **no** pasa por el LLM |
| "🤖 Prompt directo al agente (hermes -z)" | Diálogo con prompt → `hermes -z` | Distinto del de arriba: sí pasa por el LLM y devuelve respuesta, sin abrir TUI |
| "⏹ Detener gateway" | `HermesNative.gatewayStop()` | — |
| "🔍 Estado y diagnóstico" | Diálogo con instalado/versión/gateway/config/proveedor | — |
| "⚙ Wizard completo (hermes setup)" | `launchTerminalCommand("hermes setup")` | — |
| "🩺 Diagnóstico (hermes doctor)" | Terminal — salida de texto libre, no JSON | No tiene sentido envolverlo en un diálogo nativo |
| "📋 Tablero de tareas (kanban)" | `launchTerminalCommand("hermes kanban")` | — |
| "⏰ Tareas programadas (hermes cron)" | `launchTerminalCommand("hermes cron")` | Gestión de tareas programadas del propio agente |
| "↑ Actualizar Hermes (hermes update)" | Camino liviano, solo cae a reinstalar si falla | Distinto de "Instalar/reinstalar" |
| "↑ Instalar / reinstalar" | `reinstallModuleService()` | — |
| "🔗 Conectar memoria de Engram (skill)" | `HermesNative.installEngramSkill()` | Hermes tiene un sistema de Skills real que el agente carga solo cuando aplica |
| "🗑 Desinstalar" (card MANTENIMIENTO) | `confirmUninstallModule()` | — |

### HermesGatewayFragment ("Gateway")

| Control | Qué hace | Por qué |
|---|---|---|
| Switch "Gateway" | `hermes gateway` en tmux (`hermes-gw`) / lo detiene | Termux es plataforma "best-effort" en la doc oficial de Hermes |
| "Ver estado" | Diálogo con corriendo/proveedor/config guardada | — |
| "Ver logs" | `tmux attach -t hermes-gw` | Sin ruta de log estático conocida — se conecta a la sesión en vivo (mismo patrón que n8n) |
