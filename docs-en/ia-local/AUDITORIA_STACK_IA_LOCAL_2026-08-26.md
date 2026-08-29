# Local AI stack audit — Ollama, Chat, local providers

A real code audit of the Ollama integration, the multi-engine Chat screen, and the mechanism that
lets third-party CLIs use local AI as a backend. The pure llama.cpp engine is covered in a
separate document ([AUDITORIA_LLAMA_CPP_2026-08-26.md](AUDITORIA_LLAMA_CPP_2026-08-26.md)).

## Summary

Everything audited in this round is genuinely functional, with no dead code or decorative UI.

## Ollama model management

The Ollama models screen uses Ollama's real HTTP API client to list installed models (no fixed
lists), download models from a curated catalog with real live progress (percentage, speed,
estimated time), view an installed model's details, and delete it. It also offers manual download
of a model by name.

The Ollama configuration screen exposes real inference parameters — saving them writes to a real
user config file, which the Ollama process itself reads on every startup. It includes centralized
process management (restart service, view details, GPU/Vulkan info, update, uninstall), a toggle
to listen on the local network, and generation of real "custom models" from a `Modelfile` built
from the saved configuration.

## Chat screen: three engine families

The Chat screen supports three real engine families, with an explicit selector before entering
(a deliberate design decision, not an incomplete feature):

1. **Ollama** — via local HTTP, validating the module is installed before letting the user pick
   it.
2. **Local AI (llama.cpp)** — with two transports the user can choose between: the in-process
   embedded engine, or the local HTTP server (see [LLAMA_CPP_EMBEBIDO.md](LLAMA_CPP_EMBEBIDO.md)).
3. **Cloud API (user's own key)** — several real providers supported with their specific
   endpoints. The API key is stored encrypted, not in plain text.

There's no automatic fallback between engines — it's an explicit user choice, shown every time
the user returns to the Chat screen, not "if engine A fails, try engine B" logic.

Other confirmed pieces: a shell mode inside the chat (runs commands directly, bypassing the AI
model), web search results injected as context when that feature is available, image attachments
enabled only for engines that support vision (the pure local engine doesn't support it yet —
explicitly documented), persisted history with a configurable limit, streaming with stall
detection (a watchdog) and single reconnection (to avoid duplicate messages).

## Local AI integration in third-party CLIs

Out of a broader set of third-party CLIs evaluated as candidates, only three confirmed real
support for a user-configurable OpenAI-compatible endpoint against their real repository — the
rest are tied to their own cloud backend with no configurable endpoint field, so they weren't
integrated. The supported CLIs expose a "Use Ollama/local AI" button from the generic module UI,
which assembles the base URL and model name and calls each one's specific configuration function.

One particular module (a Python framework, not a Node CLI) has its own parallel local-provider
selection mechanism (choosing between Ollama or the local server), with the same concept but
separate code — consistent with its configuration format being different from the other CLIs (not
a standard `.env`/JSON/TOML).

The component that manages `.gguf` models for the embedded engine (resumable downloads via the
HTTP `Range` header, multi-layer validation of real size and format, import from the system file
picker, cleanup of orphaned partial downloads) is fully implemented, with no simulated pieces.

## RAM detection before suggesting a large model

The Ollama model catalog screen queries the device's real total RAM (no extra permissions
required) and compares it against an estimate of RAM needed for the model (computed as disk size
times a factor, documented with real evidence: a 7B-parameter model in Q4 quantization takes about
4.7GB on disk but needs about 6GB of real RAM to load). If the chosen model likely won't fit in
available RAM, a visible warning is shown — without blocking the download, a deliberate design
decision (the user may have valid reasons to download it anyway, for instance to use later on a
different device). This mechanism exists in the Ollama catalog; the GGUF model catalog for the
embedded engine has its own documented disk-space check in the llama.cpp module, though not
necessarily the same estimated-RAM check — a possible minor inconsistency between the two catalog
screens, to be confirmed in a future audit.

## Conclusion

None of the four audited areas showed dead code or UI with no real functionality behind it.
Earlier audits had found parts of this stack to be mockups (a static model list, configuration
parameters that weren't applied) — those findings no longer apply to the current code; they were
resolved in later rounds.
