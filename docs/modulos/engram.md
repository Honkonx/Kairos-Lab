# Engram — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación y estado se manejan desde la app vía `ProcessBuilder` → `modulos/engram.sh` (192 líneas; el script embebido `app/src/main/assets/scripts/engram.sh` es idéntico).

---

**Proyecto:** Kairos
**Origen del código:** [github.com/Gentleman-Programming/engram](https://github.com/Gentleman-Programming/engram) (MIT), empaquetado originalmente por [core-termux](https://github.com/DevCoreXOfficial/core-termux) (MIT)
**Script:** `modulos/engram.sh` (v1.0.0)
**Fragment:** `EngramFragment.kt` (373 líneas; `ModuleDetailNavigator.kt:35`)
**`hasSwitch`:** `false` — sin ON/OFF, sin proceso persistente en segundo plano (`modules.json:330-352`)

---

## 1. Qué es

Engram le da memoria persistente entre sesiones a los agentes de IA que corren dentro de Kairos (Claude Code, OpenCode, etc.) — guarda contexto (observaciones, decisiones, notas por proyecto) en una base SQLite local, sin depender de ningún servicio externo ni de la nube.

A diferencia de n8n/OpenClaw/OpenCode, **no es un proceso servidor** — es un binario CLI (`engram`) compilado desde Go que corre bajo demanda: cuando se lo invoca (buscar, ver stats, exportar, o su TUI interactiva), no queda residente escuchando ningún puerto.

**Clasificación: MÓDULO FUNCIONAL** (CLI con UI propia, sin servicio): Fragment dedicado con ~13 botones que ejecutan operaciones reales del binario `engram` (`EngramFragment.kt:92-164`), más 2 pills (estado, terminal) y botón de cerrar terminal. No hay servicio: `hasSwitch=false`, sin puerto (`"port": ""`), sin entrada en `ModuleController.kt`, sin tmux propio.

## 2. Permisos

No requiere ningún permiso especial de Android — solo los genéricos que ya pide el wizard de Kairos (almacenamiento, para exportar memoria a `~/`). No usa red, no expone puertos.

## 3. Lógica de instalación (`modulos/engram.sh`)

Acepta `--silent` (siempre implícito al llamar desde la app), `--force` (reinstala aunque ya esté; borra checkpoint, `~/.engram-src` y el binario, `:112`, pero **no** los datos de memoria), `--describe` (manifiesto declarativo JSON) y `--describe-files` (`:65-84`, usa `jq`; `package_name kairos-module-engram`, `version_registry_key: engram.version`).

| Paso | Checkpoint | Qué hace |
|---|---|---|
| 1 | `engram_deps` | `pkg install -y golang git sqlite` — aborta con `error()` si `go`/`git`/`sqlite3` no quedan disponibles tras instalar (`engram.sh:132-138`) |
| 2 | `engram_clone` | `git clone --depth 1` (sin `--quiet`, captura la salida y muestra las últimas 3 líneas, `engram.sh:149-150`) de `Gentleman-Programming/engram` (`:88`) a `~/.engram-src` — aborta si no encuentra `cmd/engram/` tras clonar (detecta si el repo real cambió de estructura) |
| 3 | `engram_build` | `go build -C ~/.engram-src/cmd/engram -o $PREFIX/bin/engram` — verifica que el binario haya quedado ejecutable (`-x`), y además intenta `engram --version`/`engram --help` como verificación de que corre |

Al final: `registry_write engram "installed=true" "install_date=$(date +%Y-%m-%d)"` (`engram.sh:188`) sobre `~/.android_server_registry`, prefijo `engram.*`. **No se escribe `engram.version`** (usa `registry_write`, no `registry_install`), por lo que un `moduledeb pack engram` no tendría versión de registry que leer.

**Variables de entorno de compilación**: `GOPATH=$HOME/.local/go`, `GOCACHE=$HOME/.cache/go`, `GOMODCACHE=$GOPATH/pkg/mod` — todo bajo `$HOME`, nunca `/tmp` (Android 15 lo monta `noexec`).

**Detección "ya instalado"**: `command -v engram` (`:108`) — si existe y no se pasó `--force`, sale inmediato con `exit 0`.

**Dependencias**: `modules.json` declara `requires: ["go"]` (`:347-349`), aunque el script instala `golang git sqlite` él mismo. El campo `repo` del catálogo es `Honkonx/kairos-lab` (campo del catálogo/.deb), distinto del origen real del código.

## 4. Opciones/variantes

No tiene variantes (`"variants":[]` en el manifiesto `--describe`) — una sola forma de instalación, sin selección de modo/backend.

## 5. Detección de estado en la app

`engram` tiene `hasSwitch: false` en `modules.json` — **no está en los mapas de `ModuleController.kt`** (no tiene entrada en `getModuleStartScript()`/`getTmuxSession()`/`getProcessName()`). Esto significa:
- No hay concepto de "corriendo/detenido" — solo "instalado/no instalado", vía `BaseModuleFragment.isModuleInstalled()` leyendo `engram.installed=true` del registry; el catálogo/Monitor lo trata como CLI instalado (`ModuleInstalled.kt:73,229` = `command -v engram`; `MonitorFragment.kt:193`).
- El "Terminal" que muestra `EngramFragment` (pill de estado) es sobre la sesión TUI (`engram tui`), no sobre un proceso servidor — usa `terminalStatusPill()`, el mismo mecanismo que Claude Code/Codex/OpenCode para saber si el usuario dejó una sesión de terminal minimizada con `engram tui` corriendo.

## 6. Pantalla de la app (`EngramFragment.kt`)

Card "ESTADO": motor (Go, binario nativo), almacenamiento (SQLite local), pill "listo/no responde" (`isTermuxBinaryAvailable("engram")`, solo comprueba que el binario exista en PATH, no que la base SQLite sea accesible; `Doctor` cubre eso), pill de estado de terminal.

Botones (vía `ProcessBuilder` directo, sin pasar por terminal, salvo donde se indica):

| Botón | Comando real | Cómo se muestra |
|---|---|---|
| ⌨ Abrir TUI en terminal | `engram tui` (terminal) | Overlay de terminal — **`engram` a secas NO abre el TUI**, solo imprime ayuda y sale con código 1 (según `cmd/engram/main.go` del repo real) |
| 💾 Guardar memoria manual | `engram save <título> <mensaje>` | Diálogo con 2 campos (título + contenido multilinea) — documentado en el README (`Gentleman-Programming/engram`, "Guardar un aprendizaje manualmente"). Sin `--type`/`--project` expuestos a propósito (opcionales en el README, sin lista cerrada de valores documentada) |
| 🔍 Buscar en memoria | `engram search <texto>` | Diálogo con el resultado crudo |
| 🗂 Ver contexto reciente | `engram context [project]` | Diálogo — README: "Displays recent session context". Complementa "Buscar" cuando no se sabe qué término buscar |
| 📊 Ver estadísticas | `engram stats` | Diálogo |
| 📁 Listar proyectos | `engram projects list` | Diálogo — la memoria está organizada por proyecto; permite ver qué proyectos existen sin adivinar el nombre exacto |
| 🩺 Diagnóstico | `engram doctor` | Diálogo |
| 🔗 Configurar integración con agentes | `engram setup` (terminal, pregunta interactivamente) | Overlay de terminal — `engram setup [agent]` pregunta qué agente configurar cuando no se le pasa argumento; se abre en terminal en vez de adivinar el nombre que espera el flag (no documentado con valores cerrados en el README) |
| 📤 Exportar memoria a JSON | `engram export ~/engram_export_<timestamp>.json` | Toast con confirmación (chequea que el archivo exista en disco) |
| 📥 Importar memoria desde JSON | `engram import <file>` | Lista los `engram_export_*.json` existentes en `~/` (`promptImportMemory()`/`promptImportPath()`/`confirmImport()`) para elegir sin escribir ruta; opción de ruta manual como fallback para exports en otro lugar |
| 🗑 Eliminar memoria de un proyecto (DANGER) | `engram delete project <nombre>` (soft-delete, sin flag `--hard` expuesto a propósito) | 2 confirmaciones (nombre + "¿Eliminar?") |
| ⚙ Reinstalar/recompilar | `ModuleController.installModule("engram", ...)` / `reinstallModuleService()` | Toast |
| 🗑 Desinstalar | `confirmUninstallModule()` (`EngramFragment.kt:162-164`) | — |

## 6b. Integración con agentes de IA

`engram setup` (botón "🔗 Configurar integración con agentes") es la vía genérica e interactiva. Además, varios módulos de agentes de IA exponen su propio atajo de integración con Engram:

| Módulo | Mecanismo | Detalle |
|---|---|---|
| Claude Code, Codex, OpenCode, Antigravity | `BaseModuleFragment.engramSetupButton(agentSlug)` — botón compartido, uno por Fragment (`"claude-code"`, `"codex"`, `"opencode"`, `"antigravity-cli"`) | Agrega un servidor MCP específico de Engram a la config del agente (`BaseModuleFragment.kt:1461`). Ejecuta `engram setup <slug> \|\| engram setup`: si el slug es rechazado (el README no documenta valores cerrados para `engram setup [agent]`), cae al selector interactivo de Engram |
| OpenClaw | Cliente MCP real (no usa `engramSetupButton()`) — ver comentario en `OpenClawFragment.kt`/`OpenClawNative.kt` | OpenClaw ya es un cliente MCP por su cuenta |
| Hermes | Sin `engramSetupButton()` ni tool-calling custom como OpenClaw — ver comentario en `HermesFragment.kt`/`HermesNative.kt` | Hermes tiene su propio mecanismo de Skills (ver `HERMES.md`) |

## 7. Registry

Prefijo `engram.*`: `engram.installed`, `engram.install_date`.

## 8. Notas técnicas

- **`engram` sin subcomando no abre el TUI** — `EngramFragment.kt` llama siempre `engram tui` explícito, nunca el comando pelado.
- Engram es un módulo tipo CLI (como Claude/Codex/OpenCode) que puede quedar con una sesión de terminal minimizada corriendo el TUI, por lo que incluye `terminalStatusPill()`.
- Los subcomandos no-interactivos usados en botones (`save`/`search`/`context`/`stats`/`projects list`/`doctor`/`export`/`import`/`delete`) están confirmados contra el README del repo.
- **Ejecución**: `runEngramCommand` (`EngramFragment.kt:335-356`) lanza `$TERMUX_PREFIX_PATH/bin/engram` con `ProcessBuilder(lista)` + `applyTermuxEnv()` (ruta absoluta, sin shell), `redirectErrorStream(true)`, **sin timeout** (`process.waitFor()` bloqueante en un Thread): un comando colgado (p. ej. `engram import` esperando entrada) deja el hilo vivo. TUI y `setup` van por `launchTerminalCommand`.

## 9. Seguridad

- Nada pasa por shell salvo los dos comandos fijos de terminal (`engram tui`, `engram setup`). Título/contenido de `save`, texto de `search`, ruta de `import` y nombre de proyecto son argumentos de lista — sin inyección de comandos.
- Validación mínima: solo `isEmpty()`. Un valor que empiece con `-` (p. ej. proyecto `--hard`) podría interpretarlo `engram` como flag (depende del parser Go upstream; inferencia).
- `exportMemory()` deja el JSON de la memoria completa en `~/engram_export_<ts>.json` sin `chmod` ni limpieza; la memoria puede contener contexto sensible de proyectos, y los exports se acumulan sin gestión.
- `delete project` hace doble confirmación y sin `--hard`.

## 10. Limitaciones conocidas

- Verificación de ejecución **no fatal**: `engram --version || engram --help || warn ...` (`:181`) — si el binario no corre solo avisa y sigue; el registry queda `installed=true` igual.
- La build clona `--depth 1` sin fijar commit/tag: depende de `main` del upstream en el momento (riesgo de reproducibilidad).
- La heurística de "Guardar" depende de que la salida contenga la palabra `error` (`:213`); un fallo sin esa palabra mostraría "Memoria guardada".
- Inferencia: no se verificó si "Desinstalar" borra `~/.engram-src`, el binario o los datos de memoria.
- Compilación Go en Termux ~2 min, dependiente de red y del repo upstream; no se probó en dispositivo en la última revisión.

## 11. Fecha de alta

2026-07-29 (cabecera del script: "VERSIÓN: 1.0.0 | Julio 2026").
