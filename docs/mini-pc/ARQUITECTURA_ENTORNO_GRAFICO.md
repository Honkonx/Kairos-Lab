# Arquitectura del módulo Entorno (proot-distro, GPU, VNC)

El módulo **Entorno** es la base de la funcionalidad "mini PC" de Kairos: gestiona
distribuciones Linux completas dentro de contenedores `proot-distro` (sin root), la selección
del método de renderizado por GPU, los lanzadores de aplicaciones en el escritorio, y la
exclusividad entre el escritorio nativo de Termux y un escritorio corriendo dentro de una
distro. Este documento describe la arquitectura real de esa capa y los problemas técnicos reales
que se identificaron y corrigieron a medida que la funcionalidad maduró.

## 1. Gestión de distribuciones (`proot-distro`)

Todas las operaciones sobre distribuciones (instalar, eliminar, instalar apps dentro de una
distro, eliminar apps) siguen el mismo contrato de validación antes de ejecutar cualquier
comando: comprobar que el nombre de la distro es conocido y que el binario `proot-distro` está
disponible. Las funciones que dependen de que la distro ya esté instalada (instalar un entorno
de escritorio dentro de ella, arrancarlo, hacer backup, montar el puente de carpetas) usan un
criterio más estricto: resuelven el directorio real de la distro instalada en vez de solo
validar el nombre.

| Operación | Exige "nombre conocido" | Exige "distro efectivamente instalada" |
|---|---|---|
| Listar distros | — | — |
| Instalar distro | Sí | — |
| Eliminar distro | Sí | — |
| Instalar escritorio en distro | Sí (vía resolución de directorio) | Sí |
| Iniciar escritorio en distro | Sí (vía resolución de directorio) | Sí |
| Instalar/eliminar app en distro | Sí | — |
| Backup de distro | Sí (implícito) | Sí |
| Montar puente de proyectos | Sí (implícito) | Sí |

Cuando cualquiera de las dos validaciones falla, la app devuelve un mensaje accionable (por
ejemplo, "proot-distro no disponible — instalá Entorno primero") en lugar de propagar el error
crudo del proceso subyacente.

Cada operación de instalación/eliminación de app dentro de una distro persiste su estado en un
archivo JSON local (`distro_apps.json`). Como estas operaciones pueden dispararse desde hilos
independientes (por ejemplo, instalar una app mientras se elimina otra desde otro diálogo), la
lectura-modificación-escritura de ese archivo está protegida con un lock de archivo dedicado,
evitando condiciones de carrera que corromperían el estado persistido.

## 2. Exclusividad entre escritorio nativo y escritorio dentro de una distro

Ambos modos —escritorio nativo sobre Termux y escritorio dentro de una distro `proot`— comparten
el mismo servidor X11 embebido, así que solo uno puede estar activo a la vez. El mecanismo que
garantiza esto:

- Antes de arrancar cualquiera de los dos modos, se valida contra el modo activo actual (si hay
  uno).
- El estado activo no se basa solo en una marca persistida: se verifica con `pgrep` que el
  proceso real siga vivo. Si la marca quedó obsoleta (por ejemplo, tras un cierre inesperado o
  un `kill` externo), se limpia automáticamente en vez de bloquear al usuario indefinidamente.
- Si el usuario intenta arrancar un modo mientras el otro está activo, la UI ofrece detener la
  sesión activa y reintentar la acción original en un solo paso, en vez de dejarlo en un callejón
  sin salida.

## 3. Lanzadores de escritorio y desinstalación de módulos

Kairos genera automáticamente lanzadores `.desktop` (menú de aplicaciones + entradas de
autoinicio opcionales) para cada CLI instalado a través de la app. Cuando un módulo se
desinstala, esos lanzadores se eliminan junto con el resto de sus archivos y su entrada de
registro — de lo contrario quedaban íconos rotos en el escritorio apuntando a un comando que ya
no existe.

