# llama-server / IA Local (llama.cpp)

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos, id `llamaserver`, nombre
"IA Local (llama.cpp)"). Instalación, start/stop y estado los maneja la app vía `ProcessBuilder`
→ `modulos/llamaserver.sh`.

llama.cpp tiene **dos piezas** que conviven en el módulo `llama-engine/`: el motor embebido
JNI (en-proceso, sin puerto) y el servidor `llama-server` (HTTP `:8085`) que **cualquier
módulo/CLI puede usar como backend** OpenAI-compatible.

---

**Script:** `modulos/llamaserver.sh` (362 líneas; copia sincronizada en `app/src/main/assets/scripts/llamaserver.sh`).
**Puerto:** `:8085` (loopback `127.0.0.1` por defecto; el toggle `LLAMA_SERVER_LAN` lo bindea a `0.0.0.0`).
**Registry:** prefijo `llamaserver.*`
**Módulo Gradle:** `llama-engine/` (NDK/CMake/JNI, adaptado de OfflineLLM, Apache-2.0).
**Docs técnicos:** `docs/ia-local/llama-cpp-local-engine.md` (bitácora de build).

> Convención de este documento: **confirmado** = verificado leyendo el código; **sin verificar** =
> inferencia sin prueba en dispositivo.

---

## 1. Descripción general

llama.cpp es el motor de inferencia LLM local que Kairos trae **compilado dentro de la APK**
(módulo `llama-engine/`, NDK, ABI `arm64-v8a`). No es un paquete de Termux: se compila en
CI/GitHub Actions y el binario viaja como asset del APK. En `modules.json` (`:757-777`): nombre
"IA Local (llama.cpp)", `category: "ai"`, `port: "8085"`, `hasSwitch: true`,
`tmuxSession: "llamaserver"`, sin variantes. Es un **módulo funcional**: UI propia con estado en
vivo (`GET /health`), servicio con switch, y opciones reales que se traducen a flags del binario.

Tiene **dos piezas** que comparten el mismo módulo y el mismo directorio de modelos:

| Pieza | Qué es | Puerto | Uso |
|---|---|---|---|
| `kairos_llm` (wrapper JNI `LlamaEngine`) | Motor **en-proceso** para el tab "IA Local"/Chat nativo | sin puerto | Inferencia dentro del proceso de la app (sin servidor de por medio). Historial incremental con KV-cache |
| `llama-server` (binario real de llama.cpp) | Servidor HTTP **compatible OpenAI** | `127.0.0.1:8085` | Sirve a CLIs/agentes/procesos Termux separados (OpenCode, Hermes, OpenClaw, cactus) y como backend de cualquier módulo |

**Por qué existen las dos:** los CLIs de Termux (OpenCode/Hermes/OpenClaw) son **procesos
separados** y necesitan un endpoint de red para usar el motor — un enfoque puramente en-proceso
(".so + JNI puro") no podría ofrecer eso. El servidor HTTP lo resuelve: mismo `.gguf`, mismo
motor, pero accesible por socket.

**Backend reutilizable (no solo para el Chat):** `POST http://127.0.0.1:8085/v1/chat/completions`
es compatible OpenAI. Cualquier módulo, CLI, agente o script que acepte un proveedor
`openai-compatible` puede apuntarlo ahí (ver sección 8).

## 2. Permisos

No requiere permisos Android especiales. El binario y sus `.so` viajan como assets del APK
(extraídos por `KairosBootstrap` a `~/scripts/install/`), y el motor embebido usa JNI dentro
del proceso. El acceso a los modelos `.gguf` (storage privado
`/data/data/com.termux/files/models`) funciona desde scripts Termux porque app y Termux comparten
`sharedUserId` (`com.termux`).

## 3. Instalación — `modulos/llamaserver.sh`

