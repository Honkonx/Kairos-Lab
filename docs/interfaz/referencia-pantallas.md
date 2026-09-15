# Referencia de pantallas — controles principales

Qué hace cada control de las pantallas de navegación y sistema de Kairos. Cubre las 12
pantallas de navegación/sistema (Módulos, Chat, Config, Túnel, Monitor, Plugins, Nube, X11 /
Mini PC, Archivos, IA Local, Modelos). Los módulos individuales (Ollama, n8n, Claude Code, etc.)
tienen su propia ficha de controles en `docs/modulos/`.

## Menú "Más" — acceso a las pantallas secundarias

El ítem "Más" de la barra de navegación inferior abre una hoja inferior (bottom sheet) con una
grilla de 3 columnas de accesos directos: Monitor, Archivos, Túnel, Nube, Plugins y Config —las
pantallas que no tienen su propio ícono fijo en la barra de navegación. Tocar una celda navega
directo a esa pantalla y cierra la hoja.

## ModulesFragment — pantalla de inicio ("Módulos")

| Control | Qué hace |
|---|---|
| Stats "Instalados"/"Activos"/RAM | Solo lectura, se actualiza cada 5s |
| "↻ Actualizar" (icono en la fila de stats) | `git pull` sobre el propio código de los scripts, distinto del swipe-to-refresh (que solo relee estado local sin red) |
| "🎙 Voz → Agente" | Abre un diálogo de dictado por voz que manda el pedido como prompt inicial a cualquier CLI de IA soportado |
| Pull-to-refresh (swipe down) | Relee registry + sesiones en vivo, sin red |
| Fila de módulo (tap) | Si no está instalado, abre la hoja de instalación; si ya está instalado, abre la pantalla de detalle |
| Toggle ON/OFF de fila | Arranca/detiene el proceso real del módulo |
| "Ir a Plugins →" (estado vacío) | Cuando no hay módulos instalados, redirige a la Tienda en vez de dejar la pantalla vacía |

## ChatFragment — "Chat IA"

### Selector de motor (pantalla previa al chat)

| Control | Qué hace |
|---|---|
| Card "🌐 Ollama Termux" | Verifica que Ollama esté instalado y entra al chat con ese motor |
| Card "📱 IA Local (llama.cpp)" | Entra directo — motor embebido, no requiere verificación de instalación |
| Card "☁️ Cloud API (tu clave)" | Abre selector de proveedor (Gemini/DeepSeek/OpenAI/Anthropic/Grok); si falta la clave, la pide primero |
| Switch "Embebido (sin puerto)" / "Servidor (puerto 8085)" | Cambia el transporte de llama.cpp: JNI in-process vs. HTTP al servidor local — el modo servidor permite usar el motor desde otra máquina de la red |

### Barra superior / input

| Control | Qué hace |
|---|---|
| Botón de motor (🔄) | Vuelve al selector de motor |
| Selector de modelo (texto, tap) | Lista los modelos reales disponibles según el motor activo — nunca se mezclan modelos de motores distintos en el mismo selector |
| "🎭" (persona) | Presets de system prompt |
| "Web: ON/OFF" | Activa/desactiva la búsqueda web inyectada como contexto |
| Micrófono | Dictado por voz |
| Clip/adjuntar imagen | Solo habilitado si el motor activo no es el local embebido (sin soporte de visión) |
| Enviar | Envía el mensaje al motor activo, o ejecuta un comando shell si el texto empieza con `!` |
| Limpiar historial | Borra el historial en memoria y en disco |
| Ajustes (⚙) | Sliders de temperatura (0.00–2.00), tamaño de contexto (512–8192), límite de historial (10–500, o "∞") |
| Cancelar (durante generación) | Corta la conexión activa e interrumpe la generación en curso |
| "Ver detalles"/"Ocultar detalles" (barra de error) | Expande/colapsa el detalle técnico de un error |
| Burbuja `<think>` (si el modelo la emite) | Expande/colapsa el razonamiento del modelo |

## ConfigFragment — "Config"/Ajustes

