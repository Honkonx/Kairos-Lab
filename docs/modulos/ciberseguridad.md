# Ciberseguridad — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (Módulos → tarjeta "Ciberseguridad").
Kit de herramientas de red/OSINT/pentesting/autodefensa en 2 niveles: un nivel **básico** 100% nativo (bionic, sin proot) y un nivel **pro** que agrega un contenedor Kali Linux completo vía `proot-distro`, con o sin interfaz gráfica.

---

**Proyecto:** Kairos
**Script:** `modulos/ciberseguridad.sh` (v2.3.0, 918 líneas, `:63-64`)
**Fragment:** `app/src/main/java/com/termux/app/ui/CiberseguridadFragment.kt` (1209 líneas; `ModuleDetailNavigator.kt:39`)
**`modules.json` (`:547`):** `id: ciberseguridad` — `hasSwitch: false`, `hasVariants: true`, `requiresProot: false` (el proot solo se usa condicionalmente dentro del script para el nivel pro), `arch: bionic`, `category: seguridad`, `terminalCommand: nmap`, `port: ""`, `repo: Honkonx/kairos-lab`, `requires: ["python"]`, `installMethods: ["pkg","pip"]`, `recommended: false`, tamaño `~30MB (pro: +varios cientos de MB por el contenedor Kali)`, estimado `~2 min (básico) / ~10-20 min (pro, según red)`

---

## 1. Alcance y responsabilidad (leer antes que el resto)

El propio script deja esto por escrito en su cabecera: son herramientas estándar de red/OSINT/pentesting, pensadas para diagnóstico de la propia red del usuario y pentesting autorizado. Un catálogo más amplio de herramientas ofensivas (bruteforce, automatización de Metasploit, forense Android, servidores de práctica DVWA/bWAPP/Mutillidae) **no está implementado**; no asumir que existe.

**Clasificación: MÓDULO FUNCIONAL.** UI propia con estado (lee `ciberseguridad.tier/kali_container/kali_gui` del registry, `CiberseguridadFragment.kt:179-183`), 3 pestañas, paneles nativos con parámetros por herramienta, detección de instalación en curso (`:219-221`, banner con poll cada 3 s) y gate de disclaimer legal (`:236-295`). No hay servicio ni puerto: es un kit de herramientas de ejecución puntual, no un daemon. Opciones de UI: ~16 botones + 1 dropdown (acciones sqlmap) + 3 diálogos de instalación (+ selector de 13 categorías Kali) + card de mantenimiento (`addMaintenanceCard()`, `:226`).

## 2. Permisos

Ninguno propio de Android — corre en espacio de usuario Termux (bionic nativo para el nivel básico, `proot-distro` para el nivel pro), sin root, sin permisos adicionales al wizard genérico de Kairos. El nivel pro descarga un contenedor de varios cientos de MB — solo requiere espacio y red. `su -c` solo se usa si ya hay root (`RootAccess`).

## 3. Variantes / niveles (`--variant`)

Flags (`ciberseguridad.sh:104-114`): `--silent`, `--force`, `--describe`, `--describe-files`, `--variant basico|pro-headless|pro-gui[:categoria]`. La variante se elige al instalar (`BottomSheetInstalacion`), no hay switch central en tiempo de ejecución — para cambiar de nivel hay que reinstalar con otra variante. Una variante desconocida cae en básico (`:222-226`); una categoría desconocida cae en `top10` (`:136`).

| Variante (`--variant`) | Tier interno (`$PRO`/`$PRO_GUI`) | Qué instala |
|---|---|---|
| `basico` (default) | `PRO=false` | Solo el kit nativo (PASOS 1-5c) |
| `pro-headless` (alias `pro`) | `PRO=true`, `PRO_GUI=false` | Básico + contenedor Kali sin escritorio |
| `pro-gui` | `PRO=true`, `PRO_GUI=true` | Básico + contenedor Kali + XFCE4 dentro del contenedor |

El PASO 7b instala un metapaquete de Kali elegible por categoría: hay un catálogo de 13 metapaquetes vía `--variant pro-*:<categoria>` (`KALI_CATEGORY_CATALOG`, `ciberseguridad.sh:116-137`; ej. `top10`, `large`, `everything`), expuesto en la UI (`showKaliCategoryDialog`, `CiberseguridadFragment.kt:1155-1203`).

