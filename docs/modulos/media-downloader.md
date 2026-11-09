# MEDIA_DOWNLOADER.md — módulo `media-downloader` (descargador de vídeo/audio)

> Ficha basada en la lectura del código (`modulos/media_downloader.sh`, `MediaDownloaderFragment.kt`, `ArchiveManager.kt`, `fragment_media_downloader.xml`, `ArchiveManagerTest.kt`). No probado en dispositivo.

## 1. Qué es
Descargador único: pega una URL http(s) y descarga vídeo con sonido (MP4), vídeo sin sonido, o solo audio (MP3), con
resolución máxima 480p/720p/1080p/1440p y carpeta de destino elegible. Motor: `yt-dlp` + `ffmpeg`.
`modules.json`: `media-downloader`, `type: Nativo`, `category: media`, `installMethods: ["pkg"]`, `requires: ["python"]`,
`recommended: true`, `script: media_downloader.sh`, sin `terminalCommand`, ~150MB.

## 2. Clasificación: MÓDULO FUNCIONAL
UI nativa propia con opciones reales y estado de operación (formato, calidad, destino persistente, progreso, resultado).
Opciones de UI: ~6 (campo URL, 3 formatos, selector de resolución, cambiar carpeta, Descargar). No tiene servicio persistente.

## 3. Qué consolida
Este módulo absorbe las herramientas `ffmpeg` y `yt-dlp`, que ya no se ofrecen como módulos separados. La función de archivos
comprimidos (zstd, 7z) pasa a `ArchiveManager` del gestor de archivos (ver §8).

## 4. Instalación
- `modulos/media_downloader.sh` (59 líneas). Flags: `--silent`, `--force`, `--describe`, `--describe-files`.
- `command -v yt-dlp`/`ffmpeg` faltantes o `--force` → `pkg_update_with_fallback` + `pkg install -y python-yt-dlp ffmpeg` (`:46-49`).
- Verificación (`:50-53`): `command -v` y ejecución real (`yt-dlp --version`, `ffmpeg -version`) de ambos; NO usa `verify_binary_installed` pero hace el
  mismo chequeo de post-condición. `registry_install "media-downloader" "yt-dlp <ver> + ffmpeg"`.
- `--describe-files` (`:30-43`): sin archivos propios, dependencias `pkg:python-yt-dlp` y `pkg:ffmpeg`, `verify_cmd` que ejecuta ambos.
- Gap menor: `requires: ["python"]` en el JSON apunta al módulo Python de Kairos, pero el script resuelve Python vía el paquete `python-yt-dlp` de Termux (desalineado).

## 5. Ejecución (`MediaDownloaderFragment.kt`)
- Valida que la URL empiece por `http://` o `https://` (`:81-85`).
- Crea carpeta temporal `cacheDir/media-download-<ts>` y corre `yt-dlp --newline --no-playlist --no-progress <formato> -o '<tmp>/%(title).180B.%(ext)s' <url>`
  con `ManagerNativeUtils.runExec` (lista de argumentos, sin shell; timeout 3600 s, `:110-116`).
- Formatos: con sonido `bestvideo[height<=H][ext=mp4]+bestaudio[ext=m4a]/…` + `--merge-output-format mp4`; sin sonido `bestvideo[height<=H]…`; audio `-x --audio-format mp3`.
- Al terminar copia cada archivo al destino (`File` en `/storage/emulated/0/Movies` por defecto, o `DocumentFile` si se eligió árbol SAF con permiso persistente
  guardado en SharedPreferences `media_downloader`), renombrando `nombre (2).ext` si ya existe, y borra el temporal (`:117-129`, `:145-193`).
- Progreso indeterminado (no hay porcentaje: `runExec` devuelve el log al terminar).

## 6. Funciones/opciones de la UI
URL; `RadioGroup` (video con audio / sin audio / solo audio); `Spinner` de resolución (oculto en solo audio, `:65-67`); «Cambiar» carpeta
(`OpenDocumentTree`); «Descargar»; barra indeterminada; diálogo de éxito/error (últimos 1600 caracteres del log en error). Strings ES/EN (`strings_generic.xml:227-253`, `values-en`).

## 7. Archivos y rutas
`modulos/media_downloader.sh` (copia en `app/src/main/assets/scripts/media_downloader.sh`), `MediaDownloaderFragment.kt`
(ruteado en `ModuleDetailNavigator.kt:50`), `res/layout/fragment_media_downloader.xml`, destino `/storage/emulated/0/Movies` o árbol SAF, temporal en `cacheDir`.

## 8. ArchiveManager (gestor de archivos, relacionado)
`app/src/main/java/com/termux/app/util/ArchiveManager.kt`, usado solo por `FileManagerFragment.kt` (`:277,480,522,544,618,645`), no por el descargador.
- Cobertura: ZIP (Zip64 AsNeeded), 7z (LZMA2), tar.zst/.tzst (Commons Compress 1.27.1 + `zstd-jni` 1.5.6-4 en `app/build.gradle:279-282`). Funciones: `kindFor`, `list`, `compress`, `extractAll`.
- Protecciones existentes: rechazo de rutas absolutas/`..`/NUL (`safeRelativePath`, `:53`), canonicalización y chequeo de prefijo (`:111-112,311`), enlaces simbólicos y archivos
  especiales rechazados (`:151,172,291`), nombres duplicados y conflictos archivo/carpeta (`validateEntries`, `:181`), no sobrescribe, extracción en carpeta
  temporal `.kairos-extraer-<uuid>` + movimiento con rollback, compresión a `.partial` + `renameTo`, tope de 100.000 entradas (`MAX_ENTRIES`, `:39`).

## 9. Seguridad: límites que faltan
- No hay límite de tamaño total descomprimido ni de tamaño por entrada ni de ratio de compresión → vulnerable a zip-bomb (el único tope es el número de entradas).
  `entry.size` solo se usa para listar y puede declararse falso; el copiado en streaming no se corta.
- No se comprueba el espacio libre antes de extraer.
- `tar.zst` se descomprime por completo dos veces (listar y extraer); no hay cota de memoria/tiempo de la ventana de zstd.
- No hay cancelación ni progreso de compresión/extracción; sin soporte de contraseñas/cifrado.
- Descargador: no restringe hosts, ni usa cookies; ejecuta `yt-dlp` con la URL del usuario como argumento (sin shell, sin inyección). El nombre de archivo lo fija `-o`; no se
  sanea más allá de `yt-dlp`. Sin secretos.

## 10. Tests existentes (`app/src/test/java/com/termux/app/util/ArchiveManagerTest.kt`, 70 líneas, 3 tests JUnit)
1. `safeRelativePathRejectsAbsoluteAndTraversalEntries` (rutas `../`, `/abs`, `C:\`, backslashes).
2. `zipAndSevenZCompressionCanBeListedAndExtracted` (round-trip ZIP y 7z).
3. `zipSlipArchiveIsRejectedWithoutWritingOutsideDestination`.
Sin tests para tar.zst, enlaces simbólicos, duplicados, rollback, zip-bomb ni para `MediaDownloaderFragment`.

## 11. Problemas / gaps
- Sin cancelar descarga, sin progreso real, sin actualizar `yt-dlp` desde la UI; `--no-playlist` fijo (no descarga listas).
- Audio solo MP3; `ext=mp4`/`m4a` en el selector puede degradar la calidad en sitios sin esos contenedores (cae a `best`).
- Sin verificar en dispositivo: permisos de escritura en `/storage/emulated/0/Movies` (Android 11+, targetSdk 28).
