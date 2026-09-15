# Verificación (`verificar`)

**Módulo de Kairos** — gestionado desde la UI (pestaña Módulos, `VerificarFragment`). Es una herramienta de **diagnóstico transversal**: no instala/gestiona un servicio propio, sino que audita en vivo si los otros módulos que el registry dice `installed=true` siguen teniendo su binario/carpeta/paquete real en el filesystem. No reemplaza el flujo de instalar/actualizar de cada módulo individual — lo complementa.

---

**Script:** `modulos/verificar.sh` — espejo en `app/src/main/assets/scripts/verificar.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/VerificarFragment.kt`
**`id` en `modules.json`:** `verificar` — sin switch, categoría sistema, tipo diagnóstico, sin requerir proot, comando de terminal `verificar`

---

## 1. Descripción general

`verificar.sh` lee el registry (`~/.android_server_registry`) para saber qué módulos declaran `installed=true`, y verifica **cada uno en vivo** contra el filesystem real usando la estrategia que le corresponde según cómo ese módulo instala su binario. El objetivo es detectar el caso "instalado en el papel pero roto en la práctica" — por ejemplo, el usuario borró una carpeta a mano, o una desinstalación parcial de un paquete rompió una instalación — algo que el registry por sí solo no puede detectar, porque solo refleja lo que el instalador escribió al terminar, no la verdad actual del filesystem.

Reutiliza el concepto de "6 estrategias de verificación" (comando en PATH, paquete del sistema, directorio, archivo, plugin, patrón en config) adaptado al registry y al catálogo de módulos propios de Kairos.

**No modifica nada** en modo normal: solo lee el registry y el filesystem. La única escritura posible es en modo `--all` (ver §4), donde el comentario de cabecera del script menciona que puede "re-endosar" `installed=true` en el registry — ver la nota en §12 sobre esta discrepancia.

## 2. Permisos

- **Android**: ninguno específico — corre en el mismo proceso Termux, sin permisos adicionales.
- **Termux interno**: solo lectura de `~/.android_server_registry` y comandos de solo-consulta (`command -v`, `dpkg -s`, `test -d`/`-f`, `grep`) — no instala paquetes, no descarga nada, no tiene red.

## 3. Las 6 estrategias de verificación

Cada estrategia se implementa como una función de una línea que devuelve `0` (verificado OK) o `1` (falla), sin salida propia:

| Estrategia | Comando real | Qué confirma |
|---|---|---|
| `cmd` | `command -v "$1"` | El binario está en el `PATH` (CLIs instalados vía npm/curl/instaladores propios) |
| `pkg` | `dpkg -s "$1" \| grep -q "Status: install ok installed"` | El paquete `pkg`/`apt` de Termux sigue registrado como instalado |
| `dir` | `test -d "$1"` | Existe una carpeta (rootfs de proot-distro, configs, plugins) |
| `file` | `test -f "$1"` | Existe un archivo (wrappers, keystores, tokens) |
| `plugin` | `test -d "$1"` | Existe la carpeta de un plugin zsh — misma lógica que `dir`, nombre propio para legibilidad |
| `config` | `[ -f "$2" ] && grep -qF "$1" "$2"` | Un archivo de config existe Y contiene una línea marcadora concreta (`$1`=patrón, `$2`=archivo) |

## 4. Tabla módulo → estrategia (`MOD_STRAT`)

`verificar.sh` trae hardcodeado un array asociativo Bash con más de 30 entradas — formato `"id|estrategia|blanco"` (para `config` el blanco es `"patrón|archivo"`), verificado contra qué instala realmente cada `modulos/*.sh`:

| id | Estrategia | Blanco |
|---|---|---|
| `ollama` | cmd | `ollama` |
| `n8n` | cmd | `n8n` |
| `python` | cmd | `python3` |
| `claude` | cmd | `claude` |
| `codex` | cmd | `codex` |
| `antigravity` | cmd | `agy` |
| `openclaw` | cmd | `openclaw` |
| `opencode` | cmd | `opencode` |
| `hermes` | cmd | `hermes` |
| `remote` | cmd | `cloudflared` |
| `ssh` | cmd | `cloudflared` |
| `expo` | cmd | `expo` |
| `engram` | cmd | `engram` |
| `freebuff` | cmd | `freebuff` |
| `codebuff` | cmd | `codebuff` |
| `copilotcli` | cmd | `copilot` |
| `minimaxcli` | cmd | `mmx` |
| `mimocode` | cmd | `mimo` |
| `mistralvibe` | cmd | `vibe` |
| `qwencode` | cmd | `qwen` |
| `ciberseguridad` | cmd | `nmap` |
| `entorno` | cmd | `proot-distro` |
| `db` | cmd | `mariadbd` |
| `stacks` | cmd | `proot-distro` |
| `llamaserver` | cmd | `llama-server` |
| `cactus` | cmd | `cactus` |
| `ide` | cmd | `nvim` |
| `apk` | cmd | `compil-apk-termux` |
| `kimi` | cmd | `kimi` |
| `kilo` | cmd | `kilo` |
| `cursor` | cmd | `cursor-agent` |
| `hf` | cmd | `hf` |
| `kairos` | dir | `$HOME/kairos` |
| `codegraph` | cmd | `codegraph` |
| `ohmypi` | cmd | `omp` |
| `pi` | cmd | `pi` |
| `mysql` | cmd | `mariadbd` |
| `postgres` | cmd | `postgres` |
| `sqlite` | cmd | `sqlite3` |
| `redis` | cmd | `redis-server` |

