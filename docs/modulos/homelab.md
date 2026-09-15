# Homelab

**Pantalla nativa de Kairos** — accesible desde el menú "Más". No es un módulo instalable de
`modulos/` (no tiene script `.sh` propio, no aparece en la Tienda) — es 100% Kotlin nativo, una
pantalla de gestión más, sin necesitar la terminal.

---

**App:** Kairos (fork termux-app)
**Fragment:** `HomelabFragment.kt`
**Persistencia/lógica:** `HomelabManager.kt`

---

## 1. Qué es, y qué no es

Homelab es un panel/dashboard que reúne dos cosas distintas en un mismo lugar:

1. **"Este dispositivo"** — accesos rápidos a lo que Kairos ya expone: estado de Remote/SSH
   (¿corriendo? ¿puerto?) y una lista de los módulos con servicio HTTP corriendo ahora mismo
   (Ollama, n8n, llama-server, etc.), con un botón "Abrir" directo a cada uno. El propio
   teléfono se muestra como un nodo más del homelab, no como algo aparte.
2. **"Servicios"** — una lista de servicios self-hosted **externos** que el usuario ya tiene
   corriendo en su red local (Docker/Portainer de un NAS, Pi-hole de una Raspberry Pi, Proxmox
   de un servidor, o cualquier panel HTTP genérico), agregados a mano con nombre + URL/IP:puerto
   + tipo + usuario/token opcional.

**No implementa** integración profunda por API de cada plataforma (Docker Engine API, Pi-hole
API, Proxmox API) — el "estado" de un servicio agregado es un probe HTTP genérico (`HEAD` con
timeout corto), no una lectura real de contenedores/reglas/VMs.

## 2. Diferencia con los módulos que Kairos ya gestiona

| | Módulos (Ollama, n8n, ...) | Homelab |
|---|---|---|
| Dónde corre el servicio | Dentro del propio teléfono (proceso hijo de Kairos) | Fuera — en un NAS/Raspberry Pi/servidor de la red del usuario |
| Cómo se instala | `ProcessBuilder` + script `.sh` de `modulos/` | No se instala nada — solo se registra una URL |
| Ciclo de vida | Kairos lo inicia/detiene | Kairos no controla el proceso remoto, solo consulta si responde |
| Autenticación | No aplica (proceso local) | Usuario/token opcional, guardado sin poder volver a verse nunca |

## 3. La pantalla

- **Sección "Este dispositivo"**: una fila fija para Remote/SSH (punto verde/gris + estado +
  botón "Abrir" que navega al módulo Remote) seguida de una fila por cada módulo con puerto que
  esté corriendo ahora mismo — "Abrir" lo muestra embebido en la propia app, en el mismo visor
  web interno que ya usa el resto de la app para pantallas de módulo (Ollama, n8n, OpenClaw...).
- **Sección "Servicios"**: botón "+ Agregar" abre un diálogo (nombre, tipo — Docker/Portainer,
  Pi-hole, Proxmox, HTTP genérico, Otro —, URL/IP:puerto, usuario opcional, token opcional). Cada
  servicio agregado se muestra como card: punto de estado (gris hasta el primer chequeo, verde/
  rojo después), nombre, tipo y URL, botón "Abrir". Mantener presionada una card abre un menú:
  Editar / Agregar-o-Reemplazar token / Borrar token (si tiene uno) / Eliminar servicio.
- **Facilidad de uso**: si el usuario escribe solo `192.168.1.50:9000` sin esquema, Kairos
  antepone `http://` automáticamente — no hace falta tipearlo completo.
- El chequeo de disponibilidad de cada servicio corre después de renderizar la lista, para no
  bloquear el primer render con la latencia de red (puede haber varios servicios en una red
  local lenta).

## 4. Seguridad de los tokens/contraseñas guardados

Una vez guardado un token o contraseña de un servicio, **ninguna** pantalla de Kairos vuelve a
mostrar su contenido — solo se indica si el servicio tiene o no un secreto guardado. Las únicas
acciones disponibles son reemplazarlo (el diálogo de reemplazo arranca siempre vacío, nunca
pre-cargado con el valor anterior) o borrarlo. El archivo del secreto se guarda en almacenamiento
privado de la app con permisos restringidos al propio proceso de Kairos.

## 5. Alcance futuro (no implementado todavía)

- Acceso al panel desde fuera de la app: navegador web externo, o una app compañera separada más
  liviana.
- Acceso remoto al dashboard desde fuera de la red local (túneleado), distinto del módulo Remote
  (que expone una shell completa, no un panel visual).
- Integración profunda por plataforma: Docker Engine API real (listar/reiniciar contenedores),
  Pi-hole API (estadísticas de bloqueo), Proxmox API (estado de VMs) — hoy el "estado" es solo
  disponibilidad HTTP genérica, no datos reales de la plataforma.
- Autenticación reforzada (PIN/biometría antes de ver la lista de servicios, protección
  anti-captura de pantalla).
