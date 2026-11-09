// Package permission define los niveles de permiso del gateway y la matriz que decide si una
// acción se ejecuta, se pregunta al usuario o se rechaza.
//
// Niveles (orden del usuario): lectura, pregunta, semi-total y total.
// Un nivel por encima de "pregunta" solo vale si el usuario marcó la casilla de responsabilidad.
package permission

import (
	"fmt"
	"strings"
)

// Level es el nivel de permiso de un token o el máximo que permite la política.
type Level int

const (
	// Read solo permite acciones de lectura.
	Read Level = iota
	// Ask permite todo, pero cada acción que cambia algo se confirma con el usuario.
	Ask
	// Semi ejecuta sin preguntar lo que cambia datos; pregunta solo en acciones peligrosas.
	Semi
	// Full ejecuta todo sin preguntar.
	Full
)

var levelNames = [...]string{"lectura", "pregunta", "semi-total", "total"}

// String devuelve el nombre del nivel tal como se escribe en la API y en los archivos.
func (l Level) String() string {
	if l < Read || l > Full {
		return fmt.Sprintf("desconocido(%d)", int(l))
	}
	return levelNames[l]
}

// ParseLevel interpreta el nombre de un nivel.
func ParseLevel(s string) (Level, error) {
	s = strings.ToLower(strings.TrimSpace(s))
	for i, name := range levelNames {
		if s == name {
			return Level(i), nil
		}
	}
	return Read, fmt.Errorf("nivel de permiso desconocido %q (válidos: %s)", s, strings.Join(levelNames[:], ", "))
}

// MarshalText y UnmarshalText permiten usar Level directamente en JSON.
func (l Level) MarshalText() ([]byte, error) { return []byte(l.String()), nil }

func (l *Level) UnmarshalText(b []byte) error {
	v, err := ParseLevel(string(b))
	if err != nil {
		return err
	}
	*l = v
	return nil
}

// Risk clasifica lo que una acción puede hacerle al dispositivo.
type Risk int

const (
	RiskRead Risk = iota
	RiskWrite
	RiskDanger
)

var riskNames = [...]string{"read", "write", "danger"}

func (r Risk) String() string {
	if r < RiskRead || r > RiskDanger {
		return fmt.Sprintf("desconocido(%d)", int(r))
	}
	return riskNames[r]
}

// ParseRisk interpreta "read", "write" o "danger".
func ParseRisk(s string) (Risk, error) {
	s = strings.ToLower(strings.TrimSpace(s))
	for i, name := range riskNames {
		if s == name {
			return Risk(i), nil
		}
	}
	return RiskDanger, fmt.Errorf("riesgo desconocido %q (válidos: %s)", s, strings.Join(riskNames[:], ", "))
}

// Decision es lo que el gateway debe hacer con una petición.
type Decision int

const (
	Deny Decision = iota
	AskUser
	Allow
)

func (d Decision) String() string {
	switch d {
	case Deny:
		return "denegada"
	case AskUser:
		return "pregunta"
	default:
		return "permitida"
	}
}

// Policy es la política global que fija el usuario desde la app.
type Policy struct {
	// Max es el techo: ningún token supera este nivel.
	Max Level `json:"max"`
	// Responsible indica que el usuario aceptó la casilla "soy responsable de lo que pase".
	// Sin ella, los niveles semi-total y total se reducen a pregunta.
	Responsible bool `json:"responsible"`
}

// Effective devuelve el nivel real de un token bajo esta política.
func (p Policy) Effective(token Level) Level {
	lvl := token
	if p.Max < lvl {
		lvl = p.Max
	}
	if !p.Responsible && lvl > Ask {
		return Ask
	}
	return lvl
}

// Decide aplica la matriz nivel × riesgo.
//
//	lectura:    lectura→permitida; escritura/peligro→denegada
//	pregunta:   lectura→permitida; escritura/peligro→pregunta
//	semi-total: lectura/escritura→permitida; peligro→pregunta
//	total:      todo permitido
func Decide(level Level, risk Risk) Decision {
	switch level {
	case Read:
		if risk == RiskRead {
			return Allow
		}
		return Deny
	case Ask:
		if risk == RiskRead {
			return Allow
		}
		return AskUser
	case Semi:
		if risk == RiskDanger {
			return AskUser
		}
		return Allow
	case Full:
		return Allow
	default:
		return Deny
	}
}
