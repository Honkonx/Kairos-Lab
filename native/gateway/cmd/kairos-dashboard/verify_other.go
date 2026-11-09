//go:build !windows

package main

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// verifyGatewayProcess — equivalente Linux de verify_windows.go: confirma que [host]:[port] lo
// tiene de verdad kairos-gateway (el binario junto a este, nunca otro programa que haya ocupado
// el puerto antes — mismo hallazgo de revisión automática que motivó la versión de Windows, "port
// squatting / credential phishing"). Mismo alcance real: es el camino DÉBIL (sin secreto que
// desafiar, ver verifyByChallenge en main.go), solo IPv4 (misma limitación que la versión de
// Windows — pidListeningOn ahí también rechaza todo lo que no sea IPv4).
//
// Sin libs externas (gopsutil, netstat empaquetado) a propósito — mismo criterio de todo
// native/gateway: 100% stdlib. Se leen directamente /proc/net/tcp (qué inode de socket escucha en
// ese puerto, en estado LISTEN) y /proc/<pid>/fd/* (qué proceso es dueño de ese inode) +
// /proc/<pid>/exe (qué binario es).
func verifyGatewayProcess(host net.IP, port uint16) error {
	v4 := host.To4()
	if v4 == nil {
		return fmt.Errorf("solo se soporta IPv4 (recibido %v)", host)
	}
	inodes, err := listeningInodes(v4, port)
	if err != nil {
		return fmt.Errorf("no se pudo leer /proc/net/tcp: %w", err)
	}
	if len(inodes) == 0 {
		return fmt.Errorf("nada escucha en %s:%d en estado LISTEN todavía", host, port)
	}
	pid, err := ownerPID(inodes)
	if err != nil {
		return fmt.Errorf("no se pudo identificar qué proceso escucha en %s:%d: %w", host, port, err)
	}
	if pid == 0 {
		return fmt.Errorf("%s:%d tiene un socket escuchando pero no se pudo identificar qué proceso es dueño (¿permisos?) — por seguridad no se abre", host, port)
	}
	path, err := os.Readlink(filepath.Join("/proc", strconv.Itoa(pid), "exe"))
	if err != nil {
		return fmt.Errorf("%s:%d lo tiene el proceso %d, pero no se pudo confirmar qué programa es — por seguridad no se abre", host, port, pid)
	}
	self, err := os.Executable()
	if err != nil {
		return err
	}
	expected := filepath.Join(filepath.Dir(self), "kairos-gateway")
	if filepath.Clean(path) != filepath.Clean(expected) {
		return fmt.Errorf("%s:%d lo tiene %q, no kairos-gateway — por seguridad no se abre la ventana (podría ser otro programa)", host, port, path)
	}
	return nil
}

// listeningInodes busca, en /proc/net/tcp, los inodes de socket en estado LISTEN (0A hex) cuyo
// puerto y dirección local coinciden con lo pedido ("00000000" = 0.0.0.0, escucha en todas las
// interfaces). Formato real de /proc/net/tcp (ver proc(5)): columnas separadas por espacios,
// "local_address" es HEXADECIMAL:HEXADECIMAL (IP en orden de bytes de máquina — little-endian en
// x86/ARM, los 4 bytes de la IP invertidos respecto al orden de red — y puerto en orden de red,
// big-endian, sin invertir). El campo "inode" es la columna 10 (índice 9) de los campos separados
// por espacio después de quitar la columna "sl:" inicial.
func listeningInodes(wantIP net.IP, wantPort uint16) ([]string, error) {
	f, err := os.Open("/proc/net/tcp")
	if err != nil {
		return nil, err
	}
	defer f.Close()

	wantAddrHex := fmt.Sprintf("%02X%02X%02X%02X", wantIP[3], wantIP[2], wantIP[1], wantIP[0])
	wantPortHex := fmt.Sprintf("%04X", wantPort)

	var inodes []string
	sc := bufio.NewScanner(f)
	sc.Scan() // encabezado
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 10 {
			continue
		}
		localAddr := fields[1] // "0100007F:0050"
		state := fields[3]
		inode := fields[9]
		addrPort := strings.SplitN(localAddr, ":", 2)
		if len(addrPort) != 2 {
			continue
		}
		if !strings.EqualFold(state, "0A") { // 0A = TCP_LISTEN
			continue
		}
		if !strings.EqualFold(addrPort[1], wantPortHex) {
			continue
		}
		if !strings.EqualFold(addrPort[0], wantAddrHex) && addrPort[0] != "00000000" {
			continue
		}
		inodes = append(inodes, inode)
	}
	return inodes, sc.Err()
}

// ownerPID recorre /proc/*/fd/* buscando cuál proceso tiene abierto alguno de los inodes de
// socket dados (el enlace simbólico de un fd de socket apunta a "socket:[<inode>]"). Devuelve
// error si encuentra MÁS DE UN PID distinto (mismo criterio que la versión de Windows: mejor
// fallar que asumir cuál de los dos es el real).
func ownerPID(inodes []string) (int, error) {
	want := map[string]bool{}
	for _, in := range inodes {
		want["socket:["+in+"]"] = true
	}
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return 0, err
	}
	found := 0
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue // no es un directorio de PID
		}
		fds, err := os.ReadDir(filepath.Join("/proc", e.Name(), "fd"))
		if err != nil {
			continue // proceso ajeno (sin permiso) o ya terminó -- no es un error fatal, seguir
		}
		for _, fd := range fds {
			target, err := os.Readlink(filepath.Join("/proc", e.Name(), "fd", fd.Name()))
			if err != nil || !want[target] {
				continue
			}
			if found != 0 && found != pid {
				return 0, fmt.Errorf("más de un proceso tiene ese socket abierto (PIDs %d y %d) — no se puede confiar en cuál es el real", found, pid)
			}
			found = pid
			break
		}
	}
	return found, nil
}

// showFatalError — sin ventana nativa de error en Linux todavía (no se compiló kairos-dashboard
// para Linux esta ronda, falta WebKitGTK —); un mensaje por stdout es mejor
// que nada hasta que se agregue un diálogo GTK real.
func showFatalError(title, message string) {
	fmt.Println(title + ": " + message)
}

// showInfo — ver comentario de showFatalError, mismo criterio sin ventana nativa en Linux todavía.
func showInfo(title, message string) {
	fmt.Println(title + ": " + message)
}
