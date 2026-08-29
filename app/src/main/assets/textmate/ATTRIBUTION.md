# Atribución — gramáticas TextMate

Los archivos `.tmLanguage.json` / `language-configuration.json` de este directorio (java,
kotlin, python, xml, html, javascript, typescript, markdown, json, yaml, shellscript,
css) y el tema `darcula.json` son una copia directa de
`referencia/ides/Xed-Editor-main/core/main/src/main/assets/textmate/` — proyecto
[Xed-Editor](https://github.com/Xed-Editor/Xed-Editor) (antes `Rohitkushvaha01/Xed-Editor`),
licencia **GPLv3** (ver `referencia/ides/Xed-Editor-main/LICENSE`).

Solo se copiaron los 12 lenguajes que `EditorFragment.kt` (`EXTENSION_TO_SCOPE`) mapea
realmente — no las ~48 gramáticas completas del proyecto de origen.

Ver `docs/referencias/REFERENCIA_XED_EDITOR.md` para el análisis completo de por qué se adoptó
este set de archivos y `EditorFragment.kt` para el código de wiring (sora-editor,
LGPL-2.1, dependencia sin modificar — ver `app/build.gradle`).
