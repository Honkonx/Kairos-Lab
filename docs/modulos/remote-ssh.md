# Remote (SSH + Cloudflared)

**Módulo de Kairos** — gestionado desde la UI (pestaña Módulos). Instalación manejada por la app vía `ProcessBuilder` → `modulos/ssh.sh`; runtime (start/stop, claves, tunnel) corre vía `RemoteManager.kt`, invocado desde `RemoteFragment.kt`.

---

**Script:** `modulos/ssh.sh` — espejo en `app/src/main/assets/scripts/ssh.sh`. El archivo se llama `ssh.sh`, pero el módulo real (`id` en `modules.json`) es `remote` — asimetría de nombre deliberada, ver sección 7.
**Fragment:** `app/src/main/java/com/termux/app/ui/RemoteFragment.kt`
**Manager:** `app/src/main/java/com/termux/app/util/RemoteManager.kt` (toda la lógica de runtime real)
**`id` en `modules.json`:** `remote` — con switch (único de este lote de módulos que tiene un proceso persistente, `sshd`)

---

## 1. Descripción general

Remote da acceso SSH al dispositivo (puerto **8022**, no el 22 estándar — evita conflicto con otros usos del puerto privilegiado y no requiere root) más un **túnel Cloudflare** opcional (`cloudflared`) para conectarse desde fuera de la red local sin abrir puertos en el router.

## 2. Permisos

- **Android**: ninguno especial más allá del permiso base de red que ya tiene la app.
- **Termux interno**: genera claves de servidor SSH propias (`ssh-keygen -A`), no reusa ninguna del sistema.
- **Red**: puerto 8022 abierto en `0.0.0.0` (todas las interfaces) — accesible desde cualquier dispositivo en la misma red sin túnel; con Cloudflare Tunnel, accesible también desde internet vía el token configurado.

## 3. Lógica de instalación (`modulos/ssh.sh`)

Script de 6 pasos con checkpoints:

| Paso | Qué hace |
|---|---|
| 1 | Actualización del sistema (con fallback a mirrors), salteable si ya se corrió antes |
| 2 | `openssh` + `tmux` (solo lo que falte) |
| 3 | Escribe `sshd_config` real (puerto 8022, autenticación por contraseña y clave pública, sin login root, sin X11Forwarding, con SFTP) — backup del config anterior si existía; genera claves de servidor; crea `~/.ssh/authorized_keys` |
| 4 | Genera `~/scripts/remote/ssh_start.sh` (arranca `sshd`, imprime `ssh -p 8022 usuario@IP` con la IP real detectada) y `ssh_stop.sh` |
| 5 | Descarga `cloudflared` ARM64 nativo desde GitHub Releases (binario real, sin paquete `pkg`) — si falla o el binario resulta no ejecutable, lo borra y avisa, pero no aborta la instalación (SSH funciona sin tunnel) |
| 6 | Aliases (`ssh-start`, `ssh-stop`, `ssh-status`) + registry |

**Flags soportados**: `--silent`, `--force`, `--describe`.

**Atajo de reinstalación**: si SSH ya está configurado y `cloudflared` está disponible y no hay `--force`, el script sale temprano — pero siempre repara el registry antes de salir.

## 4. Detección de estado

- **Instalación**: registry (`remote.installed`).
- **"Corriendo"**: se verifica por proceso-por-nombre (`sshd`), no por sesión tmux — a diferencia de n8n/Ollama/OpenClaw/OpenCode. `RemoteFragment` usa el mismo mecanismo que `ModuleController`, para que ambos no puedan divergir.
- **Cloudflare Tunnel**: se chequea la sesión tmux del túnel o el proceso `cloudflared` corriendo.

## 5. Pantalla de la app (`RemoteFragment.kt`)

SSH y Cloudflare Tunnel son 2 switches reales, sincronizados con el estado real vía polling.

- **Switch "SSH"**: arranca/detiene vía el ciclo de vida estándar de módulos (`ModuleController`), con confirmación real de puerto abierto antes de reportar éxito.
- **Switch "Túnel Cloudflare"**: arranca/detiene el túnel Cloudflare específico de Remote.

