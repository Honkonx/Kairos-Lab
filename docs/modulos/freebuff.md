# Freebuff

Basado en el código real (`modulos/freebuff.sh`, `app/src/main/java/com/termux/app/ui/CliToolFragment.kt`).

## 1. Qué es

Freebuff (CodebuffAI) — CLI de agente de codificación, gratuito, sin cuenta/API key requerida (confirmado contra `github.com/CodebuffAI/freebuff`). En Kairos cae en el Fragment compartido `CliToolFragment.kt` (no tiene pantalla propia) — `CLI_MODULE_CONFIGS["freebuff"]` refleja que el CLI real no documenta login, prompt directo ni selector de modelo, así que queda solo con "Abrir en terminal" + gestión de proyectos.

## 2. Instalación real (`modulos/freebuff.sh`)

En ARM64 (aarch64, la única arquitectura real que soporta Termux): descarga el binario nativo real de `github.com/CodebuffAI/freebuff/releases` (glibc, patcheado con `patchelf` al loader de Termux) — con fallback a npm si la descarga falla. El binario real dentro del tarball se llama "codecane" (rebrand interno del proyecto, no "freebuff") — el script NO asume ese nombre, lo descubre en vivo (`find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz"`) y genera un wrapper real en `$TERMUX_PREFIX/bin/freebuff` que apunta al binario real detectado, sea cual sea su nombre interno.

Detalles reales a tener en cuenta al mantener el script: el repo real de releases es `CodebuffAI/freebuff` (no `codebuff-community`), todas las releases son prerelease (hay que pedir `/releases` en vez de `/releases/latest`), y el nombre del binario real dentro del tarball es `codecane`, no `freebuff`.

El wrapper generado (`$TERMUX_PREFIX/bin/freebuff`) hace `unset LD_PRELOAD` internamente antes de ejecutar el binario real — necesario para compatibilidad glibc dentro de Termux (bionic). Verificación funcional real (`freebuff --version`) antes de declarar el método nativo exitoso — si falla, cae a npm en vez de marcar éxito con un binario roto.

## 3. Limitación conocida — descubrimiento del binario en el tarball

`find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz"` no filtra por si el archivo es realmente un ejecutable (ELF) — si el tarball de una release futura trajera más de un archivo no-`.tar.gz` (ej. un `README.md`/`LICENSE` junto al binario), `find` podría no devolver el binario real primero (el orden de `find` no está garantizado alfabético). Es un caso límite no reproducido contra una release real con ese contenido — queda como riesgo conocido a vigilar si el proyecto upstream cambia el contenido de sus releases.

## 4. Controles de la pantalla (`CliToolFragment.kt`, config `CLI_MODULE_CONFIGS["freebuff"]`)

| Control | Qué hace | Por qué |
|---|---|---|
| "⌨ Abrir en terminal" | `unset LD_PRELOAD; freebuff` | Sin login/prompt/modelo documentados — el CLI real no ofrece más que esto por línea de comandos |
| "🗂 Gestionar proyectos" | `showProjectsMenu()` | Mismo mecanismo compartido que el resto de `CliToolFragment` |
| "＋ Crear proyecto desde plantilla" | `freebuff --create '<template>' '<nombre>'` | Diálogo de 2 `EditText` (plantilla + nombre) |
| "🔄 Actualizar" / "🗑 Desinstalar" | `updateModuleService()`/`confirmUninstall()` | Mantenimiento estándar |

## 5. `freebuff --create <template> <nombre>`

Flag real documentado en `codebuff-community` (repo de plantillas/comunidad del mismo proyecto) — implementado vía `CliModuleConfig.createFromTemplateTemplate` en `CliToolFragment.kt` (campo genérico reusable por cualquier módulo con scaffolding similar), con diálogo de 2 `EditText` (`showCreateFromTemplateDialog()`) que arma `freebuff --create '<template>' '<nombre>'` con el mismo escapado de comillas simples que el resto del Fragment (`shellEscape()`).
