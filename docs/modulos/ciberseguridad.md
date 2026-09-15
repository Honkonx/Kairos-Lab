# Ciberseguridad

**Módulo de Kairos** — gestionado vía la UI de Kairos (Módulos → tarjeta "Ciberseguridad").
Kit de herramientas de red/OSINT/pentesting en 2 niveles: un nivel **básico** 100% nativo
(bionic, sin proot) y un nivel **pro** que agrega un contenedor Kali Linux completo vía
`proot-distro`, con o sin interfaz gráfica.

---

**Script:** `modulos/ciberseguridad.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/CiberseguridadFragment.kt`
**`id` en `modules.json`:** `ciberseguridad` — `hasSwitch: false`, `hasVariants: true`,
`requiresProot: false` (el proot solo se usa condicionalmente dentro del script para el nivel
pro), `arch: bionic`, `category: seguridad`, `terminalCommand: nmap`

---

## 1. Alcance y responsabilidad

Herramientas estándar de red/OSINT/pentesting, pensadas para diagnóstico de la propia red del
usuario y pentesting autorizado. Un catálogo más amplio de herramientas ofensivas (bruteforce,
automatización de Metasploit, forense Android, servidores de práctica vulnerables) queda fuera
de alcance deliberadamente.

## 2. Permisos

Ninguno propio de Android — corre en espacio de usuario Termux (bionic nativo para el nivel
básico, `proot-distro` para el nivel pro), sin root, sin permisos adicionales al asistente
genérico de primer uso de Kairos. El nivel pro descarga un contenedor de varios cientos de MB —
solo requiere espacio y red, no un permiso de Android.

## 3. Variantes / niveles (`--variant`)

`ciberseguridad.sh` acepta `--silent [--force] [--variant basico|pro-headless|pro-gui]
[--describe]`. La variante se elige al instalar, no hay switch central en tiempo de ejecución —
para cambiar de nivel hay que reinstalar con otra variante.

| Variante (`--variant`) | Tier interno | Qué instala |
|---|---|---|
| `basico` (default) | `PRO=false` | Solo el kit nativo (PASOS 1-5) |
| `pro-headless` (alias `pro`) | `PRO=true`, `PRO_GUI=false` | Básico + contenedor Kali sin escritorio |
| `pro-gui` | `PRO=true`, `PRO_GUI=true` | Básico + contenedor Kali + XFCE4 dentro del contenedor |

**Chequeo de "ya instalado"**: solo evalúa el nivel básico (`command -v nmap && theHarvester &&
sqlmap`). Si el básico ya está pero se pide `--variant pro-*`, el script no sale — sigue directo
a los pasos 6-8 (Kali), permitiendo pasar de básico a pro sin reinstalar el básico. `--force`
borra el checkpoint y fuerza repetir todos los pasos pendientes.

## 4. Nivel básico (PASOS 1-5, bionic nativo)

Cada paso tiene su propio checkpoint — reentrante, si el script se corta a mitad retoma desde el
siguiente paso sin repetir los ya hechos.

| Paso | Herramienta | Cómo se instala |
|---|---|---|
| 1 | **nmap** | `pkg install nmap` |
| 2 | **netcat-openbsd + dirb** | `pkg install netcat-openbsd dirb` |
| 2b | **nikto** | `git clone --depth 1 https://github.com/sullo/nikto.git ~/.nikto` + wrapper bash en `$PREFIX/bin/nikto` que hace `exec perl ~/.nikto/program/nikto.pl "$@"` — nikto no es un paquete oficial de Termux |
| 3 | **Python 3** | Detecta `python`/`python3` en PATH; si falta, `pkg install python` |
| 4 | **theHarvester** (OSINT) | `pip install --no-deps git+https://github.com/laramies/theHarvester.git` + instalación manual del resto de dependencias reales (leídas dinámicamente del `pyproject.toml` del repo) + un stub local de `playwright` (ver §5) |
| 5 | **sqlmap** | `pip install sqlmap` |
| 5b | **MVT (Mobile Verification Toolkit)** | `pip install mvt` |
| 5c | **ClamAV** | `pkg install clamav` (corre `freshclam` best-effort tras instalar) |

### 4.1 MVT y ClamAV — categoría "autodefensa del dispositivo"

