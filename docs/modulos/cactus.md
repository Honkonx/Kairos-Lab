# Cactus Needle

**Módulo de Kairos** — gestionado vía la UI de Kairos (`CactusFragment.kt`). Instalación,
estado y uso los maneja la app vía `ProcessBuilder` → `modulos/cactus.sh`.

---

**Script:** `modulos/cactus.sh` — copia sincronizada en
`app/src/main/assets/scripts/cactus.sh`.
**Registry:** prefijo `cactus.*`
**CLI resultante:** `cactus`

---

## 1. Descripción general

Cactus Needle es un motor de **tool-calling local** — un modelo chico (45M parámetros, ~28MB de
RAM) que traduce un pedido en lenguaje natural a una llamada de función de un catálogo declarado
(bash/python/lectura-escritura de archivos/memoria Engram), sin necesitar una IA grande para
decidir *qué* ejecutar. Tiene 2 modos:

- **`cactus run "pedido"`** — needle decide la tool call directo y la ejecuta, sin razonador de
  IA. Rápido, determinista, corre en el dispositivo sin red.
- **`cactus ai "pedido"`** — un razonador (Ollama `:11434` o llama-server `:8085`, vía endpoint
  OpenAI-compatible) interpreta primero el pedido a una instrucción corta, y luego needle la
  traduce a la tool call. Si no hay razonador disponible, cae automáticamente al modo directo.
- **`cactus extract <schema> "texto"`** — extracción estructurada (needle trata el schema como
  una única tool declarada; sus "arguments" son los campos extraídos).
- **`cactus schemas`** / **`cactus tools`** / **`cactus status`** — introspección.

**Catálogo de tools real** (`~/scripts/cactus/cactus_engine.py`): `run_bash`, `run_python`,
`read_file`, `write_file`, `list_dir`, `system_info`, `engram_remember`, `engram_recall` (estas 2
últimas delegan al módulo Engram si está instalado).

## 2. Arquitectura — jaxlib y Bionic libc

`cactus-needle` (el paquete PyPI) no es Python puro: depende de `jax`+`jaxlib`+`flax`+`optax`.
`jaxlib` es una librería compilada (XLA en C++) que en PyPI solo publica wheels con tag
`manylinux_*_aarch64` (glibc) — Termux corre sobre **Bionic libc** (Android), que esos wheels no
satisfacen. `pip install cactus-needle` nativo en Termux siempre falla en el paso de resolver
`jaxlib` (termina en `ResolutionImpossible`, no en un simple "no matching distribution").

**El script se auto-repara**: si el `pip` nativo falla, `cactus.sh` intenta automáticamente un
fallback a **proot-distro con una distro glibc real** (`ubuntu` por defecto) — instala
`proot-distro` y la distro si hacen falta, e instala `cactus-needle` dentro de ese contenedor
glibc, donde los wheels de `jaxlib` sí instalan. El runtime elegido (`pip` nativo vs `proot`) se
persiste en el registry (`cactus.runtime`) para no reintentar el camino que ya se sabe que falla
en una corrida futura. El wrapper `cactus` en `$PREFIX/bin` delega al intérprete correcto según
ese runtime (Python nativo o `proot-distro login ubuntu -- python3 ...`).

## 3. Cómo se instala (pasos reales de `cactus.sh`)

1. **Python 3** (si falta) — `pkg install python`.
2. **`python-numpy` nativo** (vía `pkg`, no pip) — cactus-needle depende de numpy; en Termux con
   Python muy reciente, pip suele no tener wheel publicado todavía y falla compilando desde
   source. Instalar el paquete nativo de Termux evita ese problema.
3. **`cactus-needle` vía pip** (`python3 -m pip install --break-system-packages cactus-needle`)
   — si `import needle` falla después, dispara el fallback automático a proot-distro/glibc
   descrito arriba.
4. **Motor + wrapper**: escribe `~/scripts/cactus/cactus_engine.py` (el motor real, catálogo de
   tools + lógica de extracción) y el wrapper ejecutable `cactus` en `$PREFIX/bin`.

Soporta `--silent`/`--force`/`--describe` (contrato estándar de `modulos/*.sh`).

## 4. UI en Kairos (`CactusFragment.kt`)

Pantalla propia dedicada (no cae en un Fragment genérico), con 6 cards reales.

### Controles de la pantalla

| Control | Qué hace | Por qué |
|---|---|---|
| Card ESTADO — filas Ollama (:11434)/llama-server (:8085) | Solo lectura, `checkPort()` cada carga | Backend del razonador de `cactus ai` — si ninguno responde, la sección "Con IA" se deshabilita |
| Card "EJECUTAR SIN IA" — campo + "▶ Ejecutar sin IA" | `cactus run --json-only "<pedido>"` — needle decide la tool directo, sin razonador | — |
| Card "EJECUTAR CON IA" — campo + "🧠 Ejecutar con IA" | `cactus ai --json-only "<pedido>"` — el razonador (Ollama/llama-server) interpreta primero, needle traduce y ejecuta | Deshabilitado (campo + botón atenuados, nota visible) si no hay backend de razonador disponible |
| Card "EXTRAER DATOS" — botón "📋 Elegir esquema (por categoría)" + campo + "🔎 Extraer" | `cactus extract <schema> "<texto>" --json-only` — extracción estructurada | 8 esquemas reales, agrupados por categoría en un diálogo |
| Card TAREAS — "☰ Plantillas" / "＋ Nueva tarea" / ▶ y 🗑 por fila | Tareas persistidas en `~/.cactus_tasks.json`, ejecución manual bajo demanda | MVP explícito: sin scheduler real, por diseño |
| "☰ Plantillas" | 5 plantillas fijas atadas al catálogo real de `cactus_engine.py` (system_info, list_dir, engram_recall, engram_remember, read_file) — pre-cargan "Nueva tarea", editable antes de guardar | El usuario no tiene que escribir el pedido desde cero cada vez |
| Card "CATÁLOGO DE TOOLS" — "🧰 Ver catálogo" | `cactus tools` — lista las funciones que needle puede ejecutar | — |
| Card SCRIPTS — "⬇ Exportar scripts" / "⬆ Importar scripts" | Exporta/importa `cactus_engine.py` + `.cactus_tasks.json` como JSON a `Download/KairosCactus/` | Respaldar o migrar entre instalaciones |
| "💻 Abrir en terminal" | `launchTerminalCommand("cactus status")` | Vía de escape a terminal real para lo que la UI no cubre |

