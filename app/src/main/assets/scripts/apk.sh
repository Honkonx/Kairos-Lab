#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  kairos-app · apk.sh (silent mode)
#  Compilador de APK en el dispositivo — sin Android Studio.
#  Puerta de entrada: `compil-apk-termux <proyecto>`.
#
#  FUENTE: i-Haklab / termux-apk-make (referencia/herramientas/
#  termux-apk-make-main + referencia/ciberseguridad/i-Haklab-master).
#  El README de termux-apk-make describe la cadena exacta que replica:
#  aapt2 (recursos) → javac (código) → d8 (DEX) → empaquetado →
#  zipalign (alineación) → apksigner (firma). Se implementa aquí
#  adaptado al contrato de scripts de KairosApp (lib.sh, registry,
#  checkpoints) y a los paquetes nativos aarch64 de Termux.
#  Nota de atribución (auditoría 2026-08-13, humano107, ver
#  docs/referencias/REFERENCIA_TERMUX_APK_MAKE.md): el mirror
#  Sable/android-platforms para android.jar NO está documentado en el
#  README de termux-apk-make (que ni siquiera trae un script real, solo
#  delega la instalación al repo apt de i-Haklab) — es una decisión
#  propia de esta implementación, no algo copiado de esa fuente.
#
#  USO DESDE APP (KairosApp):
#    bash apk.sh --silent
#    bash apk.sh --silent --force
#
#  USO MANUAL (standalone):
#    bash apk.sh
#
#  FLAGS:
#    --silent   Sin preguntas, instala todo directo
#    --force    Reinstala aunque ya esté
#
#  QUÉ INSTALA:
#    ✅ aapt2 (paquete nativo aarch64 de Termux) — compila recursos
#    ✅ openjdk-17 — javac/java (compila .java y firma con apksigner)
#    ✅ d8 — convierte .class a classes.dex (DEX de Android)
#    ✅ zipalign — alineación del APK (requerida por ART en Android 11+)
#    ✅ apksigner — firma v2/v3 del APK
#    ✅ unzip/zip/wget — utilidades de empaquetado y descarga
#    ✅ android.jar (API 30, mirror Sable/android-platforms) — para el
#       link de aapt2 y el classpath de javac. Se guarda una sola vez en
#       $PREFIX/share/android-platform/android.jar.
#    ✅ Wrapper `compil-apk-termux` en $PREFIX/bin (comando i-Haklab)
#    ✅ Keystore por defecto key.keystore (password "password") si no existe
#    ✅ Registry actualizado (apk.*)
#
#  ALCANCE REAL (2026-08-26, ver docs/arquitectura/PROPUESTA_APK_MULTILENGUAJE_2026-08-26.md):
#    Compila: Java + Kotlin (JVM puro, cadena aapt2 → [kotlinc →] javac → d8 →
#    empaquetar → zipalign → apksigner, sin Gradle). Kotlin requiere el módulo
#    "kotlin" instalado aparte (kotlin.sh, binario kotlinc) — se detecta y avisa
#    en tiempo de build si falta, no es una dependencia dura de este módulo.
#    NO compila: React Native/JS (necesita Gradle real con autolinking, no
#    viable sin PC — ver el doc de propuesta), C/C++ nativo vía CMake/NDK
#    (esfuerzo prohibitivo en el dispositivo — el NDK real de Android, no el
#    toolchain nativo de Termux, es un proyecto aparte). Para proyectos con
#    librerías nativas: colocá los .so ya compilados en lib/ del proyecto y
#    compil-apk-termux los empaqueta tal cual (no los compila).
#
#  CLI RESULTANTE:
#    compil-apk-termux build <proyecto>      → build/final.apk (default)
#       Proyecto = AndroidManifest.xml + src/ (*.java) + res/ (opcional)
#       Funciona en ~/storage/downloads, sus subcarpetas o ~/proyectos
#    compil-apk-termux info <apk>            → paquete/versión/label/firma
#    compil-apk-termux merge <base.apk> <partes...> → split APKs fusionados
#    compil-apk-termux decode <apk> <dir>    → extrae + manifest legible
#
#  NIVEL 2 (C5, humano123): parser de salida del compilador portado a
#  bash del CompilerOutputParser de CodeAssist (formato GNU
#  archivo:linea:col: error|warning:, ver referencia/ides/CodeAssist-main/
#  build-engine/.../CompilerOutputParser.kt) + subcomandos info/merge/decode.
#  - info  = aapt2 dump badging + apksigner verify + resumen de contenido
#  - merge = equivalente funcional del merge de APKEditor (referencia/
#            herramientas/APKEditor-master) usando unzip/zip/zipalign/
#            apksigner — no requiere Java/Gradle en el dispositivo
#  - decode = extracción del APK + AndroidManifest legible (aapt2 xmltree);
#             smali/decompile completo necesita pkg install apktool
#
#  INSTRUMENTACIÓN SIN ROOT (2026-09-12, ver docs/humano331.md — fuente
#  VictorH028/no-root-logger, https://github.com/VictorH028/no-root-logger,
#  clonado a ver/no-root-logger para la auditoría): subcomandos
#  instrument-decode/instrument-search/instrument-methods/instrument-apply +
#  logstart/logstop/logstatus/logtail. Decompila CUALQUIER APK con apktool
#  (instalado en el primer uso, no es dependencia dura del módulo), inyecta
#  llamadas a un logger remoto (RemoteLogger.hookEnter/hookExit/d) en el
#  método elegido vía smali_hook.py (parser Smali real, portado tal cual —
#  ver modulos/smali_hook.py), recompila con apktool, agrega RemoteLogger +
#  liblogger.so como dex/lib ADICIONALES al APK ya recompilado (evita traducir
#  RemoteLogger a mano a .smali) y vuelve a firmar. El binario nativo
#  (native_logger.c → liblogger.so) compila con el clang de Termux sin NDK —
#  su target por defecto YA es aarch64-unknown-linux-android (Bionic real,
#  confirmado en dispositivo). El log llega por HTTP a 127.0.0.1:9999
#  (log_server.py, en una sesión tmux — mismo patrón que los módulos con
#  servidor propio). UI real en ApkFragment.kt, card "INSTRUMENTACIÓN".
#
#  REPO: https://github.com/Honkonx/kairos-lab
#  VERSIÓN: 2.2.0 | Septiembre 2026 (instrumentación sin root, ver arriba)
#  VERSIÓN previa: 2.1.0 | Agosto 2026 (pedido 2026-08-13: "el compilador apk
#  de i-haklab... compilar apk dentro de download o en sus sub carpetas
#  o en la carpeta home/proyectos" — ver humano102; nivel 2 = C5 humano123)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"

