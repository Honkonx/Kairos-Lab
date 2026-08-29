# Historial de incidentes reales del rootfs embebido y su resolución

Este documento recoge, con nivel de detalle técnico, los problemas reales encontrados durante el
desarrollo del mecanismo de rootfs embebido (ver `ROOTFS_EMBEBIDO.md`) y cómo se diagnosticaron y
resolvieron. Se conserva como referencia para cualquiera que necesite depurar un problema similar
o entender por qué el diseño actual toma las decisiones que toma.

## Resumen del estado final

El flujo completo — extracción del rootfs embebido sin red, instalación real vía `apt`, y
arranque exitoso del script de configuración posterior — está **confirmado funcionando de punta
a punta en un dispositivo real**, tras resolver una serie de problemas encontrados en distintas
rondas de prueba. La sección siguiente documenta el camino real hasta llegar ahí, porque cada
incidente revela una restricción del entorno (Android, Termux, el propio índice de paquetes) que
vale la pena conocer de antemano.

## Gap conocido: paquetes de soporte glibc no cubiertos por el rootfs embebido

Confirmado como una **limitación deliberada de diseño, no un olvido**: cinco paquetes de soporte
GNU libc (usados por binarios prebuilt que no corren directamente sobre Bionic/Termux) viven en
un repositorio APT completamente distinto al índice principal que consulta el generador del
rootfs. Cubrirlos exigiría que el generador consulte un segundo índice de paquetes y resuelva esa
dependencia por separado — una extensión real del script, no un simple agregado de líneas a la
lista de paquetes.

**Impacto práctico**: el asistente de instalación deja resueltos sin red los grupos de paquetes
que sí cubre el rootfs embebido, pero el paso de instalación de soporte glibc sigue descargando
normalmente la primera vez — no bloquea el arranque, simplemente no se beneficia de la
aceleración "sin red" que sí tienen los demás grupos. Es una degradación parcial esperada, no una
falla.

**Camino de resolución, si se decide cerrarlo en el futuro**: agregar un segundo par de índice
de paquetes/lista de paquetes al generador (`build_rootfs.py`), y un checkpoint adicional en el
instalador para ese grupo. No es urgente porque ningún componente crítico del primer arranque
depende de este grupo de paquetes.

## Incidente 1 — variables de entorno del proceso hijo mal armadas

**Síntoma observado**: el paso posterior a la extracción del rootfs (el script de configuración
que prepara el entorno completo) fallaba con un error de "no se puede ejecutar bash", tanto con
rootfs embebido como sin él.

**Causa raíz**: la rutina que invoca ese script armaba las variables de entorno del proceso hijo
(`HOME`, `PREFIX`, `PATH`, biblioteca de enlazado dinámico) manualmente, en vez de usar el helper
compartido ya probado en el resto de la aplicación — se olvidaba de fijar la variable `SHELL`
(necesaria para que procesos hijos posteriores lanzados por el propio script de configuración
resuelvan bien su intérprete de comandos) y usaba el `HOME` del proceso Android de la app en vez
del `HOME` real del entorno Termux.

**Fix**: reemplazar el armado manual por el helper compartido ya usado en el resto de la
aplicación para lanzar procesos dentro del entorno Termux.

## Incidente 2 — resolución de binarios por nombre relativo poco confiable justo tras extraer el bootstrap

**Síntoma observado**, con el incidente 1 ya corregido: un error distinto pero del mismo patrón —
"no se puede ejecutar apt", en el paso de instalación de los `.deb` del rootfs, un método que ya
usaba correctamente el helper del incidente 1.

**Causa raíz probable**: que dos binarios distintos fallaran con el mismo tipo de error, en dos
lugares que ya tenían el entorno bien configurado, apuntó a que la resolución de un binario por
nombre relativo (dependiendo de que la variable `PATH` ya esté completamente resuelta) no es
100% confiable en al menos un dispositivo real justo después de que el bootstrap termina de
extraerse — posiblemente una demora real del sistema de archivos dejando el binario recién
extraído ejecutable con cierto retraso.

**Fix**: usar siempre rutas absolutas a los binarios críticos (`bash`, `apt`) en lugar de
depender de la resolución por `PATH`, con un reintento automático y una pequeña pausa como
salvaguarda ante una posible demora transitoria.

**Log persistente**: se agregó un archivo de registro combinado que instrumenta cada fase del
proceso de instalación (bootstrap, rootfs, script de configuración) — indispensable para poder
diagnosticar con precisión el resto de los incidentes de esta lista sin depender únicamente de
capturas de pantalla del error.

## Incidente 3 — hipótesis inicial descartada: "el rootfs embebido no se detectó"

