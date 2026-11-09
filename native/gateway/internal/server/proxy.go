package server

import (
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"

	"github.com/honkonx/kairos-dev/native/gateway/internal/auth"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
)

// isUpgrade dice si la petición pide cambiar de protocolo (WebSocket u otro). Mira la cabecera
// Upgrade y el token "upgrade" dentro de Connection, que puede venir en una lista ("keep-alive, Upgrade").
func isUpgrade(r *http.Request) bool {
	if r.Header.Get("Upgrade") != "" {
		return true
	}
	for _, v := range r.Header.Values("Connection") {
		for _, part := range strings.Split(v, ",") {
			if strings.EqualFold(strings.TrimSpace(part), "upgrade") {
				return true
			}
		}
	}
	return false
}

// proxyCookieName — una cookie por servicio, nunca una sola compartida (si dos servicios tuvieran
// la misma, cerrar sesión de uno invalidaría el acceso al otro sin necesidad real).
func proxyCookieName(service string) string { return "kairos_proxy_" + service }

// sameOrigin confirma que el header Origin (si vino) apunta al propio gateway — mitiga que la
// cookie de [proxyCookieName] se use como credencial ambiente desde otro origen (hallazgo de
// revisión automática, 2026-10-26: "CSRF / cross-service ambient credential", ver comentario de
// [handleProxy]). Un navegador normal siempre manda Origin en fetch/XHR/WebSocket de otro
// origen; si no lo manda (navegación GET simple top-level, o un cliente viejo) no hay Origin que
// chequear — para ESE caso el scope de SameSite=Strict + Path ya es la defensa real.
func sameOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	return err == nil && u.Host == r.Host
}

// authenticateProxy es como [Server.authenticate] pero además acepta la cookie puesta por
// [handleProxy] la primera vez, y devuelve el valor crudo usado (para poder guardarlo en la
// cookie si vino por query string). Separado de authenticate() porque ESE se usa en rutas que no
// tienen noción de "servicio" (una cookie por nombre no aplicaría ahí).
//
// La cookie SOLO se acepta en un GET simple (igual que el fallback de query string, que ya tenía
// esa restricción) o en un Upgrade/no-GET cuyo Origin coincide con este mismo host — un pedido de
// escritura o un WebSocket que trae la cookie "ambiente" desde otro origen se rechaza acá, antes
// de llegar a la decisión de permiso.
func (s *Server) authenticateProxy(r *http.Request, service string) (auth.Token, string, bool) {
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		raw := strings.TrimSpace(h[len("Bearer "):])
		t, ok := s.cfg.Tokens.Authenticate(raw)
		return t, raw, ok
	}
	if c, err := r.Cookie(proxyCookieName(service)); err == nil {
		// Un Upgrade (WebSocket) es técnicamente un GET, pero abre un canal bidireccional — se
		// exige el mismo chequeo de Origin que cualquier escritura, no alcanza con "es GET".
		if (r.Method != http.MethodGet || isUpgrade(r)) && !sameOrigin(r) {
			return auth.Token{}, "", false
		}
		t, ok := s.cfg.Tokens.Authenticate(c.Value)
		return t, c.Value, ok
	}
	if r.Method == http.MethodGet {
		raw := r.URL.Query().Get("token")
		t, ok := s.cfg.Tokens.Authenticate(raw)
		return t, raw, ok
	}
	return auth.Token{}, "", false
}

