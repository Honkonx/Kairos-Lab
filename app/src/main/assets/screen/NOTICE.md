# kairos-screen-server.jar — origen y licencia

`kairos-screen-server.jar` es el servidor de pantalla real de **scrcpy** (© Genymobile y
colaboradores, https://github.com/Genymobile/scrcpy, licencia Apache-2.0 — ver `LICENSE-scrcpy.txt`
en esta misma carpeta), compilado sin modificar desde el código fuente en
`referencia/pc-movil/scrcpy/server/` (clon local, commit visible con `git -C referencia/pc-movil/scrcpy log -1`).

Compilado el 2026-10-13 a mano (`server/build_without_gradle.sh`, adaptado a Windows —
ver `docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md` para el procedimiento completo y el bug
real encontrado en `aidl.exe` de build-tools 36.0.0, evitado usando build-tools 34.0.0 solo para el
paso de `aidl`). No se modificó ni una línea de las fuentes de scrcpy — Kairos reusa el binario
compilado tal cual, aprovechando que Apache-2.0 lo permite sin pedir permiso, manteniendo el aviso
de copyright y la licencia (este archivo cumple esa condición).

Lo que SÍ es código propio de Kairos: `ScreenMirrorManager.kt` (cómo/cuándo se lanza el proceso),
`internal/screen/local.go` + `ScreenStreamProtocol.kt`/`internal/screen/protocol.go` (el relay que
recibe lo que este servidor manda y lo reenvía por WebSocket).
