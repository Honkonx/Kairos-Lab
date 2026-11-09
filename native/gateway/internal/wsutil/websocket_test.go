package wsutil

import (
	"bufio"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Este archivo prueba el handshake y el framing contra un servidor HTTP real (httptest.NewServer
// corre net/http de verdad, con sockets reales, así que Hijack funciona) usando un cliente
// WebSocket mínimo escrito a mano — no hay ninguna librería de WebSocket en el proyecto (regla:
// solo librería estándar), así que probar de punta a punta implica hablar el protocolo también del
// lado "navegador" de la prueba.

// testClient es el lado "navegador" mínimo: sabe mandar frames enmascarados (obligatorio de
// cliente a servidor) y leer frames sin máscara (el servidor nunca enmascara).
type testClient struct {
	conn net.Conn
	br   *bufio.Reader
}

func dialWS(t *testing.T, url string) *testClient {
	t.Helper()
	host := url[len("http://"):]
	conn, err := net.DialTimeout("tcp", host, 2*time.Second)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	req := "GET /ws HTTP/1.1\r\n" +
		"Host: " + host + "\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
		"Sec-WebSocket-Version: 13\r\n\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		t.Fatalf("write handshake: %v", err)
	}
	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatalf("leyendo respuesta del handshake: %v", err)
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		t.Fatalf("esperaba 101, llegó %d", resp.StatusCode)
	}
	return &testClient{conn: conn, br: br}
}

func (c *testClient) writeMasked(opcode byte, payload []byte) error {
	first := byte(0x80) | opcode
	header := []byte{first}
	n := len(payload)
	switch {
	case n <= 125:
		header = append(header, 0x80|byte(n))
	case n <= 0xFFFF:
		ext := make([]byte, 2)
		binary.BigEndian.PutUint16(ext, uint16(n))
		header = append(header, 0x80|126)
		header = append(header, ext...)
	default:
		return fmt.Errorf("payload de prueba demasiado grande (%d bytes)", n)
	}
	var mask [4]byte
	if _, err := rand.Read(mask[:]); err != nil {
		return err
	}
	header = append(header, mask[:]...)
	masked := make([]byte, n)
	for i, b := range payload {
		masked[i] = b ^ mask[i%4]
	}
	if _, err := c.conn.Write(header); err != nil {
		return err
	}
	_, err := c.conn.Write(masked)
	return err
}

// readUnmasked lee un frame simple (sin reensamblar continuaciones; suficiente para estas pruebas,
// donde el servidor siempre manda mensajes de un solo frame).
func (c *testClient) readUnmasked() (opcode byte, payload []byte, err error) {
	hdr := make([]byte, 2)
	if _, err = fillFull(c.br, hdr); err != nil {
		return
	}
	opcode = hdr[0] & 0x0F
	length := int64(hdr[1] & 0x7F)
	switch length {
	case 126:
		ext := make([]byte, 2)
		if _, err = fillFull(c.br, ext); err != nil {
			return
		}
		length = int64(binary.BigEndian.Uint16(ext))
	case 127:
		ext := make([]byte, 8)
		if _, err = fillFull(c.br, ext); err != nil {
			return
		}
		length = int64(binary.BigEndian.Uint64(ext))
	}
	payload = make([]byte, length)
	if length > 0 {
		if _, err = fillFull(c.br, payload); err != nil {
			return
		}
	}
	return
}

