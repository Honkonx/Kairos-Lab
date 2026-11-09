package auth

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
)

func TestCreateAuthenticateRevoke(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tokens.json")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	secret, tok, err := s.Create("celular de prueba", permission.Semi)
	if err != nil {
		t.Fatal(err)
	}
	if len(secret) != 64 {
		t.Errorf("el secreto debería tener 64 hex, tiene %d", len(secret))
	}
	if tok.Hash != "" {
		t.Error("Create no debe devolver el hash")
	}

	got, ok := s.Authenticate(secret)
	if !ok || got.ID != tok.ID || got.Level != permission.Semi {
		t.Fatalf("Authenticate(secreto) = %+v, %v", got, ok)
	}
	if _, ok := s.Authenticate(secret + "x"); ok {
		t.Error("un secreto distinto no debe autenticar")
	}
	if _, ok := s.Authenticate(""); ok {
		t.Error("un secreto vacío no debe autenticar")
	}

	if err := s.Revoke(tok.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Authenticate(secret); ok {
		t.Error("un token revocado no debe autenticar")
	}
	if err := s.Revoke(tok.ID); err != ErrNotFound {
		t.Errorf("revocar dos veces debe dar ErrNotFound, dio %v", err)
	}
}

func TestSecretIsNeverPersistedInClear(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tokens.json")
	s, _ := Open(path)
	secret, _, err := s.Create("agente", permission.Read)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), secret) {
		t.Fatal("el valor en claro del token apareció en el archivo")
	}
	for _, tok := range s.List() {
		if tok.Hash != "" {
			t.Error("List no debe exponer hashes")
		}
	}
}

func TestPersistenceAcrossOpen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "tokens.json")
	s, _ := Open(path)
	secret, _, err := s.Create("persistente", permission.Full)
	if err != nil {
		t.Fatal(err)
	}
	again, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := again.Authenticate(secret); !ok {
		t.Error("el token no sobrevivió a reabrir el archivo")
	}
}

func TestCreateRejectsBadNames(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "t.json"))
	for _, name := range []string{"", "   ", strings.Repeat("x", 65)} {
		if _, _, err := s.Create(name, permission.Read); err == nil {
			t.Errorf("el nombre %q debería rechazarse", name)
		}
	}
}

func TestOpenRejectsCorruptFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tokens.json")
	if err := os.WriteFile(path, []byte("{no es json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(path); err == nil {
		t.Fatal("un archivo corrupto debe dar error y no abrirse vacío en silencio")
	}
}

// Hallazgo real de docs/referencias/pc-movil/: "caducidad de tokens -- hoy no vencen nunca".

func TestCreateWithoutTTLNeverExpires(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "t.json"))
	secret, tok, err := s.Create("sin vencimiento", permission.Read)
	if err != nil {
		t.Fatal(err)
	}
	if tok.ExpiresAt != nil {
		t.Fatalf("Create (sin TTL) no debería poner ExpiresAt, tiene %v", tok.ExpiresAt)
	}
	if _, ok := s.Authenticate(secret); !ok {
		t.Error("un token sin vencimiento debe autenticar")
	}
}

func TestCreateWithTTLExpires(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "t.json"))
	secret, tok, err := s.CreateWithTTL("vence ya", permission.Read, 1*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if tok.ExpiresAt == nil {
		t.Fatal("CreateWithTTL(ttl>0) debería poner ExpiresAt")
	}
	time.Sleep(5 * time.Millisecond)
	if _, ok := s.Authenticate(secret); ok {
		t.Error("un token vencido no debe autenticar")
	}
	// Sigue visible en List() (vencido, no revocado) hasta que algo lo pode -- admin debe poder
	// verlo para entender por qué dejó de funcionar, en vez de que desaparezca sin explicación.
	found := false
	for _, listed := range s.List() {
		if listed.ID == tok.ID {
			found = true
		}
	}
	if !found {
		t.Error("un token vencido (no revocado) debería seguir apareciendo en List()")
	}
}

func TestCreateWithTTLZeroOrNegativeNeverExpires(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "t.json"))
	for _, ttl := range []time.Duration{0, -1 * time.Hour} {
		secret, tok, err := s.CreateWithTTL("ttl no positivo", permission.Read, ttl)
		if err != nil {
			t.Fatal(err)
		}
		if tok.ExpiresAt != nil {
			t.Errorf("ttl=%v debería dejar ExpiresAt nil, tiene %v", ttl, tok.ExpiresAt)
		}
		if _, ok := s.Authenticate(secret); !ok {
			t.Errorf("ttl=%v no debería vencer de entrada", ttl)
		}
	}
}

func TestPruneExpiredRemovesOnlyExpiredTokens(t *testing.T) {
	path := filepath.Join(t.TempDir(), "t.json")
	s, _ := Open(path)
	expiredSecret, expiredTok, _ := s.CreateWithTTL("vencido", permission.Read, 1*time.Millisecond)
	liveSecret, _, _ := s.Create("vivo", permission.Read)
	time.Sleep(5 * time.Millisecond)

	removed, err := s.PruneExpired()
	if err != nil {
		t.Fatal(err)
	}
	if removed != 1 {
		t.Fatalf("PruneExpired() quitó %d tokens, esperaba 1", removed)
	}
	for _, listed := range s.List() {
		if listed.ID == expiredTok.ID {
			t.Error("el token vencido debería haber sido removido del store")
		}
	}
	if _, ok := s.Authenticate(liveSecret); !ok {
		t.Error("PruneExpired no debería tocar un token todavía vigente")
	}
	_ = expiredSecret

	// Un segundo Open() del mismo archivo debe podar automáticamente -- no debe hacer falta
	// llamar PruneExpired() a mano después de reabrir.
	again, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, listed := range again.List() {
		if listed.ID == expiredTok.ID {
			t.Error("Open() debería haber podado el token ya vencido al cargar el archivo")
		}
	}
}
