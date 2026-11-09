# udocker — Módulo de Kairos
## Documentación técnica completa

udocker es una herramienta que ejecuta imágenes Docker sin root. En Kairos es un módulo propio con pantalla dedicada, y además una pieza interna de `n8n` (variante udocker) y `entorno`.

**App:** Kairos (fork de termux-app) · ARM64 · Android

---

## 0. Estado actual del módulo

### Qué es y para qué sirve
Módulo de contenedores sin root "estilo Docker" sobre PRoot (udocker, execmode P2): bajar imágenes de Docker Hub (`pull`), crear contenedores, abrir shell, ejecutar comandos, instalar paquetes, inspeccionar, exportar/guardar/importar `.tar`. No es Docker real (sin namespaces/cgroups; ver módulo `docker`). Es el mismo motor que usan `modulos/n8n.sh` (variante udocker) y `modulos/entorno.sh` (`_install_udocker()`).

**Permisos**: ninguno especial de Android — corre 100% en espacio de usuario Termux vía PRoot, sin root, sin permisos adicionales al wizard genérico.

### Fuentes
`modulos/udocker.sh` (451 líneas, v1.0.0, copia idéntica en `app/src/main/assets/scripts/udocker.sh`), `app/src/main/assets/modules.json`, `UdockerFragment.kt` (860 líneas), `ModuleDetailNavigator.kt:40`, `EntornoNative.kt`, `ProcessBuilderExt.kt`.

### Entrada en `modules.json`
`id: "udocker"` — `script: "udocker.sh"`, `hasSwitch: false`, `terminalCommand: "udocker"`, `size: "~30MB"`, `estimate: "~2 min"`, `arch: "bionic"`, `requiresProot: false`, `requires: []`, `installMethods: ["pkg"]`, `port: ""`.

### Clasificación: MÓDULO FUNCIONAL
Fragment propio (`ModuleDetailNavigator.kt:40` → `UdockerFragment()`), con listas vivas de contenedores/imágenes (`EntornoNative.udockerContainersList()`, `udocker images`), diálogos de operación y descargas con progreso en segundo plano, no una simple instalación + terminal. Sin servicio/puerto (no corre daemon). **Opciones de UI aproximadas: ~20** (3 botones de acción + 6 acciones por contenedor + 4 por imagen + 3 distros + "otra imagen" + diálogo `-v`/`-e` + 2 gestores de paquetes + mantenimiento/diagnóstico/desinstalar).

### Uso desde la pantalla
En **Imágenes**, abrir una imagen y elegir **Crear un contenedor y usar esta imagen** inicia el flujo de nombre y crea el contenedor. Las acciones posteriores están en la fila del contenedor: abrir la shell interactiva, instalar paquetes (Alpine `apk`, Debian/Ubuntu `apt-get`), ejecutar un comando no interactivo, inspeccionar, exportar o borrar. La imagen descargada es una plantilla; no se ejecuta hasta crear un contenedor. El trabajo con `apk`/`apt` ocurre dentro de PRoot y puede estar limitado por las capacidades del contenedor; no se promete compatibilidad universal ni privilegios reales de Docker.

### Instalación (`bash udocker.sh --silent`)
Flags: `--silent --force --describe --describe-files` (`:56-62`). Dos pasos: `1/2` udocker + udockertools (`:231`), `2/2` wrappers (`:346`). Variables `UDOCKER_USE_PROOT_EXECUTABLE` y `UDOCKER_USE_CURL_EXECUTABLE` fijas (`:233,242`). Checkpoint `udocker_install`. Registry: `registry_install udocker <versión> execmode=P2 scripts=...` (`:434`); versión extraída con regex `N.N.N`.

