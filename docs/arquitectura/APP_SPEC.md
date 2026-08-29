# APP_SPEC.md — Especificación de Kairos

**Versión actual:** `0.118.0` (`versionCode` 118, ver `app/build.gradle`)
**APK:** compilado vía GitHub Actions/GitLab CI, o localmente (ver `BUILD.md`).

---

## Qué es

Fork de termux-app (github.com/termux/termux-app) que unifica en un solo APK:
- **Motor Termux** (Java) — sesiones bash reales, procesos, bootstrap APT,
  terminal VT100 (`terminal-emulator/` NDK C + `terminal-view/`).
- **UI nativa** (Kotlin, `app/src/main/java/com/termux/app/ui/`) — dashboard de
  módulos de IA local, chat, monitor del sistema, configuración, gestor de archivos,
  editor de código, túneles, y una "nube" personal de almacenamiento.

Sin React Native — toda la UI es nativa Java/Kotlin. Sin root. Los modelos de IA corren
localmente (Ollama nativo o llama.cpp embebido vía NDK), sin depender de internet en
runtime salvo para la descarga inicial de modelos. Objetivo `arm64-v8a` únicamente.

---

## Restricciones fijas de plataforma

| Parámetro | Valor real (`gradle.properties`/`build.gradle`) | Motivo |
|-----------|-------|--------|
| `targetSdkVersion` | 28 | Valores superiores bloquean `exec()` de shells en Android 10+ |
| `minSdkVersion` | 26 | Android 8.0 mínimo |
| `compileSdkVersion` | 36 | Puede subir, `targetSdk` no |
| `NDK` | 29.0.14206865 (r29) | Requerido por `terminal-emulator` C y `llama-engine/` |
| `AGP` | 8.13.2 | `com.android.tools.build:gradle` en `build.gradle` raíz |
| `Gradle` | 9.2.1 | `gradle/wrapper/gradle-wrapper.properties` |
| `Kotlin` | 2.2.21 | |
| `Java` | 17 | Engine y compilación |
| `sharedUserId` | `com.termux` | Preservado para compatibilidad con permisos/paquetes del Termux original (ver `AndroidManifest.xml`) |

---

## Arquitectura de directorios

```
kairos/
├── app/                              ← todo el trabajo de la app va acá
│   └── src/main/
│       ├── assets/
│       │   ├── modules.json          ← definición del catálogo de módulos
│       │   └── scripts/              ← copia embebida de modulos/*.sh (fallback offline)
│       ├── java/com/termux/app/
│       │   ├── TermuxActivity.java       ← Activity principal, overlay terminal, FAB, modo adaptado
│       │   ├── TermuxService.java        ← Foreground service, sesiones bash reales
│       │   ├── TermuxInstaller.java      ← Bootstrap APT (primera vez)
│       │   ├── TermuxApplication.java    ← Entry point, arranca ModuleEventBridge
│       │   ├── ModuleController.kt       ← Instala/arranca/detiene módulos vía ProcessBuilder
│       │   ├── ui/                       ← Fragments por módulo + CliToolFragment
│       │   │                                genérico para CLI tools + GenericModuleFragment
│       │   │                                fallback + pantallas core (ver APP_SCREENS.md)
│       │   ├── wizard/                   ← WizardActivity.java + fragments (ViewPager2)
│       │   ├── terminal/                 ← TermuxTerminalSessionActivityClient.java
│       │   └── util/                     ← Helpers (BatteryRestrictionHelper, PhantomProcessKillerHelper, RootfsInstaller, BackupManager, etc.)
│       ├── java/com/termux/rn/           ← Bridge legacy (BridgeSingleton, SessionInfo)
│       └── res/
│           ├── layout/activity_kairos.xml    ← BottomNav + FAB + fragment container
│           ├── layout/activity_termux.xml    ← Terminal overlay (normal + modo adaptado)
│           ├── menu/bottom_nav_menu.xml      ← tabs principales
│           ├── menu/more_nav_menu.xml        ← pantallas del menú "Más"
│           └── values/colors_kairos.xml      ← Sistema de diseño (ver abajo)
├── llama-engine/                     ← Módulo NDK, llama.cpp embebido
├── terminal-emulator/                ← NDK C, VT100 (heredado de termux-app)
├── terminal-view/                    ← Android widget (heredado de termux-app)
├── termux-shared/                    ← Utilidades compartidas (heredado de termux-app)
├── modulos/                          ← Scripts reales que instalan/arrancan cada módulo (bash)
├── tools/rootfs/                     ← `build_rootfs.py` — arma el rootfs embebido (ver ROOTFS_EMBEBIDO.md)
├── .github/workflows/                ← workflows de CI (ver BUILD.md)
└── docs/                             ← esta documentación
```

---

## UI — Navegación

### Bottom navigation

```
⊞ Módulos  |  ◈ Chat IA  |  ◉ Sistema  |  ⚙ Config  |  ⋯ Más
```

`BottomNavigationView` tiene un límite duro de 5 ítems (límite real de la librería, no una
sugerencia). El 5º ítem ("Más") abre un menú con las pantallas que no entran en la barra.

