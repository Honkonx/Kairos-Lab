# n8n

**Módulo de Kairos** — gestionado desde la UI (pestaña Módulos). Instalación, arranque/detención y estado los maneja la app vía `ProcessBuilder` → `modulos/n8n.sh`.

---

**Script:** `modulos/n8n.sh` — copia sincronizada en `app/src/main/assets/scripts/n8n.sh`.
**Puerto:** `:5678`
**Registry:** prefijo `n8n.*`

---

## 1. Descripción general

n8n es una plataforma de automatización de workflows (nodo visual, self-hosted). En Kairos corre dentro de un entorno Linux completo (Debian, vía `proot-distro`) o vía `udocker` (contenedor sin root, sin proot) — es el único módulo del stack que todavía necesita un entorno Linux completo por debajo (a diferencia de OpenClaw/OpenCode, que corren en modo nativo glibc).

## 2. Permisos

Ninguno propio de Android más allá de `INTERNET`. La variante proot no necesita root — `proot-distro` emula el entorno sin privilegios elevados.

## 3. Instalación — `modulos/n8n.sh`

Acepta `--silent --variant <proot|udocker> [--source <clean|github|rootfs-github|rootfs-clean>] [--force]`. `--describe` → `{"id":"n8n","supports_silent":true,"supports_force":true,"variants":["udocker","proot"],"variant_required":false,"variant_default":"udocker","extra_flags":[{"name":"source","applies_to_variant":"proot","values":["clean","github","rootfs-github","rootfs-clean"],"default":"clean"}]}` — **`udocker` es la variante default** si no se pasa `--variant` explícito.

### Variantes

| Variante | Entorno | Cuándo usarla |
|---|---|---|
| `udocker` (default) | Contenedor `n8nio/n8n` (imagen oficial) vía `udocker`, sin proot | Default real — alternativa más liviana, sin distro completa |
| `proot` | Debian Bookworm ARM64 vía `proot-distro` | Entorno completo, más pesado — usar cuando se necesite el sistema Linux completo por debajo |

`--source` (solo aplica a `proot`) controla de dónde sale el rootfs Debian y n8n: `clean` (proot-distro + `npm install` limpios, default), `github` (todo desde GitHub Releases), `rootfs-github`/`rootfs-clean` (mixto).

### Pasos — variante `proot` (8 pasos, checkpoint por paso)

```
PASO 1/8  Termux update
PASO 2/8  proot-distro install debian (Bookworm ARM64)
PASO 3/8  Dentro del proot: Node.js 22 LTS (setup_22.x) + npm install -g n8n
            + cloudflared (binario ARM64 nativo del proot, para el túnel)
          Verificación real: Node major >= 20 (aborta si nodesource falló
          silenciosamente), exit code real de "npm install -g n8n" capturado
          con PIPESTATUS (no confiar en el pipe con "tail"), "n8n --version"
          confirmado antes de seguir (aborta si el binario no responde).
PASO 4/8  Scripts de control → ~/scripts/n8n/:
            start_servidor.sh, stop_servidor.sh, url.sh, status.sh,
            backup.sh, cf_token.sh
PASO 5/8  Aliases en .bashrc (n8n-start/-stop/-url/-status/-backup, cf-token)
PASO 6/8  Arranque automático (Termux:Boot)
PASO 7/8  Registry
PASO 8/8  Limpieza
```

### Pasos — variante `udocker` (8 pasos, checkpoint separado `~/.install_n8n_udocker_checkpoint`)

```
PASO 0/7  udocker (con 2 mirrors de udockertools fijos como fallback — el
          origen dinámico por defecto puede fallar en red móvil/CGNAT)
PASO 1/7  Crear contenedor n8nio/n8n (udocker create)
PASO 2/7  cloudflared nativo Termux (para el túnel, fuera del contenedor)
PASO 3/7  Scripts de control → ~/scripts/n8n-udocker/ (start.sh con
          health-check real vía /healthz)
PASO 4/7  Aliases
PASO 5/7  Registry
PASO 6-7  Arranque automático + limpieza
```

### Problemas reales ya corregidos

