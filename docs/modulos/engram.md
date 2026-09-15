# Engram

**Módulo Kairos** — Gestionado vía la UI de Kairos (tab Módulos). Instalación y estado se manejan desde la app vía `ProcessBuilder` → `modulos/engram.sh`.

---

**App:** Kairos (fork termux-app)
**Origen del código:** [github.com/Gentleman-Programming/engram](https://github.com/Gentleman-Programming/engram) (MIT)
**Script:** `modulos/engram.sh`
**Fragment:** `EngramFragment.kt`
**`hasSwitch`:** `false` — sin ON/OFF, sin proceso persistente en segundo plano

---

## 1. Qué es

Engram le da memoria persistente entre sesiones a los agentes de IA que corren dentro de Kairos (Claude Code, OpenCode, etc.) — guarda contexto (observaciones, decisiones, notas por proyecto) en una base SQLite local, sin depender de ningún servicio externo ni de la nube.

A diferencia de n8n/OpenClaw/OpenCode, **no es un proceso servidor** — es un binario CLI (`engram`) compilado desde Go que corre bajo demanda: cuando se lo invoca (buscar, ver stats, exportar, o su TUI interactiva), no queda residente escuchando ningún puerto.

## 2. Permisos

No requiere ningún permiso especial de Android — solo los genéricos que ya pide el wizard de Kairos (almacenamiento, para exportar memoria a `~/`). No usa red, no expone puertos.

## 3. Lógica de instalación (`modulos/engram.sh`)

Acepta `--silent` (siempre implícito al llamar desde la app), `--force` (reinstala aunque ya esté), `--describe` (manifiesto declarativo JSON).

| Paso | Checkpoint | Qué hace |
|---|---|---|
| 1 | `engram_deps` | `pkg install -y golang git sqlite` — aborta con `error()` si `go`/`git` no quedan disponibles tras instalar |
| 2 | `engram_clone` | `git clone --quiet --depth 1` de `Gentleman-Programming/engram` a `~/.engram-src` — aborta si no encuentra `cmd/engram/` tras clonar (detecta si el repo real cambió de estructura) |
| 3 | `engram_build` | `go build -C ~/.engram-src/cmd/engram -o $PREFIX/bin/engram` — verifica que el binario haya quedado ejecutable (`-x`), y además intenta `engram --version`/`engram --help` como verificación real de que corre (no solo que el archivo exista) |

Al final: `_update_reg "installed=true" "install_date=..."` sobre `~/.android_server_registry`, prefijo `engram.*`.

**Variables de entorno de compilación**: `GOPATH=$HOME/.local/go`, `GOCACHE=$HOME/.cache/go`, `GOMODCACHE=$GOPATH/pkg/mod` — todo bajo `$HOME`, nunca `/tmp` (Android 15 lo monta `noexec`).

**Detección "ya instalado"**: `command -v engram` — si existe y no se pasó `--force`, sale inmediato con `exit 0`.

## 4. Opciones/variantes

No tiene variantes (`"variants":[]` en el manifiesto `--describe`) — una sola forma de instalación, sin selección de modo/backend.

## 5. Detección de estado en la app

`engram` tiene `hasSwitch: false` en `modules.json` — **no está en los mapas de `ModuleController.kt`** (no tiene entrada en `getModuleStartScript()`/`getTmuxSession()`/`getProcessName()`). Esto significa:
- No hay concepto de "corriendo/detenido" — solo "instalado/no instalado", vía `BaseModuleFragment.isModuleInstalled()` leyendo `engram.installed=true` del registry.
- El "Terminal" que muestra `EngramFragment` (pill de estado) es sobre la sesión TUI (`engram tui`), no sobre un proceso servidor — usa `terminalStatusPill()`, el mismo mecanismo que Claude Code/Codex/OpenCode para saber si el usuario dejó una sesión de terminal minimizada con `engram tui` corriendo.

## 6. Pantalla real de la app (`EngramFragment.kt`)

Card "ESTADO": motor (Go, binario nativo), almacenamiento (SQLite local), pill "listo", pill de estado de terminal.

Botones (todos vía `ProcessBuilder` directo, sin pasar por terminal, salvo donde se indica):

| Botón | Comando real | Cómo se muestra |
|---|---|---|
| ⌨ Abrir TUI en terminal | `engram tui` (terminal) | Overlay de terminal — `engram` a secas NO abre el TUI, solo imprime ayuda y sale con código 1 |
| 💾 Guardar memoria manual | `engram save <título> <mensaje>` | Diálogo con 2 campos (título + contenido multilinea) — confirmado en el README oficial (`Gentleman-Programming/engram`, sección "Guardar un aprendizaje manualmente"). Sin `--type`/`--project` expuestos a propósito (opcionales en el README, sin lista cerrada de valores documentada) |
| 🔍 Buscar en memoria | `engram search <texto>` | Diálogo con el resultado crudo |
| 🗂 Ver contexto reciente | `engram context [project]` | Diálogo — confirmado en el README ("Displays recent session context"). Complementa "Buscar en memoria" (que requiere saber qué buscar) con una vista rápida de lo último guardado |
| 📊 Ver estadísticas | `engram stats` | Diálogo |
| 📁 Listar proyectos | `engram projects list` | Diálogo — confirmado en el README. La memoria está organizada por proyecto |
| 🩺 Diagnóstico | `engram doctor` | Diálogo |
| 🔗 Configurar integración con agentes | `engram setup` (terminal, pregunta interactivamente) | Overlay de terminal — `engram setup [agent]` pregunta interactivamente qué agente configurar cuando no se le pasa argumento; se abre en terminal en vez de adivinar el nombre exacto que espera el flag (no documentado con valores cerrados en el README) |
| 📥 Importar memoria desde JSON | `engram import <file>` — lista los `engram_export_*.json` existentes en `~/` para elegir sin escribir ruta a mano, con opción de ruta manual como fallback | Complemento directo de "Exportar memoria" — confirmado en el README oficial |
| 📤 Exportar memoria a JSON | `engram export ~/engram_export_<timestamp>.json` | Toast con confirmación real (chequea que el archivo exista en disco) |
| 🗑 Eliminar memoria de un proyecto | `engram delete project <nombre>` (soft-delete, sin flag `--hard` expuesto a propósito) | 2 confirmaciones (nombre + "¿Eliminar?") |
| ⚙ Reinstalar/recompilar | `ModuleController.installModule("engram", ...)` | Toast |

## 7. Integración con agentes de IA

`engram setup` (botón "🔗 Configurar integración con agentes") es la vía genérica e interactiva. Además, varios módulos de agentes de IA exponen su propio atajo de integración con Engram, sin pasar por este botón:

| Módulo | Mecanismo | Detalle |
|---|---|---|
| Claude Code, Codex, OpenCode, Antigravity | `BaseModuleFragment.engramSetupButton(agentSlug)` — botón compartido, uno por Fragment (`engramSetupButton("claude-code")`, `"codex"`, `"opencode"`, `"antigravity-cli"`) | Agrega un servidor MCP específico de Engram a la config del agente. El helper hace `engram setup <slug> || engram setup` — fallback al selector interactivo real si el slug adivinado es rechazado, ya que `engram setup [agent]` no tiene valores cerrados documentados en el README |
| OpenClaw | Cliente MCP real (no usa `engramSetupButton()`) | OpenClaw ya es un cliente MCP real por su cuenta, así que su integración con Engram no pasa por el botón genérico compartido |
| Hermes | Sin `engramSetupButton()` ni tool-calling custom como OpenClaw | Hermes tiene su propio mecanismo de Skills (fuera del alcance de este doc — ver `docs/modulos/hermes.md`) |

## 8. Registry

Prefijo `engram.*`: `engram.installed`, `engram.install_date`.

## Controles de la pantalla

| Control | Qué hace | Por qué |
|---|---|---|
| Pill "Estado" | Solo lectura — `isTermuxBinaryAvailable("engram")` | "listo" / "no responde" |
| Pill "Terminal" | Solo lectura — sesión de terminal minimizada corriendo el TUI | Indicador visual consistente con el resto de módulos tipo CLI |
| "⌨ Abrir TUI en terminal" | `engram tui` (nunca `engram` a secas) | `engram` sin subcomando solo imprime ayuda y sale con código 1 |
| "💾 Guardar memoria manual" | Diálogo (título + contenido) → `engram save` | La memoria se llena automático vía agentes de IA, pero también admite anotación manual |
| "🔍 Buscar en memoria" | Diálogo de texto → `engram search` | — |
| "🗂 Ver contexto reciente" | `engram context` | Complementa "Buscar" cuando no se sabe qué término buscar |
| "📊 Ver estadísticas (stats)" | `engram stats` | — |
| "📁 Listar proyectos" | `engram projects list` | Permite ver qué proyectos existen sin adivinar el nombre exacto |
| "🩺 Diagnóstico (doctor)" | `engram doctor` | — |
| "🔗 Configurar integración con agentes" | Terminal — `engram setup` | Pregunta interactivamente qué agente configurar — el flag no tiene valores documentados cerrados, no se adivina |
| "📥 Importar memoria desde JSON" | `engram import <file>`, lista los `engram_export_*.json` existentes | Restaura backups previos sin escribir ruta a mano |
| "📤 Exportar memoria a JSON" | `engram export ~/engram_export_<timestamp>.json` | — |
| "🗑 Eliminar memoria de un proyecto" (DANGER) | Diálogo de confirmación → `engram delete project <name>` (soft-delete) | Sin flag `--hard` expuesto a propósito — no se habilita un borrado sin vuelta atrás desde un diálogo simple |
| "⚙ Reinstalar / recompilar" | `reinstallModuleService()` | — |
