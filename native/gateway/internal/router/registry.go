// Package router contiene el catálogo de acciones permitidas y los ejecutores que las llevan a cabo.
//
// Nunca se ejecutan comandos libres: cada acción está declarada en el manifiesto con su riesgo, su
// ejecutor (app, shell o nativo) y, si es de shell, un argv fijo con parámetros validados por
// expresión regular. Ejecutar sin shell evita la inyección por concatenación.
package router

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/honkonx/kairos-dev/native/gateway/internal/permission"
)

// Kind es quién ejecuta la acción.
type Kind string

const (
	// KindInApp la atiende la app Kairos (Kotlin) por un socket local.
	KindInApp Kind = "inapp"
	// KindShell es un comando fijo, sin shell, con parámetros validados.
	KindShell Kind = "shell"
	// KindNative es una función incorporada al propio gateway.
	KindNative Kind = "native"
)

// Param describe un parámetro de una acción. El patrón es obligatorio: sin él, cualquier valor
// podría colarse como flag.
type Param struct {
	Name     string `json:"name"`
	Pattern  string `json:"pattern"`
	Required bool   `json:"required"`
	// MaxLen es el tope de longitud del valor; 0 en el manifiesto significa "usar el valor por defecto"
	// (defaultMaxLen), no "sin límite" — todo parámetro tiene un tope duro. Una acción que de verdad
	// necesita valores largos (p. ej. un archivo binario en base64) lo declara explícitamente.
	MaxLen int `json:"max_len,omitempty"`
	re     *regexp.Regexp
}

// defaultMaxLen es el tope cuando la acción no declara max_len.
const defaultMaxLen = 512

// maxParamMaxLen es el techo absoluto que una acción puede declarar — coincide con el límite de
// cuerpo del puente app↔gateway (GatewayAppBridge.MAX_BODY) más margen para el resto del JSON.
const maxParamMaxLen = 3 * 1024 * 1024

// Action es una acción declarada en el manifiesto.
type Action struct {
	ID         string   `json:"id"`
	Title      string   `json:"title"`
	Risk       string   `json:"risk"`
	Kind       Kind     `json:"kind"`
	Exec       []string `json:"exec,omitempty"`
	Target     string   `json:"target,omitempty"`
	Params     []Param  `json:"params,omitempty"`
	TimeoutSec int      `json:"timeout_sec,omitempty"`
	// MinLevel es el nivel de permiso mínimo para usar la acción. Por debajo se rechaza sin preguntar (no hay
	// confirmación que valga): para acciones que no se pueden acotar con un patrón, como ejecutar un comando libre.
	MinLevel string `json:"min_level,omitempty"`

	risk     permission.Risk
	minLevel *permission.Level
}

// RequiredLevel devuelve el nivel mínimo exigido por la acción, si lo tiene.
func (a Action) RequiredLevel() (permission.Level, bool) {
	if a.minLevel == nil {
		return permission.Read, false
	}
	return *a.minLevel, true
}

// Risk devuelve el riesgo ya interpretado.
func (a Action) RiskLevel() permission.Risk { return a.risk }

// Service es una aplicación local a la que el gateway hace de proxy inverso.
type Service struct {
	Name   string `json:"name"`
	Target string `json:"target"`
}

// Manifest es el archivo de acciones y servicios.
type Manifest struct {
	Actions  []Action  `json:"actions"`
	Services []Service `json:"services"`
}

var (
	idPattern    = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)
	paramPattern = regexp.MustCompile(`^[a-z][a-z0-9_]{0,31}$`)
)

// Registry es el catálogo validado, de solo lectura una vez creado.
type Registry struct {
	actions  map[string]Action
	services map[string]Service
}

// LoadManifest lee y valida un manifiesto. Un archivo inexistente da un registro vacío.
func LoadManifest(path string) (*Registry, error) {
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return NewRegistry(Manifest{})
	}
	if err != nil {
		return nil, fmt.Errorf("leyendo manifiesto: %w", err)
	}
	var m Manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("manifiesto corrupto en %s: %w", path, err)
	}
	return NewRegistry(m)
}

// NewRegistry valida un manifiesto: ids únicos, riesgos y ejecutores válidos, rutas absolutas,
// parámetros con patrón y servicios solo en loopback.
func NewRegistry(m Manifest) (*Registry, error) {
	r := &Registry{actions: map[string]Action{}, services: map[string]Service{}}
	for _, a := range m.Actions {
		if err := a.validate(); err != nil {
			return nil, fmt.Errorf("acción %q: %w", a.ID, err)
		}
		if _, dup := r.actions[a.ID]; dup {
			return nil, fmt.Errorf("acción %q repetida", a.ID)
		}
		r.actions[a.ID] = a
	}
	for _, s := range m.Services {
		if err := validateService(s); err != nil {
			return nil, fmt.Errorf("servicio %q: %w", s.Name, err)
		}
		if _, dup := r.services[s.Name]; dup {
			return nil, fmt.Errorf("servicio %q repetido", s.Name)
		}
		r.services[s.Name] = s
	}
	return r, nil
}

