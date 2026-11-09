# Homelab

**Pantalla nativa de Kairos** — accesible desde el menú "Más". Es el panel de **control y
monitoreo** del propio dispositivo: con la facilidad de uso de un panel real, permite ver el
estado del teléfono, controlar módulos y administrar servicios y automatizaciones sin abrir la
terminal.

---

**App:** Kairos (fork de termux-app)
**Fragment:** `HomelabFragment.kt`
**Persistencia/lógica:** `HomelabManager.kt`, `SystemMetrics.kt`, `HomelabServer.kt`, `AutomationManager.kt`

---

## 0. Qué es, y qué NO es

Homelab tiene 3 pestañas:

1. **Panel** — métricas reales del sistema (CPU/RAM/almacenamiento/temperatura/batería/red/IP),
   la lista de módulos con proceso corriendo ahora mismo, y el **panel web** (`HomelabServer`)
   para controlar el teléfono como un VPS/homelab desde cualquier navegador (por IP local o por
   túnel Cloudflare).
2. **Servicios** — accesos rápidos a Remote/SSH y a los módulos con servicio HTTP corriendo en el
   propio dispositivo, + una lista de servicios self-hosted EXTERNOS agregados a mano
   (Docker/Portainer de un NAS, Pi-hole de una Raspberry Pi, Proxmox de un servidor, o cualquier
   panel HTTP genérico) + una sección de Complementos (ver §11).
3. **Automatizaciones** — `AutomationsFragment` embebido (ver Sección 8) — triggers registrables
   (arranque, horario, umbral de batería) que disparan iniciar/detener un módulo sin que el
   usuario abra la app.

**No es** un módulo instalable de `modulos/` — no tiene script `.sh` propio, no aparece en
`modules.json`, no tiene switch de instalar/iniciar/detener. Es 100% Kotlin nativo. La pestaña
"Servicios" **no implementa** integración profunda por API de cada plataforma (Docker Engine API,
Pi-hole API, Proxmox API) — el "estado" de un servicio agregado es un probe HTTP genérico (`HEAD`
con timeout corto), no una lectura real de contenedores/reglas/VMs.

Las filas de **Módulos corriendo** incluyen una acción nativa **Detener**, con confirmación y
usando `ModuleController.stopModule()`; el listado usa la fuente de estado real de
`SystemMetrics.moduleStates()`. El panel web también permite iniciar/detener módulos. La pantalla
muestra únicamente métricas que el sistema expone; GPU y caudal de red no se dibujan como si
existiera una lectura fiable.

## 1. Diferencia con Remote/SSH

La diferencia está explícita en el subtítulo de la pantalla (`homelab_subtitle`):

| | Remote (SSH) | Homelab |
|---|---|---|
| Qué es | Acceso por **terminal** — shell entrante (sshd) o saliente (cliente SSH) | Panel de **control y monitoreo** — qué está pasando ahora mismo + prender/apagar cosas desde una UI |
| Cómo se usa | El usuario escribe comandos en una sesión de shell | El usuario mira métricas/toca botones, nativo o desde un navegador |
| Alcance | Un protocolo (SSH) | Métricas del sistema + estado de módulos + servicios externos + automatizaciones + panel web propio |

La pestaña "Servicios" de Homelab SÍ tiene una fila de acceso rápido a Remote (punto de
estado + botón "Abrir") — es un ATAJO de navegación, no una reimplementación: tocarlo navega a
`RemoteFragment`, la pantalla real de SSH.

También se diferencia de los módulos que Kairos gestiona (Ollama, n8n, ...):

| | Módulos (Ollama, n8n, ...) | Servicios externos de Homelab |
|---|---|---|
| Dónde corre el servicio | Dentro del propio teléfono (proceso hijo de Kairos) | Fuera — en un NAS/Raspberry Pi/servidor de la red del usuario |
| Cómo se instala | `ProcessBuilder` + script `.sh` de `modulos/` | No se instala nada — solo se registra una URL |
| Ciclo de vida | Kairos lo inicia/detiene | Kairos no controla el proceso remoto, solo consulta si responde |
| Autenticación | No aplica (proceso local) | Usuario/token opcional, guardado sin poder volver a verse nunca |

## 2. Arquitectura

