# Cliente SSH (modo Emisor)

## Qué es

El módulo Remote implementa acceso SSH bidireccional: el dispositivo puede actuar como
**servidor** (siendo controlado remotamente) o como **cliente** (controlando otros servidores).
La pantalla del módulo se organiza en pestañas:

| Pestaña | Contenido | Dirección |
|---|---|---|
| **Receptor** | Estado del servicio SSH, información de conexión, gestión de claves públicas autorizadas, contraseña, conexiones activas, huella del servidor | El dispositivo siendo controlado |
| **Seguridad** | Puerto, exigencia de autenticación por clave, login root, claves autorizadas, clave propia del dispositivo | Configuración del servidor SSH local |
| **Cloudflare** | Túnel SSH vía Cloudflare (token, instrucciones de conexión) | Exposición a internet sin IP pública |
| **Red** | Campo de IP y escaneo de la red local | Descubrimiento de servidores en la LAN |
| **Emisor** | Cliente SSH: guardar, ver, borrar y conectar a servidores remotos | El dispositivo controlando otros equipos |

## Cliente SSH (pestaña Emisor)

### Modelo de datos

Cada conexión guardada se representa con un identificador, alias, host, puerto, usuario, un flag
que indica si debe usar la clave propia del dispositivo, y la marca de tiempo de la última
verificación exitosa. Las conexiones se persisten en un registro local como un arreglo JSON
compacto, siguiendo el mismo patrón usado para los túneles guardados del resto de la aplicación:
una única clave de registro por tipo de dato, con bloqueo exclusivo por operación de escritura
para evitar condiciones de carrera.

### Flujo de uso

1. **Agregar conexión**: un diálogo permite ingresar alias (opcional), host, puerto, usuario y un
   checkbox para usar la clave propia del dispositivo. A diferencia del puerto del servidor SSH
   local (que exige un valor ≥1024 por no correr con privilegios de root), el puerto de una
   conexión saliente no tiene esa restricción, ya que el servidor remoto puede correr con
   privilegios completos en el puerto estándar 22.
2. **Listar conexiones guardadas**: la lista se recalcula leyendo el registro en un hilo de
   fondo en cada render (nunca se cachea), mostrando alias, `usuario@host:puerto` y un indicador
   relativo de la última vez que se confirmó alcanzable.
3. **Conectar**: antes de abrir la sesión se realiza una verificación TCP corta (timeout de 1.5
   segundos) contra el host y puerto guardados. Responda o no, se abre una sesión de terminal
   real ejecutando el cliente `ssh` estándar con los parámetros correspondientes, reutilizando el
   mecanismo general de lanzamiento de comandos de terminal de la aplicación en vez de
   reimplementar el manejo de sesiones. Si la conexión está marcada para usar la clave propia
   pero esta todavía no fue generada, el comando se arma sin el flag de identidad y el cliente
   `ssh` solicitará contraseña de forma interactiva.
4. **Borrar**: elimina únicamente la referencia guardada localmente — nunca interactúa con el
   servidor remoto.

### Alcance del "monitoreo"

La verificación de alcanzabilidad implementada es puntual: ocurre justo antes de intentar
conectar, no es un sondeo periódico en segundo plano ni una sesión de monitoreo continuo. Si
responde, se actualiza la marca de "última vez confirmado" que se muestra en la lista. Un
monitoreo continuo real (sondeo periódico en background de todas las conexiones guardadas)
implicaría un costo de batería y CPU distinto, y queda fuera de este alcance inicial como
decisión consciente de diseño.

### Modelo de credenciales

El registro de conexiones **nunca** almacena contraseñas: la autenticación por contraseña queda
completamente delegada al cliente `ssh` real dentro de la sesión de terminal interactiva — la
aplicación no la ve ni la persiste en ningún momento.

Para autenticación por clave, se reutiliza el par de claves propio del dispositivo (generado
bajo demanda, sin cifrado adicional, con permisos restringidos equivalentes a los que cualquier
usuario Linux/Termux normal aplica a su propia clave SSH).

### Claves privadas importadas

Además de la clave propia del dispositivo, es posible importar la clave privada de un tercero,
con dos modos de uso:

- **Guardado persistente**: la clave se almacena en un directorio dedicado con permisos
  restrictivos (archivo y directorio). Ninguna función del sistema vuelve a exponer el contenido
  una vez guardado — solo se muestra el alias y la huella digital (`fingerprint`, un derivado
  unidireccional no reversible calculado con `ssh-keygen`). La interfaz no ofrece ninguna acción
  para "ver" la clave; las únicas acciones disponibles son usarla (por referencia), reemplazarla
  (sobrescribiendo sin leer el valor anterior) o borrarla.
- **Uso efímero (solo para la sesión actual)**: la clave nunca se agrega a la lista de claves
  importadas ni al registro persistente — se escribe en un archivo transitorio, se usa para
  lanzar el comando `ssh`, y un proceso de fondo la elimina automáticamente pasados unos
  segundos. El pequeño margen de espera antes del borrado existe porque la aplicación no controla
  el instante exacto en que el proceso `ssh` de la sesión de terminal abre el archivo — un
  borrado demasiado agresivo podría eliminar la clave antes de que se haya leído.

Este modelo sigue el mismo principio de seguridad aplicado en todo el módulo Remote: un secreto,
una vez guardado, nunca vuelve a mostrarse — solo puede usarse, reemplazarse o borrarse.