## 4. Aceleración por GPU

El método de renderizado (software puro, Zink, VirGL, según el hardware) se selecciona por el
usuario y se aplica mediante variables de entorno estándar de Mesa (`GALLIUM_DRIVER`,
`MESA_GL_VERSION_OVERRIDE`, etc.). Un punto importante de la arquitectura: `proot-distro`
arranca un entorno limpio para cada sesión — las variables exportadas en el shell del host **no**
se heredan automáticamente dentro del login de la distro. Por eso, cuando el usuario activa
aceleración por GPU, esas variables se pasan explícitamente como parte del entorno del propio
comando de login (`proot-distro login ... -- env <variables> <comando>`), tanto para el modo
nativo como para el modo distro. Sin este paso explícito, un escritorio dentro de una distro
corre siempre en software puro (`llvmpipe`) sin importar el método de GPU elegido.

## 5. El servidor X11 corre en un proceso Android separado — implicaciones de arranque

El servidor X11 embebido (basado en un fork de termux-x11) corre en su propio proceso Android,
separado del proceso principal de la app. Su arranque es asíncrono: el socket Unix que expone
(`$PREFIX/tmp/.X11-unix/X<n>`) no está garantizado a existir en el instante en que se dispara el
comando que arranca el entorno de escritorio. Si un cliente X intenta conectar antes de que el
socket exista, la conexión falla inmediatamente ("Can't open display") y el escritorio no
arranca — un síntoma que se percibe como "pantalla negra" o "no pasa nada".

La solución aplicada es un retry loop con timeout acotado (hasta 10 segundos) que espera
activamente a que el socket exista antes de intentar el login/arranque del entorno de escritorio,
tanto para el camino nativo como para el camino dentro de una distro. El chequeo de "¿el proceso
sigue vivo?" que hace la app para confirmar que el arranque tuvo éxito usa un margen de tiempo
mayor que ese retry loop, para no reportar un falso negativo sobre un arranque que todavía está
en curso.

## 6. VNC — requisitos de contraseña y feedback de progreso

TigerVNC exige una contraseña interactiva la primera vez que se usa, salvo que se le indique
explícitamente lo contrario. Como Kairos lanza el proceso sin una terminal interactiva
disponible para responder ese prompt, si no existe todavía un archivo de contraseña generado
(`~/.vnc/passwd`), el arranque de VNC se configura automáticamente sin autenticación
(`-SecurityTypes None`). Esto es seguro en este contexto concreto porque el servidor VNC se
enlaza únicamente a `localhost` y el visor incluido en la app solo se conecta a esa misma
dirección local — nunca se expone la sesión sin contraseña a la red. Si el usuario configura una
contraseña real, esa configuración se respeta sin cambios.

Tanto la instalación de TigerVNC como el arranque del servidor de escritorio muestran progreso
en vivo en la interfaz (en vez de un indicador fijo sin actualizar durante toda la operación),
consistente con la aceleración por GPU y el resto de operaciones largas del módulo.

## 7. Manejo de errores accionables

Cuando una operación larga falla (por ejemplo, la instalación de un entorno de escritorio dentro
de una distro por un problema de red o de espacio en disco), la interfaz muestra el detalle real
del error —no solo un mensaje genérico— capturando la salida del proceso subyacente en vez de
descartarla. Esto reduce a un solo intento el ciclo de "algo falló, sin saber por qué" que
antes requería reproducir el problema para poder diagnosticarlo.

## 8. Detención ordenada de sesiones de escritorio

Al detener una sesión de escritorio para reintentar el arranque del otro modo, el proceso de
apagado espera de forma acotada (hasta unos segundos) a que los procesos terminen realmente
antes de reportar éxito, en vez de asumir que una señal de terminación implica una detención
inmediata. Esto evita que un reintento rápido choque contra procesos que técnicamente ya
recibieron la señal de cierre pero todavía no liberaron el display X11.