# ── Parsear flags ─────────────────────────────────────────────
SILENT=false
FORCE=false
DESCRIBE=false
DESCRIBE_FILES=false

while [ $# -gt 0 ]; do
  case "$1" in
    --silent)   SILENT=true ;;
    --force)    FORCE=true ;;
    --describe) DESCRIBE=true ;;
    --describe-files) DESCRIBE_FILES=true ;;
  esac
  shift
done

# ── Manifiesto declarativo (--describe) ───────────────────────
if $DESCRIBE; then
  cat << 'JSON'
{"id":"apk","supports_silent":true,"supports_force":true,"variants":[],"variant_required":false}
JSON
  exit 0
fi

# ── Manifiesto de instalación (--describe-files, moduledeb.sh pack) ────
# Ver docs/arquitectura/MODULEDEB_GENERICO.md. Las herramientas de la cadena
# (aapt2/openjdk-17/d8/zipalign/apksigner/unzip/zip/wget) son paquetes pkg de
# Termux — NO se empaquetan acá, dependencies[] avisa si faltan. Lo propio de
# Kairos es el wrapper compil-apk-termux + android.jar (descarga de red, ~lento
# de repetir) — reempaquetarlos evita volver a bajar android.jar en cada
# reinstalación. El keystore NO se incluye a propósito (ver not_covered).
if $DESCRIBE_FILES; then
  jq -n \
    --arg p1 "$TERMUX_PREFIX/bin/compil-apk-termux" \
    --arg p2 "$TERMUX_PREFIX/share/android-platform/android.jar" \
    --arg dep1_check "command -v aapt2 >/dev/null 2>&1 && command -v d8 >/dev/null 2>&1 && command -v zipalign >/dev/null 2>&1 && command -v apksigner >/dev/null 2>&1" \
    --arg dep1_hint "pkg install -y aapt2 openjdk-17 d8 aapt apksigner unzip zip wget" \
    --arg verify "command -v compil-apk-termux >/dev/null 2>&1 && [ -s \"$TERMUX_PREFIX/share/android-platform/android.jar\" ]" \
    --arg patch "chmod +x \"$TERMUX_PREFIX/bin/compil-apk-termux\" 2>/dev/null || true" \
    '{
      id: "apk",
      supports_describe_files: true,
      variant: null,
      package_name: "kairos-module-apk",
      version_registry_key: "",
      files: [
        {path: $p1, required: true, note: "wrapper compil-apk-termux (build/info/merge/decode)"},
        {path: $p2, required: true, note: "android.jar API 30 (mirror Sable/android-platforms) — descarga de red, se reusa tal cual al reinstalar"}
      ],
      file_globs: [],
      dependencies: [
        {id: "build_chain", check_cmd: $dep1_check, install_hint: $dep1_hint}
      ],
      verify_cmd: $verify,
      patch_cmd: $patch,
      not_covered: [
        "El keystore ($HOME/.local/share/kairos-apk/key.keystore) NO se empaqueta a propósito — es una clave de firma, no algo genérico para redistribuir entre devices; el wrapper la regenera sola en la primera compilación si falta"
      ]
    }'
  exit 0
fi

REGISTRY="$HOME/.android_server_registry"
CHECKPOINT="$HOME/.install_apk_checkpoint"

# ── log/warn/error/info/step compartidos ─────────────────────
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
mark_done()  { grep -q "^apk_${1}=done" "$CHECKPOINT" 2>/dev/null || echo "apk_${1}=done" >> "$CHECKPOINT"; }
check_done() { grep -q "^apk_${1}=done" "$CHECKPOINT" 2>/dev/null; }


if ! $SILENT; then
  clear; echo ""
  echo -e "${CYAN}${BOLD}"
  echo "  ╔══════════════════════════════════════════╗"
  echo "  ║  📦 COMPILADOR DE APK EN EL DISPOSITIVO  ║"
  echo "  ║  aapt2 · javac · d8 · zipalign · signer  ║"
  echo "  ╚══════════════════════════════════════════╝"
  echo -e "${NC}"
fi

# ── Ya instalado ────────────────────────────────────────────
# Bug real (auditoría 2026-08-27): este chequeo solo confirmaba compil-apk-termux + aapt2,
# no los otros 3 binarios que PASO 1 instala en la misma línea de "pkg install" (d8,
# zipalign, apksigner) — si esa instalación quedó a medias (ej. mirror caído a mitad de la
# descarga, dpkg interrumpido), una corrida posterior sin --force reportaba "ya instalado" y
# salía, dejando el módulo permanentemente roto (compil-apk-termux existe pero cmd_build()
# revienta al llegar a "d8"/"zipalign"/"apksigner") sin que el usuario tuviera forma de que
# el instalador lo repare solo. Mismo criterio que verify_binary_installed(): confirmar TODO
# lo que el módulo promete, no solo una muestra parcial.
if command -v compil-apk-termux &>/dev/null && command -v aapt2 &>/dev/null \
   && command -v d8 &>/dev/null && command -v zipalign &>/dev/null && command -v apksigner &>/dev/null \
   && ! $FORCE; then
  log "Compilador de APK ya instalado"
  exit 0
fi
$FORCE && rm -f "$CHECKPOINT"

if ! $SILENT; then
  echo ""
  echo -n "  ¿Instalar el compilador de APK? (s/n): "
  read -r _CONFIRM < /dev/tty
  [ "$_CONFIRM" != "s" ] && [ "$_CONFIRM" != "S" ] && { echo "Cancelado."; exit 0; }
fi

