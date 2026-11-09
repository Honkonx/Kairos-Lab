# Freebuff — Módulo de Kairos

> Basado en el código real (`modulos/freebuff.sh` v1.3.0, 627 líneas; `CliToolFragment.kt`, config
> `"freebuff"`; `ModuleController.kt:900`; `modules.json`). "Inferencia" = deducido sin probar en dispositivo.

## 1. Qué es

Freebuff (CodebuffAI) — CLI de agente de codificación, gratuito, sin cuenta ni API key requerida (según `github.com/CodebuffAI/freebuff`). Comando `freebuff`.

**Clasificación: COMPLEMENTO/EXTENSIÓN** (con una función extra). Sin pantalla propia: `ModuleDetailNavigator.kt:51` → `CliToolFragment.newInstance(id, name)`, configurado por `CLI_MODULE_CONFIGS["freebuff"]`. El CLI no documenta login, prompt directo ni selector de modelo, así que queda solo con "Abrir en terminal" + gestión de proyectos + un diálogo de scaffolding `--create`. Sin estado propio, sin servicio. **~6 controles de UI**.

## 2. Instalación real (`modulos/freebuff.sh`)

Hay **3 métodos en cascada** en aarch64 (`freebuff.sh:11-33`; aarch64 es la única arquitectura real que soporta Termux):
1. Runtime **Bun nativo Bionic** (`Honkonx/bun-termux`, `.deb` aarch64) + `bun install -g freebuff@latest --os=linux --backend=copyfile` + descarga del binario glibc por el launcher con `FREEBUFF_BINARY_TARGET=linux-arm64` + patchelf (`_freebuff_install_via_native_bun` `:397`).
2. Binario `freebuff-linux-arm64.tar.gz` de CodebuffAI + glibc + patchelf (`_freebuff_download_native` `:193`). El binario real dentro del tarball se llama "codecane" (rebrand interno del proyecto, no "freebuff"); el script NO asume ese nombre, lo descubre en vivo (`find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz"`) y genera un wrapper en `$TERMUX_PREFIX/bin/freebuff` que apunta al binario detectado. El repo de releases es `CodebuffAI/freebuff`, todas las releases son prerelease (hay que pedir `/releases` en vez de `/releases/latest`; la tag se lee de `api.github.com/repos/CodebuffAI/freebuff/releases?per_page=5`).
3. npm (`freebuff@latest`, `:146`) — único camino fuera de aarch64.

- Flags (`:90-93`): `--silent`, `--force`, `--describe`, `--describe-files`. Sin `--variant`.
- Dependencias: `dpkg-deb`, `curl`; para el binario glibc: `glibc-repo`, `glibc`, `patchelf-glibc`; solo vía npm: `nodejs-lts`.
- **Sin sha256/checksum en ninguna ruta**. Versión no pineada (`latest`).
- Verificación: los métodos 1 y 2 hacen `freebuff --version` y descartan el método si no responde (`:~468`, `:262`); npm usa `fix_npm_shebang_wrapper freebuff freebuff` + `verify_binary_installed` (`:603-604`). Registry: `freebuff.version/channel/arch/install_date` con `channel` ∈ `native_arm64_bun_bionic | native_arm64_glibc | npm` (`:613-623`).
- "Ya instalado" → `exit 0` antes del registry (`:~489`).
- El wrapper de los métodos nativos hace `unset LD_PRELOAD` y `exec ~/.config/manicode/freebuff` (ruta con branding legado "manicode") o `$FREE_DIR/<bin>` — el mismo fix que aplica `CliToolFragment.needsGlibcCompat=true` del lado de la UI (doble seguro, redundante pero inofensivo).
- `modules.json` declara `installMethods:["npm"]`, `requires:["nodejs"]`, `arch:"bionic"`: metadata desfasada (el método preferido real es Bun nativo + glibc/patchelf y no necesita Node).

## 3. Ejecución

`terminalCommand:"freebuff"`. `needsGlibcCompat=true` → la UI antepone `unset LD_PRELOAD; ` a cada comando (`CliToolFragment.kt:~461`). Sin proceso de fondo.

