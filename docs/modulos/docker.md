# Docker — Referencia de Kairos
## Documentación técnica completa

**Documento de referencia** — módulo `docker` (`id: "docker"` en `modules.json`). A diferencia del
resto de los módulos de Kairos, **este módulo no instala nada**. Es un módulo "honesto": explica
por qué Docker real no puede funcionar en el dispositivo y dirige al usuario al módulo `udocker`,
que sí es una alternativa real disponible en Kairos.

**App:** Kairos (fork de termux-app) · ARM64 · Android
**Script:** `modulos/docker.sh` (133 líneas, v1.0.0)
**Fragment:** `app/src/main/java/com/termux/app/ui/DockerFragment.kt` (57 líneas)

---

## 1. Por qué existe

El header de `modulos/docker.sh` (líneas 6-27) documenta el motivo, con el mismo criterio de
honestidad que `modulos/mimocode.sh` y `modulos/qemu.sh` (no fingir que algo funciona cuando no funciona):

Un daemon Docker real (`dockerd`) necesita acceso directo del kernel de Linux a:
- **namespaces** — aislamiento de procesos, red y mount
- **cgroups** — límites de recursos
- **overlayfs** — para las capas de imagen

El kernel de Android, para apps **sin root**, tiene esas funciones deshabilitadas/inaccesibles
por diseño del sandbox de la plataforma. Esto **no es una limitación de Termux** — es una barrera
del sistema operativo Android mismo. No hay forma de "activarlas" desde userspace sin rootear el
dispositivo.

Con root sí sería técnicamente posible (namespaces/cgroups reales quedan accesibles), pero eso
exige rootear el teléfono — algo que Kairos está pensada explícitamente para **no** requerir,
y que este módulo no va a empujar al usuario a hacer. Además, el kernel stock de muchos fabricantes
no compila namespaces/cgroups, así que root solo no alcanza.

---

## 2. Qué hace el script exactamente (`modulos/docker.sh`)

El script **no intenta instalar ni simular Docker de ninguna forma**. Su comportamiento completo:

1. Parsea flags (`docker.sh:52-62`): `--silent`, `--describe`, `--describe-files` (con `files: []`, `verify_cmd: "false"` y `not_covered` que explica que no hay nada que empaquetar), `--force` (aceptado por consistencia con el resto de `modulos/`, pero sin efecto).
2. Si `--describe`: imprime el manifiesto JSON declarativo y sale (ver sección 4).
3. Carga `lib.sh` (helpers compartidos: `registry_write`, `notify_event`, `log`, colores) con `source ... || exit 1`, así que un `lib.sh` ausente rompe el script.
4. Detecta *best-effort* si el dispositivo parece rooteado, **solo para el mensaje** — no cambia
   el comportamiento del script en ningún caso (puede disparar un prompt de root en dispositivos rooteados):
   ```bash
   if command -v su &>/dev/null && su -c "id" &>/dev/null; then
     _IS_ROOTED=true
   fi
   ```
5. Imprime la explicación (banner si no es `--silent`, siempre el bloque `[INFO]`):
   - Si el dispositivo parece rooteado: aclara que igual este módulo no instala Docker (fuera de
     alcance de Kairos), y que con root el usuario podría instalarlo manualmente *fuera* de Kairos.
   - Si no parece rooteado: recomienda directamente udocker como la vía real.
6. Escribe en el registry `~/.android_server_registry`:
   ```bash
   registry_write docker "installed=false" "reason=no_namespaces_cgroups_unrooted_android" \
     "alternative=udocker" "rooted_device=$_IS_ROOTED"
   ```
   **`installed` queda `false` siempre, sin excepción** — no existe ningún camino en el script
   que lo ponga en `true`. Esto es deliberado: la UI nunca debe mostrar "Docker instalado".
7. `notify_event "docker" "explained" "not_supported_use_udocker"` + `log "..."`.
8. `exit 0` — **no es un error**. El comportamiento esperado del módulo es explicar y
   salir con éxito, no fallar.

