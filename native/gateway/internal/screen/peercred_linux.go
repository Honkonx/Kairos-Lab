//go:build linux

package screen

import (
	"fmt"
	"net"
	"syscall"
)

// Revisión de seguridad real: el secreto detrás del nombre del socket abstracto
// (`scrcpy_<scid>`) NO es suficiente — Linux expone todos los nombres de socket abstracto activos
// en `/proc/net/unix` (legible por cualquier proceso en la práctica, sin permiso especial), así que
// cualquier app del teléfono puede DESCUBRIR el nombre exacto sin adivinar el scid ni leer
// `screen.scid`, y conectarse para suplantar la fuente real o recibir el video. La defensa real es
// verificar la credencial del proceso que se conecta (SO_PEERCRED, solo Linux, solo librería
// estándar — `syscall.GetsockoptUcred`): únicamente UID 0 (root) o 2000 (shell — la identidad que
// usan `adb shell`/Shizuku para lanzar el servidor real) se aceptan como fuente. Cualquier otra app
// del dispositivo tiene su propio UID de sandbox, distinto de estos dos, así que queda rechazada
// aunque conozca el nombre exacto del socket.
func peerCredentialAllowed(conn net.Conn) (bool, string, error) {
	uc, ok := conn.(*net.UnixConn)
	if !ok {
		return false, "", fmt.Errorf("conexión inesperada (no es un socket Unix)")
	}
	raw, err := uc.SyscallConn()
	if err != nil {
		return false, "", err
	}
	var cred *syscall.Ucred
	var sockErr error
	err = raw.Control(func(fd uintptr) {
		cred, sockErr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
	})
	if err != nil {
		return false, "", err
	}
	if sockErr != nil {
		return false, "", sockErr
	}
	desc := fmt.Sprintf("uid=%d pid=%d", cred.Uid, cred.Pid)
	allowed := cred.Uid == 0 || cred.Uid == 2000
	return allowed, desc, nil
}
