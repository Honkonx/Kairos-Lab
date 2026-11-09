# n8n

**Módulo de Kairos** — gestionado vía la UI de Kairos (tab Módulos). Instalación, start/stop y estado los maneja la app vía `ProcessBuilder` → `modulos/n8n.sh`.

---

**Script:** `modulos/n8n.sh` (v4.0.0, 1452 líneas) — copia sincronizada en `app/src/main/assets/scripts/n8n.sh`.
**Puerto:** `:5678`
**Registry:** prefijo `n8n.*`

> Convención: **confirmado** = verificado leyendo el código; **sin verificar** = inferencia sin prueba en ejecución.

---

## 1. Descripción general

n8n es una plataforma de automatización de workflows (nodo visual, self-hosted) servida en `:5678`. En Kairos corre dentro de un entorno Linux completo (Debian, vía `proot-distro`) o vía `udocker` (contenedor sin root, sin proot) — es el único módulo del stack que todavía necesita un entorno Linux completo por debajo (a diferencia de OpenClaw/OpenCode, migrados a glibc nativo). Kairos la arranca/detiene desde la app y la gestiona (túnel Cloudflare, webhook, workflows vía REST API). Alta del módulo: 2026-07-19.

Clasificación: **módulo funcional** — UI propia (`N8nFragment`, mapeada en `ModuleDetailNavigator.kt:30`), servicio con estado (start/stop/tmux/puerto en `ModuleController.kt:1163,1185,1198,1236`), registry propio y opciones reales (~15 controles).

## 2. Permisos

Ninguno propio de Android más allá de `INTERNET`. La variante proot no necesita root — `proot-distro` emula el entorno sin privilegios elevados.

## 3. Instalación — `modulos/n8n.sh`

Flags (`n8n.sh:65-71`): `--silent`, `--force`, `--describe`, `--describe-files`, `--repair-scripts`, `--source <clean|github|rootfs-github|rootfs-clean>`, `--variant <udocker|proot>`. `--describe` → `{"id":"n8n","supports_silent":true,"supports_force":true,"variants":["udocker","proot"],"variant_required":false,"variant_default":"udocker","extra_flags":[{"name":"source","applies_to_variant":"proot","values":["clean","github","rootfs-github","rootfs-clean"],"default":"clean"}]}`.

**`udocker` es la variante default** (`VARIANT="udocker"`, `n8n.sh:59`; `proot*|debian` → proot, cualquier otro valor cae a udocker, `:206-210`) si el caller no pasa `--variant`; la UI (`N8nFragment.kt:231`) lista udocker primero.

### Variantes

| Variante | Entorno | Cuándo usarla |
|---|---|---|
| `udocker` (default) | Contenedor `n8nio/n8n` (imagen oficial) vía `udocker`, sin proot | Más liviana, sin distro completa |
| `proot` | Debian Bookworm ARM64 vía `proot-distro` | Entorno completo, más pesado |

`--source` (solo aplica a `proot`) controla de dónde sale el rootfs Debian y n8n: `clean` (proot-distro + `npm install` limpios, default), `github` (todo desde GitHub Releases), `rootfs-github`/`rootfs-clean` (mixto). La UI no lo pasa por CLI: `showInstallSourceDialog()` escribe `~/.n8n_install_source` (`N8nFragment.kt:270-296`) y el script lo lee solo si `--source` no vino explícito (`n8n.sh:88-90`).

`--describe-files` (`:117-`) describe solo la vía udocker (scripts de control + dependencias `udocker`, `udockertools`, contenedor `n8n`, `cloudflared`); la vía proot no tiene describe-files (`not_covered`, declarado en el propio script).

### Pasos — variante `proot` (8 pasos, checkpoint por paso; `:1040-1450`)

```
PASO 1/8  Termux update
PASO 2/8  proot-distro install debian (Bookworm ARM64)
PASO 3/8  Dentro del proot: Node.js 22 LTS (setup_22.x) + npm install -g n8n --unsafe-perm
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
PASO 7/8  Registry (aborta si n8n --version no responde, :1414-1421)
PASO 8/8  Limpieza
```

### Pasos — variante `udocker` (checkpoint separado `~/.install_n8n_udocker_checkpoint`; `:691-1034`)

