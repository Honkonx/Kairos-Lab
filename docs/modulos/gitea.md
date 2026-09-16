# Gitea

**Módulo Kairos** — sin Fragment propio, gestionado por la pantalla genérica de módulo (la app ya
tiene todo lo necesario para instalar/iniciar/detener/abrir la UI web sin necesitar una pantalla
dedicada), mismo patrón que `syncthing`.

---

**App:** Kairos (fork termux-app)
**Script:** `modulos/gitea.sh`
**Puerto:** `3001` (UI web, solo accesible desde el propio dispositivo — Kairos la abre embebida
dentro de la app)

---

## 1. Qué es

Servidor Git propio, autoalojado en el teléfono — Gitea ("Git with a cup of tea"). Encaja directo
con la idea de "home-lab en el bolsillo": permite tener repositorios Git propios y hacer push del
código en el que estás trabajando (por ejemplo desde el IDE integrado de Kairos) a un servidor que
vive en el mismo dispositivo, sin depender de GitHub ni de ningún otro servicio externo.

## 2. Puerto — por qué 3001 y no el 3000 de siempre

Gitea usa por defecto el puerto 3000 para su interfaz web, pero ese puerto ya lo ocupa otro
módulo de Kairos (OpenCode). Para evitar el choque, la instalación configura Gitea directamente
en el puerto **3001**. También se deshabilita el servidor SSH interno de Gitea (Android sin root
no puede usar el puerto 22 que usaría por defecto) — Kairos ya cubre acceso SSH con su propio
módulo Remote, en otro puerto. El acceso a los repositorios de Gitea queda por HTTP normal
(clonar/hacer push a `http://127.0.0.1:3001/usuario/repo.git`).

## 3. Instalación

Paquete nativo de Termux, con el ajuste de puerto aplicado automáticamente durante la
instalación — sin pasos manuales.

## 4. Arranque y detención

El módulo tiene switch ON/OFF real: al activarlo, arranca el servidor de Gitea en segundo plano.
Al desactivarlo, se detiene el proceso.

## 5. Configuración

La creación del primer usuario administrador y de los primeros repositorios es el asistente de
instalación propio de Gitea, que aparece la primera vez que se abre su interfaz — inherentemente
interactivo por diseño de la propia herramienta. Se completa desde `http://localhost:3001`, que
Kairos abre embebido dentro de la app en cuanto el switch está en ON.