```
HomelabFragment.kt (UI, 100% programática, TabLayout de 3 pestañas)
        │
        ├── Pestaña "Panel"
        │       ├── SystemMetrics.kt (util/) — lectura de CPU%/RAM/almacenamiento/temperatura/
        │       │    batería/red/IP, extraída del mismo /proc-reading que ya usaba
        │       │    MonitorFragment.kt (MonitorFragment sigue con su lógica propia)
        │       ├── SystemMetrics.moduleStates() — TODOS los módulos con hasSwitch/!internal,
        │       │    vía ModuleController.isRunning() (sin lista hardcodeada, a diferencia de
        │       │    MonitorFragment.processModules())
        │       └── HomelabServer.kt (util/) — servidor HTTP embebido (mismo patrón crudo
        │            ServerSocket/Socket que NubeServer.kt), gateado por token
        │            (~/.homelab_token), sirve el panel web + toggle de módulos por POST
        │
        ├── Pestaña "Servicios"
        │       ├── "Este dispositivo" → ModuleController.isRunning("remote") + módulos con
        │       │    `port` real en modules.json corriendo ahora
        │       └── "Servicios" externos → HomelabManager.kt (persistencia + probe HTTP)
        │              ├── ~/.android_server_registry (clave "homelab.services")
        │              └── ~/.homelab_secrets/<id> (token/contraseña, owner-only)
        │
        └── Pestaña "Automatizaciones"
                └── AutomationsFragment embebido vía childFragmentManager (ver Sección 8)
```

`HomelabFragment` no extiende `BaseModuleFragment` — no es un módulo con
instalar/iniciar/detener, mismo criterio que ya separa `MonitorFragment`/`NubeFragment` (plain
`Fragment`) del resto de los fragments de módulo real. Se agrega directo como tab del
`fragment_container` en `TermuxActivity.java` (mismo patrón `add()+hide()` que Monitor/Nube/
Entorno), navegable desde `MoreBottomSheetFragment` (`R.id.nav_homelab`, ícono `ic_homelab.xml`).
`AutomationsFragment` no se agrega como tab propio de `TermuxActivity` — vive solo como child
fragment dentro de `HomelabFragment` (ver Sección 8.1).

## 3. Pestaña "Panel" — métricas del sistema (`SystemMetrics.kt`)

Grid de 7 tarjetas (CPU, RAM, Almacenamiento, Temperatura, Batería, Red, IP local) + lista de
módulos corriendo, actualizadas cada 4s mientras la Activity está en foreground
(`onResume()`/`onPause()` arrancan/paran el polling — `TermuxActivity` usa `add()+hide()/show()`
para los tabs, así que `onPause()` real solo dispara cuando la Activity entera pasa a background,
no al cambiar de pestaña de "Más").

| Métrica | Fuente real | Notas |
|---|---|---|
| CPU % | `/proc/stat` (delta de jiffies entre 2 muestras) | Mismo mecanismo que `MonitorFragment.readCpuInfo()` — no existe "% ahora mismo" de una sola lectura |
| RAM | `/proc/meminfo` (`MemTotal`/`MemAvailable`) | |
| Almacenamiento | `StatFs` sobre `Environment.getDataDirectory()` | Requiere permiso de almacenamiento (`PermissionManager.hasStorage()`); sin él muestra `—` |
| Temperatura | `BatteryManager.EXTRA_TEMPERATURE` (broadcast sticky `ACTION_BATTERY_CHANGED`) | Proxy de temperatura del dispositivo — no hay ruta estándar a `/sys/class/thermal` sin root |
| Batería | `BatteryManager.EXTRA_LEVEL`/`EXTRA_SCALE` | Mismo broadcast que ya usa `AutomationBatteryReceiver` |
| Red | `ConnectivityManager`/`NetworkCapabilities` | Tipo (Wi-Fi/datos móviles/ethernet) + si valida internet |
| IP local | `NetworkInterface` (primera IPv4 no-loopback) | Mismo mecanismo que `NubeServer.localIpAddress()` |
| Módulos corriendo | `ModuleController.isRunning()` sobre TODO `ModuleCatalog` con `hasSwitch && !internal` | Lista con acción Detener; abrir el módulo real vive en Módulos/Monitor |

**GPU**: no hay una métrica de "uso de GPU en vivo" honesta en Android sin root, así que no se
muestra un número inventado. El diagnóstico de GPU REAL (tipo de chip, renderer, benchmark
`glxgears`) existe y es específico de Mini PC (`EntornoSistemaTab.kt`/`EntornoNative.kt`,
porque necesita `mesa-utils`/X11 instalado) — Homelab no lo duplica.