```
PASO 0/7  udocker (con 2 mirrors de udockertools fijos como fallback — el
          origen dinámico por defecto falla intermitente en red móvil/CGNAT)
PASO 1/7  Crear contenedor n8nio/n8n (udocker create --name=n8n)
PASO 2/7  cloudflared nativo Termux (para el túnel, fuera del contenedor)
PASO 3/7  Scripts de control → ~/scripts/n8n-udocker/ (start.sh con
          health-check real vía /healthz)
PASO 4/7  Aliases (n8n-ud-*)
PASO 5/7  Registry
PASO 6-7  Arranque automático + limpieza
```

La variante udocker exporta `TMPDIR` y `UDOCKER_USE_CURL_EXECUTABLE`, crea `~/n8n-udocker` (chmod 777, `:860`), instala `cloudflared` (pkg o descarga, con chequeo de que ejecuta, `:865-895`), hace `udocker pull --platform=linux/arm64` con fallback a `:stable` (`:897-936`), y arranca n8n de verdad antes de escribir el registry (`:1007-1030`; si falla, `invalidate_registry` + error).

Verificación post-instalación: udocker = health-check real vía `start.sh`; proot = `n8n --version` dentro del proot (en proot no se arranca el servidor durante la instalación). El script no usa `verify_binary_installed()` de `lib.sh`.

Registry (`update_registry`, `:196-199` → `registry_install n8n <ver> mode=… port=5678`): `n8n.installed/version/install_date/mode/port`. `invalidate_registry` (`:209`) escribe `n8n.installed=false` en fallos.

`modules.json`: `requires: [nodejs, git]`, `installMethods: [udocker, proot-distro]`, `arch: distro`, `catalogVersion 3.0.0` (el script se auto-titula v4.0.0, `n8n.sh:44`; desfase de versionado).

### Comportamientos resueltos

- **`udocker inspect` en vez de `udocker ps | grep`** — `ps`/`ps -a` listan primero el UUID del contenedor y el nombre en una columna posterior, así que `grep -q "^n8n"` nunca matcheaba; se usa `udocker inspect`.
- **Verificación real de udockertools** — se verifica `$HOME/.udocker/lib/VERSION` en disco (lo que udockertools deja al extraer bien el tarball) en vez del exit code de `grep`.
- **2 mirrors de udockertools fijos** — el origen dinámico por defecto falla intermitente en redes móviles/CGNAT. Se prueban 2 mirrors conocidos-buenos en loop antes de rendirse.
- **Node 22 LTS** — n8n exige ≥20.19.
- **Captura real de error en 2 pipes** — la instalación de NodeSource (`curl|bash`) y el `npm install -g n8n` capturan `PIPESTATUS[0]` y abortan mostrando el log real si el comando de la izquierda del pipe falló silenciosamente.
- **Verificación real antes de escribir el registry** — si `n8n --version` no responde, aborta con error real en vez de escribir `n8n.version="unknown"`.
- **Health-check real en ambas variantes** — ambas hacen polling real contra `http://localhost:5678/healthz` y `exit 1` si no responde a tiempo.
- **Parseo de `udocker images`** — la versión instalada (1.3.17) devuelve `REPO:TAG` en una sola columna (`n8nio/n8n:latest`); se extrae el tag de después de los `:` en la primera columna (`:1020`).
- **`udocker pull` y la plataforma detectada** — udocker arma el selector de plataforma del manifest de Docker Hub con `platform.system().lower()` (`udocker/helper/hostinfo.py::HostInfo.osversion()`), y el Python de Termux devuelve `"Android"` (no `"Linux"`); Docker Hub no tiene manifest para `android/arm64`, así que el pull fallaba siempre con `no image found in manifest for platform (android/arm64)`. Se pasa `--platform=linux/arm64` explícito (flag documentado de udocker) en la instalación inicial y en `update.sh`.

## 4. Ejecución y detección de estado — `ModuleController.kt`

