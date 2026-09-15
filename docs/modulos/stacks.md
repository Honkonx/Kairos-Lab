# Stacks / Entornos de Prueba

**Módulo de Kairos** — gestionado desde la UI (pantalla "Entornos de Prueba"). A diferencia del resto de `modulos/*.sh`, `stacks.sh` **no instala un paquete propio** — es un catálogo que reutiliza módulos ya existentes (`python.sh`, `db.sh`) y agrega Node.js/PHP directo vía `pkg`.

---

**Script:** `modulos/stacks.sh`
**Fragments:** `app/src/main/java/com/termux/app/ui/StacksFragment.kt` (presets fijos) + `StacksProjectFragment.kt` (proyecto real)
**`id` en `modules.json`:** `stacks` — sin switch central (cada preset/acción invoca el script directo, no pasa por el ciclo de instalación estándar de módulos)

---

## 1. Descripción general

Stacks (pantalla "Entornos de Prueba") tiene dos modos totalmente distintos, cada uno con su propio Fragment:

1. **Presets fijos** (`StacksFragment.kt`) — recetas prearmadas: elegir un stack conocido (Python+PostgreSQL, PHP+MySQL, React+Vite, HTML vanilla, o una distro Linux completa) e instalarlo con un par de toques, nativo en Termux o dentro de una distro proot.
2. **Proyecto real** (`StacksProjectFragment.kt`) — opera sobre una carpeta de proyecto ya elegida por el usuario: detecta el stack por archivos presentes, instala dependencias reales (`npm install` / `venv+pip`), y arranca/monitorea el proceso en background (tmux), con opción de exponerlo por Cloudflare Tunnel.

## 2. Permisos

Ninguno propio — usa los mismos permisos ya otorgados por el asistente de configuración de Kairos (almacenamiento para el navegador de carpetas del modo Proyecto). No abre puertos propios (los procesos que arranca sí, según lo que el usuario ponga como comando).

## 3. Presets fijos (`StacksFragment.kt` + `modulos/stacks.sh --preset`)

`stacks.sh` acepta `--silent --preset <id> [--distro <nombre>] [--flavor debian|ubuntu] [--force] [--describe]`.

| Preset | Piezas nativo (Termux, `pkg`) | Piezas distro (`apt-get` dentro de proot) |
|---|---|---|
| `python-postgres` | `pkg python` + PostgreSQL vía `db.sh` encadenado | `python3 python3-pip postgresql` |
| `php-mysql` | `pkg php` + MySQL/MariaDB vía `db.sh` encadenado | `php php-mysql mysql-server` |
| `react-vite` | `pkg nodejs` (cubre React/Vite/TypeScript/JavaScript) | `nodejs npm` |
| `html` | `pkg python` (sin build, `python3 -m http.server`) | `python3` |
| `linux-completo` | — (siempre proot-distro, nunca nativo) — instala una distro Linux real completa (Debian Bookworm por defecto, o Ubuntu con `--flavor ubuntu`) | — |

**Discrepancia script/UI**: el script soporta los 5 presets (incluido `linux-completo`), pero `StacksFragment.kt` solo expone 4 tarjetas. Para instalar una distro Linux completa desde la app hay que usar el módulo **Entorno** ("Instalar distro"), que cubre el mismo caso de uso con su propio flujo — `stacks.sh --preset linux-completo` queda disponible solo por CLI/script, sin botón en esta pantalla.

**Destino** (`--distro <nombre>`, aplica solo a los 4 presets livianos): si se pasa, instala dentro de una distro proot ya instalada en vez de nativo. La UI deshabilita el botón "Instalar en distro…" si no hay ninguna distro instalada.

**Base de datos encadenada**: los presets `python-postgres`/`php-mysql` corren `bash db.sh --silent` en vez de solo avisar — `db.sh` es idempotente (chequea binarios antes de instalar).

**Resumen final**: cada preset termina imprimiendo los comandos reales para arrancar cada pieza — la UI las filtra y muestra en el diálogo de éxito.

## 4. Modo Proyecto real (`StacksProjectFragment.kt` + `modulos/stacks.sh --project-path`)

Flags: `--project-path <carpeta> --project-action detect|install|start|stop|status|logs [--project-target native|distro|udocker] [--project-distro <nombre>] [--project-cmd <comando>] --silent`.

### 4.1 Detección de stack

Detector simple basado en archivos presentes (deliberadamente no exhaustivo), implementado en el script y en Kotlin por igual, misma lógica en ambos lados:

| Señal en la carpeta | Tag |
|---|---|
| `package.json` | `node` |
| `requirements.txt` o algún `*.py` | `python` |
| `*.db`/`*.sqlite`/`*.sqlite3` | `sqlite` |
| `composer.json` | `php` |
| `index.html` sin `package.json` | `html` |

