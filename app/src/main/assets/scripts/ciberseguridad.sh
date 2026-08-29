#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · ciberseguridad.sh (silent mode)
#  Instala el kit de herramientas de red/OSINT en Termux ARM64 — 2 niveles
#
#  USO DESDE APP (KairosApp):
#    bash ciberseguridad.sh --silent                          (básico, default)
#    bash ciberseguridad.sh --silent --variant pro-headless    (básico + Kali sin GUI)
#    bash ciberseguridad.sh --silent --variant pro-gui         (básico + Kali con GUI)
#
#  FLAGS:
#    --silent          Sin preguntas, instala todo directo
#    --force           Reinstala aunque ya esté
#    --variant <tipo>  basico (default) | pro-headless | pro-gui
#
#  NIVEL BÁSICO (bionic nativo, sin proot — igual que antes):
#    ✅ nmap (pkg oficial de Termux) — escaneo de red/puertos
#    ✅ netcat-openbsd (pkg) — utilidad de red/diagnóstico
#    ✅ dirb (pkg oficial de Termux, confirmado 2026-08-17) — fuerza bruta de
#       directorios web
#    ✅ nikto (git clone sullo/nikto + perl — NO es un paquete de Termux,
#       confirmado 2026-08-17: no existe en ningún repo oficial) — escáner de
#       vulnerabilidades web
#    ✅ Python 3 (si falta)
#    ✅ theHarvester (pip --no-deps, repo oficial laramies/theHarvester, +
#       stub local de playwright — ver PASO 4) — OSINT (recolección pasiva de
#       emails/subdominios/hosts públicos; --screenshot no disponible)
#    ✅ sqlmap (pip) — automatización de detección/explotación de SQLi
#
#  NIVEL PRO (básico + Kali Linux vía proot-distro):
#    ✅ Todo lo del nivel básico
#    ✅ proot-distro (instalado si falta — misma función que modulos/entorno.sh)
#    ✅ Contenedor "kali" — imagen OFICIAL kalilinux/kali-rolling de Docker Hub,
#       instalada con: proot-distro install kalilinux/kali-rolling -n kali
#       (investigado 2026-08-16: proot-distro v5.6.0, la que empaqueta Termux
#       hoy, YA NO tiene una lista curada de distros con alias fijos tipo
#       "kali"/"ubuntu" — desde la v5 instala CUALQUIER imagen Docker/OCI por
#       referencia + `-n/--override-alias` para el nombre del contenedor. No
#       existe un alias oficial "kali" — se usa la imagen oficial de Kali en
#       Docker Hub, que sí trae los repos apt de Kali ya configurados)
#    ✅ kali-tools-top10 (metapaquete oficial de Kali, curado — mismo criterio
#       de alcance responsable que el nivel básico, no kali-linux-everything)
#    ✅ --variant pro-gui: además dbus-x11 + xfce4 DENTRO del contenedor,
#       reutilizando ~/scripts/entorno/distro_setup_gui.sh TAL CUAL (mismo
#       script que genera modulos/entorno.sh para cualquier distro proot) —
#       si el módulo Entorno no está instalado todavía, este script lo corre
#       primero (--silent) para tener el X11 embebido + esos scripts de
#       gestión, en vez de reimplementar esa lógica acá
#
#  ALCANCE (uso responsable, ver docs/humano/humano99.md):
#    Herramientas estándar de red/OSINT/pentesting, pensadas para diagnóstico
#    de tu propia red y pentesting autorizado. El catálogo más amplio de
#    i-Haklab (bruteforce, automatización de Metasploit, forense Android,
#    servidores de práctica DVWA/bWAPP/Mutillidae) sigue pendiente para una
#    ronda posterior dedicada, con más tiempo para el alcance y la UX de
#    advertencia de cada herramienta por separado.
#
#  OUTPUT (modo --silent):
#    [STEP] descripción
#    [OK]/[WARN]/[ERROR] mensaje
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 2.1.0 | Agosto 2026 (agrega nivel "pro": Kali Linux vía proot-distro
#  con imagen oficial Docker Hub + variante GUI reutilizando entorno.sh — pedido
#  "ampliar ciberseguridad a 2 niveles, básico y pro con Kali" | v1.2.0 amplió
#  netcat/dirb/nikto + sqlmap, ver humano101 | fix PASO 4 theHarvester: ver
#  humano121 | v2.1.0 (2026-08-17): root cause real de "dirb: no · nikto: no" en
#  dispositivo — nikto NUNCA fue un paquete de Termux, y al ir en la misma línea
#  `pkg install` que dirb, apt fallaba la resolución completa y se llevaba a dirb
#  con él; se separan y nikto pasa a git clone+perl. theHarvester: playwright no
#  tiene wheel para Bionic libc en NINGUNA versión (investigado en 6+ tags) —
#  fix real con --no-deps + deps reales + stub local de playwright, y ya no
#  aborta el script completo (para que sqlmap se siga intentando igual)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false
VARIANT="basico"

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
    --variant)  shift; VARIANT="$1" ;;
  esac
  shift
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"ciberseguridad","supports_silent":true,"supports_force":true,"variants":["basico","pro-headless","pro-gui"],"variant_required":false,"variant_default":"basico","note":"basico = nmap+netcat+dirb+nikto+theHarvester+sqlmap (bionic nativo). pro-headless/pro-gui = basico + contenedor Kali Linux via proot-distro (imagen oficial kalilinux/kali-rolling) + kali-tools-top10; pro-gui ademas instala xfce4+dbus-x11 dentro del contenedor reutilizando los scripts de modulos/entorno.sh"}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Alcance deliberadamente acotado
# (10 herramientas, criterio "best-effort" del propio diseño): nmap/netcat-openbsd/
# dirb son paquetes `pkg` normales (reinstalables en segundos, no vale la pena
# empaquetarlos); theHarvester/sqlmap viven en site-packages de pip (decenas de
# archivos dispersos, no una lista corta reubicable); el ÚNICO componente
# realmente "caro de rehacer" es nikto (git clone, sin paquete oficial) — es lo
# único que este describe-files cubre. Variantes pro-headless/pro-gui (Kali vía
# proot-distro) NO cubiertas — mismo criterio que n8n.sh con udocker: estado de
# contenedor, no archivos simples reubicables.
if $DESCRIBE_FILES; then
  jq -n \
    --arg p2 "$TERMUX_PREFIX/bin/nikto" \
    --arg glob "$HOME/.nikto/**" \
    --arg verify "\"$TERMUX_PREFIX/bin/nikto\" -Version >/dev/null 2>&1" \
    --arg patch "chmod +x \"$TERMUX_PREFIX/bin/nikto\" 2>/dev/null || true" \
    '{
      id: "ciberseguridad",
      supports_describe_files: true,
      variant: "basico",
      package_name: "kairos-module-ciberseguridad",
      version_registry_key: "ciberseguridad.version",
      files: [
        {path: $p2, required: true, note: "Wrapper: exec perl $HOME/.nikto/program/nikto.pl"}
      ],
      file_globs: [
        {pattern: $glob, required: true, note: "Repo git clonado de sullo/nikto (sin paquete oficial de Termux)"}
      ],
      dependencies: [
        {id: "perl", check_cmd: "command -v perl >/dev/null 2>&1", install_hint: "pkg install -y perl"}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "nmap/netcat-openbsd/dirb son paquetes pkg normales — no empaquetados acá, se reinstalan en segundos con pkg install",
        "theHarvester y sqlmap (pip) viven en site-packages, dispersos en decenas de archivos — no cubiertos, reinstalar con el modulo normal si faltan",
        "Variantes pro-headless/pro-gui (contenedor Kali vía proot-distro) NO cubiertas — es estado de contenedor, no archivos reubicables, igual criterio que n8n.sh/udocker"
      ]
    }'
  exit 0
