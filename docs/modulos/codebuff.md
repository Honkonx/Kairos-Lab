# Codebuff

Codebuff (CodebuffAI) — CLI de agente de codificación, compatible con cualquier modelo vía
OpenRouter (sin login/API key propio automatizable). Cae en el Fragment compartido
`CliToolFragment.kt` (ver `docs/modulos/cli-tools.md`) — sin login/prompt directo/modelo
documentados por línea de comandos, queda con "Abrir en terminal" + gestión de proyectos.

## 1. Instalación (`modulos/codebuff.sh`) — mecanismo en 2 etapas

El paquete npm real `codebuff` no es la app en sí — es un **launcher delgado** (~35KB, sin ningún
addon nativo) que recién descarga el binario real la **primera vez que se ejecuta el comando**
`codebuff` (al directorio de configuración del launcher). El script dispara esa primera ejecución
a propósito (`timeout 90 codebuff --version`) para forzar la descarga, y recién ahí aplica
`patchelf` (mismo patrón glibc que otros módulos que instalan binarios glibc sobre Bionic).

Consideraciones reales resueltas en el script de instalación:
- El symlink que deja `npm install -g` tiene un shebang `#!/usr/bin/env node` que no existe en
  Termux tal cual — se corrige el wrapper después de instalar.
- El launcher calcula la clave de descarga a partir de la plataforma/arquitectura detectada en
  tiempo de ejecución, que no coincide con ninguna de las claves de su propio mapa de targets
  soportados en Android/Termux — se fuerza `CODEBUFF_BINARY_TARGET=linux-arm64` (override oficial
  que el propio launcher expone vía variable de entorno, sin parchear nada), y se persiste en
  `~/.bashrc` para que también aplique en sesiones de terminal nuevas.
- `codebuff --version` no responde rápido (el launcher dibuja un banner ASCII animado antes de
  cualquier salida) — la verificación de instalación no exige un semver parseado, solo descarta
  los errores fatales conocidos ("Unsupported platform"/"ENOENT").

## 2. Controles de la pantalla (`CliToolFragment.kt`, config `CLI_MODULE_CONFIGS["codebuff"]`)

| Control | Qué hace | Por qué |
|---|---|---|
| "⌨ Abrir en terminal" | `unset LD_PRELOAD; codebuff` | Sin login/prompt/modelo documentados — configuración de proveedor de modelo (OpenRouter) queda para que el usuario la haga manualmente dentro de la sesión |
| "🗂 Gestionar proyectos" | Menú compartido de gestión de proyectos | Mismo mecanismo compartido que el resto de CLIs |
| "＋ Crear proyecto desde plantilla" | `codebuff --create '<template>' '<nombre>'` | Diálogo de 2 campos (plantilla + nombre) |
| "🔄 Actualizar" / "🗑 Desinstalar" | Mantenimiento estándar | — |