- **Cómo se instala el binario:** `udocker` no existe como paquete apt de Termux (comentario `udocker.sh:252-255`). El script instala por **pip desde el fork `github.com/Honkonx/udocker`** con `--no-cache-dir --force-reinstall` (`udocker.sh:285-286`), con fallback a PyPI oficial (`:293`) que deja udocker sin el parche android→linux (`warn` en `:295`). Solo la cabecera del script (`:26`), `installMethods: ["pkg"]` de `modules.json` y el `install_hint` de `--describe-files` (`:108`, "pkg install -y udocker") siguen diciendo `pkg`.
- **Verificación del parche:** `udocker_fork_patch_active()` (`:157-164`) ejecuta `HostInfo().osversion() != "android"` con el Python de Termux; el marcador `~/.udocker_fork_installed` (`:288`) no basta. El corto-circuito "ya instalado" exige binario + `~/.udocker/lib/VERSION` + los 4 wrappers + parche activo (`:188-192`).
- **udockertools:** se descarga con `curl` de 2 mirrors fijos (`udocker-englib-1.2.11.tar.gz`, `:131-134`) a un temporal por PID y se instala con `UDOCKER_TARBALL=<ruta local> udocker install --force` (`:318-336`). Si ningún mirror funciona → `error`. (Los mirrors fijos evitan que el origen dinámico de `udocker install` falle en red móvil/CGNAT.)
- **execmode P2:** el script solo crea el marcador `~/.udocker_force_p2` (`:339`); la configuración real `udocker setup --execmode=P2 <contenedor>` la aplican `run.sh` (`:399`) y `UdockerFragment.installDistro()` (`:~653`). No hay un `setup` global en la instalación.
- **Post-condición:** no usa `verify_binary_installed`; la post-condición es `$HOME/.udocker/lib/VERSION` no vacío + `udocker_fork_patch_active`. Sin sha256 del tarball ni pin de commit del fork (pip instala el `HEAD` de `git+https://github.com/Honkonx/udocker.git`). El script no instala `python`, `pip` ni `proot`; asume que están en el bootstrap. `ModuleInstalled.LIVE_CHECK_DISABLED = setOf("udocker")` desactiva el live-check para este módulo.
- **Wrappers** en `~/scripts/udocker/`: `pull.sh`, `run.sh <nombre> <imagen> [-- cmd]`, `list.sh`, `rm.sh`.

### Ejecución
Sin start/stop ni sesión tmux propios. Las operaciones largas usan `ManagerNativeUtils.runExec(listOf(TERMUX_UDOCKER_PATH, ...), timeout)`: `pull` hasta 600 s, `create` 60 s, `setup` 20 s (`UdockerFragment.kt:620-660`). "Abrir terminal" lanza `udocker run --interactive --tty [flags] <nombre> bash || ... sh` en la terminal adaptada (`buildUdockerRunCommand()` `:479-483`, con `export UDOCKER_USE_PROOT_EXECUTABLE`). Comandos dentro del contenedor e instalación de paquetes: `runContainerCommand()` `:284` (con `--user=root --containerauth` cuando `asRoot`).

### Archivos y rutas
`$PREFIX/bin/udocker` (pip), `~/.udocker/{bin,lib,doc,containers,layers,repos}`, `~/.udocker_fork_installed`, `~/.udocker_force_p2`, `~/.udocker_tarball_tmp.<pid>.tar.gz` (temporal), `~/scripts/udocker/{pull,run,list,rm}.sh`, `~/.install_udocker_checkpoint`, `~/kairos_logs/install_udocker.log`, registry (`udocker.installed=true`, `execmode=P2`, ...).

### Seguridad
- Instalación remota sin integridad verificable: `pip install git+https://github.com/Honkonx/udocker.git` (sin hash ni commit fijo) y tarball de udockertools de `raw.githubusercontent.com/jorge-lip/udocker-builds` / `download.a.incd.pt`, sin sha256. No hay `curl | bash`.
- Ejecutar contenedores como root dentro de PRoot (`--user=root --containerauth`) es solo emulación de root en userspace, no aislamiento real; las imágenes de Docker Hub son código de terceros.
- Los montajes `-v` y variables `-e` pasan por `quoteShellArg()` (`:486`) al armar la línea de la terminal; `pull`/`create` reciben el nombre como argumento de lista (sin shell). Inferencia: `setup` se ejecuta con `bash -c` interpolando `$name` (`:653`); no se probó con nombres con caracteres especiales.
- Sin manejo de secretos propio (Docker Hub anónimo).