fi

# ── Normalizar variante → tier (básico/pro) + modo GUI ────────
PRO=false
PRO_GUI=false
case "$VARIANT" in
  pro-gui)                PRO=true; PRO_GUI=true ;;
  pro-headless|pro)       PRO=true; PRO_GUI=false ;;
  basico|*)               PRO=false ;;
esac
KALI_CONTAINER="kali"

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_ciberseguridad_checkpoint"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^ciberseguridad_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "ciberseguridad_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^ciberseguridad_${1}=done" "$CHECKPOINT" 2>/dev/null; }


if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  ◆ CIBERSEGURIDAD — Kit de red/OSINT     ║"
  echo "  ║  nmap theHarvester sqlmap nikto netcat   ║"
  if $PRO; then
  echo "  ║  + Kali Linux (proot-distro)              ║"
  fi
  echo "  ║  Termux ARM64                            ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
# Nivel básico ya cubierto: si además se pide --variant pro-*, NO se sale acá
# aunque el básico ya esté — hace falta seguir a los pasos 6-8 (Kali).
BASE_INSTALLED=false
command -v nmap &>/dev/null && command -v theHarvester &>/dev/null && command -v sqlmap &>/dev/null && BASE_INSTALLED=true
if $BASE_INSTALLED && ! $PRO && ! $FORCE; then
  log "Kit de ciberseguridad (básico) ya instalado"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar el kit de ciberseguridad? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — nmap ─────────────────────────────────────────────