// handleProxy reenvía la petición a un servicio local declarado en el manifiesto
// (/proxy/<servicio>/<ruta>). Lectura (GET/HEAD/OPTIONS) cuenta como riesgo de lectura; cualquier
// otro método como escritura. El proxy no puede pedir confirmación: si el nivel exige preguntar,
// se rechaza y el cliente debe usar una acción.
//
// Hallazgo de seguridad real (revisión automática 2026-10-26,): el link
// "Abrir" de cada servicio llevaba el token en la query string — queda en el historial del
// navegador y en cualquier log de acceso de un túnel en el medio, para siempre. Fix: la PRIMERA
// vez que el token llega por query string (clic en el link), se guarda en una cookie
// HttpOnly+SameSite=Strict con alcance solo a /proxy/<servicio>/ y se redirige (302) a la MISMA
// URL sin el token — lo que queda en el historial/marcadores del navegador es la URL limpia.
// Pedidos siguientes (recursos de la página, WebSocket del mismo origen) ya mandan la cookie
// solos, sin volver a necesitar el token en la URL.
func (s *Server) handleProxy(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	tok, rawToken, ok := s.authenticateProxy(r, name)
	if !ok {
		writeError(w, http.StatusUnauthorized, "token inválido")
		return
	}
	svc, found := s.cfg.Registry.Service(name)
	if !found {
		writeError(w, http.StatusNotFound, "servicio desconocido")
		return
	}

	risk := permission.RiskWrite
	switch r.Method {
	case http.MethodGet, http.MethodHead, http.MethodOptions:
		// Un GET con "Upgrade" (WebSocket) abre un canal bidireccional: después del cambio de
		// protocolo el cliente puede escribir, así que cuenta como escritura (hallazgo de la
		// revisión de seguridad automática, 2026-10-09).
		if !isUpgrade(r) {
			risk = permission.RiskRead
		}
	}
	level := s.effective(tok)
	decision := permission.Decide(level, risk)
	s.record(tok.ID, "proxy:"+svc.Name, level, decision.String(), r.Method)
	if decision != permission.Allow {
		writeError(w, http.StatusForbidden, "tu nivel de permiso ("+level.String()+") no permite "+r.Method+" por el proxy")
		return
	}

	// El token llegó por query string (clic en un link, nunca Authorization/cookie) — se guarda
	// en una cookie scoped a este servicio y se redirige a la URL sin el token, para que nunca
	// más quede expuesto en el historial/marcadores/logs de acceso. No aplica a un Upgrade (la
	// negociación de WebSocket no sigue redirects) — para ESE caso la cookie ya tiene que existir
	// de una carga de página anterior del mismo servicio.
	if r.Method == http.MethodGet && !isUpgrade(r) && r.URL.Query().Get("token") != "" {
		http.SetCookie(w, &http.Cookie{
			Name: proxyCookieName(name), Value: rawToken, Path: "/proxy/" + name + "/",
			HttpOnly: true, SameSite: http.SameSiteStrictMode, Secure: r.TLS != nil,
		})
		q := r.URL.Query()
		q.Del("token")
		clean := r.URL.Path
		if enc := q.Encode(); enc != "" {
			clean += "?" + enc
		}
		http.Redirect(w, r, clean, http.StatusFound)
		return
	}

	target, err := url.Parse(svc.Target) // ya validado como loopback al cargar el manifiesto
	if err != nil {
		writeError(w, http.StatusInternalServerError, "destino inválido")
		return
	}
	path := "/" + r.PathValue("path")
	proxy := &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			pr.SetURL(target)
			pr.Out.URL.Path = path
			pr.Out.URL.RawPath = ""
			q := pr.In.URL.Query()
			q.Del("token") // el token del gateway no debe llegar al servicio
			pr.Out.URL.RawQuery = q.Encode()
			pr.Out.Header.Del("Authorization")
			pr.Out.Header.Del("X-Kairos-Admin")
			// Hallazgo real de revisión automática (2026-10-26): la cookie de este proxy
			// ([proxyCookieName]) NO debe llegar nunca al servicio de verdad — es la credencial
			// del gateway, no suya. Se reconstruye el header Cookie sin ella; las cookies
			// PROPIAS del servicio (su propia sesión) sí viajan tal cual.
			if cookies := pr.In.Cookies(); len(cookies) > 0 {
				kept := make([]string, 0, len(cookies))
				for _, c := range cookies {
					if c.Name == proxyCookieName(name) {
						continue
					}
					kept = append(kept, (&http.Cookie{Name: c.Name, Value: c.Value}).String())
				}
				if len(kept) > 0 {
					pr.Out.Header.Set("Cookie", strings.Join(kept, "; "))
				} else {
					pr.Out.Header.Del("Cookie")
				}
			}
			pr.Out.Host = target.Host
		},
		FlushInterval: -1, // respuestas en streaming (SSE, WebSocket) sin esperar a llenar el búfer
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, err error) {
			s.cfg.Logger.Warn("el servicio local no respondió", "servicio", svc.Name, "error", err)
			writeError(w, http.StatusBadGateway, "el servicio "+svc.Name+" no responde")
		},
	}
	proxy.ServeHTTP(w, r)
}
