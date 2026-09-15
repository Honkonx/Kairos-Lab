# Arquitectura de módulos

Los módulos de Kairos se clasifican por cómo corren realmente en el dispositivo: binario nativo
ARM64 directo, distro Linux completa vía proot, paquete de un gestor (npm/pip), o un método de
instalación fuera de esas categorías.

## Bionic nativo directo (ARM64 sin proot)

Corren como binario ARM64 puro contra la libc del entorno (Bionic) — sin proot, sin traducir
syscalls. Incluye compiladores/runtimes nativos y herramientas de sistema:

| Módulo | Qué es | Instalación real |
|---|---|---|
| Python | Python 3.13 + pip + SQLite | paquete nativo |
| Ollama | Motor LLM local | binario nativo (dos vías de instalación posibles) |
| Codex | CLI de Codex (OpenAI) | canal nativo o binario, ambos bionic |
| Engram | Memoria persistente para agentes | compilado desde fuente en Go — el binario resultante es nativo, aunque el método de obtención es compilación, no un paquete |
| Remote / SSH | Acceso SSH | OpenSSH vía gestor de paquetes |
| udocker | Contenedores sin root vía PRoot | paquete nativo |
| QEMU | qemu-user + qemu-system headless (sin KVM) | paquete nativo |
| DB | MySQL/MariaDB + PostgreSQL + SQLite + Redis | paquetes nativos, 4 servidores |
| Stacks | Catálogo de stacks de desarrollo (Python+PG, PHP+MySQL, React+Vite, HTML/CSS/JS) | mezcla paquete nativo + proot-distro para el preset de distro Linux completa |
| IDE (Neovim) | Neovim + NvChad + Copilot + CodeCompanion | paquete + plugins |
| APK | Compilador de APK en el propio dispositivo | herramientas nativas (aapt2/javac/d8/zipalign/apksigner) |
| Verificar | Verificación en vivo de módulos instalados | script propio |
| Repo | Empaquetador de paquetes + repositorio local | script propio |
| Servidor llama.cpp | Servidor HTTP de inferencia local (IA Local) | compilado propio, módulo NDK del proyecto |
| Ciberseguridad | Herramientas de red y auditoría básicas + distro Kali completa opcional | paquetes nativos en el nivel básico, proot-distro solo en el nivel avanzado |

Un módulo adicional de este grupo, Docker, no instala nada: explica por qué un runtime Docker
real no es posible sin acceso root en el dispositivo, y señala udocker (contenedores sin root vía
PRoot, en la misma tabla) como la alternativa real disponible.

**Herramientas de lenguaje nativas** (dependencias internas de otros módulos): Node.js, Perl,
PHP, Rust, Clang, Go — todas paquete nativo precompilado para ARM64.

## Requiere proot-distro / glibc (distro Linux completa)

No corren contra Bionic — necesitan una distro glibc completa dentro de un entorno proot:

| Módulo | Qué es | Nota |
|---|---|---|
| Claude Code | CLI de Anthropic | requiere entorno glibc |
| Antigravity | CLI de Google | binario glibc |
| OpenClaw | Gateway de IA compatible con la API de Claude | glibc |
| OpenCode | Editor de código con IA vía interfaz web | glibc |
| Cursor | CLI de Cursor | binario oficial con Node embebido, capa glibc |
| n8n | Automatización de workflows | dual: contenedor sin root (no exige proot-distro completo) o proot-distro clásico, a elección del usuario |
| Entorno | Escritorio XFCE4 nativo o distro Linux completa | XFCE4 corre nativo (bionic) sobre el servidor X11 embebido; la opción de distro completa usa proot-distro |

## CLI npm/node (paquete instalado vía npm sobre Node.js nativo)

Node.js en sí es nativo bionic, pero el paquete del módulo viene del registro npm:

- **Agentes de código con IA**: varios CLI de asistencia de código instalados como paquetes
  globales de npm.
- **Herramientas de desarrollo** (familia "Paquetes"): TypeScript, NestJS, Prettier, servidores
  de desarrollo, túneles locales, herramientas de deploy, formateo de SQL, actualización de
  dependencias, ngrok.

## Python / pip

- Motor de tool-calling local liviano (modelo pequeño, ~45M parámetros).
- Agente de código instalado vía pip.
- Parte del módulo de Ciberseguridad usa herramientas Python instaladas vía pip.

## Otro (compilado desde fuente, binario precompilado, o híbrido)

| Módulo | Método real | Por qué no encaja en las categorías anteriores |
|---|---|---|
| Engram | Compilado desde fuente en Go | No es un paquete de ningún gestor — el script compila el propio código fuente |
| Codegraph | Descarga un binario precompilado de releases de GitHub | No pasa por ningún gestor de paquetes |
| Ohmypi | Binario glibc con extensiones en Rust, descargado de releases; requiere otro módulo npm ya instalado | Mezcla: descarga de binario + dependencia de otro módulo |
| Hugging Face CLI | Descarga directa del binario oficial | Sin gestor de paquetes intermedio |

## Meta-entradas

"Lenguajes" y "Paquetes" son pantallas contenedoras que agrupan las herramientas internas de
arriba (un switch por lenguaje/herramienta) — no son módulos instalables en sí mismas.