step "PASO 1 — Instalando nmap"
if check_done "nmap"; then
  log "nmap ya instalado [checkpoint]"
else
  pkg_update_with_fallback
  pkg install -y nmap 2>/dev/null || error "No se pudo instalar nmap"
  command -v nmap &>/dev/null || error "nmap no disponible tras instalación"
  log "nmap instalado: $(nmap --version 2>/dev/null | head -1)"
  mark_done "nmap"
fi

# ── PASO 2 — netcat / dirb (pkg) ─────────────────────────────
# NOTA (2026-08-17, root cause real confirmado — log de dispositivo mostraba
# "dirb: no · nikto: no" pese a que dirb SÍ es un paquete real de Termux,
# confirmado en packages.termux.dev/apt/termux-main/pool/main/d/dirb/
# (dirb_2.22-5_aarch64.deb, publicado 2025-09-09): apt/pkg resuelve TODOS los
# nombres de un mismo `install` antes de instalar ninguno — como "nikto" NUNCA
# existió como paquete de Termux (no está en el índice binary-aarch64 de
# ningún repo oficial), la línea `pkg install netcat-openbsd dirb nikto`
# fallaba la resolución completa y se llevaba a dirb con ella (netcat ya
# estaba disponible de antes por otra vía — por eso salía "ok" pese al fallo
# de la línea entera). Fix: separar nikto (no es un pkg, ver PASO 2b) de
# netcat+dirb (sí lo son).
step "PASO 2 — netcat + dirb (pkg)"
if check_done "pkg_extra"; then
  log "netcat/dirb ya instalados [checkpoint]"
else
  info "Instalando netcat-openbsd dirb..."
  pkg_update_with_fallback
  pkg install -y netcat-openbsd dirb 2>/dev/null || warn "Algún paquete de red no se instaló (no crítico)"
  log "netcat: $(command -v nc >/dev/null 2>&1 && echo ok || echo 'no') · dirb: $(command -v dirb >/dev/null 2>&1 && echo ok || echo 'no')"
  mark_done "pkg_extra"
fi

# ── PASO 2b — nikto (git clone sullo/nikto + perl) ───────────
# "nikto" no existe como paquete en NINGÚN repo oficial de Termux (main,
# confirmado 2026-08-17 contra el índice binary-aarch64 completo). El
# proyecto real (sullo/nikto) es un script Perl puro sin build nativo, así que
# se clona su repo y se expone con un wrapper delgado en PATH — mismo patrón
# ya usado por cactus.sh/freebuff.sh (wrapper bash que delega en el intérprete
# real en vez de reimplementar la herramienta).
step "PASO 2b — nikto (git clone sullo/nikto + perl)"
if check_done "nikto"; then
  log "nikto ya instalado [checkpoint]"
else
  command -v perl &>/dev/null || { pkg_update_with_fallback; pkg install -y perl 2>/dev/null; }
  if ! command -v perl &>/dev/null; then
    warn "No se pudo instalar perl — nikto no disponible (no crítico)"
  else
    NIKTO_DIR="$HOME/.nikto"
    if [ -d "$NIKTO_DIR/.git" ]; then
      log "Repo de nikto ya clonado"
    else
      rm -rf "$NIKTO_DIR"
      git clone --depth 1 https://github.com/sullo/nikto.git "$NIKTO_DIR" 2>/dev/null || warn "git clone de nikto falló (no crítico)"
    fi
    if [ -f "$NIKTO_DIR/program/nikto.pl" ]; then
      cat > "$TERMUX_PREFIX/bin/nikto" << WRAPPER
