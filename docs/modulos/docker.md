# Docker

**Referencia técnica** — módulo `docker` (`id: "docker"` en `modules.json`). A diferencia del
resto de los módulos de Kairos, **este módulo no instala nada**. Es un módulo "honesto": explica
por qué Docker real no puede funcionar en el dispositivo y dirige al usuario al módulo `udocker`,
que sí es una alternativa real ya disponible en Kairos.

**App:** Kairos (fork termux-app) · ARM64 · Android
**Script:** `modulos/docker.sh`
**Fragment:** `app/src/main/java/com/termux/app/ui/DockerFragment.kt`

---

## 1. Por qué existe

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
y que este módulo no empuja al usuario a hacer.

---

## 2. Qué hace el script exactamente (`modulos/docker.sh`)

El script **no intenta instalar ni simular Docker de ninguna forma**. Su comportamiento completo:

1. Parsea flags: `--silent`, `--describe`, `--force` (aceptado por consistencia con el resto de
   `modulos/`, pero sin efecto — no hay nada que forzar).
2. Si `--describe`: imprime el manifiesto JSON declarativo y sale (ver sección 4).
3. Carga `lib.sh` (helpers compartidos: `registry_write`, `notify_event`, `log`, colores).
4. Detecta *best-effort* si el dispositivo parece rooteado, **solo para el mensaje** — no cambia
   el comportamiento del script en ningún caso:
   ```bash
   if command -v su &>/dev/null && su -c "id" &>/dev/null; then
     _IS_ROOTED=true
   fi
   ```
5. Imprime la explicación (banner si no es `--silent`, siempre el bloque `[INFO]`):
   - Si el dispositivo parece rooteado: aclara que igual este módulo no instala Docker (fuera de
     scope de Kairos), y que con root el usuario podría instalarlo manualmente *fuera* de Kairos
     si quisiera.
   - Si no parece rooteado: recomienda directamente udocker como la vía real.
6. Escribe en el registry `~/.android_server_registry`:
   ```bash
   registry_write docker "installed=false" "reason=no_namespaces_cgroups_unrooted_android" \
     "alternative=udocker" "rooted_device=$_IS_ROOTED"
   ```
   **`installed` queda `false` siempre, sin excepción** — no existe ningún camino en el script
   que lo ponga en `true`. Esto es deliberado: la UI nunca debe mostrar "Docker instalado".
7. `notify_event "docker" "explained" "not_supported_use_udocker"` + `log "..."`.
8. `exit 0` — **no es un error**. El comportamiento correcto y esperado del módulo es explicar y
   salir con éxito, no fallar.

No hay rama de código que descargue, compile, ni ejecute nada relacionado con Docker real.

---

## 3. Pantalla real de la app — `DockerFragment.kt`

`DockerFragment.kt` (`app/src/main/java/com/termux/app/ui/DockerFragment.kt`) es una pantalla
dedicada, registrada en `ModuleDetailNavigator.kt`:

```kotlin
"docker" -> DockerFragment()
```

Contenido de la pantalla (extiende `BaseModuleFragment`, sin ejecutar el script ni tocar
sesiones de terminal — es 100% texto + navegación):

1. **Card de advertencia** — "⚠ Docker real no funciona en este dispositivo", con la explicación
   en lenguaje simple (sin jerga de namespaces/cgroups): Docker necesita permisos especiales que
   Android no da a ninguna app sin rootear, no es un problema de Kairos ni de Termux, y rootear
   está fuera de lo que la app busca hacer.
2. **Card "ALTERNATIVA REAL"** — explica qué es udocker en una frase: corre imágenes de Docker
   Hub (alpine, ubuntu, debian, etc.) sin root, con aislamiento más débil pero funcional, misma
   tecnología que ya usa el módulo n8n por dentro.
3. **Botón de acción primario** — "→ Ir a udocker (alternativa real)", que navega directo a
   `UdockerFragment()` (`navigateTo(UdockerFragment())`) en vez de abrir terminal o ejecutar
   nada.

El Fragment nunca invoca `docker.sh` — toda la lógica del script (detección de root, registry)
corre solo si algo dispara la ejecución del script por fuera de esta pantalla (p. ej. flujo
genérico de instalación); la pantalla en sí es puramente informativa/de navegación.

---

## 4. Manifiesto declarativo (`--describe`)

```json
{"id":"docker","supports_silent":true,"supports_force":false,"variants":[],"variant_required":false,"experimental":true,"note":"Docker real (dockerd) NO es posible sin root en Android/Termux — el kernel no expone namespaces/cgroups a apps sin privilegios. Este script no instala nada, explica el motivo y dirige a modulos/udocker.sh como alternativa real ya disponible en Kairos"}
```

