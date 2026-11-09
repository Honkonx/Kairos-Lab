# Kairos Gateway in Go (`native/gateway/`) — v0.1

> Status: **core done and tested; Kotlin integration missing** (see "Not verified / pending").

## What it is

A local HTTP server, written in Go with **only the standard library**, that receives requests (the Homelab web
UI, other devices, the Agent), validates them against permissions and decides who handles them:

| Executor | What it does | Example |
|---|---|---|
| `inapp` | Forwards to the Kairos app over loopback HTTP with a secret | start/stop a module |
| `shell` | Fixed argv **without a shell**, regex-validated parameters, absolute path | an allow-listed command |
| `native` | Built-in gateway function | version, temperature |

Free-form commands are never executed: each action is declared in a manifest together with its risk.

## Permissions

Token levels: **read · ask · semi-total · total**. Action risk: `read` · `write` · `danger`.

| Level \ Risk | read | write | danger |
|---|---|---|---|
| read | allowed | denied | denied |
| ask | allowed | **asks the user** | **asks** |
| semi-total | allowed | allowed | **asks** |
| total | allowed | allowed | allowed |

The **global policy** (set by the user from the app) has a ceiling (`max`) and the **`responsible`** checkbox
("I am responsible for what happens"). Without the checkbox, any level above "ask" is reduced to
"ask". Initial policy: `max=ask`, `responsible=false` (the most conservative). Code and matrix:
`internal/permission/` (tests cover the whole matrix).

## API

| Route | Who | What |
|---|---|---|
| `GET /healthz` | public | status and version |
| `GET /v1/me`, `GET /v1/actions` | token | who I am, effective level and what would happen with each action |
| `POST /v1/actions/{id}[?wait=N]` | token | executes, asks (202 or waits up to 120 s) or rejects (403) |
| `GET /v1/pending/{id}` | the token that created it | state of a confirmation |
| `GET /v1/events` | token | SSE **only with events addressed to that token** (e.g. `action_done` for its own request); heartbeat every 15 s |
| `GET /v1/admin/events` | app only | full SSE: `pending` of any token, `policy`, `action_done` |
| `/proxy/{service}/{path}` | token | reverse proxy to a **loopback** service in the manifest; an `Upgrade`/WebSocket counts as a **write** |
| `/v1/admin/*` | app only (`X-Kairos-Admin`) | create/revoke tokens, policy, decide pending items, audit, events |

The token goes in `Authorization: Bearer`; only on `GET` is `?token=` also accepted (EventSource and links). The proxy
strips the token and the `Authorization` header before forwarding.

## Security decisions (verified with tests)

- Tokens are stored **only as SHA-256**; the plaintext value is delivered once. A stored secret is never shown again.
  Constant-time comparison. Files `0600`, folder `0700`.
- Empty `AdminSecret` ⇒ administration **does not exist** (always 403). Approving a confirmation requires that
  secret: the token owner cannot self-approve.
- Parameters: anchored regex required, max. 512 bytes, no unknown fields; they are validated **before** bothering the user.
- An action that did not go through the registry **fails closed** (error, no panic or execution).
- Proxy: destination only `localhost`/loopback (anti-SSRF), no user:password in the URL.
- **Scoped events** (security review, 2026-10-09): each event is either "admin only" or addressed to one token; a
  token never sees another token's confirmations, parameters or policy changes. Tested with tests that
  **fail if the fix is removed** (checked by mutation).
- **WebSocket through the proxy** counts as a write (after `Upgrade` the client can write): a read token gets 403.
  The `Connection` header is interpreted as a list (`keep-alive, Upgrade`) and `Upgrade` alone is enough.
- JSONL audit log with rotation; it never contains token values.
- Listens on `127.0.0.1:8973` by default; outside loopback it warns that there is **no TLS**.

## State files (`$HOME/.kairos_gateway/`)

`tokens.json` · `policy.json` · `manifest.json` (optional; if missing the built-in one is used, which declares
`gateway.version`, `system.thermal`, `module.list/status/start/stop/install`) · `audit.log` (+ `.1`).
Variables: `KAIROS_GATEWAY_DIR`, `KAIROS_GATEWAY_ADMIN_SECRET`, `KAIROS_GATEWAY_APP_URL`, `KAIROS_GATEWAY_APP_SECRET`.

## Build and test

```
bash tools/build-gateway.sh --test     # go vet + go test, then android/arm64 → app/src/main/jniLibs/arm64-v8a/libkairos_gateway.so
cd native/gateway && go test -race ./... # the race detector needs CGO (on the development machine: w64devkit/bin in the PATH)
```

The `.so` is not committed (`*.so` is ignored); CI builds it on every run (step added to `build-app.yml` and `.gitlab-ci.yml`,
after cloudflared). Same method as cloudflared (see [`../ssh/tunel-cloudflared-nativo.md`](../ssh/tunel-cloudflared-nativo.md)): `GOOS=android` + CGO + NDK clang.

## Measured results (2026-10-09)

- **Tests:** 8 packages, `go vet` clean, `gofmt` clean, `go test -race` without failures.
- **Binary:** 8.1 MB, ELF aarch64 "for Android 26", stripped.
- **On the real device** (`adb` shell, temporary folder, already cleaned up): it starts; `/healthz` ok; 401 without a token;
  a "semi-total" token ends up as "ask" under the default policy; `gateway.version` and `system.thermal` respond
  (temperature was read as the `shell` user); create pending → approve with the admin secret → execute (fails with a
  clean error because the app was not configured as a destination); changing the policy emitted the `policy` SSE event;
  the audit log recorded every step; a parameter with `; rm -rf /` was rejected with 400.

## Not verified / pending

- **Kotlin integration (not done):** launcher (`GatewayManager`, like `TunnelManager`) with the environment and secrets,
  `/internal/action` endpoint in the app (for the `inapp` executor), admin client, and the **permissions screen** in
  Homelab (4-level selector + responsibility checkbox + confirmation dialog when the `pending` event arrives).
- That the **app user** (not `shell`) can read `/sys/class/thermal`.
- That the device's SELinux allows running `libkairos_gateway.so` from `nativeLibraryDir` under the app
  (it works for `libcloudflared.so`, but this binary was not tested inside the app).
- `Location` rewriting on proxy redirects; real WebSocket forwarding (not just the permission rejection) was not tested.
- An SSE client in the Homelab web UI (it still uses `meta refresh` today).
- APK size for x86 / other ABIs: only arm64 is built.
