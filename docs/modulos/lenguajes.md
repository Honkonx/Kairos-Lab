# Lenguajes — módulo `languages`

## Qué es

`languages` es un módulo **contenedor** — no tiene script de instalación propio
(`modules.json` campo `"script": ""`). Su Fragment (`LanguagesFragment.kt`) muestra una fila
por lenguaje con un `SwitchCompat` real:

- **ON** → `ModuleController.installModule(id, null, force=false, ...)` — el mismo mecanismo
  real que usa el resto de la app (corre `bash <id>.sh --silent`, log en
  `~/kairos_logs/install_<id>.log`).
- **OFF** → `ModuleController.deepUninstallModule(id, ...)` — desinstalación REAL del paquete
  (`pkg uninstall -y <paquete>`), con confirmación previa (AlertDialog simple, sin checkbox de
  "desinstalación profunda" — acá siempre es profunda, es lo único que tiene sentido para un
  paquete pkg nativo).

Cada fila muestra el estado real: "No instalado" / "Instalado — `<versión real>`" (leída del
registry `<id>.version`, escrita por `install_single_pkg()` en `modulos/lib.sh` al instalar; si
el registry no tiene el valor —binario instalado a mano en terminal, bypaseando la app— se
corre `<comando> --version` en vivo como fallback).

## Los lenguajes

| Lenguaje | id | Script real | Paquete pkg | Comando de versión |
|---|---|---|---|---|
| Node.js LTS | `nodejs` | `modulos/nodejs.sh` | `nodejs-lts` (+ Corepack) | `node --version` |
| Perl | `perl` | `modulos/perl.sh` | `perl` | `perl --version` |
| PHP | `php` | `modulos/php.sh` | `php` | `php --version` |
| Rust | `rust` | `modulos/rust.sh` | `rust` (rustc + cargo) | `rustc --version` |
| C/C++ (Clang) | `clang` | `modulos/clang.sh` | `clang` | `clang --version` |
| Go | `golang` | `modulos/golang.sh` | `golang` | `go version` |
| Ruby | `ruby` | `modulos/ruby.sh` | `ruby` | `ruby --version` |
| Kotlin | `kotlin` | `modulos/kotlin.sh` | `kotlin` (dependencia apt `openjdk-17`) | `kotlinc -version` |

Los scripts (`modulos/<id>.sh`) son independientes entre sí — usan todos `install_single_pkg()`
de `modulos/lib.sh`. Lo que unifica su presentación es este Fragment: antes eran tarjetas
sueltas en la Tienda, ahora son filas dentro de "Lenguajes".

## Python — no duplicado, a propósito

Python ya es un módulo de primer nivel (`PythonFragment.kt`, con gestión de entornos virtuales
por proyecto — ver `docs/modulos/python.md`). No se agregó como switch acá para no tener 2
caminos de instalación que puedan desincronizarse; en cambio, "Lenguajes" muestra una fila
informativa "🐍 Python — gestión propia" que navega directo a `PythonFragment` con un tap,
para que el catálogo se sienta centralizado sin duplicar lógica real.

## Dónde corre cada uno

Todos son **nativos de Termux** (bionic, `pkg install`, sin proot) — funcionan directo en
cualquier sesión de terminal de Kairos. **Todos también están disponibles, por separado, dentro
de una distro Linux completa** (Debian/Ubuntu vía el módulo Entorno, proot) con su
propio `apt install <paquete>` — es un sistema de archivos glibc totalmente aparte del bionic
nativo. Ese segundo camino **no lo gestiona este módulo**; si hace falta un lenguaje dentro de
una distro proot, se instala a mano ahí, igual que cualquier paquete de esa distro.

## Campo `internal` en modules.json

Los objetos correspondientes en `app/src/main/assets/modules.json` llevan `"internal": true` —
un campo en `ModuleInfo.kt`/`ModuleCatalog.kt` que oculta el módulo de la Tienda
(`PluginsFragment`) y de la pantalla Módulos (`ModulesFragment`) como entrada propia, **sin**
borrar ni modificar el objeto JSON en sí. Distinto del campo `hidden` en tiempo de ejecución
(`ProjectsManager.hiddenModuleIds()`, el usuario ocultando un módulo YA instalado desde el home)
— `internal` es una decisión de catálogo, no de usuario.

