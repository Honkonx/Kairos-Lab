# Cliente SSH — pestaña "Emisor"

SSH en Kairos funciona en ambas direcciones: el dispositivo puede ser controlado (servidor SSH
local) y puede controlar otros equipos (cliente SSH). La pantalla del módulo Remote/SSH se
organiza en 5 pestañas:

| Pestaña | Contenido | Dirección |
|---|---|---|
| **Receptor** | Estado del servidor SSH, info de conexión, agregar clave pública, contraseña, conexiones activas, copiar comando, huella del servidor, mantenimiento | Kairos siendo controlado |
| **Seguridad** | Puerto, requerir clave siempre, login root, claves autorizadas, clave propia del dispositivo | Configuración del servidor SSH local |
| **Cloudflare** | Túnel SSH vía Cloudflare (token, cómo conectarse) | Exposición sin necesidad de IP pública |
| **Red** | Campo de IP y escaneo de la red local | Descubrimiento de servidores en la LAN |
| **Emisor** | Cliente SSH: guardar, ver, borrar y conectar a servidores remotos | Kairos controlando otros equipos |

## Cliente SSH (pestaña Emisor)

### Modelo de datos

Cada conexión guardada tiene: identificador, alias, host, puerto, usuario, si usa la clave
propia del dispositivo, y la fecha del último contacto confirmado. Se persiste como un único
array JSON compacto — el mismo patrón que usa el resto de la configuración estructurada de la
app, evitando escanear múltiples claves numeradas sueltas.

### Flujo: agregar, ver, conectar, borrar

1. **Agregar**: diálogo con alias opcional, host, puerto (editable, sin restricción de rango —
   a diferencia del puerto del servidor SSH local, que sí exige un valor alto sin privilegios de
   root; el puerto de un servidor remoto puede ser el 22 estándar de un equipo que sí corre como
   root), usuario, y un checkbox para usar la clave propia del dispositivo.
2. **Ver**: lista la tarjeta de servidores guardados, releyendo el estado en un hilo de fondo
   cada vez (nunca cacheado) — cada fila muestra alias, `usuario@host:puerto`, y una indicación
   de la última vez que se confirmó contacto.
3. **Conectar**: hace un chequeo TCP corto al host y puerto guardados; si responde, actualiza la
   fecha de última confirmación. Responda o no, abre una sesión de terminal real con el comando
   SSH correspondiente (usando la clave propia si el usuario lo eligió y la clave ya existe; si
   no, el cliente SSH pedirá la contraseña de forma interactiva).
4. **Borrar**: confirmación y eliminación de la entrada guardada. No afecta al servidor remoto,
   solo a la referencia local.

### Alcance del "monitoreo"

Lo implementado es un chequeo puntual (una conexión TCP corta) justo antes de conectar — no es
un sondeo continuo en segundo plano ni una sesión de monitoreo permanente. Si responde, se
guarda como "última vez confirmado" y la fila lo muestra en minutos relativos. Un monitoreo
continuo real (sondeo periódico en background de todas las conexiones guardadas) implica un
costo de batería/CPU distinto y queda fuera de este alcance.

### Manejo de credenciales

La lista de servidores guardados nunca contiene una contraseña — la autenticación por
contraseña queda completamente interactiva en el cliente SSH real dentro de la terminal, Kairos
no la ve ni la persiste en ningún momento. Para autenticación por clave, se reutiliza la clave
propia del dispositivo (par de claves ed25519 generado localmente, con permisos restringidos),
la misma que usa el servidor SSH local para aceptar conexiones entrantes.

### Claves privadas importadas (pestaña Receptor)

Además de la clave propia del dispositivo, la pestaña Receptor permite importar la clave privada
de un tercero, con dos modos de uso:

- **Guardar de forma persistente**: la clave se escribe en un archivo con permisos restringidos
  dentro de un directorio dedicado. Las únicas acciones disponibles sobre una clave ya guardada
  son usarla (por referencia, nunca exponiendo su valor), reemplazarla (el campo de reemplazo
  siempre arranca vacío, nunca precargado con la clave anterior) o borrarla.
- **Usar solo para la sesión actual**: la clave no se agrega a la lista de claves guardadas — se
  escribe en un archivo temporal, se usa para lanzar esa conexión SSH puntual, y se borra
  automáticamente unos segundos después de iniciada la sesión.

Esto refleja una regla de diseño general del proyecto: una vez que un secreto (clave SSH, token,
contraseña) queda guardado en Kairos, la interfaz nunca vuelve a mostrar su contenido — la
identificación visual de una clave guardada es su huella digital (fingerprint, un derivado
unidireccional y no reversible de la clave), nunca la clave en sí.
