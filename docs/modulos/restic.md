# restic

> **Complemento (ya no es un módulo del catálogo).** Vive en **Homelab → Servicios → Complementos**
> (`util/HomelabAddons.kt`). Este documento describe el diseño y el comportamiento de la herramienta.

CLI puro, sin Fragment propio ni switch (`hasSwitch: false`), invocado por el usuario vía terminal
(`terminalCommand: "restic"` en `modules.json`).

---

**Script:** `modulos/restic.sh` (v1.0.0) — copia sincronizada en
`app/src/main/assets/scripts/restic.sh`.
**Registry:** prefijo `restic.*`
**Puerto:** ninguno (CLI puro, sin servicio de fondo).

---

## 1. Descripción general

Backups incrementales cifrados con versionado real: respalda CON historial de snapshots y
cifrado.

## 2. Instalación

Paquete apt nativo instalado vía `install_single_pkg()` (mismo helper que `golang.sh`) — un único
binario Go estático, sin dependencias extra. Verificado contra el índice real de `termux-packages`
(`packages/restic/build.sh`, paquete normal, no root).

## 3. Uso (queda en terminal, por diseño de la herramienta upstream)

`restic init`/`restic backup <ruta>`/`restic snapshots`/`restic restore` son inherentemente
interactivos y específicos de cada backup (ubicación del repositorio y contraseña de cifrado
elegidas por el usuario). Sin pantalla propia todavía.
