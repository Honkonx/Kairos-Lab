package router

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHelperProcess(t *testing.T) {
	if os.Getenv("KAIROS_HELPER_PROCESS") != "1" {
		return
	}
	args := os.Args
	for i, a := range args {
		if a == "--" {
			fmt.Print(strings.Join(args[i+1:], "|"))
			break
		}
	}
	os.Exit(3)
}

func shellAction() Action {
	return Action{
		ID: "eco", Title: "Eco", Risk: "read", Kind: KindShell,
		Exec:   []string{os.Args[0], "-test.run=TestHelperProcess", "--", "{word}", "{extra}"},
		Params: []Param{{Name: "word", Pattern: `[a-z]{1,8}`, Required: true}, {Name: "extra", Pattern: `[0-9]+`}},
	}
}

// validated pasa la acción por el registro, que es quien compila los patrones de sus parámetros.
func validated(t *testing.T, a Action) Action {
	t.Helper()
	reg, err := NewRegistry(Manifest{Actions: []Action{a}})
	if err != nil {
		t.Fatal(err)
	}
	got, _ := reg.Get(a.ID)
	return got
}

func TestUnvalidatedActionFailsClosed(t *testing.T) {
	if err := shellAction().CheckParams(map[string]string{"word": "hola"}); err == nil {
		t.Error("una acción que no pasó por el registro debe rechazar, no entrar en pánico")
	}
}

func TestRegistryValidation(t *testing.T) {
	good := shellAction()
	if _, err := NewRegistry(Manifest{Actions: []Action{good}}); err != nil {
		t.Fatalf("manifiesto válido rechazado: %v", err)
	}
	tests := []struct {
		name string
		mut  func(a *Action)
		want string
	}{
		{"id inválido", func(a *Action) { a.ID = "Eco Raro" }, "id inválido"},
		{"riesgo inválido", func(a *Action) { a.Risk = "meh" }, "riesgo"},
		{"exec relativo", func(a *Action) { a.Exec = []string{"ls"} }, "ruta absoluta"},
		{"parámetro sin patrón", func(a *Action) { a.Params[0].Pattern = "" }, "sin patrón"},
		{"placeholder sin parámetro", func(a *Action) { a.Exec = append(a.Exec, "{nada}") }, "no existe ese parámetro"},
		{"ejecutor desconocido", func(a *Action) { a.Kind = "magia" }, "desconocido"},
		{"timeout fuera de rango", func(a *Action) { a.TimeoutSec = 9999 }, "timeout"},
	}
	for _, tt := range tests {
		a := shellAction()
		a.Params = append([]Param{}, a.Params...)
		tt.mut(&a)
		_, err := NewRegistry(Manifest{Actions: []Action{a}})
		if err == nil || !strings.Contains(err.Error(), tt.want) {
			t.Errorf("%s: error = %v, quiero que contenga %q", tt.name, err, tt.want)
		}
	}
	if _, err := NewRegistry(Manifest{Actions: []Action{good, good}}); err == nil {
		t.Error("ids repetidos deben rechazarse")
	}
}

func TestServicesMustBeLoopback(t *testing.T) {
	ok := []string{"http://127.0.0.1:5678", "http://localhost:8080", "http://[::1]:9000"}
	for _, target := range ok {
		if _, err := NewRegistry(Manifest{Services: []Service{{Name: "n8n", Target: target}}}); err != nil {
			t.Errorf("%s debería aceptarse: %v", target, err)
		}
	}
	bad := []string{"http://192.168.1.10:80", "http://example.com", "ftp://127.0.0.1", "http://user:pw@127.0.0.1", "http://169.254.169.254"}
	for _, target := range bad {
		if _, err := NewRegistry(Manifest{Services: []Service{{Name: "x", Target: target}}}); err == nil {
			t.Errorf("%s debería rechazarse (SSRF)", target)
		}
	}
}

