package audit

import (
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func TestWriteAndTail(t *testing.T) {
	l := Open(filepath.Join(t.TempDir(), "audit.log"), 0)
	for i := 0; i < 5; i++ {
		if err := l.Write(Entry{TokenID: "t1", Action: "act" + strconv.Itoa(i), Decision: "permitida"}); err != nil {
			t.Fatal(err)
		}
	}
	got, err := l.Tail(3)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 || got[0].Action != "act2" || got[2].Action != "act4" {
		t.Fatalf("Tail(3) = %+v", got)
	}
	if got[0].Time.IsZero() {
		t.Error("la hora debe completarse sola")
	}
}

func TestTailWithoutFileIsEmpty(t *testing.T) {
	got, err := Open(filepath.Join(t.TempDir(), "nada.log"), 0).Tail(10)
	if err != nil || len(got) != 0 {
		t.Fatalf("sin archivo debía dar vacío sin error, dio %v, %v", got, err)
	}
}

func TestRotationKeepsOnePreviousFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "audit.log")
	l := Open(path, 200)
	for i := 0; i < 20; i++ {
		if err := l.Write(Entry{TokenID: "t", Action: "accion-larga-" + strconv.Itoa(i)}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := os.Stat(path + ".1"); err != nil {
		t.Fatalf("debía existir el archivo rotado: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() >= 400 {
		t.Errorf("el archivo actual creció sin rotar: %d bytes", info.Size())
	}
}
