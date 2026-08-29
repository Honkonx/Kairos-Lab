# Catálogo de módulos de Kairos

Kairos organiza cada servicio, CLI o herramienta que puede instalar como un **módulo**: un
script bash independiente (silencioso, sin prompts, con checkpoints de recuperación ante
fallos) que la app invoca vía `ProcessBuilder`, más una entrada en el catálogo de módulos que
describe su puerto, tamaño estimado, arquitectura requerida y si necesita proot. Todos los
módulos corren en Termux — nativamente contra la libc de Android (Bionic) cuando es posible, o
sobre una capa de compatibilidad glibc/proot cuando el binario real del proyecto lo exige — sin
necesidad de rootear el dispositivo.

Este catálogo lista los módulos reales agrupados por categoría, con qué instala cada uno y una
descripción breve. Para el detalle completo de arquitectura, opciones de configuración y
controles de la pantalla de cada módulo, ver el documento dedicado del módulo cuando exista
(`HERMES.md`, `OPENCLAW.md`, `OPENCODE.md`, `UDOCKER.md` en esta misma carpeta).

## IA local y agentes con interfaz propia

| Módulo | Instala | Descripción |
|---|---|---|
| **ollama** | Ollama (variante GPU vía npm con soporte Vulkan, o variante estándar vía paquete `pkg`, solo CPU) + scripts de control | Servidor de inferencia para modelos de lenguaje locales, con API HTTP compatible. Es el backend de IA local que reutilizan la mayoría de los demás módulos con capacidades de IA (Hermes, OpenCode, Cactus, etc.). |
| **llamaserver** | El binario `llama-server` (servidor HTTP compatible con la API de OpenAI, parte de llama.cpp) + scripts de control | Backend de inferencia local alternativo a Ollama, basado directamente en llama.cpp. Reutiliza los modelos `.gguf` ya descargados por el resto de la app en vez de gestionar su propio catálogo de modelos. |
| **hermes** | Hermes Agent (Nous Research) — Python en un entorno virtual dedicado, sin proot | Framework de agente de IA de código abierto con TUI interactiva y un gateway de mensajería multicanal (Telegram, Discord, Slack, SMS, Signal) que corre 100% nativo en Termux. |
| **openclaw** | OpenClaw — paquete npm sobre una capa de compatibilidad glibc, sin proot | Gateway de agente de IA con interfaz web y TUI propias, puerto fijo `18789`, compatible con proveedores locales (Ollama) y en la nube. |
| **opencode** | OpenCode — binario oficial sobre glibc de Termux, sin proot | Editor de código con IA (TUI + servidor web en `:3000`, con segunda instancia opcional en `:4096`), con soporte para Ollama local. |
| **cactus** | Cactus Needle — paquete pip (motor de ~45M de parámetros embebido) | Motor de tool-calling local liviano — puede ejecutar acciones (bash, Python, JSON) directamente o razonar primero vía un LLM local (Ollama/llama-server) antes de actuar. |
| **n8n** | n8n, en una distro Debian vía proot o en una imagen oficial vía udocker (a elección) + cloudflared para exponerlo por túnel | Plataforma de automatización de workflows con editor visual, puerto `5678`. |
| **engram** | Engram (compilado desde fuente en Go) | Sistema de memoria persistente para agentes de IA — guarda contexto entre sesiones en una base SQLite local, sin depender de ningún servicio externo, para que Claude Code, OpenCode y otros agentes lo usen como memoria compartida. |

## CLIs de codificación con IA (interfaz compartida)

Los siguientes módulos son CLIs de agentes de codificación por terminal — la mayoría no tiene
interfaz web propia y comparten un mismo tipo de pantalla en la app (lanzar en terminal, gestión
de proyectos, continuar última sesión donde el CLI lo soporta).

