package router

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// maxOutput limita la salida que se devuelve al cliente. Coincide con maxParamMaxLen (registry.go):
// un archivo binario de hasta 2 MB en base64 (fs.read con encoding=base64) pesa ~2.7 MB codificado,
// y antes de este cambio el tope de 64 KB lo habría truncado en silencio a mitad de archivo.
const maxOutput = 3 * 1024 * 1024

// Result es el resultado de ejecutar una acción.
type Result struct {
	OK       bool   `json:"ok"`
	ExitCode int    `json:"exit_code"`
	Output   string `json:"output"`
}

// Executor ejecuta una acción ya autorizada y validada.
type Executor interface {
	Run(ctx context.Context, a Action, params map[string]string) (Result, error)
}

// Dispatcher elige el ejecutor según el tipo de acción.
type Dispatcher struct {
	Shell  Executor
	InApp  Executor
	Native Executor
}

// Run valida los parámetros y delega en el ejecutor correspondiente, con su tiempo máximo.
func (d Dispatcher) Run(ctx context.Context, a Action, params map[string]string) (Result, error) {
	if err := a.CheckParams(params); err != nil {
		return Result{}, err
	}
	timeout := time.Duration(a.TimeoutSec) * time.Second
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	var ex Executor
	switch a.Kind {
	case KindShell:
		ex = d.Shell
	case KindInApp:
		ex = d.InApp
	case KindNative:
		ex = d.Native
	}
	if ex == nil {
		return Result{}, fmt.Errorf("no hay ejecutor %q configurado", a.Kind)
	}
	return ex.Run(ctx, a, params)
}

// ShellExecutor ejecuta el argv de la acción sin pasar por un shell.
type ShellExecutor struct {
	// Env es el entorno del proceso hijo; si es nil hereda el del gateway.
	Env []string
}

func (s ShellExecutor) Run(ctx context.Context, a Action, params map[string]string) (Result, error) {
	argv := a.Argv(params)
	if len(argv) == 0 {
		return Result{}, errors.New("argv vacío")
	}
	cmd := exec.CommandContext(ctx, argv[0], argv[1:]...)
	cmd.Env = s.Env
	var out cappedBuffer
	cmd.Stdout, cmd.Stderr = &out, &out
	err := cmd.Run()
	res := Result{OK: err == nil, Output: out.String()}
	var exitErr *exec.ExitError
	switch {
	case err == nil:
	case errors.As(err, &exitErr):
		res.ExitCode = exitErr.ExitCode()
		return res, nil
	case errors.Is(ctx.Err(), context.DeadlineExceeded):
		res.ExitCode = -1
		res.Output += "\n[tiempo agotado]"
		return res, nil
	default:
		return Result{}, fmt.Errorf("no se pudo ejecutar %s: %w", filepath.Base(argv[0]), err)
	}
	return res, nil
}

// InAppExecutor reenvía la acción a la app Kairos por HTTP en loopback.
type InAppExecutor struct {
	// URL es el endpoint de la app, p. ej. http://127.0.0.1:8972/internal/action.
	URL string
	// Secret se envía en X-Kairos-App-Secret; la app lo exige.
	Secret string
	Client *http.Client
}