### 4.2 Destinos de instalación (`--project-target`)

| Destino | Qué hace realmente | Nivel de automatización |
|---|---|---|
| `native` (default) | `npm install` si hay `package.json`; `python3 -m venv venv` + `pip install -r requirements.txt` si hay Python | Completo — instala Node/Python si faltan |
| `distro` | Copia el proyecto dentro del rootfs real de la distro, instala las dependencias de sistema que falten (`nodejs npm`, `python3 python3-pip`, `php`) vía `apt-get`, y corre `npm install`/`pip3 install -r requirements.txt` dentro de la distro | Completo — automatiza igual que `native`/`udocker`, con la salvedad honesta documentada en el propio script de que `apt-get`/`npm`/`pip` sobre proot son más lentos |
| `udocker` | El proyecto no se copia — se monta con bind-mount real sobre una imagen oficial de Docker Hub según el stack detectado (`node:20` / `python:3.12` / `php:8.3-cli`, `html` cae a `python:3.12`) | Completo — mismo nivel que `native`, sin copiar nada |

`composer.json` (PHP) se detecta en los 3 destinos pero Composer no se automatiza en ninguno — solo se avisa que hay que correr `composer install` a mano.

### 4.3 Arrancar / detener / estado / logs

- **`start`**: requiere `--project-cmd`. Arranca en una sesión tmux propia (hash MD5 de la ruta como identificador), con el comando envuelto según el destino. Log persistente en `~/kairos_logs/stacks_project_<hash>.log`.
- **`stop`**: mata la sesión tmux del proyecto.
- **`status`**: reporta si la sesión sigue viva.
- **`logs`**: `tail -n 200` del log persistente.

### 4.4 Pantalla `StacksProjectFragment.kt`

Cards: **PROYECTO** (elegir carpeta) → **DETECCIÓN** (tags + recomendación textual: PHP recomienda distro por dependencias de sistema más pesadas, el resto recomienda nativo) → **DESTINO DE EJECUCIÓN** (Nativo/Distro/udocker, el de Distro abre un selector de distros instaladas) → **DEPENDENCIAS** (botón instalar) → **EJECUCIÓN Y MONITOREO** (campo de comando editable, precargado con una sugerencia según el stack — `npm run dev -- --host` / `python3 app.py` / `python3 -m http.server 8080` —, arranque/detención, pill de estado, "Ver logs en vivo") → **EXPONER** (botón que pide un puerto y reusa el mismo mecanismo de túnel Cloudflare que el resto de la app).

## 4.5 Monorepos — detección de sub-proyectos

Cuando una carpeta agregada al modo Proyecto real contiene varios sub-proyectos independientes
(por ejemplo `frontend/` y `backend/` dentro de un mismo repo), Kairos puede detectarlos y
gestionarlos por separado en vez de forzar a tratar toda la carpeta como un solo stack:

- Al agregar una carpeta nueva (o pidiéndolo manualmente sobre un proyecto ya guardado con
  "Detectar sub-proyectos"), Kairos escanea las subcarpetas de primer nivel buscando su propia
  señal de stack (los mismos archivos que ya usa la detección normal — `package.json`,
  `requirements.txt`, `composer.json`, etc.), ignorando carpetas típicas de dependencias/build
  (`node_modules`, `.git`, `venv`, `dist`, `build`, y similares) para evitar falsos positivos.
  El escaneo es de un solo nivel, sin recursión — un sub-proyecto nunca se vuelve a escanear en
  busca de sub-sub-proyectos.
- Si se encuentra más de un sub-proyecto, un diálogo deja elegir cuáles tratar como
  sub-proyectos independientes (todos marcados por defecto) o descartar la detección y seguir
  tratando la carpeta como un proyecto simple de un solo stack.
- Cada sub-proyecto confirmado se comporta después exactamente como un proyecto normal
  completo — su propia detección, su propio destino de instalación (nativo/distro/udocker), su
  propio comando y su propia sesión en background — la única pieza nueva es el agrupamiento
  visual bajo el proyecto padre y un botón **"Iniciar todos"**.
- **"Iniciar todos"** arranca cada sub-proyecto en el orden en que aparecen en la lista, con un
  delay opcional entre uno y otro (útil, por ejemplo, para darle tiempo a un backend de estar
  arriba antes de arrancar el frontend que depende de él) — no hay resolución automática de
  dependencias entre sub-proyectos, es responsabilidad del usuario ordenar la lista o ajustar el
  delay según lo que necesite.
- Un botón "Detectar de nuevo" dentro de la vista de un monorepo agrega sub-proyectos nuevos que
  hayan aparecido después, sin tocar la configuración ya guardada de los que ya estaban.

## 5. Detección de estado

