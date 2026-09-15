# Tunnel — data model and actions (multi-domain)

The Tunnel tab lets you expose any Kairos module to the internet via Cloudflare or ngrok,
supporting multiple domains and saved tokens per provider.

## Data model — 3 layers, they don't replace each other

| Layer | What it's for |
|---|---|
| Global config per provider | Default token and domain for Cloudflare/ngrok. Each module card's quick buttons (with no explicit token/domain) use this config — the "⚙ Tunnel settings" panel. |
| Per-module own config | A domain/token tied to a specific module (n8n, remote, Ollama, OpenCode, OpenClaw, llama.cpp server). Saved independently from the global config. |
| Saved domains | A list of domains/tokens with no owner yet — they're added, deleted, and assigned (copied) to a module's own config when the user selects them. They aren't automatically removed once assigned — they can be reassigned to a different module later. |

The saved-domains list is persisted as a single compact JSON value per provider
(`[{"id","domain","token"}, ...]`), under the same key-value store where the rest of the app's
configuration keeps structured lists or objects — this avoids having to scan multiple keys with
a variable numbered prefix, and keeps the config file as plain text, line by line.

## The 4 actions

1. **Add** — dialog with domain (optional) and token; added as a new entry to the provider's
   list, without touching any existing assignment. Requires at least one of the two fields to be
   non-empty.
2. **Delete** — icon next to each list entry; removes only that specific entry, without
   affecting the config of a module that already has it assigned (that config lives in its own
   key, independent of this one).
3. **Assign** — tapping a domain in the list shows the tunnel-compatible modules (n8n, remote,
   Ollama, OpenCode, OpenClaw, llama.cpp server). If the chosen module already had a different
   domain/token assigned, explicit confirmation is required before replacing it.
4. **Verify** — for each module with an assigned own config, this checks: that the token/domain
   is still saved; for modules with their own config file read by their script (like n8n), that
   the file exists and matches the saved value; and if that module's tunnel is running, that the
   real process is still alive (not just that the associated terminal session exists — a session
   can remain "alive" while the process inside it has already terminated, for example after an
   unexpected shutdown).

   The result is shown with a success/error indicator per module and provider, plus the problem
   detail if there is one.

## Credential handling in the global config panel

The global config edit dialog doesn't preload the saved token in plain text — the field starts
empty, and a hint indicates whether a token is already saved without revealing it. Leaving it
empty on save keeps the existing token; typing a new one replaces it. The domain field, however,
is preloaded (it isn't sensitive data, and deliberately clearing it is a real use case: removing
the domain without removing the token).

## What's independent of this feature

- Each module card's quick buttons (anonymous tunnel, with token) keep working the same way,
  using the global config or a one-off token/domain — without going through the saved-domains
  list.
- The global config panel (edit/delete per provider) keeps working as before — the
  saved-domains list is an additional surface, not a replacement.

See also `docs/ssh/tunel-cloudflared-nativo.md` for the DNS resolution mechanism of the embedded
Cloudflare binary.