## 4. Pestaña "Panel" — panel web (`HomelabServer.kt`)

Servidor HTTP embebido a mano (mismo patrón crudo `ServerSocket`/`Socket` que `NubeServer.kt`,
sin librerías nuevas), puerto `8972` por default, gateado por token persistido en
`~/.homelab_token` (mismo mecanismo que `.nube_token`).

**Rutas:**
- `GET /?token=...` — página HTML con las mismas métricas de la Sección 3, lista de módulos con
  botón Iniciar/Detener (dispara `ModuleController.startModule()`/`stopModule()` reales por
  `POST /module`), y la lista de automatizaciones configuradas (solo lectura).
- `POST /module?token=...&id=...&action=start|stop` — toggle real de un módulo.
- `GET /status?token=...` — el mismo JSON de métricas, para un cliente sin recarga completa de
  página (la página HTML no lo usa: hace `<meta http-equiv="refresh" content="10">` en vez de
  `fetch()` periódico).

**Acceso**: por IP local (mismo patrón que Nube expone su puerto en la red local) o por túnel
Cloudflare — reusa `TunnelManager.kt` tal cual (mismo código que ya usan Nube/Tunnel/módulos),
apuntado al puerto de `HomelabServer` en vez de a un módulo. La UI nativa (`HomelabFragment`,
pestaña Panel) muestra la URL local y, si se activa un túnel, la URL pública real.

**CSP**: a diferencia de Nube (`script-src 'none'`, cero JS), esta página necesita un `<script>`
inline mínimo para que los botones Iniciar/Detener no recarguen la página completa en cada click
— se usa `script-src 'unsafe-inline'` (documentado en el comentario de `writeText()`) en vez de
`'none'`, porque no hay forma barata de generar un nonce por request sin build step. El único
script es estático (no interpola datos de usuario en su cuerpo — los valores dinámicos viajan por
atributos `onclick` ya escapados).

**Autenticación**: token igual que Nube — el panel nunca queda sin protección.

## 5. Pestaña "Servicios" — UI (`HomelabFragment.kt`)

- **"Este dispositivo"**: card con una fila fija para Remote/SSH (punto verde/gris + estado +
  botón "Abrir" que navega a `RemoteFragment`) seguida de una fila por cada módulo con `port` en
  `modules.json` que esté corriendo ahora mismo — "Abrir" navega a
  `ModuleWebViewFragment.newInstance("http://127.0.0.1:<port>", nombre)`.
- **"Servicios"**: botón "+ Agregar" abre un diálogo (nombre, tipo — dropdown Docker/Portainer,
  Pi-hole, Proxmox, HTTP genérico, Otro —, URL/IP:puerto, usuario opcional, token opcional). Cada
  servicio agregado se muestra como card: punto de estado (gris hasta el primer probe, verde/rojo
  después), nombre, `tipo · URL`, botón "Abrir". Mantener presionada una card abre un menú: Editar
  / Agregar-o-Reemplazar token / Borrar token (si tiene uno) / Eliminar servicio.
- `HomelabManager.normalizeUrl()` antepone `http://` si el usuario escribe solo
  `192.168.1.50:9000` sin esquema.
- El probe de disponibilidad corre en un segundo pase después de renderizar la lista, para no
  bloquear el primer render con la latencia de red.

## 6. Persistencia y seguridad (`HomelabManager.kt`)

Mismo patrón de registry que `RemoteManager.kt` (ver `docs/modulos/remote-ssh.md`): una clave
(`homelab.services`) con un array JSON compacto en `~/.android_server_registry`, cada operación de
escritura protegida por `RegistryLock.withLock()`.

```kotlin
data class HomelabService(
    val id: String, val name: String, val type: ServiceType, val url: String,
    val username: String = "", val hasSecret: Boolean = false
)
enum class ServiceType { DOCKER, PIHOLE, PROXMOX, HTTP, OTHER }
```

**Garantía de seguridad**: una vez guardado un token/contraseña, **ninguna** función de
`HomelabManager` expone su contenido a la UI — solo el booleano `hasSecret`. Las únicas acciones
disponibles sobre un secreto guardado son reemplazarlo (el diálogo arranca SIEMPRE vacío) o
borrarlo. El archivo del secreto (`~/.homelab_secrets/<id>`) se guarda con permisos restringidos
al dueño vía `File.setReadable`/`setWritable` — sin `chmod`/`ProcessBuilder`.

