package permission

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// DefaultPolicy es la política inicial: techo en "pregunta" y sin la casilla de responsabilidad,
// es decir, lo más conservador que aún permite usar acciones.
var DefaultPolicy = Policy{Max: Ask, Responsible: false}

// Store guarda la política en un archivo JSON. El usuario la cambia desde la app.
type Store struct {
	mu   sync.RWMutex
	path string
	p    Policy
}

// OpenStore carga la política; si el archivo no existe usa DefaultPolicy.
func OpenStore(path string) (*Store, error) {
	s := &Store{path: path, p: DefaultPolicy}
	raw, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return s, nil
	case err != nil:
		return nil, fmt.Errorf("leyendo política: %w", err)
	}
	if err := json.Unmarshal(raw, &s.p); err != nil {
		return nil, fmt.Errorf("política corrupta en %s: %w", path, err)
	}
	return s, nil
}

// Get devuelve la política vigente.
func (s *Store) Get() Policy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.p
}

// Set cambia y guarda la política. Si no se puede guardar, la anterior sigue vigente.
func (s *Store) Set(p Policy) error {
	raw, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, s.path); err != nil {
		os.Remove(tmp)
		return err
	}
	s.p = p
	return nil
}
