// Package screen relay un flujo de video/control del espejo de pantalla propio de Kairos (ver
// docs/homelab/PANTALLA_ANDROID_PROPIA_2026-10-10.md) entre UNA fuente (el futuro servidor de
// pantalla del lado Android, o un generador simulado para pruebas) y cualquier cantidad de visores
// WebSocket (el panel web, o un futuro programa de escritorio).
//
// El formato de cada paquete es el mismo que ya decodifica/codifica el lado Android
// (app/src/main/java/com/termux/app/util/ScreenStreamProtocol.kt, adaptado con crédito de scrcpy
// Apache-2.0, Genymobile — ver ese archivo para la cita exacta de líneas): una cabecera de 12 bytes
// (pts + flags en un int64 big-endian, tamaño en un int32 big-endian) seguida del payload. Esta
// implementación es independiente — no reusa código Kotlin — pero debe producir/consumir bytes
// idénticos a esa clase; cualquier cambio de formato se hace en los dos lados a la vez.
package screen

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// HeaderSize es el tamaño fijo de la cabecera de cada paquete.
const HeaderSize = 12

const (
	flagConfig   = int64(1) << 62
	flagKeyFrame = int64(1) << 61
	ptsMask      = flagKeyFrame - 1
)

// maxFrameSize acota el tamaño de payload que se acepta de la fuente: un keyframe H.264 de pantalla
// completa no debería pasar de unos pocos MB; el tope evita que un stream corrupto o malicioso haga
// que el relay intente reservar una cantidad de memoria arbitraria.
const maxFrameSize = 8 * 1024 * 1024

// Frame es la metadata de un paquete de video, espejo de ScreenFrameHeader.Frame en Kotlin.
type Frame struct {
	PTS      int64
	Config   bool
	KeyFrame bool
	Size     int32
}

// EncodeHeader arma los 12 bytes de cabecera para [Frame]. El [pts] se ignora si Config es true,
// igual que en el original (ScreenFrameHeader.encode).
func EncodeHeader(f Frame) []byte {
	var ptsAndFlags int64
	switch {
	case f.Config:
		ptsAndFlags = flagConfig
	case f.KeyFrame:
		ptsAndFlags = (f.PTS & ptsMask) | flagKeyFrame
	default:
		ptsAndFlags = f.PTS & ptsMask
	}
	buf := make([]byte, HeaderSize)
	binary.BigEndian.PutUint64(buf[0:8], uint64(ptsAndFlags))
	binary.BigEndian.PutUint32(buf[8:12], uint32(f.Size))
	return buf
}

// DecodeHeader interpreta los 12 bytes de cabecera, espejo de ScreenFrameHeader.decode.
func DecodeHeader(b []byte) (Frame, error) {
	if len(b) < HeaderSize {
		return Frame{}, fmt.Errorf("se necesitan %d bytes para la cabecera, hay %d", HeaderSize, len(b))
	}
	ptsAndFlags := int64(binary.BigEndian.Uint64(b[0:8]))
	size := int32(binary.BigEndian.Uint32(b[8:12]))
	config := ptsAndFlags&flagConfig != 0
	keyFrame := !config && ptsAndFlags&flagKeyFrame != 0
	var pts int64
	if !config {
		pts = ptsAndFlags & ptsMask
	}
	return Frame{PTS: pts, Config: config, KeyFrame: keyFrame, Size: size}, nil
}

// EncodeFrame arma un paquete completo (cabecera + payload) a partir de [f] y [payload]; es
// responsabilidad del llamador que f.Size coincida con len(payload) (ReadFrame del otro lado lo
// exige al revés, al decodificar).
func EncodeFrame(f Frame, payload []byte) []byte {
	out := make([]byte, 0, HeaderSize+len(payload))
	out = append(out, EncodeHeader(f)...)
	out = append(out, payload...)
	return out
}

// ErrFrameTooLarge se devuelve cuando la cabecera declara un tamaño fuera de rango — señal de un
// flujo corrupto o de una fuente que no habla este protocolo.
var ErrFrameTooLarge = errors.New("el paquete declara un tamaño fuera de rango")

// ReadFrame lee un paquete completo (cabecera + payload) de [r] y devuelve la metadata junto con los
// bytes crudos completos (cabecera+payload, listos para reenviar tal cual a un visor). Devuelve el
// error de [r] sin envolver (incluido io.EOF) cuando la fuente se corta a mitad de la cabecera —
// quien llama decide si eso es un cierre normal o un error.
func ReadFrame(r io.Reader) (Frame, []byte, error) {
	hdr := make([]byte, HeaderSize)
	if _, err := io.ReadFull(r, hdr); err != nil {
		return Frame{}, nil, err
	}
	f, err := DecodeHeader(hdr)
	if err != nil {
		return Frame{}, nil, err
	}
	if f.Size < 0 || int(f.Size) > maxFrameSize {
		return Frame{}, nil, ErrFrameTooLarge
	}
	raw := make([]byte, HeaderSize+int(f.Size))
	copy(raw, hdr)
	if f.Size > 0 {
		if _, err := io.ReadFull(r, raw[HeaderSize:]); err != nil {
			return Frame{}, nil, err
		}
	}
	return f, raw, nil
}
