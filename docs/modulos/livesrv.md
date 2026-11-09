# LIVESRV.md — módulo `livesrv` (Live Server, comando `live-server`)

> Ficha basada en la lectura del código. Resumen del conjunto en `paquetes.md`. No probado en dispositivo.

## 1. Qué es
Servidor estático con recarga automática (paquete npm `live-server`). `modules.json`: `livesrv`,
`Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "live-server"`, `port: ""`,
`tmuxSession: ""`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN
Solo instala el binario. La app NO lo arranca ni gestiona su puerto (no tiene `RunAction` ni
sesión tmux). Opciones de UI: 1 (switch instalar/desinstalar).

## 3. Instalación
- `modulos/livesrv.sh:82` → `install_npm_global "livesrv" "live-server" "live-server"`
  (`lib.sh:613`; verificación `live-server --version` con `verify_binary_installed`, luego `registry_install`).
- Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Ejecución
Solo manual por terminal (`live-server`). La UI no lo lanza. La fila de `PackagesFragment`
no ofrece «Abrir en terminal» ni «Abrir proyecto» (`PackagesFragment.kt:105`).

## 5. Opciones de la UI
Solo el switch (pestaña «Servidores y túneles», `PackagesFragment.kt:204`). Desinstalar: `npm uninstall -g live-server`.

## 6. Archivos y rutas
`$PREFIX/bin/live-server`, `$(npm root -g)/live-server`, registry `livesrv.version`.

## 7. Seguridad
Un servidor estático arrancado a mano escucha por defecto en todas las interfaces según el
comportamiento de live-server (sin verificar en este repo); la app no controla host/puerto.

## 8. Problemas / gaps
- No hay acción de iniciar/detener ni elección de carpeta/puerto desde la UI (pese a que el sentido de la herramienta es servir una carpeta).
- `terminalCommand` existe en `modules.json` pero la fila consolidada no lo usa.
- `--version` de `live-server` se usa como post-condición; si esa versión no imprime y retorna 0, la instalación falla con error aunque el binario funcione (no confirmado).

## 9. Fecha de alta
`modulos/livesrv.sh`: 2026-08-18.
