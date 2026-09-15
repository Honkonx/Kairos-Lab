# Túnel Cloudflare nativo embebido

Kairos embebe un binario nativo de `cloudflared` compilado específicamente para Android, en vez
de depender del binario oficial genérico de Linux. Esta decisión resuelve un problema real de
resolución DNS que afecta a cualquier binario Go puro corriendo en un entorno Android
sandboxeado sin root.

## El problema: resolución DNS de un binario Go puro en Android

El tab Túnel expone un módulo a internet mediante un túnel de Cloudflare o de ngrok. El binario
oficial de `cloudflared`, al intentar abrir un túnel, fallaba consistentemente con:

```
lookup api.trycloudflare.com on [::1]:53: connection refused
```

**Causa raíz:**

1. `cloudflared` es un binario Go oficial compilado sin soporte de cgo (práctica estándar para
   binarios Go multiplataforma) — su resolver DNS es 100% Go puro.
2. El resolver puro de Go busca `/etc/resolv.conf` en la ruta estándar del sistema operativo. En
   Android esa ruta no existe y es de solo lectura sin privilegios de root — no se puede
   "arreglar" colocando el archivo ahí.
3. El entorno del dispositivo tiene su propio `resolv.conf` funcional en otra ruta, pero el
   resolver de Go nunca lo busca ahí — no existe ninguna variable de entorno que lo redirija.
4. Las variables de entorno de depuración de Go para forzar un resolver alternativo no tienen
   ningún efecto, porque el binario oficial nunca tuvo soporte cgo compilado — no hay resolver
   alternativo al que cambiar.
5. La resolución DNS normal del dispositivo funciona sin problemas — el problema es específico
   de cómo los binarios Go puros hacen su propia resolución en este entorno.

## La solución: compilar cloudflared desde fuente con cgo real para Android

En vez de usar el binario Go puro, el pipeline de compilación construye `cloudflared` desde su
propio código fuente con soporte de cgo habilitado, apuntando al compilador del NDK de Android
para el objetivo `android/arm64`. Un binario con cgo habilitado en Android usa la función real
`getaddrinfo()` de Bionic (la libc de Android) para resolver DNS — el mismo mecanismo que usa
cualquier app Android normal, correcto por diseño, sin depender de `/etc/resolv.conf` en
absoluto.

### Compilación (CI)

```bash
NDK_CLANG="$ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
git clone --depth 1 --branch <tag> https://github.com/cloudflare/cloudflared.git
GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$NDK_CLANG" \
  go build -trimpath -ldflags "-s -w" \
  -o app/src/main/jniLibs/arm64-v8a/libcloudflared.so \
  ./cmd/cloudflared
```

- El nivel de API en el nombre del compilador (`android26`) coincide con el `minSdkVersion` del
  proyecto.
- El binario no se versiona en el repositorio — se compila fresco en cada corrida de CI, igual
  que el resto de dependencias nativas del proyecto.
- Se coloca en la ubicación estándar de librerías nativas de Android (`jniLibs/arm64-v8a/`), lo
  que hace que el sistema lo extraiga con permiso de ejecución al instalar el APK — evitando las
  restricciones de seguridad de Android 10+ sobre binarios escritos y ejecutados en tiempo de
  ejecución desde el almacenamiento de la app.

### Integración en la app

La app resuelve la ruta del binario nativo en runtime a partir del directorio de librerías
nativas de la propia instalación (nunca hardcodeada, porque Android aleatoriza ese directorio
por instalación). Si el binario nativo está disponible, se usa directamente y se deja de aplicar
cualquier variable de entorno que fuerce el resolver DNS puro de Go — aplicarla revertiría el
binario al comportamiento problemático incluso estando compilado con cgo.

### Comportamiento de reserva

Si un APK se instaló desde una compilación anterior a esta integración, o el paso de compilación
del binario nativo falla por algún motivo, la app detecta que el binario nativo no existe y cae
al camino anterior (cloudflared instalado como paquete dentro del entorno) — con el problema de
DNS sin resolver en ese caso, pero sin romper el resto de la app.

### Estado de verificación

El binario nativo compila correctamente en el pipeline de CI. La confirmación end-to-end en un
dispositivo real (arranque efectivo del túnel y resolución exitosa de la URL pública) sigue
pendiente al momento de escribir esto — no asumir que "compila en CI" equivale a "funciona en el
dispositivo" sin esa verificación.

## ngrok — mismo enfoque, pendiente de implementación

El CLI oficial de ngrok es de código cerrado, pero ngrok también publica un SDK de Go de código
abierto (licencia MIT) que permite establecer túneles de forma programática sin necesitar el
CLI en absoluto. Es un módulo Go estándar, cross-compilable con el mismo patrón de cgo + NDK ya
usado para Cloudflare.

La diferencia real es que ese SDK es una librería, no un programa — hace falta escribir un
pequeño programa propio que la importe y establezca el túnel con el token de autenticación que
la app ya gestiona. La parte de compilación cruzada ya está resuelta y es reutilizable tal cual;
solo cambia qué repositorio se compila.
