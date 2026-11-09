# Verificación (`verificar`) — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (pestaña Módulos, `VerificarFragment`).
Es una herramienta de **diagnóstico transversal**: no instala/gestiona un servicio propio, sino
que audita en vivo si los OTROS módulos que el registry dice `installed=true` siguen teniendo su
binario/carpeta/paquete real en el filesystem. No reemplaza el flujo de instalar/actualizar de
cada módulo individual — lo complementa.

---

**Proyecto:** Kairos
**Script:** `modulos/verificar.sh` (v1.0.0) — espejo en `app/src/main/assets/scripts/verificar.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/VerificarFragment.kt`
**`id` en `modules.json`:** `verificar` — `hasSwitch: false`, `category: "system"`,
`type: "Diagnóstico"`, `requiresProot: false`, `estimate: "<10 s"`, `terminalCommand: "verificar"`

---

## 1. Descripción General

`verificar.sh` lee el registry (`~/.android_server_registry`) para saber qué módulos declaran
`installed=true`, y verifica **cada uno en vivo** contra el filesystem real usando la estrategia
que le corresponde según cómo ese módulo instala su binario. El objetivo es detectar el caso
"instalado en el papel pero roto en la práctica" — por ejemplo, el usuario borró una carpeta a
mano, o un `pkg remove` rompió una instalación — algo que el registry por sí solo no puede
detectar, porque solo refleja lo que el instalador escribió al terminar, no la verdad actual del
filesystem.

Es un port del comando `list` de un proyecto externo de gestión de Termux — reutiliza el mismo
concepto de "6 estrategias de verificación" (`_check_cmd`/`_check_pkg`/`_check_dir`/
`_check_file`/`_check_plugin`/`_check_grep_config` en el original) adaptado al registry y al
catálogo de módulos propios de Kairos.

**No modifica nada**: solo lee el registry y el filesystem.

## 2. Permisos

- **Android**: ninguno específico — corre en el mismo proceso Termux del wizard, sin permisos
  adicionales.
- **Termux interno**: solo lectura de `~/.android_server_registry` y comandos de solo-consulta
  (`command -v`, `dpkg -s`, `test -d`/`-f`, `grep`) — no instala paquetes, no descarga nada, no
  tiene red.

## 3. Las 6 estrategias de verificación

Cada estrategia se implementa como una función de una línea que devuelve `0` (verificado OK) o
`1` (falla), sin salida propia — el llamador (`verify_one`) decide qué imprimir:

| Estrategia | Función | Comando real | Qué confirma |
|---|---|---|---|
| `cmd` | `_ver_cmd()` | `command -v "$1"` | El binario está en el `PATH` (CLIs instalados vía npm/curl/instaladores propios) |
| `pkg` | `_ver_pkg()` | `dpkg -s "$1" \| grep -q "Status: install ok installed"` | El paquete `pkg`/`apt` de Termux sigue registrado como instalado |
| `dir` | `_ver_dir()` | `test -d "$1"` | Existe una carpeta (rootfs de proot-distro, configs, plugins) |
| `file` | `_ver_file()` | `test -f "$1"` | Existe un archivo (wrappers, keystores, tokens) |
| `plugin` | `_ver_plugin()` | `test -d "$1"` | Existe la carpeta de un plugin zsh (`~/.zsh-plugins/<name>`) — misma lógica que `dir`, nombre propio para legibilidad |
| `config` | `_ver_config()` | `[ -f "$2" ] && grep -qF "$1" "$2"` | Un archivo de config existe Y contiene una línea marcadora concreta (`$1`=patrón, `$2`=archivo) |

## 4. Tabla módulo → estrategia (`MOD_STRAT`)

`verificar.sh` trae hardcodeado un array asociativo Bash (`declare -A MOD_STRAT`) — formato
`"id|estrategia|blanco"` (para `config` el blanco es `"patrón|archivo"`):

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
| `codegraph` | cmd | `codegraph` |
| `ohmypi` | cmd | `omp` |
| `pi` | cmd | `pi` |
| `mysql` | cmd | `mariadbd` |
| `postgres` | cmd | `postgres` |
| `sqlite` | cmd | `sqlite3` |
| `redis` | cmd | `redis-server` |
| `kairos` | dir | `$HOME/kairos` |

`mysql`/`postgres`/`sqlite`/`redis` son sub-servicios del módulo `db`, cada uno con su propia clave
`<id>.installed=true` en el registry.

Ninguna entrada usa hoy las estrategias `pkg`/`file`/`plugin`/`config` — están implementadas
y disponibles, pero el catálogo actual de módulos de Kairos se verifica en su totalidad con `cmd`
(binario en PATH) salvo `kairos` (carpeta, `dir`). Un módulo del catálogo real de `modules.json`
que NO aparece en esta tabla cae en `[SKIP]` — ver §5.

## 5. Lógica de `verify_one()` — 3 resultados posibles

Por cada id a verificar (`verify_one "$id"`):