Nota: ninguna entrada usa hoy las estrategias `pkg`/`file`/`plugin`/`config` — están implementadas y disponibles, pero el catálogo actual de módulos de Kairos se verifica en su totalidad con `cmd` (binario en PATH) salvo `kairos` (carpeta, `dir`). Un módulo del catálogo real de `modules.json` que no aparece en esta tabla cae en `[SKIP]` — ver §5.

## 5. Lógica de `verify_one()` — 3 resultados posibles

Por cada id a verificar:

1. Si `$id` no tiene entrada en `MOD_STRAT` → **SKIP**. No se puede verificar en vivo porque no hay una estrategia definida para ese módulo — no implica que esté mal instalado, solo que `verificar.sh` no sabe cómo comprobarlo todavía.
2. Si tiene entrada, corre la función correspondiente contra el blanco:
   - Devuelve `0` → **OK**: el registry decía instalado y el filesystem lo confirma.
   - Devuelve `1` → **WARN**: el registry dice `installed=true` pero la verificación en vivo falla — instalación rota, borrada a mano, o eliminación parcial de un paquete.

El resultado de cada módulo se imprime con un formato distinto según el modo activo (texto plano `[OK]`/`[WARN]`/`[SKIP]` en `--silent`, colores en modo interactivo, o se acumula como objeto para el array `JSON_MODULES` en `--json`).

## 6. Flags y modos de uso

| Flag | Efecto |
|---|---|
| `--silent` | Modo app: sin banner ni colores, líneas `[OK]`/`[WARN]`/`[SKIP]` + resumen `[STEP] RESUMEN: ...` |
| `--force` | Parseado pero no usado en la lógica actual del script (reservado — el script no reinstala nada) |
| `--describe` | Imprime el manifiesto JSON de una línea y sale |
| `--all` | Verifica todos los módulos con `installed=true` en el registry (fallback: todos los ids conocidos de `MOD_STRAT` si el registry está vacío) |
| `--json` | Un único objeto JSON en stdout, sin texto libre mezclado — implica `--silent` automáticamente |
| `-h` / `--help` | Parseado pero el script no imprime ayuda ni sale — flag sin efecto real hoy |
| `<id1> <id2> ...` (posicionales) | Verifica solo esos ids, ignorando el registry (uso manual: `bash verificar.sh ollama n8n`) |
| *(sin flags/args)* | Default: todos los módulos con `installed=true` en el registry; si el registry está vacío, todos los ids conocidos de `MOD_STRAT` |

Los posicionales tienen prioridad sobre `--all` solo si `--all` no está presente; si se pasan ambos, `--all` gana.

## 7. Salida — 3 formatos

**Texto interactivo** (sin `--silent` ni `--json`): banner ASCII, luego una línea por módulo coloreada, resumen final con separador y colores verde/amarillo/gris para OK/WARN/SKIP.

**`--silent`**: sin banner, una línea por módulo:
```
[OK]   ollama  (cmd: ollama)
[WARN] n8n  registry dice installed pero falla (cmd: n8n)
[SKIP] pi — sin estrategia definida
```
y resumen final `[STEP] RESUMEN: $OK ok, $WARN warn, $SKIP skip ($total total)`.

**`--json`**: un único objeto en stdout, formato
```json
{"total":N,"ok":N,"warn":N,"skip":N,
 "modules":[{"id":"ollama","status":"ok","strategy":"cmd","target":"ollama"}, ...]}
```
`status` ∈ `"ok" | "warn" | "skip"`. Para `"skip"`, `strategy`/`target` quedan como cadenas vacías. Los valores de string se escapan (backslash y comillas dobles) antes de insertarse en el JSON armado a mano con `printf`/concatenación de strings — no hay librería JSON, es construcción manual.

Si no hay nada para verificar (registry vacío y sin argumentos), sale temprano imprimiendo el JSON/texto vacío correspondiente sin recorrer nada.

## 8. Exit code

- `0` si `$WARN -eq 0` (todo lo verificado está OK o es SKIP).
- `1` si `$WARN -gt 0` (al menos un módulo declarado instalado falló la verificación en vivo).

