# Gitea — Módulo de Kairos

**Módulo de Kairos** — sin Fragment propio, gestionado por `GenericModuleFragment.kt` (el
contrato `hasSwitch`+`tmuxSession`+`webviewUrl` de `modules.json` ya cubre instalar/iniciar/
detener/abrir la UI web sin necesitar una pantalla dedicada), mismo patrón que `syncthing`.

---

**Script:** `modulos/gitea.sh` (v1.0.0, 194 líneas) — copia sincronizada en
`app/src/main/assets/scripts/gitea.sh`. Paquete verificado contra el índice de `termux-packages`
(`packages/gitea/build.sh`, `TERMUX_PKG_VERSION="1.27.3"`, paquete normal, no root).
**Registry:** prefijo `gitea.*`
**Puerto:** `3001` (UI web HTTP, la app la abre embebida vía `webviewUrl http://localhost:3001`).

---

## 1. Descripción general

Servidor Git propio, autoalojado en el dispositivo (Gitea — "Git with a cup of tea"). Encaja con
la filosofía "home-lab en el bolsillo" (`docs/mini-pc/`) y con el módulo `ide`/Estudio: permite
hacer push del propio código del IDE integrado a un remote que vive en el mismo teléfono, sin
depender de GitHub/GitLab.

**Clasificación: MÓDULO FUNCIONAL liviano (sin Fragment propio).** Tiene servicio real
(`hasSwitch:true`, tmux `gitea-server`, puerto 3001), pero toda la UI es `GenericModuleFragment` (no
figura en `ModuleDetailNavigator.kt`): estado, switch iniciar/detener (`GenericModuleFragment.kt:147-160`),
abrir webview (164-169), mantenimiento (actualizar/diagnosticar/desinstalar). No tiene `terminalCommand`,
así que no hay botón de terminal. ~6 opciones de UI genéricas; toda la configuración real (usuario
admin, repos, tokens) ocurre en la web de Gitea.

## 2. Conflicto de puerto — resuelto en la instalación

El `app.ini` que empaqueta `termux-packages` (`packages/gitea/app.ini`) **no trae sección
`[server]`**, así que Gitea correría con su puerto HTTP por default: **3000**. Ese puerto ya lo
usa el módulo `opencode` en Kairos (`"port": "3000"` en `modules.json` y `getModulePort()` en
`ModuleController.kt`) — por eso `gitea.sh` PASO 2 parchea `$PREFIX/etc/gitea/app.ini` agregando:

```ini
[server]
HTTP_PORT = 3001
DISABLE_SSH = true
```

`DISABLE_SSH = true` también evita que Gitea intente levantar su servidor SSH interno (default
puerto 22, que Android/Termux sin root no puede bindear — puertos <1024 requieren
`CAP_NET_BIND_SERVICE`) — Kairos ya tiene su propio `sshd` vía el módulo `remote` (puerto 8022).
El acceso Git contra este servidor queda por HTTP (`clone`/`push http://127.0.0.1:3001/user/repo.git`),
no por SSH.

## 3. Instalación

`gitea.sh --silent [--force]`.
- PASO 1: `install_single_pkg "gitea" "gitea" gitea` (apt, verifica `gitea --version`).
- PASO 2: parcheo idempotente de `$PREFIX/etc/gitea/app.ini` con checkpoint `app_ini_patch` — solo si
  el archivo existe y no trae ya una sección `[server]` propia (por si el usuario la personalizó a mano
  tras una instalación anterior; `gitea.sh:116-137`; si falta el archivo, `error`).
- PASO 3: escribe `~/scripts/gitea/start.sh|stop.sh` (checkpoint `serve_scripts`); `registry_install gitea … http_port=3001`.
- Sin descargas directas ni sha256 (apt).

## 4. Arranque/detención (`hasSwitch: true`)

`~/scripts/gitea/start.sh`/`stop.sh` (mismo patrón tmux+puerto fijo que `syncthing.sh`/`cactus.sh` PASO 5):

```
tmux new-session -d -s gitea-server \
  "gitea web --config $PREFIX/etc/gitea/app.ini > ~/kairos_logs/gitea_serve.log 2>&1"
```

`start.sh` hace `sleep 3` y valida que la sesión tmux exista (no que el puerto responda); `stop.sh` mata
la sesión. `ModuleController.kt` conoce `gitea` en sus tablas (`getModuleStartScript`/`getModuleStopInfo`/
`getTmuxSession`/`getModulePort` → 3001; `:1179,1192,1203,1245`) — mismo patrón que `syncthing`, verificado por
`ModuleControllerTest.kt` (consistencia cruzada entre `modules.json` y estas tablas).

## 5. Funciones de UI y configuración

Switch Iniciar/Detener, "Abrir interfaz web", Actualizar, Diagnosticar, Desinstalar. Nada específico de Gitea.

La creación del primer usuario admin y de repos es el wizard de instalación propio de Gitea, la
primera vez que se abre la UI — inherentemente interactivo por diseño de la herramienta upstream
(mismo criterio que `rclone config`/pairing de Syncthing) — el usuario lo completa desde
`http://localhost:3001`, que `GenericModuleFragment` abre embebido cuando el switch está en ON.

## 6. Archivos y rutas

`$PREFIX/etc/gitea/app.ini` (modificado), `~/scripts/gitea/{start,stop}.sh`, `~/kairos_logs/gitea_serve.log`,
datos de Gitea (repos/DB) en su ruta de trabajo por defecto (ruta exacta no verificada en dispositivo), registry `gitea.*`.

## 7. Seguridad

El `app.ini` parcheado no fija `HTTP_ADDR`: por defecto upstream Gitea escucha en todas las interfaces
(inferencia, no verificado en dispositivo), mientras el script dice "127.0.0.1" (`gitea.sh:165`). Si es
así, la UI y el wizard de primer usuario quedarían expuestos a la LAN antes de crear el admin. Sin
secretos en el script. Sin `curl|bash`.

## 8. Limitaciones conocidas

- Ni el script ni la UI fijan `HTTP_ADDR=127.0.0.1` ni `INSTALL_LOCK`; el mensaje de start afirma
  "127.0.0.1:3001" sin garantizarlo.
- El parcheo de `app.ini` se omite si ya existe cualquier `[server]` (no se verifica que el puerto sea
  3001); una config previa con otro puerto rompería el switch (la app espera 3001).
- `start.sh` solo valida que la sesión tmux exista, no que el puerto responda.
- Sin pantalla dedicada: no hay gestión de usuarios/repos; la integración "Estudio → remote local" que
  sugiere la cabecera del script no existe en el código leído.

## 9. Fecha de alta

`modulos/gitea.sh`: **2026-09-15**.
