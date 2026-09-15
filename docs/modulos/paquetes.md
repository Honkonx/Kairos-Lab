# Paquetes

## Qué es

`packages` consolida 10 herramientas de desarrollo npm en un solo módulo contenedor (`PackagesFragment.kt`) con un switch por herramienta — cada una se activa o desactiva directamente, sin diálogos de instalación intermedios.

## Las 10 herramientas

| Herramienta | id | Script real | Paquete npm | Comando de versión |
|---|---|---|---|---|
| TypeScript | `typescript` | `modulos/typescript.sh` | `typescript` (`tsc`) | `tsc --version` |
| NestJS CLI | `nestjs` | `modulos/nestjs.sh` | `@nestjs/cli` (`nest`) | `nest --version` |
| Prettier | `prettier` | `modulos/prettier.sh` | `prettier` | `prettier --version` |
| Live Server | `livesrv` | `modulos/livesrv.sh` | `live-server` | `live-server --version` |
| Localtunnel | `localtunnel` | `modulos/localtunnel.sh` | `localtunnel` (`lt`) | `lt --version` |
| Vercel CLI | `vercel` | `modulos/vercel.sh` | `vercel` | `vercel --version` |
| Markserv | `markserv` | `modulos/markserv.sh` | `markserv` | `markserv --version` |
| PSQL Format | `psqlformat` | `modulos/psqlformat.sh` | `psqlformat` (+ Perl vía pkg) | `psqlformat --version` |
| NPM Check Updates | `ncu` | `modulos/ncu.sh` | `npm-check-updates` (`ncu`) | `ncu --version` |
| Ngrok | `ngrok` | `modulos/ngrok.sh` | `ngrok` | `ngrok --version` |

Los 10 scripts (`modulos/<id>.sh`) usan un helper compartido en `modulos/lib.sh` que instala Node.js si falta antes del `npm install -g`. `psqlformat.sh` además instala `perl` (pkg) como dependencia antes del `npm install -g` — la desinstalación solo revierte el paquete npm `psqlformat`, nunca `perl` en sí (runtime compartido, podría estar en uso por otra herramienta).

## Dependencia de Node.js

Las 10 herramientas dependen de Node.js. Si falta, cada script lo instala solo — no hace falta activar primero el switch "Node.js LTS" del módulo Lenguajes. Si el usuario prefiere gestionarlo aparte, el switch de Node.js en Lenguajes cubre exactamente el mismo paquete real (`nodejs-lts`), sin duplicar nada.

## Desinstalación real (`ModuleController.deepUninstallPlan`)

10 entradas, todas `npm uninstall -g <paquete>`:

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

Ninguna borra Node.js en sí (runtime compartido con otros módulos).

## Detección de instalado

`typescript→tsc`, `nestjs→nest`, `prettier→prettier`, `livesrv→live-server`, `localtunnel→lt`, `vercel→vercel`, `markserv→markserv`, `psqlformat→psqlformat`, `ncu→ncu`, `ngrok→ngrok`.

## Controles de la pantalla (`PackagesFragment.kt`)

Switch por herramienta más acciones opcionales, con flags reales confirmados contra la documentación oficial de cada CLI:

| Herramienta | Switch | Acciones | Detalle no obvio |
|---|---|---|---|
| TypeScript | `tsc --version` | "Crear tsconfig.json" (`tsc --init`) | `tsc` se niega a pisar un `tsconfig.json` existente |
| NestJS CLI | `nest --version` | "Crear proyecto" (`nest new %s --package-manager npm --skip-git`, pide nombre) | `--package-manager`/`--skip-git` evitan que el CLI pregunte interactivo (se colgaría esperando entrada en un proceso sin terminal) |
| Prettier | `prettier --version` | "Verificar formato" (solo lectura) / "Aplicar formato" (`--write .`, requiere confirmación) | — |
| Live Server | `live-server --version` | Ninguna | — |
| Localtunnel | `lt --version` | Ninguna | — |
| Vercel CLI | `vercel --version` | "Ver sesión" (`whoami`) / "Iniciar sesión" (`login --no-browser`, imprime URL+código de device flow) / "Deploy a producción" (`--prod --yes`) | El login nunca pide contraseña/token — solo muestra la URL/código para completar en cualquier navegador |
| Markserv | `markserv --version` | Ninguna | — |
| PSQL Format | `psqlformat --version` | Ver card aparte abajo | Necesita un archivo `.sql` concreto |
| NPM Check Updates | `ncu --version` | "Revisar actualizaciones" (solo lectura) / "Actualizar package.json" (`ncu -u`, requiere confirmación) | — |
| Ngrok | `ngrok --version` | "Configurar authtoken" (`config add-authtoken %s`, pide el token) | El token nunca se loguea/muestra en texto plano en ningún mensaje |

**Card "Formatear archivo .sql (psqlformat)"**: "Elegir archivo .sql" abre el selector del sistema, copia el archivo elegido a un temporal dentro del sandbox de Termux, corre `psqlformat <archivo>` (solo lectura — nunca escribe de vuelta) y muestra el resultado en un diálogo con scroll. El temporal se borra siempre, con éxito o error.

## Pestañas por categoría

Las 10 herramientas de la card única "Paquetes" se agrupan en 3 pestañas por función real:

| Pestaña | Herramientas | Criterio |
|---|---|---|
| **Formato y calidad** | TypeScript, Prettier, NPM Check Updates, PSQL Format | Operan sobre código/config existente, sin proceso de servidor propio |
| **Servidores y túneles** | Live Server, Markserv, Localtunnel, Ngrok | Procesos de larga duración / exposición a la red |
| **Deploy y frameworks** | NestJS CLI, Vercel CLI | Scaffolding de proyectos nuevos + publicación |

La card introductoria y la card "Formatear archivo .sql" quedan fuera de las pestañas, arriba de ellas.

Este módulo está scopeado a 10 herramientas de desarrollo npm — no es un catálogo general de paquetes de Termux/apt. Un catálogo de paquetes generales de Termux (compresión, editores, utilidades de red) sería un módulo/alcance distinto.