| Control | Qué hace |
|---|---|
| "🎨 Tema" | Selector inline (Oscuro/Señal/Claro), aplica al toque |
| "Auto-iniciar módulos" (switch) | Los módulos instalados arrancan al abrir Kairos, no al encender el dispositivo |
| "Comprobar paquetes del sistema" | Verifica los paquetes base necesarios |
| "Verificar actualizaciones de módulos (todas)" | Revisa versiones disponibles (npm, GitHub Releases, PyPI, etc.) sin instalar nada automáticamente |
| "Notificaciones de módulos caídos" (switch) | Notifica cuando un módulo pasa de en ejecución a detenido entre chequeos |
| "Widget flotante de acceso rápido" (switch) | Pide permiso de overlay si falta y arranca/detiene el widget flotante |
| "⚡ Instalar fzf + autosugerencias (zsh)" | Instala mejoras de terminal |
| "✏️ Definir nvim como editor por defecto" | Configura la variable `$EDITOR` |
| "⌨ Usar teclado/mouse externo (OTG/Bluetooth)" | Muestra guía |
| "Terminal clásica (sin UI adaptada)" (switch) | Alterna entre la terminal con barra adaptada y la terminal original sin modificar |
| "＋ Agregar variable" (entorno) | Diálogo para agregar una variable de entorno persistente |
| "↻ Re-ejecutar setup" | Vuelve a correr el setup inicial |
| "☁ Backup completo" / "⭳ Restaurar backup" | Backup y restauración completos del entorno |
| "⬆ Exportar configuración" / "⬇ Importar configuración" | Exporta/importa la configuración de la app |
| "📋 Exportar diagnóstico" | Genera un reporte de diagnóstico |
| "⚠ Reinstalar stack" | Reinstala todo el stack — acción destructiva, requiere confirmación escrita |
| "🗑 Desinstalar un módulo" | Diálogo de selección y desinstalación |
| "🚪 Salir (detener todo y cerrar)" | Mata todos los servicios activos y cierra la app |
| Token/Chat ID de Telegram + "🧪 Probar" | Guarda credenciales y envía un mensaje de prueba para notificaciones remotas |

## TunnelFragment — "Túnel"

| Control | Qué hace |
|---|---|
| Fila de proveedor (Cloudflare/ngrok, editar) | Guarda token/dominio persistente por proveedor |
| "▶ Cloudflare" (por módulo) | Túnel anónimo de Cloudflare — no se muestra para módulos que solo exponen TCP crudo (ej. SSH) |
| "🔑 Con token" | Túnel con el token/dominio propio guardado |
| "🚇 ngrok" / "🚇 ngrok tcp" | Túnel ngrok anónimo — la variante `tcp` es para protocolos no-HTTP como SSH |
| "🔑 ngrok+dominio" / "🔑 ngrok tcp+auth" | Túnel ngrok con token propio |
| "⏹ Detener" | Corta el túnel activo |
| "Copiar" (junto a la URL activa) | Copia la URL al portapapeles |

Ver `docs/interfaz/tunel-multidominio.md` para el modelo de datos completo (dominios/tokens
guardados, asignación por módulo, verificación).

## MonitorFragment — "Monitor"

| Control | Qué hace |
|---|---|
| Anillo de RAM (sección Dispositivo) | Solo lectura, refresco cada 5s |
| Anillo de almacenamiento (sección Dispositivo) | Solo lectura |
| Tarjeta de almacenamiento (tap, sin permiso) | Pide el permiso y configura el storage compartido en un solo paso |
| Fila de proceso — "⏹ Detener"/"▶ Reiniciar" | Alterna según el estado real del proceso |
| "🗑 Eliminar" | Quita el proceso del gestor (con confirmación) |
| "↻ Reinstalar" (solo si el gestor de procesos no está disponible) | Reinstala el gestor sin salir de la pantalla ni reinstalar la app entera |
| "Desactivar" (Phantom process killer) | Ofrece 3 vías según haya root: automática, auto-detección de puerto, o tutorial manual — Android puede matar módulos en segundo plano si esta protección sigue activa |
| "Desactivar" (Optimización de batería) | Solicita excluir la app de la optimización agresiva de batería |
| "Configurar automáticamente (sin PC)" / "Auto-detectar puerto (beta)" / "Ver tutorial manual" | Tres rutas del flujo de ajuste sin root, según cuánta automatización acepte el dispositivo |

## PluginsFragment — "Tienda de plugins" (menú "Más" → Plugins)

| Control | Qué hace |
|---|---|
| Barra de búsqueda | Filtra por nombre/id/descripción/categoría en vivo |
| "↻ Catálogo" | Descarga el catálogo remoto y lo fusiona con el catálogo local |
| "📦 Paquete local" | Instala un `.deb` o `.tar.gz` propio del usuario |
| "Ver ejemplo completo ↗" | Muestra el contrato completo de un paquete local (manifest + script) |
| Tarjeta de plugin (tap) — no instalado | Abre la hoja de instalación |
| Tarjeta de plugin (tap) — instalado | Diálogo con Cambiar método (si tiene variantes reales) / Activar-Desactivar / 🔄 Reinstalar limpio / 🗑 Quitar de la Tienda (solo plugins locales) / Desinstalar |
| "🔄 Reinstalar limpio" | Borra el paquete real y reinstala desde cero — para un módulo en estado roto que una reinstalación normal no soluciona |
| Activar/Desactivar | Oculta o restaura el módulo en la pantalla Módulos sin desinstalarlo |
| Checkbox "Desinstalación profunda" | Si está marcado, borra también el paquete real, no solo el estado |
| "🗑 Quitar de la Tienda (local)" | Borra el plugin del catálogo local y su script — no desinstala el paquete si ya estaba instalado |

