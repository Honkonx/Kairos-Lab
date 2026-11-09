# GitHub Copilot CLI — Módulo de Kairos

> Fuentes: `modulos/copilotcli.sh` (256 líneas), `app/src/main/assets/modules.json`,
> `CliToolFragment.kt` (config `"copilotcli"` líneas 169-182), `ModuleController.kt:918`.
> "Inferencia" = deducido sin probar en dispositivo.

## 1) Qué es y para qué sirve
CLI de agente de GitHub Copilot (`copilot`, paquete npm `@github/copilot`). En Kairos se instala por npm y, como el paquete de plataforma oficial no se resuelve en Android, el script lo corrige a mano (ver §3).

## 2) Clasificación: **COMPLEMENTO/EXTENSIÓN**
Sin Fragment propio: `ModuleDetailNavigator.kt:53` → `CliToolFragment` parametrizado. Sin estado propio, sin servicio. Instala la herramienta, abre terminal y ofrece login, un prompt directo y un comando informativo. **~8 controles de UI**.

## 3) Instalación (`modulos/copilotcli.sh`)
- Flags (`:43-46`): `--silent`, `--force`, `--describe`, `--describe-files`. Sin variantes.
- Dependencias: `nodejs-lts` (PASO 1, `:135`); para el binario de plataforma: `glibc`, `glibc-repo`, `patchelf-glibc` (instalados solo si hacen falta, `:210-217`).
- PASO 2: `npm install -g @github/copilot@latest` (`COPILOT_PKG` `:93`) → `fix_npm_shebang_wrapper copilot @github/copilot`. Si `copilot --version` falla (`:190`): instala a mano `@github/copilot-linux-arm64@<versión>` con `--force`, **copia** el paquete a `@github/copilot-android-arm64` y reescribe `name` en su `package.json` (porque el loader resuelve `@github/copilot-${process.platform}-${process.arch}` y Android reporta `android`), y aplica `patchelf --set-interpreter` al `copilot` interno contra el loader glibc de Termux (`:197-222`). Si sigue sin ejecutar → `error`.
- **Sin sha256/checksum**; la integridad depende del registry npm.
- Registry: `copilotcli.installed/version/channel=npm/install_date` (`:247-251`). "Ya instalado" (`get_installed_ver` no vacío y sin `--force`) → `exit 0` sin tocar registry.
- Login omitido en `--silent`: el script avisa que hay que correr `copilot` y usar `/login` o exportar `GH_TOKEN`/`GITHUB_TOKEN` (`:236`).

## 4) Ejecución
`terminalCommand:"copilot"`; `needsGlibcCompat=true` → la UI antepone `unset LD_PRELOAD; ` (`CliToolFragment.kt:launchCliTerminalCommand`). Sin proceso de fondo ni puerto. Lanza siempre en la terminal de Kairos.

## 5) Controles de UI
Estado (id/versión del registry/comando) · Abrir en terminal (¿dónde abrir?) · Gestionar proyectos (con lanzar en proyecto) · **Iniciar sesión** (`copilot login`, terminal) · **Enviar prompt** (`copilot -p '<prompt>'`, escapado) · **Plugins/MCP** (`copilot plugins list`) · Actualizar · Diagnosticar · Desinstalar (`npm uninstall -g @github/copilot`, `ModuleController.kt:918`).

## 6) Archivos y rutas
Paquete npm global `@github/copilot` y `@github/copilot-android-arm64` (copia creada por el script) bajo `$(npm root -g)/@github/`, wrapper `$PREFIX/bin/copilot`, checkpoint `~/.install_copilotcli_checkpoint`. Las credenciales de Copilot las guarda el propio CLI (inferencia: no se verificó dónde).

## 7) Seguridad
- Prompt escapado con `shellEscape` antes de interpolar en `copilot -p '{PROMPT}'`.
- `patchelf` y copia de paquetes bajo `node_modules` con permisos del usuario; sin hash.
- Token: el script solo sugiere `GH_TOKEN`/`GITHUB_TOKEN`; Kairos no guarda secretos de Copilot (no hay código que los lea).
- Un prompt no interactivo (`-p`) con herramientas puede requerir permisos del agente; la UI no expone flags de permiso (inferencia).

## 8) Limitaciones conocidas
- **Contradicción pendiente de verificar**: el script dice que el login se hace con `/login` dentro de `copilot` (`copilotcli.sh:236`), pero la UI ejecuta el subcomando `copilot login` (config `authCommand`). Uno de los dos puede no ser válido en la versión real; sin dispositivo no está verificado cuál.
- `modules.json` declara `arch:"bionic"`, `installMethods:["npm"]`, `requires:["nodejs"]` pero el binario real es glibc parcheado → metadata incompleta.
- Sin checksum; `@latest` sin pin.
- Inferencia: el parche `copilot-android-arm64` depende del formato interno del loader minificado de `@github/copilot`; una actualización puede romperlo y el botón "Actualizar" no re-aplica el parche si `copilot --version` ya responde.
- Sin continuar sesión ni selector de modelo en la UI (el config no los define; no se verificó si el CLI los ofrece).

## 9) Fecha de alta
`modulos/copilotcli.sh`: **2026-08-12**.
