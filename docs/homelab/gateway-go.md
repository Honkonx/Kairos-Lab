# Gateway de Kairos en Go (`native/gateway/`) — v0.1

> Estado: **núcleo hecho y probado; falta la integración Kotlin** (ver "No verificado / pendiente").

## Qué es

Un servidor HTTP local, en Go con **solo la librería estándar**, que recibe peticiones (la web de Homelab, otros
dispositivos, el Agente), las valida contra permisos y decide quién las atiende:

| Ejecutor | Qué hace | Ejemplo |
|---|---|---|
| `inapp` | Reenvía a la app Kairos por HTTP en loopback con un secreto | iniciar/detener un módulo |
| `shell` | Argv fijo **sin shell**, parámetros validados con regex, ruta absoluta | un comando de la lista blanca |
| `native` | Función incorporada del gateway | versión, temperatura |

Nunca se ejecutan comandos libres: cada acción está declarada en un manifiesto con su riesgo.

## Permisos

Niveles de un token: **lectura · pregunta · semi-total · total**. Riesgo de una acción: `read` · `write` · `danger`.

| Nivel \ Riesgo | read | write | danger |
|---|---|---|---|
| lectura | permitida | denegada | denegada |
| pregunta | permitida | **pregunta al usuario** | **pregunta** |
| semi-total | permitida | permitida | **pregunta** |
| total | permitida | permitida | permitida |

La **política global** (la fija el usuario desde la app) tiene un techo (`max`) y la casilla **`responsible`**
("soy responsable de lo que pase"). Sin la casilla marcada, cualquier nivel por encima de "pregunta" se reduce a
"pregunta". Política inicial: `max=pregunta`, `responsible=false` (lo más conservador). Código y matriz:
`internal/permission/` (tests de toda la matriz).

## API

| Ruta | Quién | Qué |
|---|---|---|
| `GET /healthz` | público | estado y versión |
| `GET /v1/me`, `GET /v1/actions` | token | quién soy, nivel efectivo y qué pasaría con cada acción |
| `POST /v1/actions/{id}[?wait=N]` | token | ejecuta, pregunta (202 o espera hasta 120 s) o rechaza (403) |
| `GET /v1/pending/{id}` | el token que la creó | estado de una confirmación |
| `GET /v1/events` | token | SSE **solo con los eventos dirigidos a ese token** (p. ej. `action_done` de su propia petición); latido cada 15 s |
| `GET /v1/admin/events` | solo la app | SSE completo: `pending` de cualquier token, `policy`, `action_done` |
| `/proxy/{servicio}/{ruta}` | token | proxy inverso a un servicio **loopback** del manifiesto; un `Upgrade`/WebSocket cuenta como **escritura** |
| `/v1/admin/*` | solo la app (`X-Kairos-Admin`) | crear/revocar tokens, política, decidir pendientes, auditoría, eventos |

El token va en `Authorization: Bearer`; solo en `GET` se acepta también `?token=` (EventSource y enlaces). El proxy
quita el token y la cabecera `Authorization` antes de reenviar.

## Decisiones de seguridad (verificadas con tests)

- Los tokens se guardan **solo como SHA-256**; el valor en claro se entrega una vez. Un secreto guardado no se vuelve a mostrar.
  Comparación en tiempo constante. Archivos `0600`, carpeta `0700`.
- `AdminSecret` vacío ⇒ la administración **no existe** (403 siempre). Aprobar una confirmación requiere ese
  secreto: el dueño del token no puede auto-aprobarse.
- Parámetros: regex anclado obligatorio, máx. 512 bytes, sin campos desconocidos; se validan **antes** de molestar al usuario.
- Una acción que no pasó por el registro **falla cerrada** (error, no pánico ni ejecución).
- Proxy: destino solo `localhost`/loopback (anti-SSRF), sin usuario:clave en la URL.
- **Eventos con ámbito** (revisión de seguridad, 2026-10-09): cada evento es "solo administración" o va dirigido
  a un token; un token nunca ve las confirmaciones, parámetros o cambios de política de otro. Probado con tests que
  **fallan si se quita el arreglo** (comprobado por mutación).
- **WebSocket por el proxy** cuenta como escritura (tras el `Upgrade` el cliente puede escribir): un token de lectura recibe 403.
  La cabecera `Connection` se interpreta como lista (`keep-alive, Upgrade`) y basta `Upgrade` solo.
- Auditoría JSONL con rotación; nunca contiene valores de tokens.
- Escucha en `127.0.0.1:8973` por defecto; fuera de loopback avisa que **no hay TLS**.

## Archivos de estado (`$HOME/.kairos_gateway/`)

`tokens.json` · `policy.json` · `manifest.json` (opcional; si falta se usa el incorporado, que declara
`gateway.version`, `system.thermal`, `module.list/status/start/stop/install`) · `audit.log` (+ `.1`).
Variables: `KAIROS_GATEWAY_DIR`, `KAIROS_GATEWAY_ADMIN_SECRET`, `KAIROS_GATEWAY_APP_URL`, `KAIROS_GATEWAY_APP_SECRET`.

## Compilar y probar

```
bash tools/build-gateway.sh --test     # go vet + go test, luego android/arm64 → app/src/main/jniLibs/arm64-v8a/libkairos_gateway.so
cd native/gateway && go test -race ./... # el detector de carreras necesita CGO (en la máquina de desarrollo: w64devkit/bin en el PATH)
```

El `.so` no se commitea (`*.so` ignorado); el CI lo compila en cada corrida (paso añadido a `build-app.yml` y `.gitlab-ci.yml`,
después de cloudflared). Método idéntico al de cloudflared (ver [`../ssh/tunel-cloudflared-nativo.md`](../ssh/tunel-cloudflared-nativo.md)): `GOOS=android` + CGO + clang del NDK.

## Resultados medidos (2026-10-09)

- **Tests:** 8 paquetes, `go vet` limpio, `gofmt` limpio, `go test -race` sin fallos.
- **Binario:** 8,1 MB, ELF aarch64 "for Android 26", stripped.
- **En el dispositivo real** (shell de `adb`, carpeta temporal, ya limpiada): arranca; `/healthz` ok; 401 sin token;
  un token "semi-total" queda en "pregunta" con la política por defecto; `gateway.version` y `system.thermal` responden
  (la temperatura se leyó como usuario `shell`); crear pendiente → aprobar con el secreto admin → ejecutar (falla con
  error limpio porque la app no estaba configurada como destino); cambiar la política emitió el evento SSE `policy`;
  la auditoría registró cada paso; un parámetro con `; rm -rf /` se rechazó con 400.

## No verificado / pendiente

- **Integración Kotlin (no hecha):** lanzador (`GatewayManager`, como `TunnelManager`) con el entorno y los secretos,
  endpoint `/internal/action` en la app (para el ejecutor `inapp`), cliente admin, y la **pantalla de permisos** en
  Homelab (selector de 4 niveles + casilla de responsabilidad + diálogo de confirmación cuando llega el evento `pending`).
- Que el **usuario de la app** (no `shell`) pueda leer `/sys/class/thermal`.
- Que el SELinux del dispositivo permita ejecutar `libkairos_gateway.so` desde `nativeLibraryDir` bajo la app
  (sí funciona para `libcloudflared.so`, pero este binario no se probó dentro de la app).
- Reescritura de `Location` en redirecciones del proxy; el reenvío real de un WebSocket (no solo el rechazo por permisos) no se probó.
- Un cliente SSE en la web de Homelab (hoy sigue con `meta refresh`).
- Tamaño en el APK de x86 / otras ABIs: solo se compila arm64.
