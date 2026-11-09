package server

import (
	"sync"
	"time"
)

// actionRateLimitMax/actionRateLimitWindow: techo real de ejecuciones por token en una ventana
// corta. Hallazgo real de docs/referencias/pc-movil/: "rate limiting por token en /v1/actions --
// hoy no hay ninguno" -- un token comprometido, o un bug de loop del lado cliente, podía
// disparar ejecuciones reales sin ningún límite. 30 en 10s alcanza de sobra para uso normal
// (ningún flujo real de la app dispara acciones en rápida sucesión) sin estorbar a nadie.
const (
	actionRateLimitMax    = 30
	actionRateLimitWindow = 10 * time.Second
)

// actionRateLimiter es una ventana deslizante simple por token -- no un token-bucket con refill
// continuo (más código para un beneficio marginal acá, mismo criterio de simplicidad que
// [pairCooldown] en pair.go). Cuenta los intentos de cada token dentro de la ventana y corta los
// que se pasen del techo; las marcas viejas se purgan en la propia llamada, sin una goroutine de
// limpieza aparte.
type actionRateLimiter struct {
	mu     sync.Mutex
	window time.Duration
	max    int
	hits   map[string][]time.Time
}

func newActionRateLimiter(max int, window time.Duration) *actionRateLimiter {
	return &actionRateLimiter{window: window, max: max, hits: map[string][]time.Time{}}
}

// allow registra un intento de tokenID ahora mismo; devuelve false si ese token ya alcanzó el
// techo dentro de la ventana (y NO registra el intento rechazado -- solo cuenta los permitidos).
func (l *actionRateLimiter) allow(tokenID string) bool {
	now := time.Now()
	cutoff := now.Add(-l.window)
	l.mu.Lock()
	defer l.mu.Unlock()
	kept := l.hits[tokenID][:0]
	for _, t := range l.hits[tokenID] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= l.max {
		l.hits[tokenID] = kept
		return false
	}
	l.hits[tokenID] = append(kept, now)
	return true
}
