# Túnel — modelo de datos y acciones (multi-dominio)

El tab Túnel permite exponer cualquier módulo de Kairos a internet vía Cloudflare o ngrok,
soportando múltiples dominios y tokens guardados por proveedor.

## Modelo de datos — 3 capas, no se reemplazan entre sí

| Capa | Para qué sirve |
|---|---|
| Config global por proveedor | Token y dominio por defecto de Cloudflare/ngrok. Los botones rápidos de cada tarjeta de módulo (sin token/dominio explícito) usan esta config — panel "⚙ Configuración de túneles". |
| Config propia por módulo | Un dominio/token atado a un módulo específico (n8n, remote, Ollama, OpenCode, OpenClaw, servidor llama.cpp). Se guarda de forma independiente de la config global. |
| Dominios guardados | Lista de dominios/tokens sin dueño todavía — se agregan, se borran, y se asignan (copian) a la config propia de un módulo cuando el usuario los selecciona. No se eliminan automáticamente al asignarse — se pueden reasignar a otro módulo después. |

La lista de dominios guardados se persiste como un único valor JSON compacto por proveedor
(`[{"id","domain","token"}, ...]`), en la misma clave-valor donde el resto de la configuración
de la app guarda listas u objetos estructurados — evita tener que escanear múltiples claves con
un prefijo numerado variable, y mantiene el archivo de configuración como texto plano línea por
línea.

## Las 4 acciones

1. **Agregar** — diálogo con dominio (opcional) y token; se agrega como una entrada nueva a la
   lista del proveedor, sin tocar ninguna asignación existente. Requiere al menos uno de los dos
   campos no vacío.
2. **Borrar** — ícono junto a cada entrada de la lista; elimina solo esa entrada puntual, sin
   afectar la config de un módulo que ya la tenga asignada (esa config vive en su propia clave,
   independiente).
3. **Asignar** — tocar un dominio de la lista muestra los módulos compatibles con túnel (n8n,
   remote, Ollama, OpenCode, OpenClaw, servidor llama.cpp). Si el módulo elegido ya tenía un
   dominio/token distinto asignado, se pide confirmación explícita antes de reemplazarlo.
4. **Verificar** — por cada módulo con config propia asignada, comprueba: que el token/dominio
   sigue guardado; para módulos con un archivo de configuración propio leído por su script (como
   n8n), que ese archivo existe y coincide con el valor guardado; y si el túnel de ese módulo
   está corriendo, confirma que el proceso real sigue vivo (no solo que la sesión de terminal
   asociada exista — una sesión puede seguir "viva" con el proceso adentro ya terminado, por
   ejemplo tras un cierre inesperado).

   El resultado se muestra con un indicador de éxito/error por módulo y proveedor, y el detalle
   del problema si lo hay.

## Manejo de credenciales en el panel de configuración global

El diálogo de edición de la configuración global no precarga el token guardado en texto plano —
el campo arranca vacío y una pista indica si ya hay un token guardado sin revelarlo. Dejarlo
vacío al guardar conserva el token existente; escribir uno nuevo lo reemplaza. El campo de
dominio sí se precarga (no es un dato sensible, y vaciarlo a propósito es un caso de uso real:
borrar el dominio sin borrar el token).

## Qué es independiente de esta funcionalidad

- Los botones rápidos de cada tarjeta de módulo (túnel anónimo, con token) siguen funcionando
  igual, usando la config global o un token/dominio puntual — sin pasar por la lista de
  guardados.
- El panel de configuración global (editar/borrar por proveedor) sigue existiendo tal cual — la
  lista de dominios guardados es una superficie adicional, no un reemplazo.

Ver también `docs/ssh/tunel-cloudflared-nativo.md` para el mecanismo de resolución DNS del
binario de Cloudflare embebido.
