# Cactus Needle — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (`CactusFragment.kt`, 1063 líneas). Instalación, estado y uso los maneja la app vía `ProcessBuilder` → `modulos/cactus.sh`.

---

**Script:** `modulos/cactus.sh` (v1.5.1, 1358 líneas; incluye el `cactus_engine.py` embebido en un heredoc) — copia sincronizada en `app/src/main/assets/scripts/cactus.sh` (mismo SHA256).
**Registry:** prefijo `cactus.*`
**CLI resultante:** `cactus`
**`modules.json`:** `hasSwitch: false` a propósito (`:792`), `terminalCommand: "cactus"` (`:793`)

---

## 1. Qué es y para qué sirve

Cactus Needle es un motor de **tool-calling local** (`cactus-needle`, modelo Needle de 45M parámetros, ~28MB de RAM): convierte un pedido en lenguaje natural en una llamada a una tool de un catálogo fijo y la ejecuta, sin necesitar una IA grande para decidir *qué* ejecutar. Modos (parser de CLI en `cactus.sh:1159-1205`):

- **`cactus run "pedido"`** — needle decide la tool call directo y la ejecuta, SIN razonador de IA. Rápido, determinista, corre en el dispositivo sin red.
- **`cactus ai "pedido"`** — un razonador (Ollama `:11434` o llama-server `:8085`, vía endpoint OpenAI-compatible) interpreta primero el pedido a una instrucción corta, y luego needle la traduce a la tool call. Si no hay razonador disponible, cae automáticamente al modo directo.
- **`cactus extract <schema> "texto"`** — extracción estructurada (needle trata el schema como una única tool declarada; sus "arguments" son los campos extraídos). 8 schemas (ver §6).
- **`cactus serve`** — servidor HTTP opt-in (ver §8).
- **`cactus schemas`** / **`cactus tools`** / **`cactus status`** — introspección.

**Catálogo de tools real** (`~/scripts/cactus/cactus_engine.py`, `cactus.sh:1348`): `run_bash`, `run_python`, `read_file`, `write_file`, `list_dir`, `system_info`, `engram_remember`, `engram_recall` (estas 2 últimas delegan al módulo Engram si está instalado).

**Clasificación: MÓDULO FUNCIONAL.** Pantalla propia (`ModuleDetailNavigator.kt:42`, no cae en `GenericModuleFragment`), 4 pestañas, estado de backends, ejecución con/sin IA, extracción, tareas persistidas, servidor HTTP con switch y token. ~17 controles. Sin Actualizar/Desinstalar/Diagnosticar en esta pantalla (se desinstala desde Ajustes).

## 2. Arquitectura — por qué corre vía proot-distro/glibc

`cactus-needle` 2.x ya no depende de jax/jaxlib; la causa del fallo en Bionic es que Needle descarga su librería nativa desde HuggingFace al instanciar `Needle()` y su detección de plataforma no reconoce Android → baja un wheel manylinux/glibc, que Termux (Bionic libc) no satisface. Por eso `_needle_importa_ya()` **instancia** `Needle(...)` en vez de solo `import needle` (`cactus.sh:289-312`): la verificación de que "importa" no alcanza, hay que probar que realmente funciona.

**El script se auto-repara**: si el `pip` nativo falla, `cactus.sh` ejecuta automáticamente un fallback a **proot-distro con una distro glibc real** (`ubuntu` por defecto; `_install_cactus_glibc_fallback`, `:325-370`) — instala `proot-distro` y la distro si hacen falta, e instala `cactus-needle` DENTRO de ese contenedor glibc. El runtime elegido (`pip` nativo vs `proot`) se persiste en el registry (`cactus.runtime`) para no reintentar el camino que ya se sabe que falla. El wrapper `cactus` en `$PREFIX/bin` delega al intérprete correcto según ese runtime (Python nativo o `proot-distro login ubuntu -- python3 ...`; `:1235-1274`). Se confirmó en un dispositivo real `cactus.installed=true`, `cactus.runtime=proot`, `cactus.glibc_distro=ubuntu`. El camino pip nativo (sin proot) sigue sin funcionar en Bionic; todo depende de proot-distro (peso y tiempo de instalación grandes).

## 3. Instalación (`modulos/cactus.sh`)

- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (`:214-217`).
- Pasos (`:406-1334`): 1) Python (`pkg install python`) 2) `python-numpy` nativo vía `pkg` (no pip; en Termux con Python muy reciente pip suele no tener wheel y falla compilando desde source) 3) `python3 -m pip install --break-system-packages cactus-needle` + prueba real; si falla → fallback proot-distro/glibc (§2) 4) escribe `~/scripts/cactus/cactus_engine.py` + wrapper `$PREFIX/bin/cactus` 5) `start.sh`/`stop.sh` del servidor HTTP (`:1287-1331`) y registry.
- Verificación: post-condición real (`_needle_importa_ya`, `timeout 30`). Esa misma prueba se usa en el chequeo "ya instalado" (`:392`, `if command -v cactus … && _needle_importa_ya`) y puede requerir red (HuggingFace) para pasar (inferencia, no verificado).
- Registry (`registry_write cactus …`, `:1343-1355`): `installed`, `version`, `arch`, `engine`, `tools`, `modes`, `extract_schemas`, `http_server_port=8977`, `runtime` (pip|proot), `glibc_distro`, `install_date`. Detección "instalado" en la app: `command -v cactus` (`ModuleInstalled.kt:90,240`, `LiveCheck.PATH_BINARY`) — eso solo confirma que existe el wrapper, no que Needle funcione.

## 4. Ejecución

- CLI bajo demanda: la UI invoca `TERMUX_CACTUS_PATH` (`$PREFIX/bin/cactus`) por argv con `ManagerNativeUtils.runExec(..., 60 s)` (`CactusFragment.kt:272,281-283`), sin shell intermedio.
- Servidor HTTP opt-in: sesión tmux `cactus-server` (`ModuleController.kt:1204`), puerto 8977 (`:1246`), `start.sh` = `cactus serve --port ${CACTUS_SERVE_PORT:-8977}` con log `~/kairos_logs/cactus_serve.log` (`cactus.sh:1296-1313`), escucha **solo 127.0.0.1** (`:1132`). `stopAllModules` NO incluye `cactus` (`ModuleController.kt:322`), así que "detener todo" deja el servidor corriendo.

## 5. UI (`CactusFragment.kt`) — 4 pestañas

(`CactusFragment.kt:46-58,144-164`.)

| Control | Qué hace | Por qué |
|---|---|---|
| **Estado** — filas Ollama (:11434)/llama-server (:8085) | Solo lectura, `checkPort()` cada carga (`:173-213`) | Backend del razonador de `cactus ai` — si ninguno responde, la sección "Con IA" se deshabilita |
| **Ejecutar** — "▶ Ejecutar sin IA" (campo + botón) | `cactus run --json-only "<pedido>"` — needle decide la tool directo, sin razonador | Argv directo (`ManagerNativeUtils.runExec`, sin shell) |
| **Ejecutar** — "🧠 Ejecutar con IA" (campo + botón) | `cactus ai --json-only "<pedido>"` — el razonador interpreta primero, needle traduce y ejecuta | Deshabilitado (campo + botón atenuados, nota visible) si `mAiBackendAvailable == false` |
| **Ejecutar** — "📋 Elegir esquema (por categoría)" + campo + "🔎 Extraer" | `cactus extract <schema> "<texto>" --json-only` | 8 esquemas agrupados por categoría en un diálogo; `EXTRACT_SCHEMA_CATALOG` se mantiene a mano en paralelo al motor (`:357-368`) |
| **Tareas** — "☰ Plantillas" / "＋ Nueva tarea" / ▶ y 🗑 por fila | Tareas persistidas en `~/.cactus_tasks.json`, ejecución manual bajo demanda (**sin scheduler**) | MVP explícito |
| "☰ Plantillas" | 5 plantillas fijas atadas al catálogo real del motor (system_info, list_dir, engram_recall, engram_remember, read_file) — pre-cargan "Nueva tarea", editable antes de guardar | El usuario no tiene que escribir el pedido desde cero |
| **Avanzado** — "🧰 Ver catálogo" | `cactus tools` — lista las funciones que needle puede ejecutar | — |
| **Avanzado** — switch "SERVIDOR HTTP" | Inicia/detiene `cactus serve` vía tmux (`:836-850`) | Ver §8 |
| **Avanzado** — "🔑 Ver token de acceso" | Muestra el token tal cual está en disco (`:870-890`) | Ver §9 |
| **Avanzado** — "⬇ Exportar scripts" / "⬆ Importar scripts" | Exporta/importa `cactus_engine.py` + `.cactus_tasks.json` como JSON a `/storage/emulated/0/Download/KairosCactus/` (`:923-1031`) | Respaldar o migrar entre instalaciones |
| **Avanzado** — "💻 Abrir en terminal" | `launchTerminalCommand("cactus status")` | Vía de escape a terminal real |