## 4. Controles de la pantalla (`CliToolFragment.kt`, config `CLI_MODULE_CONFIGS["freebuff"]`)

| Control | Qué hace | Por qué |
|---|---|---|
| Card Estado (id, versión del registry, comando) | Solo lectura | — |
| "⌨ Abrir en terminal" (¿dónde abrir?) | `unset LD_PRELOAD; freebuff` | Sin login/prompt/modelo documentados — el CLI no ofrece más por línea de comandos |
| "🗂 Gestionar proyectos" | `showProjectsMenu()` (con lanzar en proyecto) | Mismo mecanismo compartido que el resto de `CliToolFragment` |
| "＋ Crear proyecto desde plantilla" | `freebuff --create '<template>' '<nombre>'` | Diálogo de 2 `EditText` (plantilla + nombre), ver sección 5 |
| "🔄 Actualizar" / "🔍 Diagnosticar" / "🗑 Desinstalar" | `updateModuleService()` / `runModuleDoctorForThis` / `confirmUninstall()` | Mantenimiento estándar |

## 5. `freebuff --create <template> <nombre>`

Flag documentado en `codebuff-community` (repo de plantillas/comunidad del mismo proyecto). Se implementa vía `CliModuleConfig.createFromTemplateTemplate` en `CliToolFragment.kt` (campo genérico reusable por cualquier módulo con scaffolding similar, mismo mecanismo que `installExtensionTemplate`/`symbolQueryTemplate`), con un diálogo de 2 `EditText` (`showCreateFromTemplateDialog()`) que arma `freebuff --create '<template>' '<nombre>'` con el mismo escapado de comillas simples que el resto del Fragment (`shellEscape()`).

## 6. Archivos y rutas

`$PREFIX/bin/freebuff` (wrapper), `~/.config/manicode/freebuff` (binario glibc bajado por el launcher), `$PREFIX/bin/bun` + `$PREFIX/lib/bun-termux/` (runtime Bun), `~/.bun/` (instalación global), `$FREE_DIR` (método 2), `~/tmp/bun_native_$$`, `~/tmp/freebuff_<tag>_$$`, checkpoint `~/.install_freebuff_checkpoint`.

## 7. Seguridad

- Descarga binarios de terceros (Honkonx/bun-termux con fallback a Hope2333/bun-termux, CodebuffAI) y los ejecuta **sin hash**.
- Copia el árbol `usr/` completo de un `.deb` ajeno sobre `$PREFIX` con `cp -a` (`:~334`): un `.deb` malicioso podría sobrescribir cualquier binario de Termux (el riesgo real depende de la confianza en el repo).
- `--create`: plantilla y nombre se escapan con `shellEscape`.

## 8. Limitaciones conocidas

- `ModuleController.kt:900` desinstala solo con `npm uninstall -g freebuff` — no elimina el wrapper nativo `$PREFIX/bin/freebuff`, `~/.config/manicode/freebuff`, `bun-termux` ni `~/.bun`; la desinstalación es incompleta para los canales nativos (los más probables).
- `modules.json` no refleja el método real de instalación.
- Sin checksum; sin pin de versión.
- El script anota que el binario de CodebuffAI del método 2 segfaulteó en un dispositivo real (comentarios `:26-31`) — se conserva como fallback sin garantía.
- Hipótesis no verificada: `find "$FREE_DIR" -maxdepth 1 -type f ! -name "*.tar.gz"` no filtra por si el archivo es realmente un ejecutable (ELF); si el tarball de una release futura trajera más de un archivo no-`.tar.gz` (ej. un `README.md`/`LICENSE` junto al binario), `find` podría no devolver el binario real primero (el orden de `find` no está garantizado alfabético). Reproducirlo con ADB antes de asumir un fix.
- El script de instalación mezcla tres rutas largas; sin dispositivo no se verificó cuál queda activa en cada equipo.

## 9. Fecha de alta

`modulos/freebuff.sh`: **2026-08-12**.
