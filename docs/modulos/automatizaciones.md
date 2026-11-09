# Automatizaciones — Referencia de Kairos

> Las Automatizaciones viven como pestaña "Automatizaciones" dentro de `HomelabFragment` (child
> fragment, sin código propio adicional). Ver también `HOMELAB.md`.

## 0. Qué es, y qué NO es

**Automatizaciones** le da a Kairos triggers registrables que disparan una acción real sobre un módulo
(iniciar/detener), **sin que el usuario tenga que abrir la app**. Antes de esto Kairos no tenía NINGUNA
forma de que algo se disparara solo. Encaja con la filosofía del producto ("Kairos existe para no vivir
en la terminal") — acá ni siquiera hace falta abrir la app.

**No es** un módulo instalable de `modulos/` — no tiene script `.sh`, no aparece en `modules.json`. Es
100% Kotlin nativo. **No es** un motor de reglas genérico tipo Tasker/IFTTT — el alcance es
deliberadamente chico: 3 triggers (arranque del dispositivo, horario diario, nivel de batería) × 2
acciones (iniciar módulo, detener módulo), reusando `ModuleController` tal cual existe, sin
reimplementar nada de arranque/parada.

## 1. Triggers implementados

| Trigger | Mecanismo | Receiver |
|---|---|---|
| **BOOT** (arranque del dispositivo) | `ACTION_BOOT_COMPLETED` estático en el manifest | `AutomationBootReceiver.kt` |
| **SCHEDULE** (horario diario fijo, `HH:mm`) | `AlarmManager.setInexactRepeating(RTC_WAKEUP, ..., INTERVAL_DAY, ...)` | `AutomationAlarmReceiver.kt` (vía `AutomationScheduler.kt`) |
| **BATTERY_LEVEL** (umbral 1-100% + dirección BELOW/ABOVE) | `ACTION_BATTERY_CHANGED`, receiver dinámico registrado en `TermuxApplication.onCreate()` (no se puede declarar estático en el manifest desde Android 8+) | `AutomationBatteryReceiver.kt` |

Los triggers de notificación, geofence y app-en-primer-plano están diseñados pero **no implementados**.

### `BATTERY_LEVEL` — detalle

`AutomationBatteryReceiver` se registra dinámicamente (`registerReceiver`, no `<receiver>` en el
manifest) desde `TermuxApplication.onCreate()` — `ACTION_BATTERY_CHANGED` es una "broadcast
exception" de Android 8+ que ya no admite declaración implícita estática (mismo motivo que
`SystemEventReceiver.registerPackageUpdateEvents()` para otro caso). El receiver guarda en memoria del
proceso el último nivel visto y compara contra el nuevo en cada `ACTION_BATTERY_CHANGED` (que llega
con cada % que cambia) — solo dispara la acción del workflow cuando el nivel CRUZA el umbral
configurado en la dirección elegida (`BELOW`: `previous >= threshold && percent < threshold`; `ABOVE`:
lo inverso), no en cada broadcast.

**Limitación**: a diferencia de BOOT/SCHEDULE, este trigger solo es confiable mientras el proceso de
Kairos ya esté vivo en memoria (no hay un foreground service dedicado que lo sostenga).

### Por qué `SCHEDULE` usa `setInexactRepeating`, no `setExactAndAllowWhileIdle`

Un workflow tipo "iniciar Ollama a las 8am" no necesita precisión al segundo, y la variante exacta
requiere el permiso `SCHEDULE_EXACT_ALARM` (Android 12+/API 31+), que el usuario tendría que
conceder a mano en Ajustes del sistema fuera de la propia app. Se evita esa fricción a propósito —
`setInexactRepeating` puede desviarse algunos minutos bajo Doze, aceptable para este caso de uso.

### Por qué es un `BroadcastReceiver` propio, separado de `SystemEventReceiver.java`

`SystemEventReceiver.java` ya escucha `ACTION_BOOT_COMPLETED` para
`TermuxShellManager.onActionBootCompleted()` (compatibilidad con scripts de Termux:Boot, heredado del
fork de termux-app). Android permite registrar más de un `<receiver>` estático para la misma acción
sin conflicto — se usa un receiver propio (`AutomationBootReceiver.kt`) para no mezclar
"compatibilidad Termux:Boot heredada" (Java) con "automatizaciones propias de Kairos" (Kotlin) — dos
superficies con evolución independiente.

## 2. Arquitectura

