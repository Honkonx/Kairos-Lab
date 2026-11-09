# NESTJS.md — módulo `nestjs` (NestJS CLI, comando `nest`)

> Ficha basada en la lectura del código. Resumen del conjunto en `paquetes.md`. No probado en dispositivo.

## 1. Qué es
CLI oficial de NestJS (`@nestjs/cli`) para crear proyectos Node/NestJS. `modules.json`: `nestjs`,
`Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "nest"`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN
Instala el CLI y ofrece 1 acción (scaffolding). Sin servicio ni estado. Opciones de UI: 2
(switch + «Crear proyecto Nest»).

## 3. Instalación
- `modulos/nestjs.sh:81` → `install_npm_global "nestjs" "@nestjs/cli" "nest"` (`lib.sh:613`):
  salta si `nest` existe y no hay `--force` (`registry_write installed=true`); si no,
  `ensure_node_installed` → `npm install -g @nestjs/cli` → `command -v nest` →
  `fix_npm_shebang_wrapper` (`lib.sh:563`) → `verify_binary_installed nest` (`--version`, `lib.sh:501`) → `registry_install`.
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (glob `$(npm root -g)/@nestjs/**`).

## 4. Ejecución
No es servicio. La acción corre `cd "$HOME" && nest new '<nombre>' --package-manager npm --skip-git`
(timeout 180 s). La salida se muestra al terminar, últimas 15 líneas (`formatNestNew`).

## 5. Opciones de la UI (`PackagesFragment.kt:70-85`, pestaña «Deploy y frameworks»)
1. Switch instalar/desinstalar (`npm uninstall -g @nestjs/cli`).
2. «Crear proyecto Nest» con diálogo de nombre (el valor se cita con comillas simples, `promptTextInputAction`).

## 6. Archivos y rutas
`$PREFIX/bin/nest`, `$(npm root -g)/@nestjs/cli`, proyectos nuevos en `$HOME/<nombre>`, registry `nestjs.version`.

## 7. Seguridad
Nombre del proyecto escapado para shell (no inyectable). `nest new` ejecuta `npm install` de
plantillas desde npm (sin pin). Sin secretos.

## 8. Problemas / gaps
- El proyecto siempre se crea en `$HOME`; no hay selector de carpeta ni validación del nombre.
- Salida solo al final (sin progreso en vivo); 180 s puede no alcanzar para `npm install` en red lenta (el proceso se mata y el proyecto queda a medias).
- No hay acciones de `nest start/build/generate`; para el resto hay que usar terminal.

## 9. Fecha de alta
`modulos/nestjs.sh`: 2026-08-18.