## 6. Plantillas de extracción (8 esquemas)

`EXTRACT_SCHEMAS` en `cactus_engine.py` (embebido dentro de `modulos/cactus.sh`). Needle no tiene una lista cerrada de schemas soportados por el motor ("a byte-level grammar compiled from your schemas" — cualquier schema JSON válido funciona), así que los de esta tabla son casos de uso de Kairos, no capacidades fijas del motor.

| Categoría | Esquema (`id`) | Campos |
|---|---|---|
| Documentos comerciales | `invoice` | vendor, total, due_date *(ejemplo del README, "Quickstart")* |
| Documentos comerciales | `receipt` | merchant, total, currency, line_items *(ejemplo del README, "Extraction")* |
| Documentos comerciales | `purchase_order` | vendor, po_number, items, total |
| Documentos comerciales | `quote` | client, items, total, valid_until |
| Identificación y contacto | `business_card` | name, company, title, email, phone |
| Identificación y contacto | `contact` | name, email, phone, company |
| Productividad | `meeting_notes` | topic, date, attendees, action_items |
| Productividad | `event` | title, date, location, organizer |

`CactusFragment.kt` (`showExtractSchemaDialog()`) mantiene un catálogo paralelo en Kotlin (`EXTRACT_SCHEMA_CATALOG`) — no hay forma de leer el diccionario Python desde Kotlin en build-time, así que al agregar un schema nuevo a `cactus_engine.py` hay que agregarlo también ahí a mano (riesgo de desincronía).

## 7. Programación por horario — no, por diseño

Ni el motor de needle ni la card "TAREAS" de Kairos tienen scheduler: el catálogo de tools (`run_bash,run_python,read_file,write_file,list_dir,system_info,engram_remember,engram_recall`) no incluye ninguna tool de timer/cron, y las tareas guardadas se ejecutan a demanda (botón ▶), nunca solas.

Para algo tipo "a las 2pm ejecuta n8n", el camino correcto no es agregarle scheduling a Cactus sino usar el cron real de Linux/Termux por fuera (`pkg install cronie` + `crond`, o `termux-job-scheduler` del paquete `termux-api`) para disparar un script que llame `cactus run`/`cactus extract` o directamente el comando del módulo que se quiera correr. (Kairos también tiene su pantalla de Automatizaciones para disparar acciones sobre módulos por horario/arranque/batería; ver `AUTOMATIZACIONES.md`.)

## 8. Servidor HTTP opt-in (`cactus serve`)

`cactus serve [--port 8977]` (comando en `cactus_engine.py`) expone un servidor HTTP muy liviano usando únicamente `http.server`/`BaseHTTPRequestHandler` de la stdlib de Python (sin dependencias nuevas) — el motor real (`needle`) no cambia, es solo una capa de transporte sobre la misma lógica que usa el CLI (`_needle_run_core()`/`_extract_core()`, extraídas de `run_needle()`/`run_extract()` para reusarlas desde ambos lados sin duplicar código). Permite que n8n u otros clientes locales disparen Cactus.

**Apagado por defecto (opt-in)** — se activa desde el switch "SERVIDOR HTTP" en `CactusFragment.kt`, mismo mecanismo que `LlamaServerFragment` (`startModuleService`/`stopModuleService`/`isModuleRunning()` de `BaseModuleFragment`, que delegan en `ModuleController.kt` → tmux + `~/scripts/cactus/start.sh`/`stop.sh`, generados por `cactus.sh` PASO 5). `modules.json` mantiene `hasSwitch: false` para Cactus a propósito — el switch global de la lista de Módulos implica que la función *principal* del módulo es un servicio de fondo (caso de Ollama/n8n/llama-server), y la función principal de Cactus es CLI/tool-calling bajo demanda.

**Endpoint único**: `POST http://127.0.0.1:<puerto>/run` (puerto default `8977`, sin colisión con ollama 11434, n8n 5678, openclaw 18789, opencode 3000, remote 8022, llamaserver 8085, Nube 8971). Solo escucha en `127.0.0.1` (no LAN) — alcanzable desde n8n igual, porque n8n corre en udocker/PRoot y comparte el namespace de red del host.

**Body JSON esperado** — mismos 3 modos que el CLI:
```json
{"mode": "directo", "query": "lista los archivos de ~/proyectos"}
{"mode": "ia", "query": "...", "model": "qwen2.5:1.5b (opcional)"}
{"mode": "extract", "schema": "invoice", "text": "..."}
```
Respuesta: el mismo JSON que devuelve `cactus run/ai/extract --json-only` por CLI.

