# Automatizaciones

**Pantalla nativa de Kairos** — accesible desde el menú "Más". No es un módulo instalable de
`modulos/` (no tiene script `.sh` propio, no aparece en la Tienda) — es 100% Kotlin nativo, le da
a Kairos triggers reales que disparan una acción sobre un módulo **sin que el usuario tenga que
abrir la app**.

---

**App:** Kairos (fork termux-app)
**Fragment:** `AutomationsFragment.kt`
**Persistencia/lógica:** `AutomationManager.kt` + `AutomationScheduler.kt`

---

## 1. Qué es, y qué no es

Automatizaciones es el primer mecanismo de Kairos para que algo se dispare solo, sin abrir la
app: se define un workflow (trigger → acción) y el sistema lo ejecuta de fondo.

Esta primera versión cubre, de punta a punta:

| Trigger | Cuándo dispara |
|---|---|
| **Arranque del dispositivo** | Al encender/reiniciar el teléfono |
| **Horario diario** | Todos los días, a una hora fija elegida con un selector de reloj nativo |

| Acción | Qué hace |
|---|---|
| **Iniciar módulo** | Arranca un módulo con servicio (Ollama, n8n, llama-server, etc.) |
| **Detener módulo** | Lo detiene |

**No es** un motor de reglas genérico tipo Tasker/IFTTT — el alcance es deliberadamente chico:
2 triggers × 2 acciones, reusando el mismo control de módulos que ya usa el toggle manual de
cada pantalla, sin reimplementar nada de arranque/parada por separado.

Triggers de batería, notificación push, cercanía geográfica (geofence) y "app en primer plano"
quedan diseñados pero no implementados todavía — candidatos reales a una versión futura.

## 2. La pantalla

Lista de cards, una por workflow creado: nombre, `trigger → acción`, fecha/hora de la última
ejecución, y un switch para habilitar/deshabilitar sin borrarlo. Botón "+ Agregar" abre un
diálogo con:

- Nombre del workflow.
- Trigger (arranque / horario) — el selector de hora solo aparece si se elige "horario".
- Acción (iniciar / detener) + selector de módulo, poblado únicamente con módulos que tienen
  servicio propio de verdad (se excluyen los CLIs puros, como Claude Code o Codex, que no tienen
  start/stop porque no corren en segundo plano).

Editar, deshabilitar o eliminar un workflow con horario reprograma o cancela su alarma al
instante — no hace falta esperar al próximo reinicio del teléfono para que el cambio tenga
efecto.

## 3. Por qué el horario no es "al segundo"

El horario diario usa un mecanismo de alarma aproximado, no uno de precisión exacta al segundo
— ese nivel de precisión en Android moderno exige un permiso extra que el usuario tendría que
conceder a mano fuera de la app, para un caso de uso ("arrancar Ollama a las 8am") que no
necesita esa exactitud. Puede desviarse algunos minutos bajo ahorro de batería agresivo del
sistema — aceptable para este propósito.

## 4. Seguridad — no hace falta confirmar cada disparo

Las acciones que puede ejecutar un workflow (iniciar/detener un módulo) son las mismas que la
app ya trata como de bajo riesgo cuando se hacen a mano desde su pantalla — no hay ninguna acción
destructiva detrás de una automatización en esta versión. La confirmación real ya ocurrió cuando
el usuario creó o habilitó el workflow desde la pantalla; no se le vuelve a pedir confirmación en
cada disparo individual (que, además, ocurre sin ninguna pantalla visible — ni sería posible
mostrarle un diálogo en ese momento).

## 5. Alcance futuro (no implementado todavía)

- Triggers de batería (ej. "solo si hay más del 50%"), notificación push, cercanía geográfica y
  "app X pasó a primer plano".
- Un motor de reglas más genérico, con condiciones combinadas.
