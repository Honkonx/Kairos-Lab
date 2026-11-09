# Syncthing

> **Complemento (ya no es un módulo del catálogo).** Vive en **Homelab → Servicios → Complementos**
> (`util/HomelabAddons.kt`). Este documento describe el diseño y el comportamiento de la herramienta.

Sin Fragment propio, gestionado por `GenericModuleFragment.kt` (el contrato
`hasSwitch`+`tmuxSession`+`webviewUrl` de `modules.json` ya cubre instalar/iniciar/detener/abrir
la UI web sin necesitar una pantalla dedicada).

---

**Script:** `modulos/syncthing.sh` (v1.0.0) — copia sincronizada en
`app/src/main/assets/scripts/syncthing.sh`.
**Registry:** prefijo `syncthing.*`
**Puerto:** `8384` (UI web, `127.0.0.1` únicamente — la app la abre embebida vía `webviewUrl`)

---

## 1. Descripción general

Sincronización P2P de archivos entre dispositivos, sin depender de ningún proveedor cloud.

## 2. Instalación

Paquete apt nativo instalado vía `install_single_pkg()` (ver `modulos/lib.sh`), verificado contra
el índice real de `termux-packages` (`packages/syncthing/build.sh`, `TERMUX_PKG_VERSION="2.1.5"`,
paquete normal, no root). Syncthing 2.x requiere el subcomando `serve` (las versiones 1.x corrían
directo con solo flags).

## 3. Arranque/detención (`hasSwitch: true`)

El módulo agrega `~/scripts/syncthing/start.sh`/`stop.sh` (mismo patrón tmux+puerto fijo que
`cactus.sh` PASO 5 / `llamaserver.sh`):

```
tmux new-session -d -s syncthing-server \
  "syncthing serve --no-browser --no-restart --gui-address=127.0.0.1:8384 > ~/kairos_logs/syncthing_serve.log 2>&1"
```

`ModuleController.kt` registra `syncthing` en sus tablas (`getModuleStartScript`/
`getModuleStopInfo`/`getTmuxSession`/`getModulePort` → 8384) — mismo patrón que `cactus`,
verificado por `ModuleControllerTest.kt` (consistencia cruzada entre `modules.json` y estas
tablas).

## 4. Configuración (queda en la UI web propia)

El pareo de dispositivos (Device ID) y las carpetas a sincronizar son inherentemente
interactivos por diseño de la herramienta upstream — el usuario los configura desde
`http://localhost:8384`, que `GenericModuleFragment` abre embebido cuando el switch está en ON
(campo `webviewUrl` de `modules.json`).
