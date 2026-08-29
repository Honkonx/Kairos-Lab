# docs/ia-local/ — Kairos embedded local AI engine

This folder documents Kairos's embedded local AI inference engine (llama.cpp, with an in-process
wrapper and an OpenAI-compatible HTTP server), and technical audits of the local AI stack as a
whole (llama.cpp, Ollama, and integration with third-party CLIs).

- [LLAMA_CPP_EMBEBIDO.md](LLAMA_CPP_EMBEBIDO.md) — architecture of the embedded local inference
  engine, the `llamaserver` HTTP server (port 8085), and the build process (Vulkan, NDK, CMake).
- [AUDITORIA_LLAMA_CPP_2026-08-26.md](AUDITORIA_LLAMA_CPP_2026-08-26.md) — real code audit of the
  llama.cpp module: build system, Vulkan, UI-to-backend consistency.
- [AUDITORIA_STACK_IA_LOCAL_2026-08-26.md](AUDITORIA_STACK_IA_LOCAL_2026-08-26.md) — audit of the
  Ollama integration, the multi-engine chat, and local AI providers for third-party CLIs.
