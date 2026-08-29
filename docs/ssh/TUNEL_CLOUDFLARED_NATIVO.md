# Túnel Cloudflare nativo (cloudflared embebido)

## El problema

El módulo de túneles expone servicios locales a internet mediante `cloudflared tunnel --url ...`
o `ngrok`. El botón de Cloudflare fallaba de forma consistente en dispositivos Android reales
con un error de resolución DNS:

```
lookup api.trycloudflare.com on [::1]:53: connection refused
```

### Causa raíz

El binario oficial de `cloudflared` es un binario Go compilado con `CGO_ENABLED=0` (práctica
estándar para binarios Go multiplataforma), lo que significa que su resolución DNS depende
enteramente del resolver puro de Go. Ese resolver busca `/etc/resolv.conf` en la ruta estándar
del sistema operativo — en Android esa ruta no existe (es de solo lectura y no puede crearse sin
privilegios de root).

Termux mantiene su propio `resolv.conf` funcional dentro de su prefijo, pero el resolver de Go
nunca lo busca ahí y no existe ninguna variable de entorno que permita redirigirlo. La variable
`GODEBUG=netdns=go`/`netdns=cgo`, comúnmente citada como solución para este tipo de problemas, no
tiene ningún efecto aquí: el binario oficial nunca se compiló con soporte cgo, así que no hay
ningún resolver alternativo al que conmutar. La resolución DNS normal del dispositivo (por
ejemplo vía `curl`) funciona sin problemas — el fallo es específico de cómo los binarios Go puros
resuelven nombres en un entorno Android en sandbox.

## La solución: compilar cloudflared desde fuente con soporte cgo real

En vez de distribuir el binario oficial (Go puro), el pipeline de compilación construye
`cloudflared` desde su propio código fuente con `CGO_ENABLED=1`, usando el compilador `clang` del
NDK de Android para el objetivo `android/arm64`. Un binario compilado con cgo en Android usa
`getaddrinfo()` real de Bionic (la libc de Android) para resolver nombres — el mismo mecanismo
que usa cualquier aplicación Android normal, sin depender en absoluto de `/etc/resolv.conf`.

### Paso de CI

```bash
NDK_CLANG="$ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
git clone --depth 1 --branch <tag> https://github.com/cloudflare/cloudflared.git
GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC="$NDK_CLANG" \
  go build -trimpath -ldflags "-s -w" \
  -o app/src/main/jniLibs/arm64-v8a/libcloudflared.so \
  ./cmd/cloudflared
```

Detalles relevantes:

- El nivel de API en el nombre del clang (`aarch64-linux-android26-clang`) coincide con el
  `minSdkVersion` del proyecto.
- El binario no se versiona en el repositorio — se compila en cada corrida de integración
  continua, igual que otras dependencias nativas del proyecto.
- El resultado se coloca en `app/src/main/jniLibs/arm64-v8a/`, la ubicación estándar que el
  Android Gradle Plugin empaqueta automáticamente dentro del APK con permisos de ejecución. Esto
  evita las restricciones W^X de Android 10+ sobre binarios escritos en tiempo de ejecución
  (que, si se ejecutan directamente desde el almacenamiento privado de la app, pueden carecer de
  permiso de ejecución).

### Integración en la app

El gestor de túneles resuelve la ruta del binario nativo en tiempo de ejecución a partir del
directorio de librerías nativas de la aplicación (nunca hardcodeada, porque Android asigna una
ruta distinta por instalación):

```kotlin
private fun nativeCloudflaredPath(nativeLibDir: String?): String? {
    if (nativeLibDir.isNullOrEmpty()) return null
    val f = File(nativeLibDir, "libcloudflared.so")
    return if (f.exists() && f.canExecute()) f.absolutePath else null
}
```

Cuando el binario nativo está disponible, se usa directamente y **no** se le aplica la variable
`GODEBUG=netdns=go` (aplicarla revertiría el binario al resolver puro de Go incluso estando
compilado con cgo, anulando el propósito de la compilación nativa). `ngrok` sigue usando el
binario distribuido vía Termux con esa variable, ya que todavía no cuenta con una alternativa
nativa equivalente (ver más abajo).

### Comportamiento de respaldo

Si el `.so` nativo no está presente en una instalación (por ejemplo, un APK generado antes de
incorporar este paso), el gestor de túneles cae automáticamente al camino anterior —
`cloudflared` instalado dentro del entorno Termux vía gestor de paquetes — sin romper la
compilación ni la aplicación. En ese escenario el problema de resolución DNS persiste, pero de
forma aislada y sin afectar al resto del sistema.

## ngrok: alternativa nativa viable, no implementada todavía

Se investigó si existía un camino equivalente para `ngrok`. El CLI de ngrok es de código cerrado,
pero ngrok publica **`ngrok-go`** (`golang.ngrok.com/ngrok/v2`, licencia MIT, código abierto), un
SDK en Go que permite establecer túneles ngrok de forma programática sin depender del CLI. Al
tratarse de un módulo Go estándar, es cross-compilable con el mismo patrón de CGO + NDK ya
implementado para `cloudflared`.

La diferencia respecto a `cloudflared` es que `ngrok-go` es una librería, no un programa
ejecutable listo — requeriría escribir un pequeño `main.go` propio que la importe y llame a las
funciones del SDK con el token de autenticación correspondiente. El esfuerzo de compilación
cruzada ya está resuelto y es reutilizable; solo cambiaría qué repositorio se clona y compila.
Queda documentado como mejora futura viable, pendiente de implementación.

## Estado de validación

Este mecanismo se implementó y compila correctamente en el pipeline de integración continua.
La validación de extremo a extremo en un dispositivo real (confirmar que la URL pública de
Cloudflare Quick Tunnel aparece y resuelve correctamente) queda como paso siguiente antes de
considerar el fix completamente cerrado.
