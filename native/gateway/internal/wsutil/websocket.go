// Package wsutil implementa el protocolo WebSocket (RFC 6455) del lado servidor con la librería
// estándar únicamente (regla del proyecto, ver .claude/skills/kairos-go-gateway/SKILL.md: "nada de
// dependencias nuevas sin preguntar"). Cubre solo lo que el relay de pantalla necesita: handshake,
// mensajes de texto/binarios completos (con reensamblado simple de fragmentos), ping/pong, y cierre
// ordenado — no negocia extensiones (compresión, etc.) ni expone frames parciales a quien la usa.
package wsutil

import (
	"bufio"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
)

// magicGUID es la constante fija del protocolo (RFC 6455 §1.3) usada para derivar
// Sec-WebSocket-Accept a partir de Sec-WebSocket-Key.
const magicGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// Opcodes del protocolo (RFC 6455 §5.2) — solo los que este paquete maneja.
const (
	OpContinuation byte = 0x0
	OpText         byte = 0x1
	OpBinary       byte = 0x2
	opClose        byte = 0x8
	opPing         byte = 0x9
	opPong         byte = 0xA
)

// maxMessage acota un mensaje entrante (de un visor: solo manda mensajes de control chicos, nunca
// video) — generoso para no romper si ese formato crece, pero sin dejar que un cliente hostil obligue
// a reservar memoria sin límite.
const maxMessage = 64 * 1024

// Conn es una conexión WebSocket ya aceptada (handshake hecho).
type Conn struct {
	rw net.Conn
	br *bufio.Reader
}

// OfferedProtocols devuelve la lista de subprotocolos que el cliente ofreció en
// `Sec-WebSocket-Protocol` (RFC 6425 §11.3.4), separados por coma — vacía si no mandó ninguno. Se
// usa para pasar credenciales en el handshake de WebSocket sin ponerlas en la URL (ver
// AuthProtocol más abajo): a diferencia de la URL, esta cabecera no queda en logs de acceso del
// servidor/túnel ni en el historial del navegador.
func OfferedProtocols(r *http.Request) []string {
	raw := r.Header.Get("Sec-WebSocket-Protocol")
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// Accept valida que la petición sea un upgrade a WebSocket válido, hace el hijack de la conexión
// HTTP subyacente y responde el 101 Switching Protocols. [chosenProtocol], si no está vacío, DEBE
// ser uno de los valores que devolvió [OfferedProtocols] para esta misma petición — se devuelve tal
// cual en `Sec-WebSocket-Protocol` de la respuesta, como exige el protocolo cuando el cliente ofreció
// alguno. Después de llamar a Accept, [w] y la conexión de [r] ya no son válidos para el resto del
// paquete net/http — toda la comunicación sigue por el *Conn devuelto.
func Accept(w http.ResponseWriter, r *http.Request, chosenProtocol string) (*Conn, error) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		return nil, errors.New("falta la cabecera Upgrade: websocket")
	}
	if !containsToken(r.Header.Get("Connection"), "upgrade") {
		return nil, errors.New("falta la cabecera Connection: Upgrade")
	}
	key := strings.TrimSpace(r.Header.Get("Sec-WebSocket-Key"))
	if key == "" {
		return nil, errors.New("falta Sec-WebSocket-Key")
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		return nil, errors.New("el servidor no soporta hijacking de la conexión")
	}
	conn, buf, err := hj.Hijack()
	if err != nil {
		return nil, fmt.Errorf("hijack: %w", err)
	}
	resp := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n"
	if chosenProtocol != "" {
		resp += "Sec-WebSocket-Protocol: " + chosenProtocol + "\r\n"
	}
	resp += "\r\n"
	if _, err := conn.Write([]byte(resp)); err != nil {
		conn.Close()
		return nil, err
	}
	return &Conn{rw: conn, br: buf.Reader}, nil
}

