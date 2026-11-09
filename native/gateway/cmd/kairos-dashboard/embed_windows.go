//go:build windows

package main

import _ "embed"

// embeddedGateway lleva los bytes REALES de kairos-gateway.exe adentro de kairos-dashboard.exe —
// pedido explícito del usuario (2026-10-30): "solo deben ser dos.exe, uno
// portable y el otro instalable" — antes de esto, el "portable" era en realidad una CARPETA con
// dos .exe sueltos (kairos-gateway.exe + kairos-dashboard.exe), lo cual el usuario probó y
// confirmó que seguía igual. Con el embed, kairos-dashboard.exe es un único archivo
// autocontenido: no necesita ningún otro binario "al lado" para arrancar el gateway (ver
// startGateway, que ahora extrae estos bytes a la carpeta de estado en vez de buscar un archivo
// vecino). kairos-gateway.exe sigue existiendo como binario standalone, cross-compilable, para
// quien quiera correrlo solo en un servidor headless (sin WebView2) — simplemente ya no viaja
// SUELTO junto al portable/instalador de escritorio.
//
// El archivo real en embed/kairos-gateway.exe es un PLACEHOLDER en el repo (texto, no un binario
// válido) — los scripts de build (tools/build-gateway-windows.ps1,
// tools/build-gateway-installer-windows.ps1) lo SOBRESCRIBEN con el kairos-gateway.exe recién
// compilado antes de compilar este paquete. Compilar kairos-dashboard a mano sin pasar por esos
// scripts (ej. "go build ./cmd/kairos-dashboard" directo) produce un binario que falla al
// arrancar el gateway embebido (placeholder, no un .exe real) — error esperado, no un bug.
//
//go:embed embed/kairos-gateway.exe
var embeddedGateway []byte

const embeddedGatewayName = "kairos-gateway.exe"
