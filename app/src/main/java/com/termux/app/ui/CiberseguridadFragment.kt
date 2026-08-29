package com.termux.app.ui

import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.termux.app.data.ModuleRegistry
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.PRIMARY
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.RemoteManager
import com.termux.app.util.kairosThemeColor

/**
 * Fragment dedicado del módulo Ciberseguridad (2026-08-16) — antes caía en
 * GenericModuleFragment como cualquier módulo sin UI específica. Agrega el pedido explícito
 * del usuario ("mini terminal windows mostrando datos como IP, etc"): una card "🌐 RED" con la
 * IP local real del dispositivo (reusa RemoteManager.getLocalIp(), la misma lógica que ya usa
 * el módulo Remote — DRY, sin reimplementar el truco del socket UDP) y accesos directos por
 * terminal a cada herramienta instalada. Versión pragmática: cards de info + botones de
 * lanzamiento en vez de mini-terminales embebidas reales (alcance mayor, no justificado para
 * esta ronda) — ver ciberseguridad.sh para el detalle de qué instala cada nivel.
 *
 * Ampliado 2026-08-17 (pedido explícito: panel de red en vivo en vez de terminal cruda) — la
 * misma card "🌐 RED" agrega un botón "Escanear" que corre `nmap -sn <subred>/24` en background
 * (nunca automático) y lista los hosts vivos encontrados (IP + hostname si resuelve). No reusa
 * RemoteManager.scanSubnet() porque ese algoritmo es específico para encontrar servidores
 * OpenCode/agentes en el puerto fijo REMOTE_SCAN_PORT vía health check HTTP — acá se pidió un
 * descubrimiento genérico de "cualquier dispositivo en la LAN", que es justo lo que hace un
 * ping-scan de nmap. Ver scanLan()/runLanScan() más abajo.
 *
 * El nivel (básico/pro, con o sin GUI) se elige al instalar (BottomSheetInstalacion, variantes
 * ciberseguridad → basico/pro-headless/pro-gui) y queda escrito en el registry por
 * ciberseguridad.sh — este fragment solo LEE ese estado para decidir qué botones mostrar
 * (ej. "Entrar a Kali" solo si el registry dice que el contenedor existe).
 *
 * Ampliado 2026-08-19 (auditoría de conversión terminal→UI nativa, ver docs/humano/humano169.md): antes
 * los botones de nmap/nikto/dirb solo abrían el binario SIN argumentos en la terminal — sin un
 * target no hacen nada útil por sí solos, así que en la práctica el usuario terminaba escribiendo
 * el comando completo a mano igual. Se agregan 3 paneles nativos de "escaneo rápido" (target por
 * diálogo + preset con flags oficiales documentados, parseo real de la salida a una lista, ver
 * promptNmapScan()/promptNiktoScan()/promptDirbScan() abajo) SIN sacar el acceso a la terminal
 * completa (sigue disponible para flags avanzados/wordlists propias) — mismo criterio que
 * scanLan(): nunca corre nada solo, siempre requiere tocar el botón. theHarvester tuvo un panel
 * nativo agregado en la 3ra ronda (ver abajo) para los 3 parámetros que no requieren API key
 * (dominio, fuente, límite) — sigue conservando también el acceso a terminal completo para
 * fuentes que sí requieren credenciales configuradas aparte.
 *
 * Ampliado 2026-08-19 (2da ronda, ver docs/arquitectura/AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_
 * OFICIAL_2026-08-19.md — "ciberseguridad → sqlmap: --batch, el flag de mayor valor para hacerlo
 * parcialmente scriptable"): se agrega un 4to panel nativo, promptSqlmapScan(), usando
 * `sqlmap -u <url> --batch` — `--batch` es el modo no-interactivo oficial de sqlmap (confirmado
 * contra github.com/sqlmapproject/sqlmap/wiki/Usage: "Never ask for user input, use the default
 * behavior"), a diferencia del resto del flujo interactivo de sqlmap (que si pide confirmaciones
 * en cada paso, por eso quedó descartado en la ronda anterior). A diferencia de nmap/nikto/dirb
 * (que aceptan un host/URL pelado), sqlmap necesita una URL con un parámetro real para probar
 * (ej. "?id=1") — el diálogo de target lo pide explícito para que el usuario no reuse el hábito
 * de las otras 3 herramientas.
 *
 * Ampliado 2026-08-19 (3ra ronda, ver docs/arquitectura/AUDITORIA_MODULOS_SISTEMA_SEGURIDAD_VS_
 * OFICIAL_2026-08-19.md sección "Actualización 2"):
 *  - theHarvester: promptTheHarvesterScan() — diálogo con dominio + fuente (`-b`, spinner de
 *    fuentes que NO requieren API key, default "duckduckgo") + límite (`-l`, default 100).
 *    Flags confirmados contra deepwiki.com/laramies/theHarvester (`-d <domain> -b <source>
 *    -l <limit>`). Las fuentes que sí requieren credenciales (shodan, securityTrails, etc.)
 *    siguen quedando fuera de este panel — para esas sigue haciendo falta terminal completo con
 *    la API key ya configurada en `~/.theHarvester/api-keys.yaml`, que Kairos no gestiona.
 *  - sqlmap: promptSqlmapDbs()/promptSqlmapTables()/promptSqlmapDump() — `--dbs` (listar bases),
 *    `--tables -D <db>` (listar tablas), `--dump -D <db> -T <tabla>` (extracción real, con
 *    diálogo de advertencia explícito antes de correr por ser la acción más sensible/lenta de
 *    todo el módulo). Los 3 agregan `--batch` (mismo motivo que promptSqlmapScan()). Sintaxis
 *    confirmada contra github.com/sqlmapproject/sqlmap/wiki/Usage.
 *
 * Reorganizado 2026-08-26 (mockup aprobado por el usuario, pedido explícito "no es quitar es
 * optimizar y mejorar incluso agregar"): la card única "HERRAMIENTAS — nivel básico" (5
 * herramientas apiladas + card Pro aparte) pasa a un TabLayout de 3 pestañas —
 * Reconocimiento (nmap, theHarvester), Web (nikto, dirb, sqlmap), Pro (Kali Linux) — mismo
 * patrón visual que EntornoFragment.buildTabsSection()/renderTab(). La card "🌐 RED" queda FIJA
 * arriba de las pestañas (contexto de red, no una herramienta). Reorganización puramente
 * visual — los 2 botones reales de cada herramienta (panel nativo + terminal completo) se
 * mantienen sin cambios. `tabCard()` es una copia local de `BaseModuleFragment.addCard()` que
 * agrega a un `parent` explícito en vez de al `container` del Fragment (que `addCard()`/
 * `actionButton()` hardcodean) — necesario porque cada pestaña reconstruye su propio contenido
 * dentro de `tabContentContainer`, no directo en `container`.
 *
 * Investigación de opciones faltantes (mismo pedido, comparado contra flags oficiales
 * documentados de cada herramienta — ver comentarios en cada prompt*Scan() de abajo):
 *  - nmap: ya cubierto (`-sV` agregado en la ronda de "modo root" 2026-08-25) — sin cambios.
 *  - theHarvester: ya cubierto (`-d`/`-b`/`-l`) — sin cambios.
 *  - nikto: se agrega selector de categoría `-Tuning` (antes fijo en "1") — flag oficial
 *    documentado en github.com/sullo/nikto/wiki/Annotated-Option-List, bajo riesgo (solo cambia
 *    qué categorías de chequeos corre, no ejecuta nada destructivo).
 *  - dirb: se agrega selector de wordlist (`common.txt` default vs `big.txt`, ambos ya
 *    empaquetados por el `pkg install dirb` de Termux) — mismo mecanismo oficial de dirb
 *    (segundo argumento posicional), bajo riesgo (solo cambia cobertura/tiempo del scan).
 *  - sqlmap: se agregan `--level` (1-5) y `--risk` (1-3) al panel de "prueba rápida" —
 *    flags oficiales estándar de sqlmap (github.com/sqlmapproject/sqlmap/wiki/Usage), default
 *    1/1 preserva el comportamiento previo exacto si el usuario no los toca.
 *
 * Disclaimer legal/ético agregado 2026-08-26 (`docs/modulos/CIBERSEGURIDAD.md` sección 11 —
 * de las ~10 acciones ofensivas del módulo, solo `sqlmap --dump` tenía un aviso; el resto no
 * tenía nada y el wizard de onboarding no cubre este módulo). Dos capas, mismo criterio que
 * `WizardWelcomeFragment` (checkbox "Acepto los Términos y Condiciones", persistido en
 * `SharedPreferences("kairos_prefs")`):
 *  1. [showDisclaimerBanner] — card fija arriba de todo (antes de "🌐 RED"), siempre visible,
 *     sin poder ocultarse — mismo tono directo/sin alarmismo que ya usaba el aviso de
 *     `sqlmap --dump`.
 *  2. [maybeShowDisclaimerGate] — diálogo de aceptación única (checkbox "Entiendo y acepto",
 *     no cancelable, mismo patrón que `WizardWelcomeFragment.showTermsDialog()`) antes de dejar
 *     ver el resto del módulo la primera vez — se persiste en la misma `SharedPreferences`
 *     ("kairos_prefs") bajo una key propia (`PREF_CIBERSEGURIDAD_DISCLAIMER_ACCEPTED`), no en
 *     `PREF_TOS_ACCEPTED` del wizard (son aceptaciones distintas: TOS = uso general de la app,
 *     este gate = uso específico de herramientas ofensivas).
 */
