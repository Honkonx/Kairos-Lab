# Repo local (.deb) — empaquetado de módulos (`repo`)

**Módulo de Kairos** — gestionado desde la UI (pestaña Módulos, `RepoFragment.kt`). Instalación
manejada por la app vía `ProcessBuilder` → `modulos/repo.sh`; la misma pantalla también expone un
mecanismo hermano, `modulos/moduledeb.sh`, para empaquetar módulos propios de Kairos que no pasan
por `dpkg`.

**Qué resuelve**: reinstalar en otro dispositivo (u otro Kairos) sin volver a descargar ni
reparchear nada desde cero — un paquete `apt` ya instalado, o una instalación propia de un módulo
de Kairos ya hecha y funcionando, se reempaquetan en un `.deb` real que después se instala con
`pkg install`.

---

**Script:** `modulos/repo.sh` (+ `modulos/moduledeb.sh`) — espejo en `app/src/main/assets/scripts/`
**Fragment:** `app/src/main/java/com/termux/app/ui/RepoFragment.kt`
**`id` en `modules.json`:** `repo` — sin switch (herramienta de empaquetado, no un servicio con
proceso persistente), categoría desarrollo, comando de terminal `repo`

---

## 1. Descripción general

Kairos tiene dos mecanismos hermanos de empaquetado `.deb`, con propósitos distintos pero que
comparten pantalla:

| Mecanismo | Qué empaqueta | Para qué sirve |
|---|---|---|
| `repo.sh` | Paquetes **apt/pkg reales** ya instalados en el dispositivo (ej. `nano`, `nmap`, cualquier paquete de Termux) | Reinstalar el mismo paquete apt en otro dispositivo, vía `pkg install kairos-<paquete>` desde el repo local propio |
| `moduledeb.sh` | **Instaladores propios de Kairos** que no pasan por `dpkg` (Claude Code, OpenCode, n8n — módulos que descargan binarios sueltos y los parchean, en vez de instalar vía `pkg`) | Reempaquetar una instalación de módulo ya hecha y parcheada, para reinstalarla en otro dispositivo sin re-descargar/re-parchear desde cero |

Ambos generan el mismo formato de `.deb` real (`debian-binary` + `control.tar.xz` +
`data.tar.xz`, el formato estándar de Debian/Ubuntu/Termux) — son independientes entre sí, cada
uno con su propia lógica de construcción.

## 2. Permisos

- **Android**: ninguno específico — corre en el mismo proceso Termux del asistente de
  configuración, sin permisos adicionales.
- **Termux interno**: lee archivos ya instalados en el propio filesystem de Termux (`dpkg -L`,
  metadata de `dpkg`) y escribe `.deb`/índices del repo en `$HOME` — no requiere root, no abre
  puertos, no depende de red salvo que se quiera exportar/compartir el resultado.

## 3. `repo.sh` — repo apt local en el dispositivo

Arma un repositorio apt real dentro del propio dispositivo (estructura estándar
`dists/stable/main/binary-aarch64`), con estos subcomandos:

| Subcomando | Qué hace |
|---|---|
| `init` | Crea la estructura del repo local |
| `add <módulo>` | Empaqueta un binario+scripts de un módulo de Kairos con el prefijo `kairos-<id>` |
| `pack <paquete>` | Reempaqueta **cualquier** paquete apt ya instalado en el dispositivo, sin prefijo — permite reinstalar exactamente ese mismo paquete en otro lado |
| `publish` | Regenera los índices del repo (`Packages`/`Packages.gz`/`Release`, con checksums MD5/SHA1/SHA256) tras agregar o quitar un `.deb` |
| `remove <paquete>` | Saca uno o más `.deb` del repo local y republica el índice |
| `source` | Imprime la línea lista para agregar el repo local a la configuración de apt del dispositivo |

`pack` reconstruye el `.deb` sin depender de herramientas externas de repaquetado (no
disponibles en este entorno): lee la lista real de archivos instalados del paquete y los scripts
de mantenimiento reales que ya trae Debian/Termux (los que se ejecutan antes/después de
instalar o quitar un paquete), y los preserva en el `.deb` reconstruido — el paquete resultante
se comporta igual que uno instalado desde el repo oficial.

