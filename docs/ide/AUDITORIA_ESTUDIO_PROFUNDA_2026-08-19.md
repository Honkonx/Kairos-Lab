# AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md — Bugs reales encontrados y corregidos en Estudio

Este documento resume una revisión de código profunda sobre el módulo de Estudio (editor de
código, árbol de archivos, panel de Git, búsqueda, teclado, paleta de comandos, configuración e
integración de IA), con foco en encontrar bugs reales de lógica, no solo problemas de estilo.

## Arreglados

### Filas duplicadas en el árbol de archivos por doble toque durante la carga

**El bug**: expandir una carpeta nunca antes cargada dispara una carga de contenido en segundo
plano (potencialmente lenta, ya que implica I/O real sobre el proveedor de archivos). Mientras esa
carga estaba en curso, no había ningún estado que reflejara "carga en curso" — si el usuario
tocaba la misma carpeta una segunda vez antes de que la primera carga terminara (doble toque,
carpeta con muchos archivos, dispositivo lento), se disparaba una **segunda** carga concurrente.
Cuando ambas cargas terminaban, cada archivo/subcarpeta quedaba duplicado visualmente en el árbol
(el sistema de archivos real no se veía afectado, solo la lista visible en pantalla).

**El fix**: se agregó un estado explícito de "carga en curso" por nodo del árbol, que bloquea
cualquier toque adicional sobre la misma carpeta mientras la primera carga sigue pendiente. Un
segundo toque durante la carga ahora no hace nada, en vez de disparar una carga duplicada.

### Nombres de archivo con caracteres Unicode se mostraban con escape octal en el panel de Git

**El bug**: por configuración por defecto de Git, cualquier nombre de archivo con caracteres no
ASCII "inusuales" (acentos, eñes, caracteres no latinos) se devuelve en la salida de
`git status --porcelain` entre comillas y con escape octal byte por byte — por ejemplo, un archivo
llamado `café.txt` aparecía literalmente como `"caf\303\251.txt"`. El panel de Git de Estudio no
desescapaba ese formato, así que el usuario veía el nombre del archivo reemplazado por esa
secuencia de escape cruda. Nombres con espacios no estaban afectados — Git no los trata como
"inusuales" por defecto.

**El fix**: se fuerza la opción `core.quotePath=false` en el comando de estado de Git que usa
Estudio, con lo que Git devuelve los nombres de archivo en UTF-8 crudo, sin comillas ni escape.

### Archivos en conflicto de fusión (merge) se etiquetaban como "Eliminado"/"Nuevo"/"Cambiado" en vez de "Conflicto"

**El bug**: la función que traduce los códigos de estado de `git status --porcelain` a una
etiqueta legible no tenía ninguna rama para los combos de estado que Git reporta durante un
merge/rebase con conflictos reales — por ejemplo, cuando ambos lados de una fusión borraron el
mismo archivo, o cuando ambos lo agregaron. Como las etiquetas se evaluaban en orden y la primera
coincidencia ganaba, esos casos caían en ramas genéricas y mostraban etiquetas activamente
engañosas ("Eliminado", "Nuevo") sugiriendo que el archivo ya estaba resuelto, cuando en realidad
requería intervención del usuario para resolver el conflicto.

**El fix**: se agregó una etiqueta explícita **"Conflicto"** para los siete combos de estado
"unmerged" reales que reporta Git, evaluada antes que cualquier otra rama.

## Documentados y luego corregidos

### Cerrar una pestaña con cambios sin guardar los descartaba sin ninguna advertencia

Este fue el hallazgo más serio de la revisión. Cerrar una pestaña (con el botón "✕" o con el
atajo de teclado correspondiente) eliminaba el archivo de la lista de pestañas abiertas sin
revisar si tenía cambios sin guardar — el contenido en memoria se perdía sin ningún diálogo de
confirmación ni guardado automático. Además, el indicador de "cambios sin guardar" de la pestaña
activa solo se recalculaba al cambiar de pestaña o antes de guardar, nunca al cerrarla
directamente — así que incluso un chequeo simple de ese indicador podía dar un falso negativo si
el usuario escribía y cerraba la misma pestaña sin cambiar de foco primero.

**El fix aplicado**: al cerrar una pestaña, primero se sincroniza el indicador de cambios sin
guardar de forma confiable (si es la pestaña activa); si hay cambios sin guardar, se muestra un
diálogo con tres opciones — "Guardar y cerrar", "Cerrar sin guardar", "Cancelar" — antes de
eliminar la pestaña. Si no hay cambios sin guardar, se cierra directo, igual que antes. Tanto el
botón de cierre como el atajo de teclado usan el mismo camino de código, así que el fix cubre
ambos casos sin duplicar lógica.

### Un modificador de teclado "bloqueado" podía quedar activo indefinidamente

La barra de teclado virtual permite bloquear un modificador (Ctrl/Alt/Shift) con un toque
prolongado, para no tener que mantenerlo presionado en cada combinación. Ese bloqueo solo se
liberaba si el usuario lo volvía a tocar manualmente — no había ningún punto del ciclo de vida de
la pantalla que lo reseteara automáticamente. Si el usuario bloqueaba un modificador y navegaba a
otra pantalla (panel de Git, build, configuración) sin darse cuenta de que seguía activo, la
próxima tecla que tocara podía combinarse con ese modificador "fantasma" y disparar un atajo en
vez de escribir el carácter esperado.

**El fix aplicado**: los modificadores bloqueados ahora se resetean automáticamente al pasar la
app a segundo plano y al abrir un proyecto nuevo — ambos son puntos donde "empezar de cero" ya es
el comportamiento esperado del resto de la interfaz. Deliberadamente no se resetean solo por
cambiar de pestaña o por volver de background a foreground, para no romper el caso de uso
legítimo de mantener un modificador fijo mientras se revisan varios archivos seguidos.

## Documentados, pendientes de una decisión de diseño más amplia

Estos hallazgos no representan datos corruptos ni comportamiento roto, pero identifican mejoras
reales que requieren una decisión de producto antes de implementarse:

- **Búsqueda en proyecto**: los límites de seguridad (tope de resultados, tope de tamaño de
  archivo) cortan de forma limpia, sin corromper resultados, pero no interrumpen temprano el
  recorrido del árbol de archivos completo ni la lectura del archivo actual una vez alcanzado el
  tope — en un proyecto muy grande, esto sigue gastando tiempo de más después de alcanzar el
  límite. Los archivos de tamaño desconocido (típico de algunos proveedores de almacenamiento en
  la nube) se excluyen de la búsqueda en silencio, sin ningún aviso al usuario.
- **Árbol de archivos**: las operaciones de lectura de carpetas no están envueltas en manejo de
  errores ante la posibilidad real de que el permiso de acceso a una carpeta se revoque a mitad de
  sesión — esto podría interrumpir el hilo de carga sin ningún aviso claro al usuario. Corregirlo
  bien requiere decidir qué mensaje mostrar y si conviene remover automáticamente el proyecto de
  la lista de recientes.

## Confirmado sin hallazgos

La revisión también confirmó, sin encontrar bugs:

- Los comandos registrados en la paleta de comandos apuntan todos a funcionalidad real y
  existente — ninguno es un placeholder.
- El cliente de IA distingue correctamente "no hay ningún motor local respondiendo" de "el motor
  local respondió pero devolvió un error", sin ambigüedad en la lógica de reintento entre
  proveedores.
- Las preferencias de configuración del editor (tamaño de fuente, tamaño de tabulación) no
  permiten guardar valores fuera de rango a través de la interfaz.
