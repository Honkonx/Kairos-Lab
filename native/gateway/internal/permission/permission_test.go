package permission

import (
	"encoding/json"
	"testing"
)

func TestDecideMatrix(t *testing.T) {
	tests := []struct {
		level Level
		risk  Risk
		want  Decision
	}{
		{Read, RiskRead, Allow},
		{Read, RiskWrite, Deny},
		{Read, RiskDanger, Deny},
		{Ask, RiskRead, Allow},
		{Ask, RiskWrite, AskUser},
		{Ask, RiskDanger, AskUser},
		{Semi, RiskRead, Allow},
		{Semi, RiskWrite, Allow},
		{Semi, RiskDanger, AskUser},
		{Full, RiskRead, Allow},
		{Full, RiskWrite, Allow},
		{Full, RiskDanger, Allow},
	}
	for _, tt := range tests {
		if got := Decide(tt.level, tt.risk); got != tt.want {
			t.Errorf("Decide(%v, %v) = %v, quiero %v", tt.level, tt.risk, got, tt.want)
		}
	}
}

func TestDecideUnknownLevelDenies(t *testing.T) {
	if got := Decide(Level(99), RiskRead); got != Deny {
		t.Fatalf("un nivel desconocido debe denegar, dio %v", got)
	}
}

func TestEffectiveClampsToMaxAndResponsibility(t *testing.T) {
	tests := []struct {
		name   string
		policy Policy
		token  Level
		want   Level
	}{
		{"el techo manda", Policy{Max: Ask, Responsible: true}, Full, Ask},
		{"sin responsabilidad no pasa de pregunta", Policy{Max: Full, Responsible: false}, Full, Ask},
		{"sin responsabilidad, lectura se mantiene", Policy{Max: Full, Responsible: false}, Read, Read},
		{"con responsabilidad, total", Policy{Max: Full, Responsible: true}, Full, Full},
		{"token menor que el techo", Policy{Max: Full, Responsible: true}, Semi, Semi},
	}
	for _, tt := range tests {
		if got := tt.policy.Effective(tt.token); got != tt.want {
			t.Errorf("%s: Effective = %v, quiero %v", tt.name, got, tt.want)
		}
	}
}

func TestParseLevelAndJSONRoundTrip(t *testing.T) {
	for _, name := range []string{"lectura", "pregunta", "semi-total", "total"} {
		lvl, err := ParseLevel(" " + name + " ")
		if err != nil {
			t.Fatalf("ParseLevel(%q): %v", name, err)
		}
		if lvl.String() != name {
			t.Errorf("String() = %q, quiero %q", lvl.String(), name)
		}
	}
	if _, err := ParseLevel("root"); err == nil {
		t.Error("un nivel inventado debe dar error")
	}

	raw, err := json.Marshal(Policy{Max: Semi, Responsible: true})
	if err != nil {
		t.Fatal(err)
	}
	var back Policy
	if err := json.Unmarshal(raw, &back); err != nil {
		t.Fatalf("Unmarshal(%s): %v", raw, err)
	}
	if back.Max != Semi || !back.Responsible {
		t.Errorf("ida y vuelta falló: %+v", back)
	}
	if err := json.Unmarshal([]byte(`{"max":"todo"}`), &back); err == nil {
		t.Error("un nivel inválido en JSON debe dar error")
	}
}

func TestParseRisk(t *testing.T) {
	if r, err := ParseRisk("DANGER"); err != nil || r != RiskDanger {
		t.Errorf("ParseRisk(DANGER) = %v, %v", r, err)
	}
	if _, err := ParseRisk("meh"); err == nil {
		t.Error("un riesgo inventado debe dar error")
	}
}
