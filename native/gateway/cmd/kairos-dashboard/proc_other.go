//go:build !windows

package main

import "syscall"

// hideConsoleAttr — ver KDoc de proc_windows.go. No hay ventana de consola que ocultar fuera de
// Windows (los procesos hijos de una app gráfica en Linux/macOS no abren una terminal visible
// por su cuenta); nil deja el SysProcAttr por defecto.
func hideConsoleAttr() *syscall.SysProcAttr {
	return nil
}
