//go:build windows

package main

import _ "embed"

// embeddedAdb* lleva el binario REAL de `adb.exe` (Google, AOSP platform-tools, Apache-2.0) +
// sus 2 DLL necesarias en Windows — "mini adb": solo estos 3 archivos (~8MB), no el SDK de
// Android completo (~1.5GB). Mismo patrón placeholder-reemplazado-por-build-script que
// embeddedGateway (ver embed_windows.go) — los 3 archivos de embed/ son PLACEHOLDERS de texto en
// el repo; tools/build-gateway-windows.ps1 descarga el platform-tools real de
// dl.google.com/android/repository/ y los sobreescribe antes de compilar. Ver
// native/gateway/internal/adb/adb.go para el porqué (Alcance A, docs/arquitectura/
// PLAN_ADB_USB_GATEWAY_2026-10-31.md) y el crédito real en.
//
//go:embed embed/adb.exe
var embeddedAdbBin []byte

//go:embed embed/AdbWinApi.dll
var embeddedAdbWinApiDll []byte

//go:embed embed/AdbWinUsbApi.dll
var embeddedAdbWinUsbApiDll []byte

const embeddedAdbName = "adb.exe"
