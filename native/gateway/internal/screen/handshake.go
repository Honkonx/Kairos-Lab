package screen

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"time"
)

// Revisión de seguridad real: StartConnector se conecta por loopback a un puerto fijo
// (127.0.0.1:8974 por defecto) y trata lo que responda como la fuente real de pantalla, sin verificar
// quién es — cualquier proceso del mismo usuario (todos los de Termux comparten UID) podía ocupar ese
// puerto primero ("port squatting") y: (a) hacerse pasar por la pantalla real ante quien mira el panel,
// o (b) recibir cada toque/tecla que el panel reenvía como control, pensados para el teléfono real.
// Esta autenticación reto-respuesta (HMAC-SHA256, solo librería estándar — regla del gateway) cierra
// ese hueco: sin el secreto correcto, la conexión nunca se acepta como fuente.
const (
	handshakeChallengeLen = 32
	handshakeMacLen       = sha256.Size
	handshakeTimeout      = 3 * time.Second
)

var errHandshakeFailed = errors.New("la fuente de pantalla no superó la autenticación (secreto incorrecto o protocolo inesperado)")

// writeFrame escribe un bloque con prefijo de longitud (4 bytes, big-endian) — mismo patrón simple
// usado para el reto y la respuesta.
func writeFrame(w io.Writer, b []byte) error {
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(b)))
	if _, err := w.Write(lenBuf[:]); err != nil {
		return err
	}
	_, err := w.Write(b)
	return err
}

func readFrameExact(r io.Reader, max int) ([]byte, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(lenBuf[:])
	if n == 0 || int(n) > max {
		return nil, fmt.Errorf("longitud de handshake inválida: %d", n)
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// deadlineConn es lo mínimo que necesita el handshake para no bloquear para siempre si el otro lado
// nunca responde — net.Conn lo cumple; el modo stub/pruebas usa una conexión real (net.Pipe), nunca
// hace falta implementarlo a mano.
type deadlineConn interface {
	io.ReadWriter
	SetDeadline(t time.Time) error
}

// authenticateSource hace de lado "cliente" del handshake (el gateway, que dialed la conexión):
// manda un reto aleatorio, espera HMAC-SHA256(secret, reto) de vuelta, y compara en tiempo
// constante (hmac.Equal). Sin [secret] (todavía no se generó/entregó ninguno) la conexión se
// rechaza siempre — "sin autenticar" nunca es un valor por defecto aceptable para esto.
func authenticateSource(conn deadlineConn, secret []byte) error {
	if len(secret) == 0 {
		return errors.New("no hay secreto de pantalla configurado: no se puede autenticar ninguna fuente")
	}
	_ = conn.SetDeadline(time.Now().Add(handshakeTimeout))
	defer conn.SetDeadline(time.Time{})

	challenge := make([]byte, handshakeChallengeLen)
	if _, err := rand.Read(challenge); err != nil {
		return fmt.Errorf("generando el reto: %w", err)
	}
	if err := writeFrame(conn, challenge); err != nil {
		return fmt.Errorf("mandando el reto: %w", err)
	}
	resp, err := readFrameExact(conn, handshakeMacLen)
	if err != nil {
		return fmt.Errorf("leyendo la respuesta: %w", err)
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write(challenge)
	want := mac.Sum(nil)
	if len(resp) != len(want) || !hmac.Equal(resp, want) {
		return errHandshakeFailed
	}
	return nil
}

// RespondToHandshake es el lado "servidor" (la futura fuente real de pantalla en Android, o el
// generador de pruebas): lee el reto, devuelve su HMAC. Exportada para que el lado Android (cuando
// exista) y las pruebas puedan hablar el mismo protocolo sin reimplementarlo.
func RespondToHandshake(conn deadlineConn, secret []byte) error {
	_ = conn.SetDeadline(time.Now().Add(handshakeTimeout))
	defer conn.SetDeadline(time.Time{})

	challenge, err := readFrameExact(conn, handshakeChallengeLen)
	if err != nil {
		return fmt.Errorf("leyendo el reto: %w", err)
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write(challenge)
	return writeFrame(conn, mac.Sum(nil))
}