El token del panel web (`~/.homelab_token`) sigue el mismo criterio: se genera una sola vez
(`SecureRandom`, 24 bytes) y se persiste, nunca se muestra salvo embebido en la URL que el propio
dueño del dispositivo copia/comparte.

## 7. Probe de disponibilidad

`HomelabManager.probe(url)` — `HEAD` HTTP con timeout de 2.5s. Cualquier código de respuesta
cuenta como "responde" — mismo alcance que `RemoteManager.probeClientReachable()`.

## 8. Automatizaciones

### 8.0 Qué es, y qué NO es

Triggers registrables que disparan una acción real sobre un módulo (iniciar/detener), **sin que
el usuario tenga que abrir la app**.

**No es** un módulo instalable de `modulos/` — no tiene script `.sh`, no aparece en
`modules.json`. Es 100% Kotlin nativo. **No es** un motor de reglas genérico tipo Tasker/IFTTT —
el alcance es deliberadamente chico: 3 triggers (arranque del dispositivo, horario diario, umbral
de batería) × 2 acciones (iniciar módulo, detener módulo), reusando `ModuleController` tal cual
existe, sin reimplementar nada de arranque/parada.

### 8.1 Dónde vive la UI

`AutomationsFragment.kt` no es un tab de nivel raíz de `TermuxActivity` (no se agrega a
`fragment_container`, ni tiene entrada propia en `MoreBottomSheetFragment` ni en
`more_nav_menu.xml`). Se monta como **child fragment** dentro de la pestaña "Automatizaciones" de
`HomelabFragment` (`childFragmentManager.beginTransaction().add(containerId, AutomationsFragment(), ...)`,
guardado contra doble-montado con `findFragmentByTag`). El motor real
(`AutomationManager`/`AutomationScheduler`/los 3 `BroadcastReceiver`s) es independiente de dónde
se muestra la UI.

### 8.2 Triggers implementados de punta a punta

| Trigger | Mecanismo | Receiver |
|---|---|---|
| **BOOT** (arranque del dispositivo) | `ACTION_BOOT_COMPLETED` estático en el manifest | `AutomationBootReceiver.kt` |
| **SCHEDULE** (horario diario fijo, `HH:mm`) | `AlarmManager.setInexactRepeating(RTC_WAKEUP, ..., INTERVAL_DAY, ...)` | `AutomationAlarmReceiver.kt` (vía `AutomationScheduler.kt`) |
| **BATTERY_LEVEL** (umbral 1-100% + dirección BELOW/ABOVE) | `ACTION_BATTERY_CHANGED`, receiver dinámico registrado en `TermuxApplication.onCreate()` (no se puede declarar estático en el manifest desde Android 8+) | `AutomationBatteryReceiver.kt` |

Los triggers de notificación, geofence y app-en-primer-plano no están implementados.

`BATTERY_LEVEL` se registra dinámicamente (no `<receiver>` en el manifest) desde
`TermuxApplication.onCreate()` — `ACTION_BATTERY_CHANGED` es una "broadcast exception" de
Android 8+. El receiver guarda en memoria del proceso el último nivel visto y solo dispara la
acción del workflow cuando el nivel CRUZA el umbral configurado en la dirección elegida (`BELOW`:
`previous >= threshold && percent < threshold`; `ABOVE`: lo inverso). **Costo real**: a diferencia
de BOOT/SCHEDULE, este trigger solo es confiable mientras el proceso de Kairos ya esté vivo en
memoria (no hay foreground service dedicado que lo sostenga).

`SCHEDULE` usa `setInexactRepeating` (no `setExactAndAllowWhileIdle`) para evitar pedirle al
usuario el permiso `SCHEDULE_EXACT_ALARM` (Android 12+) a mano en Ajustes — aceptable desviarse
algunos minutos bajo Doze para este caso de uso.

`AutomationBootReceiver.kt` es un `BroadcastReceiver` propio, separado de
`SystemEventReceiver.java` (que ya escucha `ACTION_BOOT_COMPLETED` para
`TermuxShellManager.onActionBootCompleted()`, compatibilidad Termux:Boot heredada) — Android
permite más de un `<receiver>` estático para la misma acción; se mantiene separada la
compatibilidad Termux:Boot heredada (Java) de las automatizaciones propias de Kairos (Kotlin).

