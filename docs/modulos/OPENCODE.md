# OpenCode — Módulo de Kairos

**Puerto:** `:3000` (servidor web principal) + `:4096` (segunda instancia opcional).

## 1. Descripción general

OpenCode es un editor de código con IA (TUI + servidor web) que soporta Ollama local como
proveedor. El binario oficial está compilado con glibc y no corre directamente sobre la libc
nativa de Android: Kairos usa el mismo enfoque que otros CLIs de codificación que integra (glibc
de Termux, sin proot ni distro Linux completa).

## 2. Permisos

No requiere permisos de Android especiales — corre 100% dentro de Termux (glibc de Termux, sin
proot, sin acceso a almacenamiento externo salvo lo que el usuario ya concedió globalmente en el
asistente de configuración inicial).

## 3. Instalación

El instalador acepta `--silent [--force]`.

### Pasos (6 en total)

```
1/6  Dependencias glibc: glibc-repo, glibc, openssl-glibc, ncurses
2/6  Detectar la última versión disponible (con reintento)
3/6  Descargar el paquete correspondiente
4/6  Instalar en Termux
5/6  Scripts de control: opencode_start.sh, opencode_stop.sh
6/6  Aliases + registro de estado
```

`ncurses` es una dependencia obligatoria (documentada como tal por el proyecto real): el binario
usa una biblioteca de terminal para su TUI. Sin `ncurses`, `opencode --version` funciona
igualmente, pero la TUI en sí falla en tiempo de ejecución.

La descarga de metadatos de releases reintenta automáticamente ante conexiones interrumpidas
(un problema real observado en redes móviles, donde una descarga puede reportarse como exitosa
pese a estar truncada).

## 4. Detección de estado

- Sesión tmux `opencode` — el estado "corriendo" se confirma con `tmux has-session`.
- Puerto `3000` — usado para confirmar el arranque real del servidor antes de reportar éxito a
  la interfaz.
- La instancia secundaria en el puerto `4096` se gestiona aparte, con su propia sesión tmux
  (`opencode-4096`).

## 5. Pantalla de la app

Controles principales:

| Control | Acción |
|---|---|
| Servidor web (selector de puerto :3000 / :4096) | Elige el puerto, un interruptor arranca/detiene el servidor en ese puerto |
| Abrir (visible solo si el servidor está corriendo) | Abre la interfaz web en un WebView interno |
| TUI en terminal | Abre la interfaz interactiva de texto (`opencode .`, corrida en el directorio home) |
| Gestionar proyectos | Importar/enlazar/eliminar/sincronizar proyectos en la carpeta compartida de proyectos |
| Enviar prompt (no interactivo) | Diálogo con texto libre, opción de continuar la última sesión, y modelo opcional — ejecuta el prompt sin abrir la interfaz interactiva completa |
| Configurar proveedor (login) | Configura API keys de proveedores en la nube |
| Ver servidores MCP | Panel con lista de servidores MCP configurados, activar/desactivar por servidor |
| Configurar Ollama local | Lee los modelos disponibles de Ollama y escribe la configuración correspondiente |
| Configurar llama-server local | Mismo mecanismo que Ollama, apuntando al servidor local de llama.cpp |
| Detener servidor | Detiene todas las sesiones/instancias del servidor web |
| Reinstalar / actualizar | Reinstalación completa |
| Desinstalar | Elimina la instalación |

El servidor web mata TODAS las sesiones activas (no solo la del puerto por defecto) al detenerse,
para evitar dejar instancias secundarias corriendo indefinidamente sin forma de pararlas desde la
interfaz.

## 6. Comandos de referencia

```bash
opencode-web          # inicia el servidor web (tmux "opencode", puerto 3000)
opencode-stop         # detiene todas las sesiones opencode*
opencode-status       # tmux has-session -t opencode
opencode-tui          # TUI directa
```

Comando no interactivo real (confirmado contra la documentación oficial del proyecto):

```bash
opencode run [--continue] '<prompt>' [--model '<proveedor/modelo>']
```

`opencode run` ejecuta el prompt de forma no interactiva pasándolo como argumento; `--model`/`-m`
acepta el formato `proveedor/modelo`; `--continue`/`-c` continúa la última sesión y es
combinable con `run` para enviar un mensaje de seguimiento sin abrir la TUI.

`opencode auth login` configura API keys de proveedores en la nube (Anthropic, OpenAI, etc.) —
distinto de apuntar a un proveedor local (Ollama/llama-server), que solo requiere escribir un
`baseURL` de estilo compatible con OpenAI, sin login.