### Problemas / limitaciones
1. Metadata/documentación de instalación incoherente con el código (`pkg` vs pip del fork), ver "Instalación".
2. **`udocker ps -a`**: el Fragment (cabecera `:42`) y `EntornoNative.udockerContainers()/udockerContainersList()` llaman `udocker ps -a`, mientras `list.sh` (comentario `udocker.sh:410-415`) afirma que `-a` es un flag inválido ("syntax error at: -a"). Contradicción interna sin resolver; cuál es correcto no se verificó en dispositivo.
3. Sin sha256 / sin pin de versión para el fork y los tarballs.
4. `requires: []` pese a depender de python/pip3/proot.
5. `installDistro` hace `pull` sin `--platform`, por lo que depende por completo del parche del fork; con el fallback a PyPI oficial el `pull` falla con "no image found in manifest for platform (android/arm64)" (texto del propio `warn`, `udocker.sh:295`).
6. `rm.sh`/`pull.sh` no fijan P2 por sí mismos (solo `run.sh`).
7. Inferencia: que el rendimiento de P2 sea aceptable para cargas pesadas; no medido.

### Fecha de alta
`modulos/udocker.sh`: **2026-08-16**.

---

## Controles de la pantalla (`UdockerFragment.kt`)

Si no está instalado, `showNotInstalled()` muestra un botón de instalación (`:107-118`).

### Card "CONTENEDORES"

| Control | Qué hace | Por qué |
|---|---|---|
| Fila de contenedor (tap) | `promptContainerActions(name)` — menú: Abrir terminal / Instalar paquetes / Ejecutar comando / Inspeccionar / Exportar a .tar / Eliminar | udocker se usa como runtime de contenedores general, no solo como pieza interna de n8n |
| → "▶ Abrir terminal" | `promptRunOptions()` (montajes `-v`/variables `-e` opcionales) → `launchTerminalCommand("udocker run ...")` | Permite montar un directorio del host y pasar variables sin escribir el comando a mano |
| → "📦 Instalar paquetes" | Alpine: `apk`; Debian/Ubuntu: `apt-get -o APT::Sandbox::User=root update && install -y` | — |
| → "🔍 Inspeccionar" | `udocker inspect <contenedor>` — metadata JSON, solo lectura | — |
| → "📤 Exportar a .tar" | `udocker export -o <tmp> <contenedor>` → copia a un Uri elegido por Storage Access Framework | udocker escribe a un path real del filesystem, no sabe de `content://` Uris — se genera primero en un temporal del sandbox |
| → "🗑 Eliminar contenedor" | Confirmación → `udocker rm <contenedor>` | Borra el rootfs completo, irreversible — de ahí la confirmación explícita |
| "📥 Instalar distro" | `promptInstallDistro()` — grid de tiles (Alpine/Ubuntu/Debian `:latest`, imágenes reales de Docker Hub) + "Otra imagen…" (cualquier referencia de Docker Hub a mano) → nombre de contenedor → `udocker pull` + `create --name=` + `setup --execmode=P2` | `setup --execmode=P2` fuerza el modo PRoot puro, mismo criterio que `modulos/udocker.sh`. Da acceso genérico a CUALQUIER imagen de Docker Hub |
| "▶ Terminal en contenedor" | `promptTerminalInContainer()` — picker de contenedores + sesión interactiva | — |
| "📦 Importar imagen desde .tar" | Selector de archivos (SAF) → `promptImportImageName()` → `udocker import <tar> <repo/imagen:tag>` | — |

### Card "IMÁGENES DESCARGADAS"

| Control | Qué hace | Por qué |
|---|---|---|
| Fila de imagen (tap) | `promptImageActions(image)` — menú: Crear contenedor / Inspeccionar / Guardar a .tar / Eliminar | — |
| → "🔍 Inspeccionar" | `udocker inspect <imagen>` — solo lectura | Funciona igual sobre una referencia de imagen |
| → "💾 Guardar a .tar" | `udocker save -o <tmp> <imagen>` → copia a Uri elegido (SAF) | Formato compatible con Docker real |
| → "🗑 Eliminar" | Confirmación → `udocker rmi` | — |

Card MANTENIMIENTO (`addMaintenanceCard()`): actualizar / diagnosticar / desinstalar.

