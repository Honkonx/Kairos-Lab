# Ollama

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación, start/stop y estado los maneja la app vía `ProcessBuilder` → `modulos/ollama.sh`.

Ollama es un **backend de IA reutilizable por cualquier módulo** de Kairos — no solo el Chat de la app. Cualquier CLI/agente/proyecto que acepte un endpoint OpenAI-compatible o la API REST de Ollama puede apuntarlo a `http://127.0.0.1:11434`.

---

**Script:** `modulos/ollama.sh` (v5.0.0, 864 líneas) — copia sincronizada en `app/src/main/assets/scripts/ollama.sh`.
**Puerto:** `:11434`
**Registry:** prefijo `ollama.*`

> Convención: **confirmado** = verificado leyendo el código; **sin verificar** = inferencia sin prueba en dispositivo.

---

## 1. Descripción general

Servidor de inferencia LLM local (`ollama serve`, `:11434`), multi-modelo, REST nativa + endpoint OpenAI-compatible. Corre **nativo en Termux** (sin proot, sin contenedor) — motor de inferencia de modelos LLM locales (GGUF cuantizados). Es el motor principal de chat de Kairos junto con llama.cpp (ver `llama-server.md`); a diferencia de ese, Ollama es un **servicio HTTP persistente** al que la app le habla por API REST, y que **cualquier proceso de Termux** (OpenCode, Hermes, OpenClaw, cactus, scripts propios) puede consumir.

Entrada del catálogo (`modules.json:3-29`): `category: "ai"`, `port: "11434"`, `hasVariants: true`, `hasSwitch: true`, `tmuxSession: "ollama-server"`, `installMethods: [termux_npm, standard]`.

Clasificación: **módulo funcional** (el más completo del grupo) — UI propia con estado en vivo (`/api/ps`, `/api/tags`), servicio con switch, y configuración real. Conteo aproximado de controles de UI: **~40** (`OllamaFragment` ≈ 5: switch, ⚙, FAB chat, "Descargar modelo", "Liberar" por modelo; `OllamaConfigFragment` ≈ 35: 12 campos de inferencia/servidor + switch LAN + Guardar/Restaurar + historial 2×(4 presets + personalizado) + estadísticas + borrar historial + crear modelo vía Modelfile + 6 de mantenimiento) más `ModelsFragment` (catálogo de 6 modelos, descarga avanzada por tag, borrar).

**Servicio reutilizable (no solo para el Chat):**
- Expone **dos APIs HTTP** en `127.0.0.1:11434`:
  1. **API REST nativa de Ollama** (`/api/tags`, `/api/chat`, `/api/generate`, `/api/pull`, `/api/delete`, `/api/show`, `/api/embeddings`) — usada por el Chat de Kairos vía `OllamaApiClient`.
  2. **Endpoint compatible OpenAI** `/v1/chat/completions` y `/v1/models` — el que consumen los CLIs (OpenCode, Hermes, cactus `/ai`, etc.) para tratarlo como un proveedor `openai-compatible`.
- `OLLAMA_HOST` bind: default `127.0.0.1` (solo este teléfono). Con el toggle **"Escuchar en LAN"** (`OLLAMA_LAN=1` en `~/.ollama_user_config`) bindea a `0.0.0.0` y queda disponible para otros dispositivos de la red local.
- **Modelos descargados por `ollama pull`** quedan en `~/.ollama/models` y son gestionados desde la app (catálogo + lista real vía `/api/tags`) o desde terminal (`ollama list`/`ollama rm`).

### Cuándo usar Ollama vs llama-server (resumen para elegir backend)

| Criterio | Ollama (`:11434`) | llama-server (`:8085`) |
|---|---|---|
| Instalación | `pkg install ollama` o `@mmmbuto/ollama-termux` (Vulkan) | Binario compilado en la APK (llama-engine NDK) |
| Modelos | Catálogo de `ollama.com/library` + `ollama pull <tag>` | Archivos `.gguf` en `filesDir/models` (compartido) |
| API | REST nativa + `/v1` OpenAI-compatible | Solo `/v1/chat/completions` (un solo modelo a la vez) |
| Historial | `messages` en cada request (stateless) | Stateless por request (reenviar `messages` completo) |
| GPU | Vulkan en la variante `termux_npm` | Vulkan en el motor embebido (runtime switch) |
| Servir varios modelos | Sí (cualquier tag) | No (uno: `LLAMA_SERVER_MODEL`) |
| LAN | Opcional `OLLAMA_LAN=1` | Opcional `LLAMA_SERVER_LAN=1` (ver `llama-server.md`) |