### 8.3 Arquitectura del motor

```
AutomationManager.kt (persistencia + validación + ejecutor)
        │
        ├── ~/.android_server_registry  (clave "automations.workflows", mismo
        │    archivo/mecanismo que HomelabManager/RemoteManager/TunnelManager — un
        │    array JSON compacto, protegido por RegistryLock)
        │
        └── execute() → ModuleController.startModule()/stopModule()  (reusado tal cual,
             sin reimplementar arranque/parada)

AutomationScheduler.kt (AlarmManager — arma/cancela la alarma diaria de cada workflow SCHEDULE)

AutomationBootReceiver.kt   ← <receiver> BOOT_COMPLETED del manifest
        │
        ├── AutomationScheduler.scheduleAll()   (re-arma alarmas — no sobreviven un reboot)
        └── AutomationManager.execute() por cada workflow BOOT habilitado

AutomationAlarmReceiver.kt  ← disparado por el PendingIntent que arma AutomationScheduler
        │
        └── AutomationManager.execute() del workflow correspondiente

AutomationBatteryReceiver.kt  ← registrado dinámicamente en TermuxApplication.onCreate()
        │                        (ACTION_BATTERY_CHANGED, no se puede declarar estático)
        └── AutomationManager.execute() de cada workflow BATTERY_LEVEL cuyo umbral cruzó
```

### 8.4 Modelo de datos (`AutomationManager.kt`)

```kotlin
enum class TriggerType { BOOT, SCHEDULE, BATTERY_LEVEL }
enum class ActionType { START_MODULE, STOP_MODULE }
enum class BatteryDirection { BELOW, ABOVE }   // solo para BATTERY_LEVEL

data class Automation(
    val id: String, val name: String, val triggerType: TriggerType,
    val scheduleTime: String = "",   // "HH:mm" 24h, solo para SCHEDULE
    val actionType: ActionType, val moduleId: String,
    val enabled: Boolean = true, val lastRunTs: Long = 0L,
    val batteryThreshold: Int = 20,                        // 1..100, solo para BATTERY_LEVEL
    val batteryDirection: BatteryDirection = BatteryDirection.BELOW
)
```

Se extendieron los campos existentes de `Automation` en vez de introducir una `sealed class
TriggerConfig` — con pocos triggers, agregar campos siguiendo el mismo patrón que ya usaba
`scheduleTime` es la opción más simple; si se suman notificación/geofence más adelante, ahí sí
conviene migrar a `TriggerConfig`.

### 8.5 Validación — el módulo referenciado siempre existe de verdad

Antes de persistir un workflow, `AutomationManager.moduleExists(context, moduleId)` confirma que
`moduleId` existe de verdad en el catálogo (`ModuleCatalog.load(context)`). La UI ya restringe la
selección a un `Spinner` poblado desde el mismo catálogo — la validación en `AutomationManager` es
la segunda capa real, nunca se confía solo en que la UI no deje elegir mal.

### 8.6 Decisión deliberada: NO pasa por `ActionApprovalGate`

Documentado en el propio KDoc de `AutomationManager.execute()` — dos motivos:

1. **Técnico**: BOOT/SCHEDULE disparan desde un `BroadcastReceiver` sin ninguna `Activity`
   visible — sin ventana real, cualquier `requestApproval()` acá terminaría siempre en timeout →
   `false` (fail-closed), una automatización que nunca automatiza nada.
2. **De diseño**: la aprobación real ya ocurrió cuando el usuario CREÓ/HABILITÓ el workflow desde
   la UI. Las acciones que puede ejecutar un workflow (`START_MODULE`/`STOP_MODULE`) son, además,
   las mismas que `ActionApprovalGate.ActionType.MODULE_START`/`MODULE_STOP` ya clasifican como
   `REMEMBERABLE` (nunca `ALWAYS_ASK`).

### 8.7 UI (`AutomationsFragment.kt`)

100% programática (sin `<layout>` XML propio). Lista de cards (nombre, `trigger → acción`, última
ejecución, switch habilitar/deshabilitar) agrupadas por disparador + botón "+ Agregar". El diálogo
de agregar/editar usa `TimePickerDialog` nativo para elegir el horario, y `Spinner`s para
trigger/acción/módulo — el spinner de módulo se puebla desde `ModuleCatalog.load(context)`
filtrando por `hasSwitch` (solo módulos con servidor propio/toggle real) y `!internal`. El
selector de horario (y, para BATTERY_LEVEL, el `EditText` de umbral + `Spinner` de dirección) se
oculta/muestra según el trigger elegido — `syncTriggerFieldsVisibility()` centraliza ese
show/hide.

