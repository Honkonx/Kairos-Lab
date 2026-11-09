# VERCEL.md — módulo `vercel` (Vercel CLI)

> Basado en la lectura del código. Resumen del conjunto en `PAQUETES.md`. No probado en dispositivo.

## 1. Qué es
CLI de Vercel (`vercel`, paquete npm) para desplegar proyectos. `modules.json`: `vercel`, `Herramienta`,
`internal: true`, `requires: ["nodejs"]`, `terminalCommand: "vercel"`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN (el más «usable» del lote)
Instala el CLI y expone 3 acciones nativas (sesión, login, deploy). Sin servicio ni estado propio de
la app. Opciones de UI: 4 (switch + 3 acciones).

## 3. Instalación
`modulos/vercel.sh:81` → `install_npm_global "vercel" "vercel" "vercel"` (flujo `lib.sh:613`; post-condición
`vercel --version`; `registry_install`). Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Ejecución
Cada acción corre `cd "$HOME" && <comando>` y muestra la salida al final en un diálogo.

## 5. Opciones de la UI (`PackagesFragment.kt:107-138`, pestaña «Deploy y frameworks»)
1. Switch instalar/desinstalar (`npm uninstall -g vercel`).
2. «Ver sesión» → `vercel whoami` (`formatVercelWhoami`).
3. «Login» → `vercel login --no-browser` (20 s, lectura parcial, `readPartialOutput`): muestra URL + código de verificación.
4. «Deploy producción» → `vercel --prod --yes` (150 s), muestra la URL de deployment o el error.

## 6. Archivos y rutas
`$PREFIX/bin/vercel`, `$(npm root -g)/vercel`, registry `vercel.version`; la credencial la guarda el propio CLI (fuera de Kairos,
típicamente en `~/.local/share/com.vercel.cli`; inferencia: ruta no verificada).

## 7. Seguridad
No se maneja ninguna contraseña/token desde la app (login por device flow). `deploy --prod --yes` publica el contenido del
proyecto enlazado en `$HOME` sin confirmación adicional (a diferencia de `prettier --write`/`ncu -u`, esta acción NO tiene
`requiresConfirmation`, `PackagesFragment.kt:133-136`).

## 8. Problemas / limitaciones
- El deploy publica a producción con un solo toque y sin diálogo de confirmación.
- El deploy solo funciona si `$HOME` ya es un proyecto enlazado (limitación declarada en el código, `:130-132`); no hay `vercel link` ni selección de carpeta.
- Inferencia (riesgo): el login se corta a los 20 s matando el proceso; si el CLI necesita seguir vivo haciendo polling para guardar el token, el login desde la app podría no completarse. No verificado en dispositivo.

## 9. Fecha de alta
`modulos/vercel.sh`: 2026-08-18.