#!/data/data/com.termux/files/usr/bin/bash
exec perl "$NIKTO_DIR/program/nikto.pl" "\$@"
WRAPPER
      chmod +x "$TERMUX_PREFIX/bin/nikto"
      # Bug real confirmado por ADB (2026-08-25, panel real "nikto — escaneo rápido" de
      # Ciberseguridad, ver docs/adb/AUDITORIA_MODULO_POR_MODULO_2026-08-24.md): nikto.pl usa
      # "XML::Writer" incondicionalmente al arrancar (no solo con "-Format xml"), y ese módulo
      # NO viene con el paquete "perl" de Termux ni existe como paquete separado en el repo
      # (confirmado con "pkg search xml-writer" → sin resultados) — sin él, CUALQUIER invocación
      # de nikto fallaba con "ERROR: Required module not found: XML::Writer" antes de escanear
      # nada. XML::Writer es Perl puro (sin extensión C), así que "cpan -T XML::Writer" alcanza
      # sin necesitar un toolchain de compilación aparte — confirmado en vivo en el dispositivo
      # (instala y nikto corre después). Best-effort: si CPAN no tiene red o falla, nikto queda
      # clonado igual (mismo criterio "no crítico" que el resto de este paso).
      PERL_MM_USE_DEFAULT=1 timeout 90 cpan -T XML::Writer &>/dev/null || \
        warn "No se pudo instalar XML::Writer via CPAN — nikto puede fallar al ejecutar (no crítico)"
      log "nikto instalado (wrapper -> $NIKTO_DIR/program/nikto.pl)"
    else
      warn "nikto.pl no encontrado tras el clone — nikto no disponible (no crítico)"
    fi
  fi
  mark_done "nikto"
fi

# ── PASO 3 — Python (si falta) ─────────────────────────────────
step "PASO 3 — Verificando Python"
if check_done "python"; then
  log "Python ya verificado [checkpoint]"
else
  PYTHON_PATH=$(command -v python 2>/dev/null || command -v python3 2>/dev/null)
  if [ -n "$PYTHON_PATH" ]; then
    log "Python detectado: $($PYTHON_PATH --version 2>/dev/null)"
    mark_done "python"
  else
    info "Instalando python..."
    pkg_update_with_fallback
    pkg install python -y 2>/dev/null || error "No se pudo instalar Python"
    command -v python3 &>/dev/null || error "Python no disponible tras instalación"
    log "Python instalado: $(python3 --version)"
    mark_done "python"
  fi
fi

# ── PASO 4 — theHarvester (OSINT) ──────────────────────────────
# NOTA (2026-08-14, root cause confirmado vía log real de dispositivo —
# "[ERROR] theHarvester no disponible tras instalación" sin "pip install falló"
# antes, es decir el pip install SÍ terminaba con exit 0):
# el paquete "theHarvester" publicado en PyPI (pypi.org/project/theHarvester)
# es un placeholder abandonado — versión 0.0.1 de febrero 2019, sin
# [project.scripts]/entry_points. `pip install theHarvester` "instalaba bien"
# (exit 0) pero jamás dejaba el comando `theHarvester` en PATH porque ese
# paquete no define ninguno. El proyecto real (laramies/theHarvester) no se
# publica en PyPI — su pyproject.toml define theHarvester = "theHarvester.
# theHarvester:main" en [project.scripts], pero solo si se instala desde el
# repo de GitHub. Fix: instalar directo desde el repo oficial con
# `pip install git+https://...` en vez del nombre de PyPI.
#
# NOTA 2 (2026-08-17, investigación real sobre "ERROR: Could not find a version
# that satisfies the requirement playwright==1.60.0" de un log de dispositivo
# posterior): se auditaron los tags 4.5.0 → 4.11.1 → master de theHarvester en
# GitHub — playwright es dependencia dura desde la 4.6.0 (2026), y NINGUNA
# versión de playwright (se probaron 1.42.0 a 1.60.0) tiene wheel para Termux/
# Android: PyPI solo publica wheels manylinux (glibc)/macOS/Windows para ese
# paquete, y no es pura-Python compilable desde sdist (embebe binarios de
# Chromium). Es un límite real de la plataforma (Bionic libc, no glibc) — no
# hay una versión más vieja de theHarvester que lo evite, y no hay forma de
# compilarlo. Fix real:
#   1. `pip install --no-deps` del paquete theHarvester en sí — salta TODAS
#      sus dependencias (no solo playwright), evitando que pip aborte la
#      resolución completa por un solo paquete sin wheel.
#   2. Instalar a mano el resto de dependencias reales, leídas del propio
#      pyproject.toml del repo (vía tomllib) en vez de una lista hardcodeada
#      que se desactualizaría cada vez que el proyecto agregue/quite una dep.
#   3. Un stub local del paquete "playwright" — necesario porque __main__.py
#      hace `from playwright.async_api import async_playwright` de forma
#      INCONDICIONAL al arrancar (no solo cuando se pide --screenshot); sin el
#      stub, CUALQUIER uso de theHarvester truena con ModuleNotFoundError. Con
#      el stub, todos los engines de búsqueda OSINT funcionan igual que
#      siempre — solo --screenshot (que de por sí necesita lanzar un Chromium
#      real, tampoco viable en Termux/Android) falla con un mensaje claro si
#      se pide explícitamente.
# Los fallos de este paso ahora son `warn` (no `error`/exit) — antes un fallo
# acá abortaba el script ENTERO y el PASO 5 (sqlmap) nunca llegaba a
# intentarse, confirmado con el log real del dispositivo (se corta justo
# después del fallo de este paso).
step "PASO 4 — Instalando theHarvester (repo oficial laramies/theHarvester)"
if check_done "theharvester"; then
  log "theHarvester ya instalado [checkpoint]"
