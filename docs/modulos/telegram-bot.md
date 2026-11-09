# TELEGRAM_BOT.md — Bot de Telegram entrante (`TelegramBotService.kt`)

> NO es un módulo de `app/src/main/assets/modules.json` (no tiene script en `modulos/`, no aparece
> en la Tienda/Módulos) — es una pieza de infraestructura de la propia app, documentada en
> `docs/modulos/` de todos modos porque conceptualmente es "una feature de control de módulos", no
> arquitectura central (mismo criterio que `HOMELAB.md`). `TelegramNotifier.kt` cubre el envío
> SALIENTE; este documento cubre la recepción de comandos.

## Qué es

Un `Service` en foreground (`TelegramBotService.kt`) que hace long-polling contra la Bot API de
Telegram (`getUpdates`) para recibir comandos de texto y botones inline, y los traduce en llamadas
directas a `ModuleController` (arrancar/parar módulos, consultar estado) — sin exponer ningún
puerto ni necesitar un dominio/HTTPS público (Kairos no tiene ninguno de los dos). Vive en el
proceso PRINCIPAL de la app (a diferencia de `X11Service.kt`, que corre en `:xserver`) porque
necesita llamar a `ModuleController` sin cruzar procesos.

El diseño sigue tres patrones: long-polling (nunca webhook), whitelist fail-closed por `from.id`, y
confirmación en 2 pasos para comandos riesgosos.

## Arquitectura

```
ConfigFragment (switch "🤖 Bot de Telegram")
        │ TelegramBotService.start(context) / .stop(context)
        ▼
TelegramBotService (foreground Service, proceso principal)
        │ Thread "telegram-bot-poll" — loop while(alive)
        │   POST https://api.telegram.org/bot<token>/getUpdates
        │   {"offset": N, "timeout": 25, "allowed_updates": ["message","callback_query"]}
        ▼
processUpdate() → handleMessage() / handleCallbackQuery()
        │ whitelist fail-closed (from.id == chat_id configurado)
        ▼
ModuleController.startModule() / .stopModule() / .isRunning()
```

- **Long-polling, nunca webhook**: Kairos no tiene un endpoint HTTPS público fijo al que Telegram
  pueda pegarle un webhook — el patrón correcto para un cliente móvil sin servidor propio es que
  el propio dispositivo pregunte ("¿hay algo nuevo?") en un loop, con `timeout=25s` por request
  para que Telegram mantenga la conexión abierta en vez de responder vacío cada segundo.
- **Offset persistido** (`SharedPreferences "kairos_prefs"`, clave `pref_telegram_bot_update_offset`):
  cada update procesado avanza el offset y se guarda antes de seguir — si el servicio se reinicia,
  no vuelve a procesar mensajes viejos (Telegram solo entrega updates con `update_id >= offset`).
- **Reconexión con backoff exponencial**: si `getUpdates` falla (red caída, error de Telegram), el
  loop espera `2s → 4s → 8s → ... → tope 60s` antes de reintentar, y resetea el backoff a 2s en
  cuanto una llamada vuelve a tener éxito.
- **Mismo molde que `X11Service.kt`**: `start()`/`stop()` estáticos, notificación foreground
  persistente con canal propio (`telegram_bot`), `onDestroy()` corta el loop y desconecta la
  conexión HTTP activa (si el hilo estaba bloqueado esperando `getUpdates`, `disconnect()` lo
  libera en vez de dejarlo colgado hasta el próximo timeout).

## Whitelist fail-closed (regla de seguridad central)

El mismo `chat_id` que se configura en Ajustes → Notificaciones para las notificaciones
SALIENTES (`TelegramNotifier`) cumple doble función: destino de mensajes salientes Y único
`from.id` autorizado a mandar comandos entrantes.

- **Nunca `chat.id`, siempre `from.id`**: `chat.id` identifica la conversación (podría ser un
  grupo con varios usuarios); `from.id` identifica a la persona real que escribió el mensaje o
  tocó el botón, y es el campo correcto para autorización.
- **Fail-closed en 3 capas, ninguna redundante**:
  1. `TelegramBotService.start(context)` (companion) — si `token`/`chat_id` están vacíos, **ni
     siquiera manda el `Intent`** para arrancar el `Service`. `ConfigFragment` recibe `false` y
     avisa al usuario en vez de dejar el switch prendido mintiendo el estado.
  2. `onCreate()` — segunda verificación, por si algo más adelante llegara a arrancar el
     `Service` directo (ej. Android relanzándolo con `Intent` `null` tras matar el proceso). Si
     falta config, `stopSelf()` inmediato tras el `startForeground()` obligatorio.
  3. `runLoop()` — si el usuario borra el token/chat id sin apagar el switch primero (config
     "vaciada en caliente"), el loop lo detecta en la siguiente iteración y corta el servicio en
     vez de seguir corriendo sin whitelist real.
