package router

import (
	"errors"
	"fmt"
	"net"
	"net/url"
)

// validateService exige que el destino del proxy sea http(s) en loopback: el gateway nunca debe
// servir de puente hacia otras máquinas (SSRF).
func validateService(s Service) error {
	if !idPattern.MatchString(s.Name) {
		return errors.New("nombre inválido")
	}
	u, err := url.Parse(s.Target)
	if err != nil {
		return fmt.Errorf("destino inválido: %w", err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return errors.New("el destino debe ser http o https")
	}
	host := u.Hostname()
	if host != "localhost" {
		ip := net.ParseIP(host)
		if ip == nil || !ip.IsLoopback() {
			return fmt.Errorf("el destino %q no es loopback", host)
		}
	}
	if u.User != nil {
		return errors.New("el destino no puede llevar usuario y clave")
	}
	return nil
}
