#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · ciberseguridad.sh (silent mode)
#  Instala el kit de herramientas de red/OSINT en Termux ARM64 — 2 niveles
#
#  USO DESDE APP (KairosApp):
#    bash ciberseguridad.sh --silent                          (básico, default)
#    bash ciberseguridad.sh --silent --variant pro-headless    (básico + Kali sin GUI, top10)
#    bash ciberseguridad.sh --silent --variant pro-gui         (básico + Kali con GUI, top10)
#    bash ciberseguridad.sh --silent --variant pro-headless:web  (básico + Kali sin GUI,
#                                                                  metapaquete kali-tools-web)
#
#  FLAGS:
#    --silent          Sin preguntas, instala todo directo
#    --force           Reinstala aunque ya esté
#    --variant <tipo>[:<categoria>]  basico (default) | pro-headless | pro-gui — opcionalmente
#                      con ":<categoria>" para elegir el metapaquete Kali (ver PASO 7b y
#                      KALI_CATEGORY_CATALOG más abajo; default "top10" si se omite)
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
#    ✅ Metapaquete Kali elegido por categoría — kali-tools-top10 por default (mismo
#       criterio de alcance responsable que el nivel básico), o cualquiera de los 12
#       perfiles/categorías oficiales de Kali vía --variant pro-*:<categoria> (ver PASO 7b
#       y KALI_CATEGORY_CATALOG — hallazgo de referencia proot-distro-nethunter/BUILD_NH(),
#       docs/referencias/ciberseguridad/AUDITORIA_KALI_GUI_REPOS_2026-09-08.md punto 1)
#    ✅ --variant pro-gui: además dbus-x11 + xfce4 DENTRO del contenedor,
#       reutilizando ~/scripts/entorno/distro_setup_gui.sh TAL CUAL (mismo
#       script que genera modulos/entorno.sh para cualquier distro proot) —
#       si el módulo Entorno no está instalado todavía, este script lo corre
#       primero (--silent) para tener el X11 embebido + esos scripts de
#       gestión, en vez de reimplementar esa lógica acá
#
#  ALCANCE (uso responsable):
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
#  VERSIÓN: 2.3.0 | Septiembre 2026 (hallazgos de referencia real, ver
#  docs/referencias/ciberseguridad/AUDITORIA_KALI_GUI_REPOS_2026-09-08.md — permiso explícito del
#  usuario; VNC queda fuera de alcance para Kali/Ciberseguridad esta ronda, reservado solo
#  para QEMU y Mini PC):
#  1) catálogo de 13 metapaquetes Kali por categoría en vez de kali-tools-top10 fijo (PASO 7b,
#  --variant pro-*:<categoria>, hallazgo de proot-distro-nethunter/BUILD_NH()); 2) workaround
#  defensivo del hang de udisks2 en proot (PASO 7b, echo vacío en su postinst antes del
#  apt-get install, hallazgo de kali-proot/proot-distro-kali — no confirmado empíricamente en
#  dispositivo esta ronda, ver comentario del PASO 7b). La GPU Mesa/Zink/VirGL y el dock Plank
#  (otros 2 hallazgos de la misma auditoría) NO se tocan acá — PASO 8 reutiliza
#  ~/scripts/entorno/distro_setup_gui.sh de modulos/entorno.sh TAL CUAL (confirmado leyendo este
#  mismo archivo), así que la GPU y cualquier autostart de escritorio son responsabilidad de
#  entorno.sh, no de este script — duplicarlo acá violaría DRY.
#
#  Historial previo — v2.2.0 | Agosto 2026 (agrega nivel "pro": Kali Linux vía proot-distro
#  con imagen oficial Docker Hub + variante GUI reutilizando entorno.sh — pedido
#  "ampliar ciberseguridad a 2 niveles, básico y pro con Kali" | v1.2.0 amplió
#  netcat/dirb/nikto + sqlmap | fix PASO 4 theHarvester (dependencias reales
#  de playwright) | v2.1.0 (2026-08-17): root cause real de "dirb: no · nikto: no" en
#  dispositivo — nikto NUNCA fue un paquete de Termux, y al ir en la misma línea
#  `pkg install` que dirb, apt fallaba la resolución completa y se llevaba a dirb
#  con él; se separan y nikto pasa a git clone+perl. theHarvester: playwright no
#  tiene wheel para Bionic libc en NINGUNA versión (investigado en 6+ tags) —
#  fix real con --no-deps + deps reales + stub local de playwright, y ya no
#  aborta el script completo (para que sqlmap se siga intentando igual) | v2.2.0
#  (2026-09-08): root cause real de "se instala la básica y a la hora sale la
#  pro, muchas veces no se instala Kali/GUI" confirmado por ADB (registry sin
#  ninguna entrada ciberseguridad.*, log/checkpoint mostrando un intento
#  interrumpido a mitad de camino) — el ÚNICO registry_write vivía al final del
#  script, así que CUALQUIER corte (error() duro o el proceso matado) antes de
#  llegar ahí perdía hasta el nivel básico ya instalado. Se agregan registry_write
#  tempranos (básico tras PASO 5c, pro-headless tras confirmar el contenedor) +
#  el paso más frágil (PASO 7, descarga de la imagen Kali) pasa de error() duro a
#  reintento (2 intentos) + warn() no-fatal, dejando el script llegar siempre al
#  final en vez de abortar todo por un corte de red
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

