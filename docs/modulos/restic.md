# restic

**Módulo Kairos** — sin Fragment propio ni switch, CLI puro invocado por el usuario vía
terminal, mismo patrón que `rclone`.

---

**App:** Kairos (fork termux-app)
**Script:** `modulos/restic.sh`
**Categoría:** nube
**`hasSwitch`:** `false` — sin proceso de fondo, CLI puro

---

## 1. Qué es

Backups incrementales cifrados con versionado real. Complementa al módulo `rclone` en vez de
solaparlo: `rclone` sincroniza o monta un remote, `restic` respalda CON historial de snapshots y
cifrado real — y puede usar un remote ya configurado con `rclone` como destino del propio
repositorio de backup, sin tener que configurar el acceso al proveedor de nuevo.

## 2. Instalación

Paquete nativo de Termux — un único binario Go estático, sin dependencias extra.

## 3. Uso

`restic init` (crear el repositorio), `restic backup <ruta>` (respaldar), `restic snapshots`
(ver el historial) y `restic restore` (recuperar) son inherentemente interactivos y específicos
de cada backup — la ubicación del repositorio y la contraseña de cifrado las elige el usuario.
Por diseño de la propia herramienta, este flujo queda en la terminal integrada de Kairos, sin
una pantalla propia todavía.
