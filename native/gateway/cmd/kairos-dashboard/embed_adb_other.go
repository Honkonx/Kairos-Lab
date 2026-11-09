//go:build !windows

package main

import _ "embed"

// Ver KDoc de embed_adb_windows.go — mismo mecanismo, un solo binario (Linux no necesita las DLL
// de USB de Windows). Placeholder reemplazado por el adb real de platform-tools-linux antes de
// compilar (ver tools/install-gateway-linux.sh).
//
//go:embed embed/adb-linux
var embeddedAdbBin []byte

const embeddedAdbName = "adb"

// En plataformas no-Windows no hay DLL que extraer — variables vacías para que el código de
// extracción compartido en main.go no necesite ramas por build tag.
var embeddedAdbWinApiDll []byte
var embeddedAdbWinUsbApiDll []byte
