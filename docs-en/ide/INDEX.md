# docs/ide/ — Studio, Kairos's integrated IDE

This folder documents "Studio", the code editing and project management screen built into
Kairos.

- [IDE_INTEGRADO.md](IDE_INTEGRADO.md) — current architecture: Studio lives inside the same APK
  as the rest of Kairos, as just another screen, and why it was designed that way.
- [IDE_EXTERNO.md](IDE_EXTERNO.md) — historical design note: the alternative that was evaluated
  (a standalone Android IDE app) and why it was dropped in favor of full integration.
- [PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md](PLAN_REDISENO_ESTUDIO_IDE_2026-08-22.md) — Studio
  redesign: multi-project support, an independent theme system, and real autocompletion via LSP.
- [AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md](AUDITORIA_ESTUDIO_PROFUNDA_2026-08-19.md) — real
  bugs found and fixed in the editor, the file tree, the Git panel, keyboard shortcuts, and
  Studio's search engine.
