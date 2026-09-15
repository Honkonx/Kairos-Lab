# Web search without an API key

Kairos's local AI chat already had one way to give a model access to the internet: the "Web"
toggle, which uses Ollama's Web Search API — but that path requires the user to create a free
API key on their Ollama account. This feature adds a second path that **requires no key at
all**: direct search-result scraping, integrated into the chat with a command and a button.

---

**App:** Kairos (termux-app fork)
**Component:** `WebSearchService.kt`
**Integration:** `ChatFragment.kt` (`/buscar` command and "🔍 Search" button)

---

## 1. How it works

`WebSearchService` queries the no-JavaScript HTML version of DuckDuckGo with real browser
headers (without them, the response is an anti-bot verification page instead of results). The
response is always classified into one of these cases, with no ambiguity:

- **Real results** — a title, URL, and text snippet are returned for each result.
- **No results** — the search itself genuinely found nothing (an explicit marker, not an
  assumption).
- **Blocked** — an anti-bot challenge or a server error code was detected. Any response that
  doesn't clearly fit "results" or "no results" is treated as "blocked", never as "no results" —
  it's safer to retry than to tell the user the web has nothing when the search mechanism
  actually failed.

After several consecutive failures, an automatic circuit breaker stops trying for a few minutes
before attempting again, to avoid hammering a block that's already known to be active.

## 2. Protection against internal URLs

Before following any URL from a result, Kairos checks that it doesn't point to a private/local
network address on the device or its internal network (a standard safeguard for this kind of
risk when an app follows URLs obtained from an external source) — the check resolves the actual
domain name rather than just inspecting the URL text, so it can't be bypassed by a domain that
resolves to an internal IP either.

## 3. How it's used in the chat

Two ways, both manual:

1. **Command** `/buscar <query>` typed directly into the chat.
2. **"🔍 Search" button** in the chat bar, which opens a dialog to type the query.

## 4. Current limitation — manual, not automatic

The AI model (Ollama, the local engine, or a cloud provider) **cannot trigger this search on its
own** — it's always the user who decides to search, via the command or the button. Kairos
doesn't yet have a real "function calling" mechanism where the model itself decides to search
mid-reasoning and gets the result back in a structured way; that remains future work.
