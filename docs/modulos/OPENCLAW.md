# OpenClaw — Módulo de Kairos

## 1. Qué es

OpenClaw es un gateway de agente de IA (paquete npm, `openclaw@latest`) con interfaz web y TUI
propias, compatible con múltiples proveedores (Ollama local, proveedores en la nube). En Kairos
corre **exclusivamente en modo nativo** (glibc + npm) — no requiere proot ni una distro Linux
completa.

Puerto fijo: `18789`. Tamaño estimado de la instalación: ~60MB.

## 2. Permisos

No requiere ningún permiso de Android específico — usa la infraestructura ya presente de Termux
(almacenamiento, ejecución de procesos) que la app ya solicita durante el asistente de
configuración inicial. No requiere grabación de audio, notificaciones push, ni ningún permiso
propio declarado a nombre de este módulo.

## 3. Arquitectura de instalación — 100% nativa, sin proot

```
Termux nativo (sin proot)
  ~/.npm-global/bin/openclaw          → paquete npm real
  ~/.openclaw-android/bin/node        → wrapper bash → loader glibc → node real
  ~/.openclaw-android/bin/npm, npx    → wrappers equivalentes
  ~/.openclaw/glibc-compat.js         → --require en NODE_OPTIONS (fix de os.networkInterfaces/homedir)
```

Node.js oficial para `linux-arm64` corre sobre el cargador ELF de la capa de compatibilidad
glibc de Termux, sin entrar a ningún entorno proot. OpenClaw requiere Node 22.22.3+, 24.15+, o
25.9+ (Node 23 no está soportado, según la documentación oficial del proyecto).

## 4. Lógica de instalación (resumen de los pasos)

El instalador acepta `--silent` (sin prompts) y `--force` (reinstala aunque ya esté presente).

1. **Infraestructura glibc + Node** — si ya hay un Node que cumple la versión mínima (del
   sistema o de un wrapper propio) con npm funcional, lo reusa. Si no, instala la capa glibc de
   compatibilidad, descarga el Node oficial `linux-arm64`, y genera wrappers `node`/`npm`/`npx`
   que sanean `NODE_OPTIONS` heredado antes de invocar el binario real.
2. **Verificación de Node + npm** — chequeo de versión mínima real (mayor.menor.parche, no solo
   el major).
3. **Instalación de OpenClaw** — `npm install -g openclaw@latest`, permitiendo que el propio
   postinstall del paquete aplique su hotfix real a la librería `baileys` (parche de una carrera
   de promesas y de un dispatcher incompatible con Undici en descargas de media, documentado por
   el propio proyecto OpenClaw). Antes de saltar este paso por checkpoint, se verifica que el
   paquete siga existiendo en disco — evita quedar reinstalando indefinidamente si el checkpoint
   indica éxito pero el paquete desapareció.
4. **Parches para Android** — `glibc-compat.js` (corrige `os.networkInterfaces()` y
   `os.homedir()`), un stub del módulo nativo `koffi` (no compilado para `android-arm64`), un
   stub de `clipboardy`, y parches de rutas `/tmp` → `$HOME/tmp` dentro del bundle instalado.
5. **Scripts de control** — genera `openclaw_start.sh`/`openclaw_stop.sh`.
6. **Aliases** — agrega un bloque a `~/.bashrc` (`openclaw-start`, `openclaw-stop`,
   `openclaw-status`, `openclaw-tui`).
7. **Registro de estado** — actualiza el registro interno de módulos instalados. La lectura de
   `openclaw --version` para este paso está protegida contra fallos: si el comando no responde
   por cualquier motivo, el registro igual se actualiza con un valor de versión `"unknown"` en
   vez de abortar la instalación completa (bug real corregido — antes, un fallo puntual en la
   lectura de versión podía dejar el módulo marcado como "no instalado" pese a que el resto de
   la instalación había terminado correctamente en disco).
8. **Limpieza** — elimina el archivo de checkpoint temporal.

## 5. Detección de estado

Módulo con arranque/detención mediante una sesión `tmux` real (nombre de sesión: `openclaw`).
"Corriendo" se determina vía `tmux has-session` o por polling del puerto `18789`, no solo por
lectura del registro interno.