El manifiesto `--describe` declara esta tabla en JSON, con `variant_required: false` y `variant_default: basico`. `--describe-files` (`:171-216`) empaqueta SOLO nikto (ver `not_covered`); el resto no es empaquetable en `.deb`.

**Chequeo de "ya instalado":** solo evalúa el nivel básico (`command -v nmap && theHarvester && sqlmap`). Si el básico ya está pero se pide `--variant pro-*`, el script **no sale** — sigue directo a los pasos 6-8 (Kali), permitiendo pasar de básico a pro sin reinstalar el básico. `--force` borra el checkpoint y fuerza repetir todos los pasos pendientes. Limitación: si el usuario ya tiene pro y vuelve a correr con `--variant basico` sin `--force`, el script sale sin tocar nada (no desinstala Kali, pero tampoco lo detecta en ese chequeo temprano).

## 4. Nivel básico (PASOS 1-5c, bionic nativo)

Cada paso tiene su propio checkpoint (`~/.install_ciberseguridad_checkpoint`, claves `ciberseguridad_<paso>=done`) — reentrante: si el script se corta a mitad retoma desde el siguiente paso.

| Paso | Herramienta | Cómo se instala | Notas |
|---|---|---|---|
| 1 | **nmap** | `pkg install nmap` | Falla con `error` (aborta) si no queda en PATH tras instalar |
| 2 | **netcat-openbsd + dirb** | `pkg install netcat-openbsd dirb` | Paquetes oficiales. Fallos son `warn` |
| 2b | **nikto** | `git clone --depth 1 https://github.com/sullo/nikto.git ~/.nikto` + wrapper bash en `$PREFIX/bin/nikto` (`exec perl ~/.nikto/program/nikto.pl "$@"`) + `cpan -T XML::Writer` | **nikto NO es un paquete de Termux**. Instala `perl` primero si falta. Todo el paso es `warn`-y-seguir |
| 3 | **Python 3** | Detecta `python`/`python3` en PATH; si falta, `pkg install python` | Prerrequisito de theHarvester/sqlmap |
| 4 | **theHarvester** (OSINT) | `pip install --no-deps git+https://github.com/laramies/theHarvester.git` + instalación manual del resto de dependencias (leídas dinámicamente del `pyproject.toml` del repo) + un **stub local de `playwright`** | Ver sección 5 |
| 5 | **sqlmap** | `pip install sqlmap` | Junto con nmap, usa `error` (aborta) si falla |
| 5b | **MVT (Mobile Verification Toolkit)** | `pip install mvt` | Herramienta real de Amnesty International — forense de spyware/stalkerware (IOCs tipo Pegasus) en el PROPIO dispositivo, vía ADB sin root o análisis de backup. Verificado con `mvt-android version` (el CLI, basado en Click, no expone `--version` a nivel raíz). Categoría "autodefensa" |
| 5c | **ClamAV** | `pkg install clamav` | Antivirus open-source, escaneo de archivos/descargas del propio dispositivo. Tras instalar corre `freshclam` best-effort con timeout 90 s (no crítico: `clamscan` funciona con la base del paquete). Categoría "autodefensa" |

Verificación: usa `verify_binary_installed` en theHarvester, sqlmap, MVT y ClamAV (`clamscan`); nmap/dirb/netcat/nikto usan solo `command -v` o chequeo de archivo, sin ejecutar el binario.

### 4.1 Por qué `nikto` va en su propio paso

`dirb` **sí** es un paquete real de Termux (`dirb_2.22-5_aarch64.deb`), pero cuando estaba en la misma línea `pkg install netcat-openbsd dirb nikto`, y `nikto` nunca existió como paquete de ningún repo oficial, `apt`/`pkg` resolvía **todos** los nombres de una misma instalación antes de instalar ninguno — el fallo de resolución de `nikto` se llevaba a `dirb` con él. Por eso `nikto` se separó a su propio paso (2b) con git clone + wrapper Perl.

### 4.2 MVT + ClamAV — categoría "autodefensa del dispositivo"

Distinta del resto del catálogo: nmap/netcat/dirb/nikto/theHarvester/sqlmap (y Kali en nivel pro) apuntan **hacia afuera** (recon/pentesting de otra red o sistema). MVT y ClamAV apuntan **hacia adentro** — protegen el propio dispositivo, sin necesitar un objetivo externo ni autorización de terceros. **No tienen ningún control de UI** (se instalan, pero solo se pueden usar a mano desde terminal): MVT (`mvt-android check-adb --output <dir>`, `mvt-android check-backup`) requiere un flujo distinto (analiza IOCs contra indicadores descargados); ClamAV: `clamscan <ruta>`. Un panel nativo dedicado queda como mejora candidata.

