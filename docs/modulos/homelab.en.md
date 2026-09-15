# Homelab

**Native Kairos screen** — accessible from the "More" menu. It's not an installable module from
`modulos/` (no `.sh` script of its own, doesn't appear in the Store) — it's 100% native Kotlin,
just another management screen, no terminal required.

---

**App:** Kairos (termux-app fork)
**Fragment:** `HomelabFragment.kt`
**Persistence/logic:** `HomelabManager.kt`

---

## 1. What it is, and what it isn't

Homelab is a panel/dashboard that brings two different things together in one place:

1. **"This device"** — quick access to what Kairos already exposes: Remote/SSH status
   (running? on which port?) and a list of the modules with an HTTP service currently running
   (Ollama, n8n, llama-server, etc.), with an "Open" button straight to each one. The phone
   itself shows up as just another node of the homelab, not as something separate.
2. **"Services"** — a list of **external** self-hosted services the user already has running on
   their local network (Docker/Portainer on a NAS, Pi-hole on a Raspberry Pi, Proxmox on a
   server, or any generic HTTP panel), added by hand with a name + URL/IP:port + type + optional
   username/token.

**Not implemented**: deep per-platform API integration (Docker Engine API, Pi-hole API, Proxmox
API) — the "status" of an added service is a generic HTTP probe (`HEAD` with a short timeout),
not a real read of containers/rules/VMs.

## 2. Difference from the modules Kairos already manages

| | Modules (Ollama, n8n, ...) | Homelab |
|---|---|---|
| Where the service runs | Inside the phone itself (a Kairos child process) | Outside — on a NAS/Raspberry Pi/server on the user's network |
| How it's installed | `ProcessBuilder` + a `.sh` script from `modulos/` | Nothing is installed — only a URL gets registered |
| Lifecycle | Kairos starts/stops it | Kairos doesn't control the remote process, it only checks whether it responds |
| Authentication | Not applicable (local process) | Optional username/token, stored so it can never be viewed again |

## 3. The screen

- **"This device" section**: a fixed row for Remote/SSH (green/gray dot + status + "Open"
  button that navigates to the Remote module) followed by a row for every module with a port
  that's currently running — "Open" shows it embedded right inside the app, in the same
  internal web viewer the rest of the app already uses for module screens (Ollama, n8n,
  OpenClaw...).
- **"Services" section**: an "+ Add" button opens a dialog (name, type — Docker/Portainer,
  Pi-hole, Proxmox, generic HTTP, Other —, URL/IP:port, optional username, optional token). Each
  added service shows up as a card: a status dot (gray until the first check, green/red after),
  name, type and URL, "Open" button. Long-pressing a card opens a menu: Edit / Add-or-Replace
  token / Clear token (if it has one) / Remove service.
- **Ease of use**: if the user types just `192.168.1.50:9000` with no scheme, Kairos
  automatically prepends `http://` — no need to type it out in full.
- Each service's availability check runs after the list has rendered, so it doesn't block the
  first render with the network latency of every service (there can be several on a slow local
  network).

## 4. Security of stored tokens/passwords

Once a service's token or password is saved, **no** screen in Kairos ever shows its content
again — it only indicates whether or not the service has a secret stored. The only available
actions are replacing it (the replace dialog always starts empty, never pre-filled with the
previous value) or deleting it. The secret file is stored in the app's private storage with
permissions restricted to Kairos's own process.

## 5. Future scope (not implemented yet)

- Access to the panel from outside the app: an external web browser, or a separate, lighter
  companion app.
- Remote access to the dashboard from outside the local network (tunneled), distinct from the
  Remote module (which exposes a full shell, not a visual panel).
- Deep per-platform integration: real Docker Engine API (list/restart containers), Pi-hole API
  (blocking statistics), Proxmox API (VM status) — today "status" is just generic HTTP
  availability, not real platform data.
- Stronger authentication (PIN/biometrics before viewing the service list, anti-screen-capture
  protection).
