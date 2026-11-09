# Codebuff — Módulo de Kairos

> Basado en el código real (`modulos/codebuff.sh` v1.5.0, 553 líneas; `CliToolFragment.kt`, config
> `"codebuff"`; `ModuleController.kt:901`; `modules.json`). "Inferencia" = deducido sin probar en dispositivo.

## 1. Qué es

Codebuff (CodebuffAI): agente de codificación multimodelo (proveedor vía OpenRouter u otro, configurado en un asistente TUI interno; sin login/API key propio automatizable). Comando `codebuff`. El paquete npm es solo un launcher; el binario real (ARM64 glibc) se baja en el primer arranque.

**Clasificación: COMPLEMENTO/EXTENSIÓN** (con una función extra). Usa el Fragment compartido `CliToolFragment` (`ModuleDetailNavigator.kt:52`). Sin estado propio ni servicio; abre terminal y ofrece scaffolding `--create`. **~6 controles de UI**. `modules.json`: `installMethods:["npm"]`, `requires:["nodejs"]`, `catalogVersion:"1.x"`.

## 2. Instalación real (`modulos/codebuff.sh`) — mecanismo en 2 etapas, no obvio

El paquete npm real `codebuff` NO es la app en sí — es un **launcher delgado** (~35KB, sin ningún addon nativo) que recién descarga el binario real la **primera vez que se ejecuta el comando** `codebuff` (a `$HOME/.config/manicode/codebuff`, según el `launcher.js` del paquete). El script dispara esa primera ejecución a propósito (`timeout 90 codebuff --version`) para forzar la descarga, y recién ahí aplica `patchelf` (mismo patrón glibc que `freebuff.sh`).

- Flags: `--silent`, `--force`, `--describe`, `--describe-files`. Sin `--variant`.
- PASO 1: glibc (`glibc-repo`, `glibc`, `patchelf-glibc`, `curl`). PASO 2: `nodejs-lts`, `npm install -g codebuff@latest --force` (`CODEBUFF_PKG` `:167`) + `fix_npm_shebang_wrapper` (el symlink de `npm install -g` tiene shebang `#!/usr/bin/env node`, inexistente en Termux). PASO 3-5: ejecutar el launcher con `CODEBUFF_BINARY_TARGET=linux-arm64` (override oficial que expone el launcher por entorno; necesario porque calcula la clave `${process.platform}-${process.arch}` = `android-arm64`, que no existe en su mapa de targets, `:73,108`) para forzar la descarga a `~/.config/manicode/codebuff`, y `patchelf --set-interpreter` (`:420-426`). PASO 5b: respaldo con el `.deb` del fork `Honkonx/codebuff-termux` (`_codebuff_download_native_fork` `:210`) con `dpkg-deb -x` + `cp -a` del árbol `usr/` (binario ya pre-parcheado). PASO 7: configuración de modelo omitida en silencioso.
- Orden de métodos: el flujo original (npm launcher + descarga diferida + patchelf, PASO 2-5) es el primario y el fork es **respaldo** en PASO 5b, solo si no quedó un binario ejecutable (`:440-461`). La cabecera del script (`:12-30,92-94`) describe el fork como "MÉTODO 1 (preferido)", pero el cuerpo manda y hace lo contrario.
- Verificación PASO 6: `timeout 25 codebuff --version`; falla (`error`) si aparece "Unsupported platform|ENOENT" o si no hay binario nativo ejecutable. `codebuff --version` nunca responde rápido — el launcher dibuja un banner ASCII animado antes de cualquier salida — por lo que no se exige un semver parseado (`:464-486`).
- Persistencia: el `export CODEBUFF_BINARY_TARGET=linux-arm64` solo vive mientras corre el script, así que se persiste en `~/.bashrc` (`:523-532`, mismo patrón idempotente que usa `ssh.sh` para sus aliases) para que una terminal nueva no reproduzca el error "Unsupported platform".
- Registry: `codebuff.version/channel/arch/install_date`, `channel` ∈ `npm | npm_native_arm64 | fork_native_arm64`.
- **Sin sha256**; sin pin de versión.

El mecanismo de detección del binario real (`$CODEBUFF_NATIVE_BIN = $HOME/.config/manicode/codebuff`) es una ruta fija conocida (no un `find` sobre un directorio con múltiples archivos), más robusto que el de Freebuff en ese aspecto (ver `FREEBUFF.md`).

## 3. Ejecución

`terminalCommand:"codebuff"`; `needsGlibcCompat=true` → la UI antepone `unset LD_PRELOAD; ` (consistente con el resto de módulos glibc+patchelf). Sin proceso de fondo. Para scaffolding: `codebuff --create '<plantilla>' '<nombre>'`.

## 4. Controles de la pantalla (`CliToolFragment.kt`, config `CLI_MODULE_CONFIGS["codebuff"]`)

| Control | Qué hace | Por qué |
|---|---|---|
| Estado (id/versión/comando) | Solo lectura | — |
| "⌨ Abrir en terminal" (¿dónde abrir?) | `unset LD_PRELOAD; codebuff` | Sin login/prompt/modelo documentados — la configuración de proveedor de modelo (OpenRouter) la hace el usuario dentro de la sesión |
| "🗂 Gestionar proyectos" | `showProjectsMenu()` | Mismo mecanismo compartido |
| "＋ Crear proyecto desde plantilla" | `codebuff --create '<template>' '<nombre>'` | Diálogo de 2 `EditText` (plantilla + nombre); mecanismo genérico `CliModuleConfig.createFromTemplateTemplate` + `showCreateFromTemplateDialog()` (ver `FREEBUFF.md` §5) |
| "🔄 Actualizar" / "🔍 Diagnosticar" / "🗑 Desinstalar" | `updateModuleService()` / `confirmUninstall()` | Mantenimiento estándar |

## 5. Archivos y rutas

`$PREFIX/bin/codebuff` (symlink npm o wrapper del fork), `~/.config/manicode/codebuff` (binario real), `$PREFIX/lib/codebuff/runtime/codebuff` (fork), paquete npm global `codebuff`, línea en `~/.bashrc`, checkpoint `~/.install_codebuff_checkpoint`, temporales de descarga.

## 6. Seguridad

- El launcher descarga de `codebuff.com/api/releases/download/...` → GitHub `CodebuffAI/codebuff-community` y se ejecuta sin verificar hash (comentario `:51`); fork de respaldo sin checksum.
- `cp -a` de un `.deb` ajeno sobre `$PREFIX` (misma salvedad que Freebuff).
- Modifica `~/.bashrc` de forma idempotente (filtra e inserta bloque). Proveedor/API key los maneja el propio CLI (no los toca Kairos).

## 7. Limitaciones conocidas

- Desinstalación incompleta: `ModuleController.kt:901` solo hace `npm uninstall -g codebuff`; deja `~/.config/manicode/codebuff`, `$PREFIX/lib/codebuff` y la línea de `.bashrc`.
- `modules.json` no declara glibc aunque el binario lo exige.
- PASO 4 no usa `verify_binary_installed` (el launcher aún no tiene binario) — se verifica recién en PASO 6.
- Inferencia: la tolerancia a "no imprime versión" puede dar falsos positivos si el binario arranca el banner pero falla luego.
- Si se reporta un error al abrir Codebuff en terminal, conviene reproducirlo con ADB/`uiautomator` antes de asumir una causa.

## 8. Fecha de alta

`modulos/codebuff.sh`: **2026-08-12**.