## 5. Plantillas de extracción (8 esquemas)

`EXTRACT_SCHEMAS` en `cactus_engine.py` (embebido dentro de `modulos/cactus.sh`) — needle no
tiene una lista cerrada de schemas soportados por el motor real: cualquier schema JSON válido
funciona igual, así que estos son casos de uso curados por Kairos, no capacidades nuevas del
motor.

| Categoría | Esquema (`id`) | Campos |
|---|---|---|
| Documentos comerciales | `invoice` | vendor, total, due_date |
| Documentos comerciales | `receipt` | merchant, total, currency, line_items |
| Documentos comerciales | `purchase_order` | vendor, po_number, items, total |
| Documentos comerciales | `quote` | client, items, total, valid_until |
| Identificación y contacto | `business_card` | name, company, title, email, phone |
| Identificación y contacto | `contact` | name, email, phone, company |
| Productividad | `meeting_notes` | topic, date, attendees, action_items |
| Productividad | `event` | title, date, location, organizer |

`CactusFragment.kt` (`showExtractSchemaDialog()`) mantiene un catálogo paralelo en Kotlin
(`EXTRACT_SCHEMA_CATALOG`) — no hay forma de leer el diccionario Python real desde Kotlin en
build-time, así que si se agrega un schema nuevo a `cactus_engine.py`, hay que agregarlo también
ahí a mano.

## 6. Sin scheduler por diseño

Ni el motor real de needle ni la card "TAREAS" de Kairos tienen scheduler. El catálogo real de
tools de needle (`run_bash`,`run_python`,`read_file`,`write_file`,`list_dir`,`system_info`,
`engram_remember`,`engram_recall`) no incluye ninguna tool de timer/cron — las tareas guardadas
en la card "TAREAS" se ejecutan a demanda (botón ▶), nunca solas.

Si se necesita ejecución programada real, el camino correcto no es agregarle scheduling a Cactus
— es usar el mecanismo de cron real de Linux/Termux por fuera (`pkg install cronie` + `crond`, o
`termux-job-scheduler` del paquete `termux-api` para tareas ligadas al ciclo de vida de Android)
para disparar un script que a su vez llame `cactus run`/`cactus extract` o directamente el
comando del módulo que se quiera correr.

## 7. Servidor HTTP opt-in (`cactus serve`) — orquestación con n8n

Cactus era CLI puro sin ninguna API — n8n no tenía forma de dispararlo. `cactus serve [--port
8977]` (comando en `cactus_engine.py`) expone un servidor HTTP muy liviano usando únicamente
`http.server`/`BaseHTTPRequestHandler` de la stdlib de Python (sin dependencias nuevas) — el
motor real (`needle`) no cambia, es solo una capa de transporte sobre la misma lógica que ya usa
el CLI.

**Apagado por defecto (opt-in)** — el usuario lo activa desde el switch "SERVIDOR HTTP" en
`CactusFragment.kt` (mismo mecanismo que otros módulos de servidor: start/stop vía tmux +
`~/scripts/cactus/start.sh`/`stop.sh`). `modules.json` mantiene `hasSwitch: false` para Cactus a
propósito — la función *principal* del módulo sigue siendo CLI/tool-calling bajo demanda; el
switch del servidor HTTP vive solo dentro de la pantalla propia de Cactus.

**Endpoint único**: `POST http://127.0.0.1:<puerto>/run` (puerto default `8977`). Solo escucha en
`127.0.0.1` (no LAN) — alcanzable desde n8n igual, porque n8n comparte el namespace de red del
host.

**Body JSON esperado** — mismos 3 modos que ya soporta el CLI:
```json
{"mode": "directo", "query": "lista los archivos de ~/proyectos"}
{"mode": "ia", "query": "...", "model": "qwen2.5:1.5b (opcional)"}
{"mode": "extract", "schema": "invoice", "text": "..."}
```
Respuesta: el mismo JSON que ya devuelve `cactus run/ai/extract --json-only` por CLI.

**Auth**: header `X-Cactus-Token: <token>`, obligatorio en toda request. El token se genera una
sola vez (`secrets.token_hex(24)`) y se persiste en `~/.cactus_http_token`. Comparación con
`hmac.compare_digest` (no `==`), como protección anti-timing-attack. El botón "🔑 Ver token de
acceso" en `CactusFragment.kt` lo muestra tal cual está en disco (no lo genera — se genera la
primera vez que corre `cactus serve`).

**Ejemplo de nodo n8n**:
```
POST http://127.0.0.1:8977/run
Header: X-Cactus-Token: <valor de ~/.cactus_http_token>
Body:   {"mode": "directo", "query": "{{$json.pedido}}"}
```