**Cobertura de comandos**: la pantalla cubre los 12 comandos del proyecto udocker (`pull/create/run/rm/ps/images/rmi/inspect/export/save/import/setup`), contrastados contra el manual oficial de `indigo-dc/udocker` (`user_manual.md`).

---

## 1. ¿Qué es udocker?

udocker es una herramienta en Python que permite ejecutar imágenes Docker sin root, sin kernel modificado y sin daemon. Funciona envolviendo el contenedor en un entorno tipo chroot usando PRoot — el mismo motor que usa proot-distro en Termux.

**No es Docker real.** Es un emulador de contenedores en espacio de usuario.

### Cómo funciona internamente

```
Termux (Android)
  └── udocker (Python)
        └── PRoot (ptrace syscall interception)
              └── Container rootfs en ~/.udocker/containers/<id>/ROOT/
                    └── n8n (Node.js process)
```

El contenedor **no tiene su propio namespace de red**. Corre directamente sobre la red del host (Termux/Android). Esto es la diferencia más importante respecto a Docker real.

### Modo soportado en Termux

Según la documentación oficial de udocker:

> *"udocker can be used with Termux on Android, the only mode currently supported is P using PRoot."*

Solo el modo **P (PRoot)** funciona en Android. Los modos F (Fakechroot), R (runc) y S (Singularity) requieren namespaces de kernel que Android no expone sin root.

---

## 2. Red y networking

### udocker en modo PRoot NO tiene aislamiento de red

A diferencia de Docker real, udocker con PRoot **comparte el stack de red del host**. Esto significa:

- El contenedor ve `localhost` exactamente como Termux lo ve
- `127.0.0.1` dentro del contenedor = `127.0.0.1` de Android
- No hay bridge network, no hay NAT, no hay namespace separado
- Los puertos que expone el contenedor se mapean directamente al host

**Consecuencia práctica:** todo servicio corriendo en Termux es accesible desde dentro del contenedor usando `localhost` o `127.0.0.1`.

### Tabla de acceso entre capas

| Desde \ Hacia | Termux/Android | udocker n8n | proot Debian | Ollama :11434 |
|---------------|---------------|-------------|--------------|---------------|
| **Termux** | — | `localhost:5678` ✅ | via proot-distro ✅ | `localhost:11434` ✅ |
| **udocker n8n** | `localhost:XXXX` ✅ | — | N/A | `localhost:11434` ✅ |
| **proot Debian** | `localhost:XXXX` ✅ | `localhost:5678` ✅ | — | `localhost:11434` ✅ |
| **Browser Android** | `localhost:5678` ✅ | — | — | — |
| **PC en WiFi** | `IP_TELEFONO:5678` ✅ | — | — | — |

---

## 3. Cloudflare tunnel + n8n udocker

### ¿Debo instalar cloudflared dentro de udocker o en Termux?

**Instalar en Termux nativo — NO dentro del contenedor.**

**Razón:** Como udocker comparte el stack de red del host, cloudflared corriendo en Termux puede hacer tunnel al puerto 5678 aunque n8n esté dentro de udocker. Para cloudflared, n8n en udocker es indistinguible de n8n en Termux nativo.

### Setup cloudflare + udocker

```bash
# En Termux (fuera del contenedor)

# Opción A: URL temporal (sin cuenta)
cloudflared tunnel --url http://localhost:5678

# Opción B: URL fija (con cuenta Cloudflare)
# cloudflare.com → Zero Trust → Tunnels → Create tunnel → copiar token
cloudflared tunnel --no-autoupdate run --token TU_TOKEN_AQUI
```

### En script tmux (inicio automático)

```bash
# start.sh de udocker — agregar ventana de cloudflared
tmux new-window -t "n8n-udocker" -n "tunnel"
tmux send-keys -t "n8n-udocker:tunnel" \
  "cloudflared tunnel --no-autoupdate --url http://localhost:5678" Enter
```

### Instalar cloudflared en Termux si no está

```bash
pkg install cloudflared
# o descarga manual ARM64:
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64 \
  -O ~/cloudflared
chmod +x ~/cloudflared
```

---

