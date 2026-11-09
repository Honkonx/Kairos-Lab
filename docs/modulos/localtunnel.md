# LOCALTUNNEL.md — módulo `localtunnel` (comando `lt`)

> Ficha basada en la lectura del código. Resumen del conjunto en `paquetes.md`. No probado en dispositivo.

## 1. Qué es
Cliente de túneles HTTP `localtunnel` (binario `lt`) con un parche para que abrir la URL funcione en
Android. `modules.json`: `localtunnel`, `Herramienta`, `internal: true`, `requires: ["nodejs"]`,
`terminalCommand: "lt"`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN
Instala el binario; sin UI de uso. NO está integrado en `TunnelManager`/`TunnelFragment` (esos solo
soportan `cloudflared` y `ngrok`: `TunnelManager.kt:228`). Opciones de UI: 1 (switch).

## 3. Instalación
- `modulos/localtunnel.sh` (128 líneas). No usa `install_npm_global()` de punta a punta: instala a mano
  (`localtunnel.sh:~103-121`) porque `lt --version` falla con «Unsupported platform: android» (dependencia `openurl`)
  antes de que el parche llegue a aplicarse.
- Orden real: `ensure_node_installed` → `npm install -g localtunnel` → `command -v lt` → `fix_npm_shebang_wrapper` →
  `_localtunnel_fix_openurl` (`sed` inserta `case 'android': command='termux-open-url'` en `openurl.js`, `localtunnel.sh:~83-95`) →
  `verify_binary_installed "lt"` → `registry_install`.
- Flags: `--silent`, `--force`, `--describe`, `--describe-files` (glob `$(npm root -g)/localtunnel/**`).

## 4. Ejecución
Solo manual (`lt --port <n>`). La app no lo arranca.

## 5. Opciones de la UI
Solo el switch (`PackagesFragment.kt:106`, pestaña «Servidores y túneles»). Desinstalar: `npm uninstall -g localtunnel`
(`ModuleController.kt:985`).

## 6. Archivos y rutas
`$PREFIX/bin/lt`, `$(npm root -g)/localtunnel` (incluye `node_modules/openurl/openurl.js` parcheado), registry `localtunnel.version`.

## 7. Seguridad
Expone un puerto local a una URL pública de `loca.lt` sin autenticación propia (comportamiento del servicio, no auditado
acá). El parche edita con `sed -i` un archivo dentro de `node_modules` (se pierde en una actualización de npm; solo se re-aplica reinstalando).

## 8. Problemas / gaps
- Sin acción de «iniciar túnel»; la app no lo conecta con `TunnelManager` (que usa cloudflared/ngrok).
- El parche `sed` depende de que exista la línea `default:` en `openurl.js`; si upstream cambia, no se aplica y falla recién `verify_binary_installed`.
- La rama «ya instalado» solo hace `registry_write installed=true` sin versión.

## 9. Fecha de alta
`modulos/localtunnel.sh`: 2026-08-18.
