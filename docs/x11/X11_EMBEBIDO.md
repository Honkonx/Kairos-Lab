# X11 embebido — Servidor X11 (Xlorie/termux-x11) dentro del APK de Kairos

## Objetivo

Kairos puede abrir un servidor X11 completo **dentro de la misma APK**, sin necesitar instalar
el APK companion oficial de termux-x11. Desde la sección "Mini PC" del menú principal, un botón
arranca el servidor Xlorie en un proceso Android aparte (`:xserver`) del mismo APK y abre el
visor (`LorieView`/`MainActivity`).

## Origen del código

El módulo `:x11-server` de Kairos es un fork del proyecto oficial `termux/termux-x11` — más
concretamente, del fork intermedio "Linbox" (orientado a correr Wine/juegos Windows sobre
Termux), que aporta el árbol `com.termux.x11.*`, sus recursos y un `libXlorie.so` precompilado
para las 4 ABIs de Android. Se usa ese linaje porque es autoconsistente (el mismo árbol fuente
que produjo el `.so` binario) y porque el patch oficial de integración del proyecto upstream a
termux-app exige AGP 9.x, mientras que Kairos sigue en AGP 8.x.

## Módulo `:x11-server`

- Ruta: `x11-server/` (módulo Gradle independiente, namespace `com.termux.x11`).
- Contiene las clases `com.termux.x11.*` (`CmdEntryPoint`, `MainActivity`, `LorieView`,
  `LoriePreferences`, controladores de input, extra-keys, utilidades, manejador Wine), recursos,
  definiciones AIDL, y los `libXlorie.so` para cada ABI.
- Se excluye deliberadamente el árbol `cpp/` del proyecto upstream — no se compila Xorg desde
  cero, se usa el `.so` precompilado — y los assets de Wine (decenas de MB) que el visor X puro no
  necesita.

## Integración en la app principal

- El manifest de la app declara la Activity `MainActivity` de X11 (no exportada, `singleTask`,
  soporta cambios de configuración completos y picture-in-picture) y un `X11Service` (no
  exportado, corriendo en su propio proceso `:xserver`, tipo de servicio en primer plano
  `dataSync`).
- `X11Service.start(context)` arranca un `startForegroundService`, que crea el proceso `:xserver`
  separado. En su `onCreate()`, el servicio crea el canal de notificación, y lanza un hilo que
  prepara un `Looper` de Android y llama a `CmdEntryPoint.main(arrayOf(":1"))` — la función nativa
  que arranca el servidor X real y que bloquea el hilo mientras corre.
- La puerta de entrada de usuario es la sección "Mini PC" de la app: muestra el estado del
  servidor (si el proceso `:xserver` está vivo), un botón "Entrar en X11" que arranca el servicio
  y abre el visor, una pantalla de "Configuración de X11" (resolución, escala, orientación,
  teclado, táctil, picture-in-picture, pantalla completa), y un botón para cerrar el servidor
  (que emite un broadcast interno para que el visor se cierre y luego detiene el servicio).
- La conexión entre el proceso `:xserver` y el visor (proceso principal de la app) se hace vía un
  broadcast interno que transporta un binder AIDL — el visor recibe ese binder y conecta el
  socket X real.
- Al presionar "atrás" en el visor, se muestra un diálogo con dos opciones: **Minimizar** (el
  servidor sigue corriendo) o **Cerrar servidor X11** (detiene todo).

## Parches propios sobre el código heredado

1. **Carga de la librería nativa**: el código original resolvía el `.so` con
   `ClassLoader.getResource()` + `System.load(path)`, lo que exige deshabilitar la extracción de
   librerías nativas del APK. Se cambió a `System.loadLibrary("Xlorie")`, que resuelve la
   librería desde el directorio nativo estándar de la app y es compatible con el empaquetado
   normal de Android.
2. **Eliminación de APIs internas ocultas de Android**: se quitó un mecanismo de fallback que
   dependía de clases internas del framework Android no disponibles en el SDK público — no hace
   falta porque el servidor corre en el proceso propio de la app, donde el envío de broadcasts
   normal siempre funciona.
3. **Obtención de contexto Android por reflexión**: una clase interna del framework
   (`ActivityThread`) se accede vía reflexión en vez de un cast directo, porque no forma parte de
   la API pública documentada — funciona igual en runtime.
4. Ajustes de interoperabilidad Kotlin/Java (anotaciones `@JvmStatic`) para poder invocar el
   servicio desde el código Java heredado del fork de termux-app.
5. Reglas de ofuscación (ProGuard) que preservan las clases del módulo X11, invocadas por
   reflexión/manifest.

## Bug crítico corregido: task compartida con la actividad principal

`MainActivity` (el visor X11) no tenía inicialmente una `taskAffinity` propia y compartía la
misma "tarea" (task) del sistema con la actividad principal de la app — como consecuencia,
"Minimizar" o "Cerrar" desde el diálogo de salida del visor mataban la aplicación entera, no
solo el visor X11. El fix: asignarle al visor una `taskAffinity` dedicada más
`FLAG_ACTIVITY_NEW_TASK` en el `Intent` que lo lanza, junto con dos subclases propias que
adaptan el comportamiento del fork heredado al contexto de Kairos (que no tiene el mismo host
`TermuxActivity` que el proyecto original esperaba).

## Bug de entrada táctil