## 5. Entrada en `modules.json`

```json
{
  "id": "docker",
  "name": "Docker",
  "repo": "Honkonx/kairos-lab",
  "script": "docker.sh",
  "icon": "🐳",
  "port": "",
  "size": "0MB (no instala nada)",
  "type": "Nativo",
  "requiresProot": false,
  "estimate": "instantáneo",
  "hasSwitch": false,
  "experimental": true,
  "arch": "bionic",
  "category": "dev",
  "installMethods": [],
  "requires": []
}
```

Puntos notables:
- `hasSwitch: false` — no hay toggle ON/OFF (no tiene sentido activar/desactivar algo que nunca
  se instala).
- `size: "0MB (no instala nada)"` y `estimate: "instantáneo"` — reflejan literalmente que el
  script no descarga ni instala nada, solo imprime y sale.
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

Esto es intencional y a prueba de futuros cambios accidentales: cualquier parte de la UI que
consulte el registry para decidir si mostrar el módulo como "instalado" (p. ej. un check verde,
un badge de estado) nunca lo va a ver así para `docker`, sin importar si el dispositivo tiene
root o no.

---

## 7. Relación real con `udocker` — en qué se diferencian y por qué son dos módulos separados

`docker` y `udocker` (ver `docs/modulos/udocker.md`) tocan el mismo problema — correr contenedores
de estilo Docker en Android sin root — pero son cosas fundamentalmente distintas, no dos nombres
para lo mismo:

| | `docker` (este módulo) | `udocker` |
|---|---|---|
| **Qué es** | Un módulo explicativo — no instala nada, solo redirige | Una herramienta real que **sí** corre imágenes de contenedores |
| **Motor** | N/A (no ejecuta contenedores) | PRoot (interceptación de syscalls por `ptrace`) |
| **¿Docker real (dockerd)?** | No, ni lo intenta | Tampoco — es un *emulador* de contenedores en userspace, no Docker real |
| **Namespaces/cgroups del kernel** | No aplica | No los usa ni los necesita — por eso funciona sin root |
| **Root requerido** | No (y el script nunca lo pide) | No |
| **`modulos/*.sh` propio** | Sí — `modulos/docker.sh` | **No** — no existe `modulos/udocker.sh`; se instala inline desde `modulos/n8n.sh` (variante udocker) y `modulos/entorno.sh` (`_install_udocker()`) |
| **Entrada en `modules.json`** | Sí (`id: "docker"`, `hasSwitch: false`) | No tiene entrada propia — no es un módulo con switch |
| **Fragment** | `DockerFragment.kt` (informativo + botón a udocker) | `UdockerFragment.kt` (funcional) |
| **`installed` en registry** | Siempre `false` | No tiene entrada propia en el registry — su estado depende de si `command -v udocker` encuentra el binario que dejó n8n o Entorno |
| **Aislamiento de red** | N/A | Ninguno real — comparte el stack de red del host (`localhost` del contenedor = `localhost` de Android) |

**Por qué existen los dos por separado, en vez de fusionar `docker.sh` dentro de `udocker`:**
`docker` cumple un rol de **UX/honestidad** — es el módulo al que llega un usuario que busca
"Docker" por nombre (porque es el término que conoce), lo encuentra en la Tienda/lista de
módulos por su ícono 🐳 y nombre familiar, y en vez de fallar silenciosamente o instalar algo que
no es Docker haciéndolo pasar por tal, se le explica la limitación real del sistema operativo y
se lo deriva al lugar correcto. udocker, en cambio, no necesita su propio punto de entrada en la
Tienda porque no es algo que un usuario instale por sí solo desde cero — es infraestructura que
otros módulos (n8n, Entorno) traen consigo cuando la necesitan, y a la que `DockerFragment`
apunta como destino de navegación una vez que ya explicó la diferencia.

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
- `--force` — aceptado por consistencia con el resto de `modulos/`, sin ningún efecto en este
  script.

## Controles de la pantalla

Pantalla deliberadamente simple — sin switch de instalación, sin botones de acción real.

| Control | Qué hace | Por qué |
|---|---|---|
| Texto "⚠ Docker real no funciona en este dispositivo" + explicación | Solo lectura | Docker real (`dockerd`) necesita namespaces + cgroups del kernel accedidos directo por un daemon con privilegios — Android bloquea eso a apps sin root por diseño del sandbox, no es una limitación de Termux ni de Kairos |
| "→ Ir a udocker (alternativa real)" | `navigateTo(UdockerFragment())` | udocker corre imágenes reales de Docker Hub en userspace vía PRoot, sin namespaces/cgroups reales pero sin necesitar root — misma tecnología que ya usa el módulo n8n por dentro |