**Firma GPG (opcional)**: el repo local se puede firmar con una clave GPG propia del usuario —
Kairos nunca genera ni controla esa clave, es 100% decisión y responsabilidad del usuario. Sin
firma, el repo se agrega como "confiable" explícitamente (comportamiento estándar para un repo
local propio); con firma, usa el mismo mecanismo de verificación que cualquier repo apt oficial.

Cada paquete empaquetado con `pack` queda registrado (paquete, versión, fecha, ruta del `.deb`)
en un índice local propio, separado del registro general de módulos de Kairos.

## 4. `moduledeb.sh` — empaquetado de módulos propios de Kairos

Cubre el caso que `repo.sh` no puede resolver: un módulo de Kairos que instaló su binario
descargándolo directo (no vía `pkg`) y aplicándole parches propios para que corra en Termux —
sin un paquete `dpkg` de origen, no hay nada que `dpkg -L` pueda leer.

| Subcomando | Qué hace |
|---|---|
| `pack <id>` | Empaqueta la instalación ya hecha y funcionando de un módulo en un `.deb` |
| `install <ruta.deb>` | Instala/aplica ese `.deb` en el dispositivo destino |
| `list` | Lista qué módulos tienen soporte de empaquetado disponible |

El `.deb` generado incluye la información de qué dependencias necesita el módulo y cómo
verificar que quedó funcionando — al instalarlo en otro dispositivo, primero confirma que las
dependencias estén presentes (avisando cuáles faltan si no), después verifica si el módulo ya
funciona tal cual, y solo si hace falta aplica el parche correspondiente antes de volver a
verificar.

Cobertura actual: un conjunto inicial de módulos con soporte de empaquetado (incluye variantes
de Claude Code, OpenCode y n8n) — cada uno documenta honestamente sus propias limitaciones (por
ejemplo, qué variante de instalación cubre, o qué archivos no llegan a capturarse si el proyecto
original creció respecto a lo que se conocía al momento de escribir el soporte). El mecanismo
está pensado para escalar a cualquier módulo del catálogo con el tiempo, no solo a los que ya lo
tienen hoy.

## 5. Detección de estado

- **Instalación del módulo `repo` en sí**: registry (`repo.installed=true`).
- No hay concepto de "corriendo"/"detenido" — es una herramienta de empaquetado que se invoca
  bajo demanda, sin proceso de fondo propio.

## 6. Pantalla real de la app (`RepoFragment.kt`)

Una sola pantalla cubre ambos mecanismos, con dos secciones claramente separadas para no
confundir al usuario sobre cuál de los dos flujos está usando:

**Sección "Repositorio propio"**:
- Inicializar/reparar el repo local.
- Publicar el índice (tras agregar o quitar paquetes).
- Ver la línea para agregar el repo local a la configuración de apt.
- Firmar el repo con una clave GPG propia (opcional; si el usuario no tiene una clave, se
  explica cómo crearla — Kairos nunca la genera por él).
- Tabla de paquetes apt instalados en el dispositivo, con un botón "Empaquetar" por fila que
  corre `repo pack <paquete>`.

**Sección "Crear .deb de módulo"**: botones para los módulos con soporte de empaquetado
disponible hoy, que invocan `moduledeb pack <id>`.

**Sección ".deb generados"**: lista de los paquetes ya empaquetados (tamaño y fecha reales),
con acciones para ver el contenido del `.deb` (metadata + árbol de archivos, antes de instalar)
o eliminarlo del repo local (no afecta el paquete que ya está instalado en el dispositivo, solo
saca el `.deb` del repo).

## 7. Notas técnicas

- Los dos mecanismos (`repo.sh`/`moduledeb.sh`) son independientes entre sí — no comparten
  código de construcción del `.deb`, aunque ambos producen el mismo formato real.
- El registro de `.deb` generados se lee directo del índice local (JSON plano) sin invocar
  ningún script — es una operación de solo lectura.
- La cobertura de `moduledeb.sh` (qué módulos tienen soporte de empaquetado) es un conjunto que
  crece con el tiempo — un módulo del catálogo que todavía no tiene soporte simplemente no
  aparece como opción en la sección "Crear .deb de módulo" hasta que se agregue.