**Síntoma observado**, en una prueba posterior con una versión más reciente del rootfs (mayor
cantidad de paquetes): el asistente mostró el mensaje de "extrayendo rootfs" y luego dio un
error. La hipótesis inicial fue que la detección del artefacto embebido había fallado y el
instalador había caído al camino alternativo de descarga por red.

**Investigación por lectura de código**: el texto que muestra el asistente durante todo el
proceso de instalación del rootfs se calcula una única vez, al principio, directamente a partir
del resultado de la comprobación de si el artefacto está embebido. Si esa comprobación hubiera
fallado, el texto mostrado habría sido el de "descargando e instalando", no el de "extrayendo" —
el propio texto que se observó es evidencia de que la detección **sí** funcionó correctamente.

**Conclusión real**: el fallo no estaba en la detección del artefacto embebido, sino en un paso
posterior (copia del artefacto, extracción del `.tar.xz`, o instalación de los `.deb`) que
comparte el mismo texto de estado en pantalla para las tres fases. Esta corrección de hipótesis
fue clave para no seguir investigando en la dirección equivocada.

**Medida de higiene aplicada en paralelo, sin confirmar que fuera la causa raíz**: se excluyó la
extensión `.xz` de la recompresión automática de recursos del sistema de build de Android — sin
esa exclusión, el empaquetador puede recomprimir con DEFLATE un archivo ya comprimido al
incluirlo como recurso, lo cual es un desperdicio de tiempo de build y un riesgo de rendimiento en
tiempo de ejecución para binarios grandes. Es una corrección de bajo riesgo, válida
independientemente de si terminó siendo la causa raíz de este incidente puntual.

## Incidente 4 — causa raíz real: conflicto de paquetes dentro del propio rootfs

**Síntoma observado, reproducido con precisión en dispositivo real con el log de diagnóstico
activo**:

```
[RootfsInstaller] EXCEPCIÓN: IllegalStateException: apt install salió con código 100:
The following packages have unmet dependencies:
 nodejs : Conflicts: nodejs-lts but 24.18.0-1 is to be installed
 nodejs-lts : Conflicts: nodejs but 26.4.0-1 is to be installed
E: Unable to correct problems, you have held broken packages.
```

**Causa raíz confirmada**: el rootfs generado contenía simultáneamente dos variantes de Node.js
mutuamente excluyentes (marcadas como `Conflicts:` entre sí en el propio índice de paquetes de
Termux). La lista de paquetes pedidos explícitamente solo pedía una de las dos variantes, pero el
resolutor de dependencias transitivas tomaba siempre la primera alternativa de cualquier
dependencia con formato `pkgA | pkgB`, sin comprobar si la otra alternativa ya era, precisamente,
la que se había pedido a propósito — arrastrando así la variante no deseada como dependencia
transitiva de algún otro paquete de la clausura.

**Fix aplicado en el generador del rootfs**: el resolutor ahora conserva todas las alternativas
de cada dependencia (no solo la primera) y, al resolver la clausura, prioriza la alternativa que
ya forma parte de lo pedido explícitamente cuando existe, en lugar de tomar siempre la primera
listada. Esta corrección aplica a cualquier conflicto de este tipo, no solo al caso puntual de
Node.js — es la causa de fondo, no un parche específico para un paquete.

## Confirmación final de punta a punta

Con el conflicto de paquetes corregido, se reprodujo el flujo completo en un dispositivo real
desde una instalación limpia: el rootfs se extrajo, se instaló y marcó correctamente sus
checkpoints, y el script de configuración posterior corrió y terminó sin errores, dejando el
entorno listo para usar. Los grupos de paquetes cubiertos por el rootfs embebido mostraron
tiempos de instalación prácticamente instantáneos (ya estaban resueltos por los checkpoints
pre-marcados) — el ahorro de "sin red" es real y medible en la práctica.

Dos hallazgos laterales, no bloqueantes, encontrados en esta misma prueba final:

- El script de configuración posterior apuntaba a una ruta equivocada de su biblioteca de
  funciones compartidas, lo cual degradaba en silencio algunos pasos secundarios (sin verificación
  de errores estricta, el fallo no interrumpía el script mientras pasaba desapercibido).
  Corregido apuntando a la ruta real.
- La primera pasada de `apt install` de todos los paquetes tardó varios minutos y venció por
  timeout (posiblemente un prompt de configuración de algún paquete quedando colgado); el
  reintento automático ya existente completó la instalación casi de inmediato porque la mayor
  parte del trabajo de la primera pasada ya había quedado hecho. No bloqueante gracias al
  reintento, pero queda pendiente identificar qué paquete específico produce ese comportamiento
  para evitar el timeout en cada instalación real.