No hay rama de código que descargue, compile, ni ejecute nada relacionado con Docker real.

---

## 3. Pantalla de la app — `DockerFragment.kt`

`DockerFragment.kt` (extiende `BaseModuleFragment`) reemplaza al "Abrir en terminal" genérico de
`GenericModuleFragment` (inútil acá porque el script solo imprime texto y sale), y está registrado
en `ModuleDetailNavigator.kt`:

```kotlin
"docker" -> DockerFragment()
```

La pantalla es 100% texto + navegación (no ejecuta el script ni toca sesiones de terminal):

1. **Card de advertencia** — "⚠ Docker real no funciona en este dispositivo", con la explicación
   en lenguaje simple (sin jerga de namespaces/cgroups): Docker necesita permisos especiales que
   Android no da a ninguna app sin rootear, no es un problema de Kairos ni de Termux, y rootear
   está fuera de lo que la app busca hacer (`strings_docker.xml:4-5`).
2. **Card "ALTERNATIVA REAL"** — explica qué es udocker en una frase: corre imágenes de Docker
   Hub (alpine, ubuntu, debian, etc.) sin root, con aislamiento más débil pero funcional, "es la
   misma tecnología que ya usa el módulo n8n por dentro" (`strings_docker.xml:6-7`).
3. **Botón de acción primario** — "→ Ir a udocker (alternativa real)", que navega directo a
   `UdockerFragment()` (`navigateTo(UdockerFragment())`, `DockerFragment.kt:53-55`) en vez de abrir
   terminal o ejecutar nada.

El Fragment nunca invoca `docker.sh`; toda la lógica del script (detección de root, registry)
corre solo si algo dispara la ejecución del script por fuera de esta pantalla (p. ej. el flujo
genérico de instalación).

| Control | Qué hace | Por qué |
|---|---|---|
| Texto "⚠ Docker real no funciona en este dispositivo" + explicación | Solo lectura | Docker real (`dockerd`) necesita namespaces + cgroups del kernel accedidos directo por un daemon con privilegios — Android bloquea eso a apps sin root por diseño del sandbox |
| "→ Ir a udocker (alternativa real)" | `navigateTo(UdockerFragment())` | udocker corre imágenes reales de Docker Hub en userspace vía PRoot, sin namespaces/cgroups reales pero sin necesitar root |

---

## 4. Manifiesto declarativo (`--describe`)

```json
{"id":"docker","supports_silent":true,"supports_force":false,"variants":[],"variant_required":false,"experimental":true,"note":"Docker real (dockerd) NO es posible sin root en Android/Termux — el kernel no expone namespaces/cgroups a apps sin privilegios. Este script no instala nada, explica el motivo y dirige a modulos/udocker.sh como alternativa real ya disponible en Kairos"}
```

## 5. Entrada en `modules.json`

Campos relevantes (`modules.json:733-755`): `id: "docker"`, `name: "Docker"`, `repo: "Honkonx/kairos-lab"`,
`script: "docker.sh"`, `icon: "🐳"`, `port: ""`, `size: "0MB (no instala nada)"`, `type: "Nativo"`,
`requiresProot: false`, `estimate: "instantáneo"`, `hasSwitch: false`, `experimental: true`,
`arch: "bionic"`, `category: "dev"`, `installMethods: []`, `requires: []`, `catalogVersion: "1.0.0"`,
`recommended: false`, `downloads: 0`, más una `description` completa.

Puntos notables:
- `hasSwitch: false` — no hay toggle ON/OFF (no tiene sentido activar/desactivar algo que nunca
  se instala).
- `size: "0MB (no instala nada)"` y `estimate: "instantáneo"` — reflejan que el script no descarga
  ni instala nada, solo imprime y sale.
- `installMethods: []` y `requires: []` — vacíos, coherente con que no hay instalación real.
- `experimental: true` — marcado igual que otros módulos "explicativos" (`qemu`, `mimocode`).

