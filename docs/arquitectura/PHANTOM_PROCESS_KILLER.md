# PHANTOM_PROCESS_KILLER.md — Phantom Process Killer de Android 12+

## El problema

Android 12+ introdujo el "phantom process killer": un mecanismo del sistema que mata procesos
en segundo plano que la app que los originó ya no tiene "en primer plano" — pensado para
procesos huérfanos/zombies genéricos, pero afecta de lleno a Termux/Kairos porque cada módulo
activo (Ollama `serve`, n8n, OpenClaw, cada sesión de terminal por CLI vía tmux) es exactamente
ese patrón: un proceso hijo de larga duración que sigue vivo aunque la Activity de la app no
esté en pantalla. Con varios módulos activos a la vez es fácil pasar el umbral que dispara el
killer. Un `SIGKILL` inesperado en un módulo (detectable vía `decodeExitSignal()` en
`ProcessBuilderExt.kt`) es la señal típica de que pasó.

## Las 3 vías implementadas en Kairos

Todas viven en `PhantomProcessKillerHelper.kt` (`app/src/main/java/com/termux/app/util/`), con
2 puntos de entrada en la UI:

- **Wizard**: paso dedicado de primer arranque, con las vías (b)/(c)/(d) de abajo como opciones
  explícitas — la vía beta (auto-detección) queda primera/recomendada (menos datos manuales),
  la guiada segunda (fallback si falla la beta), el tutorial tercera. No bloquea el avance del
  wizard.
- **Monitor → sección DIAGNÓSTICO**: mismo motor, para volver a correrlo después (algunos
  dispositivos necesitan repetirlo tras reiniciar) o si se saltó el paso del wizard. Primero
  intenta (a) root silencioso; si falla, ofrece las mismas 3 vías en un diálogo con opciones.

### (a) Intento root silencioso — primer paso, automático

Intenta primero `su -c "settings put global settings_enable_monitor_phantom_procs false"`. Si
el dispositivo está rooteado, el usuario nunca ve ningún diálogo — funciona y listo. Si falla
(sin root, o sin permiso `su`), pasa a ofrecer las 2 vías siguientes.

### (b) Vía guiada sin PC, vía ADB inalámbrico — la vía principal para dispositivos sin root

Cuando se corre `adb shell <comando>`, ese comando corre como el usuario `shell` de Android, que
ya tiene el permiso `WRITE_SECURE_SETTINGS` de forma nativa — no hace falta "otorgárselo" a
Termux ni a Kairos, solo una sesión `adb shell` autenticada. Android 11+ permite esa sesión
completamente desde el propio dispositivo vía "Depuración inalámbrica", sin cable ni PC.

**Flujo real:**
1. Si Opciones de desarrollador no están activas, explica cómo activarlas (7 toques en "Número
   de compilación").
2. Abre Opciones de desarrollador y pide 3 datos que **solo Android muestra en su propia
   pantalla nativa** — no hay API pública para leerlos por código: puerto de emparejamiento,
   código de 6 dígitos, puerto de conexión (los 2 primeros aparecen al tocar "Vincular
   dispositivo con código de emparejamiento" dentro de Depuración inalámbrica; el puerto de
   conexión está arriba de la pantalla principal, junto a la IP).
3. Ejecuta la secuencia completa:
   ```
   pkg update -y
   pkg install -y android-tools
   adb pair 127.0.0.1:<puerto_emparejamiento> <código>
   adb connect 127.0.0.1:<puerto_conexión>
   adb shell "settings put global settings_enable_monitor_phantom_procs false"
   adb shell "device_config set_sync_disabled_for_tests persistent"
   adb shell "device_config put activity_manager max_phantom_processes 2147483647"
   adb disconnect
   ```
4. Antes de desconectar, corre además los 4 comandos de refuerzo de supervivencia en segundo
   plano (ver más abajo).
5. **Nunca confía en el exit code** — relee el valor real con
   `settings get global settings_enable_monitor_phantom_procs` (debe dar `"false"`) y
   `device_config get activity_manager max_phantom_processes` (debe dar `"2147483647"`) antes
   de reportar éxito.

**Limitación dura, no evitable**: el puerto de emparejamiento, el código y el puerto de conexión
solo Android los expone en su pantalla nativa — ningún mecanismo (ADB, Shizuku, ni siquiera
root) puede leerlos por API. El usuario siempre tiene que ver esa pantalla una vez y transcribir
esos 3 datos a mano.

### (c) Vía beta con auto-detección de puerto — reduce el dato manual de 3 a 2 campos

En vez de pedir el puerto de conexión a mano (el dato que cambia cada vez que se activa
Wi-Fi/depuración, el más propenso a error), se detecta con
`nmap -sT -p 10000-65535 --open -T4 127.0.0.1` contra la IP local del propio dispositivo,
probando `adb connect` contra cada puerto abierto encontrado hasta que uno acepte la conexión.
Requiere el paquete `nmap` — se instala primero, avisando "instalando paquete necesario" antes
de ofrecer abrir Depuración inalámbrica, para que el usuario entienda por qué tarda ese paso
extra. Marcada explícitamente como "beta" porque el rango de escaneo puede tardar varios
segundos y el puerto real no siempre cae en el mismo rango en todos los fabricantes.

### (d) Tutorial — prioriza el interruptor nativo, ADB queda como respaldo

Compartido por Monitor y el wizard. El texto lidera con el interruptor nativo ("Desactivar
restricciones de procesos secundarios", buscarlo en Opciones de desarrollador) y deja los
comandos ADB (con botón "Copiar comandos ADB") como alternativa para dispositivos que no lo
tengan.

## Los 4 comandos de refuerzo de supervivencia en segundo plano

Corren en la misma sesión ADB que las vías (b) y (c), justo antes de `adb disconnect`:
```
adb shell "dumpsys deviceidle whitelist +com.termux"
adb shell "cmd appops set com.termux RUN_IN_BACKGROUND allow"
adb shell "cmd appops set com.termux WAKE_LOCK allow"
adb shell "am set-inactive com.termux false"
```
Atacan el mismo problema de fondo por 3 mecanismos que el fix del phantom process killer NO
cubre: exclusión de Doze/App Standby (sin el diálogo del sistema que la exención de batería sí
necesita), permisos de `appops` independientes de los declarados en el manifest, y sacar a la
app del estado "inactivo" de inmediato. `com.termux` es el `applicationId` real de Kairos (mismo
`sharedUserId` que Termux original), así que estos comandos aplican sin adaptar nombres.

## Interruptor nativo de Android 14+ — la única excepción real sin ADB

En Android 14+ existe un interruptor **nativo** en Opciones de desarrollador ("Stop restricting
child processes" en inglés, "Desactivar restricciones de procesos secundarios" en español) —
cero ADB, cero Termux, un solo toque. No hace falta scrollear hasta el final de la pantalla —
está cerca de "Restablecer el límite de llamadas de ShortcutManager". **Caveat**: no todos los
fabricantes lo exponen (hay reportes de dispositivos sin él incluso en Android 14+) — conviene
revisarlo primero, pero no se puede depender de que esté disponible.

## Por qué no hay una vía 100% sin ADB

`WRITE_SECURE_SETTINGS`/`WRITE_DEVICE_CONFIG` son permisos de firma/sistema
(`protectionLevel="signature|privileged"`) — Android los deniega a cualquier app normal sin
excepción de diseño, Termux/Kairos incluidos (corren con el mismo UID restringido que cualquier
otra app). No existe una ruta documentada que evite ADB o root para modificar estos ajustes
desde una app de terceros.
