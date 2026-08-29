# ARCHITECTURE.md — Kairos

> Referencia pantalla por pantalla (qué muestra cada tab/fragment, qué está conectado a lógica
> real y qué no todavía): `docs/arquitectura/APP_SCREENS.md`.

## Stack real

La navegación principal es de **5 tabs + un menú "Más" con 6 pantallas extra**
(Monitor/Archivos/Túnel/Procesos/IA Local/Nube).

```
APK
├── UI: Kotlin/Java Fragments (BottomNavigationView, 5 tabs + menú "Más", 6 pantallas extra)
├── Bridge de terminal: TermuxBridgeAdapter.kt — SOLO sesiones (getSessions/createSession/
│   writeToSession/resizeSession/killSession), implementa la interfaz TermuxBridge de
│   com.termux.rn (bridge heredado del engine, instanciado por TermuxService). NO controla
│   módulos.
├── Control de módulos: ModuleController.kt — objeto Kotlin llamado directo desde los
│   Fragments (sin capa de bridge/interfaz), ProcessBuilder → scripts bash. Ver BRIDGE_API.md.
├── Bridge módulo→app: ModuleEventBridge.kt — hilo en background que lee un FIFO real
│   ($HOME/.kairos_events) y dispara notificaciones Android, para que los scripts avisen a la
│   app en tiempo real sin polling.
├── Engine: Termux Java (TermuxService, TermuxActivity, TermuxInstaller)
│   ├── Service: TermuxService (foreground, sesiones bash, procesos)
│   ├── Terminal: TerminalView + terminal-emulator (NDK C, VT100)
│   └── Bootstrap: APT package manager, $PREFIX
└── Datos: ModuleRegistry.kt lee $HOME/.android_server_registry (Map<String,String> plano,
    keys reales tipo `<modulo>.installed`/`<modulo>.version` — el estado RUNNING no se
    persiste ahí, se calcula en vivo vía ModuleController.isRunning())
```

## Flujos

### UI → Servicio (comando)
```
Fragment
  → TermuxBridgeAdapter.kt
    → ProcessBuilder
      → bash script
```

### Estado de módulos (polling)

`ModuleRegistry.kt` no expone ningún método de "poll" propio — solo `load()`/`get()`/
`getModules()`/`isProotInstalled()`. El polling es responsabilidad de cada Fragment por
separado, no de un mecanismo central:

```
Fragment.startPolling()          ← Handler local, ej. cada 5s (MonitorFragment)
  → ModuleController.isRunning() / ModuleRegistry().load().get()
    → Fragment UI (directo, sin handler.post central)
```

### Terminal
```
TerminalView
  ↔ TermuxSession (pty)
    ↔ bash
```

## Módulos del engine

### terminal-emulator
Librería C NDK con emulación VT100/xterm.
- `TerminalEmulator.java` — wrapper JNI
- `JniTerminal.c` — implementación nativa
- Escape sequences, scrollback, colors, UTF-8

### terminal-view
Widget Android SurfaceView para dibujar el terminal.
- `TerminalView.java` — View principal
- `TranscriptScreen.java` — buffer scrollback
- `ExtraKeysView.java` — teclas extra (Tab, Ctrl, Esc)

### termux-shared
Utilidades compartidas del engine.
- `TermuxConstants.java` — rutas, nombres
- `TermuxShellManager.java` — CRUD de sesiones
- `TermuxShellUtils.java` — helpers shell
- `TermuxAppSharedPreferences.java` — preferencias
- `TermuxBootstrap.java` — bootstrap APT

### app (módulo principal)
- `TermuxActivity.java` — Activity principal (BottomNav + FAB + fragments)
- `TermuxService.java` — Foreground service (sesiones bash)
- `TermuxApplication.java` — Application entry point
- `TermuxInstaller.java` — Instalación bootstrap
- `TermuxBridgeAdapter.kt` — Bridge de sesiones de terminal (ver aclaración arriba)
- `ModuleController.kt` — Control real de módulos (install/start/stop/isRunning), sin capa de bridge
- `util/ModuleEventBridge.kt` — Bridge de eventos módulo→app (FIFO)
- `ui/` — decenas de clases Fragment (Módulos, Chat IA, Sistema, Config, Monitor, Archivos,
  Túnel, Procesos, IA Local, Nube, Plugins, un IDE integrado, además de fragments de detalle
  dedicados por módulo + `CliToolFragment` genérico para herramientas CLI +
  `GenericModuleFragment` como fallback para el resto del catálogo — ver `docs/modulos/`)
- `data/ModuleRegistry.kt` — Lector del registry de módulos ($HOME/.android_server_registry)
- `model/ModuleInfo.kt` — Data class real (id/name/icon/port/hasSwitch/tmuxSession/...) +
  `enum Status { NOT_INSTALLED, INSTALLED_STOPPED, RUNNING, INSTALLING, ERROR }`

## Patrones de optimización

### Polling de módulos proot
Cada invocación `proot-distro login debian` tarda 3-5s en ARM64. Para evitar bloqueos:

- **Agrupar checks:** un solo proceso proot detecta múltiples módulos (ej: OpenCode + OpenClaw juntos) y devuelve resultados separados.
- **Caché de 3 niveles:** (1) variable en memoria (TTL 30s), (2) archivo en disco (TTL 5min), (3) consulta real a proot.
- **Paralelizar:** los checks de módulos independientes corren en threads paralelos con `wait()`.

### Módulos nativos (sin proot)
Los módulos que corren en Termux nativo (Ollama, Python, SSH) se verifican con `pgrep` o `tmux has-session` — son instantáneos (<10ms) y no requieren caché.

---

## Comunicación entre módulos

Llamadas directas Java/Kotlin en el mismo proceso — sin HTTP, sin JS, sin IPC.

| Origen | Destino | Mecanismo |
|--------|---------|-----------|
| Fragment (terminal) | TermuxService | TermuxBridgeAdapter → método directo (getSessions/write/resize/kill) |
| Fragment (módulos) | bash | ModuleController.kt → ProcessBuilder → script (sin pasar por TermuxBridgeAdapter) |
| Fragment | Registry | ModuleRegistry → file I/O bajo demanda (no hay polling automático central — cada Fragment relee cuando corresponde) |
| Script bash | App | ModuleEventBridge → FIFO ($HOME/.kairos_events) → notificación Android |
| TerminalView | bash | PTY master ↔ slave fd |
