# Codex CLI — Módulo de Kairos

**Módulo de Kairos** — instalación gestionada por la app vía `ModuleController.installModule()` → `ProcessBuilder` → `modulos/codex.sh`. Sin switch on/off (CLI puro, sin proceso persistente).

---

**Script:** `modulos/codex.sh` (v2.0.0, 361 líneas)
**Fragment:** `CodexFragment.kt` (390 líneas)
**`modules.json`:** `id: "codex"`, `hasSwitch: false`, `hasVariants: true`, icono `◆`
**Registry prefix:** `codex.*`

---

## 1. Descripción general

Codex CLI es el asistente de código de OpenAI en terminal, empaquetado para Termux/Bionic por terceros: `@mmmbuto/codex-cli-termux` (DioNanos/codex-termux), `@mmmbuto/codex-vl` (DioNanos/codex-vl, con "code-mode" V8) o el binario Rust nativo de `wallentx/codex-termux`.

**Clasificación: MÓDULO FUNCIONAL** (pantalla propia, `ModuleDetailNavigator.kt:24`): estado real (canal/versión/pill), cadena de diálogos para prompt no interactivo con captura de salida (sandbox + approval), selector de variante, proyectos. ~12 controles. Sin servicio ni puerto.

## 2. Permisos

Ninguno específico de Android. No expone puerto ni corre en segundo plano.

## 3. Variantes/canales

El script tiene **2 variantes** (`--variant termux|vl`, `codex.sh:61`; `--describe` `:69`) más un **fallback automático nativo** (wallentx/codex-termux) que se dispara solo si la vía npm `termux` falla.

| Canal | Paquete/fuente | Node.js necesario | Notas |
|---|---|---|---|
| `termux` (default) | npm `@mmmbuto/codex-cli-termux@latest` | Sí (`nodejs-lts`) | Mantenido por un tercero (`DioNanos/codex-termux`), no oficial de OpenAI |
| `vl` (`--variant vl`) | npm `@mmmbuto/codex-vl@latest` + symlink `codex -> codex-vl` | Sí | Sin fallback (`:289-306`) |
| `termux-fallback` | Binario Rust nativo `*aarch64-linux-android.tar.gz` de `wallentx/codex-termux` | No, pero solo se llega a él con Node ya presente (`:262-264`) | Solo se usa si `termux` falla; el binario sin stripear pesa ~1.4 GB (`:241`) |

## 4. Lógica de instalación

- Flags (`:57-63`): `--silent`, `--force`, `--variant termux|vl`, `--describe`, `--describe-files`. Default silencioso: canal `termux`.
- Dependencia: `nodejs-lts` (`modules.json` `requires:["nodejs"]`).
- Pasos: Node (checkpoint `codex_node`) → `npm install -g @mmmbuto/codex-cli-termux@latest` (+ `fix_npm_shebang_wrapper`; captura `PIPESTATUS[0]` tras el pipe a `tail -5` para no confiar en el exit code de `tail`, y aborta con `error` si `npm` realmente falló) → `verify_binary_installed codex`; si falla, `_install_codex_fallback_native()` (`:225`): consulta `api.github.com/repos/wallentx/codex-termux/releases?per_page=5`, baja el tarball a `$HOME/tmp/codex_fallback_dl` (no usa `/tmp/`: Android 15 lo monta `noexec`), lo copia a `$PREFIX/opt/codex-wallentx/codex`, symlink en `$PREFIX/bin/codex`, y verifica ejecución real.
- **Login**: en modo `--silent` se omite explícitamente (`warn` + checkpoint igual marcado; `:329-331`) — no se puede automatizar sin credenciales. En modo interactivo ofrece correr `codex login`.
- **Sin sha256/checksum en ninguna ruta**; el fallback nativo descarga un tarball de ~GB sin verificar integridad. La integridad npm depende del propio registry.
- Verificación real con `verify_binary_installed` (`codex --version`); registry `codex.installed/version/channel/install_date` (`:352-358`).

### Por qué `@latest` y no `@next`

El script usa explícitamente `CODEX_PKG="@mmmbuto/codex-cli-termux@latest"`, NUNCA `@next`. El README del repo (`github.com/DioNanos/codex-termux`) describe `next` como "tested candidate versions published after GitHub Actions validation" (todavía no promovido) y dice que `@latest` es el target recomendado para usuarios finales. En este paquete puntual `next` queda POR DEBAJO de `latest` (al revés de la convención usual): en el momento de verificarlo, `stable=0.144.4`, `next=0.145.0`, `latest=0.146.0`.

## 5. Detección de estado

`get_installed_ver()`: `command -v codex && codex --version` (regex `[0-9]+\.[0-9]+\.[0-9]+`). Si ya hay una versión detectada y no hay `--force`, el script sale inmediatamente (`exit 0`) sin resincronizar el registry (a diferencia de `claude.sh`).

Del lado de la app, módulo **sin switch** — `isModuleInstalled()` gatea la UI, sin concepto de "corriendo".

## 6. Pantalla de la app (`CodexFragment.kt`)