## 2. Permisos

No requiere permisos Android especiales propios — usa `INTERNET` (implícito) para servir su API local y para `ollama pull`. La variante GPU no necesita `WRITE_SECURE_SETTINGS` ni nada privilegiado: Vulkan se activa vía paquetes de Termux (`vulkan-tools`, `mesa-vulkan-icd-freedreno`), no vía permisos de Android.

## 3. Instalación — `modulos/ollama.sh`

Flags (`ollama.sh:55-64`): `--silent`, `--force`, `--describe`, `--describe-files`, `--variant <gpu|standard>`. `--variant` es **obligatorio en silent** (`ollama.sh:136-140`) — por eso la UI no puede usar el helper genérico de actualizar. Con `--describe` devuelve un manifiesto JSON declarativo que `ModuleController.kt` usa: `{"id":"ollama","supports_silent":true,"supports_force":true,"variants":["termux_npm","standard"],"variant_aliases":{"termux_npm":["gpu","termux"],"standard":["pkg","cpu"]},"variant_required":true}`.

### Variantes

| Variante | Alias aceptados | Qué instala | CPU/GPU |
|---|---|---|---|
| `standard` | `pkg`, `cpu` | `pkg install ollama` (paquete ARM64 genérico del repo de Termux) | Solo CPU |
| `termux_npm` | `gpu`, `termux` | `@mmmbuto/ollama-termux` vía npm — build optimizado ARM64 con soporte Vulkan (`:303-586`) | GPU (Vulkan) si el driver lo soporta, cae a CPU si no |

Un valor de `--variant` que no matchea ninguno de los 2 patrones ABORTA con `[ERROR]` explícito (`:142-154`) en vez de dejar `INSTALL_MODE` sin normalizar y marcar el checkpoint como hecho.

### Pasos (6 total, con checkpoint por paso en `~/.install_ollama_checkpoint`)

```
PASO 1/6  Termux update + dependencias (tmux/curl/wget) — skip si instalar.sh/wizard ya corrió
PASO 2/6  Instalar Ollama según variante:
            standard:   pkg install ollama
            termux_npm: pkg install vulkan-tools vulkan-loader-android
                        driver según vendor (ver abajo)
                        pkg install nodejs-lts
                        npm install -g @mmmbuto/ollama-termux@latest
                        → corre "ollama-termux" (baja el binario real de un GitHub Release)
                        → verifica con ollama_binary_works() (ollama --version, no solo command -v)
PASO 3/6  Scripts de control: ollama_start.sh, ollama_stop.sh → ~/scripts/ollama/
PASO 4/6  ~/.ollama_user_config (parámetros de inferencia — solo si no existe, nunca pisa uno ya editado)
PASO 5/6  Aliases en .bashrc (ollama-start/-stop/-status/-list/-run/-pull, export OLLAMA_VULKAN=1)
PASO 6/6  Registry
```

**Rama GPU** (`termux_npm`):
(a) detecta vendor por `getprop ro.board.platform` (adreno → `mesa-vulkan-icd-freedreno`; mali/xclipse → `mesa virglrenderer-android`; otro → solo loader genérico) (`:344-378`);
(b) `npm install -g` no aborta si falla: solo `warn` y sigue (`:411-412`) porque el `install.js` upstream pide un `.sha256` por archivo que no existe en los Releases;
(c) bucle de hasta 3 intentos de `ollama-termux` validando con `ollama --version` (`:444-456`);
(d) **fallback de descarga directa** del tarball del Release + verificación contra `SHA256SUMS` combinado, repo oficial primero y fork `Honkonx/ollama-termux` como respaldo (`:478-568`);
(e) si nada deja el binario funcionando → `error` (no marca checkpoint) (`:570-579`).

Dependencias: tmux/curl/wget (PASO 1, `:259-293`), nodejs-lts (solo GPU, con `retry_cmd`).

Verificación: `verify_binary_installed ollama` en el chequeo "ya instalado" (`:214`) y `ollama_binary_works()` en la rama GPU (`:438`). La rama `standard` no usa verificación real tras `pkg install` (solo `|| error` por exit code, `:309-313`), aunque el PASO 6 sí lee `ollama --version` para el registry (`:832`).