- Start: `getModuleStartScript("n8n")` → `~/scripts/n8n-udocker/start.sh` si `n8n.mode=udocker`, si no `~/scripts/n8n/start_servidor.sh` (`ModuleController.kt:1163`; `readN8nMode()` cae a `"proot"` si no hay registry, `:1159`).
- Sesión tmux: `getTmuxSession("n8n")` → `n8n-udocker` o `n8n-server` según modo (`:1198`); el túnel corre en una ventana `tunnel` de la misma sesión.
- Puerto 5678 (`getModulePort("n8n")`, `ModuleController.kt:1236`, `modules.json` `port:"5678"`, coinciden), verificado con `waitForPortOpen()` tras un arranque exitoso. `isRunning` = tmux vivo, con fallback a puerto abierto (`:370-390`).
- Timeouts (los tres difieren): Kotlin espera 60 s el puerto (`startTimeoutMsFor`, `ModuleController.kt:113-115`); `start.sh` udocker espera hasta 180 s (`seq 1 90` x 2 s) y proot hasta 240 s (`seq 1 80` x 3 s), más 15 s de margen. Un arranque en frío entre 60 s y 180 s podría marcarse como fallo en la UI aunque n8n termine subiendo (sin probar).
- Stop: udocker `stop.sh` hace `pkill -f "cloudflared tunnel"` + `tmux kill-session` (`n8n.sh:373-376`: mata cualquier cloudflared del usuario, no solo el de n8n); proot `stop_servidor.sh` hace `pkill -f "n8n start"` y `cloudflared` dentro del proot.
- Env de arranque: ambas variantes fijan `N8N_HOST=0.0.0.0`, `N8N_PORT`, `N8N_SECURE_COOKIE=false`, `N8N_RUNNERS_ENABLED=true`, `NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os`, `NODE_FUNCTION_ALLOW_EXTERNAL=*`; `WEBHOOK_URL` desde `~/.env_n8n`. `N8N_PROXY_HOPS=1` y `N8N_RUNNERS_HEARTBEAT_INTERVAL=300` solo en proot (`:468-469`); udocker no los lleva.
- Modo local: con `~/.n8n_local_only` no se levanta cloudflared; sin él, con `~/.cf_token` hay URL fija, sin token hay `trycloudflare.com` temporal.

## 5. Pantalla de la app — `N8nFragment.kt`

Fragment: `N8nFragment` (hereda `BaseModuleFragment`, 828 líneas). Sin instalar: `showNotInstalled` → diálogo de variante (`udocker`/`proot-distro`, `:47-50`), instala vía `installModuleInBackground()` sin bloquear la navegación. Instalado: card ESTADO (entorno desde `n8n.mode`, versión del registry, URL túnel de `~/.last_cf_url`, pill de estado, `:53-93`) y los controles de la tabla de abajo. Operación real: `startModuleService/stopModuleService` (switch), `ModuleWebViewFragment` a `http://localhost:5678` (`:807`), `N8nApiClient` (`GET /workflows`, `POST /workflows/{id}/activate|deactivate`, `GET /executions`), `ProcessBuilder(TERMUX_BASH_PATH, script)` para backup/actualizar/reparar, `launchTerminalCommand` para ver logs (`tmux attach`) y URL del túnel. No hay botón "Abrir en terminal" genérico.

### 5b. Workflows API (`N8nApiClient.kt`)

`app/src/main/java/com/termux/app/util/N8nApiClient.kt` — mismo patrón HTTP que `OllamaApiClient` (`HttpURLConnection` + `org.json`, sin librería externa). Habla con `http://127.0.0.1:5678/api/v1` usando el header `X-N8N-API-KEY`. Funciones: `hasApiKey()`/`readApiKey()`/`writeApiKey()` (persistidas en `~/.n8n_api_key`), `listWorkflows()` (`GET /workflows?limit=100`), `setActive(id, active)` (`POST /workflows/{id}/activate` o `.../deactivate`) y `listExecutions()` (`GET /executions`). Todas las funciones son bloqueantes — el caller es responsable de correrlas en un `Thread` propio.

## 6. Cloudflare Tunnel

Sin token: túnel **temporal** (`*.trycloudflare.com`, cambia cada reinicio) — extraído de `cf_url.log` con `grep -o`. Con token: túnel **fijo**, misma URL siempre.

**Almacenamiento del token/dominio**: n8n tiene su botón propio "Token Cloudflare (URL fija)" en esta pantalla, pero el valor real se guarda en `TunnelManager` (`app/src/main/java/com/termux/app/util/TunnelManager.kt`) con `moduleId = "n8n"` — mismas claves de registry que usa la pestaña Tunnel, con el id del módulo como prefijo (`tunnel.n8n.cloudflare.token`/`.domain`, en vez de las claves globales `tunnel.cloudflare.*` que usan los botones rápidos genéricos). Remote/SSH sigue el mismo patrón con `moduleId = "remote"` (solo token, SSH es TCP crudo — sin campo de dominio real). Si el usuario ya tenía un token en el archivo legacy (`~/.cf_token` para n8n, `~/.cf_ssh_token` para Remote), se mueve una sola vez a `TunnelManager` la primera vez que se lee/abre el diálogo correspondiente. `TunnelManager.mirrorBespokeFiles()` escribe `~/.cf_token` como espejo (`TunnelManager.kt:531-540`).

