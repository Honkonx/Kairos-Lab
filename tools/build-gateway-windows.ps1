# Compila el binario PORTABLE de Kairos para Windows de escritorio: UN solo kairos-dashboard.exe.
#
# Primer paso real del "gateway para Windows" (ver meta 12). Es el mismo
# gateway (native/gateway, Go puro de stdlib, sin CGO) que ya corre en el telefono -- mismo
# router/auth/acciones/panel web.
#
# Corrección 2026-10-30 (reporte real del usuario,: "ya que probe los que
# estan aqui build\gateway-windows y siguen igual [...] solo deben ser dos .exe, uno portable y
# el otro instalable"): hasta ahora, "portable" era en realidad una CARPETA con DOS .exe sueltos
# (kairos-gateway.exe + kairos-dashboard.exe). Ahora kairos-dashboard.exe lleva el gateway
# EMBEBIDO (ver cmd/kairos-dashboard/embed_windows.go, //go:embed) y lo extrae solo a su propia
# carpeta de estado al arrancar (cmd/kairos-dashboard/main.go: extractEmbeddedGateway) -- el
# portable es, de verdad, un solo archivo.
#
# kairos-gateway.exe SIGUE existiendo como binario standalone (headless, sin WebView2, cross-
# compilable, sin CGO) para quien quiera correrlo solo en un servidor sin interfaz -- pero ya NO
# viaja suelto en esta carpeta de salida: se compila a una ruta TEMPORAL solo para copiarlo adentro
# de cmd/kairos-dashboard/embed/ antes de compilar el dashboard, y se borra después. Quien
# necesite kairos-gateway.exe suelto puede extraerlo corriendo kairos-dashboard.exe una vez (queda
# en su carpeta de estado, ver defaultGatewayDir() en main.go) y copiarlo de ahí.
#
# Requiere MinGW (.build-tools/w64devkit) para compilar kairos-dashboard.exe (CGO, webview_go) --
# SOLO en esta PC, el binario resultante no necesita nada mas instalado en el usuario final
# (salvo el WebView2 Runtime, de fabrica en Windows 10 1803+/11 junto a Edge).
#
# "-H=windowsgui" en AMBOS binarios (pedido real del usuario,: "no debe abrir
# cmd [...] que el usuario no la vea, ocupa espacio") -- marca el .exe como subsistema GUI, asi
# Windows no le crea una consola automatica al lanzarlo (doble-click o acceso directo). El
# gateway embebido ademas se arranca con HideWindow=true (ver cmd/kairos-dashboard/
# proc_windows.go) como segunda capa -- ninguno de los dos muestra consola nunca.
#
# Uso: powershell -ExecutionPolicy Bypass -File tools/build-gateway-windows.ps1 [-Test]

param([switch]$Test)

$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\.."
Set-Location "$root\native\gateway"

if ($Test) {
    go vet ./...
    go test -count=1 ./...
}

$version = (git -C $root rev-parse --short HEAD 2>$null)
if (-not $version) { $version = "dev" }

$outDir = "$root\build\gateway-windows"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$mingwGcc = "$root\.build-tools\w64devkit\bin\gcc.exe"
if (-not (Test-Path $mingwGcc)) {
    throw "No se encontro MinGW en $mingwGcc -- kairos-dashboard.exe necesita CGO para compilarse (webview_go). Sin esto no hay portable que generar."
}

# Paso 1: compilar kairos-gateway.exe a una ruta TEMPORAL (nunca a $outDir -- no debe quedar
# suelto junto al portable final, ver cabecera).
$tmpDir = New-Item -ItemType Directory -Force -Path "$root\build\_gateway-embed-tmp"
$tmpGateway = "$tmpDir\kairos-gateway.exe"
$env:GOOS = "windows"
$env:GOARCH = "amd64"
$env:CGO_ENABLED = "0"
go build -trimpath -ldflags "-s -w -H=windowsgui -X main.version=$version" -o $tmpGateway .\cmd\kairos-gateway
Remove-Item Env:\GOOS, Env:\GOARCH, Env:\CGO_ENABLED
if ($LASTEXITCODE -ne 0) { throw "go build de kairos-gateway fallo" }

# Paso 2: copiarlo adentro del paquete de kairos-dashboard para que //go:embed lo levante.
Copy-Item -Force $tmpGateway "$root\native\gateway\cmd\kairos-dashboard\embed\kairos-gateway.exe"
Remove-Item -Recurse -Force $tmpDir

# Paso 2.5: "mini adb" real (Google, AOSP platform-tools, Apache-2.0) -- Alcance A del plan de
# ADB por USB (docs/arquitectura/PLAN_ADB_USB_GATEWAY_2026-10-31.md). Se descarga fresco cada vez
# (siempre la version oficial mas reciente, nunca una copia vieja en el repo) y reemplaza los
# placeholders de texto de cmd/kairos-dashboard/embed/ antes de compilar -- mismo patron que el
# Paso 2 de arriba con kairos-gateway.exe.
$adbTmpDir = New-Item -ItemType Directory -Force -Path "$root\build\_adb-embed-tmp"
$adbZip = "$adbTmpDir\platform-tools-windows.zip"
Invoke-WebRequest -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $adbZip -UseBasicParsing
Expand-Archive -Force -Path $adbZip -DestinationPath $adbTmpDir
$embedDir = "$root\native\gateway\cmd\kairos-dashboard\embed"
Copy-Item -Force "$adbTmpDir\platform-tools\adb.exe" "$embedDir\adb.exe"
Copy-Item -Force "$adbTmpDir\platform-tools\AdbWinApi.dll" "$embedDir\AdbWinApi.dll"
Copy-Item -Force "$adbTmpDir\platform-tools\AdbWinUsbApi.dll" "$embedDir\AdbWinUsbApi.dll"
Remove-Item -Recurse -Force $adbTmpDir
Write-Host "mini adb (Google platform-tools, Apache-2.0) embebido -- ver para credito."

# Paso 3: compilar kairos-dashboard.exe -- UNICO archivo portable real, con el gateway adentro.
$dashboardOut = "$outDir\kairos-dashboard.exe"
$env:CC = $mingwGcc
$env:PATH = "$root\.build-tools\w64devkit\bin;$env:PATH"
$env:CGO_ENABLED = "1"
try {
    go build -trimpath -ldflags "-H=windowsgui" -o $dashboardOut .\cmd\kairos-dashboard
    if ($LASTEXITCODE -ne 0) { throw "go build de kairos-dashboard fallo" }
} finally {
    Remove-Item Env:\CC, Env:\CGO_ENABLED -ErrorAction SilentlyContinue
}

Write-Host "Portable compilado (un solo archivo): $dashboardOut"
Get-Item $dashboardOut | Select-Object Name, Length, LastWriteTime
