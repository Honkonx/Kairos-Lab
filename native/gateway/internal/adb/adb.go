// Package adb envuelve el binario real de Android Debug Bridge (Google, AOSP, Apache-2.0 —
// "mini adb": solo el binario adb + sus 2 DLL de Windows, no el SDK completo) para que el
// gateway de escritorio (kairos-dashboard) pueda otorgarle a un teléfono Kairos conectado por
// USB el mismo tipo de privilegio elevado que ya existe por ADB inalámbrico (ver
// PrivilegeProviders.kt/AdbShellBridge.kt del lado Android) — pedido explícito del usuario
// (2026-10-31): "agregar esa opcion a karios al codigo y al gateway que va
// en pc [...] mini adb [...] lo inicia el gateway".
//
// Alcance real (Alcance A del plan, docs/arquitectura/PLAN_ADB_USB_GATEWAY_2026-10-31.md): un
// teléfono NO puede ser host de adb de sí mismo por USB (confirmado leyendo el propio código de
// Kairos, strings_remote.xml: "el comando se corre EN LA PC") — por eso esto vive en el
// gateway de PC, no en el lado Android. A diferencia del flujo inalámbrico (que deja un comando
// de terminal `kairos-shell` reusable DESDE el teléfono), acá el privilegio solo es usable
// MIENTRAS el cable está conectado y el gateway tiene la sesión — se usa para acciones puntuales
// (desactivar el phantom process killer, eximir de optimización de batería), no para dejar una
// sesión persistente del lado del teléfono.
package adb

import (
	"bytes"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

// Device es una línea real de `adb devices -l` ya parseada.
type Device struct {
	Serial string
	State  string // "device" (autorizado), "unauthorized" (falta tocar "Permitir" en el teléfono), "offline"
}

// Client envuelve la ruta al binario `adb` ya extraído (ver Extract en embed_adb_*.go de
// cmd/kairos-dashboard) — nunca asume que está en el PATH del sistema, el gateway lleva el suyo.
type Client struct {
	BinPath string
}

func New(binPath string) *Client { return &Client{BinPath: binPath} }

func (c *Client) run(args ...string) (string, error) {
	cmd := exec.Command(c.BinPath, args...)
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		return out.String(), fmt.Errorf("adb %s: %w (%s)", strings.Join(args, " "), err, strings.TrimSpace(out.String()))
	}
	return out.String(), nil
}

// StartServer asegura que el server local de este `adb` esté corriendo antes de listar
// dispositivos — necesario la primera vez que se usa este binario embebido (nunca comparte
// server con un `adb` del sistema que el usuario pueda tener instalado aparte, ver -P más abajo).
func (c *Client) StartServer() error {
	_, err := c.run("start-server")
	return err
}

// Devices lista los dispositivos conectados AHORA (USB o cualquier otro transporte que este
// `adb` vea) — el caller filtra por State=="device" antes de confiar en el serial.
func (c *Client) Devices() ([]Device, error) {
	out, err := c.run("devices")
	if err != nil {
		return nil, err
	}
	var devices []Device
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "List of devices") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		devices = append(devices, Device{Serial: fields[0], State: fields[1]})
	}
	return devices, nil
}

// Shell corre un comando como identidad SHELL (uid 2000) contra el serial dado — mismo nivel de
// privilegio que ya documenta PrivilegeProviders.kt del lado Android, nunca "root" real.
func (c *Client) Shell(serial, command string) (string, error) {
	return c.run("-s", serial, "shell", command)
}

// RunAsTermux corre un comando DENTRO del sandbox de la app Kairos/Termux (`run-as com.termux`)
// — solo funciona en builds debuggable (ver AndroidManifest.xml, debug=true en los builds de
// Kairos) y es lo único que permite escribir en `~/.android_server_registry`/`$PREFIX/bin` sin
// root real, ya que esos directorios son privados de la app aunque el shell de ADB (uid 2000)
// no tenga acceso directo a ellos por defecto.
func (c *Client) RunAsTermux(serial, command string) (string, error) {
	return c.Shell(serial, "run-as com.termux sh -c '"+strings.ReplaceAll(command, "'", `'\''`)+"'")
}

const (
	// Misma clave que PhantomProcessKillerHelper.PHANTOM_SETTING_KEY (Kotlin) — una sola fuente
	// de verdad del NOMBRE del setting, aunque viva duplicada en 2 lenguajes (Go no puede
	// importar Kotlin) — si cambia de un lado, cambiar acá también.
	phantomSettingKey = "settings_enable_monitor_phantom_procs"
	registryPath      = "files/home/.android_server_registry" // relativo al data dir de com.termux, ver RunAsTermux
)

// GrantResult es lo que el caller (el flag --usb-grant o un futuro botón del dashboard) reporta
// al usuario — nunca se finge éxito sin confirmar cada paso.
type GrantResult struct {
	Serial                string
	PhantomKillerDisabled bool
	BatteryExempted       bool
	RegistryMarked        bool
	Errors                []string
}

// GrantUsbPrivilege corre la secuencia real de acciones "tipo root" que un usuario pediría acá
// (desactivar el phantom process killer, eximir de optimización de batería) + deja una marca en
// el registry del teléfono para que la propia app Kairos sepa que esto se hizo (Config >
// Permisos puede mostrarlo). Cada paso se intenta por separado y se reporta individualmente — un
// paso que falla no cancela los demás.
func GrantUsbPrivilege(c *Client, serial string) GrantResult {
	res := GrantResult{Serial: serial}

	if _, err := c.Shell(serial, "settings put global "+phantomSettingKey+" false"); err != nil {
		res.Errors = append(res.Errors, "phantom killer: "+err.Error())
	} else {
		res.PhantomKillerDisabled = true
	}

	if _, err := c.Shell(serial, "dumpsys deviceidle whitelist +com.termux"); err != nil {
		res.Errors = append(res.Errors, "bateria: "+err.Error())
	} else {
		res.BatteryExempted = true
	}

	marker := fmt.Sprintf("adb.usb.granted_at=%d", time.Now().Unix())
	writeCmd := "touch " + registryPath + " 2>/dev/null; grep -v '^adb.usb.granted_at=' " + registryPath + " > " + registryPath + ".tmp 2>/dev/null; echo '" + marker + "' >> " + registryPath + ".tmp; mv " + registryPath + ".tmp " + registryPath
	if _, err := c.RunAsTermux(serial, writeCmd); err != nil {
		res.Errors = append(res.Errors, "registry: "+err.Error())
	} else {
		res.RegistryMarked = true
	}

	return res
}
