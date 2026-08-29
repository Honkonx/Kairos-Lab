# Hermes Agent — Módulo de Kairos

**Módulo de Kairos** — se gestiona desde la pestaña Módulos de la app. La instalación,
arranque/detención y el chequeo de estado corren vía scripts bash invocados por la app.

---

**Versión documentada:** Hermes Agent v0.16.0
**Estado:** Producción — gateway de Telegram activo

---

## 1. Descripción general

Hermes Agent es un framework de agente de IA de código abierto (licencia MIT) desarrollado por
Nous Research. A diferencia de otras herramientas similares que integra Kairos (OpenClaw,
OpenCode), Hermes corre de forma **nativa en Termux**, sin proot, sin contenedores Debian, sin
Node.js embebido y sin necesidad de root.

Su arquitectura está basada en Python, con un entorno virtual (`venv`) instalado en
`~/.hermes/hermes-agent/venv/`. Expone una interfaz TUI interactiva, un gateway de mensajería
multicanal (Telegram, Discord, Slack, SMS, Signal) y un servidor de API compatible con OpenAI,
opcional, en el puerto `:8642`.

## 2. Arquitectura en el stack

```
Kairos
│
├── Servicios
│     ├── n8n          :5678  proot Debian   — automatización de workflows
│     ├── OpenClaw     :18789 proot Debian   — gateway de IA multi-proveedor
│     └── Hermes Agent        nativo Termux  — agente de IA + gateway de mensajería
│
├── Code Tools
│     ├── Claude Code         nativo Termux
│     └── OpenCode     :3000  proot Debian
│
└── Ollama         :11434 nativo Termux  — modelos de IA local
```

### Diferencias clave frente a OpenClaw

| Aspecto | OpenClaw | Hermes |
|---|---|---|
| Runtime | proot Debian + Node.js | Termux nativo, Python |
| Proceso | Daemon HTTP permanente | TUI interactiva + gateway opcional |
| Puerto | `:18789` fijo | Gateway `:8642` opcional, no expuesto por defecto |
| Chequeo de estado | `curl :18789` | `pgrep -f hermes` + `tmux has-session -t hermes-gw` |
| Telegram | Integración vía n8n | Integración nativa directa |
| Instalación | proot + npm | pip en un venv nativo de Termux |

## 3. Rutas y estructura de archivos

```
~/.hermes/                          # Directorio raíz de datos
├── config.yaml                     # Configuración principal (proveedor, modelo, agente)
├── .env                            # Claves API y tokens (permisos 600)
├── SOUL.md                         # Personalidad del agente (se carga en cada mensaje)
├── hermes-agent/                   # Código fuente (git clone)
│   ├── venv/                       # Entorno virtual Python
│   │   └── bin/hermes              # Binario real del agente
│   ├── constraints-termux.txt      # Constraints de pip para Termux/Android
│   └── scripts/
│       └── install_psutil_android.py  # Parche de psutil para ARM64
├── sessions/                       # Historial de sesiones por ID
├── memories/                       # Memoria persistente del agente
├── skills/                         # Skills cargadas (integradas + personalizadas)
├── logs/                           # Logs del gateway
├── cron/                           # Tareas programadas
├── hooks/                          # Hooks de eventos
├── image_cache/                    # Caché de imágenes procesadas
└── audio_cache/                    # Caché de audio (TTS/STT)

$PREFIX/bin/hermes                  # Shim lanzador
```

### Shim lanzador (`$PREFIX/bin/hermes`)

El instalador no crea un symlink directo, sino un **shim en bash** que limpia variables de
entorno heredadas que rompen el venv:

```bash
#!/data/data/com.termux/files/usr/bin/bash
unset PYTHONPATH
unset PYTHONHOME
exec "/data/data/com.termux/files/home/.hermes/hermes-agent/venv/bin/hermes" "$@"
```

Esto es crítico en Termux porque sesiones anidadas (tmux, proot) pueden heredar `PYTHONPATH` de
otras instalaciones de Python del stack, haciendo que Hermes importe módulos incorrectos.

## 4. Configuración principal — `~/.hermes/config.yaml`

Hermes v0.16 requiere la configuración del modelo en un **bloque YAML anidado**. El formato
plano (`model: ollama/nombre`) de versiones anteriores ya no es válido.

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

> **Error común:** si se escribe `model: ollama/nombre` en una sola línea, Hermes v0.16 lo
> ignora y usa el proveedor por defecto, generando el error `No models provided` contra
> OpenRouter.

### Variables de entorno — `~/.hermes/.env`