## 5. theHarvester — el paso más frágil (PASO 4)

Dos causas raíz documentadas en comentarios del script (líneas 246-286):

1. **El paquete `theHarvester` de PyPI es un placeholder abandonado** (v0.0.1, febrero 2019, sin `entry_points`). `pip install theHarvester` terminaba con exit 0 pero nunca dejaba el comando en PATH. Fix: instalar directo del repo real (`laramies/theHarvester`) con `pip install git+https://...`, que sí define `[project.scripts] theHarvester = "theHarvester.theHarvester:main"`.
2. **`playwright` (dependencia dura de theHarvester desde su v4.6.0) no tiene wheel para Bionic libc en ninguna versión** (investigado entre 1.42.0 y 1.60.0) — límite real de la plataforma (embebe binarios de Chromium, PyPI solo publica wheels manylinux/macOS/Windows). Fix de 3 partes:
   - `pip install --no-deps` para el paquete theHarvester (salta *todas* las dependencias para no abortar la resolución completa).
   - El resto de dependencias se instalan a mano, **leídas dinámicamente** del `pyproject.toml` del repo (vía `tomllib` + `urllib.request` en un heredoc Python, líneas 301-324) en vez de una lista hardcodeada. Si la descarga/parseo falla, cae a un fallback hardcodeado (línea 327).
   - Un **stub local de `playwright`** escrito en el `purelib` real del intérprete (`sysconfig.get_paths()['purelib']`, `site-packages/playwright/{__init__,async_api}.py`) — necesario porque `__main__.py` de theHarvester hace `from playwright.async_api import async_playwright` de forma **incondicional** al arrancar. El stub define `async_playwright()` para que lance un `RuntimeError` con mensaje claro solo si de verdad se usa `--screenshot`; todos los demás motores OSINT funcionan normal.

Todos los fallos de este paso son `warn` (no crítico), así que sqlmap se sigue intentando aunque theHarvester falle.

## 6. Nivel pro — Kali Linux vía proot-distro (PASOS 6-8, solo si `$PRO=true`)

| Paso | Qué hace | Detalle |
|---|---|---|
| 6 | Instala `proot-distro` si falta | Misma llamada que `_install_proot_distro()` de `modulos/entorno.sh` |
| 7 | Crea el contenedor `kali` | `proot-distro install kalilinux/kali-rolling -n kali` (2 intentos). El chequeo "¿ya existe?" usa `proot-distro list` (el subcomando `list-installed` ya no existe en `proot-distro` v5.8.0) |
| 7b | Metapaquete de Kali | `proot-distro login kali -- apt-get install -y <metapaquete>` envuelto en `timeout 900` (15 min); por defecto `kali-tools-top10`, no `kali-linux-everything` (alcance responsable). Si falla o excede el timeout: `warn` + `_KALI_TOOLS="kali-tools-top10-failed"` y el contenedor queda usable headless, y el script igual llega al `registry_write` final. Los metapaquetes grandes (`large`, `everything`) probablemente se quedan cortos en 900 s en redes lentas |
| 8 (solo `pro-gui`) | XFCE4 + dbus-x11 dentro del contenedor | Reutiliza `~/scripts/entorno/distro_setup_gui.sh` (el mismo script generado por `modulos/entorno.sh` para cualquier distro proot); si el módulo Entorno todavía no corrió, este paso corre `entorno.sh --silent` primero. Se respeta el exit code real de ese script (que verifica `startxfce4`): si falla, `_GUI_STATUS` queda en `headless` y el contenedor sigue siendo usable |

**Por qué no hay un alias `kali` oficial de proot-distro:** `proot-distro` v5.x dejó de tener una lista curada de distros con alias fijos; instala **cualquier** imagen Docker/OCI por referencia. Por eso se usa la imagen oficial `kalilinux/kali-rolling` de Docker Hub (trae los repos apt de Kali ya configurados) y se le fuerza el nombre `kali` con `-n`/`--override-alias`, para que el resto del ecosistema (`proot-distro login kali`, `entorno.sh --diagnose`, `gui_start.sh --distro kali`) lo encuentre.

