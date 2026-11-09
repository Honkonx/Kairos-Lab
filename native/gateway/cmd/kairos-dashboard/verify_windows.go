//go:build windows

package main

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

// Confirma que quien escucha en el puerto del dashboard es de verdad kairos-gateway.exe — nunca
// otro programa que haya ocupado el puerto antes (hallazgo de revisión automática, 2026-10-26,
//: "port squatting / credential phishing" — sin esto, abrir la ventana del
// dashboard podría terminar mostrando una página ajena, y el usuario pegaría su token ahí sin
// saberlo). Bindings crudos a iphlpapi.dll/kernel32.dll vía `syscall` — mismo patrón ya usado en
// cmd/kairos-gateway/tray_windows.go ("solo librería estándar, nunca golang.org/x/sys").
//
// Esta es la verificación de RESPALDO, para cuando el dashboard NO arrancó el gateway él mismo
// (ya estaba corriendo) — ahí no hay ningún secreto que desafiar (ver verifyByChallenge en
// main.go, el camino fuerte real, mismo mecanismo HMAC que GatewayAdminClient.kt). Acotado
// contra el hallazgo de revisión automática "parser/validator differential" (2026-10-26): solo
// cuentan las filas en estado LISTEN (2) cuyo LocalAddr coincide con el host pedido (o el
// comodín 0.0.0.0) — una fila de una conexión saliente que por casualidad usó ese número de
// puerto como efímero NO debe confundirse con el que realmente escucha.
var (
	iphlpapi                       = syscall.NewLazyDLL("iphlpapi.dll")
	procGetExtendedTCPTable        = iphlpapi.NewProc("GetExtendedTcpTable")
	kernel32dll                    = syscall.NewLazyDLL("kernel32.dll")
	procOpenProcess                = kernel32dll.NewProc("OpenProcess")
	procQueryFullProcessImageNameW = kernel32dll.NewProc("QueryFullProcessImageNameW")
	procCloseHandle                = kernel32dll.NewProc("CloseHandle")
	user32dll                      = syscall.NewLazyDLL("user32.dll")
	procMessageBoxW                = user32dll.NewProc("MessageBoxW")
)

// mibTCPRowOwnerPID — mismo layout que MIB_TCPROW_OWNER_PID (iphlpapi.h): 6 DWORD consecutivos,
// sin padding en una arquitectura de 32 bits por campo, confirmado por unsafe.Sizeof() igual que
// ya documenta tray_windows.go para sus propios structs Win32.
type mibTCPRowOwnerPID struct {
	State      uint32
	LocalAddr  uint32
	LocalPort  uint32
	RemoteAddr uint32
	RemotePort uint32
	OwningPID  uint32
}

const (
	afINET                         = 2
	tcpStateListen                 = 2
	tcpTableOwnerPIDAll            = 5
	errInsufficientBuffer          = 122
	processQueryLimitedInformation = 0x1000
)

// pidListeningOn busca, en la tabla TCP real de Windows, qué PID tiene en estado LISTEN el host:
// puerto pedido — 0 si ninguno. Dos gotchas reales de esta API, no bugs:
//   - dwLocalPort llega en orden de red (los 2 bytes del puerto están invertidos en el DWORD).
//   - dwLocalAddr ya está en el orden de bytes "natural" (los 4 bytes en memoria, leídos tal
//     cual, dan los octetos de la IP en el orden correcto) — distinto del puerto.
//
// Devuelve error si encuentra MÁS DE UN PID escuchando ahí (no debería pasar nunca en una tabla
// TCP sana — si pasa, es señal de algo raro, mejor fallar que asumir cuál de los dos es el real).
func pidListeningOn(host net.IP, port uint16) (uint32, error) {
	v4 := host.To4()
	if v4 == nil {
		return 0, fmt.Errorf("solo se soporta IPv4 (recibido %v)", host)
	}
	wantAddr := *(*uint32)(unsafe.Pointer(&v4[0]))

	var size uint32
	r, _, _ := procGetExtendedTCPTable.Call(0, uintptr(unsafe.Pointer(&size)), 0, afINET, tcpTableOwnerPIDAll, 0)
	if r != 0 && r != errInsufficientBuffer {
		return 0, fmt.Errorf("GetExtendedTcpTable (tamaño) = %d", r)
	}
	if size == 0 {
		return 0, nil
	}
	buf := make([]byte, size)
	r, _, _ = procGetExtendedTCPTable.Call(uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)), 0, afINET, tcpTableOwnerPIDAll, 0)
	if r != 0 {
		return 0, fmt.Errorf("GetExtendedTcpTable = %d", r)
	}
	numEntries := *(*uint32)(unsafe.Pointer(&buf[0]))
	rowSize := unsafe.Sizeof(mibTCPRowOwnerPID{})
	var found uint32
	for i := uint32(0); i < numEntries; i++ {
		off := 4 + uintptr(i)*rowSize
		if off+rowSize > uintptr(len(buf)) {
			break
		}
		row := (*mibTCPRowOwnerPID)(unsafe.Pointer(&buf[off]))
		if row.State != tcpStateListen {
			continue
		}
		localPort := uint16((row.LocalPort&0xFF)<<8 | (row.LocalPort&0xFF00)>>8)
		if localPort != port {
			continue
		}
		if row.LocalAddr != wantAddr && row.LocalAddr != 0 { // 0 = 0.0.0.0, escucha en todas las interfaces
			continue
		}
		if found != 0 && found != row.OwningPID {
			return 0, fmt.Errorf("más de un proceso dice escuchar en %s:%d al mismo tiempo (PIDs %d y %d) — no se puede confiar en cuál es el real", host, port, found, row.OwningPID)
		}
		found = row.OwningPID
	}
	return found, nil
}