## 4. Comunicación n8n (udocker) ↔ servicios de Termux

### 4.1 Ollama — acceso directo

n8n dentro de udocker puede llamar a Ollama (corriendo en Termux) usando `localhost:11434` sin configuración adicional.

**En un workflow de n8n:**
- Nodo: HTTP Request o LangChain Ollama
- URL: `http://localhost:11434/api/generate`
- Body: `{"model": "llama3.2", "prompt": "Tu pregunta"}`

**Importante:** Ollama debe escuchar en `0.0.0.0`, no solo en `127.0.0.1`.

```bash
# En Termux — verificar que Ollama escucha en todas las interfaces
export OLLAMA_HOST=0.0.0.0
ollama serve
```

### 4.2 Python — tres formas

**Forma 1: HTTP API (recomendada para workflows n8n)**

```bash
# En Termux — crear un mini servidor Python para exponer funcionalidad
cat > ~/python_api.py << 'EOF'
from http.server import HTTPServer, BaseHTTPRequestHandler
import json, sqlite3, urllib.parse

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers['Content-Length'])
        body = json.loads(self.rfile.read(length))
        
        # Lógica Python aquí — SQLite, pandas, scripts, etc.
        resultado = {"status": "ok", "data": procesar(body)}
        
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(resultado).encode())
    
    def log_message(self, *args): pass  # silenciar logs

def procesar(data):
    # Tu lógica Python aquí
    return data

HTTPServer(('0.0.0.0', 8765), Handler).serve_forever()
EOF

python3 ~/python_api.py &
```

En n8n (udocker): HTTP Request a `http://localhost:8765`

**Forma 2: Volumen compartido (archivos)**

```bash
# Directorio compartido accesible desde udocker Y desde Termux
mkdir -p ~/shared

# udocker run con volumen montado
udocker run \
  --volume=/data/data/com.termux/files/home/shared:/shared \
  n8n

# n8n escribe resultado en /shared/output.json
# Python en Termux lee ~/shared/output.json
```

**Forma 3: Ejecutar Python directamente desde n8n**

En n8n, nodo "Execute Command":
```bash
# No funciona directamente desde udocker — Python no está dentro del contenedor
# Usar HTTP API (Forma 1) en su lugar
```

### 4.3 SQLite — acceso directo via volumen

SQLite es un archivo. Compartir el archivo entre udocker y Termux vía `--volume`.

```bash
# Ruta de tu DB en Termux
DB_PATH="/data/data/com.termux/files/home/data/stack.db"
mkdir -p "$(dirname $DB_PATH)"

# udocker con acceso al directorio de la DB
udocker run \
  --publish=5678:5678 \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  --volume=/data/data/com.termux/files/home/data:/data \
  n8n
```

Dentro de n8n: nodo SQLite apunta a `/data/stack.db`

**Regla crítica (Android 15):** NUNCA usar `/tmp/` — es noexec en Android 15. Usar siempre rutas bajo `$HOME` (`/data/data/com.termux/files/home/`).

---

## 5. Limitaciones de udocker en Termux/Android

### Limitaciones heredadas de PRoot

| Limitación | Impacto en el stack |
|-----------|---------------------|
| Sin puertos < 1024 | n8n usa 5678 ✅ sin problema |
| Sin `su` ni cambio de UID real | n8n corre como usuario normal — OK |
| Sin mount de filesystems | Usar `--volume` en su lugar |
| Sin modificar routing/firewall | No aplica para uso normal |
| Sin `docker-compose` | Contenedores independientes únicamente |
| Sin red entre contenedores | Usar `localhost` para comunicación |
| Sin `docker exec` en proceso vivo | Solo acceso via volúmenes o HTTP |

### Limitaciones específicas en Android 15

- **`/tmp/` es noexec:** Nunca escribir scripts ahí. Usar `$HOME/`
- **Puertos < 1024:** Android remapea automáticamente (ej: :80 → :2080)
- **`read` sin `/dev/tty`:** En scripts bash dentro de Termux siempre usar `read < /dev/tty`
- **`$HOME` en `--volume`:** udocker no expande `$HOME`. Usar ruta absoluta: `/data/data/com.termux/files/home/`

