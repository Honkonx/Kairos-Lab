# Remote (SSH + Cloudflared) — Módulo de Kairos

**Módulo de Kairos** — gestionado vía la UI de Kairos (pestaña Módulos). Instalación manejada por la app vía `ProcessBuilder` → `modulos/ssh.sh`; runtime (start/stop, claves, túnel) corre vía `RemoteManager.kt`, invocado desde `RemoteFragment.kt`.

---

**Proyecto:** Kairos
**Script:** `modulos/ssh.sh` — espejo en `app/src/main/assets/scripts/ssh.sh`. **Ojo con el nombre**: el archivo se llama `ssh.sh`, pero el módulo real (`id` en `modules.json`) es `remote` — asimetría de nombre deliberada, ver sección 8.
**Fragment:** `app/src/main/java/com/termux/app/ui/RemoteFragment.kt`
**Manager:** `app/src/main/java/com/termux/app/util/RemoteManager.kt` (lógica de runtime)
**`id` en `modules.json`:** `remote` — `hasSwitch: true` (tiene un proceso persistente, `sshd`), puerto 8022, `requires: ["openssh"]`, `catalogVersion 1.1.0`

> Basado en la lectura de `modulos/ssh.sh` (451 líneas), `RemoteFragment.kt` (1374), `util/RemoteManager.kt` (1168), `ModuleController.kt` y la entrada de `modules.json`.

---

## 1. Qué es

Dos direcciones SSH: (a) Kairos como **servidor** (OpenSSH en `:8022`, túnel Cloudflare, mosh-server) para controlarlo desde fuera; (b) Kairos como **cliente** ("Receptor": conexiones guardadas a otros servidores, claves importadas).

El acceso SSH usa el puerto **8022**, no el 22 estándar — evita conflicto con otros usos del puerto privilegiado y no requiere root. Un **túnel Cloudflare** opcional (`cloudflared`) permite conectarse desde fuera de la red local sin abrir puertos en el router. Es un módulo con proceso persistente real: `sshd` corre en segundo plano igual que n8n/Ollama/OpenClaw/OpenCode.

## 2. Clasificación: MÓDULO FUNCIONAL

UI propia con estado en vivo, servicio, switches y opciones reales. 5 pestañas: **Emisor** (4 filas de info + switch SSH + 8 botones + tarjeta USB/ADB con 2 botones), **Seguridad** (switch "requerir clave", 4 botones, 4 filas), **Cloudflare** (switch túnel + 2 botones), **Red** (campo IP + escaneo LAN), **Receptor** (clave propia, claves importadas, conexiones guardadas, conexión efímera). Aprox. 25-30 controles reales.

## 3. Permisos

- **Android**: ninguno especial más allá del permiso base de red que ya tiene la app.
- **Termux interno**: genera claves de servidor SSH propias (`ssh-keygen -A`), no reusa ninguna del sistema.
- **Red**: puerto 8022 abierto en `0.0.0.0` (todas las interfaces) — accesible desde cualquier dispositivo en la misma red sin túnel; con Cloudflare Tunnel, accesible también desde internet vía el token configurado.

## 4. Instalación (`ssh.sh --silent [--force]`)

Script de 6 pasos con checkpoints (`$HOME/.install_remote_checkpoint`):

| Paso | Qué hace | Checkpoint |
|---|---|---|
| 1 | `pkg_update_with_fallback`, salteable si `instalar.sh`/`kairos.sh` ya lo hizo | `termux_update` |
| 2 | `openssh` + `tmux` (solo lo que falte) | `remote_deps` |
| 3 | **Sobrescribe** `$PREFIX/etc/ssh/sshd_config` (respaldo único `.bak`) con `Port 8022`, `ListenAddress 0.0.0.0`, `PasswordAuthentication yes`, `PubkeyAuthentication yes`, `PermitRootLogin no`, `MaxAuthTries 6`, `MaxSessions 5`, keepalive 60s/3, sin X11Forwarding, con SFTP; genera claves de servidor (`ssh-keygen -A`); crea `~/.ssh/authorized_keys` (chmod 600) | `ssh_config` |
| 4 | Genera `~/scripts/remote/ssh_start.sh` (arranca `sshd`, imprime `ssh -p 8022 usuario@IP` con la IP real detectada vía `ifconfig`/`ip addr`) y `ssh_stop.sh` (`pkill sshd`) | `ssh_scripts` |
| 5 | Descarga `cloudflared-linux-arm64` desde `releases/latest/download` a `$PREFIX/bin/cloudflared` (binario real, sin paquete `pkg`; **sin sha256, sin versión fija**) y `mosh` best-effort — si `cloudflared` falla o resulta no ejecutable (incompatible con Bionic), lo borra y avisa, pero NO aborta la instalación (SSH funciona sin túnel, solo se pierde el acceso remoto) | `cloudflared_install` — **solo se marca si `cloudflared` quedó ejecutable de verdad** |
| 6 | Aliases (`ssh-start`, `ssh-stop`, `ssh-status`) en `.bashrc` + registry | `remote_aliases` |

