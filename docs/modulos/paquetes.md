# Paquetes — módulo `packages`

> Mismo patrón que `docs/modulos/lenguajes.md` — leer ese archivo primero para el contexto completo (campo `internal`, mecanismo de switch ON=instalar/OFF=desinstalar real, `ConsolidatedModuleFragment.kt` compartido). Este documento solo cubre lo específico de "Paquetes".

## Qué es

`packages` consolida 10 herramientas de desarrollo npm (`typescript`, `nestjs`, `prettier`,
`livesrv`, `localtunnel`, `vercel`, `markserv`, `psqlformat`, `ncu`, `ngrok`) en un solo módulo
contenedor (`PackagesFragment.kt`) con un switch por herramienta — módulos ya listos que solo se
activan o desactivan, sin diálogos de instalación intermedios.

Clasificación: **contenedor sin script** (`script: ""`, `type: Contenedor`, `category: tools`),
mapeado en `ModuleDetailNavigator.kt:49` a `PackagesFragment`. Funcional como catálogo; cada
herramienta es un complemento npm.

## Las 10 herramientas

| Herramienta | id | Script real | Paquete npm | Comando de versión | Ficha |
|---|---|---|---|---|---|
| TypeScript | `typescript` | `modulos/typescript.sh` | `typescript` (`tsc`) | `tsc --version` | — |
| NestJS CLI | `nestjs` | `modulos/nestjs.sh` | `@nestjs/cli` (`nest`) | `nest --version` | `nestjs.md` |
| Prettier | `prettier` | `modulos/prettier.sh` | `prettier` | `prettier --version` | — |
| Live Server | `livesrv` | `modulos/livesrv.sh` | `live-server` | `live-server --version` | `livesrv.md` |
| Localtunnel | `localtunnel` | `modulos/localtunnel.sh` | `localtunnel` (`lt`) | `lt --version` | `localtunnel.md` |
| Vercel CLI | `vercel` | `modulos/vercel.sh` | `vercel` | `vercel --version` | — |
| Markserv | `markserv` | `modulos/markserv.sh` | `markserv` | `markserv --version` | `markserv.md` |
| PSQL Format | `psqlformat` | `modulos/psqlformat.sh` | `psqlformat` (+ Perl vía pkg) | `psqlformat --version` | — |
| NPM Check Updates | `ncu` | `modulos/ncu.sh` | `npm-check-updates` (`ncu`) | `ncu --version` | `ncu.md` |
| Ngrok | `ngrok` | `modulos/ngrok.sh` | `ngrok` | `ngrok --version` | `ngrok.md` |

Los 10 scripts (`modulos/<id>.sh`) usan `install_npm_global()` de `modulos/lib.sh` (que a su vez
llama `ensure_node_installed()` — instala `nodejs-lts` solo si falta). `psqlformat.sh` además
instala `perl` (pkg) como dependencia antes del `npm install -g` — la desinstalación (ver abajo)
solo revierte el paquete npm `psqlformat`, nunca `perl` en sí (runtime compartido, podría estar
en uso por el módulo Lenguajes u otra herramienta).

## Dependencia de Node.js

Las 10 herramientas dependen de Node.js. Si falta, cada script lo instala solo
(`ensure_node_installed()`) — no hace falta activar primero el switch "Node.js LTS" del módulo
Lenguajes. Si el usuario prefiere gestionarlo aparte, el switch de Node.js en Lenguajes cubre
exactamente el mismo paquete real (`nodejs-lts`), sin duplicar nada.

## Desinstalación real (`ModuleController.deepUninstallPlan`)

10 entradas, todas `npm uninstall -g <paquete>` (`ModuleController.kt:976-990`):

```kotlin
"typescript"  -> DeepUninstallPlan("npm uninstall -g typescript", ...)
"nestjs"      -> DeepUninstallPlan("npm uninstall -g @nestjs/cli", ...)
"prettier"    -> DeepUninstallPlan("npm uninstall -g prettier", ...)
"livesrv"     -> DeepUninstallPlan("npm uninstall -g live-server", ...)
"localtunnel" -> DeepUninstallPlan("npm uninstall -g localtunnel", ...)
"vercel"      -> DeepUninstallPlan("npm uninstall -g vercel", ...)
"markserv"    -> DeepUninstallPlan("npm uninstall -g markserv", ...)
"psqlformat"  -> DeepUninstallPlan("npm uninstall -g psqlformat", ...)
"ncu"         -> DeepUninstallPlan("npm uninstall -g npm-check-updates", ...)
"ngrok"       -> DeepUninstallPlan("npm uninstall -g ngrok", ...)
```

Ninguna borra Node.js en sí (runtime compartido con otros módulos) — mismo criterio que el resto
de `ModuleController.kt` (ej. `ollama`/`claude` nunca desinstalan Python/Node como efecto
colateral).

## Detección de instalado (`ModuleInstalled.BINARY_FALLBACK`)

`typescript→tsc`, `nestjs→nest`, `prettier→prettier`, `livesrv→live-server`,
`localtunnel→lt`, `vercel→vercel`, `markserv→markserv`, `psqlformat→psqlformat`, `ncu→ncu`,
`ngrok→ngrok` — mismo binario que ya tenían como `terminalCommand` antes de volverse
`internal`.

## Arquitectura compartida

Los mismos archivos que `docs/modulos/lenguajes.md` (campo `internal` en `ModuleInfo.kt`/
`ModuleCatalog.kt`, sentinel + `BINARY_FALLBACK` en `ModuleInstalled.kt`, filtros en
`ModulesFragment.kt`/`PluginsFragment.kt`), más:

- `app/src/main/java/com/termux/app/ui/PackagesFragment.kt` (433 líneas) — reusa
  `ConsolidatedModuleFragment.kt`, mismo mecanismo que Lenguajes.
- `app/src/main/java/com/termux/app/ui/ModuleDetailNavigator.kt` — `"packages" ->
  PackagesFragment()`.
- `app/src/main/assets/modules.json` — `internal: true` en las 10 + entrada `"packages"`.

## Controles de la pantalla (`PackagesFragment.kt`)

Mismo patrón que `LanguagesFragment` (switch por herramienta, `RunAction` opcionales) — acá con
más variedad de flags reales confirmados contra la documentación oficial de cada CLI. Opciones
de UI: 10 switches + 10 `RunAction` (typescript 1, nestjs 1, prettier 2, vercel 3, ncu 2,
ngrok 1; `PackagesFragment.kt:56-180`) + la tarjeta "Formatear .sql" con selector SAF
(`registerForActivityResult(GetContent)`, kt:48-50) = ~21 controles, agrupados en 3 pestañas
(`itemGroups()` kt:199).

| Herramienta | Switch | RunAction(s) | Detalle no obvio |
|---|---|---|---|
| TypeScript | `tsc --version` | "Crear tsconfig.json" (`tsc --init`) | Seguro por diseño: `tsc` se niega a pisar un `tsconfig.json` existente |
| NestJS CLI | `nest --version` | "Crear proyecto" (`nest new %s --package-manager npm --skip-git`, pide nombre) | `--package-manager`/`--skip-git` evitan que el CLI pregunte interactivo (se colgaría esperando stdin en un `ProcessBuilder` sin TTY) |
| Prettier | `prettier --version` | "Verificar formato" (solo lectura) / "Aplicar formato" (`--write .`, **requiere confirmación** — reescribe archivos en `$HOME`) | — |
| Live Server | `live-server --version` | Ninguno | — |
| Localtunnel | `lt --version` | Ninguno | — |
| Vercel CLI | `vercel --version` | "Ver sesión" (`whoami`) / "Iniciar sesión" (`login --no-browser`, imprime URL+código de device flow) / "Deploy a producción" (`--prod --yes`) | El login nunca pide contraseña/token — solo muestra la URL/código para completar en cualquier navegador |
| Markserv | `markserv --version` | Ninguno | — |
| PSQL Format | `psqlformat --version` | Ninguno (ver card aparte abajo) | Necesita un archivo `.sql` concreto — no encaja como RunAction de una línea sobre `$HOME` |
| NPM Check Updates | `ncu --version` | "Revisar actualizaciones" (solo lectura) / "Actualizar package.json" (`ncu -u`, **requiere confirmación**) | — |
| Ngrok | `ngrok --version` | "Configurar authtoken" (`config add-authtoken %s`, pide el token) | El token nunca se loguea/muestra en texto plano en ningún toast |

Este módulo está acotado a **10 herramientas de desarrollo npm** (no es un catálogo general de
paquetes de Termux/apt) — cada `RunAction` está confirmado contra la documentación oficial de su
CLI.

**Card "FORMATEAR ARCHIVO .SQL (psqlformat)"** — "📄 Elegir archivo .sql": abre el selector SAF,
copia el archivo elegido a un temporal real dentro del sandbox de Termux (psqlformat no sabe
leer un `content://` Uri), corre `psqlformat <archivo>` (solo lectura — nunca escribe de vuelta,
`--write` no se expone) y muestra el resultado en un diálogo con scroll. El temporal se borra
siempre, con éxito o error.

## Pestañas por categoría

Usando el hook `ConsolidatedModuleFragment.itemGroups()` sobre el helper
`BaseModuleFragment.setupTabs()`, las 10 herramientas se agrupan en 3 pestañas por función real
(`LanguagesFragment` no sobreescribe este hook y sigue con una card única):

| Pestaña | Herramientas | Criterio |
|---|---|---|
| **Formato y calidad** | TypeScript, Prettier, NPM Check Updates, PSQL Format | Operan sobre código/config existente, sin proceso de servidor propio |
| **Servidores y túneles** | Live Server, Markserv, Localtunnel, Ngrok | Procesos de larga duración / exposición a la red |
| **Deploy y frameworks** | NestJS CLI, Vercel CLI | Scaffolding de proyectos nuevos + publicación |

La card "QUÉ ES" (intro) y la card "FORMATEAR ARCHIVO .SQL (psqlformat)" (`addExtraRows()`)
quedan FUERA de las pestañas, arriba de ellas — orden de `ConsolidatedModuleFragment.
buildContent()`: intro → filas extra → lista de herramientas.

## Limitaciones conocidas

- `prettier --check/--write .` y `vercel --prod --yes` actúan sobre `$HOME` (la carpeta home
  entera, no un proyecto elegido); una acción destructiva solo mitigada por el diálogo de
  confirmación.
- `vercel --prod` exige que `$HOME` ya esté enlazado a un proyecto (comentario del propio
  código, kt:~127-131).
- `ngrok config add-authtoken %s` recibe el token por campo de texto sin loguearlo
  (`RunAction.requiresTextInput`); ngrok lo persiste en su propio archivo de config (ruta y
  cifrado no verificados).
- `ncu -u` reescribe el `package.json` de `$HOME` (con confirmación).
- Todas las herramientas necesitan Node.js (`ensure_node_installed`, `lib.sh:533`).

`PackagesFragment.kt` agregado el 2026-08-18.
