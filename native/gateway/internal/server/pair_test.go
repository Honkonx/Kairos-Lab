package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/honkonx/kairos-dev/native/gateway/internal/pending"
	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
)

func TestPairRequestApprovedIssuesWorkingToken(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	go func() {
		for i := 0; i < 200; i++ {
			if list := e.cfg.Pending.List(); len(list) > 0 && list[0].Request.ActionID == "gateway.pair_request" {
				e.cfg.Pending.Decide(list[0].Request.ID, true)
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()

	resp, err := http.Post(e.srv.URL+"/v1/pair", "application/json", strings.NewReader(`{"name":"PC de prueba"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var body map[string]any
	json.NewDecoder(resp.Body).Decode(&body)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair aprobado = %d %v", resp.StatusCode, body)
	}
	if body["state"] != "aceptado" {
		t.Fatalf("state = %v", body)
	}
	if body["level"] != "pregunta" {
		t.Errorf("nivel del token nuevo = %v, quería \"pregunta\" (Ask) por defecto", body["level"])
	}
	tokenRaw, _ := body["token"].(string)
	if tokenRaw == "" {
		t.Fatal("no se devolvió ningún token")
	}

	// El token recién emitido debería funcionar de verdad, no solo "parecer" uno.
	if code, _ := e.do(t, "GET", "/v1/me", tokenRaw, "", ""); code != http.StatusOK {
		t.Errorf("el token emitido por pairing no autentica, código %d", code)
	}
}

func TestPairRequestDenied(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	go func() {
		for i := 0; i < 200; i++ {
			if list := e.cfg.Pending.List(); len(list) > 0 {
				e.cfg.Pending.Decide(list[0].Request.ID, false)
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()

	resp, err := http.Post(e.srv.URL+"/v1/pair", "application/json", strings.NewReader(`{"name":"PC rechazada"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("pair rechazado = %d, quiero 403", resp.StatusCode)
	}
}

func TestPairRequestValidatesName(t *testing.T) {
	// Un servidor (y su enfriamiento por IP) distinto por caso -- el enfriamiento es por diseño
	// (ver TestPairCooldownBlocksRepeatedRequestsFromSameAddr) y bloquearía el segundo pedido de
	// este mismo test si compartieran servidor, sin que eso sea el bug que este test busca.
	cases := map[string]string{"vacío": `""`, "solo espacios": `"   "`, "muy largo": `"` + strings.Repeat("a", 65) + `"`}
	for label, nameJSON := range cases {
		t.Run(label, func(t *testing.T) {
			e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
			resp, err := http.Post(e.srv.URL+"/v1/pair", "application/json", strings.NewReader(`{"name":`+nameJSON+`}`))
			if err != nil {
				t.Fatal(err)
			}
			resp.Body.Close()
			if resp.StatusCode != http.StatusBadRequest {
				t.Errorf("nombre %s = %d, quiero 400", nameJSON, resp.StatusCode)
			}
		})
	}
}

func TestPairCooldownBlocksRepeatedRequestsFromSameAddr(t *testing.T) {
	c := newPairCooldown()
	if !c.allow("1.2.3.4:5555") {
		t.Fatal("el primer pedido de una IP debería permitirse")
	}
	if c.allow("1.2.3.4:5555") {
		t.Error("un segundo pedido inmediato de la MISMA IP debería bloquearse")
	}
	if !c.allow("9.9.9.9:1111") {
		t.Error("otra IP distinta no debería verse afectada por el enfriamiento de la primera")
	}
}

// TestPairCooldownIgnoresEphemeralPort — hallazgo de revisión automática ("rate-limit bypass"):
// la primera versión indexaba por RemoteAddr completo (con puerto efímero, distinto en cada
// conexión TCP nueva) — cualquiera podía esquivar el enfriamiento solo abriendo una conexión
// nueva. El mismo HOST con puertos efímeros distintos debe seguir bloqueado.
func TestPairCooldownIgnoresEphemeralPort(t *testing.T) {
	c := newPairCooldown()
	if !c.allow("1.2.3.4:11111") {
		t.Fatal("el primer pedido debería permitirse")
	}
	if c.allow("1.2.3.4:22222") {
		t.Error("el mismo host con OTRO puerto efímero debería seguir bloqueado por el enfriamiento")
	}
}

// TestPairCooldownTruncatesIPv6ToSlash64 — hallazgo de revisión automática ("key the cooldown by
// IPv6 /64 prefix"): un solo bloque IPv6 delegado (trivial de conseguir, a diferencia de IPv4)
// alcanza para generar direcciones EXACTAS distintas y esquivar un enfriamiento por dirección
// completa. Dos direcciones del MISMO /64 deben compartir el enfriamiento.
func TestPairCooldownTruncatesIPv6ToSlash64(t *testing.T) {
	c := newPairCooldown()
	if !c.allow("[2001:db8:1234:5678::1]:11111") {
		t.Fatal("el primer pedido debería permitirse")
	}
	if c.allow("[2001:db8:1234:5678:aaaa:bbbb:cccc:dddd]:22222") {
		t.Error("otra dirección del MISMO /64 debería seguir bloqueada por el enfriamiento")
	}
	if !c.allow("[2001:db8:9999::1]:33333") {
		t.Error("un /64 DISTINTO no debería verse afectado por el enfriamiento del primero")
	}
}

// TestPairRequestsDoNotBlockEachOther — hallazgo de revisión automática ("unauthenticated DoS /
// pairing pre-emption"): una versión anterior rechazaba un pairing nuevo mientras hubiera uno
// pendiente — un cliente sin autenticar podía ocupar ese único cupo con pedidos basura en bucle y
// trabar el pairing para CUALQUIER dispositivo real, indefinidamente. Confirma que dos pairings
// pendientes a la vez SÍ coexisten (dos notificaciones independientes) y cada uno se decide por
// su cuenta sin afectar al otro.
func TestPairRequestsDoNotBlockEachOther(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})

	first := e.cfg.Pending.Create(pending.Request{ActionID: "gateway.pair_request", Title: "¿Aceptar esta PC?", Params: map[string]string{"name": "primera"}})
	second := e.cfg.Pending.Create(pending.Request{ActionID: "gateway.pair_request", Title: "¿Aceptar esta PC?", Params: map[string]string{"name": "segunda"}})
	if first.ID == second.ID {
		t.Fatal("dos pairings distintos no deberían compartir id")
	}
	list := e.cfg.Pending.List()
	if len(list) != 2 {
		t.Fatalf("deberían coexistir 2 pairings pendientes, hay %d", len(list))
	}

	// Decidir uno no afecta al otro -- cada notificación se resuelve por su cuenta.
	e.cfg.Pending.Decide(first.ID, false)
	snapSecond, ok := e.cfg.Pending.Get(second.ID)
	if !ok || snapSecond.State != pending.StatePending {
		t.Errorf("el segundo pairing debería seguir pendiente tras decidir el primero, quedó %v", snapSecond.State)
	}
	e.cfg.Pending.Decide(second.ID, false)
}

// TestPairRequestIncludesSourceAddress — hallazgo de revisión automática ("approval spoofing"):
// la persona debe ver DE DÓNDE viene el pedido, no solo el nombre que esa conexión eligió
// mostrar.
func TestPairRequestIncludesSourceAddress(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	go http.Post(e.srv.URL+"/v1/pair", "application/json", strings.NewReader(`{"name":"x"}`))
	deadline := time.Now().Add(2 * time.Second)
	var found bool
	for time.Now().Before(deadline) {
		if list := e.cfg.Pending.List(); len(list) > 0 {
			if list[0].Request.Params["desde"] == "" {
				t.Error("la petición pendiente no incluye el origen (\"desde\")")
			} else {
				found = true
			}
			e.cfg.Pending.Decide(list[0].Request.ID, false)
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !found {
		t.Fatal("el pairing nunca quedó pendiente")
	}
}

// TestPairRequestCapsConcurrentPairings — hallazgo de revisión automática ("approval spoofing /
// unbounded unauthenticated pending requests"): tras sacar el límite de "uno a la vez" (ver
// TestPairRequestsDoNotBlockEachOther), nada impedía que se acumularan sin fin. Con
// [maxConcurrentPairings] ya ocupado, un pedido nuevo se rechaza (429) en vez de sumar otro.
func TestPairRequestCapsConcurrentPairings(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	for i := 0; i < maxConcurrentPairings; i++ {
		e.cfg.Pending.Create(pending.Request{ActionID: "gateway.pair_request", Title: "¿Aceptar esta PC?", Params: map[string]string{"name": "ya pendiente"}})
	}
	resp, err := http.Post(e.srv.URL+"/v1/pair", "application/json", strings.NewReader(`{"name":"una de más"}`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Errorf("pairing con el tope ya ocupado = %d, quiero 429", resp.StatusCode)
	}
}

// TestPairRequestPerOriginCapBlocksSingleOriginHoggingGlobalSlots — hallazgo de revisión
// automática ("global cap defeatable by a single origin"): un tope global solo (ver
// TestPairRequestCapsConcurrentPairings) no evita que UN origen ocupe el tope entero él solo y
// siga bloqueando a los demás. Acá el tope global (3) NI SE ACERCA a llenarse (hay 1 pendiente),
// pero ese 1 ya es del MISMO origen que hace el pedido nuevo -- debe rechazarse igual, por el
// tope por origen.
func TestPairRequestPerOriginCapBlocksSingleOriginHoggingGlobalSlots(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	u, err := url.Parse(e.srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	sameOrigin := cooldownKey(u.Hostname() + ":0") // mismo host que va a usar el POST real de abajo
	e.cfg.Pending.Create(pending.Request{ActionID: "gateway.pair_request", Title: "¿Aceptar esta PC?", Params: map[string]string{"name": "ya pendiente", "origen": sameOrigin}})

	resp, err := http.Post(e.srv.URL+"/v1/pair", "application/json", strings.NewReader(`{"name":"segunda del mismo origen"}`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Errorf("segundo pairing del MISMO origen, con el tope global lejos de llenarse = %d, quiero 429", resp.StatusCode)
	}
}

// TestPairRequestFreesSlotOnClientDisconnect — hallazgo de revisión automática ("on client
// disconnect, mark the pending entry expired/denied so it frees its slot"): si el cliente HTTP se
// va antes de que alguien decida, el pending no debería quedar ocupando el cupo hasta que expire
// solo (hasta 2 minutos) -- se libera apenas se nota la desconexión.
func TestPairRequestFreesSlotOnClientDisconnect(t *testing.T) {
	e := newEnv(t, permission.Policy{Max: permission.Full, Responsible: true})
	ctx, cancel := context.WithCancel(context.Background())
	req, err := http.NewRequestWithContext(ctx, "POST", e.srv.URL+"/v1/pair", strings.NewReader(`{"name":"se va a ir"}`))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	go http.DefaultClient.Do(req)

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && len(e.cfg.Pending.List()) == 0 {
		time.Sleep(10 * time.Millisecond)
	}
	if len(e.cfg.Pending.List()) == 0 {
		t.Fatal("el pairing nunca quedó pendiente")
	}

	cancel() // el cliente "se desconecta" antes de que nadie decida

	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && len(e.cfg.Pending.List()) != 0 {
		time.Sleep(10 * time.Millisecond)
	}
	if list := e.cfg.Pending.List(); len(list) != 0 {
		t.Errorf("el cupo debería liberarse apenas el cliente se desconecta, sigue ocupado: %v", list)
	}
}
