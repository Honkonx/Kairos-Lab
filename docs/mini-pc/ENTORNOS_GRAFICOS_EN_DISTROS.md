# Entornos gráficos dentro de distribuciones proot

Kairos puede levantar un entorno de escritorio completo (XFCE4, LXQt o MATE) dentro de una
distribución Linux instalada vía `proot-distro`, renderizando sobre el mismo servidor X11
embebido que usa el modo de escritorio nativo. Este documento describe el mecanismo, el estado
del catálogo de aplicaciones instalables dentro de una distro, y un conjunto de mejoras
concretas identificadas mediante investigación de proyectos similares del ecosistema
Termux/Android.

## Mecanismo

La instalación del entorno de escritorio dentro de la distro corre los gestores de paquetes
correspondientes (`apt`, `dnf`, `pacman` o `apk` según la distribución) dentro de una sesión
`proot-distro login`, seguida de una verificación funcional real (que el binario de sesión del
entorno de escritorio exista y sea ejecutable, no solo que el comando de instalación haya
terminado sin error). El arranque de la sesión gráfica usa `dbus-launch --exit-with-session`
junto con el entorno de escritorio elegido, con `DISPLAY` apuntando al mismo servidor X11
embebido que usa el resto de la app — nunca un servidor separado.

Patrones ya adoptados de proyectos de referencia del ecosistema (`sabamdarif/termux-desktop`,
`orailnoor/DroidDesk`, `vee63b/RDeX`): mantener la sesión despierta mientras el escritorio está
activo (wake lock), un modo explícito de renderizado por software cuando no hay aceleración por
GPU disponible, limpieza de directorios de runtime obsoletos antes de arrancar el servidor de
audio, y generación automática de lanzadores `.desktop` tanto para las herramientas de línea de
comandos de Kairos como para aplicaciones instaladas dentro de la distro.

## Selección de aplicaciones dentro de una distro

El catálogo curado actual cubre navegación web, ofimática, edición de imagen/vector, video/audio
y transferencia de archivos, además de un campo de texto libre para instalar cualquier paquete
disponible en los repositorios de la distro. Categorías identificadas como huecos reales, con su
paquete apt equivalente en Debian/Ubuntu arm64:

| Categoría | Paquete apt | Motivo |
|---|---|---|
| IDE/editor gráfico liviano | `geany` | Alternativa viable en un contenedor ARM64 sin root — VS Code/VSCodium no publican un repo apt oficial para arm64 sin agregar repositorios de terceros. |
| Visor de documentos/PDF | `evince` | Hoy abrir un PDF obliga a levantar la suite ofimática completa. |
| Gestor de contraseñas | `keepassxc` | Encaja con el uso de Kairos como homelab personal — no hay ninguna app cubriendo esta categoría hoy. |
| Cliente remoto (RDP/VNC/SSH gráfico) | `remmina` | Permite que el escritorio dentro de la distro se conecte hacia otros equipos de la red, complementando el propio visor VNC de Kairos. |
| Editor de audio | `audacity` | Complementa las herramientas de creación de contenido ya disponibles (OBS, Blender). |
| Gestor de archivos comprimidos | `xarchiver` | Utilidad básica de escritorio ausente hoy — abrir un `.zip`/`.tar.gz` requiere terminal. |

## Wallpaper: mecanismo real por entorno de escritorio

Configurar el fondo de pantalla dentro de un contenedor `proot` sin una sesión de bus de
D-Bus completa es una fuente conocida de fallos silenciosos en todo el ecosistema. El mecanismo
correcto depende del entorno de escritorio:

- **XFCE4** usa `xfconf-query`, que requiere una variable `DBUS_SESSION_BUS_ADDRESS` válida
  apuntando a un socket real — muchas distros compilan D-Bus con el autolanzamiento por X11
  deshabilitado por seguridad, así que un comando aislado fuera de la sesión activa falla con un
  error de conexión. La forma correcta es ejecutar el comando **dentro** del mismo proceso/sesión
  que ya tiene ese valor exportado (la sesión gráfica activa), en vez de como un comando nuevo y
  aislado. El path exacto de la propiedad (`/backdrop/screen0/monitorX/workspaceN/last-image`)
  depende del nombre real del monitor expuesto por el servidor X11, y conviene descubrirlo con
  `xfconf-query -c xfce4-desktop -l` antes de asumir un nombre fijo.
- **LXQt** es el caso más simple: el wallpaper se controla con un archivo INI plano
  (`~/.config/pcmanfm-qt/lxqt/settings.conf`) sin D-Bus de por medio. El comando
  `pcmanfm-qt --set-wallpaper=<ruta> --wallpaper-mode=<modo>` aplica el cambio sin necesitar
  reiniciar la sesión, a diferencia de editar el archivo directamente.
- **MATE** usa GSettings/dconf (`gsettings set org.mate.background picture-filename <ruta>`),
  que también depende de una sesión D-Bus/dconf activa para escribir en caliente.

El denominador común práctico: cualquier comando de cambio de wallpaper debe ejecutarse dentro
de la sesión gráfica activa (heredando su `DBUS_SESSION_BUS_ADDRESS` real), no como un comando
aislado nuevo — el valor de esa variable lo genera `dbus-launch` en el momento en que arranca la
sesión y no se persiste automáticamente en ningún lugar accesible para comandos posteriores.

## Instalación por niveles para metapaquetes pesados

Para paquetes de instalación muy pesada (por ejemplo, un metapaquete completo de herramientas de
seguridad), instalar todo de una sola pasada dentro de una ventana de tiempo fija tiene un riesgo
real: si la instalación completa no entra en esa ventana, el usuario se queda sin absolutamente
nada, incluidas las herramientas más livianas. El patrón usado por proyectos como NetHunter
Rootless (niveles `--nano`/`--minimal`/`--full`) resuelve esto dividiendo la instalación en un
subconjunto rápido de herramientas núcleo primero, y solo después intentando el metapaquete
completo como una mejora opcional de mejor esfuerzo. Este patrón se aplica en Kairos para la
instalación de herramientas de seguridad dentro de una distro Kali: un lote rápido de
utilidades núcleo se instala primero, y el metapaquete pesado se intenta después, con un límite
de tiempo generoso pero sin bloquear la disponibilidad del lote núcleo si el metapaquete no llega
a completarse a tiempo.

## Referencias

- [sabamdarif/termux-desktop](https://github.com/sabamdarif/termux-desktop)
- [orailnoor/DroidDesk](https://github.com/orailnoor/DroidDesk)
- [vee63b/RDeX](https://github.com/vee63b/RDeX)
- [Kali NetHunter Rootless — documentación oficial](https://www.kali.org/docs/nethunter/nethunter-rootless/)
- [pcmanfm-qt — configuración de wallpaper](https://github.com/lxqt/pcmanfm-qt/blob/master/config/pcmanfm-qt/lxqt/settings.conf.in)