Registry (`update_registry`, `:165-169`): `ollama.installed/version/install_date` (vía `registry_install`), `install_mode`, `commands`, `port=11434`, `location=termux_native`. Aviso a la app: `notify_event "ollama" "install_done"` (`:861`).

### Comportamientos resueltos (evitar reintroducirlos)

- **`npm install` NO deja el binario real funcionando por sí solo** — según el README de `DioNanos/ollama-termux` (el proyecto detrás del paquete npm), el install solo deja el wrapper CLI; el binario real (`bin/ollama` + runtime) se baja de un GitHub Release y se verifica por SHA256 recién la PRIMERA vez que se corre `ollama-termux`. Sin ese paso, `ollama` nunca aparece en PATH sin importar cuántas veces se reinstale.
- **`EEXIST` en reinstalos parciales** — el `install.js` de `DioNanos/ollama-termux` escribe el wrapper con flag exclusivo de Node (`"wx"` — falla si el archivo ya existe). Si un intento anterior quedó a medias (red cortada bajando el Release), cualquier reintento de `ollama-termux` revienta con `EEXIST` sin re-descargar el binario real — y `command -v ollama` igual reporta "encontrado" (el wrapper existe y tiene `+x`) aunque el binario real esté ausente/corrupto. Por esto la verificación real usa `ollama --version`, no `command -v`.
- **Bind seguro por default** — `ollama_start.sh` lee `OLLAMA_LAN` de `~/.ollama_user_config` en runtime — default `127.0.0.1` (solo este teléfono), `0.0.0.0` solo si el usuario activa el toggle "Escuchar en LAN" desde `OllamaConfigFragment`.
- **Sin `send-keys` frágil** — el comando completo (incluido `OLLAMA_VULKAN=1` si la variante es GPU, leído del registry en runtime) se pasa DIRECTO a `tmux new-session`, sin crear una sesión vacía y "tipearle" el comando después.
- **Log persistente** — el output de `ollama serve` se redirige a `~/kairos_logs/ollama_serve.log`.

## 4. Ejecución y detección de estado

- Script generado `~/scripts/ollama/ollama_start.sh` (`:611-723`): sesión tmux `ollama-server`, idempotente; bind `127.0.0.1` por defecto, `0.0.0.0` si `OLLAMA_LAN=1`; exporta `OLLAMA_KEEP_ALIVE`/`OLLAMA_NUM_PARALLEL`/`OLLAMA_MAX_LOADED_MODELS` solo si ≠ vacío/0; `OLLAMA_VULKAN=1` solo si `install_mode=termux_npm`; log `~/kairos_logs/ollama_serve.log`. Tras arrancar (GPU) espera 3 s y escribe `~/.ollama_backend_status` (`gpu|cpu|unknown`) buscando `library=` en el log (`:713-722`).
- `ModuleController.kt`: `getTmuxSession("ollama")` → `ollama-server` (`:1199`) — `isRunning()` chequea `tmux has-session -t ollama-server`; `getModulePort("ollama")` → `11434` (`:1239`) — `waitForPortOpen()` hace poll TCP (hasta 8s) para confirmar arranque real antes de reportar éxito a la UI; start `~/scripts/ollama/ollama_start.sh` (`:1162`); stop `ollama_stop.sh` (`:1184`); `stopAllModules` lo incluye (`:322`). Desinstalar depende de `install_mode` (`:928-940`).
- `verificar.sh` (estrategia `_ver_cmd`) valida en vivo `command -v ollama`.

## 5. Pantallas de la app

### `OllamaFragment.kt` (pantalla principal del módulo)

(`OllamaFragment.kt:12-22`) La pantalla principal es compacta: no tiene Card ESTADO con RAM/Uptime ni botones "Reiniciar"/"Actualizar"/"Info GPU"/"Desinstalar" (esos viven en `OllamaConfigFragment`).