## NubeFragment — "Nube"

| Control | Qué hace |
|---|---|
| "Iniciar"/"Detener" (servidor) | Arranca/detiene un servidor de archivos HTTP embebido, protegido por token |
| "☁️ Cloudflare" / "🚇 ngrok" (chips) | Publica el servidor de Nube vía túnel, reusando la misma lógica del tab Túnel |
| "Detener túnel" | Corta el túnel activo |
| "Copiar" / "Compartir" | Copia o comparte la URL activa (pública si hay túnel, local si no) — el enlace ya incluye el token |
| "↑" (subir carpeta) | Navega al padre — deshabilitado en la raíz |
| Fila de archivo (tap) | Si es carpeta, entra; si es archivo, muestra el tamaño |
| Fila de archivo (long-press) | Menú Renombrar/Eliminar |

## Mini PC — X11 y escritorio (dentro de `EntornoFragment`, tab "Mini PC")

| Control | Qué hace |
|---|---|
| "Entrar en X11" | Arranca el servidor X11 embebido y abre el visor en una task nueva |
| "Configuración de X11" | Resolución, escala, orientación, teclado, touch, picture-in-picture, pantalla completa |
| "🖵 Visor VNC" | Instala/arranca un cliente VNC si hace falta y abre el visor |
| "Cerrar servidor X11" | Confirmación y cierre del visor + del proceso del servidor X11 |

## FileManagerFragment — "Archivos"

| Control | Qué hace |
|---|---|
| Tabs "Termux $HOME" / "Almacenamiento interno" | Cambia la raíz de navegación entre dos raíces fijas |
| "↑" (subir carpeta) | Navega al padre — deshabilitado en la raíz actual |
| Fila de archivo (tap) — carpeta | Entra a la carpeta |
| Fila de archivo (tap) — archivo de texto editable | Abre el editor integrado (extensiones conocidas, tamaño limitado) |
| Fila de archivo (tap) — otro | Muestra nombre y tamaño |
| Fila de archivo (long-press) | Menú Copiar/Cortar/Renombrar/Eliminar (+ "Pegar aquí" si hay algo en el portapapeles) |
| "Pegar aquí" | Copia o mueve el archivo del portapapeles a la carpeta actual — bloquea pegar una carpeta dentro de sí misma |

## LocalAIFragment — "IA Local"

| Control | Qué hace |
|---|---|
| "💬 Ir al chat" | Salta al tab Chat — no existe una pantalla de chat separada, el chat es siempre el mismo |
| Radio "Vulkan si está disponible" / "Solo CPU" | Backend de GPU preferido para inferencia local |
| Slider "Temperature" (0.00–2.00) | Ajusta el parámetro, avisa si supera 1.0 |
| Slider "Contexto" (512–8192 tokens) | Ajusta el tamaño de contexto, avisa si supera el recomendado para la RAM del dispositivo (sin bloquear) |
| "📋 Lista de modelos (N/M descargados)" | Catálogo curado de 20 modelos GGUF (0.5B–14B) en un diálogo desplegable |
| Ítem del catálogo (tap, no descargado) | Confirmación con descripción y tamaño, luego descarga con barra de progreso real |
| "Eliminar" (por modelo descargado) | Borra el modelo del almacenamiento |
| "📂 Importar desde almacenamiento" | Selector de archivos para un `.gguf` ya descargado |
| "+ Agregar modelo por URL (avanzado)" | Descarga un `.gguf` desde cualquier URL directa, fuera del catálogo curado |

### ModelsFragment — sub-pantalla de Ollama ("Modelos")

Distinta de la Tienda de modelos GGUF de arriba — específica del motor Ollama (API HTTP local,
puerto 11434).

| Control | Qué hace |
|---|---|
| Ítem del catálogo curado (tap, no instalado) | Descarga el modelo con progreso real en vivo (%, velocidad, ETA) |
| Aviso "⚠ Puede no entrar en RAM" (si aplica) | Estima la RAM necesaria contra la RAM total del dispositivo y avisa sin bloquear |
| Fila de modelo instalado (tap) | Diálogo con detalle (parámetros, familia) y opción de eliminar |
| "+ Descargar modelo (avanzado)" | Diálogo para pedir un modelo por nombre exacto, fuera del catálogo curado |