**Auth**: header `X-Cactus-Token: <token>`, obligatorio en toda request. El token se genera una sola vez (`secrets.token_hex(24)`) la primera vez que corre `cactus serve` y se persiste en `~/.cactus_http_token` (mismo patrón que `NubeServer.kt`/`.nube_token`). Comparación con `hmac.compare_digest` (`:1082-1083`), mismo criterio anti-timing-attack que `NubeServer.kt`. Sin token → 403.

**Ejemplo de nodo n8n** (`HTTP Request`):
```
POST http://127.0.0.1:8977/run
Header: X-Cactus-Token: <valor de ~/.cactus_http_token>
Body:   {"mode": "directo", "query": "{{$json.pedido}}"}
```

El flujo completo end-to-end (switch → tmux → `cactus serve` real → request HTTP desde n8n) no está verificado en dispositivo; el motor Python se verificó con `py_compile`.

## 9. Archivos y rutas que crea

`$PREFIX/bin/cactus`, `~/scripts/cactus/cactus_engine.py`, `~/scripts/cactus/tools.idx` (índice de embeddings de tools, junto al engine), `~/scripts/cactus/start.sh|stop.sh`, `~/.cactus_http_token`, `~/.cactus_tasks.json`, `~/kairos_logs/cactus_serve.log`, `~/.install_cactus_checkpoint` (se borra al final), registry, y —si runtime=proot— la distro `ubuntu` completa de proot-distro (cientos de MB) con `python3-pip` + `cactus-needle` dentro.

## 10. Seguridad

- **Riesgo funcional alto por diseño**: `--json-only` implica auto-ejecución sin confirmación (`cactus.sh:907`, `effective_auto = auto or json_only`), y la UI siempre usa `--json-only` (`CactusFragment.kt:281-283`). Un pedido tipo "borra…" lo interpreta un modelo de 45M parámetros y, si elige `run_bash`/`run_python`/`write_file`, se ejecuta **sin diálogo de confirmación** (`exec_tool`, `cactus.sh:805-`; `_run` = `bash -c`/`python3 -c`). El umbral de baja confianza (0.45) solo frena en modo CLI interactivo.
- Servidor HTTP: solo loopback; token de 24 bytes comparado con `hmac.compare_digest`. El endpoint ejecuta bash/python vía needle (`:1029-1031` lo reconoce en comentarios). El archivo del token se crea sin `chmod 600` explícito (`:1039-1043`) y `showServerTokenDialog` lo **muestra en claro** cuantas veces se pida (`CactusFragment.kt:870-890`), lo que contradice el principio de no volver a mostrar un secreto guardado; se mitiga con `filterTouchesWhenObscured` (`:109-114`) pero no con `FLAG_SECURE` (inferencia: no se vio `setScreenSecure` en este fragment).
- Cualquier proceso/app del mismo namespace de red local que conozca el token puede disparar ejecución.
- Export de scripts: escribe el engine y las tareas a `Download/` (público); si el usuario guardó datos sensibles en una tarea quedan en claro.
- Import: `importScripts` reescribe `cactus_engine.py` con el contenido de un JSON importado (`CactusFragment.kt:1005-1031`; no se revisó a fondo si valida origen) — un JSON malicioso reemplazaría el motor que luego se ejecuta (hay diálogo `confirmImportScripts`).

## 11. Limitaciones conocidas

| Ítem | Tipo |
|---|---|
| Ejecución sin confirmación desde la UI (modelo 45M elige `run_bash`) | Confirmado en código |
| `stopAllModules` no detiene `cactus-server` | Confirmado en código |
| Token HTTP visible en claro + archivo sin chmod 600 explícito | Confirmado en código |
| `EXTRACT_SCHEMA_CATALOG` (Kotlin) y `EXTRACT_SCHEMAS` (Python) se mantienen a mano en paralelo | Confirmado — riesgo de desincronía |
| "Instalado" = `command -v cactus` en la app, no valida Needle | Confirmado (código); impacto inferido |
| Camino pip nativo (sin proot) sigue sin funcionar en Bionic; todo depende de proot-distro | Según la cabecera del script; no re-probado |
| Sin Actualizar/Desinstalar/Diagnosticar en la pantalla | Confirmado en código |
| Flujo `serve` end-to-end no verificado en dispositivo | Pendiente |

## 12. Fecha de alta

**2026-08-13** (primer commit de `modulos/cactus.sh`).
