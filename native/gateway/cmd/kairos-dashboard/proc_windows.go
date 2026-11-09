//go:build windows

package main

import "syscall"

// hideConsoleAttr — pedido real del usuario (2026-10-30): "el app de karios
// de escritorio no debe abrir cmd o [lo] cual que el usuario no la vea, ocupa espacio". Sin esto,
// cualquier proceso hijo de consola (kairos-gateway.exe extraído, ssh.exe del túnel) abre su
// propia ventana de consola negra al arrancar — HideWindow la suprime. Este binario
// (kairos-dashboard.exe) en sí deja de mostrar SU PROPIA consola por otra vía (compilado con
// -ldflags "-H=windowsgui", ver tools/build-gateway-windows.ps1), no por esto — esto es solo
// para los hijos que arranca.
func hideConsoleAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{HideWindow: true}
}
