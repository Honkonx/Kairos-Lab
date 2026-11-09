; Instalador de Kairos Gateway para Windows (NSIS).
;
; El gateway en si (native/gateway, Go puro de stdlib, CGO_ENABLED=0) no tiene dependencias en
; tiempo de ejecucion -- "instalar todo lo que necesita" aqui significa: copiar el binario a una
; carpeta real del usuario (sin pedir admin), crear accesos directos (Menu Inicio + Escritorio),
; y registrar el desinstalador en "Agregar o quitar programas". No descarga nada de internet
; durante la instalacion.
;
; Correccion 2026-11-05 (reporte real del usuario, docs/humano369.md: "el gateway de escritorio
; se abre al iniciar windows, toca quitar eso para que solo se abra si el usuario lo abra"): esta
; version SI ofrecia un checkbox opcional de arranque automatico (ver seccion "Correccion
; 2026-10-30" mas abajo, ahora eliminada) -- quedo removido por completo, ni siquiera como
; opcion: Kairos Gateway NUNCA se agrega al arranque de Windows desde el instalador. La seccion
; de desinstalacion sigue borrando la clave de Run por si quedo de una version anterior.
;
; Corrección 2026-10-30 (reporte real del usuario, docs/humano369.md: "solo deben ser dos .exe,
; uno portable y el otro instalable"): antes se instalaban DOS binarios (kairos-gateway.exe +
; kairos-dashboard.exe, con una rama !if/!else segun si el segundo se habia compilado). Ahora
; kairos-dashboard.exe lleva el gateway EMBEBIDO (//go:embed, ver cmd/kairos-dashboard/
; embed_windows.go) -- un solo binario real para instalar, siempre, sin rama condicional. El
; navegador deja de ser una opcion de arranque aca: kairos-dashboard.exe nunca abre uno por su
; cuenta (ver KDoc de main.go) -- el usuario que prefiera el navegador puede seguir usando
; kairos-dashboard.exe --connect o pegando la URL del panel a mano.
;
; Requiere: makensis (NSIS, https://nsis.sourceforge.io/) solo para COMPILAR este .nsi -> .exe.
; El usuario final que recibe Kairos-Gateway-Setup.exe no necesita NSIS instalado.
;
; Uso: tools/build-gateway-installer-windows.ps1 (compila el binario + corre makensis con este
; script) -- no invocar makensis a mano salvo para depurar.

!define APP_NAME "Kairos Gateway"
!define APP_EXE "kairos-dashboard.exe"
!define APP_PUBLISHER "Honkonx"
!define APP_VERSION "${KAIROS_GATEWAY_VERSION}" ; inyectado por build-gateway-installer-windows.ps1 (/DKAIROS_GATEWAY_VERSION=...)
!define APP_UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\KairosGateway"

Name "${APP_NAME}"
OutFile "${OUT_FILE}" ; inyectado por el script de build (/DOUT_FILE=...)
InstallDir "$LOCALAPPDATA\KairosGateway" ; sin admin: %LOCALAPPDATA% siempre escribible por el usuario actual
RequestExecutionLevel user
Unicode true
Icon "kairos_logo.ico"
UninstallIcon "kairos_logo.ico"

!include "MUI2.nsh"
!define MUI_ICON "kairos_logo.ico"
!define MUI_UNICON "kairos_logo.ico"

!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Spanish"

Section "Instalar"
  SetOutPath "$INSTDIR"

  ; Hallazgo real (2026-10-30, docs/humano369.md, "analizas si el gateway instalable se puede
  ; actualizar"): reinstalar ENCIMA mientras kairos-dashboard.exe (y el gateway embebido que
  ; arranca) siguen corriendo falla en SILENCIO -- Windows no deja sobreescribir un .exe en uso,
  ; y sin este chequeo el instalador seguía de largo igual (registro/accesos directos quedaban
  ; bien, pero el binario real NUNCA se actualizaba) -- confirmado reinstalando de verdad encima
  ; de un proceso corriendo: el .exe instalado quedó con el timestamp VIEJO. Mismo destino que
  ; siempre ($INSTDIR fijo, nunca otra carpeta) -- lo único que fallaba era la copia en sí.
  checkRunning:
    ClearErrors
    Delete "$INSTDIR\${APP_EXE}"
    IfErrors 0 +3
      MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "Kairos Gateway está abierto. Cerralo y tocá Reintentar." IDRETRY checkRunning
      Abort "Instalación cancelada: cerrá Kairos Gateway primero y volvé a correr el instalador."

  File "${DASHBOARD_EXE_PATH}" ; kairos-dashboard.exe, inyectado por el script de build (/DDASHBOARD_EXE_PATH=...) -- lleva el gateway embebido adentro

  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}"
  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}"
  ; --connect: formulario real (IP, usuario SSH, nombre + "Buscar telefonos en esta red") para
  ; conectarse a un telefono remoto -- sin este acceso directo, nadie descubre el flag sin leer
  ; codigo. Sin tildes a proposito, mismo criterio que el resto de este archivo: sin
  ; "Unicode true" NSIS lee el .nsi en la codepage ANSI del sistema, no UTF-8 -- un caracter
  ; acentuado en el NOMBRE del acceso directo (a diferencia de un comentario) terminaria
  ; mostrando texto roto al usuario final (mismo bug ya confirmado y corregido en
  ; kairos-dashboard, ver main.go: dataHTML()).
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\Conectar a un telefono.lnk" "$INSTDIR\${APP_EXE}" "--connect"
  ; --discover y --usb-grant: mismo motivo que --connect arriba -- regla
  ; kairos-gateway-installer-siempre-actualizado.md exige que cada capacidad nueva tenga su
  ; entrada real en el instalador, no solo en el codigo.
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\Descubrir telefonos.lnk" "$INSTDIR\${APP_EXE}" "--discover"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\Activar por USB.lnk" "$INSTDIR\${APP_EXE}" "--usb-grant"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\Desinstalar ${APP_NAME}.lnk" "$INSTDIR\uninstall.exe"

  WriteRegStr HKCU "${APP_UNINST_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "${APP_UNINST_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "${APP_UNINST_KEY}" "Publisher" "${APP_PUBLISHER}"
  WriteRegStr HKCU "${APP_UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKCU "${APP_UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKCU "${APP_UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${APP_UNINST_KEY}" "NoRepair" 1

  WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd

Section "Uninstall"
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\Conectar a un telefono.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\Descubrir telefonos.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\Activar por USB.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\Desinstalar ${APP_NAME}.lnk"
  RMDir "$SMPROGRAMS\${APP_NAME}"
  Delete "$DESKTOP\${APP_NAME}.lnk"
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "KairosGateway"
  DeleteRegKey HKCU "${APP_UNINST_KEY}"
  ; $INSTDIR (%LOCALAPPDATA%\KairosGateway) puede seguir teniendo la carpeta de estado real del
  ; gateway (tokens, auditoria, el kairos-gateway.exe extraido por extractEmbeddedGateway) si el
  ; usuario corrio el binario con --dir apuntando ahi -- no se borra nada de datos del usuario
  ; sin preguntar, solo el binario y el uninstaller de arriba.
SectionEnd
