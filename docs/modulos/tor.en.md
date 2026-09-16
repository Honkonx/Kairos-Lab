# Tor

**Kairos module** — no Fragment of its own, managed by the app's generic module screen (Kairos
already has everything needed to install/start/stop it without a dedicated screen — with no web
UI, a SOCKS proxy isn't something you browse to).

---

**App:** Kairos (termux-app fork)
**Script:** `modulos/tor.sh`
**Port:** `9050` (SOCKS5 proxy, only reachable from the device itself)

---

## 1. What it is

The Tor network (The Onion Router) — layered traffic routing for anonymous browsing. Running
inside Kairos, it exposes a local SOCKS5 proxy that any compatible app or tool can connect to so
its traffic routes through the Tor network. It's a separate module from Kairos's Cybersecurity
kit (which covers auditing/pentesting, not network anonymity) — the two solve different
problems.

## 2. Installation

Native Termux package, with the SOCKS proxy explicitly configured on port 9050 during install —
no manual steps.

## 3. Start and stop

The module has a real ON/OFF switch: turning it on starts the Tor process in the background with
its SOCKS proxy listening on `127.0.0.1:9050`. Turning it off stops it.

## 4. Usage

No interface of its own — once the switch is on, any SOCKS5-compatible app or command-line tool
(curl, browsers, other CLIs) can be pointed at `127.0.0.1:9050` to route its traffic through Tor.
