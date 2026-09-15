# Búsqueda web sin API key

El chat de IA local de Kairos ya tenía una forma de darle acceso a internet a un modelo: el
toggle "Web", que usa la Web Search API de Ollama — pero esa vía requiere que el usuario cree
una API key gratuita en la cuenta de Ollama. Esta feature agrega una segunda vía que **no pide
ninguna clave**: scraping directo de resultados de búsqueda, integrado en el chat con un
comando y un botón.

---

**App:** Kairos (fork termux-app)
**Componente:** `WebSearchService.kt`
**Integración:** `ChatFragment.kt` (comando `/buscar` y botón "🔍 Buscar")

---

## 1. Cómo funciona

`WebSearchService` consulta la versión HTML sin JavaScript de DuckDuckGo con headers de
navegador real (sin eso, el resultado es una página de verificación anti-bot en vez de
resultados). La respuesta se clasifica siempre en uno de estos casos, sin ambigüedad:

- **Resultados reales** — se devuelven título, URL y un fragmento de texto por cada resultado.
- **Sin resultados** — la propia búsqueda no encontró nada (marcado explícito, no una suposición).
- **Bloqueado** — se detectó un desafío anti-bot o un código de error del servidor. Cualquier
  respuesta que no encaje claramente en "resultados" o "sin resultados" se trata como
  "bloqueado", nunca como "sin resultados" — es más seguro reintentar que decirle al usuario que
  la web no tiene nada cuando en realidad el mecanismo de búsqueda falló.

Si hay varios fallos seguidos, un corte automático (circuit breaker) deja de intentar por unos
minutos antes de volver a probar, para no insistir contra un bloqueo que ya se sabe activo.

## 2. Protección contra URLs internas

Antes de seguir cualquier URL de un resultado, Kairos verifica que no apunte a una dirección de
red privada/local del propio dispositivo o de la red interna (protección estándar contra este
tipo de ataque cuando una app sigue URLs obtenidas de una fuente externa) — la verificación
resuelve el nombre de dominio real, no solo mira el texto de la URL, así que tampoco puede
evadirse con un dominio que resuelva a una IP interna.

## 3. Cómo se usa en el chat

Dos formas, ambas manuales:

1. **Comando** `/buscar <consulta>` escrito directamente en el chat.
2. **Botón "🔍 Buscar"** en la barra del chat, que abre un diálogo para escribir la consulta.

## 4. Limitación actual — es manual, no automática

El modelo de IA (Ollama, el motor local, o un proveedor en la nube) **no puede disparar esta
búsqueda por su cuenta** — es siempre el usuario quien decide buscar, con el comando o el botón.
Kairos todavía no tiene un mecanismo de "function calling" real donde el propio modelo decida
buscar en medio de su razonamiento y reciba el resultado de vuelta de forma estructurada; eso
queda como trabajo futuro.