Acepta `--silent [--force]`, `--describe` y `--describe-files` (`:45-60`). Con `--describe`
devuelve el manifiesto JSON declarativo que `ModuleController.kt` usa:
`{"id":"llamaserver","supports_silent":true,"supports_force":true,...}`. No tiene variantes (un
solo camino de instalación, `:57`). Dependencias declaradas: ninguna (`dependencies: []`, `:99`);
`tmux` y `jq` se asumen presentes.

**No descarga nada**: copia `~/scripts/install/llama-server` (extraído por `KairosBootstrap`) a
`$PREFIX/bin` y las `.so` a `$PREFIX/lib` (`:141-160`). Si el origen no existe → `error` (`:142`).

### Pasos (3 total, con checkpoint en `~/.install_llamaserver_checkpoint`)

```
PASO 1/3  Copiar el binario compilado:
            $HOME/scripts/install/llama-server → $PREFIX/bin/llama-server  (chmod 755)
            Copiar .so dependientes a $PREFIX/lib/:
              libggml-base.so, libllama.so, libllama-common.so, libmtmd.so, libllama-server-impl.so
            → fix del bug "CANNOT LINK EXECUTABLE: library libllama-server-impl.so not found"
            → verificación defensiva: timeout 5 llama-server --version
PASO 2/3  Scripts de control: ~/scripts/llamaserver/start.sh y stop.sh
PASO 3/3  ~/.llamaserver_user_config (LLAMA_SERVER_MODEL= + LLAMA_SERVER_PORT=8085)
          + registry_install llamaserver "<versión>" "port=8085"
```

Registry: `registry_install llamaserver <versión> port=8085` (`:112-115`).