### Menú "Más"

| id | Título | Fragment |
|---|---|---|
| `nav_monitor` | Monitor | `MonitorFragment.kt` |
| `nav_files` | Archivos | `FileManagerFragment.kt` |
| `nav_tunnel` | Túnel | `TunnelFragment.kt` |
| `nav_procesos` | Procesos | `ProcesosFragment.kt` |
| `nav_local_ai` | IA Local | `LocalAIFragment.kt` |
| `nav_nube` | Nube | `NubeFragment.kt` |

Ver `docs/arquitectura/APP_SCREENS.md` para el detalle de cada una.

### Wizard (primer arranque)

`ViewPager2` + `FragmentStateAdapter`, con varias pantallas secuenciales:

1. `WizardWelcomeFragment` — bienvenida.
2. `WizardPermissionsFragment` — permisos de Android (almacenamiento, notificaciones).
3. `WizardPhantomProcessFragment` — ayuda opcional para desactivar el límite de procesos
   fantasma de Android 12+ (auto-detección vía ADB inalámbrico, o guía manual).
4. `WizardBatteryFragment` — ayuda opcional para quitar restricciones de batería.
5. `WizardInstallFragment` — bootstrap Termux + rootfs (embebido o descarga runtime) + script
   de configuración inicial, con progreso en vivo paso a paso.
6. `WizardCheckFragment` — comprobación final opcional, "Comprobar y actualizar" u "Omitir".

Ver `docs/bootstrap/ROOTFS_EMBEBIDO.md` para el diseño del rootfs embebido.

---

## Sistema de módulos

**`app/src/main/assets/modules.json`** define el catálogo completo de módulos disponibles:
herramientas de IA local y agentes de código (Ollama, llama.cpp/`llamaserver`, Claude Code,
Codex CLI, OpenCode, OpenClaw, Kilo, Kimi, y otros CLIs de IA), automatización (n8n), un
entorno de escritorio Linux embebido (`entorno`/Mini PC, proot-distro), acceso remoto
(`remote`, SSH + túneles), bases de datos (`db`), contenedores (`docker`, `udocker`),
virtualización (`qemu`), un IDE integrado (`ide`), y utilidades generales (Python, gestor de
paquetes, herramientas de seguridad, etc.). Cada entrada tiene esta forma:

```json
{
  "id": "ollama", "name": "Ollama", "description": "...",
  "script": "ollama.sh",
  "icon": "⬡", "iconBg": "#1A4A2E",
  "port": "11434", "size": "~850MB", "type": "Nativo",
  "estimate": "~2 min", "requiresProot": false,
  "hasVariants": true, "hasSwitch": true,
  "tmuxSession": "ollama-server"
}
```

- **`hasSwitch`**: si el módulo tiene un proceso de servidor real (Ollama, n8n, OpenClaw,
  OpenCode, Remote...) vs. herramientas CLI sin servidor propio (Python, Claude Code, Codex,
  Antigravity CLI, Hermes, Expo) — estas últimas no tienen switch ON/OFF, solo "abrir
  terminal".
- **`requiresProot`**: si depende de proot-distro (Debian) en vez de correr nativo.
- **`ModuleController.kt`** es la fuente de verdad para instalar (`installModule()`),
  arrancar/detener (`startModule()`/`stopModule()`) y verificar estado real
  (`isRunning()`, `waitForPortOpen()` — poll TCP real tras un exit code exitoso, no
  confía solo en el checkpoint del script).
- **Registry**: `~/.android_server_registry`, formato `modulo.clave=valor`, leído/escrito
  por los propios scripts de `modulos/*.sh` — la UI lo lee, no lo escribe directo.

Ver `docs/modulos/` para el detalle completo por módulo (permisos, instalación,
opciones, detección).

---

## Sistema de diseño — 3 temas seleccionables

Kairos tiene **3 temas seleccionables** (Config → 🎨 Tema). El código nunca referencia
colores fijos — todo pasa por **atributos de tema** (`?attr/kairosX` en XML,
`ctx.kairosThemeColor(R.attr.kairosX)` en Kotlin, ver
`app/src/main/java/com/termux/app/util/KairosThemeColors.kt`), resueltos en runtime por el
estilo activo (`app/src/main/res/values/themes_kairos.xml`:
`Theme.Kairos.Oscuro`/`Theme.Kairos.Senal`/`Theme.Kairos.Claro`). La selección persiste en
`SharedPreferences` (`KairosThemePrefs.kt`) y se aplica con `setTheme()` antes de
`super.onCreate()` en `TermuxActivity`.

**Oscuro** (por defecto — `colors_kairos.xml`):

