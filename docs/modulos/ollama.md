# Ollama

**Módulo de Kairos** — gestionado desde la UI (pestaña Módulos). Instalación, arranque/detención y estado los maneja la app vía `ProcessBuilder` → `modulos/ollama.sh`.

Ollama es un **backend de IA reutilizable por cualquier módulo** de Kairos, no solo el Chat de la app. Cualquier CLI/agente/proyecto que acepte un endpoint OpenAI-compatible o la API REST de Ollama puede apuntarlo a `http://127.0.0.1:11434`.

---

**Script:** `modulos/ollama.sh` — copia sincronizada en `app/src/main/assets/scripts/ollama.sh`.
**Puerto:** `:11434`
**Registry:** prefijo `ollama.*`

---

## 1. Descripción general

Ollama corre **nativo en Termux** (sin proot, sin contenedor) — motor de inferencia de modelos LLM locales (GGUF cuantizados). Es el motor principal de chat de Kairos junto con llama.cpp; a diferencia de ese, Ollama es un **servicio HTTP persistente** (`ollama serve`, puerto `11434`) al que la app le habla por API REST, y que **cualquier proceso de Termux** (OpenCode, Hermes, OpenClaw, cactus, scripts propios) puede consumir.

**Servicio reutilizable (no solo para el Chat):**
- Expone **dos APIs HTTP** en `127.0.0.1:11434`:
  1. **API REST nativa de Ollama** (`/api/tags`, `/api/chat`, `/api/generate`, `/api/pull`, `/api/delete`, `/api/show`, `/api/embeddings`) — usada por el Chat de Kairos vía `OllamaApiClient`.
  2. **Endpoint compatible OpenAI** `/v1/chat/completions` y `/v1/models` — el que consumen los CLIs (OpenCode, Hermes, cactus, etc.) para tratarlo como un proveedor `openai-compatible`.
- `OLLAMA_HOST` bind: default `127.0.0.1` (solo este teléfono). Con el toggle "Escuchar en LAN" (`OLLAMA_LAN=1` en `~/.ollama_user_config`) bindea a `0.0.0.0` y queda disponible para otros dispositivos de la red local.
- **Modelos descargados por `ollama pull`** quedan en `~/.ollama/models` y son gestionados desde la app (catálogo + lista real vía `/api/tags`) o desde terminal (`ollama list`/`ollama rm`).

### Cuándo usar Ollama vs llama-server

| Criterio | Ollama (`:11434`) | llama-server (`:8085`) |
|---|---|---|
| Instalación | `pkg install ollama` o `@mmmbuto/ollama-termux` (Vulkan) | Binario compilado en la APK (llama-engine NDK) |
| Modelos | Catálogo de `ollama.com/library` + `ollama pull <tag>` | Archivos `.gguf` en `filesDir/models` (compartido) |
| API | REST nativa + `/v1` OpenAI-compatible | Solo `/v1/chat/completions` (un solo modelo a la vez) |
| Historial | `messages` en cada request (stateless) | Stateless por request (reenviar `messages` completo) |
| GPU | Vulkan en la variante `termux_npm` | Vulkan en el motor embebido (runtime switch) |
| Servir varios modelos | Sí (cualquier tag) | No (uno: `LLAMA_SERVER_MODEL`) |
| LAN | Opcional `OLLAMA_LAN=1` | Solo loopback (decisión de diseño) |

## 2. Permisos

No requiere permisos Android especiales propios — usa `INTERNET` (implícito) para servir su API local y para `ollama pull`. La variante GPU no necesita permisos privilegiados: Vulkan se activa vía paquetes de Termux (`vulkan-tools`, `mesa-vulkan-icd-freedreno`).

## 3. Instalación — `modulos/ollama.sh`

Acepta `--silent --variant <gpu|standard> [--force]`. Con `--describe` devuelve un manifiesto JSON declarativo que `ModuleController.kt` usa para no adivinar convenciones: `{"id":"ollama","supports_silent":true,"supports_force":true,"variants":["termux_npm","standard"],"variant_aliases":{"termux_npm":["gpu","termux"],"standard":["pkg","cpu"]},"variant_required":true}`.

### Variantes

| Variante | Alias aceptados | Qué instala | CPU/GPU |
|---|---|---|---|
| `standard` | `pkg`, `cpu` | `pkg install ollama` (paquete ARM64 genérico del repo de Termux) | Solo CPU |
| `termux_npm` | `gpu`, `termux` | `@mmmbuto/ollama-termux` vía npm — build optimizado ARM64 con soporte Vulkan | GPU (Vulkan) si el driver lo soporta, cae a CPU si no |

### Pasos (6 total, con checkpoint por paso)

