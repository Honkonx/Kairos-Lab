# NGROK.md — módulo `ngrok` (túneles vía npm)

> Ficha basada en la lectura del código. Resumen del conjunto en `paquetes.md`. No probado en dispositivo.

## 1. Qué es
Cliente `ngrok` (paquete npm `ngrok`, que baja el binario nativo en su postinstall) para exponer puertos locales.
`modules.json`: `ngrok`, `Herramienta`, `internal: true`, `requires: ["nodejs"]`, `terminalCommand: "ngrok"`, ~15MB.

## 2. Clasificación: COMPLEMENTO/EXTENSIÓN (pero consumido por una pantalla funcional ajena)
Este módulo en sí solo instala el CLI y ofrece 1 acción (authtoken). La funcionalidad real de túneles vive en
`TunnelManager.kt`/`TunnelFragment.kt` (proveedor «ngrok» junto a Cloudflare: `TunnelFragment.kt:138`), que NO depende de este
script. Opciones de UI del módulo: 2 (switch + «Configurar authtoken»); el túnel tiene sus propias opciones en otra pantalla.

## 3. Instalación
- `modulos/ngrok.sh:81` → `install_npm_global "ngrok" "ngrok" "ngrok"` (`lib.sh:613`; post-condición `ngrok --version`;
  `registry_install`). Flags: `--silent` (se parsea; en este script no se vuelve a leer), `--force`, `--describe`, `--describe-files`
  (glob `$(npm root -g)/ngrok/**`, incluye el binario nativo descargado por el postinstall).
- Instalación duplicada: `TunnelManager.ensureNgrok()` (`TunnelManager.kt:142-157`) hace su propio `npm install -g ngrok`
  (con `nodejs-lts` si falta) y verifica `ngrok version`, sin pasar por `ngrok.sh` ni por el registry.

## 4. Ejecución
Acción de UI: `cd "$HOME" && ngrok config add-authtoken '<token>'`. El túnel real lo arranca `TunnelManager`
(`ngrok http|tcp <puerto> --log stdout` en tmux, con `GODEBUG=netdns=go`, `TunnelManager.kt:258-276,320`).

## 5. Opciones de la UI (`PackagesFragment.kt:167-181`, pestaña «Servidores y túneles»)
1. Switch instalar/desinstalar (`npm uninstall -g ngrok`, `ModuleController.kt:990`).
2. «Configurar authtoken» con diálogo de texto (el valor se cita con comillas simples; no se loguea ni se muestra).

## 6. Archivos y rutas
`$PREFIX/bin/ngrok`, `$(npm root -g)/ngrok`, config del propio ngrok (token en su archivo de configuración), registry `ngrok.version`;
el token/dominio de `TunnelFragment` se guarda aparte (`tunnel.ngrok.*`, `TunnelManager.kt:28-30`).

## 7. Seguridad
- El token viaja como argumento de `bash -c` (visible brevemente en la lista de procesos del propio dispositivo) y ngrok lo guarda en su
  config; la app no vuelve a mostrarlo.
- Dos lugares guardan el token (config de ngrok vía este módulo y `tunnel.ngrok.token` de `TunnelManager`): no hay sincronización entre ambos.

## 8. Problemas / gaps
- Instalación duplicada (script vs `ensureNgrok`), con post-condición distinta (`ngrok version` vs `--version`) y sin registry en la ruta de `TunnelManager`.
- ngrok es binario de código cerrado; el DNS depende del workaround `GODEBUG=netdns=go` (documentado en `TunnelManager.kt:306-313`).
- El comentario de `TunnelManager` dice que `ngrok` oficial falla con DNS en `[::1]:53`; no se re-probó.

## 9. Fecha de alta
`modulos/ngrok.sh`: 2026-08-18.