// processImagePath — ruta completa del ejecutable de un proceso vivo.
// PROCESS_QUERY_LIMITED_INFORMATION alcanza, sin pedir ningún privilegio especial.
func processImagePath(pid uint32) (string, error) {
	h, _, _ := procOpenProcess.Call(processQueryLimitedInformation, 0, uintptr(pid))
	if h == 0 {
		return "", fmt.Errorf("no se pudo abrir el proceso %d", pid)
	}
	defer procCloseHandle.Call(h)
	buf := make([]uint16, 1024)
	size := uint32(len(buf))
	r, _, _ := procQueryFullProcessImageNameW.Call(h, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)))
	if r == 0 {
		return "", fmt.Errorf("QueryFullProcessImageNameW falló para el proceso %d", pid)
	}
	return syscall.UTF16ToString(buf[:size]), nil
}

// verifyGatewayProcess confirma que [host]:[port] lo tiene de verdad kairos-gateway.exe — en
// alguna de las dos ubicaciones legítimas, nunca en cualquier otro lado. Ver el comentario de
// cabecera: este es el camino DÉBIL (sin secreto que desafiar).
//
// Desde que kairos-dashboard.exe lleva el gateway EMBEBIDO (ver embed_windows.go, pedido del
// usuario "solo deben ser dos .exe"), el camino fuerte normal lo extrae a la carpeta de estado
// ([defaultGatewayDir]), no "al lado" del propio ejecutable — ese dejó de ser el único lugar
// válido. Se acepta cualquiera de los dos: junto a este binario (uso headless/standalone de
// kairos-gateway.exe solo, sin dashboard) o en la carpeta de estado por defecto (el caso real
// más común hoy: un dashboard anterior ya lo extrajo ahí y lo dejó corriendo).
func verifyGatewayProcess(host net.IP, port uint16) error {
	pid, err := pidListeningOn(host, port)
	if err != nil {
		return fmt.Errorf("no se pudo identificar qué proceso escucha en %s:%d: %w", host, port, err)
	}
	if pid == 0 {
		return fmt.Errorf("nada escucha en %s:%d en estado LISTEN todavía", host, port)
	}
	path, err := processImagePath(pid)
	if err != nil {
		return fmt.Errorf("%s:%d lo tiene el proceso %d, pero no se pudo confirmar qué programa es — por seguridad no se abre", host, port, pid)
	}
	self, err := os.Executable()
	if err != nil {
		return err
	}
	candidates := []string{filepath.Join(filepath.Dir(self), "kairos-gateway.exe")}
	if stateDir, err := defaultGatewayDir(); err == nil {
		candidates = append(candidates, filepath.Join(stateDir, "kairos-gateway.exe"))
	}
	clean := filepath.Clean(path)
	for _, expected := range candidates {
		if strings.EqualFold(clean, filepath.Clean(expected)) {
			return nil
		}
	}
	return fmt.Errorf("%s:%d lo tiene %q, no una copia legítima de kairos-gateway.exe — por seguridad no se abre la ventana (podría ser otro programa)", host, port, path)
}

// showFatalError — MessageBoxW nativo: este binario es una app gráfica (sin consola visible al
// usuario), así que un error en stderr nunca se vería. Mismo criterio que cualquier error fatal
// de una app de escritorio real.
func showFatalError(title, message string) {
	t, _ := syscall.UTF16PtrFromString(title)
	m, _ := syscall.UTF16PtrFromString(message)
	const mbIconError = 0x10
	procMessageBoxW.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), mbIconError)
}

// showInfo — mismo MessageBoxW, ícono informativo en vez de error (usado por --usb-grant para
// reportar el resultado real de cada paso, nunca un error fatal en sí).
func showInfo(title, message string) {
	t, _ := syscall.UTF16PtrFromString(title)
	m, _ := syscall.UTF16PtrFromString(message)
	const mbIconInfo = 0x40
	procMessageBoxW.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), mbIconInfo)
}