- **Detección de contenedor** — la variante `udocker` verifica la existencia del contenedor con `udocker inspect` en vez de parsear la salida de `udocker ps`, que no lista el nombre en una columna predecible.
- **Verificación real de udockertools** — en vez de confiar en el exit code de un `grep` sobre la salida del instalador, se verifica que el archivo `VERSION` que deja `udockertools` en disco realmente exista.
- **2 mirrors fijos de udockertools** como fallback — el origen dinámico por defecto puede fallar en redes móviles/CGNAT.
- **Node 22 LTS** — actualizado desde Node 20 (fin de soporte), n8n exige `>=20.19`.
- **Captura real de errores en pipes** — la instalación de Node.js y `npm install -g n8n` verifican el exit code real del comando (no el del último eslabón del pipe, típicamente `tail`), para no continuar silenciosamente tras un fallo.
- **Health-check real contra `/healthz`** en ambas variantes antes de dar el arranque por exitoso, en vez de un `sleep` fijo sin confirmar que n8n responda.
- **Parseo de versión en la variante udocker** — `udocker images` devuelve `REPO:TAG` en una sola columna; el script extrae el tag real en vez de asumir 2 columnas separadas.
- **`udocker pull` fallaba siempre por detección de plataforma** — la causa raíz: el Python que trae Termux reporta `platform.system() == "Android"` en vez de `"Linux"`, y `udocker` usa ese valor para armar el selector de plataforma del manifest de Docker Hub — como no existe ningún manifest para `android/arm64` (no es una plataforma real de Docker), la descarga de la imagen `n8nio/n8n` fallaba el 100% de las veces, sin importar la red. El fix pasa `--platform=linux/arm64` explícito a `udocker pull` para evitar la autodetección rota.

## 4. Detección de estado — `ModuleController.kt`

- `getTmuxSession("n8n")` → `"n8n-server"`.
- `getModulePort("n8n")` → `5678` — verificado con `waitForPortOpen()` tras un arranque exitoso.
- `getModuleStartScript("n8n")` → `$HOME/scripts/n8n/start_servidor.sh` (variante proot; la variante udocker usa su propio `start.sh` en `~/scripts/n8n-udocker/`).

## 5. Pantalla de la app — `N8nFragment.kt`

- Card ESTADO: entorno (`proot`/`udocker`), modo red (local / túnel Cloudflare), versión, URL de tunnel, pill de estado (corriendo/detenido).
- Dropdown + switch para modo de red y arranque/detención: el dropdown elige local/túnel Cloudflare (`~/.n8n_local_only`), el switch controla start/stop. Cambiar de modo requiere apagar primero. Lo leen `start.sh` (udocker) y `start_servidor.sh` (proot) antes de levantar el túnel — aplica recién al próximo inicio.
- "Abrir interfaz web (local)" — WebView interna a `http://localhost:5678` (arranca n8n si no está corriendo).
- "Ver URL del tunnel" — `cat ~/.last_cf_url`.
- **"Workflows (API)"** — lista los workflows reales de la instancia vía la API REST pública de n8n (`GET /api/v1/workflows`), con indicador de activo/inactivo. Tocar uno abre un diálogo con la acción "Activar"/"Desactivar" (`POST /workflows/{id}/activate|deactivate`). Si no hay API Key configurada, avisa y abre el diálogo de configuración en vez de fallar.
- **"API Key de n8n"** — guarda/borra la API Key en `~/.n8n_api_key` (prompt de texto; la key se genera manualmente en la UI de n8n, Settings → n8n API → Create an API key — no hay forma de emitirla por CLI sin sesión iniciada).
- "Ver logs" — script real según variante (`n8n_log.sh` proot / `log.sh` udocker).
- "Backup de workflows" → corre `~/scripts/n8n/n8n_backup.sh` (proot) o `~/scripts/n8n-udocker/backup.sh` (udocker), según variante.
- "Gestionar proyectos" — mismo menú compartido (symlink/copiar/sincronizar) que usan Claude/Codex/OpenCode/Antigravity/OpenClaw/Hermes, sin lanzador de proyecto propio (n8n es un servidor, no una CLI que abre una carpeta).
- "Actualizar n8n" → corre el script real según variante (`n8n_update.sh` proot / `update.sh` udocker).
- **"Token Cloudflare (URL fija)"** — permite fijar un token real de Cloudflare Tunnel para tener una URL pública permanente en vez de la temporal que cambia cada reinicio. El token se guarda vía `TunnelManager` (el mismo backend de la pestaña Tunnel) con id `"n8n"`, puente interno con el resto de la config de túneles.
- **"Configurar dominio webhook"** — fija `N8N_WEBHOOK_URL` en `~/.env_n8n` para que los webhooks se construyan con un dominio propio en vez del subdominio `*.trycloudflare.com` temporal. El dominio (pelado, sin esquema) se guarda vía `TunnelManager` con id `"n8n"` — `~/.env_n8n` sigue existiendo, como espejo real (n8n el proceso lo sigue leyendo para arrancar).
- **"Cambiar protocolo HTTP/HTTPS"** — alterna `~/.n8n_protocol` (solo aplica al modo proot; escribir el archivo es inofensivo si el usuario está en udocker).
- **"Reparar scripts de control"** — regenera SOLO start/stop/log/status/update/backup (según la variante instalada) vía `modulos/n8n.sh --repair-scripts --silent`, sin tocar el contenedor/rootfs ni los workflows/credenciales del usuario.
- **"Ver ejecuciones recientes"** — `GET /api/v1/executions` para ver si la última corrida falló sin abrir el WebView completo. El campo `status` a veces falta en el listado (comportamiento conocido de la API de n8n) — se cae a `finished` como respaldo.