En una revisión comparando tres versiones del código (el proyecto oficial, el fork intermedio del
que se copió el módulo, y Kairos), se confirmó que el manejador real de eventos táctiles estaba
comentado desde que el módulo se integró por primera vez — Kairos heredó el bug del fork, no lo
introdujo. Se restauró el despacho completo de eventos (toque, hover, movimiento genérico,
puntero capturado).

## Arranque del escritorio: causa raíz de fallos de conexión

Después de confirmar que el visor en sí (pantalla, entrada táctil) funcionaba, se detectó que
ningún cliente X (ni un escritorio nativo XFCE4, ni uno corriendo dentro de una distro Linux vía
proot) lograba conectarse o renderizar. La investigación, comparando el código fuente que produjo
el `.so` embebido contra el código de tres proyectos relacionados, encontró dos causas
combinadas:

1. **Variable de entorno `TMPDIR` no seteada en el servidor.** El código nativo que resuelve el
   socket Unix del servidor X (`$TMPDIR/.X11-unix/X<display>`) recurre a un `TMPDIR` sin definir,
   probando primero si `/tmp` existe como fallback. Como `X11Service` es un `Service` Android puro
   (sin ninguna shell de Termux de por medio) nunca definía esa variable, el resultado dependía de
   un detalle no determinístico del dispositivo — si el chequeo resolvía a `/tmp` (en vez del
   directorio temporal real de Termux), el servidor publicaba el socket en un lugar que ningún
   cliente iba a encontrar. **Fix**: forzar `TMPDIR` explícitamente (usando la API de entorno de
   proceso de Android, no variables de entorno de un proceso hijo) antes de invocar el arranque
   nativo del servidor.
2. **`LD_PRELOAD` faltante del lado del cliente.** El mecanismo que lanza el cliente X (ej.
   `startxfce4`) invoca una shell no interactiva, así que los archivos de perfil de Termux nunca
   se cargan — y con ellos, el mecanismo de interceptación de rutas (`termux-exec`) que Termux
   necesita para que rutas hardcodeadas tipo `/tmp/.X11-unix/X<n>` se redirijan al directorio real
   de la app. **Fix**: exportar explícitamente `LD_PRELOAD` apuntando a la librería de
   interceptación de Termux antes de lanzar el cliente gráfico.

Se agregó además un chequeo de diagnóstico: antes de lanzar cualquier cliente X, se verifica que
el socket Unix exista realmente en el directorio esperado.

## Visor VNC embebido

Como alternativa al X11 embebido (para casos donde el usuario prefiera un protocolo más simple o
compatible con otros clientes), Kairos incluye un visor VNC propio, implementado desde cero
siguiendo la especificación del protocolo RFB (RFC 6143) — no existe ningún cliente VNC en Kotlin
nativo reutilizable como base, así que se escribió uno propio:

- **Cliente RFB en Kotlin puro** — handshake completo (negociación de versión + autenticación VNC
  con desafío DES), formato de píxel fijo a 32 bits ARGB, y encoding **Raw únicamente** (el único
  soportado universalmente sin negociar compresión — aceptable porque cliente y servidor corren
  en el mismo dispositivo vía loopback, donde el ancho de banda no es el cuello de botella).
  Encodings más eficientes (Hextile, Tight) quedan como mejora futura documentada, no
  implementados.
- Una vista Android que dibuja el framebuffer recibido (con letterbox) y traduce toques en eventos
  de puntero RFB.
- Una pantalla que se conecta al servidor VNC local, pide contraseña si el servidor la exige, y
  mapea el teclado Android a keysyms X11 (ASCII directo y teclas de control básicas — sin
  soporte todavía para teclas muertas, acentos o layouts no-QWERTY).
- **Sincronización de portapapeles bidireccional**: implementación completa de los mensajes RFB
  `ServerCutText`/`ClientCutText` (RFC 6143 §7.5.6) en ambas direcciones, con el límite real del
  protocolo clásico de solo soportar texto Latin-1 (caracteres fuera de ese rango se sustituyen).

El visor requiere que el servidor VNC (TigerVNC) tenga una contraseña ya configurada para poder
arrancar en modo no interactivo — si nunca se configuró una, el arranque del servidor puede
fallar antes de llegar a esa negociación.

## Menú de aplicaciones dentro del escritorio

Para que los módulos de Kairos (agentes de IA, herramientas de desarrollo, etc.) sean accesibles
desde dentro del escritorio gráfico como aplicaciones normales, Kairos genera archivos `.desktop`
(formato estándar freedesktop.org) tanto en la carpeta de escritorio del usuario como en la ruta
estándar XDG que XFCE4/LXQt/MATE escanean para construir el menú "Aplicaciones" real
(`~/.local/share/applications/`). Cada entrada incluye una categoría real (Desarrollo, IA,
Sistema, Red, etc.) tomada del catálogo de módulos, así que el menú de aplicaciones nativo del
escritorio ya agrupa/filtra los módulos por categoría sin necesitar ningún panel propio. Ver
[PANEL_MODULOS_X11.md](PANEL_MODULOS_X11.md) para el diseño completo de esta funcionalidad.

## Comandos manuales equivalentes

Para referencia, estos son los comandos Android (`am`) equivalentes a lo que la UI de Kairos
ejecuta internamente:

```sh
export DISPLAY=:1
am start-foreground-service -n com.termux/.app.X11Service
sleep 2
am start --user 0 -n com.termux/com.termux.x11.MainActivity  # abre el visor
```

Detener el servidor:

```sh
am broadcast -a com.termux.x11.ACTION_STOP -p com.termux
am stopservice -n com.termux/.app.X11Service
```
