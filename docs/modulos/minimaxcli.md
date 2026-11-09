# MiniMax CLI — Módulo de Kairos

> Leído de `modulos/minimaxcli.sh` (183 líneas), `app/src/main/assets/modules.json`,
> `CliToolFragment.kt` (config `"minimaxcli"` líneas 184-196), `ModuleController.kt:917`.
> Confirmado = visto en código; Sin verificar = inferencia sin dispositivo.

## 1) Qué es y para qué sirve
CLI multimodal de MiniMax (`mmx`, paquete npm `mmx-cli`): chat de texto, imagen, síntesis de voz y consulta de cuota.

## 2) Clasificación: **COMPLEMENTO/EXTENSIÓN**
Sin Fragment propio: `ModuleDetailNavigator.kt:54` → `CliToolFragment` parametrizado. Sin estado propio ni servicio. Instala la herramienta, abre terminal y envía comandos `mmx` de una línea. **~10 controles de UI** (el más cargado de los CLIs chicos de este grupo, pero todo son lanzadores de terminal).

## 3) Instalación (`modulos/minimaxcli.sh`)
- Flags (`:43-46`): `--silent`, `--force`, `--describe`, `--describe-files`. Sin variantes.
- Dependencia: `nodejs-lts` (PASO 1 `:124`). `npm install -g mmx-cli@latest` (`MINIMAX_PKG` `:86`, PASO 2 `:146`) → `fix_npm_shebang_wrapper mmx mmx-cli` → `verify_binary_installed mmx` (`mmx --version`, `error` si no ejecuta, `:153`).
- **Sin sha256**; integridad solo por npm.
- Registry: `minimaxcli.installed/version/channel=npm/install_date` (`:174-178`). "Ya instalado" → `exit 0` sin registry. PASO 3 (API key) es solo un aviso: configurar con el asistente de `mmx` (`:162-167`).

## 4) Ejecución
`terminalCommand:"mmx"`. `needsGlibcCompat=false` (shim npm puro, necesita `LD_PRELOAD` activo). Sin proceso de fondo.

## 5) Controles de UI
Estado · Abrir en terminal (¿dónde abrir?) · Gestionar proyectos · **Iniciar sesión** (`mmx auth login`) · **Enviar prompt** (`mmx text chat --message '<prompt>'`) · **Generar imagen** (`mmx image '<prompt>'`) · **Sintetizar voz** (`mmx speech synthesize --text '<texto>' --out ~/minimax_speech.mp3`) · **Cuota** (`mmx quota`) · Actualizar · Diagnosticar · Desinstalar (`npm uninstall -g mmx-cli`, `ModuleController.kt:917`).

## 6) Archivos y rutas
Paquete npm global `mmx-cli` bajo `$(npm root -g)`, wrapper `$PREFIX/bin/mmx`, salida de voz `~/minimax_speech.mp3` (fija, se sobrescribe cada vez), checkpoint `~/.install_minimaxcli_checkpoint`. Clave/credenciales: las guarda el CLI (ubicación no verificada).

## 7) Seguridad
- Texto de prompt escapado con `shellEscape` antes de interpolar.
- La API key la maneja `mmx` (login interactivo); Kairos no la almacena ni la muestra (no hay código que la lea).
- Instalación npm sin pin ni hash.

## 8) Problemas / gaps
- La voz siempre escribe `~/minimax_speech.mp3` fijo (sin elegir ruta ni reproducir desde la UI); la imagen se genera en el cwd de la terminal sin ruta controlada.
- Los subcomandos `mmx image`, `mmx speech synthesize --out`, `mmx quota` provienen del comentario del config (contrastado contra github.com/MiniMax-AI/cli); no se ejecutaron y el módulo no tiene verificación en dispositivo (su validez actual es sin verificar).
- Metadata de `modules.json` mínima (`size ~50MB` estático, `catalogVersion:"0.x"`).
- Salidas (texto/imagen/voz) se ven solo en la terminal; no hay visor/reproductor nativo en la UI.

## 9) Fecha de alta
`modulos/minimaxcli.sh`: **2026-08-12**.
