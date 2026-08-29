# docs/bootstrap/ — Kairos bootstrap and rootfs

Technical documentation for the application's initial startup mechanism: how the base Termux
environment is installed, how an additional set of packages is generated and embedded to speed up
first launch, and the design decisions and real incidents behind that mechanism.

- [INVESTIGACION_BOOTSTRAP_Y_PAQUETES.md](INVESTIGACION_BOOTSTRAP_Y_PAQUETES.md) — investigation
  into customizing the Termux bootstrap, hosting a self-owned package mirror, and whether
  isolating operational files in the app's private sandbox offers any security benefit over
  `$HOME`.
- [ROOTFS_EMBEBIDO.md](ROOTFS_EMBEBIDO.md) — the real mechanism behind the embedded rootfs:
  package generation from the public Termux index, packaging, real on-device installation via
  `apt`, and the real incidents resolved during development.
- [COMPILAR_LOCAL_ROOTFS_Y_APK_2026-08-24.md](COMPILAR_LOCAL_ROOTFS_Y_APK_2026-08-24.md) — guide
  for generating the rootfs and building the APK (with and without the embedded rootfs) on a
  local development machine, without depending on a continuous-integration pipeline or an
  intermediate remote repository.
- [AUDITORIA_ROOTFS_END2END_2026-08-26.md](AUDITORIA_ROOTFS_END2END_2026-08-26.md) — detailed
  history of the real incidents found while getting the embedded rootfs mechanism to work end to
  end on a real device, with root cause and fix for each one.
