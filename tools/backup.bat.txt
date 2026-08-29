@echo off
title Backup Kairos (excluyendo nodes y node_modules)
cd /d "%~dp0"

set "SEVENZIP=C:\Program Files\7-Zip\7z.exe"
if not exist "%SEVENZIP%" (
    echo [ERROR] No se encontro 7-Zip en "%SEVENZIP%"
    pause
    exit /b 1
)

set "ORIGEN=C:\Users\HP\Desktop\kairos"
set "DEST_DIR=C:\Users\HP\Desktop\Backups-Kairos"
if not exist "%DEST_DIR%" mkdir "%DEST_DIR%"

set "TS=%DATE:~-4%%DATE:~3,2%%DATE:~0,2%_%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%"
set "TS=%TS: =0%"
set "DEST=%DEST_DIR%\kairos_backup_%TS%.7z"

echo ============================================
echo  Backup de kairos (sin compresion, rapido)
echo  Origen:  %ORIGEN%
echo  Destino: %DEST%
echo  Excluye: nodes y node_modules
echo ============================================

"%SEVENZIP%" a -mx=0 ^
  -xr!nodes ^
  -xr!node_modules ^
  "%DEST%" "%ORIGEN%\*"

if errorlevel 1 (
    echo [ERROR] El backup fallo
    pause
    exit /b 1
)

echo.
echo [OK] Backup creado: %DEST%
for %%F in ("%DEST%") do echo Tamano: %%~zF bytes
echo.
pause