- **Instalación**: registry (`stacks.installed`) — se escribe `true` incluso sin ningún preset corrido.
- **"Corriendo"**: no aplica a nivel de módulo — cada proyecto individual del modo Proyecto real tiene su propio estado vía sesión tmux.

## 6. Registry (`~/.android_server_registry`)

```
stacks.installed=true
stacks.version=1.4.0
stacks.presets=python-postgres,php-mysql,react-vite,html,linux-completo   # solo si se corrió sin --preset
stacks.last_preset=<preset>       # tras correr un preset con --preset
stacks.last_target=native|distro|fulldistro
stacks.last_distro=<nombre o vacío>
stacks.last_flavor=debian|ubuntu
```

## 7. Notas de diseño

- **`linux-completo` no tiene botón en `StacksFragment.kt`** — no es un bug (el módulo Entorno cubre el mismo caso), pero puede confundir a quien solo lea el script y espere ver las 5 opciones en la UI.
- **udocker no expande `$HOME` en `--volume`** — requiere ruta absoluta, que ya es lo que trae la ruta del proyecto desde el navegador de carpetas de la app.
- **Docker real no es una opción** — sin root, el daemon de Docker (namespaces/cgroups del kernel) no es posible en Android; udocker (vía PRoot) es la alternativa real de "contenedores" en este entorno.

## Controles de la pantalla

### `StacksFragment.kt` — pantalla principal ("Entornos de Prueba")

| Control | Qué hace |
|---|---|
| "Proyecto (carpeta real)" | Navega a `StacksProjectFragment` |
| "Instalar nativo (Termux)" (por preset) | Corre `stacks.sh --preset <id> --silent` |
| "Instalar en distro…" (por preset) | Elige distro si hay más de una instalada, corre con `--distro <nombre>` — deshabilitado si no hay ninguna distro proot instalada |

### `StacksProjectFragment.kt` — "Proyecto" (pantalla propia)

| Control | Qué hace |
|---|---|
| Card "PROYECTOS": fila por carpeta | Selección múltiple de carpetas — el backend soporta proyectos concurrentes (sesión/log/hash por ruta) |
| "Agregar carpeta de proyecto" | Elige raíz (Home de Termux / almacenamiento interno), navega carpetas reales |
| Card "DETECCIÓN" (solo lectura) | Stack detectado + recomendación de destino |
| Dropdown "Destino" (Nativo/Distro/udocker) | Cambia el destino de instalación — "Distro" abre un sub-diálogo antes de confirmar |
| "Instalar dependencias reales" | `npm install`/`venv+pip install` reales, no simulado — el mensaje de confirmación cambia según el destino elegido |
| Campo de texto "comando a ejecutar" | Editable, pre-rellenado con una sugerencia según el stack detectado |
| Switch "Proceso en background" | Arranca/detiene el proyecto en tmux |
| "Ver logs en vivo" | Panel nativo con poll cada 2s del log del proyecto |
| "Terminal completo" (dentro del diálogo de logs) | Para logs muy largos donde scrollear/buscar en el overlay real es más cómodo |
| "Exponer con Cloudflare (túnel)" | Pide el puerto, reusa el mismo mecanismo de túnel que el resto de la app |

## Presets adicionales (go/rust/java/dotnet)

Nota: los presets viven en `StacksPackagesFragment.kt` ("Paquetes necesarios", accesible desde el detalle de un proyecto), no en `StacksFragment.kt`.

4 presets adicionales, confirmados contra paquetes reales de Termux y de Debian/Ubuntu:

| Preset | Nativo (Termux) | Distro (Debian/Ubuntu vía apt) |
|---|---|---|
| `go` | `pkg install golang` → binario `go` | `golang-go` (Debian empaqueta Go así) |
| `rust` | `pkg install rust` → binario `cargo` | `rustc cargo` (Debian los separa en 2 paquetes) |
| `java` | `pkg install openjdk-21` → binario `java`/`javac` | `default-jdk` (metapaquete real que resuelve al JDK disponible) |
| `dotnet` | Sin paquete nativo — el SDK de .NET solo se empaqueta para Ubuntu/Debian real vía apt, no existe para la libc de Termux | `dotnet-sdk-8.0` (LTS) — nota real agregada al resumen post-instalación: compilar puede fallar en algunos dispositivos por un problema conocido de `csc.dll` en ARM64/proot, limitación real de .NET en este entorno |

`dotnet` es un caso aparte en la UI: al no tener instalación nativa, su fila no ofrece el botón "instalar nativo" normal — tocarla abre directamente el selector de distro.

**Detección de proot-distro/udocker**: `modulos/stacks.sh` tiene guards reales que avisan claramente al usuario si `proot-distro` o `udocker` no están instalados, indicando qué módulo instalar primero. La UI también deshabilita el botón "instalar en distro" cuando no hay ninguna distro instalada, con el mismo mensaje.