```bash
# Telegram
TELEGRAM_BOT_TOKEN=<token-de-BotFather>
TELEGRAM_ALLOWED_USERS=<tu-ID-numérico-de-Telegram>
TELEGRAM_HOME_CHANNEL=<tu-ID-numérico-de-Telegram>

# Proveedores de IA
OPENROUTER_API_KEY=sk-or-v1-...
GOOGLE_API_KEY=AIza...
# ANTHROPIC_API_KEY=sk-ant-...

# Gateway API server (opcional)
# API_SERVER_ENABLED=true
# API_SERVER_KEY=tu-clave-secreta
# API_SERVER_PORT=8642

# Home Assistant (opcional)
# HASS_TOKEN=...
# HASS_URL=http://homeassistant.local:8123
```

El archivo tiene permisos `600` — solo el usuario propietario puede leerlo.

## 5. Instalación

La instalación adapta el instalador oficial de Nous Research a las restricciones de Android +
Termux ARM64:

| Aspecto | Instalador oficial | Adaptación de Kairos |
|---|---|---|
| Gestor de paquetes Python | `uv` | `pip` directo (`uv` no está disponible en Termux) |
| Directorios temporales | `mktemp` → `/tmp/` | Se evita — `/tmp` es noexec en Android 15 |
| Prompts interactivos | `read -r -p` libre | Siempre `read -r ... < /dev/tty` |
| Recuperación de fallos | Sin checkpoint | Checkpoint por paso |
| Wizard al finalizar | Automático | Se pregunta antes de lanzarlo |

### Pasos del instalador

```
1/6  Paquetes del sistema (python, git, clang, rust, make, pkg-config,
     libffi, openssl, curl, ripgrep, ffmpeg, nodejs)
2/6  Clonar el repositorio (SSH primero, fallback HTTPS) → ~/.hermes/hermes-agent/
3/6  Entorno virtual Python
4/6  Dependencias Python, con 3 niveles de fallback:
       pip install -e '.[termux-all]'   ← intento 1
       pip install -e '.[termux]'       ← fallback 2
       pip install -e '.'               ← fallback 3
     (psutil se precompila con un parche específico de Android antes)
5/6  Shim en $PREFIX/bin/hermes (protege PYTHONPATH/PYTHONHOME)
6/6  Archivos de configuración (.env, config.yaml, SOUL.md)

Wizard: hermes setup (interactivo vía /dev/tty) — selección de proveedor de IA y API key
```

### Variables de entorno críticas para ARM64

```bash
ANDROID_API_LEVEL=35          # Requerido para wheels Rust/maturin (psutil, jiter)
VIRTUAL_ENV=~/.hermes/hermes-agent/venv
UV_NO_CONFIG=1                # Evita que uv herede configuración rota
```

## 6. Detección de estado

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

Estados posibles: `not_installed`, `stopped`, `running` (gateway activo en la sesión tmux
`hermes-gw`).

### Controles de la pantalla de módulo

| Opción | Descripción |
|---|---|
| Abrir Hermes (TUI) | Lanza la TUI interactiva de Hermes |
| Gateway (Telegram/Discord/SMS) | Inicia/detiene el gateway de mensajería en tmux |
| Comandos — referencia ejecutable | Lista de comandos de Hermes disponibles |
| Configurar proveedor de IA | Cambiar proveedor/modelo |
| Proveedor de IA local (Ollama / llama-server) | Configura el proveedor local elegido |
| Estado y diagnóstico | Ver estado del agente y ejecutar diagnóstico |
| Wizard completo (`hermes setup`) | Wizard de configuración interactivo |
| Actualizar / instalar / reinstalar | Gestión de la instalación |
| Tareas programadas (`hermes cron`) | Gestión de trabajos programados |

### Control del gateway

El gateway corre en una sesión `tmux` llamada `hermes-gw` usando `hermes gateway run` (modo
foreground, recomendado para Termux, sin systemd):

```bash
# Iniciar
tmux new-session -d -s "hermes-gw" "hermes gateway run"

# Ver logs
tmux attach-session -t "hermes-gw"   # Ctrl+B D para salir sin matar la sesión

# Detener
tmux kill-session -t "hermes-gw"
pkill -f "hermes gateway"
```

### Configurar Ollama local

Un helper centralizado genera la estructura YAML correcta para Hermes v0.16:

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

Incluye verificación del context window del modelo (vía `curl http://127.0.0.1:11434/api/show`)
— advierte si el modelo tiene menos de 64k tokens (requisito mínimo de Hermes para tool calling
confiable).

