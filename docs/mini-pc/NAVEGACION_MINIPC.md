# Diseño de navegación: la sección "Más"

Cuando la navegación inferior de Kairos llegó a su límite práctico de elementos visibles, las
opciones restantes (incluyendo, en su momento, el control directo del servidor X11) se agruparon
bajo una sección "Más". Se evaluaron tres patrones de navegación distintos para esa sección antes
de decidir cuál implementar.

## Patrones evaluados

1. **Bottom sheet en grilla** (adoptado) — la sección "Más" pasa de una pantalla de lista
   completa a una hoja deslizable con las opciones organizadas en grilla, sin introducir un
   patrón de navegación nuevo ni tocar el menú inferior existente. Es el cambio de menor riesgo
   de los tres: reutiliza un componente ya familiar en el resto de la app.
2. **Drawer clásico (menú lateral)** — un menú lateral tipo hamburguesa con buscador y
   categorías (Principal / Herramientas / Sistema). Es el patrón más reconocible dentro del
   ecosistema Android y queda documentado como el candidato natural si la sección "Más" vuelve a
   crecer lo suficiente como para que una grilla deje de ser suficiente.
3. **Riel de navegación lateral persistente** — una barra vertical colapsable/expandible en el
   borde de la pantalla. Evaluado y descartado por ahora: es un patrón menos común en teléfonos
   Android, con más fricción de aprendizaje para una ganancia que el bottom sheet ya cubre
   adecuadamente.

## Decisión

Se implementó el bottom sheet en grilla como la opción de menor cambio. Además, las opciones de
control del servidor X11 y del visor VNC se reubicaron dentro de la pestaña Mini PC (ver
`MINIPC_TAB.md`), reduciendo aún más lo que la sección "Más" necesita cubrir. El drawer clásico y el riel lateral quedan documentados como alternativas de diseño ya
evaluadas, no como trabajo pendiente — se retomarían solo si la sección "Más" vuelve a crecer de
forma significativa.