- Fila compacta `:11434 · vX` + ⚙ + switch (`:36-56`). Switch → `startModuleServiceWithPolling`/`stopModuleService` (`:73-85`); el polling cada 2.5s evita que la pantalla se vea "colgada" mientras el modelo carga.
- Banner ámbar "⚠ Corriendo por CPU" si `~/.ollama_backend_status` = `cpu` y modo `termux_npm` (`:123-155`): si la variante GPU no logra usar Vulkan, Ollama cae a CPU en silencio sin avisar al usuario.
- Card de modelos con `modelRow` (`:157-237`): modelos cargados en memoria (`OllamaApiClient.psModels()`, `GET /api/ps`) con "Liberar" (`OllamaApiClient.unloadModel`, `/api/generate` con `keep_alive:0`; sin esto solo se podía esperar el timeout de `OLLAMA_KEEP_ALIVE` o reiniciar TODO el servicio), y modelos descargados (`OllamaApiClient.listModels()`, `GET /api/tags`: nombre · tamaño · familia). `refreshModelsInfo()` corre ambas consultas en un `Thread` de fondo al abrir la pantalla.
- Botón "Descargar modelo" → `ModelsFragment` (`:64`); FAB 💬 → `ChatFragment` (`:66`).

### `OllamaConfigFragment.kt` (configuración y mantenimiento)

6 parámetros + system prompt (`:71-92`), Red/LAN (`:101-125`), Servidor: keep-alive, paralelos, max modelos, **num_thread, num_gpu** (`:132-155`), Guardar/Restaurar (`:156-157`), Historial (RAM presets 2/4/6/8 + personalizado 1..20; disco 20/50/100/∞ + personalizado 10..500) con estadísticas y borrado (`:300-345`), Modelo personalizado (Modelfile real, `ollama create`, `:490-575`), Mantenimiento: Reiniciar, Detalle del proceso (RAM/Uptime se muestran "—": no hay fuente real, nota en `:197-199`), Info GPU, Actualizar (reinstala con la variante del registry, `:211-220`), Diagnosticar, Desinstalar (`:168-181`).

Edita `~/.ollama_user_config` (**12 claves**, `OllamaApiClient.CONFIG_DEFAULTS` `:235-266`) vía `OllamaApiClient.readConfig()`/`writeConfigValue()`/`resetConfig()` — sin pasar por ningún script intermedio:

| Campo | Default | Rango/nota |
|---|---|---|
| `OLLAMA_TEMP` | 0.7 | 0.0–2.0 (validado antes de guardar) |
| `OLLAMA_TOP_P` | 0.9 | — |
| `OLLAMA_TOP_K` | 40 | — |
| `OLLAMA_REP_PENALTY` | 1.1 | — |
| `OLLAMA_NUM_CTX` | 2048 | tokens de contexto |
| `OLLAMA_NUM_PREDICT` | 2048 | tokens máx. de respuesta |
| `OLLAMA_SYSTEM_PROMPT` | (texto largo en español, ver `~/.ollama_user_config` generado) | multilinea |
| `OLLAMA_LAN` | `0` | toggle "Escuchar en LAN" — `1` = `0.0.0.0`, `0` = `127.0.0.1`. Se guarda solo (sin botón "Guardar" aparte), aplica recién la próxima vez que se inicie el servicio |
| `OLLAMA_KEEP_ALIVE`, `OLLAMA_NUM_PARALLEL`, `OLLAMA_MAX_LOADED_MODELS` | — | Servidor |
| `OLLAMA_NUM_THREAD`, `OLLAMA_NUM_GPU` | — | Solo en `CONFIG_DEFAULTS` de Kotlin (no en el config por defecto de `ollama.sh`); la UI aplica el default |

### `ModelsFragment.kt`

- Card CATÁLOGO: 6 modelos curados y pre-cargados (tags confirmados contra `ollama.com/library`) — `qwen2.5:0.5b/1.5b/3b`, `gemma2:2b`, `llama3.2:1b/3b`. Cada fila muestra "✓ Instalado" (si ya está en `OllamaApiClient.listModels()`) o "Descargar" (dispara `pullModel()` directo, sin escribir nombre a mano).
- Card MODELOS INSTALADOS: lista real vía `OllamaApiClient.listModels()` (HTTP directo a `:11434`, no pasa por ningún script intermedio) — tocar un modelo abre detalle (parámetros, familia) con opción "Eliminar".
- "+ Descargar modelo (avanzado)": diálogo de texto libre para cualquier tag no listado en el catálogo.
- Descarga con progreso real en vivo (%, velocidad, ETA) vía `OllamaApiClient.pullModel()` con streaming (`"stream": true`).

### Controles de la pantalla principal y de mantenimiento