**Cómo llega el binario al APK:** la tarea Gradle `downloadLlamaCpp` del módulo `llama-engine`
clona llama.cpp en build-time (tag `b10194` en `llama-engine/build.gradle:286`, sobreescribible
con env `KAIROS_LLAMACPP_TAG`), compila `llama-server` con CMake (flags: `LLAMA_BUILD_SERVER=ON`,
`GGML_VULKAN=ON`, `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `BUILD_SHARED_LIBS=ON`,
OpenMP/LLAMAFILE OFF) y lo copia a `app/src/main/assets/scripts/llama-server` (via
`copy_shared_libs.sh` + `add_dependencies`). `KairosBootstrap.kt` extrae ese asset a
`~/scripts/install/llama-server` en runtime. Un parche de texto sobre `tools/CMakeLists.txt` de
llama.cpp deshabilita las ~10 tools de debug/bench innecesarias que rompían el link.

### Problemas resueltos

- **`libllama-server-impl.so not found`** — llama-server necesita sus `.so` dependientes al lado
  o en el path de carga. Se copian explícitamente a `$PREFIX/lib/` en el PASO 1.
- **Backends no encontrados** — `GGML_BACKEND_DIR` es una **macro de compilación**, nunca
  variable de entorno de runtime. llama.cpp busca los `.so` de backend en
  `get_executable_path()` (directorio del binario) y `fs::current_path()` — por eso `start.sh`
  hace `cd '$PREFIX/lib'` antes de lanzar el binario (confirmado leyendo
  `ggml/src/ggml-backend-reg.cpp` del tag `b10194`). La alternativa más robusta (copiar los
  `.so` a `$PREFIX/bin`) queda como opción futura.
- **`send-keys` / shell interactivo**: no aplica — el comando completo va directo a
  `tmux new-session`.

## 4. Arranque — `start.sh` generado

- Sesión tmux **`llamaserver`**; idempotente (si ya corre, exit 0) (`:181-273`).
- Lee `~/.llamaserver_user_config` **en runtime** con `grep|cut|tr` (sin `eval`): `LLAMA_SERVER_MODEL`,
  `LLAMA_SERVER_PORT`, `LLAMA_SERVER_CTX_SIZE`, `LLAMA_SERVER_THREADS`, y los flags opcionales
  `-c`, `-t`, `-ngl`, `--api-key`, `--embeddings`, `--parallel`.
- **Modelo obligatorio**: si el `.gguf` no existe en `MODELS_DIR` falla con mensaje claro (`:245-248`).
- **Contexto (tokens) y threads CPU configurables**: `LLAMA_SERVER_CTX_SIZE`/`LLAMA_SERVER_THREADS`
  (default `0` = usar el default del binario: `-c 0` = contexto de entrenamiento del modelo,
  `-t` auto-detectado) se agregan como `-c $CTX_SIZE`/`-t $THREADS` a `EXTRA_ARGS` solo si el
  valor guardado no es `0`.
- Host `127.0.0.1`, o `0.0.0.0` si `LLAMA_SERVER_LAN=1` (`:241-243`).
- **Comando real** (`:250-264`):
  ```sh
  cd '$TERMUX_PREFIX/lib' && '$BIN' -m '$MODELS_DIR/$MODEL_FILE' --host 127.0.0.1 --port $PORT \
    $EXTRA_ARGS > '~/kairos_logs/llamaserver_serve.log' 2>&1
  ```
- **MODELS_DIR** = `/data/data/com.termux/files/models` = `LocalModelManager.modelsDir()`
  (`context.filesDir/models`, ruta fija en `:70`). Compartido con el motor embebido y con el Chat.
- `stop.sh` mata la sesión.

## 5. Detección de estado — `ModuleController.kt`

- `getTmuxSession("llamaserver")` → `"llamaserver"` (`:1203`) — `isRunning()` chequea `tmux has-session`.
- `getModulePort("llamaserver")` → `8085` (`:1244`) — `waitForPortOpen()` poll TCP hasta 8s.
- `getModuleStartScript` → `~/scripts/llamaserver/start.sh` (`:1168`); stop → `.../stop.sh` (`:1189`).
- `stopAllModules` lo incluye (`:322`).
- `verificar.sh` (estrategia `_ver_cmd`) valida en vivo `command -v llama-server`.

## 6. Pantallas de la app

### `LlamaServerFragment.kt`

- Fila compacta `:8085` + ⚙ + switch (`:52-81`); card con el modelo elegido y estado vía
  `GET /health` (`checkHealth()`, `:135-187`).
- **Estado real vía `/health`**: `llama-server` puede tardar varios segundos en cargar el modelo
  (mmap + backend) antes de aceptar requests y devuelve `{"status":"loading model"}` en ese
  lapso. `refreshStatus()` corre en un `Thread` de fondo: si el proceso está corriendo, hace
  `GET http://127.0.0.1:$PORT/health` (timeout 2s) y muestra "● Activo (modelo cargado)"
  (`status: ok`), "◐ Cargando modelo…" (`status: loading model`), el status crudo si es otro
  valor, o "● Proceso activo (sin responder aún)" si `/health` no contesta.
- **Selector de modelo** ("Elegir modelo (.gguf)", `showModelPicker`, `:189-210`): lista
  `LocalModelManager.listModels` y escribe `LLAMA_SERVER_MODEL` en `~/.llamaserver_user_config`
  (no tiene `/api/tags` como Ollama — lista los archivos directamente). Si no hay ninguno, avisa
  de descargar desde Chat IA.
- "Catálogo" → `LocalAIFragment`.
- Al activar el switch sin modelo elegido, se revierte a OFF (`:85-90`) y avisa. Además
  **chequea RAM libre** con `MemoryMonitor` (diálogo si crítica, toast si en aviso, `:97-109`).

### `LlamaServerConfigFragment.kt`

Concentra todos los parámetros (la pantalla principal solo tiene switch, ⚙, "Elegir modelo" y
"Catálogo"). `onResume` activa `FLAG_SECURE` y `filterTouchesWhenObscured` (`:46-66`).
"Guardar parámetros" valida enteros ≥ 0 y escribe 7 claves `LLAMA_SERVER_*` (`:254-271`); además
guarda `OLLAMA_SYSTEM_PROMPT` (clave compartida con Ollama) en un hilo (`:272-278`). Los sliders
de temperatura/contexto y el radio CPU/Vulkan escriben en `SharedPreferences` `kairos_llm_prefs`
y afectan **solo al motor embebido**, no al `llama-server` (`:282-386`) — la pantalla los junta
bajo el mismo título. Los cambios del servidor aplican en el próximo arranque, no en caliente.

### `LocalAIFragment.kt` ("IA Local" en el menú Más)

- **Estado del backend GPU detectado** (via `LlamaEngine.getGpuDeviceName()`).
- Selector **CPU-only / Vulkan-si-disponible** (`GpuBackend` enum) — el switch de runtime que
  la variante GPU de Ollama no permite.
- Sliders de `temperature` / `context_size` con `safeMax` dinámico según RAM del dispositivo.
- Gestión de modelos `.gguf` descargados.

### `LocalModelManager.kt` (util) — descarga de `.gguf` con resume

- Directorio `filesDir/models`. Patrón `.part` → `nombre.gguf.part`.
- **Resume por `Range: bytes=N-`**: `206` → append (progreso arranca en N); `416` → descarta
  `.part` y reintenta una vez; `200` (servidor sin Range) → trunca y arranca de cero.
- **Validación multi-capa**: ratio descargado/`Content-Length` ≥ 0.95, mínimo absoluto 1024
  bytes, y **magic bytes reales `GGUF`** (primeros 4 bytes) antes de renombrar a destino final.
- Velocidad/ETA cada 500ms.
- `cleanupOrphanedPartFiles()` borra `.part` huérfanos excepto el en curso.

### `LocalAIFragment.CATALOG` — modelos curados (Q4_K_M, 0.5B–3B, Hugging Face)

`Qwen2.5-0.5B`, `Qwen2.5-1.5B`, `SmolLM2-1.7B`, `Llama-3.2-1B`, `Llama-3.2-3B`, `Gemma-2-2B`
(URLs `resolve/main/<archivo>.gguf`). También se puede agregar por URL directa + nombre
(`showAddModelDialog`, opción avanzada).

## 7. El motor embebido JNI (`LlamaEngine`)

- Wrapper Kotlin de `kairos_llm` (JNI `com.termux.llm.LlamaEngine`). **Sin coroutines** —
  llamadas bloqueantes + callbacks (mismo criterio que `RootfsInstaller.kt`).
- **`loadBackends(nativeLibDir)`** → `ggml_backend_load_all_from_path` (o `ggml_backend_load_all`),
  idempotente. Carga `libggml-cpu-android_*.so` (variantes dotprod/fp16/i8mm/SVE, ggml puntúa la
  mejor) + `libggml-vulkan.so`.
- **Detección GPU**: `getGpuDeviceName()` recorre `ggml_backend_dev_count()` buscando el primer
  device `GGML_BACKEND_DEVICE_TYPE_GPU/IGPU`.
- **`GpuBackend.resolveGpuLayers()`**: `CPU_ONLY → 0`; `VULKAN_IF_AVAILABLE → 99` (offload
  completo) si hay GPU, si no `0` con fallback silencioso.
- **Inferencia** (`LLMInference.cpp`): mapea `useMmap/useMlock` → `llama_load_mode`, sampler
  chain, **historial incremental con KV-cache** (`_prevLen` evita re-tokenizar todo),
  `_assistantRole = "model"` para templates tipo Gemma.
- **Errores accionables**: `llama_log_set` captura líneas WARN/ERROR de ggml/llama.cpp en
  `_lastErrorLog` y las concatena a la excepción — es la fuente de los patrones de
  `LlmErrorMapper` (traduce errores del motor nativo y errores HTTP de Ollama leyendo
  `{"error": ...}`).
- **`GGUFReader`**: lee metadata del `.gguf` (`context_length`, `chat_template`) sin cargar el
  modelo; fallback a 2048 y template chatml.
- `numThreads = min(availableProcessors, 4)`. `temperature`/`context_size` desde prefs.

## 8. Uso como backend de IA para CUALQUIER módulo/propósito

`llama-server` es un **servicio HTTP local reutilizable** — cualquier módulo, CLI, agente o
script que acepte un proveedor `openai-compatible` puede apuntarlo a
`http://127.0.0.1:8085/v1`. Patrón ya usado en la app:

| Consumidor | Cómo se conecta | Código |
|---|---|---|
| **ChatFragment** (motor "local", transporte `http`) | `POST /v1/chat/completions` SSE con `messages` + system prompt, modelo `"local"` (el servidor sirve un solo modelo) | `ChatFragment.makeLlamaServerRequest()` |
| **Motor embebido JNI** (fallback) | Transporte `embedded`; si falla, cae automáticamente a `llama-server` si está disponible | `ChatFragment.makeLocalRequest()` (catch → fallback) |
| **OpenCode** | Provider `@ai-sdk/openai-compatible`, `baseURL: http://127.0.0.1:8085/v1`, `apiKey: "llamaserver"`, modelo `llamaserver/<modelo>` | `OpenCodeNative.llamaServerConfig()`, botón "Configurar llama-server local" en `OpenCodeFragment` |
| **Hermes** | Provider local OpenAI-compatible; lista `.gguf` directo de `LocalModelManager` (no tiene `/api/tags`) | `HermesFragment` ("Usar llama-server local") |
| **CLIs de IA** (qwencode, mimocode, mistralvibe, etc.) | `setLocalProvider(id, "http://127.0.0.1:8085/v1", model)` | `LocalCliProviderNative.kt`, `GenericModuleFragment.useLlamaServerLocal` |
| **cactus** | `/ai` → razonador vía Ollama 11434 o llama-server 8085 | `ChatFragment.dispatchCactusRun()` |
| **Cualquier script bash propio** | `curl http://127.0.0.1:8085/v1/chat/completions -d '{...}'` | — |

**Endpoint clave:** `POST http://127.0.0.1:8085/v1/chat/completions` — OpenAI-compatible,
streaming SSE (`data: {...}` + centinela `[DONE]`). **Stateless por request**: hay que reenviar
el array completo de `messages` + system prompt cada vez (a diferencia del motor JNI que
mantiene historial incremental). El campo `model` se ignora (sirve **un solo modelo**, el de
`LLAMA_SERVER_MODEL`).

**Web Search (internet para modelos):** el toggle "Web: ON/OFF" del Chat usa la Web Search API
de Ollama como **servicio compartido** (ollama + llama-server + cloud) — ver `ollama.md` §8.
Se inyecta contexto en `makeLlamaServerRequest` igual que en Ollama.

## 9. Registry (`~/.android_server_registry`)

```
llamaserver.installed=true
llamaserver.version=<versión de llama-server>
llamaserver.install_date=YYYY-MM-DD
llamaserver.commands=llama-server -m <modelo> --host 127.0.0.1 --port 8085
llamaserver.port=8085
llamaserver.location=termux_native
```

## 10. Comandos de referencia (desde una shell Termux)

```bash
llama-server --version                                   # binario instalado
# Arranque real (equivalente a lo que hace start.sh):
cd $PREFIX/lib && llama-server -m $HOME/../files/models/<modelo>.gguf --host 127.0.0.1 --port 8085
# Probar el endpoint compatible OpenAI:
curl -s http://127.0.0.1:8085/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"local","messages":[{"role":"user","content":"hola"}]}'
# Modelos .gguf (storage privado compartido por sharedUserId):
ls -lh /data/data/com.termux/files/models/
```

## 11. Comparación con Ollama

Ver tabla comparativa completa en `ollama.md` §1. En corto: Ollama = servidor externo
multi-modelo (REST + OpenAI-compatible, puede bindear LAN); llama-server = servidor de **un
solo modelo** `.gguf` (OpenAI-compatible; loopback por defecto, LAN opcional con
`LLAMA_SERVER_LAN`) + motor embebido JNI en-proceso. El Chat los trata como motores con selector
separado, pero comparten el directorio de modelos y el servicio de Web Search.

## 12. Estado / verificación

- Compila y linkea en CI (GitHub Actions + GitLab); build local en Windows verificado (APK
  universal ~241MB con llama-engine/Vulkan incluido).
- Servidor HTTP confirmado funcionando de punta a punta.
- Conexión de más módulos al backend: ya implementada para OpenCode/Hermes/CLIs de IA/cactus —
  ver §8. Cualquier módulo futuro que acepte un proveedor `openai-compatible` puede reutilizar
  el mismo patrón sin tocar llama.cpp.
- Pendiente: probar el flujo completo en dispositivo real y el motor embebido JNI con modelos ≥3B.

## 13. Controles de la pantalla

La pantalla principal (`LlamaServerFragment.kt`) solo tiene switch, ⚙, "Elegir modelo" y
"Catálogo"; los campos numéricos, API key, parallel, embeddings y LAN, "Guardar parámetros",
Reiniciar/Actualizar/Desinstalar están en `LlamaServerConfigFragment`. Conteo aproximado de
controles: **~28** (≈ 4 en la pantalla principal; ≈ 24 en la de configuración: 7 del servidor
[ctx, threads, `-ngl`, API key, parallel, embeddings, LAN] + Guardar, system prompt, contexto de
chat RAM [4 presets + personalizado], motor embebido [2 radios CPU/Vulkan + slider temperatura +
slider contexto], mantenimiento [Reiniciar, Detalle, Info GPU, Actualizar, Diagnosticar,
Desinstalar]).

| Control | Qué hace | Por qué |
|---|---|---|
| Switch "llama-server" | `switchRow()` — ON arranca el servicio (`startModuleService`), OFF lo detiene. Si se activa sin modelo elegido, se revierte a OFF (`setSwitchState(false)`) y avisa "Elegí un modelo primero" | La validación de "modelo primero" evita un switch encendido que no arrancó nada |
| "🗂 Elegir modelo (.gguf)" | Abre `showModelPicker()` — lista los `.gguf` ya descargados (`LocalModelManager.listModels()`) y guarda la elección en `~/.llamaserver_user_config` | Este módulo no descarga modelos propios — reusa el catálogo de Chat IA/`LocalAIFragment` |
| "📥 Catálogo de modelos (GGUF)" | Navega a `LocalAIFragment` (catálogo de descarga) | Vive dentro del módulo IA Local en vez de aparte |
| "🔄 Actualizar" | `updateModuleService()` | — |
| "🗑 Desinstalar" (card MANTENIMIENTO) | `confirmUninstallModule()` | Botón suelto en vez de `addMaintenanceCard()` — evita duplicar "Actualizar" (consistente con Db/Entorno/Qemu/Remote/Ciberseguridad) |
| Campo "Contexto (tokens)" / "Threads CPU" / "Capas en GPU (-ngl)" | Numéricos, `0` = default del binario (auto-detección). Se escriben en `~/.llamaserver_user_config`, los lee `start.sh` en el próximo arranque | `-ngl` es real: el binario compila con backend Vulkan (`GGML_VULKAN=ON`) y sin ese flag la aceleración GPU queda inactiva |
| Campo "API key (opcional)" | Flag `--api-key` del servidor real | Confirmado contra `tools/server/README.md` de ggml-org/llama.cpp — sin esto el servidor solo corre sin autenticar |
| Campo "Slots concurrentes (--parallel)" | Flag `--parallel` | — |
| Switch "Modo embeddings (--embeddings)" | Flag `--embeddings` | — |
| Switch "Escuchar en LAN (0.0.0.0)" | Bindea el servidor a todas las interfaces en vez de solo loopback | Texto de advertencia explícito en pantalla: "se recomienda setear una API key antes de habilitar LAN" — sin eso, cualquier dispositivo de la red puede usar el servidor sin restricción (la UI advierte con texto, no bloquea, `:226-231`) |
| "Guardar parámetros" | Valida que ctx/threads/ngl/parallel sean enteros ≥ 0 antes de escribir; si no, "Valores inválidos" | Los cambios se aplican recién en el próximo arranque del servicio, no en caliente — aclarado en el toast de confirmación |

**Detalle no obvio**: el estado "Activo" no es solo "el proceso corre" — hace `GET /health` real
contra el servidor (`checkHealth()`) porque `llama-server` puede tardar varios segundos en cargar
el modelo antes de aceptar requests.

## 14. Archivos y rutas que crea

`$PREFIX/bin/llama-server`, `.so` en `$PREFIX/lib/` (libggml-base, libllama, libllama-common,
libmtmd, libllama-server-impl), `~/scripts/llamaserver/start.sh|stop.sh`,
`~/.llamaserver_user_config`, `~/kairos_logs/llamaserver_serve.log`,
`~/.install_llamaserver_checkpoint` (se borra al final), entrada de registry. Modelos en
`/data/data/com.termux/files/models` (compartido con el Chat).

## 15. Seguridad y limitaciones conocidas

- **API key visible**: `LlamaServerConfigFragment.kt:150` hace
  `apiKeyInput?.setText(readConfigValue("LLAMA_SERVER_API_KEY"))` — la API key guardada se vuelve
  a mostrar en texto plano en un `EditText` (`InputType.TYPE_CLASS_TEXT`, `:527`, sin máscara), y
  se guarda en claro en `~/.llamaserver_user_config`. Mitigación existente: `FLAG_SECURE` y
  anti-tapjacking en esa pantalla.
- La clave viaja como argumento (`--api-key $API_KEY`, `:233`), visible en la lista de procesos
  (`ps`/`/proc/<pid>/cmdline`) para el mismo UID; no hay `chmod 600` explícito sobre el config
  (los archivos de `$HOME` son privados del UID de la app; riesgo real bajo, sin verificar).
- Aunque no hay `eval`/`source`, `API_KEY`, `MODEL_FILE` y los demás valores se interpolan sin
  comillas dentro del string que va a `tmux new-session` (shell) (`:264`). `tr -d` quita comillas
  y espacios, no `;`/`$()`. Los numéricos están validados por la UI; la API key y el nombre de
  modelo no → inyección local posible si el config se edita a mano (severidad sin verificar).
  `LLAMA_SERVER_MODEL`/`PORT` sin validación de caracteres antes de interpolarse a shell.
- LAN: `LLAMA_SERVER_LAN=1` → `0.0.0.0` (`:241-243`); sin API key queda sin autenticar.
- La verificación post-instalación es débil: `timeout 5 llama-server --version || warn` (`:166`);
  si falla, solo avisa y sigue, y luego registra la versión como `unknown` (`:344-346`). No usa
  `verify_binary_installed()`. El chequeo de "ya instalado" (`:118`) usa solo `[ -x ]`.
- El encabezado del script dice "VERSIÓN 1.0.0 | 2026-08-05" (`:33`) pese a que el script creció
  (API key, LAN, parallel, embeddings…): versión del header desactualizada.
- `LlamaServerConfigFragment` mezcla opciones del servidor HTTP y del motor embebido; el usuario
  puede confundirse porque los sliders no afectan al `llama-server` (UX, sin verificar).
- `ModuleController` no usa `isRunning` por `/health`: "activo" de la lista = sesión tmux viva
  (sin verificar `isRunning` completo).

## 16. Fecha de alta

**2026-08-05** (primer commit de `modulos/llamaserver.sh`).