| Control | Qué hace | Notas |
|---|---|---|
| Card ESTADO (Canal/Versión/Estado/Terminal) | Solo lectura: canal leído del registry (`CodexFragment.kt:40`), versión de `codex.version`, pill "Estado" según `isTermuxBinaryAvailable("codex")`, pill/botón cerrar terminal | — |
| "⌨ Abrir en terminal" | Diálogo ¿dónde abrir? → `launchTerminalCommand("codex")` | — |
| "📁 Abrir en proyecto" | `ProjectsManager.projectsList()` sobre `~/proyectos` y abre `cd ... && codex` | Carpeta compartida con Claude/Antigravity/OpenCode |
| "🗂 Gestionar proyectos" | Menú compartido symlink/importar copia desde Download/eliminar/sincronizar todos | — |
| "🔑 codex login" | `launchTerminalCommand("codex login")` | — |
| "💬 Enviar prompt (no interactivo)" | Pide prompt → modelo (texto libre, sin alias fijos, ej. `gpt-5.6-terra`; vacío = default) → sandbox (`--sandbox`: `read-only \| workspace-write \| danger-full-access` o default) → aprobación (`--ask-for-approval`: `untrusted \| on-request \| never` o default) → ejecuta `codex exec` en background con `--output-last-message <archivo>` y muestra el resultado en un diálogo (`runExecCapture`, `CodexFragment.kt:342`) | `codex exec` (alias `codex e`) corre no interactivo e imprime a stdout. `--output-last-message` separa el mensaje final del ruido de progreso (`--json` requeriría un parser de eventos dedicado). Los valores de sandbox/aprobación coinciden 1:1 con la documentación oficial de seguridad de Codex. Prompt y modelo con `shellEscape` |
| "🕒 Reanudar última sesión" | `codex resume --last` en terminal (diálogo de carpeta) | Sintaxis correcta según la documentación oficial (no hay flag corto `-l`; al reanudar no se puede cambiar modelo/reasoning-effort). El resume es por sesión grabada para el directorio actual: si nunca se corrió Codex desde ese cwd, el propio CLI responde que no hay sesión |
| Botón Engram | `engramSetupButton("codex")` — ejecuta `engram setup codex \|\| engram setup` (si el slug es rechazado, cae al selector interactivo de Engram) | — |
| "⚙ Instalar / cambiar canal" | Selector Normal / VL → `installCodexVariant`, reinstalación con `--variant` (`reinstallModuleService()`) | Mismo gap de `--force` que el resto de los módulos sin switch |
| "🗑 Desinstalar" | `confirmUninstallModule()` (`ModuleController` tiene plan profundo por canal, `ModuleController.kt:866-877`) | — |

## 7. Registry

```
codex.installed=true
codex.version=<x.y.z>
codex.channel=termux|termux-fallback|vl
codex.install_date=<YYYY-MM-DD>
```

## 8. Checkpoints — namespace propio

A diferencia de otros módulos, `codex.sh` NO usa las funciones genéricas `check_done`/`mark_done` de `lib.sh` tal cual — las sobreescribe para namespacear las keys del checkpoint como `codex_<paso>=done`, sobre el mismo archivo `$HOME/.install_codex_checkpoint`.

## 9. Archivos y rutas

Paquete npm global `@mmmbuto/codex-cli-termux` o `@mmmbuto/codex-vl` bajo `npm root -g`; `$PREFIX/bin/codex` (wrapper npm o symlink), `$PREFIX/bin/codex-vl`, `$PREFIX/opt/codex-wallentx/codex` (fallback), temporales `$HOME/tmp/codex_fallback_dl`, checkpoint `~/.install_codex_checkpoint`; la config propia de Codex (`~/.codex`) la crea el CLI, no el script.

## 10. Seguridad

- Prompt y modelo con `shellEscape` (`CodexFragment.kt:214`); sandbox/approval son listas cerradas.
- El selector de sandbox ofrece `danger-full-access` y approval `never` sin advertencia visible adicional (`CodexFragment.kt:269-279`).
- Paquetes de terceros (DioNanos, wallentx) ejecutados con permisos del usuario; sin verificación de hash.
- No maneja secretos propios; el login lo hace el CLI en terminal.

## 11. Limitaciones conocidas

- Sin checksum en la descarga del fallback (~1.4 GB) ni en nada del script.
- Inferencia: `ModuleController.kt:868-886` tiene plan de desinstalación para los 3 canales vigentes (`termux-fallback`, `vl`, `termux`); no hay rama para un canal antiguo `native`, así que una instalación vieja con `channel=native` podría no desinstalarse del todo.
- Inferencia: tras el `exit 0` temprano por "ya instalado" no se re-sincroniza el registry.
- "Reanudar última sesión" reportó un error en dispositivo real que no se pudo reproducir por lectura de código; las hipótesis son que Codex rechace `--last` cuando no hay sesión previa para ese directorio, o un cambio de sintaxis en una versión más reciente. Reproducir en dispositivo (confirmando primero que exista una sesión previa) antes de tocar código.

## 12. Fecha de alta

`modulos/codex.sh`: **2026-07-24**.
