# Compila kairos-dashboard.exe (con el gateway EMBEBIDO, ver build-gateway-windows.ps1) y lo
# empaqueta en un instalador real (Kairos-Gateway-Setup.exe, NSIS) que lo copia, crea accesos
# directos y se desinstala desde "Agregar o quitar programas" -- pedido explicito del usuario
#: "que no solo sea portable para windows si no tambien instaaleble [...]
# que instale todo lo que necesita todo en un .exe" + "el gateway de windows no es para abrir el
# navegador [...] debe [tener] una interfaz dashboard [...] sin navegador" + "solo deben ser dos
# .exe, uno portable y el otro instalable".
#
# Corrección 2026-10-30: antes este script compilaba kairos-gateway.exe Y kairos-dashboard.exe
# como DOS archivos separados, e instalaba ambos (o caía a "solo gateway + navegador" si no había
# MinGW). Ahora kairos-dashboard.exe lleva el gateway adentro (//go:embed) -- un solo binario
# real para instalar, el instalador final queda con exactamente DOS .exe en total en esta carpeta
# de salida: Kairos-Gateway-Setup.exe (este) y kairos-dashboard.exe (el portable, ver
# build-gateway-windows.ps1) -- ya no hay un tercer kairos-gateway.exe suelto.
#
# Requiere NSIS instalado (makensis.exe en el PATH, o winget install NSIS.NSIS) y el MinGW de
# .build-tools/w64devkit SOLO en esta PC para compilar -- el usuario final que recibe el .exe
# resultante no necesita nada mas instalado (salvo el WebView2 Runtime, preinstalado de fábrica
# en Windows 10 1803+/11 junto con Edge).
#
# Uso: powershell -ExecutionPolicy Bypass -File tools/build-gateway-installer-windows.ps1

$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\.."

# Paso 1: compilar el portable real (kairos-dashboard.exe con el gateway embebido adentro --
# reusa el script existente, no duplica la lógica de build).
& "$root\tools\build-gateway-windows.ps1"

$dashboardExe = "$root\build\gateway-windows\kairos-dashboard.exe"
if (-not (Test-Path $dashboardExe)) { throw "No se encontro $dashboardExe tras compilar" }

$makensis = Get-Command makensis -ErrorAction SilentlyContinue
if (-not $makensis) {
    # $env:LOCALAPPDATA\Programs\NSIS es donde "winget install NSIS.NSIS" instala por usuario, sin admin.
    $candidates = @(
        "$env:ProgramFiles\NSIS\makensis.exe",
        "${env:ProgramFiles(x86)}\NSIS\makensis.exe",
        "$env:LOCALAPPDATA\Programs\NSIS\makensis.exe"
    )
    $found = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($found) { $makensis = Get-Item $found } else {
        throw "makensis no esta instalado. Instalalo con: winget install NSIS.NSIS -- o descargalo de https://nsis.sourceforge.io/"
    }
}

$version = (git -C $root rev-parse --short HEAD 2>$null)
if (-not $version) { $version = "dev" }

$outDir = "$root\build\gateway-windows"
$outInstaller = "$outDir\Kairos-Gateway-Setup.exe"
$nsiScript = "$root\tools\installer\kairos-gateway-windows.nsi"

& $makensis.Source `
    "/DKAIROS_GATEWAY_VERSION=$version" `
    "/DOUT_FILE=$outInstaller" `
    "/DDASHBOARD_EXE_PATH=$dashboardExe" `
    $nsiScript

if ($LASTEXITCODE -ne 0) { throw "makensis fallo (codigo $LASTEXITCODE)" }

Write-Host "Instalador compilado: $outInstaller"
Get-Item $outInstaller | Select-Object Name, Length, LastWriteTime