# ── Categoría de metapaquete Kali, codificada dentro de --variant ─────
# Hallazgo de referencia (proot-distro-nethunter/BUILD_NH(), ver docs/referencias/
# ciberseguridad/AUDITORIA_KALI_GUI_REPOS_2026-09-08.md punto 1): antes PASO 7b siempre
# instalaba kali-tools-top10 fijo. Ahora --variant acepta un sufijo opcional
# "<tier>:<categoria>" (ej. "pro-headless:web") — se separa acá, ANTES de la normalización
# tier/GUI de más abajo, para que VARIANT quede con el valor limpio de siempre
# (basico/pro-headless/pro-gui) y el resto del script no necesite saber de esto. El sistema
# de variantes en sí (--describe, variant_required) NO cambia — la categoría es un
# parámetro DENTRO de la variante "pro-*", no una variante nueva (ver CiberseguridadFragment.kt
# para el motivo de codificarlo así: ModuleController.installModule() solo reenvía
# --variant/--force/--silent, no hay canal genérico de flags extra del lado app→script).
KALI_CATEGORY="${VARIANT#*:}"
if [ "$KALI_CATEGORY" = "$VARIANT" ]; then
  KALI_CATEGORY="top10"
else
  VARIANT="${VARIANT%%:*}"
fi
[ -z "$KALI_CATEGORY" ] && KALI_CATEGORY="top10"