class CiberseguridadFragment : BaseModuleFragment() {

    override fun getModuleId() = "ciberseguridad"
    override fun getModuleName() = getString(com.termux.R.string.ciberseguridad_module_name)

    private var ipValue: android.widget.TextView? = null
    private var lanResultsContainer: LinearLayout? = null
    private var lanScanButton: TextView? = null

    // TabLayout "Reconocimiento / Web / Pro" (ver KDoc de la clase, reorg 2026-08-26) —
    // mismo patrón que EntornoFragment.buildTabsSection()/renderTab(): tabContentContainer se
    // limpia y reconstruye en cada cambio de pestaña, activeTabIndex sobrevive a la reconstrucción
    // de buildContent() (ej. tras instalar Pro) para no volver siempre a la pestaña 0.
    private var tabContentContainer: LinearLayout? = null
    private var activeTabIndex = 0

    // sqlmap tiene 4 acciones mutuamente excluyentes (scan/dbs/tables/dump), cada una con
    // targets/params propios — no es un simple encendido/apagado, así que no encaja en
    // dropdownSwitchRow(). Con dropdownRow() (nuevo componente, ver docs/humano/humano194.md/
    // humano195.md) se reemplazan los 4 botones sueltos por 1 dropdown + 1 botón "Ejecutar"
    // que despacha al prompt correcto según la opción elegida.
    private val SQLMAP_ACTIONS = arrayOf(
        "Prueba rápida de SQLi (--batch)",
        "Listar bases de datos (--dbs)",
        "Listar tablas de una base (--tables)",
        "Volcar tabla (--dump, sensible)"
    )
    private var sqlmapActionRow: DropdownRow? = null

