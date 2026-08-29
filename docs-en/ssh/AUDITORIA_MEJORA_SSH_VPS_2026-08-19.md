# Exposing the device's SSH server through tunnels (ngrok / Cloudflare)

## Context

The SSH server embedded on the device (based on Termux's real OpenSSH package, not a limited
reimplementation) lets the device function somewhat like a remote server ("VPS") reachable over
SSH. This document describes which typical remote-server capabilities were already covered and
what was added to expose that SSH server to the internet without a public IP.

## Capabilities assessed

| Typical remote-server capability | Status |
|---|---|
| Persistent SSH host keys (not regenerated on every boot) | Already correct — `ssh-keygen -A` is idempotent and only generates the host keys that are missing, never overwriting an existing one. Keys are only lost if the SSH configuration directory is wiped entirely (full reinstall), equivalent behavior to any fresh OpenSSH install |
| Port management | Fixed, non-privileged port (the process runs without root privileges, so it can only bind ports ≥1024) |
| SSH service running reliably in the background | Covered — the daemon restarts on reconnect, with no need for an additional supervisor |
| Port forwarding (`-L`/`-R`/`-D`) | Already natively supported by any SSH client that connects — a standard OpenSSH feature enabled by default in the configuration |
| Exposing the device's SSH server to the internet without a public IP | Extended — see below |

## Exposure via the generic tunnel system

The app's tunneling system (also used to expose other local services, such as web dashboards)
was extended to support raw TCP traffic, required for SSH:

- The SSH module was added to the tunnel manager's list of known services, with its
  corresponding port.
- A "TCP-only modules" category was introduced: unlike the rest of the services (all HTTP), SSH
  requires a different tunnel command.
  - With **ngrok**, TCP tunnel mode (`ngrok tcp <port>`) is used instead of HTTP mode.
  - With **Cloudflare without a token**, an explicit error is returned instead of attempting an
    HTTP tunnel that would never work for raw TCP traffic.
  - With **Cloudflare with a token** (named tunnel), no behavior change was needed: that mode
    isn't tied to a specific traffic type — the ingress type (HTTP or TCP) is defined on the
    Cloudflare dashboard side when the tunnel is created. It just needed the SSH module to appear
    in the list of available services.
- Support was added for the URL format used by ngrok's TCP tunnel mode (which uses the `tcp://`
  scheme instead of `https://`), needed so the interface could correctly display the assigned
  public address.

In the UI, "TCP-only" modules hide options that don't apply (anonymous HTTP-only quick tunnel,
custom domain selection) and show a different warning than other services when exposing them: instead
of stating that "anyone with the link can use it without authentication" (true for services with
no login of their own), for SSH it clarifies that it still requires a valid username/password or
key, explicitly recommending a strong password or public-key authentication, given that the port
becomes publicly reachable.

The result is that the user can expose the device's SSH server through two alternative methods:

- **Cloudflare (named tunnel with a token)** — reuses the same configuration already used for
  other services.
- **ngrok (TCP mode)** — first available alternative that doesn't depend on having a Cloudflare
  account with Zero Trust configured, only requiring an ngrok auth token.

Both methods coexist without replacing each other — the user picks whichever one they already
have configured.

## Explicitly deferred design decisions

The following improvements were identified but left out of this implementation, as they require
larger architectural decisions or carry security risk if activated without the user fully
understanding the implications:

1. **Configurable SSH port from the UI.** Would require rewriting the server's configuration on
   the fly, restarting it, and keeping several components in sync — with real risk that an
   incorrect value leaves the service unable to start. See the SSH security panel document for
   the current state of this feature.
2. **Configurable root-login toggle / max authentication attempts from the UI.** The defaults are
   already secure (root login disabled); exposing a control to change them requires a UI design
   that makes the risk fully explicit before applying it.
3. **Reserved TCP address for ngrok tunnels**, which would provide a stable URL across tunnel
   restarts instead of a new random address each time — this is a paid-account ngrok feature, not
   implemented until there's a concrete use case for it.
4. **Public-key authentication enforced by default.** Would be more secure for a device exposed
   to the internet, but changing the default behavior would break the flow for any user who
   hasn't yet uploaded a public key — this is preferred as an explicit user decision rather than
   an automatic behavior change.

See the SSH security panel document for the follow-up on several of these points.