### Lo que udocker NO puede hacer (en Android sin root)

- Docker Compose (requiere daemon)
- Red entre contenedores (bridge/overlay networking)
- Docker volumes gestionados (usar --volume manual)
- Privileged containers
- GPU passthrough
- `docker exec` en contenedor vivo

---

## 6. ¿udocker o proot Debian para n8n?

### Comparativa directa

| Factor | n8n en proot Debian | n8n en udocker |
|--------|--------------------|----|
| Instalación | ~40 min (npm install) | ~15 min (pull imagen) |
| Espacio | ~800MB (node_modules) | ~900MB (imagen completa) |
| Versión n8n | Fijada en instalación | Siempre `latest` con pull |
| Actualización | `npm update -g n8n` (lento) | `udocker pull` + recrear (rápido) |
| Datos persistentes | `/root/.n8n` en rootfs | `~/n8n-udocker/` en Termux home |
| RAM overhead | ~150MB (Debian proot) | ~80MB (solo el contenedor) |
| Cloudflared | Instalado dentro del proot | En Termux nativo (más simple) |
| Networking con stack | Via proot-distro login | Directo — mismo localhost |
| Ollama access | `localhost:11434` ✅ | `localhost:11434` ✅ |
| SQLite access | Dentro del proot | Via --volume a ~/data/ |

### Recomendación

- **udocker** → instalación más simple, imagen oficial siempre actualizada, menos overhead
- **proot Debian** → si necesitas personalización profunda, cloudflared integrado, o el stack ya está ahí

---

## 7. Ejemplo completo: workflow n8n → Ollama → Python → SQLite

### Arquitectura del flujo

```
n8n (udocker :5678)
    │
    ├── HTTP Request → Ollama (localhost:11434) [LLM inference]
    │
    ├── HTTP Request → Python API (localhost:8765) [procesamiento]
    │        │
    │        └── SQLite (~/data/stack.db) [persistencia]
    │
    └── SQLite node → /data/stack.db [lectura/escritura directa]
```

### 1. Iniciar Ollama (Termux)

```bash
OLLAMA_HOST=0.0.0.0 ollama serve &
ollama pull llama3.2:1b
```

### 2. Iniciar Python API (Termux)

```bash
cat > ~/python_api.py << 'EOF'
from http.server import HTTPServer, BaseHTTPRequestHandler
import json, sqlite3, datetime

DB = "/data/data/com.termux/files/home/data/stack.db"

def init_db():
    con = sqlite3.connect(DB)
    con.execute("""
        CREATE TABLE IF NOT EXISTS eventos (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tipo TEXT,
            payload TEXT,
            ts TEXT
        )
    """)
    con.commit()
    con.close()

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length)) if length else {}
        
        # Usar datetime.now() — NUNCA datetime('now') de SQLite
        ts = datetime.datetime.now().isoformat()
        
        con = sqlite3.connect(DB)
        con.execute(
            "INSERT INTO eventos (tipo, payload, ts) VALUES (?, ?, ?)",
            (body.get('tipo', 'general'), json.dumps(body), ts)
        )
        con.commit()
        rows = con.execute("SELECT COUNT(*) FROM eventos").fetchone()[0]
        con.close()
        
        resp = {"ok": True, "total_eventos": rows, "ts": ts}
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(resp).encode())
    
    def log_message(self, *args): pass

init_db()
print("Python API corriendo en :8765")
HTTPServer(('0.0.0.0', 8765), Handler).serve_forever()
EOF

mkdir -p ~/data
python3 ~/python_api.py &
```

### 3. Iniciar n8n (udocker)

```bash
mkdir -p ~/n8n-udocker
chmod 777 ~/n8n-udocker

udocker run \
  --publish=5678:5678 \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  --volume=/data/data/com.termux/files/home/data:/data \
  --env=N8N_HOST=0.0.0.0 \
  --env=N8N_PORT=5678 \
  --env=N8N_SECURE_COOKIE=false \
  --env=NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os \
  --env=NODE_FUNCTION_ALLOW_EXTERNAL=* \
  n8n
```

### 4. Workflow en n8n

