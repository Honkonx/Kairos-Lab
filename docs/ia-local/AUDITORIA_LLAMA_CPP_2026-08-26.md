# Auditoría del módulo llama.cpp

Auditoría de código real (solo lectura, sin cambios de arquitectura) del módulo llama.cpp de
Kairos, contrastando la documentación existente contra el estado real del código.

## Veredicto general

**Sólido, con evidencia real de funcionamiento de punta a punta** — no es solo "compila en
integración continua". El módulo pasó por una cadena larga de builds reales fallidos, cada uno
corregido con causa raíz confirmada (ver [LLAMA_CPP_EMBEBIDO.md](LLAMA_CPP_EMBEBIDO.md)), hasta
que el servidor `llama-server` corrió de verdad en un dispositivo real. La interfaz de
configuración no es decorativa: cada campo se persiste y el script de arranque los lee y los pasa
como flags reales al binario.

## Hallazgos

### Build system: clona llama.cpp real, no lo vendoriza como submódulo

El repositorio real de llama.cpp se clona en tiempo de build, fijado a un tag pineado — confirmado
en disco que es un clon real (no una copia estática) del repositorio oficial. Después del clonado
se aplica un parche de texto idempotente que comenta la construcción de una decena de utilidades
de benchmarking/debug innecesarias — solución real a un problema real de integración CMake/AGP
(ver documento de arquitectura), no un hack cosmético.

### Vulkan realmente activo, pero condicionado a headers externos no versionados en el repo

Se confirma que Vulkan no es aspiracional — se llegó a enlazar realmente en integración continua.
El gap real es que el enlazado depende de dos checkouts externos (headers de Khronos) que el
sistema de build busca como directorios hermanos del proyecto — si no existen, el build de Vulkan
falla en configuración con solo una advertencia, no un error duro, lo que puede dejar el build
cayendo silenciosamente a compilar sin esos includes. Es el punto más frágil del pipeline.

### Arquitectura objetivo: solo `arm64-v8a`

Coherente con el resto del proyecto (Bionic ARM64 exclusivamente). Se compensa la falta de
variantes multi-ABI habilitando la compilación de múltiples variantes de kernels ARM
seleccionadas dinámicamente en tiempo de ejecución según la CPU real — no representa una
limitación de rendimiento práctica para el hardware objetivo.

### Wrapper JNI en proceso, servidor HTTP como binario aparte

Confirmado que el wrapper JNI (usado por la pantalla de Chat) expone sus funciones directamente en
el mismo proceso de la app, sin ningún socket o puerto — son dos motores completamente distintos
que comparten el mismo directorio de modelos, correctamente diferenciados en la interfaz de
usuario ("motor embebido" vs. "servidor"). El servidor HTTP expone los endpoints estándar de
llama.cpp sin ningún wrapping propio adicional de Kairos.

### Interfaz de usuario: todos los parámetros expuestos llegan realmente al motor

Se revisó la cadena completa interfaz → archivo de configuración → script → binario, confirmando
que cada campo de configuración (tamaño de contexto, hilos, capas de GPU, clave de API,
paralelismo, embeddings, exposición en red local) se valida, se persiste en un archivo real, y el
script de arranque lo lee y lo traduce a un flag real del binario — sin lectura insegura del
archivo de configuración (sin `eval`). No se encontró ninguna opción de interfaz decorativa sin
consumidor real. Algunos parámetros de muestreo (top-p, top-k, penalización de repetición) están
fijos de fábrica — documentado explícitamente en la propia interfaz, no una promesa incumplida.

### Descarga de modelos: catálogo curado con validación de espacio en disco

El catálogo curado de modelos incluidos (una veintena, verificados uno por uno contra el
repositorio real de cada modelo, con tamaños que van desde unos cientos de MB hasta varios GB) se
complementa con una opción manual de URL. La validación de la descarga es multi-capa: verificación
del formato binario real del archivo, comparación de tamaño contra lo declarado por el servidor,
limpieza de descargas parciales huérfanas, y un chequeo de espacio libre en disco real antes de
iniciar cualquier descarga (con un margen de seguridad del 15% sobre los bytes restantes) — si no
hay espacio suficiente, la descarga ni siquiera arranca, y se informa al usuario la cantidad real
necesaria y disponible.

### Consistencia con el resto de módulos de la app

El módulo `llamaserver` sigue el mismo contrato estándar que el resto de módulos de Kairos
(catálogo, puerto declarado, sesión de terminal multiplexada, categoría), sin ningún caso especial
que lo excluya del sistema normal de instalación/actualización.

## Resumen de hallazgos por severidad

| Hallazgo | Severidad |
|---|---|
| Sin chequeo de espacio en disco antes de descargar un modelo grande | Resuelto durante esta ronda de auditoría |
| Vulkan depende de checkouts externos no versionados en el propio repositorio, con fallo silencioso si faltan | Real, conocido — riesgo de pipeline de build, no de código |
| Documentación de la última reorganización de interfaz no reflejada en el documento de arquitectura principal | Cosmético — sin información incorrecta, solo dispersa entre distintos documentos |

Ningún hallazgo de esta auditoría contradice la conclusión de que el módulo está confirmado
funcionando de punta a punta.
