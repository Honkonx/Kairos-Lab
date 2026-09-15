# Arquitectura de Mini PC

Kairos convierte el teléfono en una mini PC portátil: un entorno Linux completo, con escritorio
gráfico real y aceleración de GPU, corriendo dentro de la propia app — sin depender de un
servidor externo, de una app de terceros, ni de otra computadora. El módulo que gestiona todo
esto se llama **Entorno** (pestaña "Mini PC" de la app) — ver `../modulos/entorno.md` para el
detalle completo de controles, checkpoints de instalación y scripts generados; este documento
cubre la arquitectura que los coordina.

## 1. Los subsistemas coordinados

| Subsistema | Qué hace | Documento dedicado |
|---|---|---|
| **Servidor X11 embebido** | Renderiza el escritorio gráfico (XFCE4, MATE, LXQt...) dentro del propio APK, sin ninguna app externa | `../x11/x11-embedded-architecture.md` |
| **proot-distro** | Instala y administra distros Linux completas (Ubuntu, Debian, Alpine, Kali, Arch...) sin root, usando namespaces de usuario | Sección 3 de este documento |
| **VNC embebido** | Alternativa/respaldo al visor X11 nativo, con cliente RFB propio dentro de la app | Sección 4 de este documento |
| **Detección de GPU** | Identifica el SoC del dispositivo e instala el driver acelerado correspondiente | `aceleracion-gpu.md` |

Los cuatro se instalan juntos como infraestructura base al activar el módulo Entorno; después,
cada uno se enciende/apaga por separado desde la pantalla "Mini PC", sin necesidad de reinstalar
nada.

## 2. Dos caminos: nativo vs. con distro

Kairos ofrece dos formas de tener un escritorio gráfico, pensadas para necesidades distintas:

| Camino | Qué es | Cuándo conviene |
|---|---|---|
| **Nativo** (recomendado) | Un entorno de escritorio (XFCE4, MATE, LXQt) corriendo directo sobre los binarios de Termux, sin una distro Linux completa de por medio | Uso diario — más liviano, arranca más rápido, no requiere descargar un sistema completo |
| **Con distro** | Un escritorio corriendo dentro de una distro Linux completa (`proot-distro`: Ubuntu, Debian, Kali, Arch...) | Cuando hace falta un sistema aislado real — por ejemplo, herramientas del módulo Ciberseguridad que esperan un entorno Kali completo |

Ambos caminos comparten el mismo servidor X11 embebido (mismo display `:1`, mismo socket) — solo
uno puede tener una sesión de escritorio activa a la vez; la app detecta el conflicto y avisa
antes de dejar arrancar una segunda sesión encima de la otra.

## 3. Gestión de distros (proot-distro)

`proot-distro` corre sistemas Linux completos sin root, usando namespaces de usuario en vez de
contenedores con privilegios — el mismo mecanismo que usa Termux normal para esta tarea. Desde la
pantalla "Mini PC" de Kairos, sin escribir un solo comando:

- **Instalar/eliminar distro** — catálogo de distros soportadas (Ubuntu, Debian, Alpine
  confirmadas de forma estable; Arch Linux, Fedora, Void, Kali, Manjaro, Rocky Linux, openSUSE
  Tumbleweed también disponibles).
- **Instalar/eliminar escritorio dentro de la distro** — mismo selector de entorno gráfico
  (XFCE4/LXQt/MATE/KDE) que el camino nativo, aplicado dentro de la distro elegida.
- **Login por terminal** — abre una consola real dentro de la distro, para quien prefiere
  trabajar por línea de comandos en vez del escritorio gráfico.
- **Backup de distro** — empaqueta la distro completa en un `.tar.gz`.
- **Vincular carpetas** — comparte `~/scripts` y `~/proyectos` del sistema de archivos de Termux
  con la distro, sin copiar archivos.
- **Catálogo de apps de escritorio** — instalar aplicaciones curadas (Firefox, GIMP, LibreOffice,
  Blender, OBS Studio y otras) dentro de la distro con un botón, sin memorizar el nombre exacto
  del paquete.
- **Perfil recomendado** — arma automáticamente una combinación de distro + escritorio + método
  de GPU según la memoria RAM y la GPU detectadas en el dispositivo.

## 4. VNC como alternativa

VNC (protocolo RFB) está disponible como camino secundario/de respaldo frente al visor X11
nativo, útil cuando se prefiere conectar con un cliente ya conocido o desde otro dispositivo en
la misma red. Kairos incluye:

- Un servidor VNC instalable con un botón (TigerVNC), configurable en resolución, calidad de
  color y contraseña desde la propia UI.
- **Un cliente VNC propio embebido en la app** — implementación del protocolo RFB 3.8 escrita
  para Kairos, sin depender de ninguna app externa para conectarse al servidor. También admite
  conectarse con cualquier cliente VNC de terceros a `127.0.0.1:5901`.

## 5. Estado actual

- **Camino nativo**: funciona de punta a punta, confirmado en dispositivo real — escritorio
  completo con iconos, barra de tareas y aplicaciones renderizando correctamente, incluyendo
  aceleración de GPU con los drivers correctos por fabricante (ver `aceleracion-gpu.md`).
- **Instalar un escritorio dentro de una distro**: funciona de punta a punta, confirmado en
  dispositivo real (instalación completa de un escritorio XFCE4 dentro de una distro Kali).
- **Limitación conocida**: en algunas combinaciones de distro + escritorio, *iniciar* una sesión
  de escritorio ya instalada dentro de una distro puede fallar. La causa identificada es un
  conflicto de sandboxing: los entornos gráficos modernos basados en GTK/GNOME cargan íconos
  vectoriales a través de un mecanismo que aísla cada carga en un namespace de Linux propio —
  aislamiento que puede chocar con los namespaces ya anidados de correr una distro completa
  dentro de `proot` en Android. Es una limitación conocida, en investigación — no afecta al
  camino nativo, que no pasa por esa capa de sandboxing.

## Ver también

- `../modulos/entorno.md` — pantalla completa del módulo Entorno, todos los controles y la
  lógica de instalación de la infraestructura base.
- `../x11/x11-embedded-architecture.md` — arquitectura interna del servidor X11 embebido
  (proceso Android, resolución de socket, estabilidad en segundo plano).
- `aceleracion-gpu.md` — detección de GPU por fabricante y drivers instalados.