**Kali también está en el catálogo de distros de Mini PC (Entorno):** `EntornoNative.KNOWN_DISTROS`/`EXPERIMENTAL` incluyen `"kali"`; `distroInstall()` especial-casea `name == "kali"` para instalar la imagen OCI real (`kalilinux/kali-rolling -n kali`). Instalar Kali desde Mini PC usa la misma imagen y el mismo nombre de contenedor (`kali`) que Ciberseguridad Pro, así que si el usuario ya tiene un contenedor `kali` de cualquiera de los dos caminos, el otro lo detecta como "ya instalado". En el diálogo "Instalar distro" aparece como "experimental".

**Una limitación conocida del paso 7b:** un fallo de red o de memoria durante la descarga pesada de metapaquetes (ej. `metasploit-framework`/`wireshark` dentro de `kali-tools-top10`) puede matar al proceso hijo `apt-get` dentro de `proot login`; `ModuleController.installModule()` no tiene timeout en `process.waitFor()` para instalaciones (a diferencia de `SCRIPT_TIMEOUT_SECONDS=120s` para start/stop). Por eso el `timeout 900` acota el síntoma, pero no garantiza que el metapaquete complete en redes lentas; si persiste, habría que reducir el alcance de PASO 7b (paquetes más livianos o instalarlos uno por uno con reintentos).

## 7. Registry

Al finalizar, `registry_write ciberseguridad` escribe (también escribe temprano tras el básico con tier=basico y tras confirmar el contenedor, `:~860-885`; si Kali falla se registra como básico):

```
installed=true
tier=basico|pro
kali_container=kali|""      (vacío si tier=basico)
kali_gui=true|false
tools=nmap,netcat,dirb,nikto,theharvester,sqlmap,mvt,clamav[,proot-distro,kali(<kali_tools>:<gui_status>)]
install_date=YYYY-MM-DD
```

`notify_event "ciberseguridad" "install_done" "$_tools"` dispara la notificación de instalación completada.

## 8. UI (`CiberseguridadFragment.kt`)

Lee el `ModuleRegistry` para decidir qué mostrar — **no reinstala nada por su cuenta**, solo lee `ciberseguridad.tier` / `kali_container` / `kali_gui`.

**Banner y gate de uso responsable.** Hay dos capas (key `ciberseguridad_disclaimer_accepted` en `SharedPreferences("kairos_prefs")`, distinta de `PREF_TOS_ACCEPTED`):
1. **Banner fijo siempre visible** (`addDisclaimerBanner()`) — card "⚠ USO RESPONSABLE" arriba de todo: *"Usá estas herramientas solo contra sistemas propios o con autorización explícita — el mal uso puede ser ilegal."* + botón "Ver detalle legal completo" que expande el texto largo en un diálogo (reusa `showScanResultDialog()`).
2. **Gate de aceptación única** (`showDisclaimerGate()`) — diálogo no cancelable (`setCancelable(false)`) con checkbox "Entiendo y acepto usar estas herramientas de forma responsable y legal", que habilita el botón positivo solo cuando está tildado. Se muestra ANTES de renderizar el resto del módulo la primera vez que el usuario entra con el módulo instalado (`buildContent()` corta con `return` si `!isDisclaimerAccepted()`). No vuelve a pedirse una vez aceptado.

Además, `sqlmap --dump` (`promptSqlmapDump()`) muestra un diálogo adicional "¿Extraer datos de...?" por ser la acción más sensible (puede exponer datos personales/credenciales reales).

**Card "🌐 RED"** (siempre visible, no es una herramienta):

| Control | Qué hace | Por qué |
|---|---|---|
| "IP local" (solo lectura) | `RemoteManager.getLocalIp()` (misma lógica que el módulo Remote, DRY) | Mostrar datos reales del dispositivo |
| "Nivel instalado" / "Contenedor Kali" / "Interfaz gráfica" (solo lectura) | Lee `tier`/`kali_container`/`kali_gui` del registry | El Fragment solo LEE el nivel elegido al instalar |
| "🔍 Escanear dispositivos en la LAN (nmap -sn)" | `scanLan()` → `nmap -sn <subred>/24` en background, timeout 25 s, nunca automático (solo al tocar el botón). Parsea con el regex `Nmap scan report for (\S+)(?:\s+\(([\d.]+)\))?` y lista host+IP | NO usa `RemoteManager.scanSubnet()` porque ese algoritmo es específico para health-check HTTP de servidores OpenCode/agentes en un puerto fijo; acá se necesita descubrimiento genérico |