func TestCheckParams(t *testing.T) {
	a := validated(t, shellAction())
	cases := []struct {
		name   string
		values map[string]string
		ok     bool
	}{
		{"válido", map[string]string{"word": "hola"}, true},
		{"válido con opcional", map[string]string{"word": "hola", "extra": "42"}, true},
		{"falta requerido", map[string]string{}, false},
		{"no coincide", map[string]string{"word": "HOLA"}, false},
		{"intento de flag", map[string]string{"word": "-rf"}, false},
		{"salto de línea", map[string]string{"word": "ab\ncd"}, false},
		{"parámetro extra", map[string]string{"word": "hola", "otro": "x"}, false},
	}
	for _, c := range cases {
		if err := a.CheckParams(c.values); (err == nil) != c.ok {
			t.Errorf("%s: err = %v, ok esperado %v", c.name, err, c.ok)
		}
	}
}

func TestArgvDropsMissingOptionalParam(t *testing.T) {
	a := Action{Exec: []string{"/bin/x", "--a={p}", "{q}", "fijo"}}
	got := a.Argv(map[string]string{"p": "1"})
	want := []string{"/bin/x", "--a=1", "fijo"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Errorf("Argv = %v, quiero %v", got, want)
	}
}

func TestShellExecutorRunsWithoutShell(t *testing.T) {
	ex := ShellExecutor{Env: []string{"KAIROS_HELPER_PROCESS=1"}}
	d := Dispatcher{Shell: ex}
	res, err := d.Run(context.Background(), validated(t, shellAction()), map[string]string{"word": "hola", "extra": "7"})
	if err != nil {
		t.Fatal(err)
	}
	// El helper termina con código 3 a propósito: se reporta como no-ok con su salida.
	if res.OK || res.ExitCode != 3 || res.Output != "hola|7" {
		t.Errorf("resultado inesperado: %+v", res)
	}
	// Un valor con metacaracteres de shell no pasa la validación, ni llega a ejecutarse.
	if _, err := d.Run(context.Background(), validated(t, shellAction()), map[string]string{"word": "a;rm"}); err == nil {
		t.Error("un valor con ';' debe rechazarse antes de ejecutar")
	}
}

func TestDispatcherWithoutExecutor(t *testing.T) {
	a := Action{ID: "x", Kind: KindInApp, Target: "t"}
	if _, err := (Dispatcher{}).Run(context.Background(), a, nil); err == nil {
		t.Error("sin ejecutor debe dar error")
	}
}

func TestInAppExecutorForwardsSecretAndParams(t *testing.T) {
	var gotSecret, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotSecret = r.Header.Get("X-Kairos-App-Secret")
		b := make([]byte, 256)
		n, _ := r.Body.Read(b)
		gotBody = string(b[:n])
		fmt.Fprint(w, "hecho")
	}))
	defer srv.Close()
	ex := InAppExecutor{URL: srv.URL, Secret: "s3"}
	a := Action{ID: "mod.iniciar", Risk: "write", Kind: KindInApp, Target: "module.start", Params: []Param{{Name: "id", Pattern: `[a-z0-9]+`, Required: true}}}
	reg, err := NewRegistry(Manifest{Actions: []Action{a}})
	if err != nil {
		t.Fatal(err)
	}
	act, _ := reg.Get("mod.iniciar")
	res, err := Dispatcher{InApp: ex}.Run(context.Background(), act, map[string]string{"id": "n8n"})
	if err != nil || !res.OK || res.Output != "hecho" {
		t.Fatalf("res = %+v, err = %v", res, err)
	}
	if gotSecret != "s3" || !strings.Contains(gotBody, `"target":"module.start"`) || !strings.Contains(gotBody, `"id":"n8n"`) {
		t.Errorf("la app recibió secreto=%q cuerpo=%q", gotSecret, gotBody)
	}
}

func TestNativeExecutor(t *testing.T) {
	ex := NativeExecutor{Funcs: BuiltinFuncs("test")}
	res, err := ex.Run(context.Background(), Action{Target: "gateway.version"}, nil)
	if err != nil || !res.OK || !strings.Contains(res.Output, "kairos-gateway test") {
		t.Errorf("version: %+v, %v", res, err)
	}
	if _, err := ex.Run(context.Background(), Action{Target: "no.existe"}, nil); err == nil {
		t.Error("una función desconocida debe dar error")
	}
}