# ── PASO 1 — Herramientas de compilación ───────────────────────
# Bug real encontrado 2026-08-24 (ver docs/humano212.md): "zipalign" NO es
# el nombre de un paquete de Termux ("E: Unable to locate package zipalign",
# confirmado con `pkg search zipalign` sin resultados) — el binario zipalign
# lo trae el paquete "aapt" (Android Asset Packaging Tool, distinto de
# "aapt2"), confirmado instalando "aapt" y encontrando el binario real en
# $PREFIX/bin/zipalign. Los chequeos `command -v zipalign` de abajo siguen
# siendo correctos (verifican el binario, no el paquete) — solo el nombre
# del paquete en `pkg install` estaba mal.
step "PASO 1 — Herramientas de compilación (aapt2, JDK, d8, zipalign, apksigner)"
if check_done "tools" && command -v aapt2 &>/dev/null && command -v d8 &>/dev/null && command -v zipalign &>/dev/null && command -v apksigner &>/dev/null; then
  log "Herramientas ya instaladas [checkpoint]"
else
  info "Instalando: aapt2 openjdk-17 d8 aapt apksigner unzip zip wget"
  pkg_update_with_fallback
  # Antes esta línea tenía "2>/dev/null" — descartaba el stderr real de apt
  # ANTES de que Java (redirectErrorStream) pudiera capturarlo, así que el
  # log persistente (install_apk.log) saltaba directo de "Reading state
  # information..." a "[ERROR] ..." sin mostrar qué paquete falló ni por qué
  # (bug reportado 2026-08-14, ver docs/humano/). Ahora se captura stdout+stderr
  # completo y se vuelca al log solo si algo falla, para no ensuciar el log
  # en el caso exitoso.
  _APK_TOOLS_OUT=$(pkg install aapt2 openjdk-17 d8 aapt apksigner unzip zip wget -y 2>&1)
  _APK_TOOLS_STATUS=$?
  if [ "$_APK_TOOLS_STATUS" -ne 0 ] || ! command -v aapt2 &>/dev/null || ! command -v zipalign &>/dev/null \
     || ! command -v apksigner &>/dev/null || ! command -v d8 &>/dev/null; then
    echo "----- salida completa de pkg install (código $_APK_TOOLS_STATUS) -----"
    echo "$_APK_TOOLS_OUT"
    echo "-----------------------------------------------------------------------"
  fi
  [ "$_APK_TOOLS_STATUS" -eq 0 ] || error "No se pudieron instalar las herramientas de compilación (pkg install salió con código $_APK_TOOLS_STATUS — ver salida arriba)"
  command -v aapt2 &>/dev/null || error "aapt2 no disponible (¿pkg falló?)"
  command -v zipalign &>/dev/null || error "zipalign no disponible"
  command -v apksigner &>/dev/null || error "apksigner no disponible"
  command -v d8 &>/dev/null || error "d8 no disponible"
  log "aapt2: $(aapt2 version 2>/dev/null | head -1)"
  log "zipalign: $(zipalign -h 2>&1 | head -1)"
  log "apksigner: $(apksigner --version 2>/dev/null)"
  log "java: $(java -version 2>&1 | head -1)"
  mark_done "tools"
fi

# ── PASO 2 — android.jar (platform) ────────────────────────────
step "PASO 2 — android.jar (API 30) para link de aapt2 y classpath de javac"
ANDROID_JAR="$TERMUX_PREFIX/share/android-platform/android.jar"
if check_done "androidjar" && [ -f "$ANDROID_JAR" ]; then
  log "android.jar ya descargado [checkpoint]"
else
  mkdir -p "$(dirname "$ANDROID_JAR")"
  info "Descargando android.jar desde Sable/android-platforms (mirror usado por BuildAPKs)..."
  timeout 30 wget -O "$ANDROID_JAR" \
    "https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar" \
    || error "No se pudo descargar android.jar (¿red?)"
  [ -s "$ANDROID_JAR" ] || { rm -f "$ANDROID_JAR"; error "android.jar vacío — descarga fallida"; }
  log "android.jar listo: $(du -h "$ANDROID_JAR" | cut -f1)"
  mark_done "androidjar"
fi

# ── PASO 3 — Wrapper compil-apk-termux + keystore ───────────────
step "PASO 3 — Wrapper compil-apk-termux y keystore por defecto"
WRAPPER="$TERMUX_PREFIX/bin/compil-apk-termux"
KEYSTORE="$HOME/.local/share/kairos-apk/key.keystore"
mkdir -p "$HOME/.local/share/kairos-apk"

if ! command -v compil-apk-termux &>/dev/null || $FORCE; then
  cat > "$WRAPPER" << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
#  compil-apk-termux v2.1.0 — compila e inspecciona APKs en el
#  dispositivo sin Android Studio. Patrón i-Haklab/termux-apk-make.
#
#  SUBCOMANDOS:
#    compil-apk-termux build <proyecto>          → build/final.apk (default)
#    compil-apk-termux info <apk>                → paquete/versión/label/firma
#    compil-apk-termux merge <base.apk> <partes...> → split APKs fusionados
#    compil-apk-termux decode <apk> <dir>        → extrae y deja el manifest legible
#
#  PROYECTO (estructura mínima):
#    proyecto/
#    ├── AndroidManifest.xml
#    ├── src/   (archivos .java y/o .kt — puede tener paquetes anidados)
#    └── res/   (opcional — recursos de Android)
#
#  LENGUAJES: Java + Kotlin (JVM puro, sin Gradle). Kotlin (.kt en src/) se
#  compila automáticamente si el módulo "kotlin" está instalado (kotlinc) —
#  ver "ALCANCE REAL" en apk.sh para lo que NO compila (React Native, NDK/C++).
#
#  SALIDA build: <proyecto>/build/final.apk  (firmado, listo para instalar)
#
#  RUTAS ÚTILES:
#    compil-apk-termux build ~/storage/downloads/mi_app
#    compil-apk-termux build ~/storage/downloads
#    compil-apk-termux build ~/proyectos/mi_app
#    compil-apk-termux build .   (dentro de la carpeta del proyecto)
#
#  VARIABLES DE ENTORNO (opcionales):
#    ANDROID_JAR   → ruta del android.jar (default: la instalada por apk.sh)
#    APK_ALIAS     → alias del keystore (default: kairos)
#    APK_PASS      → password del keystore (default: password)
#    APK_KEYSTORE  → ruta del keystore (default: key.keystore de apk.sh)
# ============================================================

TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/sbin:$PATH"
set -e

usage() {
  echo "USO:"
  echo "  compil-apk-termux build <proyecto>              compilar (default)"
  echo "  compil-apk-termux info <apk>                    ver info del APK"
  echo "  compil-apk-termux merge <base.apk> <partes...>  fusionar split APKs"
  echo "  compil-apk-termux decode <apk> <dir>            extraer + manifest legible"
  echo "  compil-apk-termux instrument-decode <apk>                              decompilar con apktool (smali/decompile completo)"
  echo "  compil-apk-termux instrument-search <workdir> <query>                  buscar clases .smali por nombre"
  echo "  compil-apk-termux instrument-methods <workdir> <clase.smali>           listar métodos de una clase (JSON)"
  echo "  compil-apk-termux instrument-apply <workdir> <clase.smali> <metodo> <accion> [tag] [msg]"
  echo "                                                                         inyectar hook + recompilar + firmar"
  echo "  compil-apk-termux logstart|logstop|logstatus|logtail [n]               servidor de logs de la instrumentación"
  exit 1
}

# ── Parser de salida del compilador (port a bash del CompilerOutputParser de
#    CodeAssist — referencia/ides/CodeAssist-main/build-engine/.../CompilerOutputParser.kt).
#    Reconoce el formato GNU (javac/kotlinc/aapt2):  archivo:linea[:col]: error|warning: msg
#    y lo muestra con resaltado + un resumen de errores al final — el usuario ve QUÉ
#    falló y DÓNDE, no un dump crudo de 300 líneas. Se invoca como filtro:
#    _parse_compiler_output "paso" 2>&1 | tee ... 
_parse_compiler_output() {
  local step="$1"
  shift
  local errors=0 warnings=0
  local line
  echo "── $step"
  while IFS= read -r line || [ -n "$line" ]; do
    if [[ "$line" =~ ^([^:]+):([0-9]+)(:([0-9]+))?:\ *(error|warning|note|info)s?:\ *(.*)$ ]]; then
      local file="${BASH_REMATCH[1]}" ln="${BASH_REMATCH[2]}" sev="${BASH_REMATCH[5]}" msg="${BASH_REMATCH[6]}"
      if [ "$sev" = "error" ]; then
        echo "  [ERROR] $file:$ln $msg"
        errors=$((errors+1))
      elif [ "$sev" = "warning" ]; then
        echo "  [WARN]  $file:$ln $msg"
        warnings=$((warnings+1))
      fi
    elif [[ "$line" =~ \b(error|warning)\b\ *: ]]; then
      # Error sin ubicación (ej. "error: No se pudo..." o resumen de una tool).
      echo "  $line"
      [[ "$line" =~ error ]] && errors=$((errors+1))
    else
      # Línea normal: se muestra tal cual (el usuario ve el contexto).
      echo "$line"
    fi
  done
  echo "── Fin de $step ($errors error/es, $warnings warning/s)"
  [ "$errors" -eq 0 ]
}

ANDROID_JAR="${ANDROID_JAR:-$TERMUX_PREFIX/share/android-platform/android.jar}"
KEYSTORE="${APK_KEYSTORE:-$HOME/.local/share/kairos-apk/key.keystore}"
KEY_ALIAS="${APK_ALIAS:-kairos}"
KEY_PASS="${APK_PASS:-password}"

# ── Instrumentación "sin root" (fuente VictorH028/no-root-logger, ver
#    docs/humano331.md) — decompila con apktool, inyecta llamadas a un logger
#    remoto vía smali_hook.py, recompila, agrega RemoteLogger+liblogger.so
#    como un dex/lib adicionales (sin tocar el .smali del logger a mano) y
#    vuelve a firmar. LOGGER_DIR trae el payload fuente (extraído por
#    KairosBootstrap desde app/src/main/assets/scripts/); LOGGER_CACHE guarda
#    lo que se compila una sola vez (liblogger.so + classes-logger.dex) para
#    no repetirlo en cada instrumentación. ──
LOGGER_DIR="$HOME/.kairos_apk_logger"
LOGGER_CACHE="$HOME/.local/share/kairos-apk/logger"
LOGGER_SESSION="kairos-apk-logger"

# ── info <apk> ───────────────────────────────────────────────
cmd_info() {
  local apk="$1"
  [ -f "$apk" ] || { echo "[ERROR] No existe: $apk"; usage; }
  echo "=== info: $apk ==="
  echo "── aapt2 dump badging ──"
  aapt2 dump badging "$apk" 2>&1 | grep -E "^(package|application-label|sdkVersion|targetSdkVersion|launchable-activity|uses-permission)" | sed 's/^/  /' || true
  echo "── apksigner verify ──"
  apksigner verify --print-certs "$apk" 2>&1 | sed 's/^/  /' || true
  echo "── contenido (resumen) ──"
  unzip -l "$apk" 2>/dev/null | tail -3 | sed 's/^/  /'
}