A diferencia del resto del catálogo (nmap/netcat/dirb/nikto/theHarvester/sqlmap y Kali en el
nivel pro, que apuntan hacia afuera — recon/pentesting de otra red o sistema), MVT y ClamAV
apuntan hacia adentro: protegen el propio dispositivo del usuario, sin necesitar ningún objetivo
externo ni autorización de terceros para usarse.

- **MVT** (herramienta real de Amnesty International) hace forense de spyware/stalkerware (IOCs
  tipo Pegasus) en el propio dispositivo, vía ADB sin root o análisis de backup
  (`mvt-android check-adb`/`check-backup`). No tiene panel nativo en la pantalla del módulo
  todavía — su flujo real (analiza indicadores de compromiso contra un directorio de salida, no
  un simple target+flags) requiere un diseño de UI distinto al resto de herramientas, así que por
  ahora se usa desde la terminal.
- **ClamAV** es un antivirus open-source real para escanear archivos/descargas del propio
  dispositivo (`clamscan <ruta>`). Tampoco tiene panel nativo todavía — el uso directo por
  terminal es lo suficientemente simple como para no necesitar un diálogo dedicado por ahora.

## 5. theHarvester

Dos limitaciones reales de plataforma que el script resuelve:

1. **El paquete `theHarvester` de PyPI es un placeholder abandonado**, sin `entry_points` — se
   instala directo del repo real (`laramies/theHarvester`) vía `pip install git+...`.
2. **`playwright` (dependencia dura de theHarvester) no tiene wheel para Bionic libc** en
   ninguna versión — límite real de la plataforma (embebe binarios de Chromium, PyPI solo
   publica wheels manylinux/macOS/Windows). Solución de 3 partes: instalar theHarvester con
   `--no-deps`, instalar el resto de dependencias reales leídas dinámicamente del
   `pyproject.toml` del repo, y un **stub local de `playwright`** que define `async_playwright()`
   para lanzar un error claro solo si de verdad se usa `--screenshot` — todos los demás motores
   de búsqueda OSINT funcionan normal.

Un fallo en este paso es no-crítico (`warn`), así que el paso 5 (sqlmap) se sigue intentando
igual aunque theHarvester falle.

## 6. Nivel pro — Kali Linux vía proot-distro (PASOS 6-8, solo si `$PRO=true`)

| Paso | Qué hace |
|---|---|
| 6 | Instala `proot-distro` si falta |
| 7 | Crea el contenedor `kali` — `proot-distro install kalilinux/kali-rolling -n kali` |
| 7b | `proot-distro login kali -- apt-get install -y kali-tools-top10` — metapaquete oficial curado de Kali, no `kali-linux-everything`. Fallo es no-crítico — el contenedor queda usable igual |
| 8 (solo `pro-gui`) | XFCE4 + dbus-x11 dentro del contenedor — reutiliza el mismo script generado por el módulo Entorno para cualquier distro proot |

No existe un alias `kali` oficial en `proot-distro` desde su versión 5 (instala cualquier imagen
Docker/OCI por referencia) — se usa la imagen oficial `kalilinux/kali-rolling` de Docker Hub y se
le fuerza el nombre `kali` con `-n`/`--override-alias`, para que el resto del ecosistema
(`proot-distro login kali`, el módulo Entorno, el arranque de escritorio) lo encuentre con el
nombre esperado.

El paso 8 respeta el exit code real del script de instalación de escritorio (que verifica
`startxfce4`) — si falla, el contenedor queda registrado como headless y sigue siendo usable sin
escritorio.

## 7. Registry

```
installed=true
tier=basico|pro
kali_container=kali|""      (vacío si tier=basico)
kali_gui=true|false
tools=nmap,netcat,dirb,nikto,theharvester,sqlmap,mvt,clamav[,proot-distro,kali(<kali_tools>:<gui_status>)]
install_date=YYYY-MM-DD
```

## 8. UI real (`CiberseguridadFragment.kt`)

Fragment dedicado. Lee el registry para decidir qué mostrar — no reinstala nada por su cuenta.

- **Card "🌐 RED"**: IP local, nivel instalado, contenedor Kali/interfaz gráfica (si es pro), y
  botón **"🔍 Escanear dispositivos en la LAN (nmap -sn)"** — corre `nmap -sn <subred>/24` en
  background con timeout de 25s, parsea hosts vivos.
