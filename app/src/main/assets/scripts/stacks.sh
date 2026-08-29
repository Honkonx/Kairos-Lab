#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · stacks.sh (silent mode)
#  Módulo Entornos de Prueba: catálogo de recetas de desarrollo
#  (pedido explícito del usuario, ver docs/humano/humano116.md)
#
#  A diferencia del resto de modulos/*.sh, este NO instala un paquete
#  propio — es un catálogo que reutiliza módulos ya existentes
#  (python.sh instala Python nativo por su cuenta, db.sh instala
#  MySQL/MariaDB + PostgreSQL) y agrega Node.js/PHP directo vía pkg,
#  que no tienen módulo dedicado propio en modulos/ (confirmado con
#  `grep -l "node\|php" modulos/*.sh` antes de escribir esto).
#
#  USO DESDE APP (KairosApp):
#    bash stacks.sh --silent                                   (catálogo, no-op real)
#    bash stacks.sh --preset python-postgres --silent           (nativo Termux)
#    bash stacks.sh --preset php-mysql --distro debian --silent (dentro de una distro)
#    bash stacks.sh --preset react-vite --silent
#    bash stacks.sh --preset html --silent                      (servidor estático liviano)
#    bash stacks.sh --preset linux-completo --silent            (distro Linux completa, proot)
#    bash stacks.sh --preset linux-completo --flavor ubuntu --silent
#
#  FLAGS:
#    --silent          Sin preguntas, instala todo directo
#    --force           Reinstala/actualiza aunque las piezas ya estén
#    --describe        Describe lo que hace el script
#    --preset <id>     python-postgres | php-mysql | react-vite | html | go | rust | java |
#                       dotnet | linux-completo
#    --distro <nombre> Solo aplica a los presets livianos (python-postgres, php-mysql,
#                       react-vite, html). Si se pasa, instala DENTRO de esa distro proot
#                       (via "proot-distro login <nombre> -- apt-get install")
#                       en vez de nativo (pkg install). La distro debe
#                       existir ya (instalada desde el módulo Entorno o desde
#                       el propio preset linux-completo de este script).
#    --flavor <nombre> Solo aplica a --preset linux-completo: qué distro instalar
#                       (debian [default] | ubuntu). No confundir con --distro: ese
#                       flag apunta a una distro YA instalada para los presets
#                       livianos; --flavor es la distro que ESTE preset instala.
#
#  PRESETS Y PAQUETES REALES:
#    python-postgres  nativo: pkg python (+ módulo db.sh encadenado)
#                     distro: python3 python3-pip postgresql
#    php-mysql        nativo: pkg php (+ módulo db.sh encadenado)
#                     distro: php php-mysql mysql-server
#    react-vite       nativo: pkg nodejs (trae npm — cubre React/Vite/TypeScript/JavaScript)
#                     distro: nodejs npm
#    html             nativo: pkg python (sin build, `python3 -m http.server`)
#                     distro: python3
#                     (pedido explícito, ver docs/humano/humano118.md: "python,php,reac,
#                     vite,html,tyscript,javascript" — TS/JS ya cubiertos por react-vite,
#                     este preset cierra el caso "solo HTML/CSS/JS vanilla sin Node")
#    linux-completo   SIEMPRE vía proot-distro (nunca nativo) — instala una distro Linux
#                     real completa (Debian Bookworm por defecto, o Ubuntu con --flavor
#                     ubuntu), a diferencia de los 4 presets de arriba que son livianos
#                     ("como una VPS chica", pedido explícito del usuario) y corren
#                     nativos en Termux siempre que se pueda. Reutiliza el mismo patrón
#                     ya probado de "proot-distro install <nombre>" que usan
#                     modulos/entorno.sh (_install_proot_distro) y modulos/n8n.sh
#                     (variante proot, PASO 2) — no reinventa la lógica.
#
#  DECISIÓN DE SCOPE (base de datos, presets con BD):
#    Se encadena "bash db.sh --silent" en vez de solo avisarle al
#    usuario que la instale antes — db.sh ya es idempotente (chequea
#    binarios antes de instalar) y el pedido original es "un stack
#    completo con un par de toques", así que encadenar es lo que
#    entrega ese MVP real en una sola acción. Si db.sh no está
#    disponible junto a este script, se avisa y se continúa sin
#    frenar el resto del preset (la pieza de lenguaje sigue siendo
#    útil aunque la BD no haya quedado instalada).
#
#  OUTPUT (modo --silent):
#    [STEP] mensaje       ← paso en curso
#    [OK] mensaje         ← paso completado
#    [WARN] mensaje       ← advertencia, continúa
#    [ERROR] mensaje      ← fallo (exit 1)
#    [RESUMEN] mensaje    ← línea del resumen final (comandos reales
#                            para arrancar cada pieza) — la UI filtra
#                            estas líneas para mostrar el resultado.
#
#  MODO PROYECTO REAL (--project-path, agregado en v1.2.0, ver
#  docs/humano/ del pedido "tengo un proyecto que usa python y sqlite en
#  backend y react+vite o html/javascript en frontend"):
#    bash stacks.sh --project-path <carpeta> --project-action detect --silent
#    bash stacks.sh --project-path <carpeta> --project-action install [--project-target native|distro|udocker] [--project-distro <nombre>] --silent
#    bash stacks.sh --project-path <carpeta> --project-action start --project-cmd "<comando>" [--project-target native|distro|udocker] [--project-distro <nombre>] --silent
#    bash stacks.sh --project-path <carpeta> --project-action stop --silent
#    bash stacks.sh --project-path <carpeta> --project-action status --silent
#    bash stacks.sh --project-path <carpeta> --project-action logs --silent
#  A diferencia de los presets de arriba (recetas fijas que instalan un
#  paquete conocido), este modo opera sobre una carpeta de proyecto REAL ya
#  elegida por el usuario (navegador de carpetas de la app, ProjectActions.kt)
#  — detecta node/python/sqlite/html/php por archivos presentes, instala
#  dependencias reales (npm install / venv+pip) y arranca/para el proceso en
#  tmux, con log persistente en ~/kairos_logs/. target=distro (automatizado
#  de verdad desde v1.4.0) copia el proyecto dentro del rootfs de la distro
#  (proot-distro), instala las dependencias de sistema que falten (nodejs/npm,
#  python3/pip3, php) y corre npm install / pip3 install -r requirements.txt
#  DENTRO de la distro (proot-distro login <distro> -- ...) — mismo nivel que
#  native/udocker, con la salvedad honesta de que apt-get/npm/pip sobre proot
#  son más lentos — ver cabecera de project_install_distro() más abajo.
#  target=udocker (agregado v1.3.0) corre el proyecto DENTRO de un contenedor
#  udocker (imagen oficial de Docker Hub según el stack detectado — node:20/
#  python:3.12/php:8.3-cli) vía bind-mount real (--volume, sin copiar nada) —
#  ver cabecera de project_install_udocker() más abajo, y modulos/docker.sh
#  para por qué udocker es la única vía real de "contenedores" en este entorno
#  (Docker real no es posible sin root en Android).
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 1.4.0 | Agosto 2026 (automatiza target=distro del modo
#  --project-path de verdad: instala dependencias de sistema + del proyecto
#  DENTRO de la distro vía proot-distro login, en vez de solo copiar y sugerir
#  comandos — mismo nivel que native/udocker)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ───────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
PRESET=""
DISTRO=""
FLAVOR="debian"
EXTRA=""
PROJECT_PATH=""
PROJECT_ACTION=""
PROJECT_TARGET="native"
PROJECT_DISTRO=""
PROJECT_CMD=""
while [ $# -gt 0 ]; do
  case "$1" in
    --silent)          SILENT=true; shift ;;
    --force)           FORCE=true; shift ;;
    --describe)        DESCRIBE=true; shift ;;
    --describe-files)  DESCRIBE_FILES=true; shift ;;
    --preset)          PRESET="$2"; shift 2 ;;
    --distro)          DISTRO="$2"; shift 2 ;;
    --flavor)          FLAVOR="$2"; shift 2 ;;
    --extra)           EXTRA="$2"; shift 2 ;;
    --project-path)    PROJECT_PATH="$2"; shift 2 ;;
    --project-action)  PROJECT_ACTION="$2"; shift 2 ;;
    --project-target)  PROJECT_TARGET="$2"; shift 2 ;;
    --project-distro)  PROJECT_DISTRO="$2"; shift 2 ;;
    --project-cmd)     PROJECT_CMD="$2"; shift 2 ;;
    *)                 shift ;;
  esac