# ── merge <base.apk> <partes...> ─────────────────────────────
# Equivalente funcional del merge de APKEditor (referencia/herramientas/APKEditor-master):
# desempaca base + splits en un solo dir, vuelve a empaquetar, alinea y re-firma.
cmd_merge() {
  local base="$1"; shift
  [ -f "$base" ] || { echo "[ERROR] Falta el APK base: $base"; usage; }
  [ $# -ge 1 ] || { echo "[ERROR] merge necesita al menos un APK parte (config.<abi>.apk, etc.)"; usage; }
  local work; work="$(mktemp -d)"
  local out; out="$(dirname "$base")/merged.apk"
  local f
  echo "=== merge: $(basename "$base") + $# parte(s) ==="
  for f in "$base" "$@"; do
    [ -f "$f" ] || { echo "[ERROR] No existe: $f"; rm -rf "$work"; exit 1; }
    echo "── extrayendo $(basename "$f")"
    ( cd "$work" && unzip -q -o "$f" )
  done
  # Los splits traen AndroidManifest.xml duplicado (idéntico entre partes) — se conserva
  # el de la base; des-dupear entries duplicadas no es posible sin un merge semántico.
  echo "── empaquetando"
  # -n .arsc:.so (2026-09-12, ver docs/humano331.md — mismo bug confirmado y arreglado en
  # cmd_instrument_apply): sin esto, resources.arsc queda comprimido y Android 30+ rechaza el
  # install ("Targeting R+ ... requires the resources.arsc ... stored uncompressed").
  ( cd "$work" && zip -q -r -n .arsc:.so "$out" . -x "*.idsig" )
  zipalign -f -p 4 "$out" "${out}.aligned"
  mv -f "${out}.aligned" "$out"
  if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkey -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
      -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Kairos, O=Local, OU=Local, L=Local, S=Local, C=US" >/dev/null 2>&1 || true
  fi
  apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
    --out "${out}.signed" "$out" >/dev/null 2>&1
  mv -f "${out}.signed" "$out"
  rm -rf "$work"
  echo "✅ APK fusionado: $out"
  echo "   Nota: si la app usa signature schemes v4/instant, re-firmar con apksigner lo resuelve."
}

# ── decode <apk> <dir> ───────────────────────────────────────
# No hay baksmali/apktool empaquetados por defecto en Termux; esto extrae el APK y
# deja el AndroidManifest LEGIBLE (aapt2 xmltree) + los .dex crudos — suficiente para
# inspección. Para decompilación real: pkg install apktool (si está en tu repo).
cmd_decode() {
  local apk="$1" dir="$2"
  [ -f "$apk" ] || { echo "[ERROR] No existe: $apk"; usage; }
  [ -n "$dir" ] || { echo "[ERROR] decode necesita directorio destino"; usage; }
  mkdir -p "$dir"
  echo "=== decode: $(basename "$apk") → $dir ==="
  ( cd "$dir" && unzip -q -o "$apk" )
  if [ -f "$dir/AndroidManifest.xml" ]; then
    echo "── AndroidManifest.xml (legible) ──"
    aapt2 dump xmltree "$apk" --file AndroidManifest.xml 2>/dev/null | sed 's/^/  /' || true
    echo "   (binario: $dir/AndroidManifest.xml)"
  fi
  echo "── dex extraídos ──"
  ls -1 "$dir"/*.dex 2>/dev/null | sed 's/^/  /' || echo "  (sin .dex)"
  echo "✅ APK extraído en $dir. Para smali/decompile completo: pkg install apktool."
}

# ── build <proyecto> ─────────────────────────────────────────
cmd_build() {
  local project="$1"
  PROJECT="$(cd "$project" && pwd)"
  [ -f "$PROJECT/AndroidManifest.xml" ] || { echo "[ERROR] No hay AndroidManifest.xml en $PROJECT"; usage; }

  BUILD_DIR="$PROJECT/build"
  rm -rf "$BUILD_DIR"
  # Bug real encontrado 2026-08-24 (ver docs/humano216.md, pruebas funcionales reales por ADB):
  # faltaba crear "$BUILD_DIR/dex" acá — d8 (PASO de dex más abajo) requiere que su "--output" ya
  # exista como directorio real ("Invalid output: .../build/dex — Output must be a .zip or .jar
  # archive or an existing directory", confirmado en el stacktrace real de R8/d8 al compilar un
  # proyecto real de prueba). Se agrega acá junto al resto de subcarpetas del build.
  # "kotlin-classes" (2026-08-26, ver docs/arquitectura/PROPUESTA_APK_MULTILENGUAJE_2026-08-26.md):
  # directorio de salida propio para kotlinc, separado de "classes" (javac) — mismo patrón que
  # CodeAssist (referencia/ides/CodeAssist-main), Kotlin compila ANTES que Java a su propio
  # directorio, que luego se agrega al classpath de javac.
  mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/kotlin-classes" "$BUILD_DIR/apk" "$BUILD_DIR/dex"

  # Detección de fuentes Kotlin — si el proyecto no tiene .kt, el flujo de abajo se comporta
  # exactamente igual que antes (sin invocar kotlinc, sin overhead) — ver Tarea 1 punto 4.
  find "$PROJECT/src" -name '*.kt' > "$BUILD_DIR/kotlin_sources.txt" 2>/dev/null || true
  HAS_KOTLIN=false
  [ -s "$BUILD_DIR/kotlin_sources.txt" ] && HAS_KOTLIN=true

  if $HAS_KOTLIN && ! command -v kotlinc &>/dev/null; then
    echo "[ERROR] Proyecto tiene archivos .kt pero kotlinc no está instalado — instalá el módulo Kotlin primero (Módulos → Kotlin, o 'bash kotlin.sh --silent')."
    exit 1
  fi

  TOTAL_STEPS=7
  $HAS_KOTLIN && TOTAL_STEPS=8
  STEP_N=1

  echo "=== [$STEP_N/$TOTAL_STEPS] aapt2 compile (recursos) ==="
  if [ -d "$PROJECT/res" ]; then
    _parse_compiler_output "aapt2 compile" <<< "$(aapt2 compile --dir "$PROJECT/res" -o "$BUILD_DIR/resources.zip" 2>&1)" || exit 1
    echo "  -> resources.zip"
  else
    echo "  -> sin res/, se omite"
  fi
  STEP_N=$((STEP_N+1))

  echo "=== [$STEP_N/$TOTAL_STEPS] aapt2 link (base.apk + R.java) ==="
  if [ -f "$BUILD_DIR/resources.zip" ]; then
    _parse_compiler_output "aapt2 link" <<< "$(aapt2 link -o "$BUILD_DIR/apk/base.apk" -I "$ANDROID_JAR" \
      --java "$BUILD_DIR/gen" --manifest "$PROJECT/AndroidManifest.xml" \
      "$BUILD_DIR/resources.zip" 2>&1)" || exit 1
  else
    _parse_compiler_output "aapt2 link" <<< "$(aapt2 link -o "$BUILD_DIR/apk/base.apk" -I "$ANDROID_JAR" \
      --java "$BUILD_DIR/gen" --manifest "$PROJECT/AndroidManifest.xml" 2>&1)" || exit 1
  fi
  echo "  -> base.apk + R.java"
  STEP_N=$((STEP_N+1))

  if $HAS_KOTLIN; then
    echo "=== [$STEP_N/$TOTAL_STEPS] kotlinc (código Kotlin) ==="
    _parse_compiler_output "kotlinc" <<< "$(kotlinc -classpath "$ANDROID_JAR" \
      -d "$BUILD_DIR/kotlin-classes" @"$BUILD_DIR/kotlin_sources.txt" 2>&1)" || exit 1
    echo "  -> .class compilados (kotlin-classes/)"
    STEP_N=$((STEP_N+1))
  fi

  echo "=== [$STEP_N/$TOTAL_STEPS] javac (código + R.java) ==="
  find "$PROJECT/src" "$BUILD_DIR/gen" -name '*.java' > "$BUILD_DIR/sources.txt" 2>/dev/null || true
  # Classpath de javac incluye kotlin-classes (si hubo paso Kotlin) para que código Java
  # pueda referenciar clases Kotlin — mismo patrón que CodeAssist (ver comentario arriba).
  JAVAC_CLASSPATH="$ANDROID_JAR"
  $HAS_KOTLIN && JAVAC_CLASSPATH="$ANDROID_JAR:$BUILD_DIR/kotlin-classes"
  if [ -s "$BUILD_DIR/sources.txt" ]; then
    _parse_compiler_output "javac" <<< "$(javac -source 11 -target 11 -classpath "$JAVAC_CLASSPATH" \
      -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt" 2>&1)" || exit 1
    echo "  -> .class compilados"
  else
    echo "  -> sin fuentes Java"
  fi
  STEP_N=$((STEP_N+1))

  echo "=== [$STEP_N/$TOTAL_STEPS] d8 (.class → classes.dex) ==="
  # Recoge .class de AMBOS directorios (classes/ de javac + kotlin-classes/ de kotlinc) —
  # un proyecto mixto Java+Kotlin empaqueta las dos salidas en un solo classes.dex.
  CLASS_COUNT=$(find "$BUILD_DIR/classes" "$BUILD_DIR/kotlin-classes" -name '*.class' 2>/dev/null | wc -l)
  if [ "$CLASS_COUNT" -gt 0 ]; then
    _parse_compiler_output "d8" <<< "$(d8 --release --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" \
      $(find "$BUILD_DIR/classes" "$BUILD_DIR/kotlin-classes" -name '*.class') 2>&1)" || exit 1
    echo "  -> classes.dex"
  else
    echo "  -> sin classes.dex (proyecto solo recursos)"
  fi
  STEP_N=$((STEP_N+1))

  echo "=== [$STEP_N/$TOTAL_STEPS] Empaquetado (classes.dex + lib + assets al APK) ==="
  cd "$BUILD_DIR/apk"
  [ -f "$BUILD_DIR/dex/classes.dex" ] && cp "$BUILD_DIR/dex/classes.dex" .
  [ -d "$PROJECT/assets" ] && cp -r "$PROJECT/assets" .
  [ -d "$PROJECT/lib" ] && cp -r "$PROJECT/lib" .
  if [ -f classes.dex ] || [ -d assets ] || [ -d lib ]; then
    zip -q -r "$BUILD_DIR/apk/base.apk" classes.dex assets lib 2>/dev/null || true
  fi
  echo "  -> APK empaquetado"
  STEP_N=$((STEP_N+1))

  echo "=== [$STEP_N/$TOTAL_STEPS] zipalign (alineación) ==="
  zipalign -f -p 4 "$BUILD_DIR/apk/base.apk" "$BUILD_DIR/apk/aligned.apk" 2>&1
  echo "  -> aligned.apk"
  STEP_N=$((STEP_N+1))

  echo "=== [$STEP_N/$TOTAL_STEPS] apksigner (firma v2/v3) ==="
  if [ ! -f "$KEYSTORE" ]; then
    echo "  -> generando keystore nuevo en $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkey -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
      -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Kairos, O=Local, OU=Local, L=Local, S=Local, C=US" 2>&1 | tail -1
  fi
  apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$BUILD_DIR/apk/final.apk" "$BUILD_DIR/apk/aligned.apk" 2>&1
  echo "  -> firmado"

  echo ""
  echo "✅ APK listo: $BUILD_DIR/apk/final.apk"
  echo "   Instalalo con:  pm install -r \"$BUILD_DIR/apk/final.apk\""
  echo "   (o desde el explorador de archivos de Android)"
}

# ── instrument-decode <apk> ────────────────────────────────────
# apktool NO viene con el resto de la cadena (aapt2/d8/zipalign/apksigner ya
# se instalan en PASO 1 de apk.sh) — se instala acá, en el primer uso real,
# mismo criterio que "kotlin" (módulo aparte, detectado en tiempo de build,
# no dependencia dura de apk.sh). Emite "WORKDIR:<ruta>" como última línea —
# la UI la parsea para saber dónde quedó la decompilación.
_ensure_apktool() {
  command -v apktool &>/dev/null && return 0
  echo "Instalando apktool (decompilador Smali, ~6MB + openjdk-21)..."
  pkg install -y apktool || return 1
  command -v apktool &>/dev/null
}

cmd_instrument_decode() {
  local apk="$1"
  [ -f "$apk" ] || { echo "[ERROR] No existe: $apk"; exit 1; }
  _ensure_apktool || { echo "[ERROR] No se pudo instalar apktool"; exit 1; }
  local work="$HOME/.local/share/kairos-apk/instrument/$(basename "${apk%.apk}")_$(date +%s)"
  mkdir -p "$(dirname "$work")"
  echo "=== [1/1] apktool d (decompilando — puede tardar con apps grandes) ==="
  apktool d -f -o "$work" "$apk" 2>&1 || { echo "[ERROR] apktool d falló"; exit 1; }
  echo "WORKDIR:$work"
}

# ── instrument-search <workdir> <query> ───────────────────────
# Búsqueda de clases por nombre de archivo .smali (case-insensitive, substring)
# — alcanza para que la UI ofrezca una lista tocable en vez de pedirle al
# usuario que navegue el árbol completo a mano (una app real puede tener
# miles de .smali).
cmd_instrument_search() {
  local work="$1" query="$2"
  [ -d "$work" ] || { echo "[ERROR] No existe: $work"; exit 1; }
  find "$work" -iname "*${query}*.smali" -type f 2>/dev/null | while read -r f; do
    echo "${f#"$work"/}"
  done
}

# ── instrument-methods <workdir> <clase.smali> ────────────────
# Delega en smali_hook.py --list --json — ver ese script para el parser real
# de .method/.registers/.locals.
cmd_instrument_methods() {
  local work="$1" relpath="$2"
  local full="$work/$relpath"
  [ -f "$full" ] || { echo "[ERROR] No existe: $full"; exit 1; }
  [ -f "$LOGGER_DIR/smali_hook.py" ] || { echo "[ERROR] smali_hook.py no encontrado en $LOGGER_DIR (¿bootstrap incompleto? reinstalá la app)"; exit 1; }
  python3 "$LOGGER_DIR/smali_hook.py" "$full" --list --json
}

# ── liblogger.so + classes-logger.dex (compilados UNA vez, reusados en cada
#    instrumentación) — ver docs/humano331.md para el porqué de este diseño
#    (RemoteLogger se agrega como dex/lib adicionales al APK ya recompilado
#    en vez de traducirlo a mano a .smali para inyectarlo junto al resto). ──
_ensure_logger_lib() {
  mkdir -p "$LOGGER_CACHE/lib/arm64-v8a"
  if [ ! -s "$LOGGER_CACHE/lib/arm64-v8a/liblogger.so" ]; then
    [ -f "$LOGGER_DIR/native_logger.c" ] || { echo "[ERROR] native_logger.c no encontrado en $LOGGER_DIR"; return 1; }
    echo "  -> compilando liblogger.so (clang, una sola vez)..."
    clang -shared -fPIC -O2 -fno-exceptions -fno-rtti \
      -o "$LOGGER_CACHE/lib/arm64-v8a/liblogger.so" \
      "$LOGGER_DIR/native_logger.c" || return 1
  fi
  if [ ! -s "$LOGGER_CACHE/classes-logger.dex" ]; then
    [ -f "$LOGGER_DIR/RemoteLogger.java" ] || { echo "[ERROR] RemoteLogger.java no encontrado en $LOGGER_DIR"; return 1; }
    echo "  -> compilando RemoteLogger.class -> classes-logger.dex (una sola vez)..."
    local _tmp; _tmp="$(mktemp -d)"
    javac -source 11 -target 11 -classpath "$ANDROID_JAR" -d "$_tmp" "$LOGGER_DIR/RemoteLogger.java" || { rm -rf "$_tmp"; return 1; }
    mkdir -p "$_tmp/dex"
    d8 --release --lib "$ANDROID_JAR" --output "$_tmp/dex" "$_tmp/com/kairos/logger/RemoteLogger.class" || { rm -rf "$_tmp"; return 1; }
    cp "$_tmp/dex/classes.dex" "$LOGGER_CACHE/classes-logger.dex"
    rm -rf "$_tmp"
  fi
  [ -s "$LOGGER_CACHE/lib/arm64-v8a/liblogger.so" ] && [ -s "$LOGGER_CACHE/classes-logger.dex" ]
}

# ── instrument-apply <workdir> <clase.smali> <metodo> <accion> [tag] [msg] ──
# accion: enter | exit | both | d
cmd_instrument_apply() {
  local work="$1" relpath="$2" method="$3" action="$4" tag="${5:-LOG}" message="${6:-Mensaje de log}"
  local full="$work/$relpath"
  [ -f "$full" ] || { echo "[ERROR] No existe: $full"; exit 1; }
  [ -f "$LOGGER_DIR/smali_hook.py" ] || { echo "[ERROR] smali_hook.py no encontrado en $LOGGER_DIR"; exit 1; }

  echo "=== [1/5] Inyectando hook (smali_hook.py) ==="
  local _args=(--method "$method" --action "$action" --auto-registers --no-backup)
  [ "$action" = "d" ] && _args+=(--tag "$tag" --message "$message")
  python3 "$LOGGER_DIR/smali_hook.py" "$full" "${_args[@]}" || { echo "[ERROR] Falló la inyección"; exit 1; }

  echo "=== [2/5] Preparando RemoteLogger (compilación en caché) ==="
  _ensure_logger_lib || { echo "[ERROR] No se pudo preparar liblogger.so/RemoteLogger.class"; exit 1; }

  echo "=== [3/5] Verificando permiso INTERNET en el manifest ==="
  local _manifest="$work/AndroidManifest.xml"
  if [ -f "$_manifest" ] && ! grep -q 'android.permission.INTERNET' "$_manifest"; then
    # apktool decodifica el manifest a XML legible con indentación real — insertar
    # el permiso justo antes del cierre "</manifest>" es seguro y simple, sin
    # necesitar un parser XML completo para un cambio de una sola línea.
    sed -i 's#</manifest>#    <uses-permission android:name="android.permission.INTERNET"/>\n</manifest>#' "$_manifest"
    echo "  -> permiso INTERNET agregado (necesario para que el hook llegue al log_server)"
  else
    echo "  -> ya lo tenía"
  fi

  echo "=== [4/5] apktool b (recompilando) ==="
  apktool b "$work" -o "$work/rebuilt.apk" 2>&1 || { echo "[ERROR] apktool b falló"; exit 1; }

  echo "=== [5/5] Agregando RemoteLogger + liblogger.so, alineando y firmando ==="
  local _merge; _merge="$(mktemp -d)"
  ( cd "$_merge" && unzip -q -o "$work/rebuilt.apk" )
  # META-INF de la firma que apktool haya dejado — se re-firma de cero abajo,
  # dejar los certificados/manifest viejos rompería la verificación.
  rm -rf "$_merge/META-INF"
  local _n=2
  while [ -f "$_merge/classes${_n}.dex" ]; do _n=$((_n+1)); done
  cp "$LOGGER_CACHE/classes-logger.dex" "$_merge/classes${_n}.dex"
  mkdir -p "$_merge/lib/arm64-v8a"
  cp "$LOGGER_CACHE/lib/arm64-v8a/liblogger.so" "$_merge/lib/arm64-v8a/liblogger.so"
  # Salida final bajo ~/proyectos/instrumented/ (no en el workdir de
  # ~/.local/share/kairos-apk/instrument/) — es la única ruta que el
  # FileProvider "apkbuilder" expone (ver res/xml/apk_builder_paths.xml),
  # mismo criterio que <proyecto>/build/apk/final.apk para compilaciones
  # normales. shareOrInstallApk() en ApkFragment.kt necesita esta ruta para
  # poder ofrecer compartir/instalar sin ampliar el alcance del provider a
  # todo ~/.local/share/kairos-apk/ (ahí vive también el keystore de firma).
  mkdir -p "$HOME/proyectos/instrumented"
  local _out="$HOME/proyectos/instrumented/$(basename "$work").apk"
  rm -f "$_out"
  # -n .arsc:.so (2026-09-12, confirmado en dispositivo real, ver docs/humano331.md): "zip"
  # comprime TODO por default, incluido resources.arsc — Android 30+ exige resources.arsc
  # SIN comprimir y alineado a 4 bytes ("pm install" real: "Failed parse during
  # installPackageLI: Targeting R+ (version 30 and above) requires the resources.arsc of
  # installed APKs to be stored uncompressed and aligned"). apktool ya deja rebuilt.apk bien,
  # pero re-zipear todo el contenido (para agregar classes-logger.dex + liblogger.so) lo
  # rompía. .so también sin comprimir — requisito real para que el linker lo mapee directo
  # (mmap) en vez de necesitar extraerlo primero.
  ( cd "$_merge" && zip -q -r -n .arsc:.so "$_out" . )
  rm -rf "$_merge"
  zipalign -f -p 4 "$_out" "${_out}.aligned" 2>&1 || { echo "[ERROR] zipalign falló"; exit 1; }
  mv -f "${_out}.aligned" "$_out"
  if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkey -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
      -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Kairos, O=Local, OU=Local, L=Local, S=Local, C=US" >/dev/null 2>&1 || true
  fi
  apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
    --out "${_out}.signed" "$_out" 2>&1 || { echo "[ERROR] apksigner falló"; exit 1; }
  mv -f "${_out}.signed" "$_out"
  echo ""
  echo "✅ APK instrumentado: $_out"
}

# ── logstart / logstop / logstatus / logtail [n] ──────────────
# Servidor de logs (log_server.py, 127.0.0.1:9999) en una sesión tmux
# detached — mismo patrón que los módulos con servidor propio (n8n, ollama).
cmd_logstart() {
  command -v tmux &>/dev/null || { echo "[ERROR] tmux no está instalado"; exit 1; }
  if tmux has-session -t "$LOGGER_SESSION" 2>/dev/null; then
    echo "ALREADY_RUNNING"
    return 0
  fi
  mkdir -p "$LOGGER_CACHE"
  [ -f "$LOGGER_DIR/log_server.py" ] || { echo "[ERROR] log_server.py no encontrado en $LOGGER_DIR"; exit 1; }
  cp -f "$LOGGER_DIR/log_server.py" "$LOGGER_CACHE/log_server.py"
  tmux new-session -d -s "$LOGGER_SESSION" -n logger "python3 '$LOGGER_CACHE/log_server.py'"
  sleep 1
  if tmux has-session -t "$LOGGER_SESSION" 2>/dev/null; then
    echo "STARTED"
  else
    echo "[ERROR] No se pudo iniciar (¿puerto 9999 ocupado?)"
    exit 1
  fi
}

cmd_logstop() {
  tmux kill-session -t "$LOGGER_SESSION" 2>/dev/null || true
  echo "STOPPED"
}

cmd_logstatus() {
  if tmux has-session -t "$LOGGER_SESSION" 2>/dev/null; then
    echo "RUNNING"
  else
    echo "STOPPED"
  fi
}

cmd_logtail() {
  local n="${1:-100}"
  [ -f "$LOGGER_CACHE/app_logs.txt" ] && tail -n "$n" "$LOGGER_CACHE/app_logs.txt" || true
}

# ── dispatch ─────────────────────────────────────────────────
[ $# -lt 1 ] && usage
case "$1" in
  build)  [ $# -lt 2 ] && usage; cmd_build "$2" ;;
  info)   [ $# -lt 2 ] && usage; cmd_info "$2" ;;
  merge)  [ $# -lt 3 ] && usage; shift; cmd_merge "$@" ;;
  decode) [ $# -lt 3 ] && usage; cmd_decode "$2" "$3" ;;
  instrument-decode)  [ $# -lt 2 ] && usage; cmd_instrument_decode "$2" ;;
  instrument-search)  [ $# -lt 3 ] && usage; cmd_instrument_search "$2" "$3" ;;
  instrument-methods) [ $# -lt 3 ] && usage; cmd_instrument_methods "$2" "$3" ;;
  instrument-apply)   [ $# -lt 5 ] && usage; shift; cmd_instrument_apply "$@" ;;
  logstart)  cmd_logstart ;;
  logstop)   cmd_logstop ;;
  logstatus) cmd_logstatus ;;
  logtail)   cmd_logtail "$2" ;;
  -h|--help|help) usage ;;
  *) # Default = build con el proyecto pasado directo (compatibilidad v1).
     cmd_build "$1" ;;
esac
EOF
  chmod +x "$WRAPPER"
  log "Wrapper compil-apk-termux instalado"
fi

# keystore por defecto (generado en la primera compilación, no acá —
# keytool sin interacción necesita -dname; se deja que el wrapper lo cree)

# ── Registry ─────────────────────────────────────────────────
step "FINALIZANDO"
_DATE=$(date +%Y-%m-%d)
registry_write apk \
  "installed=true" \
  "command=compil-apk-termux" \
  "chain=aapt2,kotlinc,javac,d8,zipalign,apksigner" \
  "android_jar=API30" \
  "install_date=${_DATE}"

notify_event "apk" "install_done" ""
log "Compilador de APK listo — usá: compil-apk-termux <ruta/al/proyecto>"
rm -f "$CHECKPOINT"
exit 0
