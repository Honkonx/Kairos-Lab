# Agente Kairos (`kairos_agent`) — Complemento de Kairos

> Ficha basada en la lectura del código. Convención: **Confirmado** = verificado leyendo el archivo citado; **Sin verificar** = inferencia no comprobada en dispositivo.

**Script:** `modulos/kairos_agent.sh` (860 líneas, idéntico byte a byte a `app/src/main/assets/scripts/kairos_agent.sh`)
**Fragment:** ninguno propio — usa `GenericModuleFragment` (`ModuleDetailNavigator.kt:65`, rama `else`)
**Alta:** 2026-09-16 (`catalogVersion` 1.0.0)

## 1. Qué es y para qué sirve

Motor Python 100% stdlib (embebido como heredoc en el `.sh`, `kairos_agent.sh:133-821`) que se
instala en `~/.kairos_agent/` y se invoca con el wrapper `kairos-agent`. Mantiene una base
SQLite (`modules`, `state`, `history`; `:324-345`) con el catálogo de módulos de Kairos, su
estado observado y el historial de acciones, y expone un **catálogo fijo de intents**
(`INTENTS`, `:637-652`): `refrescar`, `procesos`, `estado`, `instalar`, `iniciar`, `detener`,
`historial`, más los subcomandos CLI `tools` e `interpretar`. Opcionalmente un LLM local
(llama-server :8085 / Ollama :11434 / `KAIROS_AGENT_LLM_URL`) puede *elegir una intent* a partir
de lenguaje natural (`interpret_with_ai`, `:688-729`).

Es una CLI de orquestación (JSON por stdout), no un servicio ni un chat.

## 2. Clasificación

**COMPLEMENTO/EXTENSIÓN (con CLI propia).** Justificación (confirmado): no hay Fragment dedicado
(`ModuleDetailNavigator.kt` no lo lista; la única mención en `app/src/main/java` es un comentario
en `KairosBootstrap.kt:61`), no tiene `hasSwitch` (`modules.json:1771`), ni puerto, ni proceso
residente, ni entrada en `ModuleController.kt`. La UI es la genérica: ~5 controles (ver §5). Toda
la funcionalidad real vive en la CLI.

## 3. Instalación

- Flags: `--silent`, `--force`, `--describe` (`:49-63`). `--describe` devuelve
  `{"id":"kairos_agent","supports_silent":true,"supports_force":true,"variants":[]...}`.
  No implementa `--describe-files` (no hay soporte `moduledeb`).
- Dependencias: `python3` y `sqlite` (`modules.json:1781-1784`); el script las instala si faltan
  (`pkg install python` `:107`, `pkg install sqlite` `:122`). `installMethods: ["stdlib"]`.
- Pasos: 1 Python, 2 sqlite3 (verificado con `python3 -c "import sqlite3"` `:124`), 3 escribe
  el motor `~/.kairos_agent/kairos_agent_engine.py` (`:131-824`), 4 wrapper
  `$PREFIX/bin/kairos-agent` (`:828-834`) + primer `kairos-agent refrescar` best-effort (`:838`).
- Checkpoints: `~/.install_kairos_agent_checkpoint` (`kairos_agent_python`, `_sqlite`).
- "Ya instalado": `command -v kairos-agent` + existe el motor + sin `--force` -> `exit 0` (`:86`).
  `--force` solo borra el checkpoint (`:90`); no borra motor ni DB, el motor se reescribe
  igual (`cat >` `:133`).
- Verificación: sqlite3 sí se verifica; el wrapper/motor final no se ejecuta como
  post-condición (no usa `verify_binary_installed`) — el `refrescar` inicial solo emite
  `warn` si falla (`:838`), y aun así se escribe `installed=true`.
- Registry (`registry_install`, `:841-845`): `kairos_agent.installed=true`, `version=1.0.0`,
  `install_date`, `engine`, `db`, `intents`, `ai_hook`. Además `notify_event ... install_done`.

## 4. Ejecución

No hay start/stop: la CLI corre bajo demanda y termina (`main()` `:793-816`). Sin puerto
propio (`"port": ""`), sin sesión tmux propia, sin proceso residente.

El agente *controla* a otros módulos mediante `MODULE_CONTROL` (`:202-242`), copia manual de
las rutas de `ModuleController.kt` para 7 módulos (ollama, n8n, openclaw, opencode, remote,
llamaserver, db). Las rutas coinciden con las de `ModuleController.kt:1162-1171` para los que se
comprobaron (ollama/remote/llamaserver/db) — es una duplicación manual: si `ModuleController`
cambia, el agente queda desfasado sin aviso. Para cualquier otro módulo solo `instalar`/`estado`
(`:558-568`, `:575-579`).

Subprocesos usados: `bash <script>`, `tmux has-session/kill-session`, `pgrep -x`, `pkill -x`,
`ps -ef`/`ps aux`, `su -c id`/`su -c ps -ef` (solo `procesos` con root confirmado). Timeouts:
instalar 1800 s, iniciar 180 s, detener 60 s.

## 5. Funciones/opciones de la UI

Fragment: `GenericModuleFragment.kt` (fallback genérico). Controles que le tocan: tarjeta Estado
(id, versión del registry, tipo, ejecución nativa), pill de estado, botón **Abrir en terminal**
(ejecuta `kairos-agent` pelado, `terminalCommand` de `modules.json:1774`), **Gestionar
proyectos** (menú `ProjectActions` — poco pertinente aquí: el agente no tiene concepto de
proyectos), y tarjeta Mantenimiento: **Actualizar**, **Diagnosticar**, **Desinstalar**. ~5-6
controles, ninguno específico del agente.