else
  if ! command -v git &>/dev/null; then
    info "git no encontrado, instalando..."
    pkg_update_with_fallback
    pkg install -y git 2>/dev/null
  fi
  if ! command -v git &>/dev/null; then
    warn "git no disponible — theHarvester no se pudo instalar (no crítico)"
  else
    PIP_PYTHON=$(command -v python 2>/dev/null || command -v python3 2>/dev/null)
    info "Resolviendo dependencias reales de theHarvester (excluyendo playwright/winloop)..."
    TH_DEPS=$("$PIP_PYTHON" - << 'PYEOF'
import tomllib
import urllib.request

try:
    data = urllib.request.urlopen(
        "https://raw.githubusercontent.com/laramies/theHarvester/master/pyproject.toml",
        timeout=15,
    ).read()
    deps = tomllib.loads(data.decode())["project"]["dependencies"]
    names = []
    for dep in deps:
        name = dep.split(";")[0].strip()
        for sep in ("==", ">=", "<=", "~=", "!=", "<", ">"):
            if sep in name:
                name = name.split(sep)[0].strip()
                break
        if name.lower() not in ("playwright", "winloop"):
            names.append(name)
    print(" ".join(names))
except Exception:
    pass
PYEOF
)
    if [ -z "$TH_DEPS" ]; then
      warn "No se pudo leer pyproject.toml de theHarvester (red o parseo) — se intenta con el set de dependencias conocido (2026-08-17)"
      TH_DEPS="aiodns aiofiles aiohttp aiohttp-socks aiomultiprocess aiosqlite beautifulsoup4 censys certifi dnspython fastapi lxml netaddr PyYAML python-dateutil httpx retrying shodan slowapi ujson uvicorn uvloop"
    fi
    info "Instalando dependencias reales de theHarvester: $TH_DEPS"
    # pip_install() en vez de "$PIP_PYTHON" -m pip install directo (lib.sh, 2026-08-28, ver
    # docs/humano278.md): serializa con flock contra cualquier OTRO módulo instalando por pip
    # al mismo tiempo — confirmado en dispositivo que mistralvibe.sh y n8n.sh fallaron
    # mientras este PASO 4 corría en paralelo.
    pip_install "$PIP_PYTHON" $TH_DEPS 2>&1 | tail -8
    info "Ejecutando: $PIP_PYTHON -m pip install --no-deps git+https://github.com/laramies/theHarvester.git"
    pip_install "$PIP_PYTHON" --no-deps "git+https://github.com/laramies/theHarvester.git" 2>&1 | tail -8
    if [ ${PIPESTATUS[0]} -ne 0 ]; then
      warn "pip install (repo oficial, --no-deps) falló — revisar requisitos (Python >= 3.12) (no crítico)"
    else
      # Stub de playwright — ver NOTA 2 arriba. Se escribe en el purelib real
      # del intérprete usado (sysconfig, no una ruta hardcodeada) para que el
      # import incondicional de __main__.py lo encuentre.
      PLAYWRIGHT_STUB_DIR=$("$PIP_PYTHON" -c "import sysconfig; print(sysconfig.get_paths()['purelib'])" 2>/dev/null)
      if [ -n "$PLAYWRIGHT_STUB_DIR" ]; then
        mkdir -p "$PLAYWRIGHT_STUB_DIR/playwright"
        : > "$PLAYWRIGHT_STUB_DIR/playwright/__init__.py"
        cat > "$PLAYWRIGHT_STUB_DIR/playwright/async_api.py" << 'PYEOF'
"""Stub de playwright para Termux/Android (ver ciberseguridad.sh PASO 4) — el
paquete real no tiene wheel compatible con Bionic libc en ninguna version.
theHarvester importa esto de forma incondicional al arrancar; este stub evita
el ModuleNotFoundError para que el OSINT normal funcione. Solo --screenshot
(que de por si necesita un Chromium real) falla, con un mensaje claro.
"""


class _PlaywrightUnavailableError(RuntimeError):
    pass


def async_playwright():
    raise _PlaywrightUnavailableError(
        "playwright no esta disponible en Termux/Android (sin wheel compatible "
        "con Bionic libc) -- la funcion --screenshot de theHarvester no "
        "funciona en este dispositivo"
    )