| Token (atributo) | Hex | Uso |
|-------|-----|-----|
| `kairosBg` | `#050505` | Fondo principal |
| `kairosBg2` | `#0A0A0A` | Cards |
| `kairosBg3` | `#111111` | Ítems de lista |
| `kairosBgElevated` | `#1A1A1A` | Elementos elevados (diálogos) |
| `kairosBgSurface` | `#0D0D0D` | Superficies |
| `kairosNavBg` | `#080808` | Fondo de la barra de navegación |
| `kairosText` | `#E8E8E8` | Texto primario |
| `kairosText2` | `#888888` | Texto secundario |
| `kairosText3` | `#555555` | Labels, subtítulos |
| `kairosBlue` | `#3B82F6` | Acento info |
| `kairosGreen` | `#22C55E` | Acento éxito/activo |
| `kairosRed` | `#EF4444` | Error/peligro |
| `kairosAmber` | `#F59E0B` | Advertencia |
| `kairosBorder` / `kairosDivider` | `#1F1F1F` / `#151515` | Bordes/separadores |
| `kairosStatusRunning`/`Stopped`/`Installing`/`Error`/`NotInstalled` | ver arriba | Estado visual por módulo — también pinta el badge circular superpuesto en el ícono de cada fila |

**Señal** (cian-teal frío, `colors_kairos_senal.xml`) — mismos tokens, paleta distinta: fondo
`#0A0E14`→`#1B222E`, texto `#E4EAF2`/`#7C8B9E`/`#4A5568`, acentos `kairosBlue=#4FD1C5` (cian, no
azul puro), `kairosGreen=#48BB78`, `kairosRed=#F56565`, `kairosAmber=#ECC94B`.

**Claro** (`colors_kairos_claro.xml`, modo claro real — no una simple inversión) — mismo set de
tokens con fondos claros y `android:windowLightStatusBar`/`windowLightNavigationBar` activados
en el estilo.

### Componentes UI reusables (`BaseModuleFragment.kt`)

Heredados por la mayoría de los fragments de módulo, reemplazan grupos de botones mutuamente
excluyentes por controles más compactos:

| Componente | Uso | Ejemplo real |
|---|---|---|
| `dropdownSwitchRow()` | Elegir 1 de N opciones + encendido/apagado que bloquea el dropdown mientras está ON | n8n (🏠 local / 🌐 Cloudflare + switch), OpenCode (puerto 3000/4096 + switch) |
| `switchRow()` | Encendido/apagado simple, sin opciones | Remote (SSH, túnel Cloudflare), OpenClaw (gateway), Db (MySQL/PostgreSQL/Redis — 3 switches independientes), Ollama, Hermes Gateway, LlamaServer |
| `dropdownRow()` | Elegir 1 de N opciones que NO son un toggle binario (sin switch) | Ciberseguridad (acciones de sqlmap), Hermes (proveedor IA local: Ollama vs llama-server) |

---

## Convenciones de código

**Java (engine heredado de Termux):** sin lambdas, `this.` explícito, `@Override` en
todo método sobreescrito, sin dependencias externas al SDK Android.

**Kotlin (UI y utilidades):** idiomático, `if (!isAdded) return` antes de cualquier
`requireActivity()`/`requireContext()` en callbacks async (guard estándar del proyecto
contra crashes de fragment-detached), I/O de archivos siempre en `Thread` separado con
`runOnUiThread`/`Handler.post()` para actualizar UI.

**Interop Kotlin↔Java:** cualquier función de un `object` Kotlin llamada desde `.java`
necesita `@JvmStatic` (si no, solo existe como método de instancia en `INSTANCE`) y
`@JvmOverloads` si tiene parámetros con default (si no, Java no ve el overload de menor
aridad). Cuando un parámetro con default se agrega en el medio de una firma (no al final),
`@JvmOverloads` no genera el overload que Java necesita — hay que agregar un overload manual
explícito.

**XML layouts:** snake_case, `@+id/` prefix, evitar dp hardcodeado.

---

## Build

```bash
./gradlew :app:assembleDebug
```

Ver `docs/arquitectura/BUILD.md` para el detalle completo de los workflows de CI y el build
local.

---

## Estado de implementación

| Componente | Estado |
|-----------|--------|
| UI Módulos + hoja de instalación | ✅ Funcional |
| Sistema de switches start/stop | ✅ Funcional (`ModuleController.kt`, con `waitForPortOpen()`) |
| Chat (motor Ollama + motor llama.cpp separados) | ✅ Funcional |
| Sistema/Monitor/Config/Archivos/Túnel/Procesos/IA Local/Nube | ✅ Funcional |
| Terminal overlay + modo adaptado (barras + sidebar) | ✅ Funcional |
| Wizard primer arranque | ✅ Funcional |
| Rootfs embebido (opcional) + descarga runtime | ✅ Funcional |
| llama.cpp NDK (`llama-engine/`) | ✅ Funcional |
| Fix del phantom process killer (Android 12+) | ✅ Funcional — 3 vías (guiada/beta/manual) |

---

## Ver también

- `docs/arquitectura/APP_SCREENS.md` — cada pantalla real, en detalle.
- `docs/modulos/` — documentación por módulo.
- `docs/bootstrap/ROOTFS_EMBEBIDO.md` — mecanismo de rootfs.
- `docs/ia-local/LLAMA_CPP_EMBEBIDO.md` — módulo llama.cpp.