`kairos-agent` sin argumentos imprime `{"ok": false, "error": "uso: ..."}` y
`sys.exit(1)` (`:794-797`), por lo que "Abrir en terminal" abre una sesión que muestra el uso y
sale; no es un flujo útil por sí mismo. Ninguna intent (instalar/iniciar/estado/historial) es
accesible desde botones.

Relacionado pero separado: `VoiceToAgentDialog.kt` (botón en `ModulesFragment.kt:143`) es el
"Voz -> Agente" que dicta un prompt a CLIs de IA (claude, codex, opencode, hermes...); su lista
`CLI_OPTIONS` no incluye `kairos_agent`. No son lo mismo que este complemento.

## 6. Archivos y rutas

- `~/.kairos_agent/kairos_agent_engine.py`, `~/.kairos_agent/kairos_agent.db`,
  `~/.kairos_agent/catalog.json` (resumen `source/count/generated_at`, `:462`).
- `$PREFIX/bin/kairos-agent` (wrapper bash -> `exec python3 engine`).
- `~/.install_kairos_agent_checkpoint` (temporal), registry `~/.android_server_registry`
  (prefijo `kairos_agent.`).
- Lee: `~/scripts/modules.json` (extraído por `KairosBootstrap.kt:66`, versionado por
  `isAlreadyExtracted` `:37-45`), `~/scripts/install/*.sh` (instaladores) y rutas de start/stop
  de otros módulos bajo `~/scripts/<modulo>/`.

## 7. Seguridad

¿Es cierto que "nunca ejecuta comandos libres"? **Sí, en el código revisado:**
- Ningún `shell=True`, `eval`, `exec` ni `os.system` en el motor; todos los subprocesos usan
  lista de argumentos (`_run(cmd)` -> `subprocess.run(list)`, `:248-250`).
- Los `id` de módulo se validan con `^[a-z0-9_]+$` (`:193`, `:258`) antes de formar la ruta
  `~/scripts/install/<id>.sh` (sin path traversal) y la variante con `^[a-z0-9_-]+$` (`:194`).
- El LLM solo puede devolver `{"intent","args"}` y se rechaza si el intent no está en `INTENTS`
  o args no es dict (`:723-726`); las intents mutantes (`instalar/iniciar/detener`) exigen
  `--auto` o devuelven `pending_confirmation` (`:756-762`).

Matices y riesgos:
1. "Sin comandos libres" no significa "sin ejecución de scripts": `instalar` ejecuta
   `bash ~/scripts/install/<id>.sh --silent` — ejecuta el contenido de ese archivo, que es
   confianza en el bootstrap de la app (no es input del LLM).
2. `_VARIANT_RE` admite un valor que empiece con `-` (p. ej. `--force` como "variante"): se pasa
   como argumento a un script propio, no a un shell; impacto bajo, pero la validación es laxa.
3. `--auto` en `interpretar` permite que el LLM dispare una instalación/arranque/detención de
   *cualquier* módulo conocido sin confirmación posterior; no hay allowlist por módulo ni
   confirmación en UI (porque no hay UI).
4. `KAIROS_AGENT_LLM_URL` es una URL arbitraria que recibe el pedido y el catálogo completo de
   intents; `urllib.request.urlopen` también acepta `file://` y otros esquemas (sin verificar en
   ejecución; la ausencia de filtro de esquema es un hecho del código). Sin autenticación hacia el LLM.
5. Robustez: `int(args.get("limit"...))` (`:622`) lanza `ValueError` no capturado con un límite
   no numérico (CLI o salida de LLM) — termina el proceso, no es una vulnerabilidad.
6. Secretos: el agente no guarda ni lee credenciales; `history.detail` guarda hasta 2000
   caracteres de salida de instaladores (`:355`) en la DB sin permisos restrictivos específicos
   (no hay `chmod`; el directorio hereda el umask de Termux, privado por el sandbox). La salida
   de un instalador podría contener tokens (sin verificar).
7. `procesos` con root ejecuta `su -c ps -ef` (comando fijo).

## 8. Problemas / gaps

Confirmados en código:
- Sin UI propia: ninguna intent es accesible desde botones; "Abrir en terminal" abre `kairos-agent` pelado, que solo imprime uso y sale con 1 (`:794-797`).
- Detección de "instalado" por UI: `kairos_agent` no está en `ModuleInstalled.BINARY_FALLBACK`/`LIVE_FALLBACK`; depende solo del registry (`kairos_agent.installed`); si el fallback en vivo se usara probaría `command -v kairos_agent` (guion bajo) mientras el binario real es `kairos-agent` (guion medio).
- Sin post-condición real del wrapper (ver §3); `--force` no limpia motor/DB.
- Sin `--describe-files` (no empaquetable con `moduledeb`).
- `MODULE_CONTROL` duplica `ModuleController.kt` a mano (§4); solo 7 módulos tienen start/stop.
- El fallback `_describe_installed_scripts` (`:375-408`) deja nombre/puerto/categoría vacíos (documentado en el propio código).
- Tras `refrescar`, `refresh_catalog` hace `DELETE FROM modules` y reinserta todo (`:420`); el estado `running` no es transaccional (gravedad baja, sin verificar).
- `history` crece indefinidamente (`log_history` nunca purga; declarado como "nunca se borra" `:151`).

Sin verificar en dispositivo: la ejecución de `instalar`, la interpretación con IA, ni el comportamiento de "Desinstalar" del fragment genérico frente a `~/.kairos_agent`/wrapper (el script no incluye lógica de desinstalación).

## 9. Fecha de alta

2026-09-16 (cabecera del script "Septiembre 2026").
