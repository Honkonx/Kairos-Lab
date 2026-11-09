//go:build windows

package main

// Ícono de bandeja del sistema para Windows (2026-10-16, ver meta 12: "mas alla
// de un binario CLI") — bindings crudos a user32.dll/shell32.dll vía syscall (librería estándar de
// Go, nunca golang.org/x/sys ni ninguna dependencia de terceros — sigue el principio "solo
// librería estándar" que ya declara main.go). Activado con -tray (apagado por defecto: es la
// primera ronda de este código de bajo nivel, se mantiene opt-in hasta confirmarlo más veces en
// dispositivos reales de usuarios).
//
// Las estructuras de abajo (notifyIconData, msg, wndClassEx) están definidas como structs de Go
// con los MISMOS tipos y el MISMO orden de campos que sus equivalentes de Win32
// (NOTIFYICONDATAW/MSG/WNDCLASSEXW de shellapi.h/winuser.h) — el compilador de Go aplica las
// mismas reglas de alineación que un compilador de C en la misma arquitectura para structs sin
// bitfields, así que `unsafe.Sizeof()` da el `cbSize` real sin tener que calcular offsets de
// bytes a mano (fuente real de bugs en este tipo de código).

import (
	"log/slog"
	"runtime"
	"sync"
	"syscall"
	"unsafe"
)

var (
	user32  = syscall.NewLazyDLL("user32.dll")
	shell32 = syscall.NewLazyDLL("shell32.dll")

	procRegisterClassExW = user32.NewProc("RegisterClassExW")
	procCreateWindowExW  = user32.NewProc("CreateWindowExW")
	procDefWindowProcW   = user32.NewProc("DefWindowProcW")
	procGetMessageW      = user32.NewProc("GetMessageW")
	procTranslateMessage = user32.NewProc("TranslateMessage")
	procDispatchMessageW = user32.NewProc("DispatchMessageW")
	procPostQuitMessage  = user32.NewProc("PostQuitMessage")
	procLoadIconW        = user32.NewProc("LoadIconW")
	procDestroyWindow    = user32.NewProc("DestroyWindow")
	procShellNotifyIconW = shell32.NewProc("Shell_NotifyIconW")
)

const (
	wsOverlappedWindow = 0x00CF0000
	wmDestroy          = 0x0002
	wmClose            = 0x0010
	nimAdd             = 0x00000000
	nimDelete          = 0x00000002
	nifMessage         = 0x00000001
	nifIcon            = 0x00000002
	nifTip             = 0x00000004
	idiApplication     = 32512 // ícono genérico del sistema — no se agrega ningún .ico propio todavía
)

// MSG real de Win32 — ver cabecera del archivo.
type msg struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	ptX     int32
	ptY     int32
}

// NOTIFYICONDATAW (versión V1, la más simple y estable — szTip de 64 caracteres, sin los campos
// agregados en versiones posteriores de Windows que esta ronda no necesita).
type notifyIconData struct {
	cbSize           uint32
	hWnd             uintptr
	uID              uint32
	uFlags           uint32
	uCallbackMessage uint32
	hIcon            uintptr
	szTip            [64]uint16
}

// WNDCLASSEXW real de Win32 — ver cabecera del archivo.
type wndClassEx struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     uintptr
	hIcon         uintptr
	hCursor       uintptr
	hbrBackground uintptr
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       uintptr
}

var trayStop = make(chan struct{})
var trayOnce sync.Once

// startSystemTray registra una clase de ventana invisible y agrega un ícono de bandeja —
// corre en su propia goroutine, fijada a un solo hilo del sistema operativo
// (runtime.LockOSThread()) porque el message loop de Win32 exige que todas las llamadas
// relacionadas con la MISMA ventana vengan del MISMO hilo que la creó.
func startSystemTray(logger *slog.Logger) {
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		if err := runSystemTray(logger); err != nil {
			logger.Warn("el ícono de bandeja no se pudo iniciar (el gateway sigue funcionando igual, solo sin ícono)", "error", err)
		}
	}()
}

func stopSystemTray() {
	trayOnce.Do(func() { close(trayStop) })
}

func runSystemTray(logger *slog.Logger) error {
	className, err := syscall.UTF16PtrFromString("KairosGatewayTrayWindow")
	if err != nil {
		return err
	}
	wndProc := syscall.NewCallback(func(hwnd, uMsg, wParam, lParam uintptr) uintptr {
		if uint32(uMsg) == wmDestroy {
			ret, _, _ := procPostQuitMessage.Call(0)
			return ret
		}
		ret, _, _ := procDefWindowProcW.Call(hwnd, uMsg, wParam, lParam)
		return ret
	})

	wc := wndClassEx{
		style:         0,
		lpfnWndProc:   wndProc,
		hInstance:     0,
		lpszClassName: className,
	}
	wc.cbSize = uint32(unsafe.Sizeof(wc))
	if ret, _, _ := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))); ret == 0 {
		return syscall.GetLastError()
	}

	title, err := syscall.UTF16PtrFromString("Kairos Gateway")
	if err != nil {
		return err
	}
	hwnd, _, _ := procCreateWindowExW.Call(
		0, uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(title)),
		uintptr(wsOverlappedWindow), 0, 0, 0, 0, 0, 0, 0, 0,
	)
	if hwnd == 0 {
		return syscall.GetLastError()
	}
	defer procDestroyWindow.Call(hwnd)

	hIcon, _, _ := procLoadIconW.Call(0, uintptr(idiApplication))

	var nid notifyIconData
	nid.cbSize = uint32(unsafe.Sizeof(nid))
	nid.hWnd = hwnd
	nid.uID = 1
	nid.uFlags = nifIcon | nifTip | nifMessage
	nid.uCallbackMessage = wmClose // reusa wmClose como mensaje de callback del ícono, ver cabecera: v1 mínima, sin menú todavía.
	nid.hIcon = hIcon
	tip, _ := syscall.UTF16FromString("Kairos Gateway activo")
	copy(nid.szTip[:], tip)

	if ret, _, _ := procShellNotifyIconW.Call(uintptr(nimAdd), uintptr(unsafe.Pointer(&nid))); ret == 0 {
		return syscall.GetLastError()
	}
	logger.Info("ícono de bandeja del sistema agregado")
	defer procShellNotifyIconW.Call(uintptr(nimDelete), uintptr(unsafe.Pointer(&nid)))

	go func() {
		<-trayStop
		procDestroyWindow.Call(hwnd)
	}()

	var m msg
	for {
		ret, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(ret) <= 0 {
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
	}
	return nil
}
