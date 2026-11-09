// Package audit escribe el registro de auditoría del gateway: una línea JSON por decisión.
// Nunca recibe valores de tokens; solo su id.
package audit

import (
	"bufio"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Entry es una línea del registro.
type Entry struct {
	Time     time.Time `json:"time"`
	TokenID  string    `json:"token_id"`
	Action   string    `json:"action"`
	Level    string    `json:"level"`
	Decision string    `json:"decision"`
	Result   string    `json:"result,omitempty"`
}

// Log es un registro con rotación por tamaño (un único archivo anterior, ".1").
type Log struct {
	mu       sync.Mutex
	path     string
	maxBytes int64
}

// Open prepara el registro; el archivo se crea al escribir la primera entrada.
func Open(path string, maxBytes int64) *Log {
	if maxBytes <= 0 {
		maxBytes = 1 << 20
	}
	return &Log{path: path, maxBytes: maxBytes}
}

// Write agrega una entrada.
func (l *Log) Write(e Entry) error {
	if e.Time.IsZero() {
		e.Time = time.Now().UTC()
	}
	line, err := json.Marshal(e)
	if err != nil {
		return err
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if err := os.MkdirAll(filepath.Dir(l.path), 0o700); err != nil {
		return err
	}
	if info, err := os.Stat(l.path); err == nil && info.Size() >= l.maxBytes {
		if err := os.Rename(l.path, l.path+".1"); err != nil {
			return err
		}
	}
	f, err := os.OpenFile(l.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	_, werr := f.Write(append(line, '\n'))
	if cerr := f.Close(); werr == nil {
		werr = cerr
	}
	return werr
}

// Tail devuelve las últimas n entradas del archivo actual, de la más vieja a la más nueva.
func (l *Log) Tail(n int) ([]Entry, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	f, err := os.Open(l.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()

	var all []Entry
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	for sc.Scan() {
		var e Entry
		if json.Unmarshal(sc.Bytes(), &e) == nil {
			all = append(all, e)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if n > 0 && len(all) > n {
		all = all[len(all)-n:]
	}
	return all, nil
}
