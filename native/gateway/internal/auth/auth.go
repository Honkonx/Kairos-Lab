// Package auth guarda los tokens del gateway. Solo se persiste el hash SHA-256: el valor en claro
// se entrega una única vez al crearlo y nunca se vuelve a mostrar (regla kairos-secrets-never-revealed).
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
)

// ErrNotFound se devuelve al revocar un token que no existe.
var ErrNotFound = errors.New("token no encontrado")

// Token es un acceso concedido a un dispositivo o agente.
type Token struct {
	ID      string           `json:"id"`
	Name    string           `json:"name"`
	Hash    string           `json:"hash,omitempty"`
	Level   permission.Level `json:"level"`
	Created time.Time        `json:"created"`
	// ExpiresAt es nil para un token sin vencimiento (comportamiento histórico, hallazgo real de
	// docs/referencias/pc-movil/: "caducidad de tokens -- hoy no vencen nunca"). Los tokens
	// creados antes de este campo deserializan con ExpiresAt=nil -- sin migración necesaria.
	ExpiresAt *time.Time `json:"expiresAt,omitempty"`
}

// Expired informa si el token ya venció. Un token sin ExpiresAt nunca vence.
func (t Token) Expired(now time.Time) bool {
	return t.ExpiresAt != nil && now.After(*t.ExpiresAt)
}

// Store es el almacén de tokens, seguro para uso concurrente.
type Store struct {
	mu     sync.RWMutex
	path   string
	tokens []Token
}

// Open carga el archivo de tokens; si no existe, empieza vacío.
func Open(path string) (*Store, error) {
	s := &Store{path: path}
	raw, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return s, nil
	case err != nil:
		return nil, fmt.Errorf("leyendo tokens: %w", err)
	}
	if err := json.Unmarshal(raw, &s.tokens); err != nil {
		return nil, fmt.Errorf("tokens corruptos en %s: %w", path, err)
	}
	// Poda al abrir -- un token vencido de una sesión anterior no debería seguir ocupando
	// espacio en el archivo ni apareciendo en la lista de admin como si todavía fuera válido.
	if _, err := s.PruneExpired(); err != nil {
		return nil, fmt.Errorf("podando tokens vencidos de %s: %w", path, err)
	}
	return s, nil
}

// Create genera un token nuevo, sin vencimiento. Devuelve el valor en claro, que no se guarda.
func (s *Store) Create(name string, level permission.Level) (string, Token, error) {
	return s.CreateWithTTL(name, level, 0)
}

// CreateWithTTL genera un token nuevo que vence `ttl` después de ahora -- ttl <= 0 significa
// "sin vencimiento" (mismo comportamiento que [Store.Create]).
func (s *Store) CreateWithTTL(name string, level permission.Level, ttl time.Duration) (string, Token, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 64 {
		return "", Token{}, errors.New("el nombre del token debe tener entre 1 y 64 caracteres")
	}
	secret, err := randomHex(32)
	if err != nil {
		return "", Token{}, err
	}
	id, err := randomHex(4)
	if err != nil {
		return "", Token{}, err
	}
	t := Token{ID: id, Name: name, Hash: hashOf(secret), Level: level, Created: time.Now().UTC()}
	if ttl > 0 {
		expires := t.Created.Add(ttl)
		t.ExpiresAt = &expires
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.tokens = append(s.tokens, t)
	if err := s.saveLocked(); err != nil {
		s.tokens = s.tokens[:len(s.tokens)-1]
		return "", Token{}, err
	}
	t.Hash = ""
	return secret, t, nil
}

// Revoke borra un token por id.
func (s *Store) Revoke(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, t := range s.tokens {
		if t.ID == id {
			prev := s.tokens
			s.tokens = append(append([]Token{}, prev[:i]...), prev[i+1:]...)
			if err := s.saveLocked(); err != nil {
				s.tokens = prev
				return err
			}
			return nil
		}
	}
	return ErrNotFound
}

// List devuelve los tokens sin su hash.
func (s *Store) List() []Token {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Token, len(s.tokens))
	for i, t := range s.tokens {
		t.Hash = ""
		out[i] = t
	}
	return out
}

// Authenticate busca el token que corresponde al valor en claro. Compara todos los hashes en
// tiempo constante para no revelar por tiempo cuál coincidió. Un token vencido ([Token.Expired])
// nunca autentica -- sigue existiendo en el store (visible en [List], para que admin sepa que
// venció) hasta que algo lo pode con [PruneExpired] o lo revoque a mano.
func (s *Store) Authenticate(plain string) (Token, bool) {
	if plain == "" {
		return Token{}, false
	}
	want := hashOf(plain)
	now := time.Now().UTC()
	s.mu.RLock()
	defer s.mu.RUnlock()
	var found Token
	ok := false
	for _, t := range s.tokens {
		if subtle.ConstantTimeCompare([]byte(t.Hash), []byte(want)) == 1 && !t.Expired(now) {
			found, ok = t, true
		}
	}
	found.Hash = ""
	return found, ok
}

// PruneExpired quita del store los tokens ya vencidos. Devuelve cuántos se quitaron. Pensado
// para llamarse al abrir el store y periódicamente (no hay un timer propio acá, el caller
// decide la cadencia) -- un token vencido que [Authenticate] ya rechaza igual sigue ocupando
// espacio en el archivo si nadie lo poda.
func (s *Store) PruneExpired() (int, error) {
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := make([]Token, 0, len(s.tokens))
	removed := 0
	for _, t := range s.tokens {
		if t.Expired(now) {
			removed++
			continue
		}
		kept = append(kept, t)
	}
	if removed == 0 {
		return 0, nil
	}
	prev := s.tokens
	s.tokens = kept
	if err := s.saveLocked(); err != nil {
		s.tokens = prev
		return 0, err
	}
	return removed, nil
}

func (s *Store) saveLocked() error {
	raw, err := json.MarshalIndent(s.tokens, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(s.path, raw)
}

func hashOf(secret string) string {
	sum := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(sum[:])
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("sin entropía: %w", err)
	}
	return hex.EncodeToString(b), nil
}

// writeFileAtomic escribe en un temporal y renombra; el directorio queda 0700 y el archivo 0600.
func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".tmp-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil && !errors.Is(err, os.ErrPermission) {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}