**Flags soportados**: `--silent`, `--force`, `--describe` (`{"id":"remote",...,"variants":[],"variant_required":false}`).

**Atajo de reinstalación**: si SSH ya está configurado (`sshd_config` tiene `Port 8022` o el registry dice `ssh.installed=true`) Y `cloudflared` está disponible Y no hay `--force`, el script sale temprano — pero SIEMPRE repara el registry antes de salir (ver sección 8). Verifica `command -v sshd`; no ejecuta el binario.

## 5. Detección de estado y ejecución

- **Instalación**: registry (`remote.installed`).
- **"Corriendo"**: `ModuleController.isRunning("remote")` usa `pgrep -x sshd` (proceso-por-nombre, no tmux — a diferencia de n8n/Ollama/OpenClaw/OpenCode que corren en sesiones tmux). `RemoteFragment.updateInfoRows()` usa este mismo mecanismo (no un booleano calculado aparte en `RemoteManager`) para que ambos no puedan divergir.
- **Cloudflare Tunnel**: `RemoteManager.info()` chequea `tmux has-session -t <sesión CF>` O `pgrep -f "cloudflared.*tunnel"`.
- **Start/stop de SSH**: `ssh_start.sh` → `sshd` y `pgrep -x sshd`; stop: `pkill sshd`. La app usa `startModuleService`/`stopModuleService` (`ModuleController`: `remote` → `ssh_start.sh`/`sshd`/8022).
- **Túnel**: sesión tmux `cf-ssh-tunnel` con `cloudflared tunnel run --token <token> | tee log` (`RemoteManager.cfStart()`/`cfStop()`).
- Los cambios de `sshd_config` reinician sshd con `pkill sshd; sshd`.

## 6. Pantalla real de la app (`RemoteFragment.kt`)

SSH y Cloudflare Tunnel son 2 `SwitchRow` reales (`sshSwitch`, `tunnelSwitch`), sincronizados en `updateInfoRows()` con el estado real del polling (cada 5 s mientras la pantalla está abierta; el `Handler`/`Runnable` se cancela en `onDestroyView()`).

- **Switch "SSH"**: `on=true` → `startModuleService { ... }` (heredado de `BaseModuleFragment`, vía `ModuleController`); `on=false` → `stopModuleService { ... }`. Así hay un único camino de lifecycle, con `waitForPortOpen()` y `ModuleEventBridge.notifySessionEvent()`.
- **Switch "Túnel Cloudflare"**: `runRemoteAction("cf-start"/"cf-stop")` → `RemoteManager.cfStart()`/`cfStop()`.

| Botón / control | Acción real |
|---|---|
| Card INFO (SSH/IP/Usuario/Conexiones) | Solo lectura, poll cada 5 s |
| Campo IP + 📡 Buscar servidor en la red | `scanSubnet()` — escanea la LAN buscando el puerto 4096 (servidores OpenCode), lista resultados en diálogo, pega la IP elegida en el campo |
| ℹ Info de conexión | `info()` — arma IP/usuario/conexiones/estado |
| 🔑 Agregar clave pública | `sshAddKey(clave)` — prompt de texto, agrega a `~/.ssh/authorized_keys` |
| 🔒 Cambiar contraseña | `sshSetPassword(pass)` — `passwd` por stdin, sin interpolar en shell |
| 🔌 Conexiones SSH activas | `sshConnections()` — lista sesiones + PID del daemon, en `AlertDialog` |
| 📋 Copiar comando SSH | `copyConnectCommand()` — copia `info.connectCmd` (el `ssh -p 8022 usuario@IP` real) al portapapeles |
| 🔐 Huella del servidor (fingerprint) | `showHostKeyFingerprints()` — corre `ssh-keygen -lf` sobre cada `*_key.pub` (RSA/ECDSA/ED25519) y las muestra para que el usuario pueda verificarlas contra lo que ve el cliente SSH al conectar la primera vez (protección contra MITM) |
| 🔑 Configurar token CF | `cfSetToken(token)` — guarda el token de Cloudflare (vía `TunnelManager`) |
| 📖 Cómo conectarse vía CF-SSH | `cfInfo()` — instrucciones |
| 🗑 Desinstalar (card MANTENIMIENTO) | `confirmUninstallModule()` |

**Seguridad SSH** (lee `sshd_config` en vivo, nunca cacheado, vía `refreshSecurityPanel()`):

| Control | Acción real (`RemoteManager`) |
|---|---|
| Switch "Requerir clave SSH siempre" | `sshSetPasswordAuth(false/true)` — deshabilita/habilita `PasswordAuthentication`; se niega a desactivarla si no hay ninguna clave en `authorized_keys` (guardrail anti auto-lockout, en UI y server-side) |
| 🔌 Cambiar puerto SSH | `sshSetPort(port)` — puertos 1024-65535 (sshd corre sin root) |
| ⛔ Alternar login root | `sshSetPermitRootLogin(true/false)` — botón DANGER, advertencia explícita antes de permitir |
| 🔑 Generar clave propia | `sshGenerateOwnKeypair()` — ed25519, para que el dispositivo se conecte COMO CLIENTE a otros servidores |
| 📋 Copiar mi clave pública propia | copia `securityConfig.ownPublicKey` |