func (e InAppExecutor) Run(ctx context.Context, a Action, params map[string]string) (Result, error) {
	if e.URL == "" {
		return Result{}, errors.New("la app no está configurada como destino")
	}
	body, err := json.Marshal(map[string]any{"target": a.Target, "params": params})
	if err != nil {
		return Result{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, e.URL, bytes.NewReader(body))
	if err != nil {
		return Result{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Kairos-App-Secret", e.Secret)
	client := e.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return Result{}, fmt.Errorf("la app no respondió: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxOutput))
	if err != nil {
		return Result{}, err
	}
	return Result{OK: resp.StatusCode/100 == 2, ExitCode: resp.StatusCode, Output: string(raw)}, nil
}

// NativeFunc es una función incorporada al gateway.
type NativeFunc func(ctx context.Context, params map[string]string) (string, error)

// NativeExecutor ejecuta funciones registradas por nombre (a.Target).
type NativeExecutor struct {
	Funcs map[string]NativeFunc
}

func (n NativeExecutor) Run(ctx context.Context, a Action, params map[string]string) (Result, error) {
	fn, ok := n.Funcs[a.Target]
	if !ok {
		return Result{}, fmt.Errorf("función nativa %q no existe", a.Target)
	}
	out, err := fn(ctx, params)
	if err != nil {
		return Result{OK: false, ExitCode: 1, Output: err.Error()}, nil
	}
	return Result{OK: true, Output: out}, nil
}

// BuiltinFuncs son las funciones nativas que trae el gateway.
func BuiltinFuncs(version string) map[string]NativeFunc {
	return map[string]NativeFunc{
		"gateway.version": func(context.Context, map[string]string) (string, error) {
			return fmt.Sprintf("kairos-gateway %s (%s/%s)", version, runtime.GOOS, runtime.GOARCH), nil
		},
		"system.thermal": func(context.Context, map[string]string) (string, error) {
			return ReadThermal("/sys/class/thermal")
		},
		"system.port_check": func(ctx context.Context, params map[string]string) (string, error) {
			return PortCheck(ctx, params["port"])
		},
	}
}

// PortCheck intenta conectar por TCP a 127.0.0.1:port — el gateway corre en el propio
// dispositivo, así que esto SÍ ve lo que escucha en el teléfono (no tiene sentido desde una PC
// remota, pero esta función nunca corre ahí). Pedido real de la investigación de Homelab
// (ítem #3 de la lista de valor/costo): confirmar si un puerto ya está
// ocupado por ALGO (no necesariamente este propio módulo) antes de arrancar un módulo, en vez de
// enterarse recién cuando el arranque falla. "native" en vez de "inapp": no necesita nada de
// Kotlin, es un dial TCP puro — más simple y más rápido que ir y volver por el puente interno.
func PortCheck(ctx context.Context, portStr string) (string, error) {
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return "", fmt.Errorf("puerto inválido: %q", portStr)
	}
	dialCtx, cancel := context.WithTimeout(ctx, 500*time.Millisecond)
	defer cancel()
	conn, dialErr := (&net.Dialer{}).DialContext(dialCtx, "tcp", fmt.Sprintf("127.0.0.1:%d", port))
	open := dialErr == nil
	if open {
		conn.Close()
	}
	return fmt.Sprintf(`{"port":%d,"open":%t}`, port, open), nil
}

// ReadThermal lista las zonas térmicas legibles ("tipo=grados") bajo root. Android puede negar la
// lectura a una app normal; en ese caso devuelve un error claro en vez de inventar un valor.
func ReadThermal(root string) (string, error) {
	zones, err := filepath.Glob(filepath.Join(root, "thermal_zone*"))
	if err != nil {
		return "", err
	}
	var lines []string
	for _, z := range zones {
		typ, err1 := os.ReadFile(filepath.Join(z, "type"))
		temp, err2 := os.ReadFile(filepath.Join(z, "temp"))
		if err1 != nil || err2 != nil {
			continue
		}
		milli, err := strconv.Atoi(strings.TrimSpace(string(temp)))
		if err != nil {
			continue
		}
		lines = append(lines, fmt.Sprintf("%s=%.1f", strings.TrimSpace(string(typ)), float64(milli)/1000))
	}
	if len(lines) == 0 {
		return "", errors.New("no se pudo leer ninguna zona térmica (Android puede restringir /sys/class/thermal)")
	}
	return strings.Join(lines, "\n"), nil
}

// cappedBuffer guarda como mucho maxOutput bytes y descarta el resto sin fallar.
type cappedBuffer struct {
	buf       bytes.Buffer
	truncated bool
}

func (c *cappedBuffer) Write(p []byte) (int, error) {
	room := maxOutput - c.buf.Len()
	if room > 0 {
		if len(p) > room {
			c.buf.Write(p[:room])
			c.truncated = true
		} else {
			c.buf.Write(p)
		}
	} else if len(p) > 0 {
		c.truncated = true
	}
	return len(p), nil
}

func (c *cappedBuffer) String() string {
	if c.truncated {
		return c.buf.String() + "\n[salida recortada]"
	}
	return c.buf.String()
}