- **Cada mensaje/callback entrante se re-valida** (`isAuthorized(fromId, whitelistedChatId)`) —
  no se confía en que "si el Service está corriendo, ya está todo autorizado".

## Confirmación en 2 pasos (`/stop`)

El único comando "riesgoso" de esta versión — parar un módulo en uso — nunca se ejecuta
directo desde el texto del comando:

1. `/stop <modulo>` → si el módulo está corriendo, se manda un mensaje con teclado inline
   (`reply_markup.inline_keyboard`) con dos botones: `✅ Sí` (`callback_data:
   "stopyes:<modulo>:<nonce>"`) y `❌ No` (`callback_data: "stopno:<modulo>:<nonce>"`).
2. El usuario toca un botón → Telegram manda un `callback_query` → `handleCallbackQuery()`
   ejecuta la acción real (`ModuleController.stopModule()`) o cancela, y edita el mensaje
   original (`editMessageText`) para mostrar el resultado y **vaciar el teclado** (evita que el
   usuario pueda volver a tocar los mismos botones sobre un mensaje ya resuelto).

**Protección de doble-tap**: cada `callback_query.id` solo se procesa una vez — un `Set`
acotado (`processedCallbackIds`, tope 200 entradas, con una cola FIFO paralela para desalojar la
más vieja) descarta cualquier repetición del mismo id, sea porque Telegram reenvió el update o
porque el usuario logró tocar el botón dos veces antes de que el teclado desapareciera del lado
del cliente.

## Comandos

| Comando | Qué hace |
|---|---|
| `/status` | Lista los módulos con switch (`hasSwitch: true` en `modules.json`) que están corriendo AHORA — vía `SystemMetrics.moduleStates()` (misma fuente de verdad que `ModuleController`/`ModuleCatalog`). |
| `/start <modulo>` | `ModuleController.startModule(moduleId, context)` — mismo camino que el switch de la UI. Sin argumento, muestra `/help`. |
| `/stop <modulo>` | Pide confirmación (ver arriba) antes de `ModuleController.stopModule()`. |
| `/metrics` | CPU/RAM/almacenamiento/batería/red — vía `SystemMetrics.kt`. El % de CPU toma 2 muestras separadas por 400ms (mismo criterio que `MonitorFragment`). |
| `/ssh` | Estado de Remote/SSH — `ModuleController.isRunning("remote")` + puerto fijo `8022`. |
| `/help` | Lista de comandos. |

Parser de comandos: texto plano, sin NLP — `"/comando@NombreBot argumento"` (formato que Telegram
usa en chats grupales) también se reconoce, recortando el `@NombreBot` antes de comparar.

## UI — Ajustes → Notificaciones

El switch "🤖 Bot de Telegram (recibir comandos)" vive en la MISMA tarjeta que tiene
token/chat id/botón "Probar" (`ConfigFragment.setupTelegramSection()`) — no agrega campos nuevos,
reusa los existentes. Al activarlo:

1. Valida que token/chat id (los que están en pantalla en ese momento) no estén vacíos — si
   faltan, revierte el switch y muestra el mismo toast que "Probar" usa para el mismo caso.
2. Los guarda en `SharedPreferences` (mismas claves que `TelegramNotifier`) y en
   `pref_telegram_bot_enabled` (intención persistida — lo que el switch refleja al recargar la
   pantalla).
3. Llama a `TelegramBotService.start(context)`.
4. Si arrancó, invoca `BatteryRestrictionHelper.requestDisableBatteryRestrictions(activity)` —
   el mismo helper que usa el resto de la app para pedirle al usuario que excluya Kairos de las
   restricciones agresivas de batería por fabricante (Samsung/Xiaomi MIUI/Huawei/OPPO/Vivo/
   OnePlus/Asus) — un foreground service de larga duración con un hilo bloqueado en I/O de red
   gran parte del tiempo es exactamente el patrón que esas restricciones suelen matar tras horas
   en background.

Apagar el switch llama a `TelegramBotService.stop(context)` y borra la intención persistida.

## Secretos — nunca se muestran de nuevo

