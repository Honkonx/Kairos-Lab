# Kairos como homelab de bolsillo — hoja de ruta

Este documento resume la visión de Kairos como un "mini PC"/homelab de bolsillo: qué necesidades
típicas de un homelab casero ya cubre, qué falta, y qué mejoras concretas están propuestas para
cerrar esa brecha.

## Principio rector

Kairos ofrece capacidades avanzadas (contenedores, distros Linux, servicios de red) como
módulos instalables y opcionales, nunca como un modo forzado que cambie la cara de toda la
aplicación. Un usuario que solo quiere un asistente de IA en la terminal no debería sentir que
la app "es para homelabs" — la sigue viendo simple. Un usuario que quiere armar un servidor
casero completo en el bolsillo encuentra todo lo necesario sin salir de la app. Cada
funcionalidad nueva se integra como un módulo más con su propio interruptor y pantalla, no como
un "modo homelab" global.

## Qué ya cubre Kairos

| Necesidad de un homelab | Estado |
|---|---|
| Servicios en segundo plano que sobreviven reinicios | Cubierto — sesiones persistentes + módulos con interruptor propio |
| Acceso remoto seguro | Cubierto — túneles (Cloudflare/ngrok) con token y dominio propio |
| Monitoreo de qué está corriendo | Cubierto de forma básica, con espacio de mejora |
| Copias de seguridad | Cubierto de forma parcial |
| Bases de datos persistentes | Cubierto — módulo de bases de datos (MySQL/Postgres/SQLite) |
| Automatización/orquestación | Cubierto — módulo n8n |
| Entornos de desarrollo/pruebas | Cubierto — presets de stacks (Python, PHP, React+Vite) |
| Almacenamiento compartido entre host y distros | Pendiente |
| Interfaz gráfica remota | Cubierto — X11 embebido + túnel, VNC como capa adicional |
| Notificaciones de eventos | Pendiente |
| Catálogo de aplicaciones instalables con un toque | Cubierto para módulos de Kairos; falta para aplicaciones dentro de una distro |

La conclusión práctica: Kairos ya cubre la mayoría de lo que un homelab pequeño necesita. Lo que
falta es sobre todo pulido (monitoreo más rico, notificaciones, respaldo completo) y dos
funcionalidades concretas nuevas — almacenamiento compartido entre el host y las distros, y un
catálogo de aplicaciones instalables dentro de una distro con lanzador gráfico automático.

## Por qué no QEMU ni un demonio Docker real

Se evaluó explícitamente incorporar QEMU o un demonio Docker completo como mecanismo general de
aislamiento, y se descartó: ambos requieren una capa de emulación completa de CPU por software
(Android no expone aceleración de virtualización x86 a aplicaciones de usuario, y el kernel de
Android tampoco tiene soporte nativo de cgroups/namespaces para un demonio Docker real), lo que
implica una sobrecarga de batería, memoria y tiempo de arranque significativamente mayor que las
alternativas ya usadas en Kairos (`udocker` para contenedores, `proot-distro` para distros Linux
completas) sin ninguna ganancia real para el caso de uso principal. Un uso de nicho —correr
imágenes de máquina virtual completas para retos de ciberseguridad tipo CTF que vienen
empaquetados como `.vmdk`/`.ova`— podría justificar un módulo aislado en el futuro, pero no como
mecanismo central de la plataforma.

## Almacenamiento compartido entre el host y las distros

Patrón identificado como directamente portable: usar los flags nativos de `proot-distro`
(`--shared-home`, `--shared-tmp`) junto con una detección automática de almacenamiento externo
(tarjetas SD, USB montado) que se vincula automáticamente en cada sesión, sin que el usuario
tenga que configurar rutas de montaje a mano cada vez que entra a una distro.

## Catálogo de aplicaciones dentro de una distro, con lanzador automático

El patrón consiste en generar comandos de instalación por distro (`<distro> install <paquete>`)
que, además de instalar el paquete dentro del contenedor, detectan automáticamente el archivo
`.desktop` que trae y lo copian al menú de aplicaciones del escritorio del host — con un
wrapper que hace que ese lanzador entre a la distro correcta y ejecute el comando ahí. Una
pantalla nativa equivalente permitiría listar los paquetes con lanzador `.desktop` ya instalados
dentro de la distro activa, con un botón para agregarlos al menú.

## Otras mejoras propuestas

- **Notificaciones** (por ejemplo, vía Telegram) — un homelab sin aviso de "el servicio X se
  cayó" es un homelab a medias. Identificado como el ítem de mayor valor con menor esfuerzo
  pendiente.
- **Verificación en vivo de instalación** — confirmar que un binario instalado realmente
  funciona (no solo que el comando de instalación terminó sin error) antes de marcar un módulo
  como instalado, para que la base sobre la que se construyen nuevas funcionalidades sea
  confiable.
- **Panel de monitoreo enriquecido** — un panel único con el estado de RAM/disco, qué módulos
  están corriendo, y el estado de bases de datos, distros y escritorios activos.
- **Exportación/importación de configuración completa** — un solo archivo con el registro de
  módulos y la configuración de cada uno, para migrar de dispositivo o tener un respaldo
  completo de cómo está armado el homelab.

## Tabla de prioridad

| Funcionalidad | Valor | Esfuerzo | Riesgo |
|---|---|---|---|
| Almacenamiento compartido en distros | Alto | Medio | Bajo |
| Notificaciones (Telegram) | Alto | Bajo | Bajo |
| Catálogo de apps dentro de una distro + lanzador | Alto | Alto | Medio |
| Verificación en vivo de instalación | Alto | Medio | Bajo |
| Panel de monitoreo enriquecido | Medio | Medio | Bajo |
| Exportación/importación de configuración | Medio | Medio | Bajo |
| QEMU / Docker real como mecanismo central | Bajo | Alto | — (no recomendado) |