## 7. Integración con Telegram

### Requisitos

- Bot creado vía `@BotFather` en Telegram — genera el `TELEGRAM_BOT_TOKEN`
- ID numérico del usuario vía `@userinfobot` — va en `TELEGRAM_ALLOWED_USERS`
- Gateway corriendo (`hermes gateway run`)

`TELEGRAM_ALLOWED_USERS` es la lista de control de acceso. Sin este campo configurado, el
gateway acepta la conexión pero descarta todos los mensajes silenciosamente — el log muestra
`Channel directory built: 0 target(s)`.

### Modo de conexión

Hermes usa **long polling** (no webhook) — el gateway hace peticiones periódicas a la API de
Telegram. No requiere URL pública ni cloudflared, funciona con cualquier conexión a internet.

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

## 8. Ollama local con Hermes

### Requisito crítico

Hermes requiere un modelo con **mínimo 64,000 tokens de contexto** para tool calling confiable.
Modelos con ventana menor son rechazados al iniciar.

### Modelos compatibles por dispositivo (orientativo)

| Modelo | RAM base | Context nativo | Dispositivo ~11GB RAM | Dispositivo 16GB RAM |
|---|---|---|---|---|
| `qwen2.5:7b` | ~5 GB | 128k | ajustado | recomendado |
| `qwen2.5:14b` | ~10 GB | 128k | no recomendado | recomendado |
| `llama3.1:8b` | ~6 GB | 128k | ajustado | recomendado |
| `deepseek-r1:7b` | ~5 GB | 64k | ajustado | recomendado |
| `qwen2.5:3b` | ~2 GB | 32k | con Modelfile | recomendado |

### Configurar el context window vía Modelfile

Cuando el modelo carga con contexto insuficiente por defecto, se crea un Modelfile que fuerza
`num_ctx`:

```bash
cat > ~/qwen25_7b_65k.modelfile << 'EOF'
FROM qwen2.5:7b
PARAMETER num_ctx 65536
EOF

ollama create qwen2.5:7b-65k -f ~/qwen25_7b_65k.modelfile
```

### Nota sobre RAM en dispositivos de gama media

Con el stack completo corriendo (n8n, Ollama, gateway de Hermes), la RAM disponible para el
modelo puede reducirse a solo unos pocos GB. Un `num_ctx` demasiado alto (por ejemplo 131072)
puede provocar un segfault del proceso `llama-server` en ARM64 por falta de memoria — el valor
seguro recomendado es `65536` (el mínimo requerido por Hermes).

## 9. Comandos de referencia rápida

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
hermes gateway run              # Foreground (recomendado en Termux)
hermes gateway status
hermes gateway stop

# Actualización
hermes update

# Envío directo (sin pasar por el agente/LLM)
hermes send "mensaje"

# Mantenimiento
hermes kanban                   # Tablero de tareas
hermes migrate                  # Migrar config a nuevo formato
hermes cron                     # Gestión de tareas programadas
```

## 10. Desinstalación

```bash
# Detener gateway
tmux kill-session -t "hermes-gw" 2>/dev/null
pkill -f "hermes gateway" 2>/dev/null

# Eliminar código (conserva la configuración)
rm -rf ~/.hermes/hermes-agent/
rm -rf ~/.hermes/venv/
rm -f  $PREFIX/bin/hermes
rm -f  ~/.local/bin/hermes
```

`~/.hermes/config.yaml` y `~/.hermes/.env` se **conservan intencionalmente** para no perder las
claves API y los tokens de Telegram al reinstalar.

## 11. Problemas conocidos y soluciones

| Problema | Causa | Solución |
|---|---|---|
| `No models provided` (HTTP 400) | `config.yaml` con formato plano | Usar la estructura YAML anidada (sección 4) |
| `Channel directory built: 0 target(s)` | `TELEGRAM_ALLOWED_USERS` no configurado | Agregar el ID numérico en `~/.hermes/.env` |
| Segfault de `llama-server` | `num_ctx` demasiado alto para la RAM disponible | Reducir a `65536`, detener otros servicios |
| `API call failed: No models provided` | OpenRouter configurado sin modelo por defecto | `hermes config set model.default google/gemini-flash-1.5` |
| Gateway no arranca tras reiniciar el dispositivo | La sesión tmux no persiste sin arranque automático | Iniciar manualmente desde el gestor de módulos |
| `hermes: command not found` tras instalar | El shim no está en el PATH activo de la sesión | `source ~/.bashrc` o abrir una nueva sesión de Termux |