`~/.env_n8n` (con `N8N_WEBHOOK_URL=...`) sigue siendo necesario — n8n el PROCESO (no la UI de Kairos) lee ese archivo para arrancar con el dominio configurado — pero es un espejo que se reescribe cada vez que se guarda el dominio, no la fuente de verdad que la UI relee (eso es `TunnelManager.getConfig("cloudflared", "n8n")`).

En la pestaña **Tunnel**, el túnel propio de n8n (arrancado por `modulos/n8n.sh`, no por `TunnelManager`) se muestra con su estado real (activo/inactivo, URL si hay) pero **sin botones de arrancar/detener** desde ahí: el control real de ese túnel vive en esta pantalla (switch de n8n), Tunnel es solo panel de visibilidad para este caso (ver `TunnelFragment.applyState()`, parámetro `ownTunnel`).

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

## 9. Controles de la pantalla (`N8nFragment.kt`)

| Control | Qué hace | Notas |
|---|---|---|
| Card ESTADO (Entorno) | Solo lectura — `n8n.mode` del registry (proot/udocker) | Muestra la variante real instalada |
| Dropdown+switch "n8n" (modo de red + arranque) | Dropdown elige local/túnel Cloudflare (`.n8n_local_only`), switch controla start/stop (`N8nFragment.kt:64-72`) | Cambiar de modo requiere apagar primero (switch bloqueado mientras corre). Aplica recién al próximo inicio; lo leen `start.sh` (udocker) y `start_servidor.sh` (proot) antes de levantar el túnel |
| "🌐 Abrir interfaz web (local)" | Arranca n8n si hace falta y navega al WebView (`localhost:5678`) | — |
| "🌐 Ver URL del túnel" | `cat ~/.last_cf_url` en terminal | — |
| "📋 Workflows (API)" | Lista los workflows reales de la instancia vía la API REST de n8n (`N8nApiClient`), con ⚪/✅ según estén inactivos/activos. Tocar uno abre un diálogo con la acción "Activar"/"Desactivar". Sin API Key, avisa y abre el diálogo de configuración | — |
| "🔑 API Key de n8n" | Guarda/borra la key en `~/.n8n_api_key` (prompt de texto) | La key se genera a mano en la UI web de n8n (Settings → n8n API → Create an API key); no hay forma de emitirla por CLI sin sesión iniciada. El diálogo no precarga el valor guardado; la key se muestra solo como configurada/no configurada (`N8nFragment.kt:687`) |
| "📋 Ver logs" | Corre el script de log real según la variante (`n8n_log.sh` proot / `n8n-udocker/log.sh`) | — |
| "☁ Backup de workflows" | Corre `~/scripts/n8n/n8n_backup.sh` (proot) o `~/scripts/n8n-udocker/backup.sh` (udocker) | — |
| "🗂 Gestionar proyectos" | Menú compartido symlink/copiar/sincronizar (`showProjectsMenu`) | Mismo menú que Claude/Codex/OpenCode/Antigravity/OpenClaw/Hermes, sin `onLaunchInProject` (n8n es un servidor, no una CLI que abre una carpeta) |
| "↑ Actualizar n8n" | Corre el script de update real según la variante (`n8n_update.sh` proot / `update.sh` udocker) | — |
| "🔑 Token Cloudflare (URL fija)" | Guarda/borra el token vía `TunnelManager.saveConfig("cloudflared", token, domain, "n8n")` (`N8nFragment.kt:488-491`) | Permite fijar un token real de Cloudflare Tunnel para tener una URL pública permanente en vez de la temporal. Usa `ProcessBuilder` con `TERMUX_BASH_PATH` (ruta absoluta): un `"bash"` relativo falla siempre en `ProcessBuilder` |
| "🌐 Configurar dominio webhook" | Fija `N8N_WEBHOOK_URL` en `~/.env_n8n` | El diálogo pide solo el dominio pelado y `writeWebhookDomain()` agrega `https://` si el usuario no puso un esquema — `N8N_WEBHOOK_URL` necesita la URL completa con esquema (`modulos/n8n.sh` línea ~256). Sin esto los webhooks usan el subdominio temporal `*.trycloudflare.com` |
| "🔄 Cambiar protocolo HTTP/HTTPS" | Escribe `~/.n8n_protocol` | Solo aplica al modo proot; escribirlo en udocker es inofensivo. `writeProtocol` pasa el valor como argumento posicional `$1`, no interpolado (`N8nFragment.kt:519`) |
| "🔧 Reparar scripts de control" | Corre `modulos/n8n.sh --repair-scripts --silent` | Regenera SOLO start/stop/log/status/update/backup (según la variante instalada), sin tocar el contenedor/rootfs ni los workflows/credenciales del usuario |
| "⏱ Ver ejecuciones recientes" | `GET /api/v1/executions` (`N8nApiClient.listExecutions()`) | Permite ver si la última corrida falló sin abrir el webview completo. `status` a veces falta en el listado (bug conocido upstream, `n8n-io/n8n#20706`) — se cae a `finished` como respaldo |

