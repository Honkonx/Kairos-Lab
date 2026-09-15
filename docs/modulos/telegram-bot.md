# Bot de Telegram

**Pieza nativa de Kairos**, no un módulo instalable de `modulos/` — mismo criterio que Homelab:
conceptualmente es una feature de control de la app, así que se documenta acá aunque no tenga
script propio ni aparezca en la Tienda.

---

**App:** Kairos (fork termux-app)
**Servicio:** `TelegramBotService.kt` (foreground service, proceso principal de la app)
**UI:** Ajustes → Notificaciones

---

## 1. Qué es

Kairos ya podía **mandar** notificaciones a un chat de Telegram (arranque/caída de un módulo,
etc.). Esta pieza agrega la dirección contraria: un bot de Telegram que **recibe** comandos de
texto y botones, y los traduce en acciones reales sobre los módulos de Kairos — arrancar/parar
un módulo, o consultar qué está corriendo — todo sin exponer ningún puerto público ni necesitar
un dominio propio.

Funciona por *long-polling*: el propio teléfono le pregunta a Telegram "¿hay algo nuevo?" en un
bucle, en vez de que Telegram tenga que llamar a un servidor de Kairos (que no existe como
endpoint público). Es el patrón correcto para un cliente móvil sin servidor propio detrás.

## 2. Seguridad — lista blanca de un solo usuario

El mismo `chat_id` que ya se configuraba para las notificaciones salientes cumple ahora doble
función: destino de los mensajes salientes **y** único usuario autorizado a mandar comandos.
Cualquier mensaje o botón tocado por alguien que no sea ese usuario se ignora — la
verificación es "a prueba de fallos": si falta el token o el chat id, el bot directamente no
arranca, en vez de arrancar sin protección.

## 3. Confirmación en dos pasos para acciones riesgosas

El único comando que puede afectar algo en uso (`/stop`, parar un módulo) nunca se ejecuta
directo desde el texto del comando: el bot responde con dos botones ("Sí" / "No") y solo
ejecuta la acción real cuando el usuario confirma tocando el botón correcto. El mensaje se
actualiza después con el resultado, y los botones desaparecen para evitar que se puedan volver
a tocar sobre un mensaje ya resuelto.

## 4. Comandos disponibles

| Comando | Qué hace |
|---|---|
| `/status` | Lista los módulos que están corriendo ahora mismo. |
| `/start <modulo>` | Arranca un módulo — mismo camino que tocar el switch en la app. |
| `/stop <modulo>` | Pide confirmación (ver arriba) antes de parar un módulo. |
| `/ssh` | Estado de Remote/SSH y su puerto. |
| `/help` | Lista de comandos. |

## 5. Activarlo

El switch "Bot de Telegram (recibir comandos)" vive en la misma tarjeta de Ajustes →
Notificaciones donde ya se configura el token del bot y el chat id para las notificaciones
salientes — no agrega campos nuevos. Al activarlo, si el dispositivo tiene restricciones
agresivas de batería por fabricante, Kairos pide excluir la app de esas restricciones (un
servicio de larga duración escuchando red en segundo plano es justo el tipo de proceso que esas
restricciones suelen matar tras unas horas).

## 6. Limitaciones conocidas (v1)

- **No sobrevive a que Android mate el proceso completo de la app** (reinicio del teléfono,
  "Forzar cierre", o que el sistema lo mate por memoria) — hay que reabrir Kairos y reactivar el
  switch a mano.
- **Solo admite un chat id autorizado** — no hay lista de varios usuarios permitidos.
- **Comandos limitados a start/stop/status/ssh** — todavía no hay, por ejemplo, un comando para
  ver logs de un módulo por Telegram.

## 7. El token nunca se vuelve a mostrar

El token del bot y el chat id se guardan con el mismo criterio de seguridad que cualquier otra
credencial de Kairos: los campos de la propia tarjeta de Ajustes son el único lugar donde se ven
— no aparecen en ningún log ni en ningún mensaje que el propio bot mande.
