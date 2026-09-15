# Servidor X11 embebido

Kairos incluye un servidor X11 completo corriendo dentro del propio APK — no depende de una
app externa ni de un servidor VNC de terceros. Es lo que permite tener un escritorio Linux
gráfico (XFCE4, MATE) directo en el teléfono, tanto en modo nativo sobre Termux como dentro de
una distro completa vía `proot-distro`.

## Origen del código

El módulo `x11-server/` es un fork de [afeimod/linbox](https://github.com/afeimod/linbox)
(a su vez basado en [termux/termux-x11](https://github.com/termux/termux-x11), el proyecto
Xlorie/termux-x11 upstream) — se conserva el árbol de paquetes real `com.termux.x11.*` y el
binario nativo precompilado `libXlorie.so` por ABI (`x11-server/libs/<abi>/libXlorie.so`).

## Arquitectura

El servidor corre en un **proceso Android separado** dentro del mismo APK
(`android:process=":xserver"`, declarado en el manifest de la app) — no es un proceso nativo
independiente como `Xorg` en Linux, es un servicio Android normal que hospeda el binario X11
nativo.

```
Proceso principal (com.termux)          Proceso :xserver
┌─────────────────────────┐             ┌──────────────────────────┐
│ X11Service.start()       │  Intent     │ X11Service.onCreate()     │
│  → startForegroundService│ ──────────▶ │  → Thread "x11-server"    │
│                           │             │     Looper.prepare()      │
│ MainActivity (visor,      │             │     CmdEntryPoint.main([":1"])
│ com.termux.x11.*)         │◀── socket ──│       → libXlorie.so       │
│  se conecta al display    │  X11        │       (Xorg embebido)      │
│  vía ACTION_START         │             └──────────────────────────┘
└─────────────────────────┘
```

- `X11Service.kt` (`app/src/main/java/com/termux/app/X11Service.kt`) es el punto de entrada:
  `X11Service.start(context)`/`X11Service.stop(context)` arrancan y detienen el servicio.
- Al crearse, arranca un `Thread` dedicado que llama a `Looper.prepare()` seguido de
  `CmdEntryPoint.main(arrayOf(":1"))` — el bloque estático de `CmdEntryPoint` carga
  `libXlorie.so` vía `System.loadLibrary("Xlorie")`, y el `main()` nativo corre el servidor X
  completo dentro de ese proceso.
- El display usado es `:1` (constante `X11Service.DISPLAY`).
- El visor (`com.termux.x11.MainActivity`, accesible desde el menú de la app) se conecta al
  servidor escuchando el broadcast `ACTION_START`, que `CmdEntryPoint` reemite cada segundo
  hasta que hay una conexión activa.
- El hilo del servidor queda vivo mientras el servicio corre porque `CmdEntryPoint.main()`
  entra en un `Looper.loop()` bloqueante.

## Resolución del socket X11 (`TMPDIR`)

El binario nativo (`cmdentrypoint.c`, compilado dentro de `libXlorie.so`) resuelve el socket
Unix del display en `$TMPDIR/.X11-unix/X<display>`. Si `TMPDIR` no está seteado explícitamente,
el propio binario intenta un fallback interno (`/tmp`, y si eso falla, `$PREFIX/tmp`) — pero en
un proceso Android "bare" (el proceso `:xserver`, sin pasar por el shell normal de Termux) no
hay garantía de qué devuelve esa resolución en cada dispositivo. Para eliminar la ambigüedad,
`X11Service` fuerza `TMPDIR` explícitamente antes de que el código nativo lo lea:

```kotlin
Os.setenv("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp", true)
```

Se usa `android.system.Os.setenv()` (wrapper directo de `setenv(3)` a nivel de proceso) en vez
de `System.getenv()`/`ProcessBuilder.environment()`, porque solo `Os.setenv()` es visible para
`getenv()` en código nativo JNI que corre en el mismo proceso. Esto asegura que tanto un
escritorio nativo (`startxfce4` directo sobre Termux) como uno dentro de una distro
(`proot-distro` con `--shared-tmp`, que mapea `$PREFIX/tmp`) encuentren el mismo socket.

## Estabilidad en background

`X11Service` corre como servicio en primer plano (`startForeground`) con notificación
persistente, y adquiere un `PowerManager.PARTIAL_WAKE_LOCK` mientras el proceso `:xserver` está
vivo — sin esto, mecanismos de Android como el Phantom Process Killer (Android 12+) o Doze
pueden matar el proceso en background sin previo aviso. El wake lock se libera en `onDestroy()`;
no lleva timeout porque el ciclo de vida completo ya está gobernado por `start()`/`stop()`.

## Escritorios soportados

El servidor X11 embebido es el transporte gráfico común a dos modos de uso distintos:

- **Modo nativo** — un entorno de escritorio (XFCE4, MATE) corriendo directo sobre los binarios
  de Termux, sin una distro Linux completa de por medio.
- **Modo distro** — un entorno de escritorio corriendo dentro de una distro Linux completa
  (`proot-distro`, Debian/Ubuntu/Arch), conectándose al mismo servidor X vía el socket
  compartido descrito arriba.

Un visor VNC (`VncViewerActivity`/`VncCanvasView`) está disponible como alternativa al visor X11
nativo para quien prefiera esa vía de conexión. `VncClient.kt` implementa el protocolo RFB
(RFC 6143) de forma nativa en Kotlin — handshake completo, autenticación VNC por desafío DES, y
encoding **Raw** (sin compresión Hextile/Tight) — suficiente porque cliente y servidor corren en
el mismo dispositivo, conectados por loopback (`127.0.0.1:5901`), donde el ancho de banda no es
el cuello de botella.

## Referencias upstream

| Proyecto | Aporte |
|---|---|
| [termux/termux-x11](https://github.com/termux/termux-x11) | Proyecto Xlorie/termux-x11 original |
| [afeimod/linbox](https://github.com/afeimod/linbox) | Fuente real del fork usado en `x11-server/` |
| [sabamdarif/termux-desktop](https://github.com/sabamdarif/termux-desktop) | Patrones de storage compartido y aceleración GPU por distro |
| [LinuxDroidMaster/Termux-Desktops](https://github.com/LinuxDroidMaster/Termux-Desktops) | Cierre de sesión de escritorio, arranque por distro+entorno gráfico |
</content>