## 6. Pantalla de la app

OpenClaw usa un workspace propio en `$HOME/.openclaw/workspace` — separado de la carpeta
compartida de proyectos que usan otros CLIs de codificación integrados en Kairos.

Controles principales:

| Control | Acción |
|---|---|
| Interruptor Gateway | Inicia/detiene el proceso — corre los scripts de arranque/detención |
| Reiniciar gateway | Detiene y vuelve a iniciar |
| Ver logs | Muestra el log de runtime del gateway |
| Mostrar URL con token | Arma la URL completa con el token de autenticación (`http://localhost:18789/#token=...`), leído del archivo de configuración |
| Abrir interfaz web (local) | Si no está corriendo, lo arranca primero; luego abre la interfaz en un WebView interno |
| Abrir TUI (terminal) | Abre la interfaz de texto interactiva |
| Onboarding | Ejecuta el wizard interactivo de configuración inicial |
| Proveedor de IA / Modelo | Ver y editar la configuración de proveedores |
| Configurar canales | Diálogo para activar/editar Discord, Telegram, WhatsApp y Slack — token del bot/API y estado activo por canal |
| Gestionar workspaces | Importar/symlink/eliminar/sincronizar workspaces |
| Instalar / actualizar | Reinstalación completa |

## 7. Runtime — scripts generados

El script de arranque:
- Si el gateway ya responde en `:18789`, sale inmediatamente sin hacer nada.
- Mata cualquier proceso/sesión previa y arranca una sesión tmux nueva.
- Acepta un modo "no esperar" (dispara y sale sin bloquear).
- Por defecto, hace un chequeo de salud HTTP real contra el puerto antes de reportar éxito (no
  solo confirma que la sesión tmux existe).
- Calcula el heap de Node dinámicamente como un porcentaje de la memoria disponible del
  dispositivo (con un mínimo y máximo razonables) en vez de usar un valor fijo — esto evita que
  el sistema mate el proceso por falta de memoria justo cuando Node carga el bundle completo por
  primera vez.

El script de detención mata cualquier proceso/sesión de OpenClaw y confirma con una petición
HTTP que el gateway ya no responde.

## 8. Configuración — `~/.openclaw/openclaw.json`

Este es el archivo de configuración real y vigente de OpenClaw (confirmado contra la
documentación oficial del proyecto). Cada canal de mensajería (Discord, Telegram, WhatsApp,
Slack, Signal, iMessage, WebChat, y varios más vía plugins) tiene su propia sección bajo
`channels.<proveedor>` en ese mismo archivo.

El editor de canales de Kairos permite activar/desactivar un canal y guardar su token, con
respaldo automático del archivo de configuración antes de cada escritura y escritura atómica
(archivo temporal + renombrado). El alcance implementado cubre el caso de uso más común
(activar un canal con su token) — campos avanzados por proveedor (multi-cuenta, control de
acceso fino) no tienen editor propio en la app; para eso hay que editar
`~/.openclaw/openclaw.json` directamente.

## 9. Gotcha conocido: `gateway.mode=local`

El gateway de OpenClaw solo necesita el campo `gateway.mode` establecido en `"local"` dentro de
`openclaw.json` para arrancar y auto-generar su propio token de autenticación
(`gateway.auth.token`) — la elección de proveedor de IA es un paso posterior y separable, no un
prerequisito real del token. Kairos pre-siembra este campo automáticamente durante la
instalación (y lo refuerza en cada arranque del gateway) para que el usuario no necesite correr
el wizard de onboarding completo solo para obtener el token y acceder a la interfaz web.

## 10. Alcance del workspace

OpenClaw no tiene acceso arbitrario a cualquier carpeta del sistema de archivos de Termux — usa
únicamente su propio workspace dedicado (`$HOME/.openclaw/workspace`). El gestor de proyectos de
la app permite importar contenido desde Descargas, desde almacenamiento externo, o mediante
enlaces simbólicos desde otro proyecto ya importado — no permite navegar y enlazar una carpeta
arbitraria del directorio home de Termux directamente.