# Catálogo real de 13 perfiles/metapaquetes oficiales de Kali (los mismos que expone
# proot-distro-nethunter/BUILD_NH()) — categoría desconocida cae a "top10" (mismo
# comportamiento que antes de este cambio, nunca rompe una llamada --variant vieja sin ":").
kali_metapackage_for_category() {
  case "$1" in
    top10)                echo "kali-tools-top10" ;;
    default)               echo "kali-linux-default" ;;
    large)                  echo "kali-linux-large" ;;
    everything)              echo "kali-linux-everything" ;;
    info-gathering)          echo "kali-tools-information-gathering" ;;
    web)                     echo "kali-tools-web" ;;
    crypto-stego)            echo "kali-tools-crypto-stego" ;;
    passwords)               echo "kali-tools-passwords" ;;
    forensics)               echo "kali-tools-forensics" ;;
    fuzzing)                 echo "kali-tools-fuzzing" ;;
    reverse-engineering)     echo "kali-tools-reverse-engineering" ;;
    sniffing-spoofing)       echo "kali-tools-sniffing-spoofing" ;;
    exploitation)            echo "kali-tools-exploitation" ;;
    *)                       echo "kali-tools-top10" ;;
  esac
}
KALI_METAPACKAGE="$(kali_metapackage_for_category "$KALI_CATEGORY")"

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"ciberseguridad","supports_silent":true,"supports_force":true,"variants":["basico","pro-headless","pro-gui"],"variant_required":false,"variant_default":"basico","note":"basico = nmap+netcat+dirb+nikto+theHarvester+sqlmap (bionic nativo). pro-headless/pro-gui = basico + contenedor Kali Linux via proot-distro (imagen oficial kalilinux/kali-rolling); pro-gui ademas instala xfce4+dbus-x11 dentro del contenedor reutilizando los scripts de modulos/entorno.sh. El metapaquete Kali a instalar se elige con un sufijo opcional en --variant, \"pro-headless:<categoria>\" o \"pro-gui:<categoria>\" (default top10 si se omite) -- categorias: top10, default, large, everything, info-gathering, web, crypto-stego, passwords, forensics, fuzzing, reverse-engineering, sniffing-spoofing, exploitation"}
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
#
# La variante real se arma leyendo "ciberseguridad.tier" (basico|pro) +
# "ciberseguridad.kali_gui" (true|false) del registry (ambos escritos por el
# registry_write final más abajo) y mapeándolos a los mismos ids que usa la UI
# (BottomSheetInstalacion.kt::getVariantId()): basico | pro-headless | pro-gui
# — en vez de hardcodear "basico" siempre. El resto de este manifest sigue
# cubriendo solo nikto (ver not_covered) sin importar qué tier salga; si el
# tier real es pro-*, moduledeb pack igual empaqueta nikto+dependencias —
# solo el nombre del .deb de salida cambia (2026-09-11, ver
# MEJORAS_PENDIENTES.md "moduledeb: variant en nombre de .deb").
if $DESCRIBE_FILES; then
  _df_registry="$HOME/.android_server_registry"
  _df_tier=$(grep -m1 '^ciberseguridad\.tier=' "$_df_registry" 2>/dev/null | cut -d= -f2 | tr -d '\r\n')
  _df_gui=$(grep -m1 '^ciberseguridad\.kali_gui=' "$_df_registry" 2>/dev/null | cut -d= -f2 | tr -d '\r\n')
  if [ "$_df_tier" = "pro" ]; then
    [ "$_df_gui" = "true" ] && _df_variant="pro-gui" || _df_variant="pro-headless"
  else
    _df_variant="basico"
  fi
  jq -n \
    --arg variant "$_df_variant" \
    --arg p2 "$TERMUX_PREFIX/bin/nikto" \
    --arg glob "$HOME/.nikto/**" \
    --arg verify "\"$TERMUX_PREFIX/bin/nikto\" -Version >/dev/null 2>&1" \
    --arg patch "chmod +x \"$TERMUX_PREFIX/bin/nikto\" 2>/dev/null || true" \
    '{
      id: "ciberseguridad",
      supports_describe_files: true,
      variant: $variant,
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
# Default false — solo se pone true dentro de "if $PRO" (PASO 7) si el contenedor Kali
# realmente quedó instalado (existía o el install/reintento tuvo éxito). Declarado acá arriba
# (no solo dentro del bloque $PRO) para que el tier efectivo de la sección "Registry" final
# pueda referenciarlo sin importar la variante elegida — ver nota del registry temprano más
# abajo (PASO 5c) y del registry intermedio (PASO 7) para el motivo real de este cambio.
KALI_CONTAINER_OK=false

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
  pkg install -y nmap || error "No se pudo instalar nmap"
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
  pkg install -y netcat-openbsd dirb || warn "Algún paquete de red no se instaló (no crítico)"
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
  command -v perl &>/dev/null || { pkg_update_with_fallback; pkg install -y perl; }
  if ! command -v perl &>/dev/null; then
    warn "No se pudo instalar perl — nikto no disponible (no crítico)"
  else
    NIKTO_DIR="$HOME/.nikto"
    if [ -d "$NIKTO_DIR/.git" ]; then
      log "Repo de nikto ya clonado"
    else
      rm -rf "$NIKTO_DIR"
      git clone --depth 1 https://github.com/sullo/nikto.git "$NIKTO_DIR" || warn "git clone de nikto falló (no crítico)"
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
      PERL_MM_USE_DEFAULT=1 timeout 90 cpan -T XML::Writer || \
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
    pkg install python -y || error "No se pudo instalar Python"
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
    pkg install -y git
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
    # pip_install() en vez de "$PIP_PYTHON" -m pip install directo (lib.sh, 2026-08-28):
    # serializa con flock contra cualquier OTRO módulo instalando por pip
    # al mismo tiempo — confirmado en dispositivo que mistralvibe.sh y n8n.sh fallaron
    # mientras este PASO 4 corría en paralelo.
    pip_install "$PIP_PYTHON" $TH_DEPS
    info "Ejecutando: $PIP_PYTHON -m pip install --no-deps git+https://github.com/laramies/theHarvester.git"
    pip_install "$PIP_PYTHON" --no-deps "git+https://github.com/laramies/theHarvester.git"
    if [ $? -ne 0 ]; then
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
      # Chequeo funcional real, no solo "existe en PATH" — ver
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
  pip_install "$PIP_PYTHON2" sqlmap
  if [ $? -ne 0 ]; then
    warn "pip install sqlmap falló (no crítico)"
  # Chequeo funcional real, no solo "existe en PATH" — ver
  # verify_binary_installed() en lib.sh.
  elif verify_binary_installed sqlmap; then
    log "sqlmap instalado: $(sqlmap --version 2>/dev/null)"
    mark_done "sqlmap"
  else
    warn "sqlmap no ejecuta tras la instalación (no crítico, revisá manualmente: sqlmap --version)"
  fi