**Nodo 1 — HTTP Request (Ollama):**
```
Method: POST
URL: http://localhost:11434/api/generate
Body: {
  "model": "llama3.2:1b",
  "prompt": "{{ $json.pregunta }}",
  "stream": false
}
```

**Nodo 2 — HTTP Request (Python API):**
```
Method: POST  
URL: http://localhost:8765
Body: {
  "tipo": "consulta_llm",
  "pregunta": "{{ $json.pregunta }}",
  "respuesta": "{{ $json.response }}"
}
```

**Nodo 3 — SQLite (lectura directa):**
```
Database: /data/stack.db
Query: SELECT * FROM eventos ORDER BY ts DESC LIMIT 10
```

---

## 8. Comandos de referencia rápida

### Instalación y setup

```bash
# El módulo udocker se instala desde la pestaña Módulos de Kairos
# (instala el binario por pip desde el fork, más udockertools, y fija execmode P2)

# Usar proot de Termux (recomendado para Android)
export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)
```

### Gestión de imágenes y contenedores

```bash
# Descargar imagen
udocker pull n8nio/n8n

# Crear contenedor con nombre
udocker create --name=n8n n8nio/n8n

# Listar contenedores
udocker ps

# Listar imágenes
udocker images

# Eliminar contenedor
udocker rm n8n

# Ruta del contenedor en disco
udocker inspect -p n8n
# → /data/data/com.termux/files/home/.udocker/containers/<id>/ROOT
```

### Ejecutar contenedores

```bash
# n8n con volumen y variables de entorno
udocker run \
  --publish=5678:5678 \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  --env=N8N_SECURE_COOKIE=false \
  n8n

# Shell interactivo dentro del contenedor
udocker run --user=root n8n /bin/bash

# Ejecutar comando único
udocker run n8n n8n --version

# Modo debug
udocker -D run n8n
```

### Actualizar n8n

```bash
# 1. Detener contenedor actual (Ctrl+C o kill tmux)
tmux kill-session -t n8n-udocker

# 2. Descargar nueva imagen
udocker pull n8nio/n8n

# 3. Eliminar contenedor viejo
udocker rm n8n

# 4. Recrear con nueva imagen
udocker create --name=n8n n8nio/n8n

# 5. Iniciar
bash ~/scripts/n8n-udocker/start.sh
```

### Backup y restore

```bash
# Backup de datos n8n (workflows, credenciales, etc.)
FECHA=$(date +%Y%m%d_%H%M)
tar -czf "/sdcard/Download/n8n_udocker_${FECHA}.tar.gz" \
  -C /data/data/com.termux/files/home/n8n-udocker .

# Restore
mkdir -p ~/n8n-udocker
tar -xzf /sdcard/Download/n8n_udocker_YYYYMMDD_HHMM.tar.gz \
  -C ~/n8n-udocker

# Backup completo incluyendo imagen (para migración)
# Los contenedores están en ~/.udocker/containers/
tar -czf /sdcard/Download/udocker_full_backup.tar.gz \
  -C ~ .udocker/
```

### Cambiar modo de ejecución

```bash
# Ver modo actual
udocker setup n8n

# Cambiar a P2 (más lento pero más compatible)
udocker setup --execmode=P2 n8n

# Volver a P1 (default, más rápido)
udocker setup --execmode=P1 n8n
```

---

## 9. Troubleshooting

### Error: "invalid host volume path"

```bash
# ❌ Causa: udocker no expande $HOME
udocker run --volume=$HOME/data:/data n8n

# ✅ Fix: ruta absoluta siempre
udocker run \
  --volume=/data/data/com.termux/files/home/data:/data \
  n8n
```

### Error: "this container exposes privileged TCP/IP ports"

```bash
# Advertencia informativa — no es error fatal
# Puertos < 1024 se remapean automáticamente
# :80 → :2080, :443 → :2443
# n8n usa :5678 — no afecta
```

### n8n arranca pero no se puede conectar

```bash
# Verificar que n8n escucha en todas las interfaces
udocker run --env=N8N_HOST=0.0.0.0 n8n

# Verificar puerto
ss -tlnp | grep 5678

# Test desde Termux
curl http://localhost:5678
```