Instalación silenciosa en segundo plano si el módulo no está instalado: diálogo elige variante (`udocker`/`proot-distro`), instala sin bloquear la navegación.

## 5b. Workflows API (`N8nApiClient.kt`)

`app/src/main/java/com/termux/app/util/N8nApiClient.kt` — mismo patrón HTTP que `OllamaApiClient` (`HttpURLConnection` + `org.json`, sin librería externa). Habla con `http://127.0.0.1:5678/api/v1` usando el header `X-N8N-API-KEY`. Funciones: `hasApiKey()`/`readApiKey()`/`writeApiKey()` (persistidas en `~/.n8n_api_key`), `listWorkflows()` (`GET /workflows?limit=100`) y `setActive(id, active)` (`POST /workflows/{id}/activate` o `.../deactivate`). Todas las funciones son bloqueantes — el caller es responsable de correrlas en un `Thread` propio.

## 6. Cloudflare Tunnel

Sin token: túnel **temporal** (`*.trycloudflare.com`, cambia cada reinicio) — extraído de `cf_url.log`. Con token: túnel **fijo**, misma URL siempre.

**Arquitectura de almacenamiento del token/dominio**: n8n conserva su propio botón "Token Cloudflare (URL fija)" en su pantalla, pero el valor real se guarda en `TunnelManager` (`app/src/main/java/com/termux/app/util/TunnelManager.kt`) con `moduleId = "n8n"` — mismas claves de registry que usa la pestaña Tunnel, con el id del módulo como prefijo (`tunnel.n8n.cloudflare.token`/`.domain`, en vez de las claves globales `tunnel.cloudflare.*` que usan los botones rápidos genéricos). Remote/SSH sigue el mismo patrón con `moduleId = "remote"` (solo token, SSH es TCP crudo — sin campo de dominio real).

`~/.env_n8n` (con `N8N_WEBHOOK_URL=...`) sigue existiendo y sigue siendo necesario — n8n el proceso (no la UI de Kairos) lee ese archivo para arrancar con el dominio configurado — pero pasó a ser un espejo que se reescribe cada vez que se guarda el dominio, no la fuente de verdad que la UI relee para mostrar el estado actual (eso lo cubre `TunnelManager.getConfig("cloudflared", "n8n")`).

En la pestaña Tunnel, el túnel propio de n8n (arrancado por `modulos/n8n.sh` solo, no por `TunnelManager`) se muestra con su estado real (activo/inactivo, URL si hay) pero sin botones de arrancar/detener desde ahí — el control real de ese túnel vive en la pantalla de n8n; Tunnel es solo panel de visibilidad para este caso.

## 7. Registry (`~/.android_server_registry`)

```
n8n.installed=true
n8n.version=<versión real>
n8n.install_date=YYYY-MM-DD
n8n.mode=proot|udocker
n8n.port=5678
```

## 8. Comandos de referencia

```bash
n8n-start     # alias -> start_servidor.sh (proot) o start.sh (udocker)
n8n-stop
n8n-url       # URL real del túnel activo
n8n-status
n8n-backup    # backup de workflows
cf-token <token>   # fijar token de Cloudflare Tunnel
```