- **Pestañas "Reconocimiento / Web / Pro"**:
  - **Reconocimiento**: nmap y theHarvester, cada uno con panel nativo de "escaneo rápido"
    (diálogo con target + preset de flags oficiales, resultado parseado en un diálogo
    monoespaciado) más botón "terminal completo" para flags avanzados.
  - **Web**: nikto (con selector de categoría `-Tuning`), dirb (con selector de wordlist
    `common.txt`/`big.txt`) y sqlmap (dropdown de 4 acciones: prueba rápida `--batch` / listar
    bases `--dbs` / listar tablas `--tables` / volcar tabla `--dump`, con `--level`/`--risk`
    opcionales en la prueba rápida y advertencia extra antes de `--dump`).
  - **Pro**: si el nivel instalado es pro, botón "☠ Entrar a Kali (proot-distro)" y, si tiene
    GUI, "🖥 Iniciar escritorio Kali (XFCE)"; si no es pro, redirige al selector de variante de
    instalación.
- **Card "MANTENIMIENTO"** — Actualizar/Desinstalar, patrón estándar del resto de módulos.

Todos los paneles nativos requieren tocar el botón explícitamente — ninguno corre sin acción del
usuario.

## 9. `modules.json` — declaración real

```json
{
  "id": "ciberseguridad",
  "name": "Ciberseguridad",
  "script": "ciberseguridad.sh",
  "size": "~30MB (pro: +varios cientos de MB por el contenedor Kali)",
  "type": "Nativo",
  "requiresProot": false,
  "hasVariants": true,
  "estimate": "~2 min (básico) / ~10-20 min (pro, según red)",
  "hasSwitch": false,
  "terminalCommand": "nmap",
  "arch": "bionic",
  "category": "seguridad",
  "installMethods": ["pkg", "pip"],
  "requires": ["python"],
  "recommended": false
}
```

## 10. Kali en el catálogo de distros de Mini PC

El catálogo de distros del tab "Mini PC" (independiente del contenedor Kali que instala este
módulo) también incluye `kali` como opción — instala la misma imagen OCI (`kalilinux/kali-rolling
-n kali`) que usa este módulo. Si el usuario ya tiene un contenedor `kali` de cualquiera de los
dos caminos, el otro lo detecta como "ya instalado" sin duplicar. Está marcada como distro
experimental (menos probada en dispositivos reales que ubuntu/debian/alpine).

## 11. Uso responsable — disclaimer en la app

La pantalla del módulo muestra:

1. **Banner fijo siempre visible** — card "⚠ USO RESPONSABLE" arriba de todo, con texto corto y
   un botón "Ver detalle legal completo" que expande el texto largo en un diálogo.
2. **Gate de aceptación única** — diálogo no cancelable con checkbox "Entiendo y acepto usar
   estas herramientas de forma responsable y legal", que habilita el botón positivo solo cuando
   está tildado. Se muestra la primera vez que el usuario entra con el módulo ya instalado, antes
   de renderizar el resto de la pantalla. No vuelve a pedirse una vez aceptado.

Además, la acción `sqlmap --dump` (extracción real de datos) muestra un diálogo de advertencia
adicional por ser la acción más sensible del catálogo.

## 12. Herramientas evaluadas y no adoptadas (nivel básico)

Para mantener el nivel básico 100% nativo (bionic, sin root), se evaluaron y descartaron por
limitación real de plataforma:

- **wireshark/tshark** (captura en vivo) — requiere raw sockets, bloqueados en Android sin root.
- **aircrack-ng** (modo monitor WiFi) — el driver del chip WiFi de la inmensa mayoría de
  dispositivos Android sin root no expone modo monitor a espacio de usuario.
- **metasploit-framework** — framework Ruby completo (cientos de MB, base de datos opcional), no
  encaja en un panel nativo liviano; su uso real queda dentro del contenedor Kali Pro.

**hydra** (fuerza bruta de credenciales) sí es viable técnicamente (binario C liviano, sin root,
disponible como paquete de Termux) y queda como candidata para una futura expansión del nivel
básico.

## 13. Limitaciones conocidas

- **theHarvester `--screenshot` no funciona** en ningún nivel — depende de un Chromium real vía
  playwright, sin wheel para Bionic libc.
- **`kali-tools-top10` puede quedar incompleto** si la instalación dentro del contenedor falla o
  se corta por límite de tiempo — el script no aborta, el contenedor queda usable pero sin
  garantía de que las 10 herramientas top de Kali estén todas instaladas.
- **El chequeo de "ya instalado" no verifica el nivel pro** — solo mira si el básico está en
  PATH.
