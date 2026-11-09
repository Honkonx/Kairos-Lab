package server

import (
	"crypto/tls"
	"crypto/x509"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Hallazgo real de seguridad (docs/referencias/homelab/AUDITORIA_homelab_2026-10-09.md,
// reauditada 2026-11-02): "TLS en el listener LAN del gateway -- el token viaja en texto plano".

func TestEnsureSelfSignedCertGeneratesValidCert(t *testing.T) {
	dir := t.TempDir()
	certPath, keyPath, err := EnsureSelfSignedCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(certPath); err != nil {
		t.Fatalf("no se escribió el certificado: %v", err)
	}
	if _, err := os.Stat(keyPath); err != nil {
		t.Fatalf("no se escribió la clave: %v", err)
	}
	// El par debe cargar como un keypair TLS real -- no solo "los archivos existen".
	pair, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		t.Fatalf("el par cert/key generado no es válido: %v", err)
	}
	cert, err := x509.ParseCertificate(pair.Certificate[0])
	if err != nil {
		t.Fatalf("no se pudo parsear el certificado: %v", err)
	}
	if time.Until(cert.NotAfter) < 9*365*24*time.Hour {
		t.Errorf("el certificado debería durar ~10 años, vence en %v", cert.NotAfter)
	}
	if cert.NotBefore.After(time.Now()) {
		t.Errorf("NotBefore (%v) no debería ser futuro", cert.NotBefore)
	}
}

func TestEnsureSelfSignedCertIsIdempotent(t *testing.T) {
	dir := t.TempDir()
	certPath1, keyPath1, err := EnsureSelfSignedCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	rawCert1, _ := os.ReadFile(certPath1)
	rawKey1, _ := os.ReadFile(keyPath1)

	// Segunda llamada: NO debe regenerar un par distinto -- un gateway que se reinicia seguido
	// (cada vez que el usuario prende/apaga el modo LAN) no debe invalidar el cert que los
	// clientes ya puedan haber confiado/guardado.
	certPath2, keyPath2, err := EnsureSelfSignedCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	if certPath1 != certPath2 || keyPath1 != keyPath2 {
		t.Fatalf("las rutas cambiaron entre llamadas: (%s,%s) vs (%s,%s)", certPath1, keyPath1, certPath2, keyPath2)
	}
	rawCert2, _ := os.ReadFile(certPath2)
	rawKey2, _ := os.ReadFile(keyPath2)
	if string(rawCert1) != string(rawCert2) {
		t.Error("el certificado se regeneró en la segunda llamada -- debería reusar el existente")
	}
	if string(rawKey1) != string(rawKey2) {
		t.Error("la clave se regeneró en la segunda llamada -- debería reusar la existente")
	}
}

func TestEnsureSelfSignedCertRegeneratesIfKeyMissing(t *testing.T) {
	dir := t.TempDir()
	certPath, keyPath, err := EnsureSelfSignedCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	// Borrar solo la clave (ej. un cleanup a medias) no debe dejar un cert huérfano sin su
	// contraparte -- EnsureSelfSignedCert debe notar que falta y regenerar el par completo.
	if err := os.Remove(keyPath); err != nil {
		t.Fatal(err)
	}
	certPath2, keyPath2, err := EnsureSelfSignedCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	if certPath2 != certPath || keyPath2 != keyPath {
		t.Fatalf("las rutas no deberían cambiar: (%s,%s) vs (%s,%s)", certPath, keyPath, certPath2, keyPath2)
	}
	if _, err := tls.LoadX509KeyPair(certPath2, keyPath2); err != nil {
		t.Fatalf("el par regenerado no es válido: %v", err)
	}
}

func TestEnsureSelfSignedCertFailsOnUnwritableDir(t *testing.T) {
	// Un directorio que no existe (y que EnsureSelfSignedCert no crea él mismo -- esa
	// responsabilidad es del caller, como ya hace os.MkdirAll(*dir, ...) en main.go) debe
	// devolver un error real, nunca fallar en silencio.
	badDir := filepath.Join(t.TempDir(), "no-existe", "mas-profundo")
	if _, _, err := EnsureSelfSignedCert(badDir); err == nil {
		t.Fatal("se esperaba un error al escribir en un directorio inexistente")
	}
}