func acceptKey(key string) string {
	h := sha1.New()
	h.Write([]byte(key))
	h.Write([]byte(magicGUID))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

func containsToken(header, token string) bool {
	for _, part := range strings.Split(header, ",") {
		if strings.EqualFold(strings.TrimSpace(part), token) {
			return true
		}
	}
	return false
}

// WriteMessage manda un mensaje completo en un solo frame (FIN=1). Los servidores nunca enmascaran
// (RFC 6455 §5.1) — la máscara es solo obligatoria de cliente a servidor.
func (c *Conn) WriteMessage(opcode byte, payload []byte) error {
	return c.writeFrame(true, opcode, payload)
}

// Close manda un frame de cierre y cierra la conexión subyacente. Seguro de llamar más de una vez
// o después de que ReadMessage ya haya detectado el cierre.
func (c *Conn) Close() error {
	_ = c.writeFrame(true, opClose, nil)
	return c.rw.Close()
}

func (c *Conn) writeFrame(fin bool, opcode byte, payload []byte) error {
	first := opcode & 0x0F
	if fin {
		first |= 0x80
	}
	header := []byte{first}
	n := len(payload)
	switch {
	case n <= 125:
		header = append(header, byte(n))
	case n <= 0xFFFF:
		ext := make([]byte, 2)
		binary.BigEndian.PutUint16(ext, uint16(n))
		header = append(header, 126)
		header = append(header, ext...)
	default:
		ext := make([]byte, 8)
		binary.BigEndian.PutUint64(ext, uint64(n))
		header = append(header, 127)
		header = append(header, ext...)
	}
	if _, err := c.rw.Write(header); err != nil {
		return err
	}
	if n == 0 {
		return nil
	}
	_, err := c.rw.Write(payload)
	return err
}

// ReadMessage lee el próximo mensaje completo, reensamblando fragmentos de continuación y
// respondiendo automáticamente a ping (con pong) e ignorando pong. Un cierre del cliente se traduce
// en io.EOF, para que el llamador lo trate igual que cualquier otro fin de conexión.
func (c *Conn) ReadMessage() (byte, []byte, error) {
	for {
		fin, opcode, payload, err := c.readFrame()
		if err != nil {
			return 0, nil, err
		}
		switch opcode {
		case opPing:
			if err := c.writeFrame(true, opPong, payload); err != nil {
				return 0, nil, err
			}
			continue
		case opPong:
			continue
		case opClose:
			_ = c.writeFrame(true, opClose, nil)
			return 0, nil, io.EOF
		}
		if fin {
			return opcode, payload, nil
		}
		full := append([]byte{}, payload...)
		for {
			fin2, _, payload2, err := c.readFrame()
			if err != nil {
				return 0, nil, err
			}
			full = append(full, payload2...)
			if len(full) > maxMessage {
				return 0, nil, errors.New("mensaje reensamblado demasiado grande")
			}
			if fin2 {
				break
			}
		}
		return opcode, full, nil
	}
}

func (c *Conn) readFrame() (fin bool, opcode byte, payload []byte, err error) {
	hdr := make([]byte, 2)
	if _, err = io.ReadFull(c.br, hdr); err != nil {
		return
	}
	fin = hdr[0]&0x80 != 0
	opcode = hdr[0] & 0x0F
	masked := hdr[1]&0x80 != 0
	length := int64(hdr[1] & 0x7F)
	switch length {
	case 126:
		ext := make([]byte, 2)
		if _, err = io.ReadFull(c.br, ext); err != nil {
			return
		}
		length = int64(binary.BigEndian.Uint16(ext))
	case 127:
		ext := make([]byte, 8)
		if _, err = io.ReadFull(c.br, ext); err != nil {
			return
		}
		length = int64(binary.BigEndian.Uint64(ext))
	}
	if length < 0 || length > maxMessage {
		err = fmt.Errorf("frame de %d bytes supera el máximo permitido (%d)", length, maxMessage)
		return
	}
	if !masked {
		// RFC 6455 §5.1: todo frame de cliente a servidor debe venir enmascarado; uno sin máscara
		// es un cliente que no habla el protocolo (o algo que no es WebSocket en absoluto).
		err = errors.New("frame de cliente sin máscara (protocolo inválido)")
		return
	}
	var maskKey [4]byte
	if _, err = io.ReadFull(c.br, maskKey[:]); err != nil {
		return
	}
	payload = make([]byte, length)
	if length > 0 {
		if _, err = io.ReadFull(c.br, payload); err != nil {
			return
		}
	}
	for i := range payload {
		payload[i] ^= maskKey[i%4]
	}
	return
}
