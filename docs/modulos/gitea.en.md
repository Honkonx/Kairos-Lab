# Gitea

**Kairos module** — no Fragment of its own, managed by the app's generic module screen (Kairos
already has everything needed to install/start/stop/open the web UI without a dedicated screen),
same pattern as `syncthing`.

---

**App:** Kairos (termux-app fork)
**Script:** `modulos/gitea.sh`
**Port:** `3001` (web UI, only reachable from the device itself — Kairos opens it embedded
inside the app)

---

## 1. What it is

A self-hosted Git server running on the phone itself — Gitea ("Git with a cup of tea"). It fits
right into the "home-lab in your pocket" idea: it lets you have your own Git repositories and
push the code you're working on (for example, from Kairos's built-in IDE) to a server that lives
on the same device, with no dependency on GitHub or any other external service.

## 2. Port — why 3001 instead of the usual 3000

Gitea defaults to port 3000 for its web interface, but another Kairos module (OpenCode) already
uses that port. To avoid the clash, the install configures Gitea directly on port **3001**.
Gitea's internal SSH server is also disabled (unrooted Android can't use the port 22 it would
default to) — Kairos already covers SSH access through its own Remote module, on a different
port. Access to Gitea's repositories stays over plain HTTP (clone/push to
`http://127.0.0.1:3001/user/repo.git`).

## 3. Installation

Native Termux package, with the port adjustment applied automatically during install — no manual
steps.

## 4. Start and stop

The module has a real ON/OFF switch: turning it on starts the Gitea server in the background.
Turning it off stops the process.

## 5. Configuration

Creating the first admin user and the first repositories is Gitea's own install wizard, which
shows up the first time its interface is opened — inherently interactive by the tool's own
design. It's completed from `http://localhost:3001`, which Kairos opens embedded inside the app
as soon as the switch is on.
