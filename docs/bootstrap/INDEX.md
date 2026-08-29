# docs/bootstrap/ — Bootstrap y rootfs de Kairos

Documentación técnica del mecanismo de arranque inicial de la aplicación: cómo se instala el
entorno base de Termux, cómo se genera y embebe un conjunto de paquetes adicionales para acelerar
el primer arranque, y las decisiones de diseño e incidentes reales detrás de ese mecanismo.

- [INVESTIGACION_BOOTSTRAP_Y_PAQUETES.md](INVESTIGACION_BOOTSTRAP_Y_PAQUETES.md) — investigación
  sobre personalizar el bootstrap de Termux, alojar un mirror propio de paquetes, y si aislar
  archivos operativos en el sandbox privado de la app aporta algún beneficio de seguridad frente
  a `$HOME`.
- [ROOTFS_EMBEBIDO.md](ROOTFS_EMBEBIDO.md) — mecanismo real del rootfs embebido: generación de
  paquetes a partir del índice público de Termux, empaquetado, instalación real vía `apt` en el
  dispositivo, y los incidentes reales resueltos durante su desarrollo.
- [COMPILAR_LOCAL_ROOTFS_Y_APK_2026-08-24.md](COMPILAR_LOCAL_ROOTFS_Y_APK_2026-08-24.md) —
  guía para generar el rootfs y compilar el APK (con y sin rootfs embebido) en una máquina de
  desarrollo local, sin depender de un pipeline de integración continua ni de un repositorio
  remoto intermedio.
- [AUDITORIA_ROOTFS_END2END_2026-08-26.md](AUDITORIA_ROOTFS_END2END_2026-08-26.md) — historial
  detallado de los incidentes reales encontrados al llevar el mecanismo de rootfs embebido a
  funcionar de punta a punta en un dispositivo real, con causa raíz y fix de cada uno.