fi

# ── PASO 5b — MVT / Mobile Verification Toolkit (autodefensa, pip) ──
# Herramienta real de Amnesty International (paquete PyPI "mvt",
# github.com/mvt-project/mvt) — forense de spyware/stalkerware (detecta IOCs
# tipo Pegasus) en el PROPIO dispositivo, vía ADB sin root o análisis de un
# backup — distinta de todo lo demás del script (nmap/theHarvester/nikto/
# dirb/sqlmap apuntan hacia afuera, MVT apunta hacia adentro: protege el
# propio teléfono del usuario). Hallazgo de la auditoría de
# referencia/ciberseguridad/i-Haklab-master, ver docs/referencias/
# ciberseguridad/REFERENCIA_IHAKLAB.md. Es Python puro (Click CLI) — instala
# limpio con pip, sin dependencias nativas pesadas.
# El CLI real no expone un flag "--version" a nivel raíz (es un grupo de
# subcomandos Click: check-adb/check-androidqf/check-backup/download-apks/
# version) — se verifica con el subcomando "version" en vez del flag default
# de verify_binary_installed().
step "PASO 5b — Instalando MVT (Mobile Verification Toolkit, forense de spyware)"
if check_done "mvt"; then
  log "MVT ya instalado [checkpoint]"
else
  PIP_PYTHON3=$(command -v python 2>/dev/null || command -v python3 2>/dev/null)
  info "Ejecutando: $PIP_PYTHON3 -m pip install mvt"
  pip_install "$PIP_PYTHON3" mvt
  if [ $? -ne 0 ]; then
    warn "pip install mvt falló (no crítico)"
  elif verify_binary_installed mvt-android version; then
    log "MVT instalado: $(mvt-android version 2>/dev/null | head -1)"
    mark_done "mvt"
  else
    warn "MVT no ejecuta tras la instalación (no crítico, revisá manualmente: mvt-android version)"
  fi
fi

# ── PASO 5c — ClamAV (autodefensa, pkg) ──────────────────────
# Antivirus open-source real (pkg oficial de Termux) — escaneo de archivos/
# descargas del propio dispositivo, misma categoría "autodefensa" que MVT
# (ver PASO 5b). Hallazgo de la misma auditoría de i-Haklab-master.
# freshclam (actualización de la base de firmas) es best-effort con timeout:
# clamscan funciona igual con la base que trae el paquete si freshclam no
# llega a completar por red lenta — mismo criterio "no crítico" que el resto
# de pasos best-effort de este script (XML::Writer de nikto, PASO 2b).
step "PASO 5c — Instalando ClamAV (antivirus, escaneo de archivos del dispositivo)"
if check_done "clamav"; then
  log "ClamAV ya instalado [checkpoint]"
