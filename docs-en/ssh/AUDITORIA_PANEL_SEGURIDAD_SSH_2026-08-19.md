# SSH security panel

## Motivation

Exposing the device's SSH server to the internet through tunnels (see the document on exposure
via ngrok/Cloudflare) raises a legitimate security question: what authentication controls does
the user have visibility into before doing so? This panel addresses that by showing, and
allowing live changes to, the SSH server's actual security configuration.

## Actual SSH server configuration

Before building the panel, the server's real behavior was confirmed:

- **Password**: password authentication uses PAM against the real password of the system user —
  there's no app-specific authentication mechanism layered on top of that.
- **Public keys**: managed through OpenSSH's standard authorized-keys file.
- **Port**: fixed, non-privileged (the process runs without root privileges).
- **Root login**: disabled by default.
- **Password authentication**: enabled by default — this was the actual gap that needed
  visibility: without an accessible control, a user who exposes the server to the internet via a
  tunnel ends up with password authentication enabled without knowing it or being able to change
  it from the app.

None of these facts required changes to the SSH server's install script — the real gap was the
absence of an interface to read and modify these values in real time, not a problem with the
install-time configuration.

## What was implemented

### Reading and modifying configuration

A function was added that parses the server's actual configuration on every query (never
cached), so the panel always reflects the real state even if the configuration file is edited
manually outside the app. Functions are exposed to:

- Query the current state (port, password authentication, root login, whether authorized keys
  exist, whether the device's own key exists, service status).
- Change the port (validated within the range allowed for a non-root process).
- Toggle password authentication — with a server-side guardrail that **rejects disabling it** if
  no authorized key exists yet, preventing the user from locking themselves out with no way back
  in.
- Toggle root login.
- Generate the device's own keypair (used as a client to connect to other servers, complementing
  the management of third-party authorized keys).

Any configuration change automatically restarts the service if it was already running, since the
SSH server doesn't reload its configuration on its own — without this step, the panel would show
a new value while the in-memory process kept operating with the old configuration.

### Interface

A "SSH Security" section was added with:

| Field | Source |
|---|---|
| Port | Server's actual configuration |
| Password authentication | Enabled / Disabled |
| Root login | Allowed / Blocked |
| Authorized keys | Present / None |
| Device's own key | Generated / Not generated |

With actions to change the port, toggle each control (with explicit confirmation for
higher-risk changes — disabling password auth or enabling root login), generate the device's own
key, and copy the device's own public key to the clipboard.

### Consistency with the tunnel system

The tunnel manager resolves the SSH service's port by querying the actual security configuration
instead of using a fixed value — otherwise, if the user changed the port from this panel, the
tunnel system would keep trying to expose the previous port, with nothing listening there
anymore.

## Default value design principles

- Password authentication stays enabled by default, matching how the server is installed —
  disabling it automatically before the user has added a public key would leave them with no way
  to get in. Disabling it is always an explicit action, backed by a server-side guardrail in
  addition to UI confirmation.
- Root login stays blocked by default, matching the install-time configuration — the control to
  enable it exists but never activates as a side effect of another action; it always requires
  explicit confirmation with a warning about the real risk.
- The port doesn't change from its default value except by explicit user action, within the
  range technically valid for a non-root process.
- Automatically restarting the service after a configuration change only happens if the service
  was already running — if it wasn't, it isn't started automatically as a side effect.

## "Always require key-based authentication" switch

A direct, visible switch was added to the panel, with the semantics of a traditional remote
server: **on** means password authentication is disabled (only a valid key can get in, even for
the same user from another device without the matching key); **off** means password
authentication is allowed again, which is the install's default behavior.

### Two-layer guardrail

1. **In the UI**, before showing any dialog: if there are no authorized keys added, the switch is
   blocked immediately with a notice to add the device's own public key first, and the control
   reverts to reflect the real state (it never appears visually enabled without the change having
   actually applied).
2. **In the business logic layer**: the function that disables password authentication still
   rejects the operation if no authorized key exists — this is the real guarantee, independent of
   any UI path that might bypass the first layer.

If authorized keys exist, a confirmation dialog is shown with the traditional-remote-server
analogy before applying the change; canceling the dialog reverts the switch to its real state.
Turning the switch off (allowing passwords again) has no restriction or confirmation dialog,
since loosening the restriction doesn't carry the same risk of accidental lockout.

## Out of scope (deliberate)

- Password rotation or complexity policies were not implemented — the underlying authentication
  system doesn't support this natively without additional components.
- Per-IP rate limiting (fail2ban-style) was not implemented — the per-connection authentication
  attempt limit is already configured on the server; an IP-blocking mechanism is a larger
  feature, out of scope for a basic visibility-and-control panel.
