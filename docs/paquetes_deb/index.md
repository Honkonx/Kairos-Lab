# Paquetes `.deb` de módulos de Kairos — índice

> Documentación del sistema de `.deb` de módulos: cómo es, qué requiere y usa, cómo se crean y cómo se
> instalan. Reemplaza como referencia vigente al diseño v1 de `moduledeb`.

## Idea central

Un `.deb` de Kairos **no es un paquete de apt cualquiera**: es un módulo ya instalado empaquetado junto con
la información que Kairos necesita para decidir si instalarlo. Esa información son **dos archivos dentro del
propio paquete**, y son lo **primero que se lee, antes de siquiera pensar en instalar**:

1. `DEBIAN/manifest.json` — qué es el paquete, qué rutas toca y qué necesita para funcionar (paquetes `pkg`,
   complementos, otros módulos).
2. `DEBIAN/SHA256SUMS.txt` — el hash de `manifest.json` y de **cada** archivo que trae.

Sin esos dos archivos el paquete **no se instala**, venga de donde venga.

## Un solo sistema para tres orígenes

| Origen | Quién lo dispara | Código |
|---|---|---|
| **GitHub** (Release de `Honkonx/Kairos-Lab`) | hoja de instalación → origen "GitHub" | `ModuleDebInstaller.installFromGithub` |
| **Paquete local** (archivo elegido por el usuario) | Tienda → "Paquete local" | `LocalPluginManager.installDeb` → `ModuleDebInstaller.installFromDeb` |
| **Creado desde el módulo en el APK** | Repo → "Crear .deb de un módulo" | `moduledeb pack <id>` (genera el mismo formato) |

Los tres terminan en `ModuleDebInstaller.installFromDeb`, que ejecuta siempre el mismo flujo
([`flujo-instalacion.md`](flujo-instalacion.md)). La única diferencia es la **confianza**: GitHub trae un hash publicado aparte
([`catalogo-github.md`](catalogo-github.md)); un archivo local no, así que pide confirmación mostrando el resumen.

## Documentos

- [`formato-v2.md`](formato-v2.md) — qué lleva `manifest.json` y `SHA256SUMS.txt` (campos, ejemplo real).
- [`flujo-instalacion.md`](flujo-instalacion.md) — pasos y comprobaciones, de leer el JSON a aplicar.
- [`crear-paquetes.md`](crear-paquetes.md) — `moduledeb pack`, `reseal`, `inspect`; cómo se escribe `--describe-files`.
- [`catalogo-github.md`](catalogo-github.md) — `catalog.json` y `SHA256SUMS.txt` del Release; cómo se publican.

## Archivos de código

`modulos/moduledeb.sh` (+ copia en `app/src/main/assets/scripts/`), `util/ModuleDebInstaller.kt`,
`util/LocalPluginManager.kt`, `ui/PluginsFragment.kt` (diálogo de confirmación), `ui/BottomSheetInstalacion.kt`,
`ui/RepoFragment.kt`, `tools/moduledeb-release.sh`. Tests: `ModuleDebInstallerTest`.

## Estado

Implementado y probado en WSL con un `.deb` real en formato v1 del módulo `claude` (rechazo de v1, `reseal`,
`inspect`, rechazo de archivo extra, de `.bashrc` no declarado, de manifest alterado y de ruta fuera de
`$HOME`/`$PREFIX`).
**No probado en el dispositivo** (el flujo completo APK → GitHub/local → instalar).
Los paquetes `.deb` en formato v1 (sin `manifest.json` ni `SHA256SUMS.txt`) no se instalan: hay que regenerarlos
con `moduledeb pack` o convertirlos con `moduledeb reseal` (ver [`catalogo-github.md`](catalogo-github.md)).