### Ollama no responde desde n8n

```bash
# Ollama debe escuchar en 0.0.0.0, no en 127.0.0.1
OLLAMA_HOST=0.0.0.0 ollama serve

# Verificar
curl http://localhost:11434/api/tags
```

### Python API no responde desde n8n

```bash
# Verificar que Python API escucha en 0.0.0.0
# (en el script: HTTPServer(('0.0.0.0', 8765), Handler))

# Verificar que corre
ss -tlnp | grep 8765

# Test
curl -X POST http://localhost:8765 \
  -H "Content-Type: application/json" \
  -d '{"tipo":"test"}'
```

### Contenedor no arranca (P1 fails)

```bash
# Intentar con P2
udocker setup --execmode=P2 n8n
udocker run ... n8n

# O usar proot de Termux explícitamente
export UDOCKER_USE_PROOT_EXECUTABLE=$(which proot)
udocker run ... n8n
```

### Datos n8n no persisten

```bash
# Verificar que ~/n8n-udocker tiene permisos correctos
ls -la ~/n8n-udocker
chmod 777 ~/n8n-udocker

# Verificar que el --volume usa ruta absoluta
udocker run \
  --volume=/data/data/com.termux/files/home/n8n-udocker:/home/node/.n8n \
  n8n
```

---

## 10. Estructura de archivos en el stack

```
$HOME/  (/data/data/com.termux/files/home/)
├── .udocker/
│   ├── bin/                    # Binarios de udocker (proot, etc.)
│   ├── containers/
│   │   └── <uuid>/
│   │       ├── ROOT/           # Rootfs del contenedor (n8n instalado aquí)
│   │       └── container.json  # Metadata
│   └── repos/                  # Imágenes descargadas (capas)
│
├── n8n-udocker/                # Datos persistentes de n8n
│   ├── config.json             # Configuración n8n
│   ├── database.sqlite         # DB interna de n8n
│   └── .n8n/                   # Workflows, credenciales
│
├── data/
│   └── stack.db                # SQLite compartida con Python y n8n
│
├── scripts/
│   ├── n8n/                    # Scripts para n8n proot
│   └── n8n-udocker/            # Scripts para n8n udocker
│       ├── start.sh
│       ├── stop.sh
│       ├── status.sh
│       ├── log.sh
│       ├── update.sh
│       └── backup.sh
│
└── python_api.py               # API Python local (puerto 8765)
```

---

## 11. Variables de entorno relevantes para n8n en udocker

```bash
# Variables que van en --env al correr udocker run

N8N_HOST=0.0.0.0                    # Escuchar en todas las interfaces
N8N_PORT=5678                        # Puerto
N8N_SECURE_COOKIE=false              # Necesario en HTTP sin HTTPS
N8N_RUNNERS_ENABLED=true             # Habilitar task runners
N8N_RUNNERS_HEARTBEAT_INTERVAL=300   # Timeout runners
NODE_FUNCTION_ALLOW_BUILTIN=child_process,fs,path,os  # Módulos Node permitidos
NODE_FUNCTION_ALLOW_EXTERNAL=*       # Módulos externos
WEBHOOK_URL=https://tu-dominio.com   # Si usas cloudflared con dominio fijo
N8N_BASIC_AUTH_ACTIVE=false          # Sin auth básica (usa cuenta n8n)
```

---

## 12. Resumen de decisiones de arquitectura

| Componente | Dónde corre | Razón |
|-----------|-------------|-------|
| **Ollama** | Termux nativo | Necesita hardware nativo ARM64, sin overhead |
| **Python 3.13** | Termux nativo | Acceso directo a SQLite y filesystem |
| **SQLite** | Termux nativo | Archivo compartido via --volume |
| **Claude Code** | Termux nativo | CLI que necesita acceso al sistema |
| **n8n** | udocker | Imagen oficial, fácil actualización, menos RAM |
| **cloudflared** | Termux nativo | Comparte red con udocker — no necesita estar dentro |
| **Redis** | udocker (opcional) | Imagen Alpine pequeña, para n8n queue mode |
| **PostgreSQL** | udocker (opcional) | Alternativa a SQLite para n8n si crece |
