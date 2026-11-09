package screen

import (
	"net"
	"testing"
	"time"
)

func TestHandshakeSucceedsWithMatchingSecret(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()

	done := make(chan error, 1)
	go func() { done <- RespondToHandshake(server, testSecret) }()

	if err := authenticateSource(client, testSecret); err != nil {
		t.Fatalf("authenticateSource con el secreto correcto debería pasar, dio: %v", err)
	}
	if err := <-done; err != nil {
		t.Fatalf("RespondToHandshake: %v", err)
	}
}

func TestHandshakeFailsWithWrongSecret(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()

	go func() { _ = RespondToHandshake(server, []byte("otro-secreto-distinto")) }()

	err := authenticateSource(client, testSecret)
	if err == nil {
		t.Fatal("authenticateSource con el secreto equivocado debería fallar y no lo hizo")
	}
}

func TestHandshakeFailsWithoutAnySecretConfigured(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()

	if err := authenticateSource(client, nil); err == nil {
		t.Fatal("sin secreto configurado, la autenticación nunca debe pasar")
	}
}

// Simula exactamente el ataque que motivó este hallazgo: un proceso cualquiera ocupa el puerto y
// acepta la conexión, pero no habla el protocolo de autenticación — nunca debe quedar como fuente.
func TestHandshakeFailsAgainstANonSpeakingPeer(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()

	go func() {
		buf := make([]byte, 4096)
		_, _ = server.Read(buf) // lee el reto y no contesta nada con sentido
		time.Sleep(50 * time.Millisecond)
		_, _ = server.Write([]byte("no soy el protocolo esperado"))
	}()

	if err := authenticateSource(client, testSecret); err == nil {
		t.Fatal("un peer que no habla el protocolo de handshake nunca debe autenticarse")
	}
}