Esto permite usar `verificar.sh --all --json` en un pipeline y chequear el exit code como señal rápida de "¿hay algo roto?", sin tener que parsear el JSON.

## 9. Checkpoint y librería compartida

Sourcea `lib.sh` (mismo directorio) para las funciones compartidas de logging y colores. Define un checkpoint propio, pero ninguna parte del script actual lo invoca — quedó declarado por convención del template pero sin uso real en la lógica de verificación (que no tiene pasos secuenciales que necesiten resumirse tras una interrupción).

## 10. Pantalla de la app (`VerificarFragment.kt`)

Extiende `BaseModuleFragment`. Tres cards:

**DIAGNÓSTICO EN VIVO** — texto explicativo + botón "Verificar todos los módulos". Al tocarlo, muestra un indicador de progreso ("Verificando módulos… corriendo las 6 estrategias en vivo") y corre en un hilo separado:
```
bash <TERMUX_HOME>/scripts/install/verificar.sh --all --json
```
con timeout de 40 segundos. El exit code del script no se usa para decidir éxito/fracaso de la corrida (puede ser `1` intencionalmente si hay WARNs) — solo se chequea que `stdout` no esté vacío y sea JSON parseable; si falla el parseo o stdout viene vacío, se trata como error de la corrida.

**RESULTADOS** — lista de filas, una por módulo. Orden fijo: primero los `warn` (lo más útil de ver arriba), después `skip`, `ok` al final. Cada fila muestra:

| Status | Texto |
|---|---|
| `ok` | "Instalado y verificado" |
| `warn` | "Registry dice instalado pero el binario real no está" |
| `skip` | "Instalado (sin estrategia de verificación definida todavía)" |

Si `strategy` no está vacío, se agrega una línea monoespaciada `estrategia: <strategy> → <target>` debajo.

Si `modules` viene vacío: mensaje distinto según si la corrida falló del todo o si el registry estaba vacío.

**MANTENIMIENTO** — botones estándar:
- "Actualizar" — reinstala/actualiza el módulo.
- "Desinstalar" — con retorno a la pantalla anterior si tiene éxito.

Si el módulo no está instalado, se muestra el estado "no instalado" estándar con botón de instalar, como cualquier otro módulo.

## 11. Entrada en `modules.json`

```json
{
  "id": "verificar",
  "name": "Verificación",
  "description": "Verifica en vivo que los módulos instalados sigan funcionando (6 estrategias: cmd, pkg, dir, file, plugin, config).",
  "repo": "Honkonx/kairos-lab",
  "script": "verificar.sh",
  "port": "",
  "size": "",
  "type": "Diagnóstico",
  "requiresProot": false,
  "estimate": "<10 s",
  "hasSwitch": false,
  "terminalCommand": "verificar",
  "arch": "bionic",
  "category": "system",
  "catalogVersion": "0.x",
  "installMethods": ["standard"],
  "requires": [],
  "recommended": false,
  "downloads": 0
}
```

`hasSwitch: false` porque no hay proceso persistente que arrancar/detener — el módulo solo "corre" cuando se toca el botón de verificar, no vive de fondo. `requiresProot: false` porque el script solo hace comprobaciones en el entorno Termux nativo (aunque algunos de los módulos que verifica sí usen proot, `verificar.sh` en sí no entra ahí).

## 12. Notas de arquitectura

- **`MOD_STRAT` es una tabla estática, no derivada del catálogo real** (`modules.json`) — un módulo nuevo agregado a `modules.json` sin actualizar también `MOD_STRAT` en `verificar.sh` queda en `[SKIP]` silencioso (no es un bug visible, pero reduce la cobertura del diagnóstico). Mantener ambas listas en sync es manual.
- **`--force` y `-h`/`--help` están parseados pero no hacen nada** — se leen como flags válidos pero ninguna rama del script los consulta después de parsearlos.
- **El checkpoint está definido pero nunca invocado** — vestigio del template estándar de `modulos/*.sh`, sin impacto funcional porque no hay pasos que necesiten resumirse.
- **El script nunca escribe en el registry en el modo usado por la app** (`--all --json`) — el comentario de cabecera menciona que el modo `--all` puede "re-endosar" `installed=true`, pero revisando la lógica real del script no hay ningún registro ni edición del registry en ninguna parte — es una discrepancia entre el comentario de cabecera y el comportamiento real, documentada acá para que no se asuma que existe esa escritura sin verificarla de nuevo si se vuelve a tocar el script.
- **Falsos WARN posibles con la estrategia `cmd`** — si un módulo instala su binario fuera del `PATH` que ve `verificar.sh` (por ejemplo un entorno virtual de Python no activado), la verificación puede reportar WARN aunque el módulo funcione bien desde su propio flujo — la estrategia `cmd` no conoce contexto de entorno virtual/activación, solo el `PATH` del proceso que corre el script.
