# SSH client — the "Sender" tab

SSH in Kairos works in both directions: the device can be controlled (local SSH server) and can
control other machines (SSH client). The Remote/SSH module screen is organized into 5 tabs:

| Tab | Content | Direction |
|---|---|---|
| **Receiver** | SSH server status, connection info, add public key, password, active connections, copy command, server fingerprint, maintenance | Kairos being controlled |
| **Security** | Port, always require key, root login, authorized keys, the device's own key | Local SSH server configuration |
| **Cloudflare** | SSH tunnel via Cloudflare (token, how to connect) | Exposure without needing a public IP |
| **Network** | IP field and local network scan | Discovering servers on the LAN |
| **Sender** | SSH client: save, view, delete, and connect to remote servers | Kairos controlling other machines |

## SSH client (Sender tab)

### Data model

Each saved connection has: an identifier, an alias, host, port, user, whether it uses the
device's own key, and the date of the last confirmed contact. It's persisted as a single compact
JSON array — the same pattern used by the rest of the app's structured settings, avoiding
scanning through multiple loose numbered keys.

### Flow: add, view, connect, delete

1. **Add**: a dialog with an optional alias, host, port (editable, with no range restriction —
   unlike the local SSH server's port, which does require a high, non-root-privileged value; a
   remote server's port can be the standard port 22 of a machine that does run as root), user,
   and a checkbox to use the device's own key.
2. **View**: lists a card for each saved server, re-reading its status on a background thread
   every time (never cached) — each row shows the alias, `user@host:port`, and an indication of
   the last time contact was confirmed.
3. **Connect**: does a short TCP check against the saved host and port; if it responds, it
   updates the last-confirmed date. Whether or not it responds, it opens a real terminal session
   with the corresponding SSH command (using the device's own key if the user chose that and the
   key already exists; otherwise the SSH client will prompt for the password interactively).
4. **Delete**: confirmation and removal of the saved entry. It doesn't affect the remote server,
   only the local reference.

### Scope of the "monitoring"

What's implemented is a one-off check (a short TCP connection) right before connecting — it's
not a continuous background poll or a permanent monitoring session. If it responds, it's saved
as "last confirmed" and the row shows it in relative minutes. Real continuous monitoring
(periodic background polling of all saved connections) implies a different battery/CPU cost and
is out of scope here.

### Credential handling

The list of saved servers never contains a password — password authentication remains fully
interactive in the real SSH client inside the terminal; Kairos never sees or persists it at any
point. For key-based authentication, the device's own key is reused (a locally generated
ed25519 key pair, with restricted permissions), the same one the local SSH server uses to accept
incoming connections.

### Imported private keys (Receiver tab)

Besides the device's own key, the Receiver tab lets you import a third-party private key, with
two modes of use:

- **Save persistently**: the key is written to a file with restricted permissions inside a
  dedicated directory. The only actions available on an already-saved key are using it (by
  reference, never exposing its value), replacing it (the replacement field always starts empty,
  never pre-filled with the previous key), or deleting it.
- **Use for this session only**: the key is never added to the list of saved keys — it's written
  to a temporary file, used to launch that one SSH connection, and automatically deleted a few
  seconds after the session starts.

This reflects a general design rule of the project: once a secret (SSH key, token, password) is
saved in Kairos, the interface never shows its content again — an already-saved key is
identified visually by its fingerprint (a one-way, non-reversible derivative of the key), never
the key itself.