func fillFull(r *bufio.Reader, p []byte) (int, error) {
	total := 0
	for total < len(p) {
		n, err := r.Read(p[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}

func TestHandshakeAndEcho(t *testing.T) {
	var serverConn *Conn
	ready := make(chan struct{})
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		c, err := Accept(w, r, "")
		if err != nil {
			t.Errorf("Accept: %v", err)
			return
		}
		serverConn = c
		close(ready)
		opcode, payload, err := c.ReadMessage()
		if err != nil {
			return
		}
		_ = c.WriteMessage(opcode, payload) // eco
	})
	ts := httptest.NewServer(mux)
	defer ts.Close()

	client := dialWS(t, ts.URL)
	defer client.conn.Close()

	msg := []byte("hola-websocket")
	if err := client.writeMasked(OpBinary, msg); err != nil {
		t.Fatalf("writeMasked: %v", err)
	}

	<-ready
	opcode, payload, err := client.readUnmasked()
	if err != nil {
		t.Fatalf("readUnmasked: %v", err)
	}
	if opcode != OpBinary || string(payload) != string(msg) {
		t.Errorf("eco distinto: opcode=%d payload=%q", opcode, payload)
	}
	_ = serverConn
}

func TestAcceptRejectsNonUpgradeRequest(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		if _, err := Accept(w, r, ""); err == nil {
			t.Error("una petición sin Upgrade: websocket debe fallar")
		} else {
			w.WriteHeader(http.StatusBadRequest)
		}
	})
	ts := httptest.NewServer(mux)
	defer ts.Close()

	resp, err := http.Get(ts.URL + "/ws")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("esperaba 400, llegó %d", resp.StatusCode)
	}
}

func TestServerNeverReadsUnmaskedClientFrame(t *testing.T) {
	// Un frame de "cliente" sin máscara es protocolo inválido (RFC 6455 §5.1) — probado a nivel de
	// Conn.readFrame directo, construyendo un par de sockets con net.Pipe (sin pasar por HTTP).
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	srv := &Conn{rw: a, br: bufioReader(a)}
	done := make(chan error, 1)
	go func() {
		_, _, err := srv.ReadMessage()
		done <- err
	}()
	// FIN=1, opcode=binary, longitud=0, sin bit de máscara.
	if _, err := b.Write([]byte{0x82, 0x00}); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err == nil {
			t.Error("un frame sin máscara debe producir un error")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("ReadMessage nunca volvió")
	}
}

func bufioReader(c net.Conn) *bufio.Reader { return bufio.NewReader(c) }

func TestOfferedProtocolsParsesCommaSeparatedList(t *testing.T) {
	r, _ := http.NewRequest(http.MethodGet, "/", nil)
	r.Header.Set("Sec-WebSocket-Protocol", "kairos-token.abc , otro-protocolo")
	got := OfferedProtocols(r)
	if len(got) != 2 || got[0] != "kairos-token.abc" || got[1] != "otro-protocolo" {
		t.Fatalf("parseo incorrecto: %#v", got)
	}
}

func TestOfferedProtocolsEmptyWhenHeaderMissing(t *testing.T) {
	r, _ := http.NewRequest(http.MethodGet, "/", nil)
	if got := OfferedProtocols(r); got != nil {
		t.Fatalf("sin cabecera debe dar nil, dio %#v", got)
	}
}

// Confirma el otro lado del fix de seguridad: Accept() de verdad repite el subprotocolo elegido en
// la respuesta (RFC 6455 §4.2.2) — sin esto, un navegador real rechaza la conexión por protocolo no
// reconocido aunque el servidor haya "aceptado" el token correctamente.
func TestAcceptEchoesChosenProtocol(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		offered := OfferedProtocols(r)
		if len(offered) != 1 {
			t.Errorf("esperaba 1 subprotocolo ofrecido, llegaron %d", len(offered))
		}
		c, err := Accept(w, r, offered[0])
		if err != nil {
			t.Fatalf("Accept: %v", err)
		}
		defer c.Close()
	}))
	defer srv.Close()

	host := srv.URL[len("http://"):]
	conn, err := net.DialTimeout("tcp", host, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	req := "GET /ws HTTP/1.1\r\n" +
		"Host: " + host + "\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
		"Sec-WebSocket-Protocol: kairos-token.secreto123\r\n" +
		"Sec-WebSocket-Version: 13\r\n\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		t.Fatal(err)
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := resp.Header.Get("Sec-WebSocket-Protocol"); got != "kairos-token.secreto123" {
		t.Errorf("la respuesta debe repetir el subprotocolo elegido, dio %q", got)
	}
}
