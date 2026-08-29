# Exponer SSH del dispositivo mediante túneles (ngrok / Cloudflare)

## Contexto

El servidor SSH embebido en el dispositivo (basado en el paquete OpenSSH real de Termux, no una
reimplementación limitada) permite que el dispositivo funcione de forma similar a un servidor
remoto ("VPS") accesible por SSH. Este documento describe qué capacidades típicas de un servidor
remoto ya estaban cubiertas y qué se agregó para exponer ese SSH a internet sin necesidad de una
IP pública.

## Capacidades evaluadas

| Capacidad típica de un servidor remoto | Estado |
|---|---|
| Claves de host SSH persistentes (no regenerarse en cada arranque) | Ya correcto — `ssh-keygen -A` es idempotente y solo genera las claves de host que faltan, nunca sobrescribe una existente. Las claves solo se pierden si se borra por completo el directorio de configuración SSH (reinstalación completa), comportamiento equivalente a cualquier instalación fresca de OpenSSH |
| Gestión de puerto | Puerto fijo, no privilegiado (el proceso corre sin privilegios de root, por lo que solo puede enlazar puertos ≥1024) |
| Servicio SSH corriendo de forma confiable en segundo plano | Cubierto — el daemon se reinicia al reconectar, sin necesidad de un supervisor adicional |
| Reenvío de puertos (`-L`/`-R`/`-D`) | Ya soportado de forma nativa por cualquier cliente SSH que se conecte — es una característica estándar de OpenSSH que viene habilitada por defecto en la configuración |
| Exponer el SSH del dispositivo a internet sin IP pública | Ampliado — ver más abajo |

## Exposición vía túnel genérico

El sistema de túneles de la aplicación (usado también para exponer otros servicios locales como
paneles web) se extendió para soportar tráfico TCP crudo, necesario para SSH:

- Se agregó el módulo SSH a la lista de servicios conocidos del gestor de túneles, con su puerto
  correspondiente.
- Se introdujo una categoría de "módulos solo-TCP": a diferencia del resto de los servicios
  (todos HTTP), SSH requiere un comando de túnel distinto.
  - Con **ngrok**, se usa el modo de túnel TCP (`ngrok tcp <puerto>`) en vez del modo HTTP.
  - Con **Cloudflare sin token**, se devuelve un error explícito en vez de intentar un túnel
    HTTP que nunca funcionaría para tráfico TCP crudo.
  - Con **Cloudflare con token** (túnel nombrado), no fue necesario ningún cambio de
    comportamiento: ese modo no está atado a un tipo de tráfico específico — el tipo de ingreso
    (HTTP o TCP) se define del lado del panel de Cloudflare al crear el túnel. Solo hacía falta
    que el módulo SSH apareciera en la lista de servicios disponibles.
- Se agregó reconocimiento del formato de URL que usa el modo túnel TCP de ngrok (que usa el
  esquema `tcp://` en vez de `https://`), necesario para que la interfaz pudiera mostrar
  correctamente la dirección pública asignada.

En la interfaz, los módulos "solo-TCP" ocultan las opciones que no aplican (túnel anónimo
HTTP-only, selección de dominio personalizado) y muestran una advertencia distinta a la de otros
servicios al momento de exponerlos: en vez de indicar que "cualquiera con el enlace podrá usarlo
sin autenticación" (cierto para servicios sin login propio), para SSH se aclara que sigue
requiriendo usuario/contraseña o clave válida, recomendando explícitamente el uso de una
contraseña fuerte o autenticación por clave pública, dado que el puerto queda alcanzable
públicamente.

El resultado es que el usuario puede exponer el servidor SSH del dispositivo con dos métodos
alternativos:

- **Cloudflare (túnel nombrado con token)** — reutiliza la misma configuración ya usada para
  otros servicios.
- **ngrok (modo TCP)** — primera alternativa disponible que no depende de tener una cuenta
  Cloudflare con Zero Trust configurado, solo requiere un token de autenticación de ngrok.

Ambos métodos conviven sin reemplazarse — el usuario elige cuál usar según lo que ya tenga
configurado.

## Decisiones de diseño explícitamente diferidas

Las siguientes mejoras se identificaron pero se dejaron fuera de esta implementación por
requerir decisiones de arquitectura mayores o presentar riesgo de seguridad si se activan sin que
el usuario comprenda las implicancias:

1. **Puerto SSH configurable desde la interfaz.** Implicaría reescribir la configuración del
   servidor en caliente, reiniciarlo, y mantener sincronizados varios componentes — con riesgo
   real de que un valor incorrecto deje al servicio sin arrancar. Ver el documento del panel de
   seguridad SSH para el estado actual de esta característica.
2. **Toggle de login root / intentos máximos de autenticación configurables desde la UI.** Los
   valores por defecto ya son seguros (login root deshabilitado); exponer un control para
   cambiarlos requiere un diseño de interfaz que deje el riesgo completamente explícito antes de
   aplicarlo.
3. **Dirección TCP reservada para túneles ngrok**, que permitiría una URL estable entre reinicios
   del túnel en vez de una nueva dirección aleatoria cada vez — es una funcionalidad de cuenta
   paga de ngrok, no implementada hasta contar con un caso de uso concreto.
4. **Autenticación por clave pública forzada por defecto.** Sería más seguro para un dispositivo
   expuesto a internet, pero cambiar el comportamiento por defecto rompería el flujo de cualquier
   usuario que todavía no haya cargado una clave pública — se prefiere que sea una decisión
   explícita del usuario en vez de un cambio de comportamiento automático.

Ver el documento del panel de seguridad SSH para el desarrollo posterior de varios de estos
puntos.