```
PASO 1/6  Termux update + dependencias (tmux/curl/wget)
PASO 2/6  Instalar Ollama según variante:
            standard:   pkg install ollama
            termux_npm: pkg install vulkan-tools vulkan-loader-android
                        pkg install mesa-vulkan-icd-freedreno (driver Turnip/Adreno)
                        pkg install nodejs-lts
                        npm install -g @mmmbuto/ollama-termux@latest
                        → corre "ollama-termux" (baja el binario real de un GitHub Release)
                        → verifica con ollama_binary_works() (ollama --version, no solo command -v)
PASO 3/6  Scripts de control: ollama_start.sh, ollama_stop.sh → ~/scripts/ollama/
PASO 4/6  ~/.ollama_user_config (parámetros de inferencia — solo si no existe)
PASO 5/6  Aliases en .bashrc (ollama-start/-stop/-status/-list/-run/-pull, export OLLAMA_VULKAN=1)
PASO 6/6  Registry
```

**Notas de arquitectura relevantes**: `npm install` por sí solo no deja el binario real funcionando — el paquete `@mmmbuto/ollama-termux` solo instala el wrapper CLI; el binario real (`bin/ollama` + runtime) se baja de un GitHub Release y se verifica por SHA256 recién la primera vez que se corre `ollama-termux`. Por este motivo la verificación de instalación usa `ollama --version` (confirma que el binario responde) en vez de solo `command -v` (que solo confirma que el archivo existe con permiso de ejecución). El bind por defecto es `127.0.0.1` (solo este teléfono); `0.0.0.0` solo si el usuario activa el toggle "Escuchar en LAN". El arranque corre en una sesión `tmux`, con el output redirigido a `~/kairos_logs/ollama_serve.log`.

## 4. Detección de estado — `ModuleController.kt`

- `getTmuxSession("ollama")` → `"ollama-server"` — `isRunning()` chequea `tmux has-session -t ollama-server`.
- `getModulePort("ollama")` → `11434` — usado por `waitForPortOpen()` para confirmar arranque real (poll TCP, hasta 8s) antes de reportar éxito a la UI.
- `getModuleStartScript("ollama")` → `$HOME/scripts/ollama/ollama_start.sh`; `getModuleStopInfo("ollama")` → `.../ollama_stop.sh`.

## 5. Pantallas de la app

### `OllamaFragment.kt` (pantalla principal del módulo)

- Card ESTADO: proceso (`ollama serve`), puerto (`:11434`), versión.
- Card MODELO ACTIVO — consulta `OllamaApiClient.psModels()` (`GET /api/ps`, modelos cargados en memoria/VRAM ahora mismo) y muestra nombre + tamaño en memoria por modelo, o "Ninguno" si no hay nada cargado.
- Card MODELOS DESCARGADOS — consulta `OllamaApiClient.listModels()` (`GET /api/tags`), mostrando nombre, tamaño y familia por modelo.
- Botones: "Abrir Chat IA con este modelo" (navega a `ChatFragment`), "Reiniciar servicio" (stop+start), switch de arranque/detención.
- "Descargar modelo" → navega a `ModelsFragment` (catálogo + gestión completa de modelos).
- Card CONFIGURACIÓN → "Parámetros de inferencia" → navega a `OllamaConfigFragment`.
- **"Actualizar Ollama"** — reinstala reusando la variante guardada en `ollama.install_mode` del registry.
- **"Info GPU / Vulkan"** → diálogo con datos reales: dispositivo Vulkan detectado (`vulkaninfo`), si `OLLAMA_VULKAN=1` está exportado, y features de CPU relevantes para inferencia (`i8mm`/`dotprod`/`sve`, leídas de `/proc/cpuinfo`).
- Banner condicional "Corriendo por CPU": lee `~/.ollama_backend_status` (marcador que deja `ollama_start.sh`) — si la variante GPU no logra usar Vulkan, Ollama cae a CPU en silencio.
- "Liberar" (por modelo activo) — `/api/generate` con `keep_alive:0`, para no tener que esperar el timeout o reiniciar todo el servicio.

### `ModelsFragment.kt`

- Card CATÁLOGO: modelos curados y pre-cargados (tags confirmados) — `qwen2.5:0.5b/1.5b/3b`, `gemma2:2b`, `llama3.2:1b/3b`. Cada fila muestra "Instalado" (si ya está en `OllamaApiClient.listModels()`) o "Descargar".
- Card MODELOS INSTALADOS: lista real vía `OllamaApiClient.listModels()` — tocar un modelo abre detalle (parámetros, familia) con opción "Eliminar".
- "Descargar modelo (avanzado)": diálogo de texto libre para cualquier tag no listado en el catálogo.
- Descarga con progreso real en vivo (%, velocidad, ETA) vía `OllamaApiClient.pullModel()` con streaming.

### `OllamaConfigFragment.kt`

Edita `~/.ollama_user_config` (7 claves) vía `OllamaApiClient.readConfig()`/`writeConfigValue()`/`resetConfig()`:

| Campo | Default | Rango/nota |
|---|---|---|
| `OLLAMA_TEMP` | 0.7 | 0.0–2.0 (validado antes de guardar) |
| `OLLAMA_TOP_P` | 0.9 | — |
| `OLLAMA_TOP_K` | 40 | — |
| `OLLAMA_REP_PENALTY` | 1.1 | — |
| `OLLAMA_NUM_CTX` | 2048 | tokens de contexto |
| `OLLAMA_NUM_PREDICT` | 2048 | tokens máx. de respuesta |
| `OLLAMA_SYSTEM_PROMPT` | (texto largo en español) | multilinea |
| `OLLAMA_LAN` | `0` | toggle "Escuchar en LAN" — `1` = `0.0.0.0`, `0` = `127.0.0.1`. Aplica recién la próxima vez que se inicie el servicio |

También cubre: red LAN, keep-alive/num_parallel/max_loaded_models, historial (RAM+disco), creación de modelos custom vía Modelfile (`ollama create`), y mantenimiento completo.

## 6. Registry (`~/.android_server_registry`)

```
ollama.installed=true
ollama.version=<versión real de "ollama --version">
ollama.install_date=YYYY-MM-DD
ollama.install_mode=termux_npm|standard
ollama.commands=ollama serve,ollama run,ollama list,ollama pull,ollama rm
ollama.port=11434
ollama.location=termux_native
```

## 7. Comandos de referencia

```bash
ollama-start          # alias -> ollama_start.sh (tmux, log en ~/kairos_logs/ollama_serve.log)
ollama-stop           # alias -> ollama_stop.sh
ollama-status         # curl -s http://localhost:11434
ollama list           # modelos descargados
ollama pull <modelo>  # descargar
ollama run <modelo>   # chat CLI directo (fuera de la app)
ollama rm <modelo>    # eliminar
```

## 8. Uso como backend de IA para cualquier módulo/propósito

Ollama **no es solo el Chat de la app**: es un servicio HTTP local reutilizable. Cualquier módulo de Kairos, CLI, agente o script propio que acepte un proveedor "openai-compatible" (o la API REST de Ollama) puede apuntarlo a `http://127.0.0.1:11434`:

| Consumidor | Cómo se conecta |
|---|---|
| **ChatFragment** (motor "ollama") | `OllamaApiClient` → `POST /api/chat` con `messages` + `options` + `images` (base64) |
| **OpenCode** | Provider `@ai-sdk/openai-compatible` con `baseURL: http://127.0.0.1:11434/v1` |
| **Hermes** | Provider local OpenAI-compatible (lista modelos vía `/api/tags`) |
| **CLIs de agentes de IA** (qwencode, mimocode, mistralvibe, etc.) | `setLocalProvider(id, "http://127.0.0.1:11434/v1", model)` |
| **cactus** | Razonador vía Ollama (o llama-server) |
| **Cualquier script bash propio** | `curl http://127.0.0.1:11434/api/chat -d '{...}'` o `--base-url http://127.0.0.1:11434/v1` en CLIs compatibles |

**Endpoints clave (API REST nativa):**
- `GET  /api/tags` — modelos instalados.
- `POST /api/chat` — chat (recomendado: mantiene `messages`, soporta `images`).
- `POST /api/generate` — generación simple.
- `POST /api/pull` — descargar modelo (streaming de progreso).
- `DELETE /api/delete` — eliminar modelo.
- `POST /api/show` — metadata del modelo (familia, parámetros, capacidad de imagen).
- `POST /api/embeddings` — embeddings (para RAG/vectores).
- `GET  /api/ps` — modelos cargados en memoria/VRAM ahora mismo.

**Compatibilidad OpenAI:** `POST /v1/chat/completions` y `GET /v1/models` — es el puente que permite usar Ollama como backend de OpenCode, Hermes, o cualquier herramienta que hable `openai-compatible`.

**Web Search (internet para modelos):** el toggle "Web: ON/OFF" del Chat usa la **Web Search API de Ollama** (`POST https://ollama.com/api/web_search`, Bearer key gratuita de `ollama.com/settings/keys`, máximo 5 resultados, timeout 10s) como servicio compartido para los motores locales (Ollama y llama-server) y cloud — los resultados se inyectan como contexto del prompt. No requiere que el modelo de Ollama tenga acceso a internet: el contexto lo agrega el Chat antes del request.

## 9. Diferencia con llama.cpp embebido / llama-server

Ollama es un **servicio HTTP externo multi-modelo** (proceso Termux, API REST + OpenAI-compatible); llama.cpp tiene dos piezas — el **motor embebido JNI** (`LlamaEngine`, en-proceso, sin puerto) y el **servidor `llama-server`** (`:8085`, un solo modelo `.gguf`, solo OpenAI-compatible). `ChatFragment` los trata como motores distintos con selector de modelo separado, pero comparten el servicio de Web Search.