---

## 6. Registry — por qué `installed` nunca es `true`

`docker.sh` escribe siempre:

```
docker.installed=false
docker.reason=no_namespaces_cgroups_unrooted_android
docker.alternative=udocker
docker.rooted_device=<true|false>
```

Esto es intencional y a prueba de cambios accidentales: cualquier parte de la UI que
consulte el registry para decidir si mostrar el módulo como "instalado" (p. ej. un check verde,
un badge de estado) nunca lo va a ver así para `docker`, sin importar si el dispositivo tiene
root o no. Es la única escritura del módulo: no crea directorios ni binarios.

---

## 7. Relación con `udocker` — en qué se diferencian y por qué son dos módulos separados

`docker` y `udocker` (ver `UDOCKER.md`) tocan el mismo problema — correr contenedores
de estilo Docker en Android sin root — pero son cosas distintas:

| | `docker` (este módulo) | `udocker` |
|---|---|---|
| **Qué es** | Un módulo explicativo — no instala nada, solo redirige | Una herramienta real que **sí** corre imágenes de contenedores |
| **Motor** | N/A (no ejecuta contenedores) | PRoot (interceptación de syscalls por `ptrace`) |
| **¿Docker real (dockerd)?** | No, ni lo intenta | Tampoco — es un *emulador* de contenedores en userspace, no Docker real |
| **Namespaces/cgroups del kernel** | No aplica | No los usa ni los necesita — por eso funciona sin root |
| **Root requerido** | No (y el script nunca lo pide) | No |
| **`modulos/*.sh` propio** | `modulos/docker.sh` | `modulos/udocker.sh` |
| **Entrada en `modules.json`** | `id: "docker"`, `hasSwitch: false` | `id: "udocker"` (`modules.json:655`) |
| **Fragment** | `DockerFragment.kt` (informativo + botón a udocker) | `UdockerFragment.kt` (funcional) |
| **`installed` en registry** | Siempre `false` | Depende de si `command -v udocker` encuentra el binario |
| **Aislamiento de red** | N/A | Ninguno real — comparte el stack de red del host (`localhost` del contenedor = `localhost` de Android) |

**Por qué existen los dos por separado:** `docker` cumple un rol de **UX/honestidad** — es el
módulo al que llega un usuario que busca "Docker" por nombre (porque es el término que conoce), lo
encuentra en la Tienda/lista de módulos por su ícono 🐳 y nombre familiar, y en vez de fallar
silenciosamente o instalar algo que no es Docker haciéndolo pasar por tal, se le explica la
limitación real del sistema operativo y se lo deriva al lugar correcto.

En otras palabras: **`docker.sh` no es una versión reducida de `udocker`, ni un paso previo a
instalarlo** — es una pantalla de aterrizaje semántica para el término "Docker" que redirige a la
herramienta correcta, con cero superposición funcional.

---

## 8. Uso desde la app

```bash
bash docker.sh --silent
```

Flags soportados:
- `--silent` — sin preguntas (igual imprime toda la explicación por `[INFO]`, solo omite el
  banner interactivo).
- `--describe` — imprime el manifiesto declarativo (sección 4) y sale.
- `--force` — aceptado por consistencia con el resto de `modulos/`, sin efecto en este script.

## 9. Seguridad

Sin secretos, sin `curl | bash`, sin interpolación de entrada de usuario. Único riesgo menor: invoca
`su -c "id"` si `su` existe, lo que puede disparar un prompt de root en dispositivos rooteados
(por lectura de código; efecto real no probado).

## 10. Limitaciones conocidas

- La `description` de `modules.json` es muy larga para una tarjeta de catálogo.
- Inferencia: el módulo aparece en el catálogo con icono 🐳 aunque no instala nada, lo que puede
  confundir a un usuario que busque Docker real; es la decisión de UX descrita en la sección 7.

## 11. Fecha de alta

2026-08-16 (`modulos/docker.sh`).