Editar/deshabilitar/eliminar un workflow SCHEDULE reprograma/cancela su alarma de inmediato vía
`AutomationScheduler` — sin esto, un horario editado seguiría disparando a la hora vieja hasta el
próximo reboot.

## 9. Alcance futuro (no implementado)

1. **App compañera externa** — una app Android separada, más liviana, solo con el dashboard.
2. **SSH como vía de acceso remoto AL panel** — llegar al panel web desde afuera de la LAN sin
   depender de un túnel Cloudflare activo.
3. **Integración profunda por plataforma** — Docker Engine API real (listar/reiniciar
   contenedores), Pi-hole API (estadísticas de bloqueo), Proxmox API (estado de VMs) — hoy el
   "estado" de un servicio externo es solo un probe HTTP genérico de disponibilidad.
4. **Autenticación reforzada** del panel web (PIN/biometría antes de generar el link, rotación de
   token) — hoy es un token único persistente, mismo nivel que Nube.
5. Triggers de Automatizaciones: notificación, geofence, app-en-primer-plano.

## 10. Archivos reales

| Archivo | Rol |
|---|---|
| `app/src/main/java/com/termux/app/ui/HomelabFragment.kt` | Pantalla — 100% programática, TabLayout de 3 pestañas (Panel/Servicios/Automatizaciones) |
| `app/src/main/java/com/termux/app/util/HomelabManager.kt` | Persistencia de servicios externos (registry JSON) + secretos + probe HTTP |
| `app/src/main/java/com/termux/app/util/SystemMetrics.kt` | Lector de métricas de sistema reusable (CPU/RAM/almacenamiento/temperatura/batería/red/IP/módulos) |
| `app/src/main/java/com/termux/app/util/HomelabServer.kt` | Servidor HTTP embebido del panel web (métricas + toggle de módulos) |
| `app/src/main/java/com/termux/app/ui/AutomationsFragment.kt` | UI de Automatizaciones — embebida vía `childFragmentManager` dentro de `HomelabFragment` |
| `app/src/main/java/com/termux/app/util/AutomationManager.kt` | Modelo de datos + persistencia + validación + ejecutor de automatizaciones |
| `app/src/main/java/com/termux/app/util/AutomationScheduler.kt` | AlarmManager — arma/cancela/re-arma alarmas diarias |
| `app/src/main/java/com/termux/app/AutomationBootReceiver.kt` | `<receiver>` BOOT_COMPLETED |
| `app/src/main/java/com/termux/app/AutomationAlarmReceiver.kt` | `<receiver>` disparado por AlarmManager |
| `app/src/main/java/com/termux/app/AutomationBatteryReceiver.kt` | Receiver dinámico `ACTION_BATTERY_CHANGED` |
| `app/src/main/res/drawable/ic_homelab.xml` | Ícono de Homelab (rack de 3 bandejas con LED) |
| `app/src/main/res/values/strings_homelab.xml` + `values-en/` | Strings ES/EN (Panel/Servicios/panel web) |
| `app/src/main/res/values/strings_automatizaciones.xml` + `values-en/` | Strings ES/EN de Automatizaciones |
| `app/src/main/res/menu/more_nav_menu.xml` | `R.id.nav_homelab` (única entrada) |
| `app/src/main/java/com/termux/app/ui/MoreBottomSheetFragment.kt` | Tile único "Homelab" en la grilla "Más" |
| `app/src/main/java/com/termux/app/TermuxActivity.java` | Registro como tab (`add()`/`hide()`/`switchFragment()`) |
| `app/src/main/AndroidManifest.xml` | Declaración de los `<receiver>` estáticos (BOOT, ALARM) de Automatizaciones |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | Registro dinámico de `AutomationBatteryReceiver` en `onCreate()` |

## 11. Complementos

La pestaña Servicios incluye una sección **Complementos** (Syncthing y Restic, sobre
`AddonEngine`/`HomelabAddons`). Syncthing arranca sin contraseña de interfaz por defecto. Las
tarjetas de servicios muestran el menú ⋮ visible y el estado en texto. Sin verificar en
dispositivo.