## 10. Archivos y rutas

`~/scripts/n8n/` (proot: `start_servidor.sh, stop_servidor.sh, ver_url.sh, n8n_status.sh, n8n_log.sh, n8n_update.sh, n8n_backup.sh, cf_token.sh`), `~/scripts/n8n-udocker/` (`start/stop/status/log/update/backup.sh`), `~/debian.sh`, `~/n8n-udocker/` (datos udocker), `/root/.n8n` dentro del proot, `~/.udocker/`, `~/.install_n8n_*checkpoint`, `~/.termux/boot/start_n8n[_udocker].sh`, `~/.last_cf_url`, `~/.cf_ud_url.log`, `~/.cf_token`, `~/.env_n8n`, `~/.n8n_local_only`, `~/.n8n_protocol`, `~/.n8n_api_key`, `~/.n8n_install_source`, `~/.udocker_force_p2`, aliases en `~/.bashrc`, backups en `/sdcard/Download/n8n_*.tar.gz`.

## 11. Seguridad

- `~/.n8n_api_key` y `~/.cf_token` se escriben sin `chmod 600` (`N8nApiClient.kt:44-49`, `TunnelManager.kt:535`, `n8n.sh:625`); dependen del aislamiento de `$HOME`.
- El token de Cloudflare viaja en la línea de comandos `cloudflared tunnel run --token …` dentro de `tmux send-keys` (`n8n.sh:343-345`, `:523`): visible en la lista de procesos y en el buffer de tmux.
- Los diálogos de API key y token no precargan el valor guardado; solo guardar/borrar.
- `NODE_FUNCTION_ALLOW_BUILTIN=child_process,…` y `NODE_FUNCTION_ALLOW_EXTERNAL=*` dejan a los Code nodes ejecutar procesos y cargar cualquier módulo. `N8N_SECURE_COOKIE=false` y `N8N_HOST=0.0.0.0` exponen n8n a la LAN sin TLS; con túnel, a Internet. Kairos no añade autenticación propia (n8n pide crear el owner en el primer acceso; sin verificar).
- `~/.env_n8n` se lee con `grep|cut` y se concatena sin comillas a `UDOCKER_ENV_ARGS` (`n8n.sh:285-286`); la UI quita el esquema del dominio pero no valida caracteres (`N8nFragment.kt:438`). Riesgo bajo (archivo del propio usuario), pero sin validación.

## 12. Limitaciones conocidas

- `update.sh` (udocker) saca la versión con `awk '{print $2}'` (`n8n.sh:413`), el parseo que ya se corrigió en la instalación (`:1020`, usa `$1` + `cut`); tras "Actualizar n8n" el registry puede quedar con una versión incorrecta.
- `catalogVersion` 3.0.0 en `modules.json` vs script v4.0.0.
- `describe-files`/moduledeb no cubre proot ni la imagen.
- `stop.sh` udocker mata todos los `cloudflared tunnel`.
- `~/.n8n_protocol` solo se escribe desde la UI; en `modulos/n8n.sh` no hay ninguna lectura del archivo, por lo que el botón "Cambiar protocolo" podría no tener efecto real (sin verificar en dispositivo).
- `n8nio/n8n:latest` ha estado roto en Docker Hub; el fallback a `:stable` lo mitiga (sin verificar).
