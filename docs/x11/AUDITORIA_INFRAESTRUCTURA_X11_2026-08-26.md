# Auditoría de infraestructura del servidor X11 embebido

Alcance: la infraestructura del servidor X11 embebido en sí (módulo `x11-server`, el servicio
`X11Service`, el ciclo de vida del proceso `:xserver`, el socket, y la capa de GPU) — no la
interfaz de usuario de Mini PC ni la comparación X11/Wayland/VNC/proot-distro (ver
[AUDITORIA_XLORIE_WAYLAND_2026-08-25.md](AUDITORIA_XLORIE_WAYLAND_2026-08-25.md)).

## De qué fork proviene realmente el módulo

Comparando la estructura de archivos, se confirma que el árbol vendorizado **no** es el
termux-x11 oficial: le faltan algunas piezas del proyecto oficial (un cargador propio, el paquete
de teclas extra original), y tiene de más todo un árbol orientado a Wine (manejador de Windows,
contenedor, utilidades de compresión, editor de registro de Windows) y soporte para visores
Meta Quest/XR — confirma que el árbol real vendorizado proviene de un fork orientado a correr
Wine/juegos Windows sobre Termux, no una copia directa del repositorio oficial de escritorio
Linux puro. El `.so` precompilado embebido es autoconsistente con ese mismo árbol fuente (no se
pudo confirmar el commit/versión exacta de termux-x11 upstream sobre la que se basa el fork, por
no existir changelog en el proyecto intermedio y por tratarse de un binario precompilado).

Los parches propios de Kairos sobre este fork (carga de librería nativa vía
`System.loadLibrary`, eliminación del fallback de APIs internas de Android, obtención de contexto
por reflexión, anotaciones de interoperabilidad Kotlin/Java, reglas de ofuscación) se confirmaron
presentes en el código real, sin desincronización con la documentación.

## Ciclo de vida del proceso `:xserver`

Arranque: `X11Service.start(context)` inicia un servicio en primer plano que crea el proceso
Android separado `:xserver`. En su creación, adquiere un **WakeLock parcial** (agregado para
evitar que el sistema mate el proceso en segundo plano sin avisar — un riesgo real en Android
moderno, donde el "Phantom Process Killer" puede terminar procesos en background incluso siendo
un servicio en primer plano), fuerza la variable `TMPDIR` (ver
[X11_EMBEBIDO.md](X11_EMBEBIDO.md)), y lanza el hilo que arranca el servidor X nativo.

**Detección de "servidor vivo" mejorada.** El chequeo original solo confirmaba que el *proceso*
Android existía (vía un comando de sistema que busca procesos por nombre), no que el servidor X
realmente estuviera aceptando conexiones en el socket — una ventana real entre "el proceso
existe" y "el socket está publicado y aceptando clientes". Se agregó una verificación adicional,
liviana (comprobación directa de existencia del archivo de socket, sin invocar ningún proceso
externo), y ahora el estado "corriendo" del servidor requiere que ambas condiciones se cumplan.
Esto reduce significativamente la ventana de falso positivo sin agregar latencia perceptible.

**Sin manejo explícito de caída/reinicio.** Si el proceso nativo del servidor termina antes de
tiempo, no existe ningún mecanismo de detección proactiva ni de reinicio automático — el usuario
tendría que notar que el servidor dejó de responder y volver a iniciarlo manualmente. El servicio
no sobreescribe el comportamiento por defecto de reinicio de Android (`START_STICKY`), así que si
el sistema mata el proceso por presión de memoria, es plausible que Android lo reviva
automáticamente — pero esto no está confirmado empíricamente.

**Cierre**: como el servidor nativo no expone ningún mecanismo propio de parada limpia, el cierre
del servicio termina forzando la finalización de todo el proceso `:xserver` — comportamiento
seguro porque corre aislado en su propio proceso, sin afectar al resto de la app.