| Módulo | Instala | Descripción |
|---|---|---|
| **claude** | Claude Code — binario nativo (ELF/glibc) | CLI de codificación de Anthropic. |
| **codex** | OpenAI Codex CLI — vía npm | CLI de codificación de OpenAI. |
| **antigravity** | Antigravity CLI (`agy`) — binario nativo | CLI de codificación con autenticación por cuenta de Google. |
| **copilotcli** | GitHub Copilot CLI — vía npm (`@github/copilot`) | CLI de codificación de GitHub, requiere una cuenta con Copilot activo. |
| **cursor** | Cursor CLI (`cursor-agent`) — instalador oficial sobre una capa de compatibilidad glibc | CLI de codificación del editor Cursor. |
| **kilo** | Kilo Code CLI — binario nativo ARM64 parcheado para correr sobre glibc de Termux | CLI de codificación de Kilo Code. |
| **kimi** | Kimi Code (`@moonshot-ai/kimi-code`) — vía npm | CLI de codificación de Moonshot AI. |
| **minimaxcli** | MiniMax CLI (`mmx-cli`) — vía npm | CLI de codificación de MiniMax. |
| **mistralvibe** | Mistral Vibe — vía pip | CLI de codificación de Mistral, basado en Python. |
| **qwencode** | Qwen Code (`@qwen-code/qwen-code`) — vía npm | CLI de codificación de Alibaba, fork de Gemini CLI. |
| **pi** | Pi Coding Agent (`@earendil-works/pi-coding-agent`) — vía npm | CLI de codificación por terminal. |
| **ohmypi** | Oh-My-Pi (`omp`) — binario compilado contra glibc, con addons nativos en Rust | Versión ampliada y standalone de Pi Coding Agent — gestión de sesiones, soporte MCP, herramientas de análisis de código (AST grep, diff, resaltado de sintaxis, búsqueda difusa). |
| **codebuff** / **freebuff** | Codebuff / Freebuff (fork de código abierto de Codebuff) — binario nativo, con métodos de instalación en cascada según arquitectura | CLIs de codificación de CodebuffAI; Freebuff es la variante que usa un runtime Bun nativo para Bionic como método preferido. |
| **mimocode** | MiMo Code (Xiaomi) — binario nativo Bionic puro (sin dependencia de glibc) | CLI de codificación de Xiaomi. |
| **codegraph** | CodeGraph — binario Node.js precompilado | **No es un agente de IA** — es una herramienta de análisis estático que genera un grafo de relaciones entre archivos/funciones/clases de un proyecto, para navegación y refactor. |
| **hf** | Hugging Face CLI (`hf`) — instalador oficial | Gestión de modelos, datasets y spaces de Hugging Face desde la terminal, incluida la descarga de archivos GGUF con reanudación de descargas interrumpidas. |

## Servicios remotos y red

| Módulo | Instala | Descripción |
|---|---|---|
| **ssh** (Remote) | OpenSSH (puerto `8022`) + cloudflared nativo (túnel) + `mosh-server` (best-effort) | Acceso remoto al dispositivo por SSH, expuesto por túnel de Cloudflare sin necesidad de IP pública. Mosh, cuando está disponible, da sesiones resilientes a cambios de red. |
| **db** | MariaDB (MySQL), PostgreSQL, SQLite y Redis, cada uno con sus propios scripts de arranque/detención | Motores de base de datos locales para desarrollo. |
| **docker** | Nada — módulo informativo | Explica por qué un daemon Docker real no puede correr sin rootear el dispositivo (Android no expone namespaces/cgroups a apps sin root) y dirige al módulo `udocker` como alternativa real ya integrada. |
| **udocker** | udocker + `udockertools`, modo de ejecución `P2` forzado, wrappers de gestión de contenedores | Runtime de contenedores en espacio de usuario (sin root, vía PRoot) que puede bajar y correr imágenes reales de Docker Hub. Ver `UDOCKER.md` para el detalle completo. |
| **qemu** | Paquetes `qemu-user-*` (emulación de binarios de otra arquitectura) y `qemu-system-*-headless` (máquinas virtuales sin gráficos, sin aceleración KVM) | Emulación de CPU/binarios. El modo usuario (correr un binario x86_64 en un teléfono ARM64, por ejemplo) es el caso de uso más sólido sin root; el modo sistema corre por software puro (sin KVM), útil para probar una imagen liviana, no para un escritorio fluido. |

