# Auditoría del stack de IA local — Ollama, Chat, proveedores locales

Auditoría de código real sobre la integración de Ollama, la pantalla de Chat multi-motor, y el
mecanismo que permite a CLIs de terceros usar IA local como backend. El motor llama.cpp puro se
cubre en un documento aparte
([AUDITORIA_LLAMA_CPP_2026-08-26.md](AUDITORIA_LLAMA_CPP_2026-08-26.md)).

## Resumen

Todo lo auditado en esta ronda es funcional real, sin código muerto ni interfaz decorativa.

## Gestión de modelos de Ollama

La pantalla de modelos de Ollama usa el cliente HTTP real de la API de Ollama para listar los
modelos instalados (sin listas fijas), descargar modelos de un catálogo curado con progreso real
en vivo (porcentaje, velocidad, tiempo estimado), ver el detalle de un modelo instalado, y
eliminarlo. También ofrece descarga manual de un modelo por nombre.

La pantalla de configuración de Ollama expone los parámetros reales de inferencia — guardarlos
escribe a un archivo de configuración de usuario real, que el propio proceso de Ollama lee en
cada arranque. Incluye gestión centralizada del proceso (reiniciar servicio, ver detalle,
información de GPU/Vulkan, actualizar, desinstalar), un toggle para escuchar en la red local, y
generación de "modelos personalizados" reales a partir de un `Modelfile` construido con la
configuración guardada.

## Pantalla de Chat: tres familias de motores

La pantalla de Chat soporta tres familias de motores reales, con un selector explícito antes de
entrar (decisión de diseño deliberada, no una limitación por completar):

1. **Ollama** — vía HTTP local, validando que el módulo esté instalado antes de dejarlo elegir.
2. **IA Local (llama.cpp)** — con dos transportes elegibles por el usuario: el motor embebido en
   proceso, o el servidor HTTP local (ver
   [LLAMA_CPP_EMBEBIDO.md](LLAMA_CPP_EMBEBIDO.md)).
3. **API en la nube (clave propia del usuario)** — varios proveedores reales soportados con sus
   endpoints específicos. La clave de API se guarda cifrada, no en texto plano.

No hay fallback automático entre motores — es una elección explícita del usuario, mostrada cada
vez que se vuelve a la pantalla de Chat, no una lógica de "si el motor A falla, probar el motor
B".

Otras piezas confirmadas: modo shell dentro del chat (ejecuta comandos directamente, sin pasar
por el modelo de IA), inyección de resultados de búsqueda web como contexto cuando esa función
está disponible, adjuntar imágenes solo habilitado para los motores que soportan visión (el motor
local puro no la soporta todavía — documentado explícitamente), historial persistido con límite
configurable, streaming con detección de "atascado" (watchdog) y reconexión única (para evitar
mensajes duplicados).

## Integración de IA local en CLIs de terceros

De un conjunto más amplio de CLIs de terceros evaluados como candidatos, solo tres confirmaron
soporte real de un endpoint compatible con la API de OpenAI configurable por el usuario contra su
repositorio real — los demás están atados a su propio backend en la nube, sin un campo de
endpoint configurable, así que no se integraron. Los CLIs sí soportados exponen un botón "Usar
Ollama/IA local" desde la interfaz genérica de módulo, que arma la URL base y el nombre de modelo
correspondiente y llama a la función de configuración específica de cada uno.

Un módulo particular (un framework de Python, no un CLI de Node) tiene su propio mecanismo
paralelo de selección de proveedor local (elegir entre Ollama o el servidor local), con el mismo
concepto pero código separado — consistente con que su formato de configuración es distinto al
de los demás CLIs (no un `.env`/JSON/TOML estándar).

El componente que gestiona los modelos `.gguf` para el motor embebido (descarga con reanudación
vía cabecera HTTP `Range`, validación multi-capa de tamaño y formato real, importación desde el
selector de archivos del sistema, limpieza de descargas parciales huérfanas) está completamente
implementado, sin elementos simulados.

## Detección de RAM antes de sugerir un modelo grande

La pantalla de catálogo de modelos de Ollama consulta la RAM total real del dispositivo (sin
requerir permisos adicionales) y la compara contra una estimación de RAM necesaria para el modelo
(calculada como el tamaño en disco multiplicado por un factor, documentado con evidencia real: un
modelo de 7B parámetros en cuantización Q4 ocupa unos 4.7GB en disco pero necesita unos 6GB de
RAM real para cargar). Si el modelo elegido probablemente no entra en la RAM disponible, se
muestra un aviso visible — sin bloquear la descarga, una decisión de diseño deliberada (el
usuario puede tener razones válidas para descargar igual, por ejemplo para usarlo más adelante en
otro dispositivo). Este mecanismo existe en el catálogo de Ollama; el catálogo de modelos GGUF
para el motor embebido tiene su propio chequeo de espacio en disco documentado en el módulo de
llama.cpp, aunque no necesariamente el mismo chequeo de RAM estimada — una posible inconsistencia
menor entre las dos pantallas de catálogo, a confirmar en una auditoría futura.

## Conclusión

Ninguno de los cuatro puntos auditados presentó código muerto o interfaz sin funcionalidad real
detrás. Auditorías previas habían encontrado partes de este stack como mockups (una lista estática
de modelos, parámetros de configuración que no se aplicaban) — esos hallazgos ya no aplican al
código actual, quedaron resueltos en rondas posteriores.
