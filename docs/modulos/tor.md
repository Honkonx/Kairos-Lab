# Tor

**Módulo Kairos** — sin Fragment propio, gestionado por la pantalla genérica de módulo (la app ya
tiene todo lo necesario para instalar/iniciar/detener sin necesitar una pantalla dedicada — sin
UI web, el proxy SOCKS no es algo que se navegue).

---

**App:** Kairos (fork termux-app)
**Script:** `modulos/tor.sh`
**Puerto:** `9050` (proxy SOCKS5, solo accesible desde el propio dispositivo)

---

## 1. Qué es

La red Tor (The Onion Router) — enrutamiento de tráfico por capas para navegar de forma anónima.
Corriendo dentro de Kairos, expone un proxy SOCKS5 local al que cualquier app o herramienta
compatible se puede conectar para que su tráfico pase por la red Tor. Es un módulo separado del
kit de Ciberseguridad de Kairos (que cubre auditoría/pentesting, no anonimato de red) — ambas
cosas resuelven problemas distintos.

## 2. Instalación

Paquete nativo de Termux, con el proxy SOCKS configurado explícitamente en el puerto 9050 durante
la instalación — sin pasos manuales.

## 3. Arranque y detención

El módulo tiene switch ON/OFF real: al activarlo, arranca el proceso de Tor en segundo plano con
su proxy SOCKS escuchando en `127.0.0.1:9050`. Al desactivarlo, se detiene.

## 4. Uso

Sin interfaz propia — una vez el switch está en ON, cualquier app o herramienta de línea de
comandos compatible con SOCKS5 (curl, navegadores, otros CLIs) se puede apuntar a
`127.0.0.1:9050` para enrutar su tráfico por Tor.