```
AutomationsFragment.kt (UI, 100% programática)
        │
        ├── AutomationManager.kt (persistencia + validación + ejecutor)
        │       │
        │       ├── ~/.android_server_registry  (clave "automations.workflows", mismo
        │       │    archivo/mecanismo que HomelabManager/RemoteManager/TunnelManager — un
        │       │    array JSON compacto, protegido por RegistryLock)
        │       │
        │       └── execute() → ModuleController.startModule()/stopModule()  (reusado tal cual,
        │            sin reimplementar arranque/parada)
        │
        └── AutomationScheduler.kt (AlarmManager — arma/cancela la alarma diaria de cada
             workflow SCHEDULE)

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

`AutomationsFragment` no extiende `BaseModuleFragment` — mismo criterio que `HomelabFragment` (no es
un módulo con lifecycle de proceso propio). Se muestra embebido como pestaña de `HomelabFragment`.

## 3. Modelo de datos (`AutomationManager.kt`)

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

Los campos específicos de cada trigger se agregan a `Automation` directamente en vez de una `sealed
class TriggerConfig`: con pocos triggers, sumar campos siguiendo el patrón de `scheduleTime` es la
opción más simple; si se suman notificación/geofence, conviene revisar la migración a `TriggerConfig`.

## 4. Validación — el módulo referenciado siempre existe de verdad

Antes de persistir un workflow, `AutomationManager.moduleExists(context, moduleId)` confirma que
`moduleId` existe en el catálogo (`ModuleCatalog.load(context)`). La UI ya restringe la selección a un
`Spinner` poblado desde el mismo catálogo (imposible elegir un id inexistente a mano) — la validación
en `AutomationManager` es la segunda capa real: no se confía solo en que la UI no deje elegir mal.

## 5. Decisión deliberada: NO pasa por `ActionApprovalGate`

Documentado en el KDoc de `AutomationManager.execute()` — dos motivos:

1. **Técnico**: BOOT/SCHEDULE disparan desde un `BroadcastReceiver` sin ninguna `Activity` visible.
   `ActionApprovalGate.androidAlertDialogPresenter` necesita una ventana real para mostrar un
   `AlertDialog` — sin eso, cualquier `requestApproval()` acá terminaría siempre en timeout → `false`
   (fail-closed). Gatear esto sería una automatización que nunca automatiza nada.
2. **De diseño**: la aprobación real ya ocurrió cuando el usuario CREÓ/HABILITÓ el workflow desde la
   UI (se aprueba la creación/actualización del workflow, no cada disparo individual). Las acciones
   que puede ejecutar un workflow (`START_MODULE`/`STOP_MODULE`) son, además, las mismas que
   `ActionApprovalGate.ActionType.MODULE_START`/`MODULE_STOP` clasifican como `REMEMBERABLE` (nunca
   `ALWAYS_ASK`) — no hay ninguna acción destructiva detrás de un workflow.

## 6. UI (`AutomationsFragment.kt`)

100% programática (sin `<layout>` XML propio), mismo criterio que `HomelabFragment`. Lista de cards
(nombre, `trigger → acción`, última ejecución, switch habilitar/deshabilitar) + botón "+ Agregar". El
diálogo de agregar/editar usa `TimePickerDialog` nativo de Android para elegir el horario (nunca pide
tipear `HH:mm` a mano), y `Spinner`s para trigger/acción/módulo — el spinner de módulo se puebla desde
`ModuleCatalog.load(context)` filtrando por `hasSwitch` (solo módulos con servidor propio/toggle real
de iniciar-detener, ej. Ollama/n8n — excluye CLIs puros como Claude Code/Codex, que no tienen
start/stop script) y `!internal` (excluye los ids consolidados dentro de los módulos contenedor
`languages`/`packages`). El selector de horario y los campos de umbral (`EditText`) + dirección
(`Spinner`) para BATTERY_LEVEL se muestran/ocultan según el trigger elegido —
`syncTriggerFieldsVisibility()` centraliza ese show/hide para los 3 triggers.

Editar/deshabilitar/eliminar un workflow SCHEDULE reprograma/cancela su alarma de inmediato vía
`AutomationScheduler` — así un horario editado no sigue disparando a la hora vieja hasta el próximo
reboot.

## 7. Archivos

| Archivo | Rol |
|---|---|
| `app/src/main/java/com/termux/app/ui/AutomationsFragment.kt` | Pantalla — 100% programática, lista + diálogo agregar/editar |
| `app/src/main/java/com/termux/app/util/AutomationManager.kt` | Modelo de datos + persistencia (registry JSON) + validación + ejecutor (`execute()`) |
| `app/src/main/java/com/termux/app/util/AutomationScheduler.kt` | AlarmManager — arma/cancela/re-arma alarmas diarias |
| `app/src/main/java/com/termux/app/AutomationBootReceiver.kt` | `<receiver>` BOOT_COMPLETED — ejecuta workflows BOOT + re-arma alarmas |
| `app/src/main/java/com/termux/app/AutomationAlarmReceiver.kt` | `<receiver>` disparado por AlarmManager — ejecuta UN workflow SCHEDULE |
| `app/src/main/java/com/termux/app/AutomationBatteryReceiver.kt` | Receiver dinámico (`ACTION_BATTERY_CHANGED`) — detecta cruce de umbral y ejecuta los workflows BATTERY_LEVEL correspondientes |
| `app/src/main/res/drawable/ic_automation.xml` | Ícono (rayo/bolt, mismo criterio que `ic_homelab.xml`) |
| `app/src/main/res/values/strings_automatizaciones.xml` + `values-en/` | Strings ES/EN |
| `app/src/main/AndroidManifest.xml` | Declaración de los 2 `<receiver>` estáticos (BOOT, ALARM) |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | `AutomationBatteryReceiver.Companion.register(context)` en `onCreate()` — registro dinámico, `ACTION_BATTERY_CHANGED` no admite `<receiver>` estático implícito desde Android 8+ |
