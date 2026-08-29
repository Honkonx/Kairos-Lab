# Historial de bugs reales — X11 embebido

Este documento resume los bugs reales encontrados y corregidos durante varias auditorías de
código del subsistema X11 embebido de Kairos, incluyendo su causa raíz confirmada y el fix
aplicado.

## Intents del visor sin `FLAG_ACTIVITY_NEW_TASK`

Tras corregir el bug crítico de `taskAffinity` compartida (ver
[X11_EMBEBIDO.md](X11_EMBEBIDO.md)), se encontró que tres puntos de entrada distintos a la
pantalla del visor X11 (instalar y arrancar XFCE4 nativo, arrancar el escritorio nativo, arrancar
el escritorio dentro de una distro) construían el `Intent` de lanzamiento sin el flag
`FLAG_ACTIVITY_NEW_TASK` — exactamente el flag necesario para que la `taskAffinity` dedicada del
visor tenga efecto de forma confiable. Sin él, esos tres caminos podían reintroducir
silenciosamente el bug original (el visor compartiendo tarea con la actividad principal). Se
agregó el flag a los tres puntos, con un comentario cruzado para evitar que un futuro cuarto punto
de entrada repita el mismo olvido.

## WakeLock parcial ausente

Comparando la arquitectura de Kairos contra un proyecto de referencia con un diseño equivalente
(servidor X embebido corriendo en su propio proceso Android vía un servicio en primer plano), se
encontró que ese proyecto adquiere un `WakeLock` parcial mientras el proceso está vivo — Kairos no
lo hacía, dejando el servidor expuesto a que Android lo matara silenciosamente en segundo plano
(el "Phantom Process Killer"/Doze del sistema puede terminar procesos en background incluso siendo
un servicio en primer plano). Se agregó la adquisición del WakeLock al arrancar el servicio y su
liberación al detenerlo, con manejo defensivo (el servidor sigue funcionando aunque la adquisición
fallara, solo sin esa protección extra).

## Bug de comportamiento — no una regresión

Se revisó exhaustivamente si actualizar el módulo vendorizado a la versión más reciente del
proyecto upstream sería viable. La conclusión es que **no es viable hoy**: la versión más nueva
requiere una versión mayor de la herramienta de build (Android Gradle Plugin 9.x, mientras Kairos
usa 8.x), no incluye ningún binario nativo precompilado (habría que compilar el servidor X desde
cero vía NDK, exactamente lo que la integración actual evita a propósito), y varios métodos que
las subclases propias de Kairos invocan directamente ya no existen con la misma firma en el código
nuevo — sería necesario reescribir, no solo recompilar. El único elemento razonablemente
portable identificado es un módulo de logging de diagnóstico autocontenido, de bajo tamaño — una
mejora opcional, no prioritaria.

## Falta de declaración de Activities de Input Control (crash confirmado)

El módulo vendorizado incluye una serie de Activities relacionadas con perfiles de control de
entrada (gamepad/controles táctiles personalizados), heredadas del linaje del que se copió el
módulo. Ninguna de las tres estaba declarada en el manifest de la aplicación — cuando el usuario
tocaba el diálogo de configuración de un perfil de control, Android lanzaba una excepción de
"actividad no encontrada" sin capturar, cerrando la aplicación completa. **Causa raíz confirmada**
por la ausencia total de esas tres clases en el manifest. **Fix**: se agregaron las tres
declaraciones de Activity necesarias, cada una con el tema visual apropiado según si su layout
trae su propia barra de herramientas o depende del tema del sistema — sin modificar una sola
línea del módulo vendorizado.

## Segundo bug del mismo flujo: NullPointerException en "Open Controller"

Después del fix anterior, un reporte real de uso en dispositivo confirmó que el mismo flujo
seguía fallando — esta vez por una causa **distinta**. El diálogo de configuración de controles
de entrada asume que ciertos campos (referencias al visor real, al gestor de controles, a la
vista de entrada) ya están inicializados — pero esos campos solo se inicializan cuando la
Activity que abre el diálogo es el visor real del escritorio. La pantalla standalone de
"Configuración de X11" de Kairos es una subclase distinta, que nunca pasa por esa
inicialización, así que esos campos quedaban nulos y el diálogo lanzaba una excepción de puntero
nulo en su primera línea.

**Fix**: sin modificar el módulo vendorizado, se sobreescribió el método en la subclase propia de
Kairos para verificar esos campos antes de delegar en la implementación original — si están
presentes (caso real: se abrió desde el visor), delega normalmente; si están ausentes (caso real:
pantalla standalone), muestra un aviso pidiendo abrir primero el visor del escritorio, en vez de
crashear. Limitación conocida y aceptada: la pantalla standalone sigue sin poder configurar
controles de entrada directamente — el usuario necesita abrir el visor primero. Resolver esto "de
verdad" (que la pantalla de configuración standalone pueda editar perfiles sin el visor abierto)
requeriría cambios dentro del propio módulo vendorizado, fuera de alcance mientras se mantenga
como código protegido.

## Investigación de Wayland/XWayland en proyectos de referencia — sin hallazgos reales

Se investigó si dos proyectos de referencia mencionaban soporte real de Wayland/XWayland que se
pudiera adoptar. La conclusión, confirmada leyendo el código fuente (no solo la documentación de
esos proyectos): ambos son el mismo linaje termux-x11/Xlorie que Kairos ya usa, o una reescritura
en otro framework de la misma tecnología subyacente — las menciones a "wayland"/"xwayland" que
aparecen al buscar en el código son archivos de protocolo del propio Xorg/X protocol upstream,
vendorizados pero sin usar, la misma clase de archivos que ya existen sin activarse dentro del
propio módulo de Kairos. No hay ningún soporte real de Wayland que portar desde esos proyectos.
Construir un compositor Wayland real desde cero es un proyecto de meses, no algo que se pueda
tomar prestado.

## Configuración de VNC desde la interfaz

Antes de este cambio, la app solo ofrecía instalar/iniciar/detener el servidor VNC con parámetros
fijos (resolución, profundidad de color) definidos una única vez durante la instalación del
módulo. Se agregó una opción de configuración real: un diálogo que permite elegir resolución,
profundidad de color, y si el servidor debe requerir contraseña — validando los valores contra
las opciones reales que soporta el servidor VNC subyacente antes de aplicarlos, y avisando
explícitamente al usuario del riesgo real si decide no requerir contraseña (aunque la conexión
sigue limitada a la propia máquina). El puerto/display no se hizo configurable a propósito: está
fijo al mismo display que usa el X11 embebido, y exponerlo como opción rompería esa alineación sin
ningún beneficio real.

## Verificación de permisos del manifest

Se confirmó que los permisos que el flujo X11 realmente necesita ya están declarados al mínimo
(servicio en primer plano, tipo de servicio, notificaciones, WakeLock) — y que el módulo X11 no
usa ningún permiso de superposición de pantalla (el botón flotante del visor es una vista normal
dentro de la propia actividad, no un overlay de sistema).
