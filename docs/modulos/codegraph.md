# CodeGraph — módulo `codegraph` (análisis estático de código)

> Resumen del conjunto de CLIs en `CLI_TOOLS.md`. No probado en dispositivo.

## 1. Qué es
CodeGraph (`colbymchenry/codegraph`): herramienta de análisis estático que construye un grafo de relaciones entre
archivos/funciones/clases de un proyecto. NO es un agente de IA (`modules.json`: `codegraph`, `type: Nativo`,
`category: dev`, `installMethods: ["github_release"]`, `requires: []`, `terminalCommand: "codegraph"`, ~60MB, ~2 min).
Visible en la Tienda (no es `internal`).

## 2. Clasificación: MÓDULO FUNCIONAL (ligero)
Tiene UI propia con acciones que corren subcomandos reales sobre un proyecto elegido (no solo instala y abre terminal).
Comparte el fragment `CliToolFragment` con otros 13 CLIs (`ModuleDetailNavigator.kt:61`), configurado en `CliToolFragment.kt:377-390`.
Opciones de UI: ~10 (ver §5). No tiene servicio en segundo plano ni puerto.

## 3. Instalación
- `modulos/codegraph.sh` (397 líneas; versión 2.0.0 según cabecera). Flags: `--silent`, `--force`, `--describe`, `--describe-files`
  (manifiesto `moduledeb`, `:101-135`). En modo no silencioso pide confirmación por `/dev/tty` (`:237`); la app usa `--silent`.
- Dos métodos en cascada. Nota: la cabecera del script (líneas ~31-48) describe el fork nativo como «MÉTODO 1 preferido», pero el código
  hace lo contrario: primero el método original y el fork es respaldo (`:253-256`, `:361-378`).
  1. PASO 1 (`:258`): si `uname -m` = aarch64, instala `glibc-repo glibc dpkg curl` faltantes (fallo = solo `warn`).
  2. PASO 3-5 (subshell, `:285-358`): dependencias (`nodejs-lts ripgrep sqlite git clang make curl`), descarga
     `codegraph-linux-arm64.tar.gz` de la última release de `colbymchenry/codegraph` a `~/.local/share/kairos-data/codegraph-linux-arm64`,
     y crea el wrapper `$PREFIX/bin/codegraph` (`exec node .../lib/dist/bin/codegraph.js "$@"`).
  3. PASO 5b (`:361-378`): si `verify_binary_installed codegraph --help` falla, `_codegraph_download_native_fork` (`:161`) baja el `.deb` aarch64
     de `Honkonx/codegraph-termux` (o `Hope2333/codegraph-termux`), lo extrae con `dpkg-deb -x` y copia `usr/` a `$PREFIX`; trae su propio Node glibc.
- Verificación: `verify_binary_installed codegraph --help` (`:353`, `:380`; usa `--help`, no `--version`).
- Registry (`:388`): `installed=true`, `version`, `channel=github_release|fork_native_arm64`, `install_date`. Usa checkpoints (`~/.install_codegraph_checkpoint`), borrados al final.
- Ya instalado y sin `--force`: `exit 0` sin tocar el registry (`:~243`).

## 4. Ejecución
No hay proceso residente. Cada acción abre la terminal integrada con el comando (`launchCliTerminalCommand`), con `cd` a la carpeta de proyecto elegida.

## 5. Opciones de la UI (`CliToolFragment.kt:466-620`)
Tarjetas: Estado (id/versión/comando) y Usar. Botones: 1 «Abrir en terminal» (carpeta por defecto o elegida), 2 «Gestionar proyectos»,
3 «Analizar proyecto» (`codegraph init '<ruta>' && codegraph status '<ruta>'`), 4 buscar símbolo (`codegraph query '<símbolo>'`),
5-7 «callers», «callees», «impact» (`codegraph callers|callees|impact '<símbolo>'`), 8 «archivos afectados» (`codegraph affected '<ruta>'`),
9 «Actualizar», 10 «Desinstalar» (con opción de desinstalación profunda). Sin login ni proveedor local (`hasAuth=false`, `localProviderCapable=false`).

## 6. Archivos y rutas
`$PREFIX/bin/codegraph`; datos del método 2 `~/.local/share/kairos-data/codegraph-linux-arm64/` (+ archivo `version`); fork: `$PREFIX/lib/codegraph-termux/<ver>/`;
config del propio binario `~/.codegraph`; registry `~/.android_server_registry`.

## 7. Seguridad
Descarga binarios de GitHub Releases sin verificación de hash (no se usa `verify_sha256` en este script). El método 2 toma la «última release»
por redirección de `releases/latest` (sin pin). El fork de respaldo copia un árbol `usr/` completo sobre `$PREFIX` (puede pisar archivos). No maneja secretos.

## 8. Limitaciones conocidas
- La cabecera del script está desfasada respecto al orden real de métodos (§3).
- Sin verificación de integridad (hash) de lo descargado; sin pin de versión.
- Las acciones solo se lanzan en la terminal (no hay panel nativo con el grafo/resultados); el análisis no se muestra en UI propia.
- Inferencia: la dependencia glibc del PASO 1 se instala siempre en aarch64 aunque el método original (Node) no la use; no se midió el costo.

## 9. Fecha de alta
`modulos/codegraph.sh`: 2026-08-18.