done

# ── Manifiesto declarativo (--describe) ─────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"stacks","supports_silent":true,"supports_force":true,"variants":["python-postgres","php-mysql","react-vite","html","go","rust","java","dotnet","linux-completo"],"variant_required":false,"note":"catalogo de recetas de desarrollo — usar --preset <id> [--distro <nombre>] para instalar liviano (nativo o dentro de una distro ya instalada; dotnet SIEMPRE requiere --distro, no tiene paquete nativo de Termux); --preset linux-completo [--flavor debian|ubuntu] instala una distro Linux real vía proot-distro; sin --preset solo registra el catalogo como disponible. --extra <lista,separada,por,comas> agrega frameworks/librerías puntuales encima del runtime del preset (python-postgres: fastapi,django,flask,uvicorn,sqlalchemy,requests,numpy,pandas vía pip; react-vite: express,next,vue,axios vía npm; php-mysql: laravel vía composer, requiere --distro). Modo alternativo --project-path <carpeta> --project-action detect|install|start|stop|status|logs [--project-target native|distro|udocker] [--project-distro <nombre>] [--project-cmd <comando>] opera sobre un proyecto real ya elegido por el usuario en vez de un preset fijo — target=udocker corre el proyecto dentro de un contenedor udocker vía bind-mount (--volume), sin copiar nada"}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. stacks.sh NO instala un paquete
# propio (ver cabecera del script) — es un catálogo/orquestador que reutiliza
# módulos ya existentes (python.sh, db.sh, pkg nodejs/php directo) y opera
# sobre proyectos reales del usuario (--project-path). files:[] deliberado —
# no hay un artefacto propio de "stacks" para empaquetar, cada preset ya se
# empaqueta (o no) vía su propio módulo real.
if $DESCRIBE_FILES; then
  jq -n '{
    id: "stacks", supports_describe_files: true, variant: null,
    package_name: "kairos-module-stacks",
    version_registry_key: "stacks.version",
    files: [], file_globs: [], dependencies: [],
    verify_cmd: "true",
    patch_cmd: "",
    not_covered: ["stacks.sh es un catálogo/orquestador — no instala un paquete propio, encadena módulos ya existentes (python.sh, db.sh, pkg nodejs/php/etc.) según el --preset elegido, o opera sobre un proyecto real del usuario (--project-path). No hay un artefacto propio para empaquetar; cada pieza real se describe en su propio módulo"]
  }'
  exit 0
fi

# ── Archivos de estado ───────────────────────────────────────
REGISTRY="$HOME/.android_server_registry"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── log/warn/error/info/step + registry_write/pkg_update_with_fallback compartidos ──
source "$SCRIPT_DIR/lib.sh" 2>/dev/null || {
  echo "Error: lib.sh no encontrado"
  exit 1
}

# ============================================================
#  Modo proyecto real (--project-path) — atajo temprano, corre ANTES del
#  flujo de presets fijos de abajo y sale (case totalmente distinto, no
#  comparte --preset/--distro/--flavor).
# ============================================================
_project_hash() { printf '%s' "$1" | md5sum | cut -c1-10; }
_project_session() { echo "proj-$(_project_hash "$1")"; }
_PROJECT_LOG_DIR="$HOME/kairos_logs"
_project_run_log() { echo "$_PROJECT_LOG_DIR/stacks_project_$(_project_hash "$1").log"; }
_project_install_log() { echo "$_PROJECT_LOG_DIR/stacks_project_$(_project_hash "$1")_install.log"; }

