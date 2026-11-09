# Tor

> **Complemento (ya no es un módulo del catálogo).** Es la pestaña "Tor" de **Ciberseguridad**
> (`util/TorManager.kt`). Este documento describe el diseño y el comportamiento de la herramienta.

Sin Fragment propio, gestionado por `GenericModuleFragment.kt` (el contrato `hasSwitch`+`tmuxSession`
de `modules.json` ya cubre instalar/iniciar/detener sin necesitar una pantalla dedicada — sin
`webviewUrl`, el proxy SOCKS no es HTTP navegable).

---

**Script:** `modulos/tor.sh` (v1.0.0) — copia sincronizada en
`app/src/main/assets/scripts/tor.sh`.
**Registry:** prefijo `tor.*`
**Puerto:** `9050` (proxy SOCKS5, `127.0.0.1` — sin UI web; apunta cualquier app/CLI compatible
con SOCKS5 ahí una vez el switch está en ON).

---

## 1. Descripción general

Red de anonimato/enrutamiento cebolla (The Onion Router) — expone un proxy SOCKS5 local.
Verificado contra el índice real de `termux-packages` (`packages/tor/build.sh`,
`TERMUX_PKG_VERSION="0.4.9.12"`, paquete normal, no root).

## 2. Puerto SOCKS

El propio Tor upstream documenta en su `torrc.sample` (el que `termux_step_post_make_install`
copia a `$PREFIX/etc/tor/torrc` tras instalar) que abre un proxy SOCKS en el puerto **9050** por
default incluso sin una línea `SocksPort` explícita en el config — comportamiento del binario
`tor` en sí (no específico de Android/Termux), sin parches en el `build.sh` de `termux-packages`
que lo alteren. Para no depender de ese default implícito, `tor.sh` PASO 2 agrega
`SocksPort 9050` de forma explícita a `torrc` si no detecta ya una línea `SocksPort` activa
(idempotente, mismo espíritu que el parcheo de puerto en `gitea.sh`).

## 3. Instalación

Paquete apt nativo (`install_single_pkg "tor" "tor" tor`) + parcheo idempotente de `torrc`
(PASO 2, checkpoint `torrc_patch`).

## 4. Arranque/detención (`hasSwitch: true`)

El módulo agrega `~/scripts/tor/start.sh`/`stop.sh` (mismo patrón tmux+puerto fijo que
`syncthing.sh`/`gitea.sh`):

```
tmux new-session -d -s tor-server \
  "tor -f $PREFIX/etc/tor/torrc > ~/kairos_logs/tor_serve.log 2>&1"
```

`ModuleController.kt` registra `tor` en sus tablas (`getModuleStartScript`/`getModuleStopInfo`/
`getTmuxSession`/`getModulePort` → 9050) — mismo patrón que `syncthing`/`gitea`, verificado por
`ModuleControllerTest.kt` (consistencia cruzada entre `modules.json` y estas tablas).

**Nota:** el `build.sh` real de `termux-packages` usa `TERMUX_PKG_SERVICE_SCRIPT` (framework de
`termux-services`, `sv-enable tor`) — Kairos no usa ese framework en ningún módulo, así que
`tor.sh` sigue el mismo patrón tmux que el resto de los módulos con switch, no el mecanismo
nativo de `termux-services`.

## 5. Uso

Una vez el switch está en ON, cualquier app/herramienta compatible con SOCKS5
(curl, navegadores, otros CLIs) se puede apuntar a `127.0.0.1:9050` para enrutar su tráfico por
Tor.
