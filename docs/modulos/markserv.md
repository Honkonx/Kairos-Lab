# MARKSERV.md — módulo `markserv` (servidor de Markdown en vivo)

> Ficha basada en la lectura del código. Resumen del conjunto en `paquetes.md`. No probado en dispositivo.

## 1. Qué es
Servidor que renderiza Markdown en el navegador con recarga en vivo (paquete npm `markserv`).
`modules.json`: `markserv`, `Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "markserv"`,
`port: ""`, `tmuxSession: ""`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN
Solo instala el binario; la app no lo arranca ni conoce su puerto. Opciones de UI: 1 (switch).

## 3. Instalación
`modulos/markserv.sh:81` → `install_npm_global "markserv" "markserv" "markserv"` (`lib.sh:613`; post-condición
`markserv --version`; `registry_install`). Flags: `--silent`, `--force`, `--describe`, `--describe-files`.

## 4. Ejecución
Solo manual desde terminal (`markserv`). Sin acción de UI.

## 5. Opciones de la UI
Solo el switch (`PackagesFragment.kt:139`, pestaña «Servidores y túneles»). Desinstalar: `npm uninstall -g markserv`.

## 6. Archivos y rutas
`$PREFIX/bin/markserv`, `$(npm root -g)/markserv`, registry `markserv.version`.

## 7. Seguridad
Sirve el directorio donde se lanza; sin control desde la app (interfaces de escucha por defecto no auditadas acá). Sin secretos.

## 8. Problemas / gaps
- Sin iniciar/detener, elección de carpeta ni puerto, ni apertura del navegador desde la UI.
- El `--version` de markserv se exige como post-condición; si el paquete no lo implementa, la instalación falla por diseño genérico (no confirmado en dispositivo).
- Podría solaparse funcionalmente con Live Server (`livesrv`); no se evaluó consolidar.

## 9. Fecha de alta
`modulos/markserv.sh`: 2026-08-18.
