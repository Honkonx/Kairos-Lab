# DESCARGA_E_INSTALACION.md — los 3 mecanismos de descarga/instalación de Kairos

> Kairos tiene 3 sistemas de descarga/instalación completamente independientes, cada uno con
> su propia lógica y sus propias garantías. Este doc los compara uno al lado del otro —
> para el detalle técnico completo de cada uno, ver el doc dedicado citado en cada sección.

## 1. Rootfs — paquetes base de Termux

Ver `docs/bootstrap/rootfs-embebido.md` para el mecanismo completo (extracción, `apt install`
real, comprobación de actualizaciones). Resumen de las 2 variantes de build:

| Variante | Workflow | Cómo llega el rootfs | Requiere red en el wizard |
|---|---|---|---|
| Liviana | `build-app.yml` | `RootfsInstaller.kt` lo descarga en runtime desde una GitHub Release | Sí |
| Con rootfs embebido | `build-app-rootfs.yml` | Ya viene dentro del APK como asset, `RootfsInstaller.kt` solo extrae | No |

**Por qué la variante liviana puede fallar para un usuario final**: si el repositorio de origen
del rootfs es privado, GitHub responde 404 a cualquier descarga de asset de Release sin
autenticar. El build con rootfs embebido (`build-app-rootfs.yml`, corre en CI) sí tiene un token
disponible durante el build y lo usa para autenticar la descarga — pero `RootfsInstaller.kt`
corre en el dispositivo de un usuario real, sin ningún token disponible (embeber uno en el APK
sería extraíble/abusable, descartado a propósito). Mientras el repositorio de origen del rootfs
siga siendo privado, la variante liviana va a fallar con 404 en runtime — no es un bug
intermitente, es un límite arquitectónico conocido (ver `RootfsInstaller.kt` y
`rootfs-embebido.md`, sección "Repo privado y token de acceso").

**Flujo del wizard** (`WizardInstallFragment.kt`): corre `kairos.sh` (bootstrap base), intenta
el rootfs (embebido o descarga según la variante del build), y si falla cae al comportamiento de
siempre — `pkg install` normal, paquete por paquete, sin bloquear el resto de la instalación.

## 2. Instalación de módulos — checkpoints y el gap real del `--force`

`ModuleController.installModule(moduleId, variant, onProgress, onComplete)` — corre
`bash $HOME/scripts/install/<módulo>.sh --silent [--variant <variant>]` vía `ProcessBuilder`,
logueando todo a `~/kairos_logs/install_<módulo>.log` (nunca muestra el output crudo en
pantalla, solo un spinner — ver `BottomSheetInstalacion.kt`). Si el proceso termina con un
exit code de señal (segfault, killed), lo decodifica a un nombre legible (`decodeExitSignal()`)
en vez de mostrar solo el número.

La lógica de **checkpoints** (qué pasos ya se hicieron, para no repetirlos en cada
reinstalación) vive DENTRO de cada `modulos/<módulo>.sh` — `ModuleController` no sabe nada de
eso, solo corre el script y espera el resultado.

**Gap conocido**: `installModule()` no tiene ningún parámetro para pasar `--force`, aunque
varios scripts de `modulos/` ya soportan ese flag internamente para decidir entre "saltar el
checkpoint existente" o "reinstalar de cero". Sin ese parámetro, tocar "Actualizar/Reinstalar"
en un módulo ya instalado puede no tener efecto real — el script ve el checkpoint, se salta
todo, y reporta éxito igual. Tampoco existe todavía una vía de "actualizar sin reinstalar todo"
para la mayoría de los módulos — resolver el `--force` sin esa vía de actualización incremental
solo cambiaría "no hace nada" por "reinstala todo desde cero cada vez".

## 3. Descarga de modelos de IA — 2 sistemas separados, 2 motores distintos

Kairos tiene DOS motores de inferencia completamente independientes, cada uno con su propio
mecanismo de descarga de modelos — nunca comparten código ni UI:

### 3a. Ollama — catálogo curado + streaming real

`ModelsFragment.kt` muestra un catálogo curado de modelos verificados más un campo de texto
libre "(avanzado)" para cualquier otro nombre de modelo. La descarga real la hace
`OllamaApiClient.pullModel(name, onProgress)` — pide `POST /api/pull` con `"stream": true`
contra el servidor Ollama local (`127.0.0.1:11434`), y parsea cada línea de la respuesta
(streaming NDJSON) para extraer `completed`/`total` y calcular velocidad/ETA reales. El modelo
queda gestionado por el propio Ollama (en su directorio interno, no en el storage de la app).

### 3b. llama.cpp — GGUF manual, storage privado de la app

`LocalAIFragment.kt` (motor local, `llama-engine/`, ver `llama-cpp-local-engine.md`) no tiene
catálogo cerrado — el usuario también puede pegar una URL directa a un archivo `.gguf`
(típicamente de Hugging Face). `LocalModelManager.downloadModel(context, url, fileName,
onProgress)` descarga a `context.filesDir/models/` (storage PRIVADO de la app — no `$HOME` de
Termux, independiente del rootfs) con:
- Progreso real con velocidad/ETA.
- Validación multi-capa antes de aceptar la descarga: tamaño mínimo vs. `Content-Length`
  declarado (ratio ≥95%, no exige coincidencia exacta porque no todos los servidores mandan
  el header correcto), y verificación del **magic header GGUF real** (primeros 4 bytes =
  `"GGUF"` literal) — esto evita aceptar descargas cortadas a mitad de camino, o una página de
  error HTML devuelta con HTTP 200 como si fuera un modelo válido.
- Limpieza automática de archivos `.part` huérfanos (descargas canceladas/interrumpidas).

**Por qué son 2 sistemas separados y no uno solo**: Ollama gestiona sus propios modelos
internamente (formato propio, servidor HTTP propio) — Kairos solo le pide que descargue.
llama.cpp embebido no tiene servidor ni gestor propio — el archivo `.gguf` se carga
directamente en el proceso de la app vía JNI, así que Kairos tiene que manejar la descarga y
el storage él mismo. Compartir código entre los dos no tendría sentido real, más allá de
patrones de UX similares (spinner, velocidad/ETA) que sí se replicaron a propósito.
