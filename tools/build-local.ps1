<#
build-local.ps1 — Compilar Kairos APK en local (Windows), replicando build-app.yml/.gitlab-ci.yml

Uso:
  .\tools\build-local.ps1              # build liviano (assembleDebug, sin rootfs embebido)
  .\tools\build-local.ps1 -SkipVulkan  # saltar el paso de Vulkan-Headers/SPIRV-Headers (ya clonados)

Requisitos verificados en esta maquina (2026-08-15): Android Studio con SDK, NDK
27.2.12479018 + 29.0.14206865, cmake 3.22.1 (trae ninja), git, cmake standalone.
No requiere WSL — todos los pasos del workflow son Gradle/CMake/git normales,
sin nada exclusivo de Linux.
#>

param(
    [switch]$SkipVulkan
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ParentDir = Split-Path -Parent $ProjectRoot

Write-Host "== Kairos build local ==" -ForegroundColor Cyan
Write-Host "Proyecto: $ProjectRoot"

# 1. JAVA_HOME — usa el JBR embebido en Android Studio si no hay uno mejor ya seteado
$StudioJbr = "C:\Program Files\Android\Android Studio\jbr"
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    if (Test-Path "$StudioJbr\bin\java.exe") {
        $env:JAVA_HOME = $StudioJbr
        Write-Host "JAVA_HOME -> $StudioJbr (JBR de Android Studio)"
    } else {
        throw "No se encontro un JDK valido. Instala Android Studio o setea JAVA_HOME a un JDK 17+."
    }
}

# 2. ANDROID_HOME / ANDROID_SDK_ROOT
if (-not $env:ANDROID_HOME) {
    $DefaultSdk = "$env:LOCALAPPDATA\Android\Sdk"
    if (Test-Path $DefaultSdk) {
        $env:ANDROID_HOME = $DefaultSdk
    } else {
        throw "ANDROID_HOME no seteado y no se encontro SDK en $DefaultSdk. Instala el SDK desde Android Studio."
    }
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Write-Host "ANDROID_HOME -> $env:ANDROID_HOME"

# 3. local.properties — asegurar que sdk.dir apunte al SDK real de esta maquina
$LocalProps = Join-Path $ProjectRoot "local.properties"
$SdkDirLine = "sdk.dir=" + ($env:ANDROID_HOME -replace '\\', '/')
Set-Content -Path $LocalProps -Value $SdkDirLine -Encoding ASCII
Write-Host "local.properties -> $SdkDirLine"

# 4. NDK requerido por llama-engine (ademas del NDK 29.x del resto del proyecto)
$RequiredNdk = "27.2.12479018"
$NdkPath = Join-Path $env:ANDROID_HOME "ndk\$RequiredNdk"
if (-not (Test-Path $NdkPath)) {
    Write-Host "NDK $RequiredNdk no encontrado, instalando via sdkmanager..." -ForegroundColor Yellow
    $Sdkmanager = Join-Path $env:ANDROID_HOME "cmdline-tools\latest\bin\sdkmanager.bat"
    if (-not (Test-Path $Sdkmanager)) {
        throw "No hay sdkmanager en $Sdkmanager y falta NDK $RequiredNdk. Instala 'NDK (Side by side) $RequiredNdk' desde Android Studio > SDK Manager."
    }
    & $Sdkmanager "ndk;$RequiredNdk"
} else {
    Write-Host "NDK $RequiredNdk OK"
}

# 5. Vulkan-Headers / SPIRV-Headers como hermanos del checkout (rootProject.file("../..."))
if (-not $SkipVulkan) {
    $VulkanHeaders = Join-Path $ParentDir "Vulkan-Headers"
    $SpirvHeaders = Join-Path $ParentDir "SPIRV-Headers"

    if (-not (Test-Path $VulkanHeaders)) {
        Write-Host "Clonando Vulkan-Headers en $VulkanHeaders ..." -ForegroundColor Yellow
        git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git $VulkanHeaders
    } else {
        Write-Host "Vulkan-Headers ya existe en $VulkanHeaders"
    }

    if (-not (Test-Path $SpirvHeaders)) {
        Write-Host "Clonando SPIRV-Headers en $SpirvHeaders ..." -ForegroundColor Yellow
        git clone --depth 1 https://github.com/KhronosGroup/SPIRV-Headers.git $SpirvHeaders
    } else {
        Write-Host "SPIRV-Headers ya existe en $SpirvHeaders"
    }

    $SpirvInstall = Join-Path $SpirvHeaders "install"
    if (-not (Test-Path $SpirvInstall)) {
        Write-Host "Configurando + instalando SPIRV-Headers (cmake)..." -ForegroundColor Yellow
        cmake -S $SpirvHeaders -B "$SpirvHeaders\build" -DCMAKE_INSTALL_PREFIX="$SpirvInstall"
        cmake --install "$SpirvHeaders\build"
    } else {
        Write-Host "SPIRV-Headers ya instalado en $SpirvInstall"
    }
} else {
    Write-Host "Saltando paso de Vulkan-Headers/SPIRV-Headers (-SkipVulkan)"
}

# 6. Compilador C++ de HOST para llama.cpp/tools/ui (llama-ui-embed) — sin esto,
# find_program(g++/clang++) de ese CMakeLists.txt de terceros encuentra por error el
# clang++ del NDK de Android (solo sabe compilar para Android) y falla con
# "'inttypes.h' file not found". w64devkit es un MinGW g++ portable (sin instalador,
# sin tocar el registro) — si esta ahi, se antepone al PATH para que find_program lo
# encuentre primero. Ver docs/humano128.md.
$W64Devkit = Join-Path $ProjectRoot ".build-tools\w64devkit\bin"
if (Test-Path "$W64Devkit\g++.exe") {
    $env:PATH = "$W64Devkit;$env:PATH"
    Write-Host "Compilador de host -> $W64Devkit\g++.exe (w64devkit portable)"
} else {
    Write-Host "w64devkit no encontrado en $W64Devkit -- si el build falla en llama-engine con 'inttypes.h file not found', instalarlo ahi (ver docs/arquitectura/BUILD.md)." -ForegroundColor Yellow
}

# 7. Build real — mismo comando que build-app.yml / .gitlab-ci.yml
$env:TERMUX_PACKAGE_VARIANT = "apt-android-7"
$env:TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = "0"

Push-Location $ProjectRoot
try {
    Write-Host "== gradlew downloadBootstraps assembleDebug ==" -ForegroundColor Cyan
    & "$ProjectRoot\gradlew.bat" downloadBootstraps assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle fallo con exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$ApkDir = Join-Path $ProjectRoot "app\build\outputs\apk\debug"
Write-Host "== BUILD OK ==" -ForegroundColor Green
Write-Host "APK en: $ApkDir"
Get-ChildItem $ApkDir -Filter "*.apk" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host " - $($_.Name)" }
