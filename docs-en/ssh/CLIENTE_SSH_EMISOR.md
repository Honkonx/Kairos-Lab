# SSH client (Sender mode)

## What it is

The Remote module implements bidirectional SSH access: the device can act as a **server** (being
controlled remotely) or as a **client** (controlling other servers). The module's screen is
organized into tabs:

| Tab | Content | Direction |
|---|---|---|
| **Receiver** | SSH service status, connection info, authorized public key management, password, active connections, server fingerprint | The device being controlled |
| **Security** | Port, key-based auth enforcement, root login, authorized keys, device's own key | Local SSH server configuration |
| **Cloudflare** | SSH tunnel via Cloudflare (token, connection instructions) | Exposure to the internet without a public IP |
| **Network** | IP field and LAN scanning | Discovering servers on the local network |
| **Sender** | SSH client: save, view, delete, and connect to remote servers | The device controlling other machines |

## SSH client (Sender tab)

### Data model

Each saved connection is represented by an id, alias, host, port, user, a flag indicating
whether it should use the device's own key, and the timestamp of the last successful check. Saved
connections are persisted in a local registry as a compact JSON array, following the same
pattern used for the app's saved tunnels elsewhere: a single registry key per data type, with an
exclusive lock per write operation to avoid race conditions.

### Usage flow

1. **Add connection**: a dialog collects an optional alias, host, port, user, and a checkbox to
   use the device's own key. Unlike the local SSH server's port (which requires a value ≥1024
   since it doesn't run with root privileges), an outbound connection's port has no such
   restriction, since the remote server may run with full privileges on the standard port 22.
2. **List saved connections**: the list is recomputed by reading the registry on a background
   thread on every render (never cached), showing alias, `user@host:port`, and a relative
   indicator of the last time it was confirmed reachable.
3. **Connect**: before opening the session, a short TCP check (1.5-second timeout) is run against
   the saved host and port. Whether or not it responds, a real terminal session is opened running
   the standard `ssh` client with the corresponding parameters, reusing the app's general
   terminal command-launch mechanism instead of reimplementing session handling. If the
   connection is marked to use the device's own key but that key hasn't been generated yet, the
   command is built without the identity flag and the real `ssh` client prompts for a password
   interactively.
4. **Delete**: removes only the locally saved reference — it never interacts with the remote
   server.

### Scope of "monitoring"

The reachability check implemented is a point-in-time probe: it runs right before attempting to
connect, not a periodic background poll or a continuous monitoring session. If it responds, the
"last confirmed" marker shown in the list is updated. True continuous monitoring (a periodic
background poll of every saved connection) would carry a distinct battery and CPU cost, and is
deliberately out of scope for this initial implementation.

### Credential model

The connection registry **never** stores passwords: password authentication is fully delegated
to the real `ssh` client inside the interactive terminal session — the app never sees or persists
it at any point.

For key-based authentication, the device's own keypair is reused (generated on demand, without
additional encryption, with restrictive permissions equivalent to what any normal Linux/Termux
user applies to their own SSH key).

### Imported private keys

In addition to the device's own key, a third party's private key can be imported, with two usage
modes:

- **Persistent storage**: the key is stored in a dedicated directory with restrictive
  permissions (both file and directory). No function in the system ever exposes the content
  again once saved — only the alias and the fingerprint (a one-way, non-reversible derivative
  computed with `ssh-keygen`) are shown. The interface offers no "view key" action; the only
  available actions are using it (by reference), replacing it (overwriting without reading the
  previous value), or deleting it.
- **Ephemeral use (current session only)**: the key is never added to the list of imported keys
  or to the persistent registry — it's written to a transient file, used to launch the `ssh`
  command, and a background process automatically deletes it a few seconds later. The small delay
  before deletion exists because the app doesn't control the exact moment the terminal session's
  `ssh` process opens the file — deleting it too aggressively could remove the key before it's
  been read.

This model follows the same security principle applied throughout the Remote module: once a
secret is saved, it's never displayed again — it can only be used, replaced, or deleted.