**Receptor** (Kairos como cliente): importar clave privada (aviso: nunca se vuelve a mostrar), conectar por clave de sesión (archivo efímero borrado a los 30 s), conexiones guardadas con sonda TCP y chequeo de cambio de host key.

**Anti-tapjacking** en la pantalla. `RemoteFragment.kt` ya cubre de forma amplia lo que OpenSSH ofrece; port forwarding es un caso de uso más avanzado y se deja fuera a propósito.

## 7. Registry (`~/.android_server_registry`)

```
ssh.installed=true
ssh.version=<versión de OpenSSH>
ssh.install_date=<YYYY-MM-DD>
ssh.port=8022
ssh.location=termux_native
ssh.auth=password+pubkey
remote.installed=true
remote.version=<misma versión>
remote.install_date=<YYYY-MM-DD>
remote.port=8022
remote.location=termux_native
```

También hay claves `ssh_client.*` para el modo Receptor.

## 8. Notas de diseño

- **Doble prefijo `ssh.*`/`remote.*` — deliberado**: el `id` real del módulo en `modules.json` es `remote`, y toda la UI Kotlin (`ModuleRegistry`, `BaseModuleFragment.isModuleInstalled()`, `ModulesFragment.pollStatus()`) lee `remote.installed`. El script se llama `ssh.sh` y escribe AMBOS prefijos: `ssh.*` por compatibilidad con el propio auto-chequeo del script, `remote.*` es lo que la app realmente lee.
- **La salida temprana repara el registry**: el script repara el registry en CADA corrida (incluso cuando ya está todo instalado), para que un simple reintento sin `--force` alcance para dejar `remote.installed=true`.
- **Checkpoint de `cloudflared` verificado**: `mark_done "cloudflared_install"` solo corre si `command -v cloudflared` confirma que quedó ejecutable de verdad; si no, un reintento vuelve a intentar la descarga.
- **`ssh.sh` NO es el nombre que ve el usuario en la app** — la pantalla dice "Remote", el archivo interno se llama `ssh.sh` por convención histórica del proyecto (nombrado por el protocolo, no por el módulo).

## 9. Archivos y rutas

`$PREFIX/etc/ssh/sshd_config(.bak)`, `~/.ssh/{authorized_keys,id_ed25519*}`, `~/.ssh/imported/<id>` (600), `~/.ssh/.ephemeral/`, `~/scripts/remote/`, `$PREFIX/bin/cloudflared`, `~/.bashrc` (aliases `ssh-start/stop/status`), registry (`remote.*`, `ssh.*`, `ssh_client.*`), checkpoint `~/.install_remote_checkpoint`, log del túnel (`CF_SSH_LOG_FILE`).

## 10. Seguridad

- `ListenAddress 0.0.0.0` + `PasswordAuthentication yes` por defecto: el SSH queda en toda interfaz con contraseña habilitada. Mitigación existente: switch "Requerir clave" y bloqueo anti auto-lockout (`RemoteManager.kt:405-424`).
- El token de Cloudflare se pasa en la línea de comandos (`--token`, visible en `ps`/tmux) y se guarda en texto plano en el registry (`TunnelManager.saveConfig`), no en un almacén protegido. Sí está escapado con `shellQuote`.
- Un secreto guardado nunca se vuelve a mostrar: ninguna función devuelve el contenido de una clave importada guardada (solo alias + fingerprint, reemplazar, borrar); `chmod 700/600` en claves.
- `cloudflared` se baja de `latest` sin verificación de integridad (supply-chain).
- Inferencia: el binario descargado puede sufrir el problema de DNS de Go en Android documentado en `docs/ssh/tunel-cloudflared-nativo.md` (usa `/etc/resolv.conf`); `RemoteManager.cfStart()` invoca `cloudflared` del PATH, no el `libcloudflared.so` nativo (`nativeLibraryDir`) que usa `TunnelManager` para otros túneles. No se probó el modo `--token` en dispositivo.

## 11. Problemas / limitaciones

- `ssh.sh` no establece contraseña ni clave; hasta que el usuario cree una (UI) el login por contraseña no es utilizable.
- `verificar.sh` valida `remote` contra `cloudflared`, no `sshd` (ver `VERIFICAR.md`).
- `ssh_stop.sh` usa `pkill sshd` (mata todos los sshd, incluidas sesiones activas).
- La cabecera de `ssh.sh` cita el repo `termux-ai-stack` y `install_remote.sh`, y el banner dice v2.1.0 mientras el encabezado dice v2.2.0 (cosmético).
- `passwd` se escribe en un proceso sin timeout al leer salida antes de `waitFor(10s)` (`readText()` previo al timeout); un `passwd` colgado bloquearía el hilo.
- Inferencia: mosh-server se instala pero la UI no ofrece nada específico de mosh.

## 12. Fecha de alta

`modulos/ssh.sh` agregado el **2026-07-19**.