Card "INFO" (SSH corriendo/detenido + puerto, IP, usuario, conexiones activas — con polling automático cada 5s mientras la pantalla está abierta), luego card "SERVIDOR EN LA RED" (campo de IP + botón "Buscar servidor en la red" que escanea la LAN), switch SSH, y el resto de botones:

| Botón / control | Acción real |
|---|---|
| Switch **SSH** | Arranca/detiene el proceso `sshd` |
| Buscar servidor en la red | Escanea la LAN buscando el puerto de servicio, lista resultados, pega la IP elegida en el campo |
| Info de conexión | Arma IP/usuario/conexiones/estado |
| Agregar clave pública | Prompt de texto, agrega a `~/.ssh/authorized_keys` |
| Cambiar contraseña | Prompt de texto |
| Conexiones SSH activas | Lista sesiones + PID del daemon |
| Copiar comando SSH | Copia el `ssh -p 8022 usuario@IP` real (ya armado con la IP detectada) al portapapeles |
| Huella del servidor (fingerprint) | Corre `ssh-keygen -lf` sobre cada archivo de clave de host ya generado (una huella por tipo: RSA/ECDSA/ED25519) y las muestra, para que el usuario pueda verificarlas contra lo que ve el cliente SSH al conectar la primera vez (protección contra ataques de intermediario) |
| Switch **Túnel Cloudflare** | Arranca/detiene el túnel |
| Configurar token CF | Guarda el token de Cloudflare (prompt de texto) |
| Cómo conectarse vía CF-SSH | Instrucciones reales |

**Card "SEGURIDAD SSH"**: panel que expone controles de `sshd_config` en vivo (nunca cacheado, releído tras cada acción):

| Control | Acción real |
|---|---|
| Switch "Requerir clave SSH siempre" | Deshabilita/habilita `PasswordAuthentication`; bloqueado en la UI si no hay ninguna clave en `authorized_keys` (guardrail para no perder acceso) |
| Cambiar puerto SSH | Puertos 1024-65535 (sshd corre sin root) |
| Alternar login root | Advertencia explícita antes de permitir |
| Generar clave propia | Para que el dispositivo se conecte como cliente a otros servidores |
| Copiar mi clave pública propia | — |

**Polling**: `RemoteFragment` mantiene su propio manejador de refresco que se cancela explícitamente al salir de la pantalla, evitando que siga re-programándose en segundo plano.

Toda la lógica de runtime vive en Kotlin (`RemoteManager.kt`).

## 6. Registry (`~/.android_server_registry`)

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

## 7. Notas de diseño

- **Doble prefijo `ssh.*`/`remote.*` — deliberado**: el `id` real del módulo en `modules.json` es `remote`, y toda la UI Kotlin lee `remote.installed`. El script en cambio se llama `ssh.sh` y originalmente solo escribía claves `ssh.*` — para mantener compatibilidad hacia atrás, el registry se escribe con ambos prefijos: `ssh.*` se mantiene por compatibilidad con el propio auto-chequeo del script; `remote.*` es lo que la app realmente lee.
- La salida temprana del script (cuando ya está todo instalado) repara el registry en cada corrida, para que un simple reintento sin `--force` alcance para corregir cualquier inconsistencia.
- El checkpoint de `cloudflared` solo se marca si el binario quedó ejecutable de verdad tras la descarga.
- **`ssh.sh` no es el nombre que ve el usuario en la app** — la pantalla dice "Remote", el archivo interno se llama `ssh.sh` por convención histórica del proyecto (nombrado por el protocolo, no por el módulo).

## Alcance real cubierto

`RemoteFragment.kt` cubre de forma completa lo que OpenSSH ofrece de cara al usuario: agregar clave pública, cambiar contraseña, ver conexiones activas, huella del servidor, cambiar puerto, alternar login root, generar/copiar clave propia, y token+guía de Cloudflare. Port forwarding (un caso de uso más avanzado/riesgoso) queda deliberadamente fuera de la UI.