| Control | Qué hace | Notas |
|---|---|---|
| Switch "Ollama" (arranque/detención) | `startModuleServiceWithPolling()`/`stopModuleService()` | Polling cada 2.5s mientras el modelo carga |
| Banner "⚠ Corriendo por CPU" (condicional) | Lee `~/.ollama_backend_status` (marcador que deja `ollama_start.sh`) | Avisa que la variante GPU cayó a CPU |
| Card MODELO ACTIVO | Solo lectura, `/api/ps` (`OllamaApiClient.psModels()`) | — |
| "Liberar" (por modelo activo) | `/api/generate` con `keep_alive:0` | Descarga el modelo de memoria sin reiniciar el servicio |
| Card MODELOS DESCARGADOS | Solo lectura, `/api/tags` | Mismo dato que `ModelsFragment` |
| "＋ Descargar modelo" | Navega a `ModelsFragment` | — |
| FAB 💬 | Navega a `ChatFragment` | Atajo directo al chat |
| "⚙ Parámetros de inferencia" | Navega a `OllamaConfigFragment` | Separa la configuración de la pantalla principal |
| "↻ Reiniciar servicio" | `stopModuleService()` → `startModuleService()` encadenados | Acción de un solo toque |
| "🎮 Info GPU / Vulkan" | Corre `vulkaninfo`, lee `/proc/cpuinfo` y `~/.bashrc` (busca `export OLLAMA_VULKAN=1`) | Muestra el dispositivo Vulkan detectado (`deviceName`), si `OLLAMA_VULKAN=1` está exportado, y features de CPU relevantes (`i8mm`/`dotprod`/`sve`) |
| "🔄 Actualizar Ollama" | `ModuleController.installModule()` con `--force` y la variante guardada en `ollama.install_mode` | `ollama.sh` exige `--variant` en modo silent: se reusa la variante original, no se puede usar el helper genérico |
| "🗑 Desinstalar" | `confirmUninstallModule()` | — |

## 6. Archivos y rutas que crea

`$PREFIX/bin/ollama` (pkg o wrapper), `$PREFIX/lib/ollama/**` (runtime ~120 MB, solo GPU), `~/scripts/ollama/ollama_start.sh|ollama_stop.sh`, `~/.ollama_user_config`, `~/.ollama_backend_status`, `~/kairos_logs/ollama_serve.log`, `~/.ollama/models` (modelos), `~/.install_ollama_checkpoint` (se borra al final), entradas en `~/.bashrc` (PASO 5, `:801-825`) y `~/.android_server_registry`.

## 7. Registry (`~/.android_server_registry`)

```
ollama.installed=true
ollama.version=<versión real de "ollama --version">
ollama.install_date=YYYY-MM-DD
ollama.install_mode=termux_npm|standard
ollama.commands=ollama serve,ollama run,ollama list,ollama pull,ollama rm
ollama.port=11434
ollama.location=termux_native
```

## 8. Uso como backend de IA para CUALQUIER módulo/propósito

Ollama **no es solo el Chat de la app**: es un servicio HTTP local reutilizable. Cualquier módulo de Kairos, CLI, agente o script propio que acepte un proveedor "openai-compatible" (o la API REST de Ollama) puede apuntarlo a `http://127.0.0.1:11434`. Patrón ya usado en la app:

| Consumidor | Cómo se conecta | Código |
|---|---|---|
| **ChatFragment** (motor "ollama") | `OllamaApiClient` → `POST /api/chat` con `messages` + `options` + `images` (base64) | `util/OllamaApiClient.kt`, `ChatFragment.makeOllamaRequest()` |
| **OpenCode** | Provider `@ai-sdk/openai-compatible` con `baseURL: http://127.0.0.1:11434/v1` | `OpenCodeNative.ollamaConfig()` |
| **Hermes** | Provider local OpenAI-compatible (lista modelos vía `/api/tags`) | `HermesNative.kt`, `HermesFragment` |
| **CLIs de IA** (qwencode, mimocode, mistralvibe, etc.) | `setLocalProvider(id, "http://127.0.0.1:11434/v1", model)` | `LocalCliProviderNative.kt`, `GenericModuleFragment.useLocalServer` |
| **cactus** | `/ai` → razonador vía Ollama (o llama-server) | `ChatFragment.dispatchCactusRun()` |
| **Cualquier script bash propio** | `curl http://127.0.0.1:11434/api/chat -d '{...}'` o `--base-url http://127.0.0.1:11434/v1` en CLIs compatibles | — |