1. Si `$id` no tiene entrada en `MOD_STRAT` → **SKIP** (return 2). No se puede verificar en vivo
   porque no hay una estrategia definida para ese módulo — no implica que esté mal instalado, solo
   que `verificar.sh` no sabe cómo comprobarlo todavía.
2. Si tiene entrada, corre la función `_ver_<estrategia>` correspondiente contra el blanco:
   - Devuelve `0` → **OK** (return 0): el registry decía instalado y el filesystem lo confirma.
   - Devuelve `1` → **WARN** (return 1): el registry dice `installed=true` pero la verificación
     en vivo falla — instalación rota/borrada a mano/`pkg remove` parcial.

El resultado de cada módulo se imprime con un formato distinto según el modo activo (texto plano
`[OK]`/`[WARN]`/`[SKIP]` en `--silent`, colores `log`/`warn` de `lib.sh` en modo interactivo, o se
acumula como objeto para el array `JSON_MODULES` en `--json`).

## 6. Flags y modos de uso

| Flag | Efecto |
|---|---|
| `--silent` | Modo app: sin banner ni colores, líneas `[OK]`/`[WARN]`/`[SKIP]` + resumen `[STEP] RESUMEN: ...` |
| `--force` | Parseado pero no usado en la lógica actual del script (reservado — el script no reinstala nada) |
| `--describe` | Imprime el manifiesto JSON de una línea y sale: `{"id":"verificar","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}` |
| `--all` | Verifica todos los módulos con `installed=true` en el registry (fallback: todos los ids conocidos de `MOD_STRAT` si el registry está vacío) |
| `--json` | Un único objeto JSON en stdout, sin texto libre mezclado — implica `--silent` automáticamente |
| `-h` / `--help` | Parseado (`HELP=true`) pero el script no imprime ayuda ni sale — flag sin efecto real hoy |
| `<id1> <id2> ...` (posicionales) | Verifica **solo** esos ids, ignorando el registry (uso manual: `bash verificar.sh ollama n8n`) |
| *(sin flags/args)* | Default: todos los módulos con `installed=true` en el registry; si el registry está vacío, todos los ids conocidos de `MOD_STRAT` |

Los posicionales tienen prioridad sobre `--all` solo si `--all` no está presente (`if [ ${#ARGS[@]} -gt 0 ] && ! $ALL`); si se pasan ambos, `--all` gana.

## 7. Salida — 3 formatos

**Texto interactivo** (sin `--silent` ni `--json`): banner ASCII con emoji 🔍, luego una línea
por módulo coloreada (`log`/`warn` de `lib.sh`), resumen final con separador `═══` y colores
verde/amarillo/dim para OK/WARN/SKIP.

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
`status` ∈ `"ok" | "warn" | "skip"`. Para `"skip"`, `strategy`/`target` quedan como cadenas
vacías. Los valores de string se escapan con `_json_escape()` (backslash y comillas dobles) antes
de insertarse en el JSON armado a mano con `printf`/concatenación de strings — no hay librería
JSON, es construcción manual.

Si no hay nada para verificar (registry vacío y sin argumentos), sale temprano imprimiendo el
JSON/texto vacío correspondiente sin recorrer nada.

## 8. Exit code

- `0` si `$WARN -eq 0` (todo lo verificado está OK o es SKIP).
- `1` si `$WARN -gt 0` (al menos un módulo declarado instalado falló la verificación en vivo).

Esto permite usar `verificar.sh --all --json` en un pipeline y chequear el exit code como señal
rápida de "¿hay algo roto?", sin tener que parsear el JSON.

## 9. Checkpoint y `lib.sh`

Sourcea `lib.sh` (mismo directorio) para las funciones compartidas `log`/`warn`/`error`/`info`/
`step` y los colores (`CYAN`/`BOLD`/`GREEN`/`YELLOW`/`DIM`/`NC`). Define un checkpoint propio en
`~/.install_verificar_checkpoint` con `mark_done()`/`check_done()` (patrón estándar de
`modulos/*.sh`), pero **ninguna parte del script actual los invoca** — quedaron declarados por
convención del template pero sin uso real en la lógica de verificación (que no tiene
pasos secuenciales que necesiten resumirse tras una interrupción).

## 10. Pantalla real de la app (`VerificarFragment.kt`)

`getModuleId()` → `"verificar"`, `getModuleName()` → `"Verificación"`. Extiende
`BaseModuleFragment` (Fragment dedicado, enrutado desde `ModuleDetailNavigator.kt:46`).

Tres cards:

**DIAGNÓSTICO EN VIVO** — texto explicativo + botón "🔍 Verificar todos los módulos". Al tocarlo
(`runVerification()`), muestra un `ProgressDialogController` ("Verificando módulos… corriendo las
6 estrategias en vivo") y corre en un `Thread` separado:
```
bash <TERMUX_HOME>/scripts/install/verificar.sh --all --json
```
vía `ManagerNativeUtils.runShell(...)` con `timeoutSeconds = 40`. El exit code del script **no**
se usa para decidir éxito/fracaso de la corrida (puede ser `1` intencionalmente si hay WARNs) —
solo se chequea que `stdout` no esté vacío y sea JSON parseable (`JSONObject(stdout.trim())`); si
falla el parseo o stdout viene vacío, se trata como error de la corrida (`outcome == null`).

**RESULTADOS** — lista de filas, una por módulo, generada por `renderResults()`. Orden fijo:
primero los `warn` (lo más útil de ver arriba), después `skip`, `ok` al final
(`statusOrder(status)`: warn=0, skip=1, ok=2). Cada fila (`buildResultRow`) muestra:

| Status | Icono | Texto |
|---|---|---|
| `ok` | ✅ | "Instalado y verificado" (verde `kairos_green`) |
| `warn` | ⚠️ | "Registry dice instalado pero el binario real no está" (ámbar `kairos_amber`) |
| `skip` (o cualquier otro) | ○ | "Instalado (sin estrategia de verificación definida todavía)" (gris `kairos_text3`) |

`skip` solo ocurre cuando el registry ya confirma `installed=true` pero no hay estrategia
definida, así que el módulo sí está instalado. No hay acción de reparar desde un WARN.

Si `strategy` no está vacío, se agrega una línea monoespaciada `estrategia: <strategy> → <target>`
debajo (ej. `estrategia: cmd → ollama`).

Si `modules` viene vacío: mensaje distinto según si la corrida falló del todo
("Falló la verificación — revisá que \"verificar\" esté instalado.") o si el registry estaba
vacío ("Nada para verificar (registry vacío).").

**MANTENIMIENTO** — botones estándar de `BaseModuleFragment`:
- "🔄 Actualizar" → `updateModuleService`, toast de éxito/fallo apuntando a
  `~/kairos_logs/install_verificar.log`.
- "🗑 Desinstalar" → `ModuleController.uninstallModule("verificar")`, con `popBackStack()` si
  tiene éxito.

Si el módulo no está instalado (`!isModuleInstalled()`), se muestra el estado "no instalado"
estándar con botón de instalar (`installModuleInBackground`), como cualquier otro módulo.

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

`hasSwitch: false` porque no hay proceso persistente que arrancar/detener — el módulo solo
"corre" cuando se toca el botón de verificar, no vive de fondo. `requiresProot: false` porque el
script solo hace comprobaciones en el entorno Termux nativo (aunque algunos de los módulos que
verifica sí usen proot, `verificar.sh` en sí no entra ahí).

`--describe-files` declara solo el script y la dependencia `jq`. No hay descargas ni sha256.

## 12. Limitaciones conocidas

- **`MOD_STRAT` es una tabla estática, no derivada del catálogo real** (`modules.json`) — un
  módulo nuevo agregado a `modules.json` sin actualizar también `MOD_STRAT` en `verificar.sh`
  queda en `[SKIP]` (no es un bug visible, pero reduce la cobertura del diagnóstico).
  Mantener ambas listas en sync es manual.
- **La estrategia `cmd` solo hace `command -v`** (`_ver_cmd`): no ejecuta el binario (a diferencia
  de `verify_binary_installed`), así que un wrapper roto pasa como OK.
- **`remote` y `ssh` verifican `cloudflared`, no `sshd`** (`MOD_STRAT[remote]="cmd|cloudflared"`):
  como en `ssh.sh` la descarga de cloudflared es opcional (solo `warn` si falla), un Remote con SSH
  sano y sin cloudflared daría un WARN falso.
- **`--force` y `-h`/`--help` están parseados pero no hacen nada** — se leen como flags válidos
  (no caen en el `*` catch-all de args posicionales) pero ninguna rama del script los consulta
  después de parsearlos.
- **El checkpoint (`~/.install_verificar_checkpoint`) y sus funciones `mark_done`/`check_done`
  están definidos pero nunca invocados** — vestigio del template estándar de `modulos/*.sh`, sin
  impacto funcional.
- **El script nunca escribe en el registry** — el comentario de cabecera menciona que el modo
  `--all` puede "re-endosar" `installed=true`, pero no hay ningún `registry_write` ni edición del
  registry en el script: es una discrepancia entre el comentario de cabecera y el comportamiento
  real.
- **Falsos WARN posibles con la estrategia `cmd`** — si un módulo instala su binario fuera del
  `PATH` que ve `verificar.sh` (por ejemplo un venv de Python no activado), la verificación puede
  reportar WARN aunque el módulo funcione bien desde su propio flujo — la estrategia `cmd` no
  conoce contexto de entorno virtual/activación, solo el `PATH` del proceso que corre el script.
- `collect_installed` toma el prefijo de cada línea `installed=true`: ids como `ssh`, `mysql`,
  `postgres` se reportan por separado; los ids sin entrada en `MOD_STRAT` caen en SKIP.

**Alta.** `modulos/verificar.sh` agregado el **2026-08-14**.