else
  pkg_update_with_fallback
  pkg install -y clamav || warn "No se pudo instalar clamav (no crítico)"
  if verify_binary_installed clamscan; then
    log "ClamAV instalado: $(clamscan --version 2>/dev/null | head -1)"
    info "Actualizando base de firmas (freshclam, best-effort, hasta 90s)..."
    timeout 90 freshclam 2>&1 | tail -5 || warn "freshclam no pudo actualizar la base de firmas ahora — clamscan funciona igual con la base que trae el paquete, correr 'freshclam' manualmente más tarde (no crítico)"
    mark_done "clamav"
  else
    warn "clamav no ejecuta tras la instalación (no crítico, revisá manualmente: clamscan --version)"
  fi
fi

# ── Registry temprano (nivel básico) ──────────────────────────
# BUG REAL confirmado por ADB en dispositivo (2026-09-08, reporte textual del usuario:
# "cuando la primera vez ponen la opcion pro/completa, se instala la basica y a la hora es que
# sale la pro [...] muchas veces no se instala la distro kali, tampoco la interfaz grafica"):
# antes el ÚNICO registry_write de todo el script vivía al
# final (sección "Registry" más abajo), DESPUÉS de los pasos de Kali (PASO 6-8). Evidencia
# real de un intento fallido en el dispositivo (~/.android_server_registry SIN ninguna
# entrada "ciberseguridad.*", ~/.install_ciberseguridad_checkpoint con nmap/pkg_extra/nikto/
# python en "done" pero theharvester/sqlmap sin terminar, y install_ciberseguridad.log
# cortado en seco en "PASO 5 — sqlmap" sin seguir nunca): cualquier corte del script (un
# error() duro — exit 1, ver lib.sh — o el proceso interrumpido/matado a mitad de instalar)
# ANTES de llegar al final pierde el registro de TODO, incluido el nivel básico que sí había
# terminado de instalar bien. El fallback por binario de la UI
# (ModuleInstalled.BINARY_FALLBACK["ciberseguridad"] = "nmap") seguía mostrando el módulo
# como "instalado" igual (nmap ya está en PATH desde PASO 1) pero sin NINGÚN dato real de
# tier/kali en el registry — de ahí la confusión reportada. Este write temprano deja un
# registro durable de que básico terminó, independiente de lo que pase después con Kali (que
# es, con diferencia, el paso más largo/frágil de todo el módulo — descarga de una imagen
# Docker de varios cientos de MB). El write final de la sección "Registry" lo sobreescribe
# con tier=pro si Kali termina bien (registry_write() reemplaza el bloque completo del id,
# no acumula — ver lib.sh).
registry_write ciberseguridad \
  "installed=true" \
  "tier=basico" \
  "kali_container=" \
  "kali_gui=false" \
  "tools=nmap,netcat,dirb,nikto,theharvester,sqlmap,mvt,clamav" \
  "install_date=$(date +%Y-%m-%d)"

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
      pkg install -y proot-distro || error "No se pudo instalar proot-distro"
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
  # KALI_CONTAINER_OK gatea PASO 7b/8 y el tier final escrito en la sección "Registry" — ver
  # nota del registry temprano más arriba. Antes este paso usaba error() (exit 1 duro, ver
  # lib.sh) ante CUALQUIER fallo — como es la descarga más pesada/frágil de todo el módulo
  # (imagen oficial kalilinux/kali-rolling, varios cientos de MB), un solo corte de red se
  # llevaba puesto el script ENTERO, sin dejar ni siquiera el nivel básico registrado (ver
  # bug real confirmado por ADB, nota de arriba). Ahora es "no crítico": reintenta una vez
  # y, si sigue fallando, sigue de largo con básico (ya registrado arriba) en vez de abortar.
  KALI_CONTAINER_OK=false
  if check_done "kali_container"; then
    log "Contenedor Kali ya instalado [checkpoint]"
    KALI_CONTAINER_OK=true
  else
    # BUG REAL confirmado por ADB en dispositivo (2026-08-26):
    # "proot-distro list-installed" YA NO EXISTE en proot-distro v5.8.0 (la que trae Termux
    # hoy) — devuelve "Error: unknown command 'list-installed'" a stderr, silenciado por el
    # "2>/dev/null" de abajo, así que este chequeo SIEMPRE daba falso (grep sin match sobre
    # stdin vacío) y el script reintentaba "proot-distro install" de cero en cada corrida
    # aunque el contenedor "kali" ya existiera. El comando real en esta versión es
    # "proot-distro list" (imprime "Installed containers:\n  * kali\n  * ubuntu...").
    if proot-distro list 2>/dev/null | grep -qw "$KALI_CONTAINER"; then
      log "Contenedor '$KALI_CONTAINER' ya existe"
      KALI_CONTAINER_OK=true
    else
      # Hasta 2 intentos reales — una descarga interrumpida a mitad de camino no deja nada
      # resumible (proot-distro no soporta reanudar), así que reintentar es un install limpio
      # de nuevo, no un resume parcial. Best-effort: si los 2 fallan, warn() (no error()) y el
      # script sigue de largo — básico queda instalado igual, el usuario puede reintentar
      # "Instalar nivel Pro" más tarde sin perder nada de lo que ya funciona.
      _KALI_INSTALL_OK=false
      for _attempt in 1 2; do
        info "Ejecutando (intento $_attempt/2): proot-distro install kalilinux/kali-rolling -n $KALI_CONTAINER"
        if proot-distro install kalilinux/kali-rolling -n "$KALI_CONTAINER"; then
          _KALI_INSTALL_OK=true
          break
        fi
        warn "Intento $_attempt de instalar el contenedor Kali falló"
        [ "$_attempt" = "1" ] && sleep 5
      done
      if $_KALI_INSTALL_OK; then
        log "Contenedor '$KALI_CONTAINER' instalado"
        KALI_CONTAINER_OK=true
      else
        warn "No se pudo instalar el contenedor Kali tras 2 intentos (revisar red — la imagen pesa varios cientos de MB, no crítico) — el nivel básico queda instalado igual, se puede reintentar Pro más tarde"
      fi
    fi
    $KALI_CONTAINER_OK && mark_done "kali_container"
  fi

  # ── Registry intermedio (contenedor Kali confirmado, sin GUI/tools todavía) ──
  # Mismo motivo que el registry temprano de básico (ver nota más arriba): PASO 7b (instalar
  # $KALI_METAPACKAGE) YA tenía un bug real documentado (ver comentario debajo) de
  # morir sin llegar al registry_write final pese a que el contenedor SÍ había quedado creado
  # — este write intermedio deja "tier=pro" (headless) registrado apenas el contenedor existe,
  # antes de arriesgar el paso más pesado/lento ($KALI_METAPACKAGE, hasta 900s). El write final
  # de la sección "Registry" sobreescribe con el estado real de tools/GUI si todo termina bien.
  if $KALI_CONTAINER_OK; then
    registry_write ciberseguridad \
      "installed=true" \
      "tier=pro" \
      "kali_container=$KALI_CONTAINER" \
      "kali_gui=false" \
      "tools=nmap,netcat,dirb,nikto,theharvester,sqlmap,mvt,clamav,proot-distro,kali(pending:headless)" \
      "install_date=$(date +%Y-%m-%d)"
  fi

  # Checkpoint por categoría (2026-09-09, ver KDoc del header y KALI_CATEGORY más arriba): antes
  # la clave era fija ("kali_tools"), así que instalar una categoría distinta en una corrida
  # posterior (ej. primero "top10", después "web") quedaba silenciosamente saltada por
  # check_done() — con "kali_tools_$KALI_CATEGORY" cada categoría se instala/trackea por
  # separado (additivo: apt no desinstala nada de una categoría previa, solo agrega la nueva).
  step "PASO 7b — $KALI_METAPACKAGE (categoría: $KALI_CATEGORY)"
  if ! $KALI_CONTAINER_OK; then
    warn "Contenedor Kali no disponible — se omite $KALI_METAPACKAGE (no crítico)"
  elif check_done "kali_tools_$KALI_CATEGORY"; then
    log "$KALI_METAPACKAGE ya instalado [checkpoint]"
  else
    # BUG REAL confirmado por ADB en dispositivo (2026-08-26,
    # log real: install_ciberseguridad.log cortaba en seco justo después de "Reading package
    # lists..." de este paso, sin [OK]/[WARN]/[SEÑAL] — el proceso hijo murió sin que el script
    # llegara nunca al "registry_write ciberseguridad installed=true" del final, así que
    # ModuleInstalled/la UI reportaban "no disponible" para siempre pese a que el contenedor
    # Kali SÍ había quedado creado en disco (0.9GB confirmados con adb, kalilinux/kali-rolling
    # ya bajado). kali-tools-top10 (y el resto de metapaquetes del catálogo, ver más arriba)
    # arrastra paquetes muy pesados (metasploit-framework, wireshark, etc.) — en una red
    # lenta/inestable puede tardar mucho más de lo que el proceso en background de Android
    # sobrevive sin que el hijo sea matado. "timeout" acota el paso a 15 minutos: si se cuelga,
    # cae al "else" de abajo (ya diseñado como "no crítico") en vez de dejar el script colgado
    # indefinidamente sin llegar nunca al registry_write final.
    # BUG REAL confirmado por ADB en dispositivo 2026-08-27 (mismo
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
    #
    # Workaround del hang de udisks2 en proot (2026-09-09, hallazgo de referencia real
    # kali-proot/proot-distro-kali, ver docs/referencias/ciberseguridad/
    # AUDITORIA_KALI_GUI_REPOS_2026-09-08.md punto 4): udisks2 puede llegar como dependencia
    # transitiva de cualquiera de los metapaquetes del catálogo — su postinst intenta hablar con
    # polkit/dbus del sistema, que no corre completo dentro de proot, y puede colgar la
    # instalación. NO CONFIRMADO EMPÍRICAMENTE en este dispositivo esta ronda —
    # se aplica igual de forma DEFENSIVA
    # (echo vacío en su postinst antes de que apt-get lo procese): si udisks2 nunca termina
    # siendo dependencia de la categoría elegida, esto es un no-op inofensivo (crea un archivo
    # que nadie lee); si SÍ lo es, evita que su postinst cuelgue el resto de la instalación.
    # Workaround del cuelgue REAL de systemd/cron-daemon-common en proot (2026-09-11,
    # confirmado por ADB en dispositivo real — a diferencia del de
    # udisks2 de arriba, este SÍ se reprodujo y confirmó empíricamente en este dispositivo):
    # "apt-get install kali-tools-top10" arrastra systemd como dependencia transitiva; su
    # postinst falla siempre bajo proot con "Failed to enable units: Protocol driver not
    # attached." / "Cannot open '/etc/machine-id': Protocol driver not attached" (proot no
    # emula los sockets/syscalls que systemd real necesita — no es un problema de permisos ni
    # de red, es una limitación estructural de proot, sin fix posible del lado de systemd).
    # Ese fallo deja dpkg con systemd "unconfigured", lo que en cascada bloquea
    # cron-daemon-common (dependency problems) y CUALQUIER apt-get posterior en el mismo
    # contenedor — incluyendo PASO 8 (GUI/xfce4) más abajo, que fallaba SIEMPRE aunque no
    # tuviera nada que ver con Kali/GUI en sí. Igual que udisks2: un contenedor proot-distro
    # NUNCA corre systemd como PID1 real (usa su propio proot-distro login), así que
    # "configurar" systemd sin ejecutar su postinst es seguro acá — se stubea (exit 0) en vez
    # de dejar que falle. cron-daemon-common también se stubea porque SU postinst (que solo
    # corre si systemd terminó de "configurarse") intenta leer un .conf de systemd-sysusers
    # que tampoco existe en este entorno ("Failed to read 'cron-daemon-common.conf'"),
    # confirmado con la misma prueba en vivo. Verificado con "dpkg --configure -a" real: sin
    # estos 2 stubs, exit code != 0 con los 2 paquetes sin configurar; con ambos, exit 0 limpio.
    _SYSTEMD_WORKAROUND='mkdir -p /var/lib/dpkg/info; echo "exit 0" > /var/lib/dpkg/info/systemd.postinst; echo "exit 0" > /var/lib/dpkg/info/cron-daemon-common.postinst;'
    _UDISKS2_WORKAROUND='mkdir -p /var/lib/dpkg/info; [ -f /var/lib/dpkg/info/udisks2.postinst ] || echo "" > /var/lib/dpkg/info/udisks2.postinst;'
    pkill -9 -f "proot-distro login $KALI_CONTAINER" 2>/dev/null
    pkill -9 -f "proot-distro/containers/$KALI_CONTAINER/" 2>/dev/null
    pkill -9 -f "proot-distro/installed-rootfs/$KALI_CONTAINER" 2>/dev/null
    if setsid timeout -k 10 900 proot-distro login "$KALI_CONTAINER" -- bash -c \
      "set -o pipefail; export DEBIAN_FRONTEND=noninteractive; $_SYSTEMD_WORKAROUND dpkg --configure -a; $_UDISKS2_WORKAROUND apt-get update -y; apt-get install -y $KALI_METAPACKAGE"; then
      log "$KALI_METAPACKAGE instalado en el contenedor '$KALI_CONTAINER'"
      _KALI_TOOLS="$KALI_METAPACKAGE"
    else
      warn "$KALI_METAPACKAGE no se instaló completo (no crítico — el contenedor queda usable igual)"
      _KALI_TOOLS="$KALI_METAPACKAGE-failed"
    fi
    mark_done "kali_tools_$KALI_CATEGORY"
  fi
  [ -z "${_KALI_TOOLS:-}" ] && _KALI_TOOLS="$KALI_METAPACKAGE"
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
  if $PRO_GUI && ! $KALI_CONTAINER_OK; then
    warn "Contenedor Kali no disponible — se omite la GUI (no crítico)"
  elif $PRO_GUI; then
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
# Tier efectivo (2026-09-08, ver notas del registry temprano/intermedio más arriba): antes
# esto era "pro" con solo pedir --variant pro-*, sin importar si el contenedor Kali realmente
# llegó a instalarse — con KALI_CONTAINER_OK (PASO 7) el tier final refleja lo que de verdad
# quedó funcionando, no solo lo que el usuario pidió.
step "FINALIZANDO"
_DATE=$(date +%Y-%m-%d)
_tools="nmap,netcat,dirb,nikto,theharvester,sqlmap,mvt,clamav"
_EFFECTIVE_PRO=false
if $PRO && $KALI_CONTAINER_OK; then
  _EFFECTIVE_PRO=true
  _tools="${_tools},proot-distro,kali(${_KALI_TOOLS}:${_GUI_STATUS})"
elif $PRO; then
  warn "Nivel Pro pedido pero el contenedor Kali no quedó instalado — se registra como básico (reintentar 'Instalar nivel Pro' más tarde)"
fi
registry_write ciberseguridad \
  "installed=true" \
  "tier=$([ "$_EFFECTIVE_PRO" = "true" ] && echo pro || echo basico)" \
  "kali_container=$([ "$_EFFECTIVE_PRO" = "true" ] && echo "$KALI_CONTAINER" || echo "")" \
  "kali_gui=$([ "$_GUI_STATUS" = "gui" ] && echo true || echo false)" \
  "tools=${_tools}" \
  "install_date=${_DATE}"

notify_event "ciberseguridad" "install_done" "$_tools"
log "Kit de ciberseguridad instalado correctamente (${_tools})"
rm -f "$CHECKPOINT"
exit 0