**Pestañas "Reconocimiento / Web / Pro"** (`TabLayout` debajo de la card RED; `buildToolsTabs()` / `renderToolsTab()`, patrón igual a `EntornoFragment`; `tabCard()` es una copia local de `BaseModuleFragment.addCard()` que agrega a un `parent` explícito). Cada herramienta (salvo sqlmap) tiene un **panel nativo de "escaneo rápido"** (diálogo con target + preset de flags oficiales, parseo del stdout a una lista, resultado en `AlertDialog` monoespaciado) MÁS un botón "terminal completo" que abre el binario sin argumentos para flags avanzados. Ningún panel corre sin tocar el botón. Timeouts: nmap 45 s, nikto 45 s (`-maxtime 30s`), dirb 45 s, sqlmap 90/120/150 s, theHarvester 60 s. Los comandos corren con `ManagerNativeUtils.runShell(cmd, timeout)` (`bash -c`) en un hilo de fondo; los "terminal completo" usan `launchTerminalCommand`.

*Pestaña "Reconocimiento"* (`:442-449`):

| Control | Qué hace | Por qué |
|---|---|---|
| "🎯 nmap — escaneo rápido de puertos (panel nativo)" | `promptNmapScan()` — pide target, corre `nmap -F -T4 -sV` (o `-sS` real vía `RootAccess.runAsRoot` si hay root, `:663-666`), parsea tabla de puertos | Un nmap sin target no hace nada útil; `-A` se descartó por ser más lento/intrusivo para un panel de escaneo rápido |
| "⌨ nmap (terminal completo)" | `launchTerminalCommand("nmap")` | Flags avanzados |
| "🔎 theHarvester — OSINT rápido (panel nativo)" | `promptTheHarvesterScan()` — dominio + fuente (spinner de 7 fuentes sin API key: `duckduckgo, bing, crtsh, otx, rapiddns, threatminer, urlscan`) + límite (`-d -b -l`) | Las fuentes con credenciales siguen sin panel — Kairos no gestiona `~/.theHarvester/api-keys.yaml` |
| "⌨ theHarvester (terminal completo)" | `launchTerminalCommand("theHarvester")` | Fuentes con API key ya configurada aparte |

*Pestaña "Web"* (`:451-474`):

| Control | Qué hace | Por qué |
|---|---|---|
| "🕷 nikto — escaneo rápido de vulnerabilidades web (panel nativo)" | `promptNiktoScan()` — target + categoría `-Tuning` (dropdown 1/2/3/4/9/b, default "1"), corre `nikto -h <target> -Tuning <categoría> -maxtime 30s` | `-Tuning`/`-maxtime` acotan el scan; flags de la wiki oficial de nikto |
| "⌨ nikto (terminal completo)" | `launchTerminalCommand("nikto")` | — |
| "📁 dirb — fuerza bruta rápida de directorios (panel nativo)" | `promptDirbScan()` — target + wordlist (`common.txt` default / `big.txt`, ambos empaquetados por `pkg install dirb` en `$PREFIX/share/dirb/wordlists/`), corre `dirb <target>[ wordlist] -S -w -r` | `-r` (no recursivo) acota el scan a un nivel |
| "⌨ dirb (terminal completo)" | `launchTerminalCommand("dirb")` | — |
| Dropdown "💉 sqlmap — elegir acción" | 4 opciones excluyentes: prueba rápida (`--batch`) / listar bases (`--dbs`) / listar tablas (`--tables -D`) / volcar tabla (`--dump`) | No encaja en `dropdownSwitchRow()`: se usa `dropdownRow()` + botón "Ejecutar" separado |
| "▶ Ejecutar acción sqlmap elegida" | Despacha a `promptSqlmapScan()`/`promptSqlmapDbs()`/`promptSqlmapTables()`/`promptSqlmapDump()`. La prueba rápida pide además `--level` (1-5) y `--risk` (1-3), default 1/1 | Flags oficiales de sqlmap |
| "⌨ sqlmap (terminal completo)" | `launchTerminalCommand("sqlmap")` | — |

*Pestaña "Pro"* (Kali, `:476-498`):