## GPU: dos capas completamente separadas

Un hallazgo importante de esta auditoría es que el renderizado del visor embebido y la
aceleración GPU configurable en la app son dos cosas completamente distintas, que conviene no
confundir:

1. **Renderizado del visor embebido**: el compositing real ocurre dentro del binario nativo
   precompilado del servidor X (`libXlorie.so`), opaco desde el código Java/Kotlin — no hay
   ninguna señal de que este camino use ninguna de las opciones de GPU configurables de la app.
2. **GPU para clientes X dentro de Termux/proot**: la selección de método de GPU que sí expone la
   app (automático, software, Zink, VirGL, etc.) instala paquetes Mesa/VirGL/Zink dentro del
   entorno Termux/proot para que las *aplicaciones cliente* que corren dentro del escritorio
   (por ejemplo, un programa que use OpenGL) tengan aceleración — es aceleración del lado del
   cliente X, no del servidor/visor.

La detección de hardware GPU se basa en el nombre de la plataforma que reporta el fabricante del
chip (heurística por patrones de nombre conocidos de familias Adreno/Mali/Xclipse), no en una
consulta real de capacidades Vulkan/OpenGL — es razonable como base para un menú de
recomendación, pero puede subestimar hardware con nombres de plataforma atípicos (en ese caso cae
a un perfil "genérico" seguro por software).

## Socket y conexión: dos rutas distintas

Hay dos caminos de conexión distintos al mismo servidor X, no uno solo:

- **Clientes X reales dentro de Termux** (por ejemplo, un escritorio nativo lanzado desde la app)
  se conectan por el socket Unix de filesystem tradicional.
- **El visor propio embebido** (proceso principal de la app) no usa ese socket de filesystem en
  absoluto — se conecta vía IPC de Android (Binder/AIDL): el descriptor de archivo del socket X se
  transporta directamente por esa vía, sin abrir un segundo socket de filesystem para el visor.

## Consideración de seguridad: `sharedUserId`

Kairos declara un `sharedUserId` heredado del proyecto Termux original (requisito de
compatibilidad del bootstrap). Cualquier app instalada con ese mismo `sharedUserId` corre bajo el
mismo UID de Linux del sistema, y por lo tanto podría en teoría leer/escribir directamente el
socket X del filesystem, sin pasar por ningún mecanismo de Android (Binder, permisos de
exportación). Sin embargo, Android exige que un `sharedUserId` compartido esté firmado con la
misma clave criptográfica que la app que lo declaró primero — no es "cualquier app puede unirse
nombrando el mismo identificador", requiere estar firmada con el mismo certificado. El riesgo real
es entonces acotado: solo otra app compilada y firmada con la misma clave que Kairos/Termux
podría explotar esta vía. El socket X en sí no tiene ninguna autenticación adicional (no hay
cookies tipo `xauth`/MIT-MAGIC) — es de confianza total para cualquier proceso que logre abrirlo.
Este es un vector de riesgo bajo pero real, no mitigado por ningún control adicional del lado del
socket, y no documentado anteriormente.

## Veredicto general

**Funcional, con brechas de robustez no triviales, sin bugs bloqueantes conocidos sin arreglar.**
El visor (renderizado + entrada) está confirmado funcionando en dispositivo real. El arranque de
escritorio tiene fixes aplicados con causa raíz bien fundamentada mediante lectura de código
fuente de múltiples proyectos relacionados. Las brechas identificadas en esta auditoría son de
robustez, no de funcionalidad rota: la detección de "vivo" mejoró pero no cierra el 100% de la
ventana teórica, no hay manejo explícito de caída/reinicio del proceso nativo, y existe un vector
de acceso directo al socket vía `sharedUserId` compartido (riesgo bajo, acotado por firma
criptográfica, pero real). Ninguna de las tres justifica bloquear nada — son candidatas a
endurecimiento futuro, no incidentes activos.