# Detector simple basado en archivos presentes (no exhaustivo, pedido
# explícito: "un detector simple basado en archivos presentes"). Devuelve
# etiquetas separadas por espacio: node, python, sqlite, html, php.
#
# Bug real reportado 2026-08-20 (mismo fix espejado en detectProjectStack() de
# StacksProjectFragment.kt — ver el comentario largo ahí para el detalle): php
# dependía ÚNICAMENTE de composer.json, mientras que python ya tenía una señal
# más amplia (requirements.txt O cualquier *.py suelto) — un proyecto PHP sin
# Composer no emitía "php" en absoluto, y si además tenía un *.py utilitario
# suelto el resultado terminaba siendo solo "python". Agregado *.php suelto
# como señal adicional, simétrica con *.py.
project_detect() {
  local p="$1"
  local tags=()
  [ -f "$p/package.json" ] && tags+=(node)
  { [ -f "$p/requirements.txt" ] || ls "$p"/*.py >/dev/null 2>&1; } && tags+=(python)
  ls "$p"/*.db "$p"/*.sqlite "$p"/*.sqlite3 >/dev/null 2>&1 && tags+=(sqlite)
  { [ -f "$p/composer.json" ] || ls "$p"/*.php >/dev/null 2>&1; } && tags+=(php)
  [ -f "$p/index.html" ] && [ ! -f "$p/package.json" ] && tags+=(html)
  if [ ${#tags[@]} -eq 0 ]; then
    echo "none"
  else
    echo "${tags[*]}"
  fi
}

# Instalación nativa REAL (camino completo/prioritario, ver cabecera del
# pedido del usuario: "quiero que se instale node_modules, venv, etc.") —
# npm install si hay package.json, venv + pip install -r requirements.txt si
# hay Python. Log persistente en ~/kairos_logs/ (mismo patrón que
# ModuleController.installModule() del lado Kotlin).
project_install_native() {
  local p="$PROJECT_PATH"
  mkdir -p "$_PROJECT_LOG_DIR"
  local plog; plog="$(_project_install_log "$p")"
  : > "$plog"
  step "Instalando dependencias de $p (nativo Termux)" | tee -a "$plog"
  if [ -f "$p/package.json" ]; then
    if ! command -v npm &>/dev/null; then
      step "Instalando Node.js" | tee -a "$plog"
      pkg_update_with_fallback
      pkg install -y nodejs >>"$plog" 2>&1 || error "No se pudo instalar nodejs"
    fi
    step "npm install" | tee -a "$plog"
    (cd "$p" && npm install) >>"$plog" 2>&1 || warn "npm install terminó con errores — revisá $plog"
  fi
  if [ -f "$p/requirements.txt" ] || ls "$p"/*.py >/dev/null 2>&1; then
    if ! command -v python3 &>/dev/null; then
      step "Instalando Python" | tee -a "$plog"
      pkg_update_with_fallback
      pkg install -y python >>"$plog" 2>&1 || error "No se pudo instalar python"
    fi
    step "Creando entorno virtual (venv)" | tee -a "$plog"
    (cd "$p" && [ -d venv ] || python3 -m venv venv) >>"$plog" 2>&1
    if [ -f "$p/requirements.txt" ]; then
      step "pip install -r requirements.txt" | tee -a "$plog"
      (cd "$p" && ./venv/bin/pip install -r requirements.txt) >>"$plog" 2>&1 \
        || warn "pip install terminó con errores — revisá $plog"
    fi
  fi
  if [ -f "$p/composer.json" ]; then
    warn "composer.json detectado — PHP/Composer no se automatiza en este MVP (instalá con 'pkg install php composer' manualmente)" | tee -a "$plog"
  fi
  log "Instalación de dependencias completa — log: $plog"
}

# Instalación REAL dentro de la distro (ya no es un MVP de "copiar y sugerir"
# — ver docs/humano/ de este pedido: "automatizalo de verdad, al mismo nivel
# que nativo/udocker"). Copia el proyecto dentro del rootfs real de la distro
# ($TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs/<distro>, misma ruta
# que documenta entorno.sh) y después instala las dependencias del sistema que
# falten (nodejs/npm, python3/pip3, php) + las del proyecto (npm install /
# pip3 install -r requirements.txt) DENTRO de la distro, vía
# "proot-distro login <distro> -- ..." — mismo patrón ya probado en
# install_distro_preset() (más abajo en este archivo) y en distroAppInstall()
# de EntornoNative.kt. Nota honesta de rendimiento (pedido explícito): apt-get
# y npm/pip corriendo sobre proot son más lentos que nativo o udocker — no se
# promete la misma velocidad, solo se automatiza el flujo completo igual.
project_install_distro() {
  local p="$PROJECT_PATH" distro="$PROJECT_DISTRO"
  [ -n "$distro" ] || error "Falta --project-distro"
  command -v proot-distro &>/dev/null || error "proot-distro no está instalado — instalá el módulo Entorno primero"
  local rootfs="$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs/$distro"
  [ -d "$rootfs" ] || error "La distro '$distro' no está instalada"
  mkdir -p "$_PROJECT_LOG_DIR"
  local plog; plog="$(_project_install_log "$p")"
  : > "$plog"
  local name; name="$(basename "$p")"

  step "Copiando $p dentro de la distro '$distro' (/root/$name)" | tee -a "$plog"
  mkdir -p "$rootfs/root"
  rm -rf "${rootfs:?}/root/$name"
  cp -r "$p" "$rootfs/root/$name" || error "No se pudo copiar el proyecto dentro de '$distro'"

  local tags; tags="$(project_detect "$p")"
  warn "Instalar dentro de una distro proot puede tardar bastante más que nativo/udocker (apt-get + npm/pip corriendo sobre proot) — no se garantiza la misma velocidad" | tee -a "$plog"

  step "Actualizando índice de paquetes dentro de '$distro'" | tee -a "$plog"
  proot-distro login "$distro" -- env DEBIAN_FRONTEND=noninteractive apt-get update -y >>"$plog" 2>&1 \
    || warn "apt-get update dentro de '$distro' tuvo advertencias — sigo con la instalación"

  case " $tags " in
    *" node "*)
      if ! proot-distro login "$distro" -- command -v npm &>/dev/null; then
        step "Instalando nodejs npm dentro de '$distro'" | tee -a "$plog"
        proot-distro login "$distro" -- env DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs npm >>"$plog" 2>&1 \
          || error "No se pudo instalar nodejs/npm dentro de '$distro'"
      fi
      step "npm install (dentro de '$distro')" | tee -a "$plog"
      # $name (basename del proyecto elegido en el navegador de carpetas de la app)
      # puede traer espacios — sin comillas acá, la bash -c ANIDADA lo parte en
      # varias palabras (ej. "cd /root/mi proyecto" -> cd a "/root/mi" + comando
      # suelto "proyecto"). Comillas escapadas para que la bash -c interna reciba
      # la ruta como un solo argumento.
      proot-distro login "$distro" -- bash -c "cd \"/root/$name\" && npm install" >>"$plog" 2>&1 \
        || warn "npm install terminó con errores dentro de '$distro' — revisá $plog"
      ;;
  esac
  case " $tags " in
    *" python "*)
      if ! proot-distro login "$distro" -- command -v python3 &>/dev/null; then
        step "Instalando python3 python3-pip dentro de '$distro'" | tee -a "$plog"
        proot-distro login "$distro" -- env DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-pip >>"$plog" 2>&1 \
          || error "No se pudo instalar python3 dentro de '$distro'"
      fi
      if [ -f "$p/requirements.txt" ]; then
        step "pip3 install -r requirements.txt (dentro de '$distro')" | tee -a "$plog"
        # Mismo gotcha que el bloque npm install de arriba: $name sin comillas se
        # rompe en la bash -c anidada si el nombre del proyecto trae espacios.
        proot-distro login "$distro" -- bash -c "cd \"/root/$name\" && pip3 install -r requirements.txt" >>"$plog" 2>&1 \
          || warn "pip3 install terminó con errores dentro de '$distro' — revisá $plog"
      fi
      ;;
  esac
  case " $tags " in
    *" php "*)
      if ! proot-distro login "$distro" -- command -v php &>/dev/null; then
        step "Instalando php dentro de '$distro'" | tee -a "$plog"
        proot-distro login "$distro" -- env DEBIAN_FRONTEND=noninteractive apt-get install -y php >>"$plog" 2>&1 \
          || error "No se pudo instalar php dentro de '$distro'"
      fi
      warn "composer.json detectado — PHP/Composer no se automatiza en este MVP (corré 'composer install' a mano dentro de la distro, ej. proot-distro login $distro)" | tee -a "$plog"
      ;;
  esac
  case " $tags " in
    *" html "*)
      if ! proot-distro login "$distro" -- command -v python3 &>/dev/null; then
        step "Instalando python3 dentro de '$distro' (servidor estático)" | tee -a "$plog"
        proot-distro login "$distro" -- env DEBIAN_FRONTEND=noninteractive apt-get install -y python3 >>"$plog" 2>&1 \
          || error "No se pudo instalar python3 dentro de '$distro'"
      fi
      ;;
  esac

  log "Dependencias instaladas dentro de '$distro' — log: $plog"
}

# ============================================================
#  Instalación en udocker (contenedor userspace vía PRoot) — destino real de
#  "contenedores" en este entorno (ver cabecera de modulos/docker.sh: Docker
#  real —dockerd, namespaces/cgroups del kernel— NO es posible sin root en
#  Android; udocker, ya adoptado por modulos/n8n.sh y expuesto como módulo
#  propio en modulos/udocker.sh, es la alternativa real).
#
#  El proyecto NO se copia dentro del contenedor — se monta con bind-mount
#  real vía "udocker run --volume=<host>:<container>", el mismo mecanismo ya
#  documentado y usado en producción (docs/modulos/UDOCKER.md §8/§9, n8n.sh
#  variante udocker: "--volume=/data/data/.../home/n8n-udocker:/home/node/.n8n").
#  udocker NO expande "$HOME" en --volume (mismo doc, §9 "invalid host volume
#  path") — requiere ruta absoluta, que ya es lo que trae $PROJECT_PATH desde
#  el navegador de carpetas de la app (ProjectActions.kt), así que no hace
#  falta resolver nada acá. Se descartó deliberadamente un "udocker cp": no
#  aparece documentado ni en docs/modulos/UDOCKER.md ni en ningún script real
#  del proyecto — inventar su sintaxis sin poder verificarla en un dispositivo
#  real habría sido peor que usar bind-mount, que sí está confirmado.
#
#  Imagen base por stack detectado — imágenes oficiales reales de Docker Hub
#  (mismo criterio que UdockerFragment.kt: "no son un formato propio de
#  udocker, son las mismas que correrían con Docker real"). PHP: composer.json
#  se detecta y se avisa, igual que en project_install_native()/
#  project_install_distro() — no se automatiza Composer en este MVP.
# ============================================================
_udocker_container_name() { echo "kairos-proj-$(_project_hash "$1")"; }

_udocker_image_for_tags() {
  case " $1 " in
    *" node "*)   echo "node:20" ;;
    *" python "*) echo "python:3.12" ;;
    *" php "*)    echo "php:8.3-cli" ;;
    *" html "*)   echo "python:3.12" ;;
    *)            echo "" ;;
  esac
}

project_install_udocker() {
  local p="$PROJECT_PATH"
  command -v udocker &>/dev/null || error "udocker no está instalado — instalá el módulo udocker primero"
  mkdir -p "$_PROJECT_LOG_DIR"
  local plog; plog="$(_project_install_log "$p")"
  : > "$plog"
  local tags; tags="$(project_detect "$p")"
  local image; image="$(_udocker_image_for_tags "$tags")"
  [ -n "$image" ] || error "No se reconoció un stack instalable en udocker para este proyecto (tags: $tags)"
  local name; name="$(_udocker_container_name "$p")"
  export UDOCKER_USE_PROOT_EXECUTABLE="$(which proot 2>/dev/null || echo "$TERMUX_PREFIX/bin/proot")"

  step "Descargando imagen $image (udocker)" | tee -a "$plog"
  if udocker images 2>/dev/null | grep -qF "$image"; then
    log "Imagen $image ya presente" | tee -a "$plog"
  else
    udocker pull "$image" >>"$plog" 2>&1 || error "No se pudo descargar $image — revisá $plog"
  fi

  step "Creando contenedor '$name'" | tee -a "$plog"
  if udocker inspect "$name" &>/dev/null; then
    log "Contenedor '$name' ya existe" | tee -a "$plog"
  else
    udocker create --name="$name" "$image" >>"$plog" 2>&1 || error "No se pudo crear el contenedor '$name' — revisá $plog"
  fi
  udocker setup --execmode=P2 "$name" >>"$plog" 2>&1 || true

  case " $tags " in
    *" node "*)
      step "npm install (dentro del contenedor '$name')" | tee -a "$plog"
      udocker run --volume="$p":/app "$name" sh -c "cd /app && npm install" >>"$plog" 2>&1 \
        || warn "npm install terminó con errores dentro del contenedor — revisá $plog"
      ;;
  esac
  case " $tags " in
    *" python "*)
      if [ -f "$p/requirements.txt" ]; then
        step "pip install -r requirements.txt (dentro del contenedor '$name')" | tee -a "$plog"
        udocker run --volume="$p":/app "$name" sh -c "cd /app && pip install -r requirements.txt" >>"$plog" 2>&1 \
          || warn "pip install terminó con errores dentro del contenedor — revisá $plog"
      fi
      ;;
  esac
  case " $tags " in
    *" php "*)
      warn "composer.json detectado — PHP/Composer no se automatiza en este MVP (corré 'composer install' a mano dentro del contenedor, ej. con --project-action logs o una terminal en el contenedor)" | tee -a "$plog"
      ;;
  esac

  log "Dependencias instaladas dentro del contenedor '$name' (imagen $image) — log: $plog"
}

# Arranca el proceso real del proyecto en background (tmux, mismo patrón que
# TunnelManager.start()/n8n) — session única derivada del hash de la ruta,
# log persistente para "ver logs en vivo" (tail) desde la app.
project_start() {
  local p="$PROJECT_PATH" cmd="$PROJECT_CMD"
  [ -n "$cmd" ] || error "Falta --project-cmd"
  local session; session="$(_project_session "$p")"
  command -v tmux &>/dev/null || error "tmux no está instalado (módulo Entorno)"
  tmux has-session -t "$session" 2>/dev/null && error "Ya hay un proceso corriendo para este proyecto (sesión $session)"
  mkdir -p "$_PROJECT_LOG_DIR"
  local rlog; rlog="$(_project_run_log "$p")"
  local full_cmd
  if [ "$PROJECT_TARGET" = "distro" ]; then
    [ -n "$PROJECT_DISTRO" ] || error "Falta --project-distro"
    full_cmd="proot-distro login $PROJECT_DISTRO -- bash -c \"cd '$p' && $cmd\""
  elif [ "$PROJECT_TARGET" = "udocker" ]; then
    command -v udocker &>/dev/null || error "udocker no está instalado — instalá el módulo udocker primero"
    local uname; uname="$(_udocker_container_name "$p")"
    udocker inspect "$uname" &>/dev/null || error "Contenedor udocker '$uname' no existe todavía — instalá dependencias primero (--project-action install --project-target udocker)"
    export UDOCKER_USE_PROOT_EXECUTABLE="$(which proot 2>/dev/null || echo "$TERMUX_PREFIX/bin/proot")"
    full_cmd="udocker run --volume='$p':/app '$uname' sh -c \"cd /app && $cmd\""
  else
    full_cmd="cd '$p' && $cmd"
  fi
  tmux new-session -d -s "$session" "$full_cmd 2>&1 | tee '$rlog'"
  log "Proceso iniciado (sesión $session) — log: $rlog"
}

project_stop() {
  local p="$PROJECT_PATH"
  local session; session="$(_project_session "$p")"
  tmux has-session -t "$session" 2>/dev/null || error "No hay proceso corriendo para este proyecto"
  tmux kill-session -t "$session"
  log "Proceso detenido (sesión $session)"
}

project_status() {
  local p="$PROJECT_PATH"
  local session; session="$(_project_session "$p")"
  if tmux has-session -t "$session" 2>/dev/null; then
    echo "[RESUMEN] running=true session=$session log=$(_project_run_log "$p")"
  else
    echo "[RESUMEN] running=false session=$session"
  fi
}

project_logs() {
  local p="$PROJECT_PATH"
  local rlog; rlog="$(_project_run_log "$p")"
  [ -f "$rlog" ] || error "Sin logs todavía para este proyecto — arrancalo primero"
  tail -n 200 "$rlog"
}

if [ -n "$PROJECT_PATH" ]; then
  [ -d "$PROJECT_PATH" ] || error "Carpeta de proyecto no encontrada: $PROJECT_PATH"
  case "$PROJECT_TARGET" in
    native|distro|udocker) ;;
    *) error "project-target desconocido: $PROJECT_TARGET (válidos: native, distro, udocker)" ;;
  esac
  case "$PROJECT_ACTION" in
    detect)
      echo "[RESUMEN] tags=$(project_detect "$PROJECT_PATH")"
      ;;
    install)
      case "$PROJECT_TARGET" in
        distro)  project_install_distro ;;
        udocker) project_install_udocker ;;
        *)       project_install_native ;;
      esac
      ;;
    start)  project_start ;;
    stop)   project_stop ;;
    status) project_status ;;
    logs)   project_logs ;;
    *) error "project-action desconocida: $PROJECT_ACTION (válidas: detect, install, start, stop, status, logs)" ;;
  esac
  registry_write stacks "installed=true" "version=1.4.0"
  notify_event "stacks" "project_${PROJECT_ACTION}" "$PROJECT_PATH"
  exit 0
fi

VALID_PRESETS="python-postgres php-mysql react-vite html go rust java dotnet linux-completo"

# ── Sin --preset: registra el catálogo como disponible y sale (no hay
#    "paquete propio" que instalar — ver cabecera). Cubre el caso de
#    que algo dispare la instalación genérica del módulo sin preset. ──
if [ -z "$PRESET" ]; then
  registry_write stacks "installed=true" "version=1.4.0" "presets=${VALID_PRESETS// /,}"
  log "Catálogo de Entornos de Prueba disponible — presets: ${VALID_PRESETS}"
  notify_event "stacks" "install_done" "catalog"
  exit 0
fi

case " $VALID_PRESETS " in
  *" $PRESET "*) ;;
  *) error "Preset desconocido: $PRESET (válidos: $VALID_PRESETS)" ;;
esac

TARGET="native"
[ -n "$DISTRO" ] && TARGET="distro"

# dotnet no tiene paquete nativo de Termux (ver comentario real en
# native_package_for_preset()) — exigir --distro explícito en vez de caer en
# install_native_preset() y fallar recién ahí con un error críptico de "pkg install"
# sobre un paquete que no existe.
if [ "$PRESET" = "dotnet" ] && [ "$TARGET" != "distro" ]; then
  error "El preset 'dotnet' requiere una distro ya instalada — pasá --distro <nombre> (instalá una distro primero desde el módulo Entorno)"
fi

# linux-completo es un caso aparte: SIEMPRE proot-distro instalando una distro
# nueva/propia (nunca nativo, nunca "dentro de" una distro ya existente) — el
# flag --distro de los presets livianos no aplica acá y se ignora si se pasó
# por error.
if [ "$PRESET" = "linux-completo" ]; then
  TARGET="fulldistro"
  [ -n "$DISTRO" ] && warn "--distro no aplica a linux-completo (ese flag es para los presets livianos) — se ignora"
  case "$FLAVOR" in
    debian|ubuntu) ;;
    *) error "Flavor desconocido: $FLAVOR (válidos: debian, ubuntu)" ;;
  esac
fi

# ── Modo manual: cabecera y confirmación ────────────────────
if ! $SILENT; then
  clear
  echo -e "${CYAN}${BOLD}"
  cat << 'HEADER'
  ╔══════════════════════════════════════════════╗
  ║   kairos-app · Entornos de Prueba            ║
  ║   Stacks de desarrollo · v1.1.0              ║
  ╚══════════════════════════════════════════════╝
HEADER
  echo -e "${NC}"
  echo "  Preset: $PRESET"
  case "$TARGET" in
    fulldistro) echo "  Destino: distro Linux completa vía proot-distro ($FLAVOR)" ;;
    distro)     echo "  Destino: distro proot ($DISTRO)" ;;
    *)          echo "  Destino: Termux nativo" ;;
  esac
  echo ""
  echo -n "  ¿Continuar? (s/n): "
  read -r CONFIRM < /dev/tty
  [ "$CONFIRM" != "s" ] && [ "$CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ============================================================
#  Piezas nativas por preset (pkg de Termux) y su binario de chequeo
# ============================================================
native_package_for_preset() {
  case "$1" in
    python-postgres) echo "python" ;;
    php-mysql)       echo "php" ;;
    react-vite)      echo "nodejs" ;;
    html)            echo "python" ;;
    # Ronda 2026-08-25 (pedido explícito del usuario: "toca agregar mas lenguajes,
    # paquetes... tipo .net, modulos etc"). Confirmado real (WebSearch, paquetes reales
    # del repo main de Termux, no inventados): "golang" (binario go), "rust" (binario
    # cargo/rustc), "openjdk-21" (binario java/javac — JDK completo, cubre Java Y Kotlin
    # vía kotlinc si el usuario lo instala aparte, mismo criterio que react-vite cubre
    # TS/JS con un solo runtime base).
    go)              echo "golang" ;;
    rust)            echo "rust" ;;
    java)            echo "openjdk-21" ;;
    # dotnet NO tiene paquete nativo de Termux — confirmado real (WebSearch): el SDK de
    # .NET solo se empaqueta para Ubuntu/Debian real (apt), no para Bionic/Termux. Sin
    # entrada acá a propósito — install_native_preset() nunca debe llamarse para este
    # preset, ver el guard explícito agregado más abajo antes de "case "$TARGET" in".
  esac
}

native_binary_for_preset() {
  case "$1" in
    python-postgres) echo "python3" ;;
    php-mysql)       echo "php" ;;
    react-vite)      echo "node" ;;
    html)            echo "python3" ;;
    go)              echo "go" ;;
    rust)            echo "cargo" ;;
    java)            echo "java" ;;
  esac
}

# ============================================================
#  Piezas distro (Debian/apt) por preset — nombres estándar bien
#  conocidos del ecosistema apt, no requieren verificación extra.
# ============================================================
distro_packages_for_preset() {
  case "$1" in
    python-postgres) echo "python3 python3-pip postgresql" ;;
    php-mysql)       echo "php php-mysql mysql-server" ;;
    react-vite)      echo "nodejs npm" ;;
    html)            echo "python3" ;;
    # Ronda 2026-08-25 — nombres reales de paquete apt (Debian/Ubuntu, distinto del nombre
    # de Termux en algunos casos): "golang-go" (Debian empaqueta Go así, no "golang" a
    # secas), "rustc cargo" (Debian los separa en 2 paquetes), "default-jdk" (metapaquete
    # real de Debian/Ubuntu que resuelve al JDK disponible, más portable entre versiones
    # de la distro que fijar "openjdk-21-jdk" a mano).
    go)              echo "golang-go" ;;
    rust)            echo "rustc cargo" ;;
    java)            echo "default-jdk" ;;
    # dotnet SIEMPRE requiere distro (nunca nativo) — confirmado real (WebSearch): el SDK
    # de .NET solo se empaqueta para Ubuntu 24.10+ real vía apt ("dotnet-sdk-8.0"/"-9.0"),
    # no existe paquete de Termux/Bionic. Se fija la versión 8.0 (LTS, mayor compatibilidad
    # con versiones de Ubuntu más viejas que 24.10 que el usuario ya pueda tener instaladas)
    # en vez de 9.0 — el usuario puede pedir otra versión a mano dentro de la distro si
    # necesita una más nueva.
    dotnet)          echo "dotnet-sdk-8.0" ;;
  esac
}

needs_db() {
  case "$1" in
    python-postgres|php-mysql) return 0 ;;
    *) return 1 ;;
  esac
}

# ============================================================
#  Base de datos encadenada (python-postgres / php-mysql, nativo)
# ============================================================
ensure_db_native() {
  if command -v mariadbd &>/dev/null && command -v postgres &>/dev/null && ! $FORCE; then
    log "Motor de BD (MySQL/MariaDB + PostgreSQL) ya presente [db.sh omitido]"
    return 0
  fi
  if [ ! -f "$SCRIPT_DIR/db.sh" ]; then
    warn "db.sh no encontrado junto a stacks.sh — instalá el módulo 'Base de Datos' aparte"
    return 0
  fi
  info "Encadenando db.sh (MySQL/MariaDB + PostgreSQL + SQLite)…"
  local force_flag=""
  $FORCE && force_flag="--force"
  bash "$SCRIPT_DIR/db.sh" --silent $force_flag || warn "db.sh terminó con errores — la BD puede no haber quedado lista"
}

# ============================================================
#  Instalación nativa (Termux, pkg install)
# ============================================================
install_native_preset() {
  step "Actualizando índice de paquetes de Termux"
  pkg_update_with_fallback

  local pkgname; pkgname="$(native_package_for_preset "$PRESET")"
  local binname; binname="$(native_binary_for_preset "$PRESET")"
  step "Instalando $pkgname"
  if command -v "$binname" &>/dev/null && ! $FORCE; then
    log "$pkgname ya instalado ($($binname --version 2>&1 | head -1))"
  else
    pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" "$pkgname" \
      || error "No se pudo instalar $pkgname"
    command -v "$binname" &>/dev/null || error "$binname no quedó disponible tras instalar $pkgname"
    log "$pkgname instalado"
  fi

  if needs_db "$PRESET"; then
    step "Base de datos"
    ensure_db_native
  fi
}

# ============================================================
#  Instalación en distro (proot-distro login -- apt-get install)
# ============================================================
install_distro_preset() {
  step "Verificando proot-distro y la distro '$DISTRO'"
  command -v proot-distro &>/dev/null || error "proot-distro no está instalado — instalá el módulo Entorno primero"

  local listado; listado="$(proot-distro list 2>/dev/null | tr '[:upper:]' '[:lower:]')"
  echo "$listado" | grep -E "^[[:space:]]*${DISTRO}\b" | grep -q "installed" \
    || error "La distro '$DISTRO' no está instalada — instalala primero desde el módulo Entorno"

  local pkgs; pkgs="$(distro_packages_for_preset "$PRESET")"

  step "Actualizando índice de paquetes dentro de $DISTRO"
  proot-distro login "$DISTRO" -- env DEBIAN_FRONTEND=noninteractive apt-get update -y >/dev/null 2>&1 \
    || warn "apt-get update dentro de $DISTRO tuvo advertencias — sigo con la instalación"

  step "Instalando ($pkgs) dentro de $DISTRO"
  proot-distro login "$DISTRO" -- env DEBIAN_FRONTEND=noninteractive apt-get install -y \
    -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" $pkgs \
    || error "La instalación de ($pkgs) dentro de $DISTRO falló"
  log "Paquetes instalados dentro de $DISTRO"
}

# ============================================================
#  Instalación de distro Linux completa (preset linux-completo)
#
#  Mismo patrón ya probado en modulos/entorno.sh (_install_proot_distro,
#  instala el paquete proot-distro si falta) y modulos/n8n.sh (variante
#  proot, PASO 2 — "proot-distro install <nombre>" con manejo idempotente
#  de "already exists" vía grep sobre stdout+stderr). No se reinventa la
#  lógica: se reutiliza tal cual, solo parametrizada por $FLAVOR (debian
#  por defecto, o ubuntu) en vez de tener "debian" fijo como en n8n.sh.
# ============================================================
install_full_distro_preset() {
  step "Verificando proot-distro"
  if command -v proot-distro &>/dev/null; then
    log "proot-distro ya instalado"
  else
    pkg_update_with_fallback
    pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
      proot-distro proot || error "No se pudo instalar proot-distro"
    log "proot-distro instalado"
  fi

  step "Instalando distro Linux completa: $FLAVOR"
  local out rc
  out=$(proot-distro install "$FLAVOR" 2>&1); rc=$?
  if echo "$out" | grep -q "already exists"; then
    log "$FLAVOR ya estaba instalado"
  elif [ $rc -ne 0 ]; then
    error "Falló la instalación de $FLAVOR: $out"
  else
    log "$FLAVOR instalado"
  fi

  # Nombre real registrado por proot-distro (puede diferir levemente del
  # flavor pedido, ej. variantes con sufijo de versión) — mismo criterio de
  # detección que _detect_rootfs en n8n.sh, simplificado porque acá ya
  # sabemos el nombre pedido y solo confirmamos que quedó instalado.
  local listado
  listado="$(proot-distro list 2>/dev/null | tr '[:upper:]' '[:lower:]')"
  echo "$listado" | grep -E "^[[:space:]]*${FLAVOR}\b" | grep -q "installed" \
    || warn "No se pudo confirmar '$FLAVOR' como instalada en 'proot-distro list' — revisá manualmente"
}

# ============================================================
#  Paquetes extra por preset (2026-08-23, ver docs/humano209.md, pedido
#  explícito: "en entorno faltan muchas configuracion o lenjuagesm tipo
#  fash api de python, entre muchos mas, investiga y agregalos") — el
#  preset instala el runtime base (python3/nodejs/php), --extra agrega
#  frameworks/librerías puntuales encima vía el gestor de paquetes propio
#  de cada lenguaje (pip/npm/composer), sin tocar qué presets existen.
# ============================================================
extra_packages_catalog() {
  case "$1" in
    python-postgres) echo "fastapi,django,flask,uvicorn,sqlalchemy,requests,numpy,pandas" ;;
    react-vite)      echo "express,next,vue,axios" ;;
    php-mysql)       echo "laravel" ;;
    *)                echo "" ;;
  esac
}

install_extra_packages() {
  [ -z "$EXTRA" ] && return 0
  local IFS=','
  local pkgs; read -ra pkgs <<< "$EXTRA"
  case "$PRESET" in
    python-postgres)
      step "Instalando paquetes extra de Python: $EXTRA"
      if [ "$TARGET" = "distro" ]; then
        proot-distro login "$DISTRO" -- pip3 install --break-system-packages "${pkgs[@]}" \
          || warn "Algunos paquetes extra de Python fallaron dentro de $DISTRO — revisá manualmente"
      else
        pip install --user "${pkgs[@]}" \
          || warn "Algunos paquetes extra de Python fallaron — revisá manualmente"
      fi
      ;;
    react-vite)
      step "Instalando paquetes extra de Node: $EXTRA"
      if [ "$TARGET" = "distro" ]; then
        proot-distro login "$DISTRO" -- npm install -g "${pkgs[@]}" \
          || warn "Algunos paquetes extra de Node fallaron dentro de $DISTRO — revisá manualmente"
      else
        npm install -g "${pkgs[@]}" \
          || warn "Algunos paquetes extra de Node fallaron — revisá manualmente"
      fi
      ;;
    php-mysql)
      if [[ " ${pkgs[*]} " == *" laravel "* ]]; then
        if [ "$TARGET" = "distro" ]; then
          step "Instalando Composer + Laravel installer dentro de $DISTRO"
          proot-distro login "$DISTRO" -- bash -c \
            'command -v composer &>/dev/null || (curl -sS https://getcomposer.org/installer | php -- --install-dir=/usr/local/bin --filename=composer)' \
            || warn "No se pudo instalar Composer dentro de $DISTRO"
          proot-distro login "$DISTRO" -- composer global require laravel/installer \
            || warn "No se pudo instalar el instalador de Laravel dentro de $DISTRO"
        else
          warn "Laravel/Composer necesita una distro (proot) — instalá una desde el módulo Entorno y elegí 'instalar en distro'"
        fi
      fi
      ;;
    *) warn "El preset '$PRESET' no tiene paquetes extra disponibles — se ignora --extra" ;;
  esac
}

# ============================================================
#  Resumen final — comandos reales para arrancar cada pieza
# ============================================================
print_summary() {
  case "$TARGET" in
    fulldistro) echo "[RESUMEN] Preset: $PRESET · Destino: distro Linux completa ($FLAVOR)" ;;
    distro)     echo "[RESUMEN] Preset: $PRESET · Destino: distro $DISTRO" ;;
    *)          echo "[RESUMEN] Preset: $PRESET · Destino: Termux nativo" ;;
  esac
  case "$PRESET:$TARGET" in
    linux-completo:fulldistro)
      echo "[RESUMEN] Entrar a la distro: proot-distro login $FLAVOR"
      echo "[RESUMEN] Es una distro Linux completa y real ($FLAVOR) — no un preset liviano:"
      echo "[RESUMEN]   instalá ahí dentro lo que necesites con apt-get (a diferencia de los"
      echo "[RESUMEN]   presets python-postgres/php-mysql/react-vite/html, que corren nativos"
      echo "[RESUMEN]   en Termux sin pasar por proot)."
      ;;
    python-postgres:native)
      echo "[RESUMEN] Python:     python3 tu_app.py"
      echo "[RESUMEN] PostgreSQL: bash ~/scripts/db/postgres_start.sh   (luego: psql -U \$(whoami))"
      ;;
    python-postgres:distro)
      echo "[RESUMEN] proot-distro login $DISTRO"
      echo "[RESUMEN]   python3 tu_app.py"
      echo "[RESUMEN]   service postgresql start && psql"
      ;;
    php-mysql:native)
      echo "[RESUMEN] PHP:   php -S 0.0.0.0:8080 -t ."
      echo "[RESUMEN] MySQL: bash ~/scripts/db/mysql_start.sh   (luego: mysql -u root)"
      ;;
    php-mysql:distro)
      echo "[RESUMEN] proot-distro login $DISTRO"
      echo "[RESUMEN]   php -S 0.0.0.0:8080 -t ."
      echo "[RESUMEN]   service mysql start && mysql -u root"
      ;;
    react-vite:native)
      echo "[RESUMEN] npm create vite@latest mi-app -- --template react"
      echo "[RESUMEN] cd mi-app && npm install && npm run dev -- --host"
      ;;
    react-vite:distro)
      echo "[RESUMEN] proot-distro login $DISTRO"
      echo "[RESUMEN]   npm create vite@latest mi-app -- --template react"
      echo "[RESUMEN]   cd mi-app && npm install && npm run dev -- --host"
      ;;
    html:native)
      echo "[RESUMEN] Servidor estático: cd tu_proyecto && python3 -m http.server 8080"
      ;;
    html:distro)
      echo "[RESUMEN] proot-distro login $DISTRO"
      echo "[RESUMEN]   cd tu_proyecto && python3 -m http.server 8080"
      ;;
    go:native)
      echo "[RESUMEN] go run tu_app.go   (o: go build && ./tu_app)"
      ;;
    go:distro)
      echo "[RESUMEN] proot-distro login $DISTRO"
      echo "[RESUMEN]   go run tu_app.go"
      ;;
    rust:native)
      echo "[RESUMEN] cargo new mi_proyecto && cd mi_proyecto && cargo run"
      ;;
    rust:distro)
      echo "[RESUMEN] proot-distro login $DISTRO"
      echo "[RESUMEN]   cargo new mi_proyecto && cd mi_proyecto && cargo run"
      ;;
    java:native)
      echo "[RESUMEN] javac Main.java && java Main"
      ;;
    java:distro)
      echo "[RESUMEN] proot-distro login $DISTRO"
      echo "[RESUMEN]   javac Main.java && java Main"
      ;;
    dotnet:distro)
      echo "[RESUMEN] proot-distro login $DISTRO"
      echo "[RESUMEN]   dotnet new console -o mi_app && cd mi_app && dotnet run"
      echo "[RESUMEN] Nota: compilar puede fallar en algunos dispositivos (csc.dll SIGSEGV,"
      echo "[RESUMEN]   limitación conocida de .NET en ARM64/proot) — si pasa, no es un bug"
      echo "[RESUMEN]   de Kairos, es una limitación real de .NET en este entorno."
      ;;
  esac
  [ -n "$EXTRA" ] && echo "[RESUMEN] Extras instalados: $EXTRA"
}

# ============================================================
#  Ejecutar
# ============================================================
case "$TARGET" in
  fulldistro) install_full_distro_preset ;;
  distro)     install_distro_preset ;;
  *)          install_native_preset ;;
esac

[ "$TARGET" != "fulldistro" ] && install_extra_packages

registry_write stacks "installed=true" "version=1.4.0" "last_preset=$PRESET" "last_target=$TARGET" "last_distro=$DISTRO" "last_flavor=$FLAVOR" "last_extra=$EXTRA"

print_summary

if ! $SILENT; then
  echo ""
  echo -e "${GREEN}${BOLD}  Preset '$PRESET' listo ✓${NC}"
  echo ""
fi

notify_event "stacks" "install_done" "$PRESET:$TARGET"
log "Preset '$PRESET' completado ($TARGET)"
exit 0