**Endpoints clave (API REST nativa):**
- `GET  /api/tags` — modelos instalados.
- `POST /api/chat` — chat (recomendado: mantiene `messages`, soporta `images`).
- `POST /api/generate` — generación simple.
- `POST /api/pull` — descargar modelo (streaming de progreso).
- `DELETE /api/delete` — eliminar modelo.
- `POST /api/show` — metadata del modelo (familia, parámetros, capacidad de imagen).
- `POST /api/embeddings` — embeddings (para RAG/vectores).
- `GET  /api/ps` — modelos cargados en memoria/VRAM ahora mismo (`OllamaApiClient.psModels()`).

Los endpoints `/api/embeddings`, `/api/push` y `/api/copy` de la API real no tienen UI propia; son de bajo valor para el caso de uso de Kairos (consumo local, no publicación de modelos).

**Compatibilidad OpenAI:** `POST /v1/chat/completions` y `GET /v1/models` — es el puente que permite usar Ollama como backend de OpenCode, Hermes, o cualquier herramienta que hable `openai-compatible`.

**Web Search (internet para modelos):** el toggle "Web: ON/OFF" del Chat usa la **Web Search API de Ollama** (`POST https://ollama.com/api/web_search`, Bearer key gratuita de `ollama.com/settings/keys`, max 5 resultados, timeout 10s) como **servicio compartido** para los motores locales (ollama Y llama-server) y cloud — los resultados se inyectan como contexto del prompt (`augmentPromptWithWebSearch`). No requiere que el modelo de Ollama esté conectado a internet: el contexto lo agrega el Chat antes del request. Key guardada en `kairos_llm_prefs` (`ollama_web_api_key`), toggle `web_search_enabled`.

## 9. Comandos de referencia

```bash
ollama-start          # alias -> ollama_start.sh (tmux, log en ~/kairos_logs/ollama_serve.log)
ollama-stop           # alias -> ollama_stop.sh
ollama-status         # curl -s http://localhost:11434
ollama list           # modelos descargados
ollama pull <modelo>  # descargar
ollama run <modelo>   # chat CLI directo (fuera de la app)
ollama rm <modelo>    # eliminar
```

## 10. Diferencia con llama.cpp embebido / llama-server

Ver la tabla de la sección 1 y `llama-server.md`. En corto: Ollama es un **servicio HTTP externo multi-modelo** (proceso Termux, API REST + OpenAI-compatible); llama.cpp tiene dos piezas — el **motor embebido JNI** (`LlamaEngine`, en-proceso, sin puerto) y el **servidor `llama-server`** (`:8085`, un solo modelo `.gguf`, OpenAI-compatible). `ChatFragment` los trata como motores distintos con selector de modelo separado, pero comparten el servicio de Web Search.

## 11. Seguridad y limitaciones conocidas

- Bind a loopback por defecto (`:641-642`); `ollama_start.sh` NO hace `source` del config (comentario `:631-638`) y extrae valores con `grep|cut|tr`.
- Los valores extraídos de `OLLAMA_KEEP_ALIVE`, `OLLAMA_NUM_PARALLEL` y `OLLAMA_MAX_LOADED_MODELS` se interpolan **sin validar** dentro del string que se pasa a `tmux new-session` (`:653-663`, `:691-692`), que lo ejecuta con shell. `tr -d` solo quita comillas/espacios/CR, no `;` ni `$()`. Hoy solo el propio usuario (o la UI) escribe ese archivo, y la UI valida numéricos pero **Keep-alive es texto libre** (`OllamaConfigFragment.kt:625-`): vector de inyección local de baja gravedad (severidad sin probar).
- `OLLAMA_LAN=1` expone la API sin autenticación (Ollama no tiene auth nativa) — el toggle trae texto de advertencia (`strings_ollama.xml:16`).
- `export OLLAMA_VULKAN=1` se escribe en `~/.bashrc` para **todas** las variantes (`ollama.sh:813`, sin condicional), aunque `standard` es CPU-only; el start script sí lo condiciona bien (`:675`). Inocuo pero impreciso; además el alias `ollama-lan` (`:820`) bindea `0.0.0.0` sin pasar por el toggle.
- Secretos: no maneja credenciales propias (la API key de Web Search vive en `kairos_llm_prefs`, fuera de este módulo).
- Rama `standard` sin verificación de binario real post-`pkg install` (ver §3).
- Sin verificar en dispositivo: el fallback de descarga directa y el banner CPU (documentados como probados por ADB en comentarios del script, no re-probados).

## 12. Fecha de alta

**2026-07-19** (primer commit de `modulos/ollama.sh`).