## Desinstalación real (`ModuleController.deepUninstallPlan`)

```kotlin
"nodejs" -> DeepUninstallPlan("pkg uninstall -y nodejs-lts", ...)  // paquete real es nodejs-lts, no nodejs
"perl"   -> DeepUninstallPlan("pkg uninstall -y perl", ...)
"php"    -> DeepUninstallPlan("pkg uninstall -y php", ...)
"rust"   -> DeepUninstallPlan("pkg uninstall -y rust", ...)
"clang"  -> DeepUninstallPlan("pkg uninstall -y clang", ...)
"golang" -> DeepUninstallPlan("pkg uninstall -y golang", ...)
```

## Detección de instalado (`ModuleInstalled.BINARY_FALLBACK`)

`nodejs→node`, `perl→perl`, `php→php`, `rust→rustc`, `clang→clang`, `golang→go` — mismo binario
que ya tenían como `terminalCommand` en el catálogo antes de volverse `internal`. Esto hace que
el switch refleje un lenguaje instalado A MANO en terminal (bypaseando la app), no solo lo que
instaló la propia app.

## Arquitectura compartida

- `app/src/main/java/com/termux/app/ui/ConsolidatedModuleFragment.kt` — base compartida con
  `PackagesFragment` (fila "nombre + estado/versión + switch").
- `app/src/main/java/com/termux/app/ui/LanguagesFragment.kt`.
- `app/src/main/java/com/termux/app/ui/ModuleDetailNavigator.kt` — `"languages" ->
  LanguagesFragment()`.
- `app/src/main/java/com/termux/app/model/ModuleInfo.kt` — campo `internal`.
- `app/src/main/java/com/termux/app/data/ModuleCatalog.kt` — parseo/serialización de `internal`.
- `app/src/main/java/com/termux/app/data/ModuleInstalled.kt` — sentinel `"languages"→"bash"`
  (el módulo contenedor siempre aparece "instalado", nunca dispara la hoja de instalación) +
  `BINARY_FALLBACK` de los lenguajes.
- `app/src/main/java/com/termux/app/ModuleController.kt` — `deepUninstallPlan` por lenguaje.
- `app/src/main/java/com/termux/app/ui/ModulesFragment.kt` / `PluginsFragment.kt` — filtran
  `internal` del catálogo mostrado.
- `app/src/main/assets/modules.json` — `internal: true` en cada lenguaje + entrada
  `"languages"`.

Ver también `docs/modulos/paquetes.md` (mismo patrón, para herramientas npm).

## Controles de la pantalla (`LanguagesFragment.kt`)

Un `switchRow()` por lenguaje (ON instala, OFF desinstala) + botones `RunAction` opcionales por
fila (una sola pasada, sin diálogo previo salvo que muten algo — ver `RunAction.
requiresConfirmation`/`requiresTextInput`).

| Lenguaje | Switch (versión) | RunAction(s) | Por qué (si aplica) |
|---|---|---|---|
| Node.js LTS | `node --version` | "Paquetes npm -g" (`npm list -g --depth=0`) | — |
| Perl | `perl --version` | "Config" (`perl -V`) | Dependencia de PSQL Format (módulo Paquetes) |
| PHP | `php --version` | "Módulos cargados" (`php -m`) | — |
| Rust | `rustc --version` | "Binarios cargo install" (`cargo install --list`) | — |
| C/C++ (Clang) | `clang --version` | Ninguno | Sin gestor de paquetes propio ni flag de una sola pasada con valor real |
| Go | `go version` | "Entorno" (`go env`) | — |
| Ruby | `ruby --version` | — | — |
| Kotlin | `kotlinc -version` | — | Paquete apt real declara `openjdk-17` como dependencia, no hace falta instalarlo aparte |

**Card "YA INSTALADO APARTE"**: fila de acceso directo a `PythonFragment` (gestión propia de
venv/pip) — no se duplica el instalador acá para no tener 2 caminos que puedan desincronizarse,
pero queda visible para que el catálogo se sienta centralizado.

**Detalle no obvio**: estos lenguajes corren NATIVOS en Termux (bionic, sin proot) — cada uno
también funciona por separado dentro de una distro Linux completa (Debian/Ubuntu vía Entorno,
proot) con su propio `apt install`, pero ese segundo camino NO lo gestiona este módulo.