func TestParamMaxLenDefaultsAndOverrides(t *testing.T) {
	a := Action{ID: "x", Risk: "read", Kind: KindNative, Target: "gateway.version", Params: []Param{
		{Name: "short", Pattern: `(?s).*`, Required: true},
		{Name: "long", Pattern: `(?s).*`, Required: true, MaxLen: 2000},
	}}
	reg, err := NewRegistry(Manifest{Actions: []Action{a}})
	if err != nil {
		t.Fatal(err)
	}
	got, _ := reg.Get("x")
	// Sin max_len declarado, el tope por defecto (512) sigue aplicando.
	if err := got.CheckParams(map[string]string{"short": strings.Repeat("a", 513), "long": "x"}); err == nil {
		t.Error("un parámetro sin max_len debe rechazar más de 512 caracteres")
	}
	if err := got.CheckParams(map[string]string{"short": "ok", "long": "x"}); err != nil {
		t.Errorf("512 o menos debe aceptarse: %v", err)
	}
	// Con max_len propio, puede superar los 512 por defecto.
	if err := got.CheckParams(map[string]string{"short": "ok", "long": strings.Repeat("b", 1500)}); err != nil {
		t.Errorf("un parámetro con max_len=2000 debe aceptar 1500 caracteres: %v", err)
	}
	if err := got.CheckParams(map[string]string{"short": "ok", "long": strings.Repeat("b", 2001)}); err == nil {
		t.Error("un parámetro con max_len=2000 debe rechazar más de 2000 caracteres")
	}
}

func TestParamMaxLenCannotExceedTheHardCeiling(t *testing.T) {
	a := Action{ID: "x", Risk: "read", Kind: KindNative, Target: "gateway.version", Params: []Param{
		{Name: "p", Pattern: `(?s).*`, MaxLen: maxParamMaxLen + 1},
	}}
	if _, err := NewRegistry(Manifest{Actions: []Action{a}}); err == nil || !strings.Contains(err.Error(), "max_len") {
		t.Errorf("un max_len por encima del techo absoluto debe rechazarse, err=%v", err)
	}
}

func TestReadThermal(t *testing.T) {
	root := t.TempDir()
	zone := filepath.Join(root, "thermal_zone0")
	if err := os.MkdirAll(zone, 0o755); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(zone, "type"), []byte("cpu-0\n"), 0o644)
	os.WriteFile(filepath.Join(zone, "temp"), []byte("41250\n"), 0o644)
	got, err := ReadThermal(root)
	if err != nil || got != "cpu-0=41.2" && got != "cpu-0=41.3" {
		t.Errorf("ReadThermal = %q, %v", got, err)
	}
	if _, err := ReadThermal(t.TempDir()); err == nil {
		t.Error("sin zonas debe dar error claro, no un valor inventado")
	}
}

func TestPortCheck(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	openPort := ln.Addr().(*net.TCPAddr).Port

	got, err := PortCheck(context.Background(), fmt.Sprintf("%d", openPort))
	if err != nil || !strings.Contains(got, `"open":true`) {
		t.Errorf("puerto abierto: PortCheck = %q, %v", got, err)
	}

	ln.Close() // libera el puerto -- ahora nadie escucha ahí
	got, err = PortCheck(context.Background(), fmt.Sprintf("%d", openPort))
	if err != nil || !strings.Contains(got, `"open":false`) {
		t.Errorf("puerto cerrado: PortCheck = %q, %v", got, err)
	}

	if _, err := PortCheck(context.Background(), "no-es-un-numero"); err == nil {
		t.Error("puerto inválido debe dar error, no un resultado inventado")
	}
	if _, err := PortCheck(context.Background(), "99999"); err == nil {
		t.Error("puerto fuera de rango debe dar error")
	}
}