El campo de token arranca siempre vacío (con un hint que indica "ya hay uno guardado — déjalo
vacío para mantenerlo o escribe uno nuevo para reemplazarlo"), usa `inputType` de contraseña
(máscara de escritura), y una función `resolveTelegramToken()` resuelve al valor guardado solo si
el campo quedó vacío — el token guardado no se vuelve a mostrar en pantalla. El chat id (no es un
secreto en sí, es un identificador numérico sin el cual no se puede ni enviar ni recibir nada —
comparable al `chat.id` que ya aparece en cualquier mensaje reenviado) sí se muestra precargado.

El token no aparece en logs: ningún `KairosLogger.log()` de `TelegramBotService.kt`/
`TelegramNotifier.kt` incluye el valor del token o del chat id, ni se manda en ningún mensaje del
propio bot.

## Limitaciones conocidas

- **No sobrevive a que Android mate el proceso completo de la app.** No hay `BOOT_COMPLETED`
  receiver ni lógica explícita de auto-reinicio en `TermuxApplication.onCreate()` — si el
  usuario activó el bot y el proceso de Kairos muere (reinicio del teléfono, "Forzar cierre",
  el sistema mata el proceso por memoria), el switch en Ajustes va a seguir mostrando el estado
  persistido (`pref_telegram_bot_enabled = true`) pero el servicio real no está corriendo hasta
  que el usuario vuelva a abrir la app y reactivar el switch a mano. Se descartó un auto-arranque
  desde `TermuxApplication.onCreate()` (que corre en todos los procesos de la app, incluido
  `:xserver`) por el riesgo real de arrancar el servicio también en el proceso `:xserver` sin un
  guard confiable de "proceso principal" — dos procesos distintos llamando a `getUpdates` con el
  mismo offset competirían por los mismos updates. Si se retoma, el guard correcto es comparar
  `Application.getProcessName()` (API 28+) o `ActivityManager.getRunningAppProcesses()` (API
  26-27) contra el nombre de paquete sin sufijo `:`, antes de llamar a `TelegramBotService.start()`.
- **Solo whitelist de 1 chat id** — no hay lista de varios `from.id` autorizados. Si el usuario
  necesita operar el bot desde más de una cuenta de Telegram, no está soportado.
- **Sin gate "modo sin confirmaciones" independiente** — hoy la única confirmación es la de
  `/stop`, y no hay forma de exigir que el usuario "active" el control remoto desde el propio
  dispositivo antes de que funcione. Para el catálogo actual (pocos comandos, uno con
  confirmación) se considera proporcional; si el catálogo de comandos crece (ej. ejecutar comandos
  arbitrarios, instalar módulos), habría que reconsiderarlo.
- **`/start`/`/stop` no validan que `<modulo>` sea un id real de `modules.json` antes de llamar a
  `ModuleController`** — `startModule()` ya devuelve `(false, "Unknown module")` para un id
  inexistente, así que el usuario recibe un mensaje de error claro, pero no hay autocompletado ni
  lista de ids válidos dentro del propio chat (sí la hay vía `/status`, que lista los módulos con
  switch reales).
- **Sin comandos para otros módulos más allá de start/stop/status/metrics/ssh** (ej. no hay
  `/logs <modulo>`, no hay forma de mandar el output de instalación por Telegram).
- **`ActionApprovalGate` (`app/src/main/java/com/termux/app/util/ActionApprovalGate.kt`) no está
  conectado a este bot**, pese a que su propio KDoc dice "el primer consumidor real es el bot de
  Telegram": `TelegramBotService.kt` llama directo a `ModuleController.startModule()`/
  `stopModule()`, sin pasar por el gate ni por su política (`ActionType.MODULE_START`/
  `MODULE_STOP`, ambas `ApprovalPolicy.REMEMBERABLE`). En la práctica esto es CONSISTENTE con el
  resto de la app: el switch de la UI tampoco pasa por el gate para arrancar/parar un módulo (el
  tap del switch ES el consentimiento, sin diálogo) — de las 9 acciones de `ActionType`, solo
  `MODULE_UNINSTALL_DEEP`/`MODULE_REINSTALL_CLEAN` (`ApprovalPolicy.ALWAYS_ASK`) están conectadas
  de verdad, en `BaseModuleFragment.confirmUninstallModule()`. El resto del catálogo
  (`MODULE_START/STOP/STOP_ALL/INSTALL/UNINSTALL_SOFT`, política `REMEMBERABLE`) es código
  preparado pero sin ningún caller real todavía, en toda la app — no es una limitación específica
  del bot de Telegram. `/stop` tiene su propia confirmación en 2 pasos, independiente del gate.

## Archivos relevantes

- `app/src/main/java/com/termux/app/TelegramBotService.kt` — el servicio completo.
- `app/src/main/java/com/termux/app/util/TelegramNotifier.kt` — envío saliente (comparte las
  claves de preferencia `PREF_BOT_TOKEN`/`PREF_CHAT_ID`).
- `app/src/main/java/com/termux/app/util/SystemMetrics.kt` — fuente real de `/status` (vía
  `moduleStates()`) y `/metrics` (RAM/CPU/almacenamiento/batería/red).
- `app/src/main/java/com/termux/app/ui/ConfigFragment.kt` — `setupTelegramSection()`, switch
  dentro de la misma tarjeta.
- `app/src/main/AndroidManifest.xml` — declaración del `<service>` (proceso principal,
  `foregroundServiceType="dataSync"`, mismo tipo que `X11Service`/`RunCommandService`).
- `app/src/main/res/values/strings_config.xml` + `values-en/strings_config.xml` — strings del
  switch y de las respuestas del bot (`telegram_bot_*`).