class Browser:
    pass


class BrowserContext:
    pass


class Page:
    pass
PYEOF
      else
        warn "No se pudo resolver el purelib de $PIP_PYTHON — stub de playwright no escrito, theHarvester puede fallar al arrancar"
      fi
      # Chequeo funcional real, no solo "existe en PATH" — ver docs/humano/humano194.md,
      # verify_binary_installed() en lib.sh.
      if verify_binary_installed theHarvester; then
        log "theHarvester instalado (OSINT completo; --screenshot no disponible, ver NOTA 2 arriba)"
        mark_done "theharvester"
      else
        warn "theHarvester no ejecuta tras la instalación (no crítico)"
      fi
    fi
  fi
fi

# ── PASO 5 — sqlmap (SQLi) ─────────────────────────────────────
# Fallos de este paso son `warn` (no `error`/exit), mismo criterio que PASO 4
# (theHarvester) de arriba — confirmado por ADB en dispositivo real
# (2026-08-29) que un `error()` acá (pip lock tomado por OTRO módulo
# instalando en paralelo, ej. hermes.sh corriendo su propio pip install de
# varios minutos) abortaba el script ENTERO antes de llegar a PASO 6/7/8
# (proot-distro + contenedor Kali + GUI) — el usuario reportaba "Pro con GUI
# falla" pero la causa real no tenía nada que ver con Kali/GUI: sqlmap es
# parte del nivel básico y no debería poder tumbar el nivel Pro completo.
step "PASO 5 — Instalando sqlmap vía pip"
if check_done "sqlmap"; then
  log "sqlmap ya instalado [checkpoint]"
else
  PIP_PYTHON2=$(command -v python 2>/dev/null || command -v python3 2>/dev/null)
  info "Ejecutando: $PIP_PYTHON2 -m pip install sqlmap"
  pip_install "$PIP_PYTHON2" sqlmap 2>&1 | tail -5
  if [ ${PIPESTATUS[0]} -ne 0 ]; then
    warn "pip install sqlmap falló (no crítico)"
  # Chequeo funcional real, no solo "existe en PATH" — ver docs/humano/humano194.md,
  # verify_binary_installed() en lib.sh.
  elif verify_binary_installed sqlmap; then
    log "sqlmap instalado: $(sqlmap --version 2>/dev/null)"
    mark_done "sqlmap"
  else
    warn "sqlmap no ejecuta tras la instalación (no crítico, revisá manualmente: sqlmap --version)"
  fi
fi

_KALI_TOOLS=""
_GUI_STATUS=""

