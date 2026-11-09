//go:build !windows

package main

import "log/slog"

// Sin bandeja del sistema fuera de Windows (Android no la necesita — Kotlin ya controla su
// propio ciclo de vida; Linux/macOS de escritorio quedan para cuando haya señal real de que se
// usan). Ver tray_windows.go para la implementación real.
func startSystemTray(_ *slog.Logger) {}
func stopSystemTray()                {}