## Entorno de escritorio y desarrollo

| Módulo | Instala | Descripción |
|---|---|---|
| **entorno** | proot-distro + udocker, PulseAudio, drivers de GPU según el hardware, herramientas de escritorio (XFCE, VNC) y de IA base en el host | El módulo "mini PC" — arma distros Linux completas con escritorio gráfico dentro del dispositivo, con un servidor X11 embebido en la propia app (sin depender de una app externa). |
| **stacks** | No instala nada propio — es un catálogo de recetas que reutiliza otros módulos (Python, DB) o instala paquetes puntuales (Node.js, PHP) según el preset elegido | Entornos de desarrollo predefinidos (Python+PostgreSQL, PHP+MySQL, React+Vite, sitio estático, o una distro Linux completa), instalables nativamente o dentro de una distro proot ya existente. |
| **ide** | Neovim + NvChad (framework de configuración) + integración de Copilot/CodeCompanion | Editor de código completo directamente en la terminal de Kairos, sin necesidad de un editor gráfico. |
| **apk** | Cadena de compilación de APKs Android (aapt2, javac, d8, zipalign, apksigner) | Compilador de aplicaciones Android directamente en el dispositivo, sin Android Studio. |

## Seguridad y verificación

| Módulo | Instala | Descripción |
|---|---|---|
| **ciberseguridad** | Nivel básico: nmap, netcat, dirb, nikto, theHarvester, sqlmap (nativos, sin proot). Nivel pro: además, una distro Kali Linux completa vía proot-distro | Kit de herramientas de red/OSINT para pruebas de seguridad, en dos niveles según cuánto espacio y capacidad quiera dedicar el usuario. |
| **verificar** | Nada — herramienta de diagnóstico | Verifica en vivo, contra el sistema de archivos real, que los módulos que el registro interno marca como instalados sigan funcionando de verdad (detecta instalaciones rotas por una limpieza manual o una actualización fallida). |
| **repo** | Estructura de un repositorio apt local en el dispositivo | Permite empaquetar lo que un módulo ya instaló como un `.deb` real, instalable después con `pkg install` desde cualquier sesión de Termux. |

## Lenguajes y herramientas de línea de comandos

Estos módulos livianos se presentan agrupados en dos pantallas contenedoras de la app
("Lenguajes" y "Paquetes"), cada ítem con su propio interruptor de instalación:

| Módulo | Instala |
|---|---|
| **nodejs** | Node.js LTS (con Corepack habilitado para pnpm/yarn) |
| **perl** | Perl |
| **php** | PHP CLI |
| **rust** | Rust (rustc + cargo) |
| **clang** | Clang (compilador C/C++) |
| **golang** | Go |
| **kotlin** | Kotlin (`kotlinc`) |
| **typescript** | TypeScript |
| **nestjs** | NestJS CLI |
| **prettier** | Prettier |
| **livesrv** | Live Server |
| **localtunnel** | Localtunnel |
| **vercel** | Vercel CLI |
| **markserv** | Markserv |
| **psqlformat** | PSQL Format |
| **ncu** | npm-check-updates |
| **ngrok** | ngrok |

## Herramientas de producto (Expo/EAS)

| Módulo | Instala | Descripción |
|---|---|---|
| **expo** | EAS CLI (Expo Application Services) vía npm | Compilación, actualizaciones OTA y publicación de apps React Native/Expo desde el dispositivo. |

---

*Kairos es una app Android nativa (fork de termux-app con interfaz Kotlin/Java) que unifica
estos módulos bajo una sola interfaz gráfica, pensada para que la mayoría de las tareas de cada
servicio tengan un camino de botones/diálogos reales — la terminal integrada sigue disponible
para power users, pero no es el único camino.*