| Control | Qué hace | Por qué |
|---|---|---|
| "☠ Entrar a Kali (proot-distro)" (solo si `isPro`: tier=pro y hay `kaliContainer`) | `proot-distro login <kaliContainer>` en la terminal | — |
| "🖥 Iniciar escritorio Kali (XFCE)" (solo si `isPro` y `kaliGui`) | `bash ~/scripts/entorno/gui_start.sh --distro <kaliContainer>` | Solo aparece si el nivel instalado tiene GUI |
| "⬆ Instalar nivel Pro (Kali)" (si NO es pro) | `showProInstallDialog()` (`:490-495, 1083-1097`): elección headless/GUI y luego de categoría Kali (13), instala en segundo plano | — |

**Instalación silenciosa** (antes de instalar): diálogo "Básico" / "Pro sin GUI" / "Pro con GUI" (`:1100-1150`), `installModuleInBackground(variant)` — instala en background, la ventana se cierra y el usuario sigue usando la app; avisa al terminar.

**Card "MANTENIMIENTO"** — "🔄 Actualizar" (`updateModuleService`) y "🗑 Desinstalar" (`ModuleController.uninstallModule`), patrón estándar compartido.

## 9. Herramientas evaluadas y no adoptadas como panel nativo

| Herramienta | Viable en panel nativo (bionic, sin root) | Veredicto |
|---|---|---|
| **hydra** (fuerza bruta de credenciales) | Sí — binario C liviano, sin root ni modo monitor; Termux la tiene en su repo (`pkg install root-repo && pkg install hydra`). Encajaría en el patrón de panel existente (target + wordlist + protocolo) | Candidata viable técnicamente; **no implementada** |
| **metasploit-framework** (`msfconsole`) | No — framework Ruby completo (cientos de MB, base de datos PostgreSQL opcional, miles de módulos). Solo tiene sentido dentro del contenedor Kali (`kali-tools-top10` lo incluye) | No recomendada como panel nativo; a lo sumo un atajo `proot-distro login kali -- msfconsole` |
| **wireshark / tshark** | No para captura en vivo — requiere raw socket (`AF_PACKET`/`libpcap`), que Android bloquea a apps sin root. `tshark` podría analizar un `.pcap` ya existente (análisis offline) | No recomendada sin root |
| **aircrack-ng** | No — requiere modo monitor del chipset WiFi, que en Android estándar casi nunca se expone | No recomendada (limitación del hardware/driver) |

El catálogo de utilidades adicionales de proyectos tipo i-Haklab (androforensic, payloads, esteganografía, etc.) es de perfil más ofensivo/gris y queda fuera del criterio de "diagnóstico de red propia y pentesting autorizado estándar" de la sección 1.

## 10. Limitaciones conocidas

- **theHarvester `--screenshot` no funciona** en ningún nivel — depende de un Chromium real vía playwright, que no tiene wheel para Bionic libc; el stub deja un mensaje de error claro.
- **El metapaquete Kali puede quedar incompleto** si `apt-get install` falla o excede el timeout dentro del contenedor — el script no aborta; el contenedor queda usable sin garantía de que todas las herramientas estén instaladas. Cualquier herramienta adicional dentro del contenedor Kali hay que instalarla a mano (`proot-distro login kali -- apt-get install <paquete>`).
- **Inyección de comandos (por lectura de código):** el objetivo escrito por el usuario se interpola entre comillas simples dentro de un string `bash -c` sin escapar comillas simples: `nmap ... '$target'` (`:666`), `nikto -h '$target'` (`:738`), `sqlmap -u '$target'` (`:878`, `:1010`, `:1027`, `:1058`; también `-D '$db' -T '$table'`), `theHarvester -d '$domain'` (`:955`), `dirb '$target'` (`:807`). Un valor con `'` rompe el quoting y ejecuta comandos arbitrarios con los permisos de Kairos. El riesgo es auto-infligido (el usuario escribe el valor), pero es un defecto real.
- **MVT y ClamAV** instalados sin UI.
- **Verificación débil** (`command -v`) para nmap/dirb/netcat/nikto.
- `requiresProot:false` en `modules.json` pese a que el pro depende de proot-distro; `terminalCommand: "nmap"` es el acceso genérico.
- Sin sha256 en ninguna descarga (git clone, pip, imagen proot-distro, pyproject por HTTPS). nikto se clona de `master` de GitHub y theHarvester desde `git+https` sin pin de commit ni hash. Nada de `curl|bash`.
- No guarda API keys; las fuentes de theHarvester con clave quedan para la terminal.
- Inferencia: el workaround de udisks2 (`:~790`) está marcado por el propio script como no confirmado empíricamente.

## 11. Fecha de alta

`modulos/ciberseguridad.sh`: 2026-08-12.