if $PRO; then
  # ── PASO 6 — proot-distro ────────────────────────────────────
  # Misma función que _install_proot_distro() de modulos/entorno.sh — no se
  # sourcea entorno.sh entero (define muchas funciones internas no
  # necesarias acá), pero es la MISMA llamada real (pkg install proot-distro).
  step "PASO 6 — proot-distro (para el contenedor Kali)"
  if check_done "proot_distro"; then
    log "proot-distro ya instalado [checkpoint]"
  else
    if command -v proot-distro &>/dev/null; then
      log "proot-distro ya instalado"
    else
      pkg_update_with_fallback
      pkg install -y proot-distro 2>/dev/null || error "No se pudo instalar proot-distro"
      command -v proot-distro &>/dev/null || error "proot-distro no disponible tras instalación"
      log "proot-distro instalado"
    fi
    mark_done "proot_distro"
  fi

  # ── PASO 7 — Contenedor Kali (imagen oficial Docker Hub) ─────
  # proot-distro v5.6.0 (la que empaqueta Termux hoy, ver header de este
  # archivo) ya no tiene un alias curado "kali" — instala CUALQUIER imagen
  # Docker/OCI por referencia. Se usa la imagen oficial kalilinux/kali-rolling
  # (repos apt de Kali ya configurados dentro) y se nombra el contenedor
  # "kali" con -n/--override-alias, para que el resto del ecosistema
  # (proot-distro login kali, entorno.sh --diagnose, gui_start.sh --distro
  # kali) lo vea con el nombre esperado.
  step "PASO 7 — Contenedor Kali (kalilinux/kali-rolling vía proot-distro)"
  if check_done "kali_container"; then
    log "Contenedor Kali ya instalado [checkpoint]"
  else
    # BUG REAL confirmado por ADB en dispositivo (2026-08-26, ver docs/humano/humano226.md):
    # "proot-distro list-installed" YA NO EXISTE en proot-distro v5.8.0 (la que trae Termux
    # hoy) — devuelve "Error: unknown command 'list-installed'" a stderr, silenciado por el
    # "2>/dev/null" de abajo, así que este chequeo SIEMPRE daba falso (grep sin match sobre
    # stdin vacío) y el script reintentaba "proot-distro install" de cero en cada corrida
    # aunque el contenedor "kali" ya existiera. El comando real en esta versión es
    # "proot-distro list" (imprime "Installed containers:\n  * kali\n  * ubuntu...").
    if proot-distro list 2>/dev/null | grep -qw "$KALI_CONTAINER"; then
      log "Contenedor '$KALI_CONTAINER' ya existe"
    else
      info "Ejecutando: proot-distro install kalilinux/kali-rolling -n $KALI_CONTAINER"
      proot-distro install kalilinux/kali-rolling -n "$KALI_CONTAINER" || \
        error "No se pudo instalar el contenedor Kali (revisar red — la imagen pesa varios cientos de MB)"
      log "Contenedor '$KALI_CONTAINER' instalado"
    fi
    mark_done "kali_container"
  fi

  step "PASO 7b — kali-tools-top10 (metapaquete oficial, curado)"
  if check_done "kali_tools"; then
    log "kali-tools-top10 ya instalado [checkpoint]"
  else
    # BUG REAL confirmado por ADB en dispositivo (2026-08-26, ver docs/humano/humano226.md,
    # log real: install_ciberseguridad.log cortaba en seco justo después de "Reading package
    # lists..." de este paso, sin [OK]/[WARN]/[SEÑAL] — el proceso hijo murió sin que el script
    # llegara nunca al "registry_write ciberseguridad installed=true" del final, así que
    # ModuleInstalled/la UI reportaban "no disponible" para siempre pese a que el contenedor
    # Kali SÍ había quedado creado en disco (0.9GB confirmados con adb, kalilinux/kali-rolling
    # ya bajado). kali-tools-top10 arrastra paquetes muy pesados (metasploit-framework,
    # wireshark, etc.) — en una red lenta/inestable puede tardar mucho más de lo que el proceso
    # en background de Android sobrevive sin que el hijo sea matado. "timeout" acota el paso a
    # 15 minutos: si se cuelga, cae al "else" de abajo (ya diseñado como "no crítico") en vez de
    # dejar el script colgado indefinidamente sin llegar nunca al registry_write final.
    # BUG REAL confirmado por ADB en dispositivo 2026-08-27 (ver docs/humano256.md, mismo
    # reporte "error de Ciberseguridad con Kali" — la instalación de kali-tools-top10 fallaba
    # de forma INSTANTÁNEA, sin siquiera intentar bajar nada): un `proot`/`dpkg` de una corrida
    # ANTERIOR (killeada por Android en background, o por el `timeout 900` de abajo sin llegar a
    # matar la cadena completa de descendientes — mismo patrón raíz ya confirmado en
    # `docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md` § "Ronda 2026-08-27 R3" para
    # `killSessionProcessGroup()`: proot arma su propio árbol de procesos que un TERM/KILL al
    # padre no siempre alcanza) quedaba VIVO y colgado (`ps` real: `dpkg` en estado `S`
    # (`do_wait`) indefinido, PID sobreviviente reparentado, sosteniendo
    # `/var/lib/dpkg/lock-frontend` para siempre) — todo intento nuevo de apt-get fallaba al
    # toque con "E: Could not get lock /var/lib/dpkg/lock-frontend" / "dpkg was interrupted, you
    # must manually run 'dpkg --configure -a'", exactamente el corte "seco" después de "Reading
    # package lists..." que el usuario reportó. Se limpia CUALQUIER dpkg/proot huérfano de ESTE
    # contenedor antes de reintentar (`pkill -f` sobre el path real de bind del rootfs, mismo
    # patrón que `distroRootfsMarker` en `EntornoNative.kt::startDistroDesktop()` — si nada
    # quedó colgado son no-ops instantáneos) + `dpkg --configure -a` self-heal como primer paso
    # DENTRO del propio intento (repara la base de dpkg si algo la dejó a medio configurar).
    # Además `setsid` + `timeout -k 10` (en vez del `timeout 900` liso de antes): corre en su
    # propia sesión/grupo de procesos y manda SIGKILL 10s después del SIGTERM si el proceso
    # sigue vivo — reduce (no garantiza al 100%, proot puede dejar hijos ptraced huérfanos si
    # muere el propio proot supervisor) la chance de que ESTA corrida deje otro descendiente
    # colgado para la próxima vez.
    pkill -9 -f "proot-distro login $KALI_CONTAINER" 2>/dev/null
    pkill -9 -f "proot-distro/containers/$KALI_CONTAINER/" 2>/dev/null
    pkill -9 -f "proot-distro/installed-rootfs/$KALI_CONTAINER" 2>/dev/null
    if setsid timeout -k 10 900 proot-distro login "$KALI_CONTAINER" -- bash -c \
      'set -o pipefail; export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a 2>&1 | tail -20; apt-get update -y 2>&1 | tail -5; apt-get install -y kali-tools-top10 2>&1 | tail -10'; then
      log "kali-tools-top10 instalado en el contenedor '$KALI_CONTAINER'"
      _KALI_TOOLS="kali-tools-top10"
    else
      warn "kali-tools-top10 no se instaló completo (no crítico — el contenedor queda usable igual)"
      _KALI_TOOLS="kali-tools-top10-failed"
    fi
    mark_done "kali_tools"
  fi
  [ -z "${_KALI_TOOLS:-}" ] && _KALI_TOOLS="kali-tools-top10"
  _GUI_STATUS="headless"

  # ── PASO 8 — GUI dentro del contenedor (solo --variant pro-gui) ──
  # Reutiliza TAL CUAL ~/scripts/entorno/distro_setup_gui.sh (generado por
  # modulos/entorno.sh) en vez de reimplementar la instalación de
  # dbus-x11+xfce4 dentro de una distro proot — ver ese script para el
  # detalle real (detecta apt-get/dnf/pacman/apk, crea ~/.xsession). Si el
  # módulo Entorno todavía no corrió en este dispositivo (no existe el
  # script), se lo corre primero en modo --silent: es la MISMA lógica que ya
  # está probada para X11 embebido + proot-distro + los scripts gui_*, no
  # tiene sentido duplicarla acá.
  if $PRO_GUI; then
    step "PASO 8 — Interfaz gráfica dentro del contenedor Kali (xfce4)"
    if check_done "kali_gui"; then
      log "GUI de Kali ya configurada [checkpoint]"
    else
      ENTORNO_SCRIPT_DIR="$(dirname "${BASH_SOURCE[0]}")"
      DISTRO_SETUP_GUI="$TERMUX_HOME/scripts/entorno/distro_setup_gui.sh"
      if [ ! -x "$DISTRO_SETUP_GUI" ]; then
        info "Módulo Entorno no está provisto todavía — instalándolo primero (X11 embebido + scripts de gestión)..."
        bash "$ENTORNO_SCRIPT_DIR/entorno.sh" --silent || warn "entorno.sh terminó con errores — se intenta igual configurar la GUI de Kali"
      fi
      if [ -x "$DISTRO_SETUP_GUI" ]; then
        # BUG REAL corregido 2026-08-16 (mismo patrón que freebuff.sh): antes
        # se registraba "_GUI_STATUS=gui" (éxito) SIN importar si
        # distro_setup_gui.sh realmente instaló xfce4 — el script ya devuelve
        # un exit code funcional real (verifica startxfce4), así que ahora se
        # respeta ese resultado en vez de asumir éxito siempre.
        if bash "$DISTRO_SETUP_GUI" "$KALI_CONTAINER"; then
          log "GUI de Kali configurada — lanzar con: ~/scripts/entorno/gui_start.sh --distro $KALI_CONTAINER"
          _GUI_STATUS="gui"
        else
          warn "distro_setup_gui.sh no pudo terminar la instalación de la GUI — el contenedor queda headless (revisar log arriba)"
          _GUI_STATUS="headless"
        fi
      else
        warn "No se encontró $DISTRO_SETUP_GUI ni tras instalar Entorno — GUI de Kali NO configurada, el contenedor queda headless"
      fi
      mark_done "kali_gui"
    fi
  fi
fi

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_DATE=$(date +%Y-%m-%d)
_tools="nmap,netcat,dirb,nikto,theharvester,sqlmap"
if $PRO; then
  _tools="${_tools},proot-distro,kali(${_KALI_TOOLS}:${_GUI_STATUS})"
fi
registry_write ciberseguridad \
  "installed=true" \
  "tier=$([ "$PRO" = "true" ] && echo pro || echo basico)" \
  "kali_container=$([ "$PRO" = "true" ] && echo "$KALI_CONTAINER" || echo "")" \
  "kali_gui=$([ "$_GUI_STATUS" = "gui" ] && echo true || echo false)" \
  "tools=${_tools}" \
  "install_date=${_DATE}"

notify_event "ciberseguridad" "install_done" "$_tools"
log "Kit de ciberseguridad instalado correctamente (${_tools})"
rm -f "$CHECKPOINT"
exit 0
