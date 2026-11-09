package server

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

// EnsureSelfSignedCert devuelve la ruta a un certificado/clave TLS autofirmados dentro de
// [dir], generándolos si no existen todavía. Hallazgo real de seguridad (docs/referencias/
// homelab/AUDITORIA_homelab_2026-10-09.md, reauditada 2026-11-02): "TLS en el listener LAN del
// gateway -- el token viaja en texto plano por WiFi local". Un certificado autofirmado (sin
// autoridad real detrás, imposible de tener para una IP de LAN que cambia de red en red) sigue
// siendo mejor que texto plano: cifra el canal contra un atacante pasivo en la misma red (el
// ataque real y barato -- un sniffer de paquetes), aunque no protege contra un
// man-in-the-middle activo que también pueda inyectar su propio certificado (ese nivel de
// confianza ya lo da el TOKEN en sí, que sigue siendo obligatorio -- TLS acá es defensa en
// profundidad del canal, no un sustituto de la autenticación).
//
// Válido 10 años (sin necesidad de renovación automática -- es autofirmado, nadie lo revoca) y
// cubre `127.0.0.1`, `::1`, y el hostname `kairos-gateway` como Subject Alternative Names; los
// clientes (kairos-dashboard/la app) se conectan por IP de LAN directa, así que de todas formas
// necesitan `InsecureSkipVerify` (no hay forma de que un cert emitido de antemano conozca la IP
// de LAN real antes de que el dispositivo se conecte a una red) -- el hostname en el cert no se
// usa para validar, solo para que el cert sea sintácticamente completo.
func EnsureSelfSignedCert(dir string) (certPath, keyPath string, err error) {
	certPath = filepath.Join(dir, "gateway_cert.pem")
	keyPath = filepath.Join(dir, "gateway_key.pem")
	if _, errCert := os.Stat(certPath); errCert == nil {
		if _, errKey := os.Stat(keyPath); errKey == nil {
			return certPath, keyPath, nil
		}
	}

	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", fmt.Errorf("generando clave TLS: %w", err)
	}
	serial, err := rand.Int(rand.Reader, big.NewInt(1<<62))
	if err != nil {
		return "", "", fmt.Errorf("generando número de serie: %w", err)
	}
	template := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{Organization: []string{"Kairos Gateway (autofirmado)"}, CommonName: "kairos-gateway"},
		NotBefore:             time.Now().Add(-1 * time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{"kairos-gateway"},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &priv.PublicKey, priv)
	if err != nil {
		return "", "", fmt.Errorf("creando certificado: %w", err)
	}
	keyDER, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return "", "", fmt.Errorf("serializando clave: %w", err)
	}

	certOut, err := os.OpenFile(certPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return "", "", fmt.Errorf("escribiendo %s: %w", certPath, err)
	}
	defer certOut.Close()
	if err := pem.Encode(certOut, &pem.Block{Type: "CERTIFICATE", Bytes: der}); err != nil {
		return "", "", fmt.Errorf("codificando certificado: %w", err)
	}

	keyOut, err := os.OpenFile(keyPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return "", "", fmt.Errorf("escribiendo %s: %w", keyPath, err)
	}
	defer keyOut.Close()
	if err := pem.Encode(keyOut, &pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}); err != nil {
		return "", "", fmt.Errorf("codificando clave: %w", err)
	}

	return certPath, keyPath, nil
}
