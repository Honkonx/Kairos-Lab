# Panel de seguridad SSH

## Motivación

Exponer el servidor SSH del dispositivo a internet mediante túneles (ver el documento sobre
exposición vía ngrok/Cloudflare) plantea una pregunta de seguridad legítima: ¿qué controles de
autenticación tiene visibles el usuario antes de hacerlo? Este panel resuelve ese punto,
mostrando y permitiendo modificar en vivo la configuración de seguridad real del servidor SSH.

## Configuración real del servidor SSH

Antes de construir el panel se confirmó el comportamiento real del servidor:

- **Contraseña**: la autenticación por contraseña usa PAM contra la contraseña real del usuario
  del sistema — no existe un mecanismo de autenticación propio de la aplicación por encima de
  eso.
- **Claves públicas**: gestionadas mediante el archivo estándar de claves autorizadas de
  OpenSSH.
- **Puerto**: fijo, no privilegiado (el proceso corre sin privilegios de root).
- **Login root**: deshabilitado por defecto.
- **Autenticación por contraseña**: habilitada por defecto — este era el punto real que
  necesitaba visibilidad: sin un control accesible, un usuario que expone el servidor a internet
  mediante un túnel queda con autenticación por contraseña habilitada sin saberlo ni poder
  cambiarlo desde la aplicación.

Ninguno de estos hechos requería cambios en el script de instalación del servidor SSH — la
brecha real era la ausencia de una interfaz para leer y modificar estos valores en tiempo real,
no un problema en la configuración de instalación.

## Qué se implementó

### Lectura y modificación de configuración

Se agregó una función que analiza la configuración real del servidor en cada consulta (nunca
cacheada), de modo que el panel siempre refleje el estado real incluso si el archivo de
configuración se edita manualmente fuera de la aplicación. Se exponen funciones para:

- Consultar el estado actual (puerto, autenticación por contraseña, login root, existencia de
  claves autorizadas, existencia de clave propia del dispositivo, estado del servicio).
- Cambiar el puerto (validado dentro del rango permitido para un proceso sin privilegios de
  root).
- Alternar la autenticación por contraseña — con un guardrail del lado del servidor que
  **rechaza deshabilitarla** si no existe al menos una clave autorizada, evitando que el usuario
  se bloquee a sí mismo sin ninguna forma de volver a entrar.
- Alternar el login root.
- Generar el par de claves propio del dispositivo (usado como cliente para conectarse a otros
  servidores, complementario a la gestión de claves autorizadas de terceros).

Cualquier cambio de configuración reinicia automáticamente el servicio si ya estaba corriendo,
dado que el servidor SSH no relee su configuración por sí solo — sin este paso, el panel
mostraría un valor nuevo mientras el proceso en memoria siguiera operando con la configuración
anterior.

### Interfaz

Se agregó una sección "Seguridad SSH" con:

| Campo | Fuente |
|---|---|
| Puerto | Configuración real del servidor |
| Autenticación por contraseña | Habilitada / Deshabilitada |
| Login root | Permitido / Bloqueado |
| Claves autorizadas | Presentes / Ninguna |
| Clave propia del dispositivo | Generada / No generada |

Con acciones para cambiar el puerto, alternar cada control (con confirmación explícita para los
cambios de mayor riesgo — deshabilitar la contraseña o habilitar el login root), generar la clave
propia, y copiar la clave pública propia al portapapeles.

### Consistencia con el sistema de túneles

El gestor de túneles resuelve el puerto del servicio SSH consultando la configuración real de
seguridad en lugar de usar un valor fijo — de otro modo, si el usuario cambiaba el puerto desde
este panel, el sistema de túneles seguiría intentando exponer el puerto anterior, ya sin ningún
servicio escuchando ahí.

## Principios de diseño de los valores por defecto

- La autenticación por contraseña se mantiene habilitada por defecto tal como la instala el
  servidor — deshabilitarla automáticamente sin que el usuario haya agregado antes una clave
  pública lo dejaría sin forma de entrar. Deshabilitarla es siempre una acción explícita, con
  guardrail del lado del servidor además de la confirmación en la interfaz.
- El login root se mantiene bloqueado por defecto, coincidiendo con la configuración de
  instalación — el control para habilitarlo existe pero nunca se activa como efecto colateral de
  otra acción, siempre requiere confirmación explícita con una advertencia sobre el riesgo real.
- El puerto no cambia de su valor por defecto salvo acción explícita del usuario, dentro del
  rango técnicamente válido para un proceso sin privilegios de root.
- El reinicio automático del servicio tras un cambio de configuración solo ocurre si el servicio
  ya estaba corriendo — si no lo estaba, no se arranca automáticamente como efecto colateral.

## Interruptor de "requerir siempre autenticación por clave"

Se incorporó un interruptor visible y directo en el panel, con la semántica de un servidor remoto
tradicional: **activado** significa que la autenticación por contraseña queda deshabilitada
(solo se puede entrar con una clave válida, incluso desde el propio usuario en otro dispositivo
sin la clave correspondiente); **desactivado** significa que la contraseña vuelve a estar
permitida, que es el comportamiento por defecto de la instalación.

### Guardrail de dos capas

1. **En la interfaz**, antes de mostrar cualquier diálogo: si no hay ninguna clave autorizada
   agregada, el interruptor se bloquea de inmediato con un aviso indicando que primero hay que
   agregar la clave pública propia, y el control vuelve a reflejar el estado real (nunca queda
   visualmente activado sin que el cambio haya aplicado de verdad).
2. **En el servidor de lógica de negocio**: la función que deshabilita la autenticación por
   contraseña sigue rechazando la operación si no existe al menos una clave autorizada — esta es
   la garantía real, independiente de cualquier camino de interfaz que pudiera saltearse la
   primera capa.

Si existen claves autorizadas, se muestra un diálogo de confirmación con la analogía de un
servidor remoto tradicional antes de aplicar el cambio; cancelar el diálogo revierte el
interruptor a su estado real. Desactivar el interruptor (volver a permitir contraseña) no tiene
restricción ni diálogo de confirmación, ya que reducir la restricción no tiene el mismo riesgo de
bloqueo accidental.

## Fuera de alcance (deliberado)

- No se implementó rotación ni políticas de complejidad de contraseña — el sistema de
  autenticación subyacente no lo soporta de forma nativa sin componentes adicionales.
- No se implementó limitación de intentos por IP (tipo fail2ban) — el límite de intentos de
  autenticación por conexión ya está configurado en el servidor; un mecanismo de bloqueo por IP
  es una funcionalidad más amplia, fuera del alcance de un panel de visibilidad y control básico.
