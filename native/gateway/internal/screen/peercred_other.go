//go:build !linux

package screen

import (
	"errors"
	"net"
)

// El gateway real solo corre en Android (Linux) — este stub existe únicamente para que `go build`/
// `go test` compilen en la máquina de desarrollo (Windows/macOS), donde SO_PEERCRED no existe. Falla
// cerrado a propósito: sin poder verificar la credencial, nunca se acepta una fuente como válida.
func peerCredentialAllowed(conn net.Conn) (bool, string, error) {
	return false, "", errors.New("verificación de credencial del socket no disponible en esta plataforma (solo Linux)")
}