func (a *Action) validate() error {
	if !idPattern.MatchString(a.ID) {
		return errors.New("id inválido (minúsculas, números, '.', '_' o '-')")
	}
	risk, err := permission.ParseRisk(a.Risk)
	if err != nil {
		return err
	}
	a.risk = risk
	if a.MinLevel != "" {
		lvl, err := permission.ParseLevel(a.MinLevel)
		if err != nil {
			return err
		}
		a.minLevel = &lvl
	}

	for i := range a.Params {
		p := &a.Params[i]
		if !paramPattern.MatchString(p.Name) {
			return fmt.Errorf("parámetro %q con nombre inválido", p.Name)
		}
		if p.Pattern == "" {
			return fmt.Errorf("parámetro %q sin patrón: todo parámetro debe validarse", p.Name)
		}
		re, err := regexp.Compile(`^(?:` + p.Pattern + `)$`)
		if err != nil {
			return fmt.Errorf("parámetro %q con patrón inválido: %w", p.Name, err)
		}
		p.re = re
		if p.MaxLen <= 0 {
			p.MaxLen = defaultMaxLen
		} else if p.MaxLen > maxParamMaxLen {
			return fmt.Errorf("parámetro %q: max_len no puede superar %d", p.Name, maxParamMaxLen)
		}
	}

	switch a.Kind {
	case KindShell:
		if len(a.Exec) == 0 || !(strings.HasPrefix(a.Exec[0], "/") || filepath.IsAbs(a.Exec[0])) {
			return errors.New("una acción shell necesita exec[0] como ruta absoluta (sin buscar en PATH)")
		}
		for _, arg := range a.Exec {
			for _, name := range placeholders(arg) {
				if a.param(name) == nil {
					return fmt.Errorf("el argumento %q usa {%s} pero no existe ese parámetro", arg, name)
				}
			}
		}
	case KindInApp, KindNative:
		if a.Target == "" {
			return errors.New("falta target")
		}
	default:
		return fmt.Errorf("ejecutor %q desconocido (válidos: inapp, shell, native)", a.Kind)
	}
	if a.TimeoutSec < 0 || a.TimeoutSec > 600 {
		return errors.New("timeout_sec debe estar entre 0 y 600")
	}
	return nil
}

func (a *Action) param(name string) *Param {
	for i := range a.Params {
		if a.Params[i].Name == name {
			return &a.Params[i]
		}
	}
	return nil
}

var placeholderRe = regexp.MustCompile(`\{([a-z][a-z0-9_]*)\}`)

func placeholders(s string) []string {
	var out []string
	for _, m := range placeholderRe.FindAllStringSubmatch(s, -1) {
		out = append(out, m[1])
	}
	return out
}

// Get busca una acción.
func (r *Registry) Get(id string) (Action, bool) {
	a, ok := r.actions[id]
	return a, ok
}

// Service busca un servicio proxy.
func (r *Registry) Service(name string) (Service, bool) {
	s, ok := r.services[name]
	return s, ok
}

// List devuelve las acciones ordenadas por id.
func (r *Registry) List() []Action {
	out := make([]Action, 0, len(r.actions))
	for _, a := range r.actions {
		out = append(out, a)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

// CheckParams valida los valores recibidos contra los patrones de la acción. Rechaza parámetros
// no declarados, valores demasiado largos y valores que no coinciden.
func (a Action) CheckParams(values map[string]string) error {
	for name := range values {
		if a.param(name) == nil {
			return fmt.Errorf("parámetro %q no declarado", name)
		}
	}
	for _, p := range a.Params {
		v, present := values[p.Name]
		if !present {
			if p.Required {
				return fmt.Errorf("falta el parámetro %q", p.Name)
			}
			continue
		}
		if p.re == nil {
			return fmt.Errorf("el parámetro %q no fue validado: la acción debe salir del registro", p.Name)
		}
		max := p.MaxLen
		if max <= 0 {
			max = defaultMaxLen
		}
		if len(v) > max || !p.re.MatchString(v) {
			return fmt.Errorf("el parámetro %q no cumple su patrón", p.Name)
		}
	}
	return nil
}

// Argv arma el argv de una acción shell sustituyendo {param}. Un parámetro opcional ausente
// elimina el argumento que lo usa.
func (a Action) Argv(values map[string]string) []string {
	argv := make([]string, 0, len(a.Exec))
	for _, tmpl := range a.Exec {
		missing := false
		out := placeholderRe.ReplaceAllStringFunc(tmpl, func(m string) string {
			name := m[1 : len(m)-1]
			v, ok := values[name]
			if !ok {
				missing = true
			}
			return v
		})
		if !missing {
			argv = append(argv, out)
		}
	}
	return argv
}
