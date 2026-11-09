package com.termux.app.util

import android.content.Context
import com.termux.app.ModuleController
import com.termux.app.data.ModuleCatalog
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Hallazgo real de MEJORAS_PENDIENTES.md ("`nginx` — reverse proxy... un nginx preconfigurado
 * con un vhost por módulo activo (subpaths o subdominios locales) resolvería 'tengo que
 * acordarme de 8 puertos distintos' con una sola URL — encaja con la filosofía de 'no vivir en
 * la terminal' SI se arma con una UI que genere la config automáticamente a partir de los
 * módulos activos, en vez de pedirle al usuario que edite nginx.conf a mano"). Esta clase ES
 * esa generación automática: se llama desde [com.termux.app.util.ModuleEventBridge
 * .notifySessionEvent] en cada STARTED/STOPPED de un módulo, no requiere ninguna acción manual
 * del usuario — el reverse proxy se mantiene al día solo.
 *
 * Diseño: un `location /<module-id>/` por módulo con `hasSwitch=true` + puerto fijo que esté
 * REALMENTE corriendo ahora (`ModuleController.isRunning()`, no solo "instalado"), escrito en
 * `conf.d/kairos_routes.conf` (nginx.conf real de Termux ya incluye "conf.d" con todos los
 * .conf por default) + una página de estado (`index.html`) que lista las rutas activas con links clicables
 * — así el usuario entra a `http://localhost:8099/` y ve qué hay disponible, sin acordarse de
 * ningún puerto ni tocar ningún archivo de config.
 *
 * SIN VERIFICAR EN DISPOSITIVO REAL (2026-11-09) — módulo nuevo, implementado sin dispositivo
 * conectado esta sesión. Antes de darlo por funcional: confirmar que el paquete `nginx` de
 * Termux realmente incluye todos los .conf de "conf.d" por default en su `nginx.conf` base (asumido por
 * convención estándar de nginx, no confirmado leyendo el `.conf` real empaquetado), y que
 * `proxy_pass` funciona contra los puertos reales de los módulos sin reescritura de rutas
 * adicional (algunos frontends SPA pueden romperse con un subpath si no soportan `base path`
 * configurable — limitación conocida de cualquier reverse proxy por subpath, no específica de
 * Kairos).
 *
 * SEGURIDAD -- 2 hallazgos reales de una revisión automática (2026-11-09), ambos atendidos:
 * (1) nginx bindea EXPLÍCITO a `127.0.0.1` (ver `modulos/nginx.sh`), nunca a todas las
 * interfaces -- sin esto, este proxy sería la puerta trasera que expone a la LAN módulos que
 * ya bindean solo a loopback a propósito (Ollama, n8n, etc.). (2) Todos los módulos comparten
 * el MISMO origen (`http://localhost:8099`) bajo path-based routing -- se agrega
 * `proxy_cookie_path` por módulo para evitar que una cookie de sesión de un módulo viaje al
 * subpath de otro. Esto NO aísla `localStorage`/`fetch` same-origin entre módulos (dos UIs bajo
 * el mismo origen siguen pudiendo leerse ese estado entre sí) -- aislamiento real exigiría un
 * puerto o subdominio propio por módulo, cambio de arquitectura más grande, diferido a una
 * ronda futura si se confirma que hace falta en la práctica.
 */
object NginxProxyManager {
    const val MODULE_ID = "nginx"
    const val PORT = 8099

    private val confDir: File get() = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/nginx/conf.d")
    private val routesFile: File get() = File(confDir, "kairos_routes.conf")
    private val indexDir: File get() = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "share/kairos-nginx")
    private val indexFile: File get() = File(indexDir, "index.html")

    /** No-op silencioso si nginx no está instalado -- cero overhead para quien no usa este módulo. */
    fun regenerateRoutes(context: Context) {
        val reg = ManagerNativeUtils.readRegistry()
        if (reg["$MODULE_ID.installed"] != "true") return
        Thread {
            try {
                val modules = ModuleCatalog.load(context)
                val routes = modules.filter { m ->
                    m.id != MODULE_ID && m.hasSwitch && m.port.toIntOrNull() != null && ModuleController.isRunning(m.id)
                }
                confDir.mkdirs()
                routesFile.writeText(buildString {
                    routes.forEach { m ->
                        append("location /${m.id}/ {\n")
                        append("    proxy_pass http://127.0.0.1:${m.port}/;\n")
                        append("    proxy_set_header Host \$host;\n")
                        append("    proxy_set_header X-Real-IP \$remote_addr;\n")
                        // Hallazgo real de seguridad (revisión automática 2026-11-09): todos
                        // los módulos comparten el MISMO origen (http://localhost:8099) bajo
                        // path-based routing -- sin esto, una cookie de sesión de un módulo
                        // podría viajar o pisarse con la de otro (mismo dominio+puerto). Scope
                        // explícito de cookie al subpath propio -- mitiga el caso de cookies,
                        // NO aísla localStorage/fetch same-origin entre módulos (eso exigiría
                        // un puerto o subdominio propio por módulo, cambio de arquitectura más
                        // grande, diferido -- ver nota en el KDoc de esta clase).
                        append("    proxy_cookie_path / /${m.id}/;\n")
                        append("}\n")
                    }
                })
                indexDir.mkdirs()
                indexFile.writeText(buildString {
                    append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Kairos — Reverse proxy</title>")
                    append("<style>body{font-family:sans-serif;background:#0d1117;color:#e6edf3;padding:24px}a{color:#58a6ff;text-decoration:none}li{margin:8px 0}</style>")
                    append("</head><body><h1>Kairos — rutas activas</h1><ul>")
                    if (routes.isEmpty()) {
                        append("<li>Ningún módulo con switch está corriendo ahora — prendé uno y volvé a esta página.</li>")
                    } else {
                        routes.forEach { m -> append("<li><a href='/${m.id}/'>/${m.id}/</a> → ${m.name} (puerto ${m.port})</li>") }
                    }
                    append("</ul></body></html>")
                })
                reloadIfRunning()
            } catch (_: Exception) {
                // No crítico -- el proxy sigue sirviendo la config anterior si esto falla.
            }
        }.start()
    }

    private fun reloadIfRunning() {
        val (rc, _, _) = ManagerNativeUtils.runExec(listOf("pgrep", "-f", "nginx: master"), 5)
        if (rc == 0) {
            val nginxBin = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "sbin/nginx").absolutePath
            ManagerNativeUtils.runExec(listOf(nginxBin, "-s", "reload"), 10)
        }
    }
}