    override fun buildContent() {
        // Instalación silenciosa en segundo plano (pedido explícito, ver docs/humano/humano181.md:
        // "en n8n, hermes y ciberseguridad, en la ventana de instalar debe salir la opción o un
        // switch para la instalación silenciosa que al tocar se empiece a instalar pero se
        // cierre la ventana y el usuario pueda seguir utilizando el apk, además cuando termine
        // debe avisar" — Hermes/n8n ya lo tenían desde 2026-08-13 (humano101), a Ciberseguridad
        // le faltaba: mismo patrón que N8nFragment.showSilentInstallVariantDialog(), con las 3
        // variantes reales de modulos/ciberseguridad.sh (básico/pro-headless/pro-gui).
        if (!isModuleInstalled()) {
            showNotInstalled(getModuleName()) { showSilentInstallVariantDialog() }
            return
        }

        // Gate de aceptación única (ver KDoc de la clase) — se muestra ANTES que cualquier otro
        // contenido y corta buildContent() acá (no renderiza nada más hasta que el usuario acepte).
        // No bloquea la instalación en sí (ya ocurrió arriba), solo el USO de las herramientas.
        if (!isDisclaimerAccepted()) {
            showDisclaimerGate()
            return
        }

        val registry = try { ModuleRegistry(requireContext()).load() } catch (_: Exception) { null }
        val tier = registry?.get("ciberseguridad.tier") ?: "basico"
        val kaliContainer = registry?.get("ciberseguridad.kali_container")?.takeIf { it.isNotBlank() }
        val kaliGui = registry?.get("ciberseguridad.kali_gui") == "true"
        val isPro = tier == "pro" && kaliContainer != null

        // Banner legal/ético fijo (ver KDoc de la clase) — arriba de todo, antes de "🌐 RED".
        addDisclaimerBanner()

        // Card "🌐 RED" — pedido explícito del usuario: datos reales del dispositivo (IP
        // local, ya existía) + un panel tipo "mini terminal" con dispositivos conectados en
        // la LAN (nuevo, 2026-08-17). El scan NUNCA corre solo — requiere tocar "Escanear" —
        // usa `nmap -sn <subred>/24` (ya instalado por este mismo módulo, PASO 1) en vez del
        // scanSubnet() de RemoteManager: ese algoritmo es específico para encontrar
        // servidores OpenCode/agentes en el puerto fijo REMOTE_SCAN_PORT (valida con un
        // health check HTTP a ese puerto) — no sirve para listar "cualquier dispositivo de
        // la LAN", que es lo que pidió el usuario acá.
        addCard(getString(com.termux.R.string.ciberseguridad_network_card_title)) {
            addView(infoRow(getString(com.termux.R.string.ciberseguridad_ip_local_label), getString(com.termux.R.string.ciberseguridad_loading_placeholder)).also { ipValue = it.valueTextView() })
            addView(infoRow(getString(com.termux.R.string.ciberseguridad_installed_level_label), if (isPro) getString(com.termux.R.string.ciberseguridad_level_pro_value) else getString(com.termux.R.string.ciberseguridad_level_basic_value)))
            if (isPro) {
                addView(infoRow(getString(com.termux.R.string.ciberseguridad_kali_container_label), kaliContainer ?: "—"))
                addView(infoRow(getString(com.termux.R.string.ciberseguridad_gui_label), if (kaliGui) getString(com.termux.R.string.ciberseguridad_gui_yes) else getString(com.termux.R.string.ciberseguridad_gui_no)))
            }
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_scan_lan_button), GHOST) {
                scanLan()
            }.also { lanScanButton = it as? TextView })
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                lanResultsContainer = this
            })
        }
        loadLocalIp()

        buildToolsTabs(isPro, kaliContainer, kaliGui)

        // Mismo helper compartido que el resto de los Fragments con pantalla propia (ver
        // BaseModuleFragment.addMaintenanceCard(), auditoría 2026-08-19) — antes reimplementaba
        // acá mismo un uninstall "simple" sin la opción de desinstalación profunda que sí
        // tenía GenericModuleFragment para el resto de módulos sin pantalla propia.
        addMaintenanceCard()
    }

    // ────────────────────────────────────────────────────────────
    // Disclaimer legal/ético (ver KDoc de la clase, 2026-08-26) — banner fijo siempre visible +
    // gate de aceptación única antes del primer uso. Mismo archivo de SharedPreferences que
    // WizardWelcomeFragment ("kairos_prefs"), key propia (no reusa PREF_TOS_ACCEPTED: son
    // aceptaciones distintas — TOS general de la app vs. uso de herramientas ofensivas).
    // ────────────────────────────────────────────────────────────

    private fun disclaimerPrefs() = requireContext().getSharedPreferences("kairos_prefs", 0)

    private fun isDisclaimerAccepted(): Boolean = disclaimerPrefs().getBoolean(PREF_DISCLAIMER_ACCEPTED, false)

    private fun disclaimerShortText() = getString(com.termux.R.string.ciberseguridad_disclaimer_short)

    private fun disclaimerFullText() = getString(com.termux.R.string.ciberseguridad_disclaimer_full)

    /**
     * Gate no cancelable (mismo criterio que el TOS del wizard) — reemplaza TODO el contenido
     * del fragment por el diálogo hasta que el usuario tilde el checkbox y toque "Entiendo y
     * acepto". No se puede cerrar tocando afuera ni con el botón atrás (setCancelable(false) +
     * sin setNegativeButton) — es una aceptación real, no un aviso que se pueda ignorar.
     */
    private fun showDisclaimerGate() {
        val ctx = requireContext()
        val body = TextView(ctx).apply {
            text = disclaimerFullText()
            textSize = 13f
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }
        val checkboxRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val checkbox = CheckBox(ctx)
        checkboxRow.addView(checkbox)
        checkboxRow.addView(TextView(ctx).apply {
            text = getString(com.termux.R.string.ciberseguridad_disclaimer_checkbox)
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
        })
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(android.widget.ScrollView(ctx).apply {
                addView(body)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260))
            })
            addView(checkboxRow)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(com.termux.R.string.ciberseguridad_disclaimer_dialog_title))
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton(getString(com.termux.R.string.ciberseguridad_disclaimer_accept_button), null)
            .show()
        val acceptButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        acceptButton.isEnabled = false
        checkbox.setOnCheckedChangeListener { _, checked -> acceptButton.isEnabled = checked }
        acceptButton.setOnClickListener {
            disclaimerPrefs().edit().putBoolean(PREF_DISCLAIMER_ACCEPTED, true).apply()
            dialog.dismiss()
            if (!isAdded) return@setOnClickListener
            container.removeAllViews()
            buildContent()
        }
    }

    /** Card fija, siempre visible — mismo tono directo/sin alarmismo que ya usaba el aviso de
     * `sqlmap --dump`. "Ver más" abre el texto completo del gate (mismo texto, sin re-pedir
     * aceptación — ya se aceptó una vez para llegar hasta acá). */
    private fun addDisclaimerBanner() {
        addCard(getString(com.termux.R.string.ciberseguridad_disclaimer_banner_title)) {
            addView(TextView(requireContext()).apply {
                text = disclaimerShortText()
                textSize = 12f
                setTextColor(requireContext().kairosThemeColor(com.termux.R.attr.kairosText2))
                setPadding(dp(14), dp(4), dp(14), dp(4))
            })
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_disclaimer_view_full), GHOST) {
                showScanResultDialog(getString(com.termux.R.string.ciberseguridad_disclaimer_full_dialog_title), disclaimerFullText().lines())
            })
        }
    }

    // ────────────────────────────────────────────────────────────
    // TabLayout "Reconocimiento / Web / Pro" — ver KDoc de la clase (reorg 2026-08-26).
    // Mismo patrón que EntornoFragment.buildTabsSection()/renderTab(): un solo TabLayout arriba
    // de un contenedor que se limpia y reconstruye en cada cambio de pestaña.
    // ────────────────────────────────────────────────────────────

    private fun buildToolsTabs(isPro: Boolean, kaliContainer: String?, kaliGui: Boolean) {
        val ctx = requireContext()
        val tabLayout = TabLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, dp(4))
            }
            // Bug visual real confirmado por ADB (2026-08-26, screenshot en dispositivo real —
            // ver docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md ítem 7): con MODE_FIXED,
            // las 3 pestañas se dividen el ancho en partes iguales — "Reconocimiento" (14
            // caracteres, en mayúsculas) no entra en ese tercio y el TabLayout lo envuelve a 2
            // líneas cortando la palabra a la mitad ("RECONOCIME"/"NTO"), quedando desalineado
            // contra "WEB"/"PRO" (una sola línea). MODE_SCROLLABLE deja que cada pestaña mida su
            // propio contenido — sin wrap, sin cortar palabras — comportamiento estándar de
            // Material Design para un TabLayout con etiquetas de longitud dispareja.
            tabMode = TabLayout.MODE_SCROLLABLE
            setSelectedTabIndicatorColor(ctx.kairosThemeColor(com.termux.R.attr.kairosGreen))
            setTabTextColors(ctx.kairosThemeColor(com.termux.R.attr.kairosText3), ctx.kairosThemeColor(com.termux.R.attr.kairosText))
            setBackgroundColor(ctx.kairosThemeColor(com.termux.R.attr.kairosBg2))
        }
        listOf(
            getString(com.termux.R.string.ciberseguridad_tab_reconocimiento),
            getString(com.termux.R.string.ciberseguridad_tab_web),
            getString(com.termux.R.string.ciberseguridad_tab_pro)
        ).forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        container.addView(tabLayout)

        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(content)
        tabContentContainer = content

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { renderToolsTab(tab.position, isPro, kaliContainer, kaliGui) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        tabLayout.getTabAt(activeTabIndex)?.select()
        renderToolsTab(activeTabIndex, isPro, kaliContainer, kaliGui)
    }

    private fun renderToolsTab(index: Int, isPro: Boolean, kaliContainer: String?, kaliGui: Boolean) {
        activeTabIndex = index
        val content = tabContentContainer ?: return
        content.removeAllViews()
        when (index) {
            0 -> renderReconocimientoTab(content)
            1 -> renderWebTab(content)
            else -> renderProTab(content, isPro, kaliContainer, kaliGui)
        }
    }

    /**
     * Copia local de [BaseModuleFragment.addCard] que agrega a un [parent] explícito en vez de
     * al `container` del Fragment — `addCard()`/`actionButton()` de la clase base hardcodean
     * `container.addView(...)`, lo que no sirve acá porque cada pestaña reconstruye su propio
     * contenido dentro de `tabContentContainer`, no directo en `container` (ver KDoc de la clase).
     *
     * [title] nullable (2026-08-26, bug visual real confirmado por ADB — ver KDoc de la clase
     * y docs/arquitectura/DEPURACION_COMPLETA_2026-08-26.md ítem 7): "RECONOCIMIENTO"/"WEB"
     * repetían EXACTO el texto que la pestaña activa del TabLayout ya muestra arriba —
     * encabezado redundante, se quita en esos 2 casos. "KALI LINUX (PRO)" en renderProTab()
     * sigue con título propio porque no es un duplicado literal de "PRO" (agrega info real).
     */
    private fun tabCard(parent: LinearLayout, title: String? = null, block: LinearLayout.() -> Unit) {
        val ctx = requireContext()
        if (title != null) {
            parent.addView(TextView(ctx).apply {
                text = title
                textSize = 10f
                setTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosText3))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f
                setPadding(dp(4), dp(16), dp(4), dp(8))
            })
        }
        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(com.termux.R.attr.kairosBg2))
            radius = resources.getDimension(com.termux.R.dimen.kairos_stroke_default)
            strokeColor = ctx.kairosThemeColor(com.termux.R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            setContentPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp(8)
            }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            block()
        }
        card.addView(inner)
        parent.addView(card)
    }

    private fun renderReconocimientoTab(parent: LinearLayout) {
        tabCard(parent) {
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_nmap_native_button), GHOST) { promptNmapScan() })
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_nmap_terminal_button), GHOST) { launchTerminalCommand("nmap") })
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_theharvester_native_button), GHOST) { promptTheHarvesterScan() })
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_theharvester_terminal_button), GHOST) { launchTerminalCommand("theHarvester") })
        }
    }

    private fun renderWebTab(parent: LinearLayout) {
        tabCard(parent) {
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_nikto_native_button), GHOST) { promptNiktoScan() })
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_nikto_terminal_button), GHOST) { launchTerminalCommand("nikto") })
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_dirb_native_button), GHOST) { promptDirbScan() })
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_dirb_terminal_button), GHOST) { launchTerminalCommand("dirb") })
            // sqlmap: 4 acciones mutuamente excluyentes → dropdownRow() + 1 botón "Ejecutar"
            // en vez de 4 botones sueltos (ver comentario de SQLMAP_ACTIONS más arriba).
            addView(
                dropdownRow(getString(com.termux.R.string.ciberseguridad_sqlmap_action_label), SQLMAP_ACTIONS.toList()) { }
                    .also { sqlmapActionRow = it }
                    .root
            )
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_sqlmap_run_button), GHOST) {
                when (sqlmapActionRow?.selectedOptionIndex() ?: 0) {
                    0 -> promptSqlmapScan()
                    1 -> promptSqlmapDbs()
                    2 -> promptSqlmapTables()
                    else -> promptSqlmapDump()
                }
            })
            addView(createActionButton(getString(com.termux.R.string.ciberseguridad_sqlmap_terminal_button), GHOST) { launchTerminalCommand("sqlmap") })
        }
    }

    private fun renderProTab(parent: LinearLayout, isPro: Boolean, kaliContainer: String?, kaliGui: Boolean) {
        val kaliTabTitle = getString(com.termux.R.string.ciberseguridad_kali_tab_title)
        if (isPro) {
            tabCard(parent, kaliTabTitle) {
                addView(createActionButton(getString(com.termux.R.string.ciberseguridad_kali_enter_button), PRIMARY) {
                    launchTerminalCommand("proot-distro login $kaliContainer", sessionName = getString(com.termux.R.string.ciberseguridad_kali_session_name))
                })
                if (kaliGui) {
                    addView(createActionButton(getString(com.termux.R.string.ciberseguridad_kali_gui_start_button), GHOST) {
                        launchTerminalCommand(
                            "bash ~/scripts/entorno/gui_start.sh --distro $kaliContainer",
                            sessionName = getString(com.termux.R.string.ciberseguridad_kali_gui_session_name)
                        )
                    })
                }
            }
        } else {
            tabCard(parent, kaliTabTitle) {
                addView(infoRow(getString(com.termux.R.string.ciberseguridad_status_label), getString(com.termux.R.string.ciberseguridad_not_installed_value)))
                addView(createActionButton(getString(com.termux.R.string.ciberseguridad_install_pro_button), GHOST) { showProInstallDialog() })
            }
        }
    }

    private fun loadLocalIp() {
        Thread {
            val ip = try { RemoteManager.getLocalIp() } catch (_: Exception) { "—" }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                ipValue?.text = ip
            }
        }.start()
    }

    // `nmap -sn <subred>/24` — ping-scan sin puertos, solo descubre hosts vivos (no valida
    // ningún servicio, a diferencia de RemoteManager.scanSubnet()). Corre en background con
    // timeout generoso (25s: una /24 completa puede tardar según la red) y nunca se dispara
    // solo — solo al tocar el botón "Escanear" (pedido explícito del usuario: un scan de red
    // no debe correr sin que el usuario lo pida).
    private fun scanLan() {
        if (!isAdded) return
        lanScanButton?.isEnabled = false
        lanScanButton?.text = getString(com.termux.R.string.ciberseguridad_scanning_button)
        renderLanResults(listOf(getString(com.termux.R.string.ciberseguridad_scanning_lan_placeholder)))
        Thread {
            val result = try { runLanScan() } catch (e: Exception) {
                LanScanResult.Error(e.message ?: getString(com.termux.R.string.ciberseguridad_unknown_error))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                lanScanButton?.isEnabled = true
                lanScanButton?.text = getString(com.termux.R.string.ciberseguridad_scan_lan_button)
                when (result) {
                    is LanScanResult.NotInstalled -> renderLanResults(listOf(getString(com.termux.R.string.ciberseguridad_nmap_missing_reinstall)))
                    is LanScanResult.Error -> renderLanResults(listOf(getString(com.termux.R.string.ciberseguridad_error_prefix, result.message)))
                    is LanScanResult.Empty -> renderLanResults(listOf(getString(com.termux.R.string.ciberseguridad_no_devices_found)))
                    is LanScanResult.Found -> renderLanResults(result.hosts)
                }
            }
        }.start()
    }

    private sealed class LanScanResult {
        object NotInstalled : LanScanResult()
        data class Error(val message: String) : LanScanResult()
        object Empty : LanScanResult()
        data class Found(val hosts: List<String>) : LanScanResult()
    }

    private fun runLanScan(): LanScanResult {
        val (checkCode, _, _) = ManagerNativeUtils.runShell("command -v nmap", 5)
        if (checkCode != 0) return LanScanResult.NotInstalled

        val ip = RemoteManager.getLocalIp()
        val octets = ip.split(".")
        if (octets.size != 4) return LanScanResult.Error(getString(com.termux.R.string.ciberseguridad_invalid_local_ip, ip))
        val subnet = "${octets[0]}.${octets[1]}.${octets[2]}.0/24"

        val (exitCode, stdout, stderr) = ManagerNativeUtils.runShell("nmap -sn $subnet", 25)
        if (exitCode != 0) return LanScanResult.Error(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_nmap_error) })

        // "Nmap scan report for host.lan (192.168.1.5)" o "Nmap scan report for 192.168.1.5"
        val hostRegex = Regex("""Nmap scan report for (\S+)(?:\s+\(([\d.]+)\))?""")
        val hosts = hostRegex.findAll(stdout).map { match ->
            val name = match.groupValues[1]
            val addr = match.groupValues[2]
            if (addr.isNotBlank() && addr != name) "$name  ($addr)" else name
        }.toList()

        return if (hosts.isEmpty()) LanScanResult.Empty else LanScanResult.Found(hosts)
    }

    private fun renderLanResults(lines: List<String>) {
        val ctx = requireContext()
        val holder = lanResultsContainer ?: return
        holder.removeAllViews()
        lines.forEach { line ->
            holder.addView(TextView(ctx).apply {
                text = line
                textSize = 12f
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setTextColor(ctx.kairosThemeColor(com.termux.R.attr.kairosText2))
            })
        }
    }

    // ────────────────────────────────────────────────────────────
    // Paneles nativos de "escaneo rápido" (nmap / nikto / dirb) — ver KDoc de la clase.
    // Patrón común: diálogo con el target → correr un preset con timeout acotado →
    // parsear stdout real a líneas legibles → mostrarlas en un AlertDialog con texto
    // monoespaciado (mismo criterio visual que renderLanResults()). Nunca se dispara
    // solo — siempre requiere tocar el botón y confirmar el target.
    // ────────────────────────────────────────────────────────────

    private fun promptTarget(title: String, hint: String, onConfirm: (String) -> Unit) {
        if (!isAdded) return
        val edit = EditText(requireContext()).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(edit)
            .setPositiveButton(getString(com.termux.R.string.ciberseguridad_scan_button)) { _, _ ->
                val target = edit.text.toString().trim()
                if (target.isNotEmpty()) onConfirm(target) else toast(getString(com.termux.R.string.ciberseguridad_target_empty))
            }
            .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
            .show()
    }

    private fun showScanResultDialog(title: String, lines: List<String>) {
        if (!isAdded) return
        val ctx = requireContext()
        val body = TextView(ctx).apply {
            text = if (lines.isEmpty()) getString(com.termux.R.string.ciberseguridad_no_results) else lines.joinToString("\n")
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(body) }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(getString(com.termux.R.string.ciberseguridad_close_button), null)
            .show()
    }

    private fun runQuickScan(progressMessage: String, resultTitle: String, run: () -> List<String>) {
        toast(progressMessage)
        Thread {
            val lines = try { run() } catch (e: Exception) { listOf("Error: ${e.message ?: "desconocido"}") }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                showScanResultDialog(resultTitle, lines)
            }
        }.start()
    }

    /**
     * nmap — escaneo rápido de puertos: `-F` (fast mode, escanea el subconjunto de puertos más
     * comunes) `-T4` (timing template "aggressive", ver nmap.org/book/man-timing.html) `-sV`
     * (detección de versión de servicio, nmap.org/book/man-briefoptions.html) sobre el target.
     * Parsea líneas de la tabla de puertos ("PORT/PROTO STATE SERVICE[ VERSION]").
     *
     * MVP de modo root (2026-08-25, ver `docs/arquitectura/INVESTIGACION_MODO_ROOT_2026-08-25.md`,
     * luz verde explícita del usuario): SYN scan (`-sS`) requiere raw sockets — solo root los
     * tiene. Sin root, nmap cae solo a TCP connect scan (`-sT`, lo que ya corría acá antes) —
     * más lento y más detectable. Con `RootAccess.hasRoot()`, se corre vía `su -c` con `-sS`
     * real; sin root, mismo camino de siempre sin cambios de comportamiento.
     */
    private fun promptNmapScan() {
        promptTarget(getString(com.termux.R.string.ciberseguridad_nmap_prompt_title), getString(com.termux.R.string.ciberseguridad_nmap_prompt_hint)) { target ->
            // RootAccess.hasRoot() corre un subproceso (cacheado, pero la 1ra vez puede tardar
            // hasta 3s) — se decide DENTRO del Thread de runQuickScan(), nunca antes, para no
            // bloquear el hilo de UI en el click del diálogo.
            runQuickScan(getString(com.termux.R.string.ciberseguridad_nmap_scanning_progress, target), getString(com.termux.R.string.ciberseguridad_nmap_result_title, target)) {
                val (code, _, _) = ManagerNativeUtils.runShell("command -v nmap", 5)
                if (code != 0) return@runQuickScan listOf(getString(com.termux.R.string.ciberseguridad_tool_not_installed, "nmap"))
                val useRoot = com.termux.app.util.RootAccess.hasRoot()
                val scanFlags = if (useRoot) "-F -T4 -sV -sS" else "-F -T4 -sV"
                val (exitCode, stdout, stderr) = if (useRoot) {
                    val r = com.termux.app.util.RootAccess.runAsRoot("nmap $scanFlags '$target'", 45)
                    Triple(if (r.ok) 0 else 1, r.stdout, r.stderr)
                } else {
                    ManagerNativeUtils.runShell("nmap $scanFlags '$target'", 45)
                }
                if (exitCode != 0 && stdout.isBlank()) return@runQuickScan listOf(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_nmap_error) })
                val portRegex = Regex("""^(\d+/\w+)\s+(\S+)\s+(\S+)(?:\s+(.*))?$""")
                val results = stdout.lines().mapNotNull { line ->
                    val m = portRegex.find(line.trim()) ?: return@mapNotNull null
                    val (port, state, service, version) = m.destructured
                    "$port  $state  $service" + if (version.isNotBlank()) "  ($version)" else ""
                }
                if (results.isEmpty()) listOf(getString(com.termux.R.string.ciberseguridad_nmap_no_ports)) else results
            }
        }
    }

    /**
     * nikto — escaneo rápido de vulnerabilidades web: `-h <target>` (host/URL objetivo) `-Tuning
     * <categoría>` (categoría de chequeos, ver github.com/sullo/nikto/wiki/
     * Annotated-Option-List) `-maxtime 30s` (límite de tiempo real por host, mismo doc oficial)
     * para acotar el scan a algo razonable desde un botón de la app. Parsea las líneas de
     * hallazgo reales de nikto (siempre empiezan con "+ ").
     *
     * Selector de `-Tuning` agregado 2026-08-26 (investigación de opciones faltantes, ver KDoc
     * de la clase) — antes la categoría estaba fija en "1", sin forma de elegir otra sin caer al
     * terminal completo. Categorías oficiales confirmadas contra la wiki citada arriba; "1" sigue
     * siendo el default para no cambiar el comportamiento previo si el usuario no toca el selector.
     */
    private fun niktoTuningCategories() = arrayOf(
        "1" to getString(com.termux.R.string.ciberseguridad_nikto_tuning_1),
        "2" to getString(com.termux.R.string.ciberseguridad_nikto_tuning_2),
        "3" to getString(com.termux.R.string.ciberseguridad_nikto_tuning_3),
        "4" to getString(com.termux.R.string.ciberseguridad_nikto_tuning_4),
        "9" to getString(com.termux.R.string.ciberseguridad_nikto_tuning_9),
        "b" to getString(com.termux.R.string.ciberseguridad_nikto_tuning_b)
    )

    private fun promptNiktoScan() {
        val ctx = requireContext()
        val targetEdit = EditText(ctx).apply { hint = getString(com.termux.R.string.ciberseguridad_url_hint) }
        val tuningCategories = niktoTuningCategories()
        var selectedTuning = tuningCategories[0]
        val tuningLabel = TextView(ctx).apply {
            text = getString(com.termux.R.string.ciberseguridad_nikto_tuning_label, selectedTuning.second)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        }
        tuningLabel.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(getString(com.termux.R.string.ciberseguridad_nikto_tuning_dialog_title))
                .setItems(tuningCategories.map { "${it.first} — ${it.second}" as CharSequence }.toTypedArray()) { _, which ->
                    selectedTuning = tuningCategories[which]
                    tuningLabel.text = getString(com.termux.R.string.ciberseguridad_nikto_tuning_label, selectedTuning.second)
                }
                .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
                .show()
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(targetEdit)
            addView(tuningLabel)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(com.termux.R.string.ciberseguridad_nikto_prompt_title))
            .setView(layout)
            .setPositiveButton(getString(com.termux.R.string.ciberseguridad_scan_button)) { _, _ ->
                val target = targetEdit.text.toString().trim()
                if (target.isEmpty()) { toast(getString(com.termux.R.string.ciberseguridad_target_empty)); return@setPositiveButton }
                val tuning = selectedTuning.first
                runQuickScan(getString(com.termux.R.string.ciberseguridad_nikto_scanning_progress, target, tuning), getString(com.termux.R.string.ciberseguridad_nikto_result_title, target)) {
                    val (code, _, _) = ManagerNativeUtils.runShell("command -v nikto", 5)
                    if (code != 0) return@runQuickScan listOf(getString(com.termux.R.string.ciberseguridad_tool_not_installed, "nikto"))
                    val (_, stdout, stderr) = ManagerNativeUtils.runShell(
                        "nikto -h '$target' -Tuning $tuning -maxtime 30s", 45
                    )
                    val findings = stdout.lines().filter { it.trim().startsWith("+") }
                    if (findings.isEmpty()) {
                        listOf(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_nikto_no_findings, tuning, selectedTuning.second) })
                    } else findings.map { it.trim() }
                }
            }
            .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
            .show()
    }

    /**
     * dirb — fuerza bruta rápida de directorios: `-S` (silent — no lista cada palabra probada,
     * solo lo encontrado) `-w` (no detener ante WARNING) `-r` (no recursivo — un solo nivel, para
     * que el scan rápido termine en tiempo acotado) sobre el target, con el wordlist elegido.
     * Ver github.com/v0re/dirb/blob/master/dirb.1 para las opciones oficiales. Parsea las líneas
     * "+ URL (CODE:nnn|SIZE:nnn)" que dirb imprime por cada hallazgo.
     *
     * Selector de wordlist agregado 2026-08-26 (investigación de opciones faltantes, ver KDoc de
     * la clase) — antes siempre corría con el wordlist por defecto de dirb (common.txt, elegido
     * automáticamente por dirb cuando no se pasa un 2do argumento). "big.txt" ya viene empaquetado
     * por el mismo `pkg install dirb` de Termux junto a common.txt (confirmado en
     * $PREFIX/share/dirb/wordlists/) — más cobertura a costa de más tiempo. Con "common"
     * (default) se sigue sin pasar el 2do argumento, para no cambiar el comando exacto que ya
     * corría antes de este cambio.
     */
    private fun dirbWordlists() = arrayOf(
        "common" to getString(com.termux.R.string.ciberseguridad_dirb_wordlist_common),
        "big" to getString(com.termux.R.string.ciberseguridad_dirb_wordlist_big)
    )

    private fun promptDirbScan() {
        val ctx = requireContext()
        val targetEdit = EditText(ctx).apply { hint = getString(com.termux.R.string.ciberseguridad_url_hint) }
        val wordlists = dirbWordlists()
        var selectedWordlist = wordlists[0]
        val wordlistLabel = TextView(ctx).apply {
            text = getString(com.termux.R.string.ciberseguridad_dirb_wordlist_label, selectedWordlist.second)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        }
        wordlistLabel.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(getString(com.termux.R.string.ciberseguridad_dirb_wordlist_dialog_title))
                .setItems(wordlists.map { it.second as CharSequence }.toTypedArray()) { _, which ->
                    selectedWordlist = wordlists[which]
                    wordlistLabel.text = getString(com.termux.R.string.ciberseguridad_dirb_wordlist_label, selectedWordlist.second)
                }
                .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
                .show()
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(targetEdit)
            addView(wordlistLabel)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(com.termux.R.string.ciberseguridad_dirb_prompt_title))
            .setView(layout)
            .setPositiveButton(getString(com.termux.R.string.ciberseguridad_scan_button)) { _, _ ->
                val target = targetEdit.text.toString().trim()
                if (target.isEmpty()) { toast(getString(com.termux.R.string.ciberseguridad_target_empty)); return@setPositiveButton }
                val wordlistId = selectedWordlist.first
                val wordlistArg = if (wordlistId == "big") " \"\$PREFIX/share/dirb/wordlists/big.txt\"" else ""
                runQuickScan(getString(com.termux.R.string.ciberseguridad_dirb_scanning_progress, target, wordlistId), getString(com.termux.R.string.ciberseguridad_dirb_result_title, target)) {
                    val (code, _, _) = ManagerNativeUtils.runShell("command -v dirb", 5)
                    if (code != 0) return@runQuickScan listOf(getString(com.termux.R.string.ciberseguridad_tool_not_installed, "dirb"))
                    val (_, stdout, stderr) = ManagerNativeUtils.runShell(
                        "dirb '$target'$wordlistArg -S -w -r", 45
                    )
                    val findings = stdout.lines().filter { it.trim().startsWith("+") }
                    if (findings.isEmpty()) {
                        listOf(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_dirb_no_findings) })
                    } else findings.map { it.trim() }
                }
            }
            .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
            .show()
    }

    /**
     * sqlmap — prueba rápida de inyección SQL: `-u <url> --batch`. `--batch` es el modo
     * no-interactivo oficial de sqlmap ("Never ask for user input, use the default behavior",
     * confirmado contra github.com/sqlmapproject/sqlmap/wiki/Usage 2026-08-19) — sin este flag
     * sqlmap pide confirmaciones en cada paso (heurísticas de detección, uso de payloads
     * adicionales, etc.) y se cuelga esperando stdin en un ProcessBuilder sin TTY. A diferencia
     * de nmap/nikto/dirb (host/URL pelado), sqlmap necesita un parámetro real en la URL para
     * tener algo que probar (ej. "?id=1") — de ahí el hint del diálogo, distinto al de las otras
     * 3 herramientas, para que el usuario no reuse el hábito de pegar solo un host/IP.
     *
     * Timeout de 90s vía `ManagerNativeUtils.runShell` (mismo mecanismo que las otras 3: proceso
     * corre en background, `process.waitFor(timeoutSeconds, TimeUnit.SECONDS)` seguido de
     * `destroyForcibly()` si no terminó a tiempo) — más alto que nmap/nikto/dirb (45s) porque
     * sqlmap con `--batch` y su nivel/riesgo por defecto (level=1, risk=1) igual corre varias
     * rondas de payloads por parámetro antes de concluir, no tiene un flag de límite de tiempo
     * tan directo como el `-maxtime` de nikto. Parsea las líneas reales que sqlmap imprime por
     * hallazgo ("Parameter: ... is vulnerable") y el mensaje de cierre limpio ("does not appear
     * to be injectable") documentados en la auditoría de módulos citada en el KDoc de la clase.
     *
     * `--level` (1-5) y `--risk` (1-3) agregados 2026-08-26 (investigación de opciones
     * faltantes, ver KDoc de la clase) — flags oficiales estándar de sqlmap (github.com/
     * sqlmapproject/sqlmap/wiki/Usage: `--level` controla cuántos puntos de inyección/payloads se
     * prueban, `--risk` controla qué tan intrusivos son los payloads probados). Default 1/1 —
     * mismo comportamiento exacto que antes de este cambio si el usuario no toca los campos.
     */
    private fun promptSqlmapScan() {
        val ctx = requireContext()
        val targetEdit = EditText(ctx).apply { hint = getString(com.termux.R.string.ciberseguridad_sqlmap_url_param_hint) }
        val levelEdit = EditText(ctx).apply {
            hint = getString(com.termux.R.string.ciberseguridad_sqlmap_level_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val riskEdit = EditText(ctx).apply {
            hint = getString(com.termux.R.string.ciberseguridad_sqlmap_risk_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(targetEdit)
            addView(levelEdit)
            addView(riskEdit)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(com.termux.R.string.ciberseguridad_sqlmap_scan_dialog_title))
            .setView(layout)
            .setPositiveButton(getString(com.termux.R.string.ciberseguridad_sqlmap_test_button)) { _, _ ->
                val target = targetEdit.text.toString().trim()
                if (target.isEmpty()) { toast(getString(com.termux.R.string.ciberseguridad_target_empty)); return@setPositiveButton }
                val level = levelEdit.text.toString().trim().toIntOrNull()?.coerceIn(1, 5) ?: 1
                val risk = riskEdit.text.toString().trim().toIntOrNull()?.coerceIn(1, 3) ?: 1
                runQuickScan(
                    getString(com.termux.R.string.ciberseguridad_sqlmap_scan_progress, target, level.toString(), risk.toString()),
                    getString(com.termux.R.string.ciberseguridad_sqlmap_scan_result_title, target)
                ) {
                    val (code, _, _) = ManagerNativeUtils.runShell("command -v sqlmap", 5)
                    if (code != 0) return@runQuickScan listOf(getString(com.termux.R.string.ciberseguridad_tool_not_installed, "sqlmap"))
                    val (_, stdout, stderr) = ManagerNativeUtils.runShell(
                        "sqlmap -u '$target' --batch --level=$level --risk=$risk", 90
                    )
                    val findings = stdout.lines()
                        .map { it.trim() }
                        .filter {
                            it.startsWith("Parameter:") ||
                                it.contains("is vulnerable") ||
                                it.contains("appear to be injectable") ||
                                it.startsWith("sqlmap identified")
                        }
                    if (findings.isEmpty()) {
                        listOf(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_sqlmap_scan_no_results) })
                    } else findings
                }
            }
            .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
            .show()
    }

    /**
     * theHarvester — OSINT rápido: `-d <dominio> -b <fuente> -l <límite>`. Flags confirmados
     * contra deepwiki.com/laramies/theHarvester/4.1-command-line-interface (2026-08-19): `-d`
     * dominio objetivo, `-b` fuente de búsqueda (un módulo por corrida), `-l` límite de
     * resultados. El spinner de fuentes solo ofrece las que NO piden API key en
     * `~/.theHarvester/api-keys.yaml` (duckduckgo/bing/crtsh/otx/rapiddns/threatminer/urlscan) —
     * fuentes con clave (shodan, securityTrails, etc.) siguen quedando en terminal completo.
     * Parsea líneas con "@" (emails) o con forma de host (contienen el dominio buscado) del
     * bloque de resultados real que imprime theHarvester al final de la corrida.
     */
    private val THEHARVESTER_NO_KEY_SOURCES = arrayOf(
        "duckduckgo", "bing", "crtsh", "otx", "rapiddns", "threatminer", "urlscan"
    )

    private fun promptTheHarvesterScan() {
        val ctx = requireContext()
        val domainEdit = EditText(ctx).apply { hint = getString(com.termux.R.string.ciberseguridad_theharvester_domain_hint) }
        val limitEdit = EditText(ctx).apply {
            hint = getString(com.termux.R.string.ciberseguridad_theharvester_limit_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        var selectedSource = THEHARVESTER_NO_KEY_SOURCES[0]
        val sourceLabel = TextView(ctx).apply {
            text = getString(com.termux.R.string.ciberseguridad_theharvester_source_label, selectedSource)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        }
        sourceLabel.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(getString(com.termux.R.string.ciberseguridad_theharvester_source_dialog_title))
                .setItems(THEHARVESTER_NO_KEY_SOURCES.map { it as CharSequence }.toTypedArray()) { _, which ->
                    selectedSource = THEHARVESTER_NO_KEY_SOURCES[which]
                    sourceLabel.text = getString(com.termux.R.string.ciberseguridad_theharvester_source_label, selectedSource)
                }
                .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
                .show()
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(domainEdit)
            addView(sourceLabel)
            addView(limitEdit)
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(com.termux.R.string.ciberseguridad_theharvester_prompt_title))
            .setView(layout)
            .setPositiveButton(getString(com.termux.R.string.ciberseguridad_theharvester_search_button)) { _, _ ->
                val domain = domainEdit.text.toString().trim()
                if (domain.isEmpty()) { toast(getString(com.termux.R.string.ciberseguridad_theharvester_domain_empty)); return@setPositiveButton }
                val limit = limitEdit.text.toString().trim().ifEmpty { "100" }
                runQuickScan(
                    getString(com.termux.R.string.ciberseguridad_theharvester_progress, domain, selectedSource, limit),
                    getString(com.termux.R.string.ciberseguridad_theharvester_result_title, domain)
                ) {
                    val (code, _, _) = ManagerNativeUtils.runShell("command -v theHarvester", 5)
                    if (code != 0) return@runQuickScan listOf(getString(com.termux.R.string.ciberseguridad_tool_not_installed, "theHarvester"))
                    val (_, stdout, stderr) = ManagerNativeUtils.runShell(
                        "theHarvester -d '$domain' -b $selectedSource -l $limit", 60
                    )
                    val findings = stdout.lines()
                        .map { it.trim() }
                        .filter { line ->
                            line.isNotEmpty() && (line.contains("@") || (line.contains(domain) && !line.startsWith("*") && !line.startsWith("[*]")))
                        }
                        .distinct()
                    if (findings.isEmpty()) {
                        listOf(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_theharvester_no_findings, selectedSource) })
                    } else findings
                }
            }
            .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
            .show()
    }

    /**
     * Diálogo compartido "base de datos [+ tabla]" para las 3 acciones extendidas de sqlmap
     * (--dbs/--tables/--dump) — [askTable] controla si se pide también el nombre de tabla
     * (--tables y --dump la necesitan como `-T`, --dbs no la usa en absoluto).
     */
    private fun promptSqlmapDbTarget(title: String, askTable: Boolean, onConfirm: (target: String, db: String, table: String) -> Unit) {
        if (!isAdded) return
        val ctx = requireContext()
        val targetEdit = EditText(ctx).apply { hint = getString(com.termux.R.string.ciberseguridad_sqlmap_url_param_hint) }
        val dbEdit = EditText(ctx).apply { hint = getString(com.termux.R.string.ciberseguridad_sqlmap_db_hint) }
        val tableEdit = if (askTable) EditText(ctx).apply { hint = getString(com.termux.R.string.ciberseguridad_sqlmap_table_hint) } else null
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(targetEdit)
            addView(dbEdit)
            tableEdit?.let { addView(it) }
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(getString(com.termux.R.string.ciberseguridad_continue_button)) { _, _ ->
                val target = targetEdit.text.toString().trim()
                val db = dbEdit.text.toString().trim()
                val table = tableEdit?.text?.toString()?.trim().orEmpty()
                if (target.isEmpty()) { toast(getString(com.termux.R.string.ciberseguridad_target_empty)); return@setPositiveButton }
                onConfirm(target, db, table)
            }
            .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
            .show()
    }

    /** sqlmap --dbs --batch: lista las bases de datos accesibles vía la inyección detectada. */
    private fun promptSqlmapDbs() {
        promptTarget(getString(com.termux.R.string.ciberseguridad_sqlmap_dbs_dialog_title), getString(com.termux.R.string.ciberseguridad_sqlmap_url_param_hint)) { target ->
            runQuickScan(getString(com.termux.R.string.ciberseguridad_sqlmap_dbs_progress, target), getString(com.termux.R.string.ciberseguridad_sqlmap_dbs_result_title, target)) {
                val (code, _, _) = ManagerNativeUtils.runShell("command -v sqlmap", 5)
                if (code != 0) return@runQuickScan listOf(getString(com.termux.R.string.ciberseguridad_tool_not_installed, "sqlmap"))
                val (_, stdout, stderr) = ManagerNativeUtils.runShell("sqlmap -u '$target' --batch --dbs", 120)
                val findings = stdout.lines().map { it.trim() }.filter { it.startsWith("[*]") }
                if (findings.isEmpty()) {
                    listOf(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_sqlmap_dbs_no_results) })
                } else findings
            }
        }
    }

    /** sqlmap --tables -D <db> --batch: lista las tablas de una base ya conocida (requiere --dbs primero). */
    private fun promptSqlmapTables() {
        promptSqlmapDbTarget(getString(com.termux.R.string.ciberseguridad_sqlmap_tables_dialog_title), askTable = false) { target, db, _ ->
            if (db.isEmpty()) { toast(getString(com.termux.R.string.ciberseguridad_sqlmap_db_empty)); return@promptSqlmapDbTarget }
            runQuickScan(getString(com.termux.R.string.ciberseguridad_sqlmap_tables_progress, db, target), getString(com.termux.R.string.ciberseguridad_sqlmap_tables_result_title, db)) {
                val (code, _, _) = ManagerNativeUtils.runShell("command -v sqlmap", 5)
                if (code != 0) return@runQuickScan listOf(getString(com.termux.R.string.ciberseguridad_tool_not_installed, "sqlmap"))
                val (_, stdout, stderr) = ManagerNativeUtils.runShell(
                    "sqlmap -u '$target' --batch -D '$db' --tables", 120
                )
                val findings = stdout.lines().map { it.trim() }.filter { it.startsWith("|") && !it.matches(Regex("^\\|[- ]+\\|$")) }
                if (findings.isEmpty()) {
                    listOf(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_sqlmap_tables_no_results, db) })
                } else findings
            }
        }
    }

    /**
     * sqlmap --dump -D <db> -T <tabla> --batch: extracción REAL de datos — la acción más
     * sensible de todo el módulo (puede exponer datos personales/credenciales de la base
     * atacada). Requiere un diálogo de advertencia explícito antes de correr, a diferencia de
     * --dbs/--tables (solo metadata). Timeout más alto (150s) porque el dump de una tabla con
     * muchas filas tarda más que listar nombres.
     */
    private fun promptSqlmapDump() {
        promptSqlmapDbTarget(getString(com.termux.R.string.ciberseguridad_sqlmap_dump_dialog_title), askTable = true) { target, db, table ->
            if (db.isEmpty() || table.isEmpty()) { toast(getString(com.termux.R.string.ciberseguridad_sqlmap_db_table_empty)); return@promptSqlmapDbTarget }
            AlertDialog.Builder(requireContext())
                .setTitle(getString(com.termux.R.string.ciberseguridad_sqlmap_dump_confirm_title, table))
                .setMessage(getString(com.termux.R.string.ciberseguridad_sqlmap_dump_confirm_message, db, table))
                .setPositiveButton(getString(com.termux.R.string.ciberseguridad_sqlmap_dump_extract_button)) { _, _ ->
                    runQuickScan(
                        getString(com.termux.R.string.ciberseguridad_sqlmap_dump_progress, db, table, target),
                        getString(com.termux.R.string.ciberseguridad_sqlmap_dump_result_title, db, table)
                    ) {
                        val (code, _, _) = ManagerNativeUtils.runShell("command -v sqlmap", 5)
                        if (code != 0) return@runQuickScan listOf(getString(com.termux.R.string.ciberseguridad_tool_not_installed, "sqlmap"))
                        val (_, stdout, stderr) = ManagerNativeUtils.runShell(
                            "sqlmap -u '$target' --batch -D '$db' -T '$table' --dump", 150
                        )
                        val findings = stdout.lines().map { it.trim() }.filter { it.startsWith("|") }
                        if (findings.isEmpty()) {
                            listOf(stderr.ifBlank { getString(com.termux.R.string.ciberseguridad_sqlmap_dump_no_results) })
                        } else findings
                    }
                }
                .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
                .show()
        }
    }

    // Bug real reportado por el usuario (2026-08-25, probando en su dispositivo): "ciberseguridad
    // completo con Kali Linux no se instala" — el botón "Instalar nivel Pro (Kali)" (cuando ya
    // hay un básico instalado) solo mostraba un toast pidiendo ir al listado de Módulos → "Cambiar
    // método" → Pro, pero ESE camino (reinstallModuleService() en BaseModuleFragment, variant=null
    // → ModuleController.resolveInstalledVariant()) re-resuelve la variante YA instalada
    // (básico), nunca deja elegir "pro-headless"/"pro-gui" — no había NINGÚN camino real en toda
    // la app para instalar Kali una vez que básico ya estaba instalado. Fix: mismo patrón que
    // showSilentInstallVariantDialog() (instalación fresca) pero solo con las 2 opciones Pro,
    // llamando installModuleInBackground(variant) directo — ciberseguridad.sh ya soporta esto
    // sin --force (línea ~186: el guard de "ya instalado, salir" está condicionado a "! $PRO",
    // así que pedir --variant pro-headless/pro-gui sobre un básico ya instalado SÍ sigue de
    // largo a los pasos de Kali, el script en sí ya estaba bien).
    private fun showProInstallDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(com.termux.R.string.ciberseguridad_pro_install_dialog_title))
            .setMessage(getString(com.termux.R.string.ciberseguridad_pro_install_dialog_message))
            .setItems(arrayOf(
                getString(com.termux.R.string.ciberseguridad_pro_headless_item),
                getString(com.termux.R.string.ciberseguridad_pro_gui_item)
            )) { _, which ->
                val variant = if (which == 1) "pro-gui" else "pro-headless"
                installModuleInBackground(variant) { ok ->
                    if (ok) {
                        toast(getString(com.termux.R.string.ciberseguridad_kali_installed_toast))
                        container.removeAllViews()
                        buildContent()
                    } else {
                        toast(getString(com.termux.R.string.ciberseguridad_install_failed_log))
                    }
                }
            }
            .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
            .show()
    }

    // Mismo patrón que N8nFragment.showSilentInstallVariantDialog() — ver comentario de
    // buildContent() más arriba para el pedido original (docs/humano/humano181.md).
    private fun showSilentInstallVariantDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(com.termux.R.string.ciberseguridad_silent_install_dialog_title))
            .setMessage(getString(com.termux.R.string.ciberseguridad_silent_install_dialog_message))
            .setItems(arrayOf(
                getString(com.termux.R.string.ciberseguridad_basic_item),
                getString(com.termux.R.string.ciberseguridad_pro_headless_item),
                getString(com.termux.R.string.ciberseguridad_pro_gui_item)
            )) { _, which ->
                val variant = when (which) {
                    1 -> "pro-headless"
                    2 -> "pro-gui"
                    else -> "basico"
                }
                installModuleInBackground(variant) { ok ->
                    if (ok) {
                        toast(getString(com.termux.R.string.ciberseguridad_installed_toast))
                        container.removeAllViews()
                        buildContent()
                    } else {
                        toast(getString(com.termux.R.string.ciberseguridad_install_failed_log))
                    }
                }
            }
            .setNegativeButton(getString(com.termux.R.string.ciberseguridad_cancel), null)
            .show()
    }

    companion object {
        private const val PREF_DISCLAIMER_ACCEPTED = "ciberseguridad_disclaimer_accepted"
    }
}
