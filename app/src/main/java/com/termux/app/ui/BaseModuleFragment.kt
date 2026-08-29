package com.termux.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.HORIZONTAL
import android.widget.LinearLayout.VERTICAL
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.termux.R
import java.io.File
import com.termux.app.util.kairosThemeColor

abstract class BaseModuleFragment : Fragment() {

    protected lateinit var container: LinearLayout

    abstract fun getModuleId(): String
    abstract fun getModuleName(): String
    abstract fun buildContent()

    /**
     * Flecha "←" del header — por defecto true (el 99% de los usos son detalle de módulo
     * abierto vía ModuleDetailNavigator/replace()+addToBackStack(), ver navegación en
     * ModulesFragment.kt). EntornoFragment la desactiva: desde 2026-08-25 (ver
     * docs/mini-pc/MINIPC_TAB_2026-08-25.md) también vive como tab raíz del BottomNavigationView
     * (TermuxActivity.mEntornoFragment, agregado directo al fragment_container junto a los otros
     * ~11 tabs, nunca vía addToBackStack) — ahí popBackStack() ya es un no-op inofensivo (el
     * backstack siempre queda vacío al llegar a un tab, ver switchFragment()), pero mostrar una
     * flecha "atrás" que no navega a ningún lado es una suposición rota heredada de que este
     * Fragment SOLO se abre como detalle de módulo — exactamente el caso que la tarea pidió
     * confirmar antes de promover Entorno a tab.
     */
    protected open val showBackButton: Boolean = true

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_module_detail, c, false)
    }

    private lateinit var fabSlot: ViewGroup

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.content_container)
        fabSlot = view.findViewById(R.id.fab_slot)
        addHeader()
        buildContent()
    }

    /**
     * FAB real, flotante sobre el `ScrollView` (2026-08-23, ver docs/humano209.md — antes
     * `fragment_module_detail.xml` era un `ScrollView` raíz sin lugar para superponer nada, así
     * que la acción principal de una pantalla terminaba siendo "un botón más" arriba de la
     * lista; ahora hay un `fab_slot` real). Usar para LA acción principal de la pantalla (ej.
     * "abrir el chat" en Ollama/IA Local) — nunca para acciones secundarias, esas siguen siendo
     * botones/filas normales dentro de `container`.
     */
    protected fun showFab(icon: String, onClick: () -> Unit) {
        val ctx = requireContext()
        fabSlot.removeAllViews()
        fabSlot.addView(TextView(ctx).apply {
            text = icon
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val blue = ctx.kairosThemeColor(R.attr.kairosBlue)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(blue)
            }
            elevation = dp(6).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
            setOnClickListener { onClick() }
        })
    }

    /** Saca el FAB si la pantalla tiene más de un estado y no todos necesitan uno (ej. la lista
     * de proyectos de Stacks sí, el detalle de un proyecto no) — `showFab()` no se limpia sola
     * entre llamadas a `buildContent()` porque vive en `fabSlot`, un hermano de `container`, no
     * adentro de él. */
    protected fun hideFab() {
        if (::fabSlot.isInitialized) fabSlot.removeAllViews()
    }

    /**
     * Fila compacta de modelo/ítem (2026-08-23, ver docs/humano209.md — mockup aprobado por el
     * usuario, "no es quitar opciones es reorganizarlas [...] toca organizar bien y bonito").
     * Reemplaza el patrón viejo de una card entera de solo-lectura por ítem — un swatch
     * (ícono/color de estado), nombre + subtítulo en una sola fila, y contenido final opcional
     * (pill/botón chico) a la derecha. Pensado para listas de modelos (Ollama/IA Local) pero
     * genérico para cualquier lista de ítems similares (proyectos de Stacks, etc.).
     */
    protected fun modelRow(
        icon: String,
        iconBg: Int? = null,
        name: String,
        subtitle: String,
        trailing: (LinearLayout.() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            onClick?.let { setOnClickListener { it() } }
            addView(TextView(ctx).apply {
                text = icon
                textSize = 15f
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(iconBg ?: ctx.kairosThemeColor(R.attr.kairosBg3))
                }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).also { it.rightMargin = dp(11) }
            })
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = name
                    textSize = 13.5f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                })
                addView(TextView(ctx).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                    setPadding(0, dp(2), 0, 0)
                })
            })
            if (trailing != null) {
                val trailingContainer = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                trailingContainer.trailing()
                addView(trailingContainer)
            }
        }
    }

    /**
     * Header de estado compacto: 1 fila (punto de estado + texto) + contenido final opcional
     * (switch/botón) — reemplaza las cards de 4-5 líneas de solo-lectura (Proceso/Puerto/
     * Versión/RAM/Uptime) que ocupaban media pantalla para datos que en la práctica casi
     * siempre eran iguales o "—". El detalle completo sigue existiendo (ver "⋯"/pantalla de
     * Configuración de cada módulo), esto es solo el resumen de un vistazo.
     */
    protected fun compactStatusRow(
        statusText: String,
        isActive: Boolean,
        trailing: (LinearLayout.() -> Unit)? = null,
    ): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).also { it.rightMargin = dp(10) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(
                        if (isActive) ctx.kairosThemeColor(R.attr.kairosGreen)
                        else ctx.kairosThemeColor(R.attr.kairosText3)
                    )
                }
            })
            addView(TextView(ctx).apply {
                text = statusText
                textSize = 13.5f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            if (trailing != null) {
                val trailingContainer = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                trailingContainer.trailing()
                addView(trailingContainer)
            }
        }
    }

    private fun addHeader() {
        val ctx = requireContext()
        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        if (showBackButton) {
            val backBtn = TextView(ctx).apply {
                text = "\u2190"
                textSize = 20f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                setPadding(dp(8), dp(4), dp(12), dp(4))
                setOnClickListener { parentFragmentManager.popBackStack() }
            }
            header.addView(backBtn)
        }
        val name = TextView(ctx).apply {
            text = getModuleName()
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also {
                it.gravity = android.view.Gravity.CENTER_VERTICAL
            }
        }
        header.addView(name)
        container.addView(header)
    }

    /**
     * [parent] agregado 2026-08-26 (extracción del helper de pestañas, ver comentario de
     * [setupTabs] más abajo) — default `container` preserva el comportamiento de siempre para
     * los ~50 usos existentes de `addCard(title) { }`. Se pasa explícito cuando la card va
     * DENTRO del contenido de una pestaña (un `LinearLayout` propio, no directo en `container`)
     * — mismo motivo que `CiberseguridadFragment.tabCard()` (copia local, previa a esta
     * generalización) necesitaba un `parent` explícito.
     */
    protected fun addCard(title: String? = null, parent: LinearLayout = container, block: LinearLayout.() -> Unit) {
        val ctx = requireContext()
        if (title != null) {
            parent.addView(TextView(ctx).apply {
                text = title
                textSize = 10f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f
                setPadding(dp(4), dp(16), dp(4), dp(8))
            })
        }
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            radius = resources.getDimension(R.dimen.kairos_stroke_default)
            strokeColor = ctx.kairosThemeColor(R.attr.kairosBorder)
            strokeWidth = dp(1)
            cardElevation = 0f
            setContentPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.bottomMargin = dp(8)
            }
        }
        val inner = LinearLayout(ctx).apply {
            orientation = VERTICAL
            block()
        }
        card.addView(inner)
        parent.addView(card)
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Pestañas reusables (TabLayout + reconstruir contenido por pestaña) — extraído
    // 2026-08-26 (pedido explícito del usuario) tras confirmar que RemoteFragment.kt,
    // CiberseguridadFragment.kt y EntornoFragment.kt implementaron el mismo patrón visual
    // por separado el mismo día (TabLayout de categorías + grid/cards de contenido), cada uno
    // reimplementando su propio renderTab()/section()/tabCard() a mano. De los 3 patrones
    // reales:
    //  - EntornoFragment.buildTabsSection()/renderTab(): un solo `tabContentContainer` que se
    //    limpia (removeAllViews()) y reconstruye por completo en cada cambio de pestaña.
    //  - CiberseguridadFragment.buildToolsTabs()/renderToolsTab(): mismo mecanismo, más
    //    tabCard() como copia local de addCard() que agrega a un `parent` explícito en vez de
    //    a `container` (necesario porque cada pestaña reconstruye dentro de un contenedor
    //    propio, no directo en `container`).
    //  - RemoteFragment.section()/renderActiveTab(): NO reconstruye — construye las 5 pestañas
    //    una sola vez agregando directo a `container` (para no tener que adaptar ~20 años de
    //    código con estado real: polling, switches, seguridad SSH) y registra qué vistas de
    //    `container` pertenecen a cada pestaña, alternando VISIBLE/GONE por índice.
    // Se elige el patrón Entorno/Ciberseguridad (limpiar+reconstruir) como base de este helper
    // por ser el más simple de los 3 — no requiere trackear una lista de vistas por pestaña, cada
    // pestaña simplemente recibe un LinearLayout vacío para poblar de nuevo — y es el que ya
    // usaron 2 de los 3 Fragments reales de forma independiente. El patrón de RemoteFragment
    // (visibilidad sobre `container` sin reconstruir) queda documentado acá por si algún módulo
    // futuro con mucho estado en vivo (polling, switches) lo necesita en vez de este helper —
    // no se generalizó porque agrega la complejidad de trackear listas de vistas por pestaña sin
    // beneficio real para el caso común.
    //
    // Uso:
    //   setupTabs(listOf("Motores", "Backup"))
    //       .tab(0) { content -> renderMotoresTab(content) }
    //       .tab(1) { content -> renderBackupTab(content) }
    //       .build()
    // ────────────────────────────────────────────────────────────────────────────

    /** Controlador devuelto por [TabbedSectionsBuilder.build] — permite re-renderizar la pestaña
     *  activa (ej. tras refrescar estado, mismo criterio que EntornoFragment.refreshStatus()→
     *  renderTab(activeTabIndex)) sin perder de vista qué pestaña está seleccionada. */
    protected inner class TabbedSections internal constructor(
        private val content: LinearLayout,
        private val blocks: List<(LinearLayout) -> Unit>
    ) {
        var activeIndex: Int = 0
            private set

        /** Limpia el contenedor de contenido y vuelve a correr el block de la pestaña [index]. */
        fun render(index: Int) {
            if (index !in blocks.indices) return
            activeIndex = index
            content.removeAllViews()
            blocks[index](content)
        }

        /** Re-renderiza la pestaña actualmente activa — atajo para refrescos de estado. */
        fun renderActive() = render(activeIndex)
    }

    /** Builder devuelto por [setupTabs] — registrar el contenido de cada pestaña con [tab] antes
     *  de llamar [build]. Índices sin `.tab(i) { }` registrado quedan como pestañas vacías (no
     *  es un error — puede ser intencional para un placeholder "próximamente"). */
    protected inner class TabbedSectionsBuilder internal constructor(
        private val tabNames: List<String>,
        private val parent: LinearLayout,
        private val tabMode: Int
    ) {
        private val blocks = arrayOfNulls<(LinearLayout) -> Unit>(tabNames.size)

        /** Registra el contenido de la pestaña [index] — [block] recibe el LinearLayout vacío a
         *  poblar (con addView/tileGrid/tabCard-style helpers propios del caller), reconstruido
         *  desde cero cada vez que esa pestaña se selecciona. */
        fun tab(index: Int, block: (LinearLayout) -> Unit): TabbedSectionsBuilder {
            require(index in tabNames.indices) {
                "Tab index $index fuera de rango (0..${tabNames.size - 1}, pestañas: $tabNames)"
            }
            blocks[index] = block
            return this
        }

        /** Agrega el TabLayout + el contenedor de contenido a [parent] y renderiza [initialIndex]. */
        fun build(initialIndex: Int = 0): TabbedSections {
            val ctx = requireContext()
            val tabLayout = TabLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(0, dp(8), 0, dp(4))
                }
                tabMode = this@TabbedSectionsBuilder.tabMode
                setSelectedTabIndicatorColor(ctx.kairosThemeColor(R.attr.kairosGreen))
                setTabTextColors(ctx.kairosThemeColor(R.attr.kairosText3), ctx.kairosThemeColor(R.attr.kairosText))
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg2))
            }
            tabNames.forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
            parent.addView(tabLayout)

            val content = LinearLayout(ctx).apply { orientation = VERTICAL }
            parent.addView(content)

            val sections = TabbedSections(content, blocks.map { it ?: {} })
            tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { sections.render(tab.position) }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
            val start = initialIndex.coerceIn(0, tabNames.size - 1)
            tabLayout.getTabAt(start)?.select()
            sections.render(start)
            return sections
        }
    }

    /**
     * Punto de entrada del helper de pestañas (ver comentario arriba) — [parent] default
     * `container` (el ScrollView único del Fragment); pasar un LinearLayout propio cuando el
     * TabLayout no va directo en `container` (ej. dentro de una card). [tabMode] default
     * MODE_SCROLLABLE (EntornoFragment usó esto para 5 pestañas); usar TabLayout.MODE_FIXED
     * para 2-4 pestañas cortas (CiberseguridadFragment).
     */
    protected fun setupTabs(
        tabNames: List<String>,
        parent: LinearLayout = container,
        tabMode: Int = TabLayout.MODE_SCROLLABLE
    ): TabbedSectionsBuilder = TabbedSectionsBuilder(tabNames, parent, tabMode)

    protected fun infoRow(key: String, value: String, valueColor: Int? = null): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(ctx).apply {
                text = key
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
            })
            addView(TextView(ctx).apply {
                text = value
                textSize = 12f
                setTypeface(android.graphics.Typeface.MONOSPACE)
                gravity = android.view.Gravity.END
                setTextColor(valueColor ?: ctx.kairosThemeColor(R.attr.kairosText))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f)
            })
        }
    }

    // Refactor (auditoría 2026-08-13, ver docs/viejo/AUDITORIA_CODIGO_2026-08-13.md
    // §3.5): infoRow() solo devuelve el View contenedor — esta extension function toma el
    // 2do hijo (el TextView del valor) para poder actualizarlo después de leer el estado real
    // en segundo plano. Antes reimplementada idéntica (mismo comentario incluido) en 7
    // fragments distintos (Hermes/N8n-vía-valueRow/Entorno/OpenClaw/Python/LlamaServer/Db/
    // OpenCode-vía-valueRow/Expo/HermesGateway) — al ser un member extension function de esta
    // clase, queda disponible en todos los subtipos sin volver a declararla.
    protected fun View.valueTextView(): TextView? =
        (this as? ViewGroup)?.let { it.getChildAt(1) as? TextView }

    /**
     * infoRow(key, initialValue) + valueTextView() en un solo paso — devuelve tanto la fila
     * (para addView) como el TextView del valor (para actualizarlo después). Antes
     * reimplementado idéntico en N8nFragment/OpenCodeFragment/OpenClawFragment.
     */
    protected fun valueRow(key: String, initialValue: String): Pair<View, TextView> {
        val row = infoRow(key, initialValue)
        return row to row.valueTextView()!!
    }

    protected fun actionButton(
        text: String,
        style: ButtonStyle = ButtonStyle.GHOST,
        onClick: () -> Unit
    ) {
        container.addView(createActionButton(text, style, onClick))
    }

    protected fun createActionButton(
        text: String,
        style: ButtonStyle = ButtonStyle.GHOST,
        onClick: () -> Unit
    ): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            when (style) {
                ButtonStyle.PRIMARY -> {
                    // Dirección acordada en docs/humano/humano191.md (mockup /design, sección "Mockup
                    // de componentes"): el botón primario "escandilaba" con relleno sólido verde
                    // saturado (#22C55E) + texto negro. Reemplazado por relleno azul tenue
                    // (14% opacidad de kairosBlue) + borde, con ripple/elevación reales — el
                    // verde neón queda reservado como acento fino (pill(), switches), nunca como
                    // bloque sólido de un botón grande.
                    val blue = ctx.kairosThemeColor(R.attr.kairosBlue)
                    val fill = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(13).toFloat()
                        setColor(Color.argb(36, Color.red(blue), Color.green(blue), Color.blue(blue)))
                        setStroke(dp(1), Color.argb(110, Color.red(blue), Color.green(blue), Color.blue(blue)))
                    }
                    val mask = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(13).toFloat()
                        setColor(Color.WHITE)
                    }
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Color.argb(60, Color.red(blue), Color.green(blue), Color.blue(blue))),
                        fill,
                        mask
                    )
                    elevation = dp(1).toFloat()
                    setTextColor(blue)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                ButtonStyle.DANGER -> {
                    setBackgroundColor(Color.parseColor("#26EF4444"))
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosRed))
                }
                ButtonStyle.GHOST -> {
                    setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                }
            }
        }
    }

    protected fun divider() {
        container.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).also {
                it.leftMargin = dp(14)
                it.rightMargin = dp(14)
            }
            setBackgroundColor(requireContext().kairosThemeColor(R.attr.kairosBorder))
        })
    }

    protected fun pill(text: String, isActive: Boolean): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3))
            if (isActive) {
                setBackgroundColor(Color.parseColor("#1A22C55E"))
            } else {
                setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            }
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).also {
                    it.rightMargin = dp(5)
                }
                setBackgroundColor(
                    if (isActive) ctx.kairosThemeColor(R.attr.kairosGreen)
                    else ctx.kairosThemeColor(R.attr.kairosText3)
                )
            })
            addView(TextView(ctx).apply {
                this.text = text
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    if (isActive) ctx.kairosThemeColor(R.attr.kairosGreen)
                    else ctx.kairosThemeColor(R.attr.kairosText3)
                )
            })
        }
    }

    /**
     * Fila "dropdown + switch bloqueado" — pedido explícito del usuario (2026-08-22, ver
     * docs/humano/humano192.md): reemplaza el antipatrón de N botones para una sola decisión
     * excluyente (ej. OpenCode tenía 2 botones de puerto + un switch aparte; Entornos de
     * Prueba tenía 3 botones de destino + Iniciar/Detener separados). Un solo dropdown para
     * elegir la opción + un switch que la bloquea mientras está encendido — no se puede
     * cambiar la opción sin apagar primero. El dropdown es un [android.widget.PopupMenu] real
     * (no una animación falsa), el switch es el [androidx.appcompat.widget.SwitchCompat]
     * estándar ya usado en `item_module_row.xml`.
     *
     * Devuelve un [DropdownSwitchRow] para que el caller pueda sincronizar el estado real
     * (ej. tras un poll de red que confirma si el servicio sigue corriendo) sin re-disparar
     * `onSwitchToggled` — `setSwitchState()` desconecta el listener mientras actualiza.
     */
    protected inner class DropdownSwitchRow(
        val root: View,
        private val dropdownLabel: TextView,
        private val switchView: androidx.appcompat.widget.SwitchCompat,
        private val options: List<String>,
        private var selectedIndex: Int,
        private val onSwitchToggled: (Boolean, Int) -> Unit
    ) {
        fun selectedOptionIndex(): Int = selectedIndex

        fun setSelectedIndex(index: Int) {
            selectedIndex = index
            dropdownLabel.text = options[index]
        }

        /** Actualiza el switch SIN disparar onSwitchToggled — para sincronizar con estado real. */
        fun setSwitchState(on: Boolean) {
            switchView.setOnCheckedChangeListener(null)
            switchView.isChecked = on
            dropdownLabel.isEnabled = !on
            dropdownLabel.alpha = if (on) 0.45f else 1f
            switchView.setOnCheckedChangeListener { _, checked -> onSwitchToggled(checked, selectedIndex) }
        }
    }

    protected fun dropdownSwitchRow(
        label: String,
        options: List<String>,
        initialIndex: Int = 0,
        initialOn: Boolean = false,
        onOptionChosen: (Int) -> Unit,
        onSwitchToggled: (Boolean, Int) -> Unit
    ): DropdownSwitchRow {
        val ctx = requireContext()
        var currentIndex = initialIndex
        val dropdownLabel = TextView(ctx).apply {
            text = options[initialIndex]
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val switchView = androidx.appcompat.widget.SwitchCompat(ctx).apply {
            isChecked = initialOn
        }
        dropdownLabel.setOnClickListener {
            if (switchView.isChecked) return@setOnClickListener
            val popup = android.widget.PopupMenu(ctx, dropdownLabel)
            options.forEachIndexed { i, opt -> popup.menu.add(0, i, i, opt) }
            popup.setOnMenuItemClickListener { item ->
                currentIndex = item.itemId
                dropdownLabel.text = options[currentIndex]
                onOptionChosen(currentIndex)
                true
            }
            popup.show()
        }
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(dropdownLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                it.marginEnd = dp(10)
            })
            addView(switchView)
        }
        val controller = DropdownSwitchRow(row, dropdownLabel, switchView, options, initialIndex, onSwitchToggled)
        switchView.setOnCheckedChangeListener { _, checked ->
            dropdownLabel.isEnabled = !checked
            dropdownLabel.alpha = if (checked) 0.45f else 1f
            onSwitchToggled(checked, currentIndex)
        }
        return controller
    }

    /**
     * Fila "label + switch" — versión sin dropdown de [dropdownSwitchRow], para pares de
     * botones Iniciar/Detener sueltos que no tienen variantes que elegir (ej. servicios que
     * solo se encienden/apagan: SSH, Cloudflare tunnel, gateway de OpenClaw). Mismo criterio
     * "color solo para estado real" del pedido del usuario — el switch no lleva relleno de
     * color propio, solo la pista se tiñe muy tenue cuando está ON (ver `switch_track_color`).
     */
    protected inner class SwitchRow(val root: View, private val switchView: androidx.appcompat.widget.SwitchCompat) {
        fun setSwitchState(on: Boolean) {
            switchView.setOnCheckedChangeListener(null)
            switchView.isChecked = on
            switchView.setOnCheckedChangeListener { _, checked -> onToggledRef(checked) }
        }
        internal lateinit var onToggledRef: (Boolean) -> Unit
    }

    protected fun switchRow(label: String, initialOn: Boolean = false, onToggled: (Boolean) -> Unit): SwitchRow {
        val ctx = requireContext()
        val switchView = androidx.appcompat.widget.SwitchCompat(ctx).apply { isChecked = initialOn }
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(switchView)
        }
        val controller = SwitchRow(row, switchView)
        controller.onToggledRef = onToggled
        switchView.setOnCheckedChangeListener { _, checked -> onToggled(checked) }
        return controller
    }

    /**
     * Fila "solo dropdown" — versión sin switch de [dropdownSwitchRow], para elegir entre 3+
     * opciones mutuamente excluyentes que NO son un encendido/apagado (ej. sqlmap tiene 4
     * acciones distintas con targets/params propios, no un simple toggle — ver docs/humano/humano194.md
     * y docs/humano/humano195.md, diferido en la ronda anterior a falta de este componente). El botón
     * que dispara la acción elegida sigue siendo un botón normal aparte, este componente solo
     * resuelve la selección de modo.
     */
    protected inner class DropdownRow(
        val root: View,
        private val dropdownLabel: TextView,
        private val options: List<String>,
        private var selectedIndex: Int
    ) {
        fun selectedOptionIndex(): Int = selectedIndex

        fun setSelectedIndex(index: Int) {
            selectedIndex = index
            dropdownLabel.text = options[index]
        }
    }

    protected fun dropdownRow(
        label: String,
        options: List<String>,
        initialIndex: Int = 0,
        onOptionChosen: (Int) -> Unit
    ): DropdownRow {
        val ctx = requireContext()
        val dropdownLabel = TextView(ctx).apply {
            text = options[initialIndex]
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
        }
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(dropdownLabel)
        }
        // selectedIndex se mantiene dentro de controller (no en una var suelta del closure) para
        // que setSelectedIndex() y el click listener siempre lean/escriban el mismo estado.
        val controller = DropdownRow(row, dropdownLabel, options, initialIndex)
        dropdownLabel.setOnClickListener {
            val popup = android.widget.PopupMenu(ctx, dropdownLabel)
            options.forEachIndexed { i, opt -> popup.menu.add(0, i, i, opt) }
            popup.setOnMenuItemClickListener { item ->
                controller.setSelectedIndex(item.itemId)
                onOptionChosen(item.itemId)
                true
            }
            popup.show()
        }
        return controller
    }

    protected fun showNotInstalled(moduleName: String, onInstallSilently: (() -> Unit)? = null) {
        val ctx = requireContext()
        container.removeAllViews()
        container.addView(TextView(ctx).apply {
            text = getString(R.string.base_module_not_installed_title)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
            setPadding(dp(24), dp(48), dp(24), dp(16))
        })
        container.addView(TextView(ctx).apply {
            text = getString(R.string.base_module_not_installed_body, moduleName)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
            setPadding(dp(24), 0, dp(24), dp(24))
        })
        // Instalación silenciosa en segundo plano (pedido 2026-08-13): el usuario sigue
        // navegando la app mientras el módulo se instala internamente (mismo Thread de
        // ModuleController.installModule, sin BottomSheet que bloquee). Cada módulo decide
        // qué variante pasar (n8n: udocker/proot-distro; hermes: ninguna).
        //
        // Feedback visual de "instalando" (pedido explícito del usuario, auditoría de UX de
        // instalación 2026-08-29): antes el botón quedaba tapeable durante TODA la instalación,
        // sin ningún indicio de que tocarlo de nuevo era un no-op — este era el otro lado del
        // mismo bug que el guard de installModuleInBackground()/reinstallModuleService() de
        // arriba (notificación falsa de "falló"): acá el problema es puramente de UI, no de
        // lógica. moduleId ya refleja el estado real de ModuleController.isInstalling() al
        // construir la pantalla (cubre navegar-y-volver mientras instala en segundo plano) y al
        // tocar el botón (cubre el primer tap real) — pollInstallingButtonState() lo re-habilita
        // solo cuando la instalación realmente termina, sin depender de que cada uno de los ~10
        // callers de showNotInstalled() recuerde re-renderizar la pantalla en su onDone.
        if (onInstallSilently != null) {
            val moduleId = getModuleId()
            val alreadyInstalling = com.termux.app.ModuleController.isInstalling(moduleId)
            val installButton = createActionButton(
                if (alreadyInstalling) getString(R.string.base_module_install_button_installing) else getString(R.string.base_module_install_background),
                ButtonStyle.PRIMARY
            ) {}
            installButton.isEnabled = !alreadyInstalling
            installButton.alpha = if (alreadyInstalling) 0.5f else 1f
            installButton.setOnClickListener {
                if (com.termux.app.ModuleController.isInstalling(moduleId)) return@setOnClickListener
                installButton.isEnabled = false
                installButton.alpha = 0.5f
                (installButton as? TextView)?.text = getString(R.string.base_module_install_button_installing)
                onInstallSilently()
                pollInstallingButtonState(installButton, moduleId)
            }
            container.addView(installButton)
            if (alreadyInstalling) {
                pollInstallingButtonState(installButton, moduleId)
            }
        }
        container.addView(createActionButton(getString(R.string.base_module_back), ButtonStyle.GHOST) {
            parentFragmentManager.popBackStack()
        })
    }

    /**
     * Re-habilita [button] (texto + estado) apenas [moduleId] deja de estar instalando —
     * ver comentario de [showNotInstalled]. Guard de Fragment-adjunto (`isAdded`) igual que el
     * resto del proyecto (`.claude/rules/kotlin-kairos-android-patterns.md`): si el usuario
     * navega a otra pantalla mientras esto sigue reintentando, la cadena de `postDelayed()` se
     * corta acá en vez de seguir agendando callbacks contra un Fragment ya desadjuntado.
     */
    private fun pollInstallingButtonState(button: View, moduleId: String) {
        if (!isAdded) return
        if (!com.termux.app.ModuleController.isInstalling(moduleId)) {
            button.isEnabled = true
            button.alpha = 1f
            (button as? TextView)?.text = getString(R.string.base_module_install_background)
            return
        }
        button.postDelayed({
            if (isAdded) pollInstallingButtonState(button, moduleId)
        }, 1500)
    }

    // open: PythonFragment lo sobreescribe para además exigir el binario real con AND (ver
    // isTermuxBinaryAvailable) — es el módulo con más evidencia real de tener el registry
    // desincronizado del dispositivo (docs/humano*.md 2026-07-31), un caso más estricto que
    // el OR de acá abajo (registry dice instalado pero el binario está roto).
    //
    // Fix 2026-08-13 (ver docs/humano/humano118.md — bug de clase conocida: el registry
    // puede desincronizarse del dispositivo real): antes esto solo leía el registry directo,
    // sin ningún fallback — cualquier Fragment que no overrideara este método (la mayoría)
    // no tenía forma de detectar un módulo instalado a mano en terminal (bypaseando la app).
    // Delegar en ModuleInstalled.isInstalled() (registry O binario real, ambos cacheados)
    // hace que TODOS los Fragments hereden el mismo fallback sin tener que reimplementarlo
    // uno por uno — antes solo lo tenían Python/Ollama (override propio) y ModulesFragment
    // (ya llamaba a ModuleInstalled directo).
    //
    // Fix 2026-08-15 (ModuleDoctor + verificación en vivo): se sube a isInstalledRobust()
    // (registry O binario O estrategia en vivo de LIVE_FALLBACK con cache de 10s) para que
    // el estado de cada módulo refleje el filesystem real — el registry puede quedar como
    // installed=true sin que el binario exista (ver humano118) y viceversa. El poll de
    // ModulesFragment sigue usando isInstalled() (más barato); acá la robustez vale la pena
    // porque cada visita al detalle es puntual, no periódica.
    protected open fun isModuleInstalled(): Boolean {
        return com.termux.app.data.ModuleInstalled.isInstalledRobust(requireContext(), getModuleId())
    }

    /**
     * Diagnóstico de salud de TODOS los módulos (ModuleDoctor) y muestra el resumen en un
     * Toast: X verificados · Y OK · Z no instalados · N rotos. Escribe el reporte completo
     * en ~/kairos_logs/module_doctor.json y emite una notificación local de ERROR si hay
     * módulos rotos (ver ModuleDoctor). Corre en un background thread y vuelve a la UI con
     * el mismo guard de Fragment-adjunto que [startModuleService] — los 30+ módulos
     * verificados con bash en vivo pueden tardar unos segundos.
     */
    protected fun runModuleDoctor() {
        if (!isAdded) return
        val ctx = requireContext()
        Thread {
            val summary = com.termux.app.util.ModuleDoctor.runDiagnosticsForAll(ctx)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread { if (isAdded) toast(summary) }
        }.start()
    }

    protected fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * Abre el overlay de terminal y corre `command` en una sesión nueva. `sessionName`
     * (opcional) nombra la sesión en el drawer — por defecto usa getModuleName() para que
     * cada CLI (Claude/Codex/OpenCode/etc.) se distinga en la lista en vez de mostrar todas
     * un número genérico.
     */
    protected fun launchTerminalCommand(command: String, sessionName: String? = getModuleName()) {
        (activity as? com.termux.app.TermuxActivity)?.openTerminalWithCommand(command, sessionName)
    }

    /**
     * true si hay una sesión de terminal TUI (minimizada con el botón "Minimizar" o visible)
     * abierta para este módulo — ver TermuxActivity.isSessionActive(). Usa el mismo
     * sessionName que launchTerminalCommand() por defecto (getModuleName()), así que refleja
     * exactamente la sesión que abre "TUI en terminal" en cada módulo.
     */
    protected fun isTerminalSessionActive(sessionName: String = getModuleName()): Boolean =
        (activity as? com.termux.app.TermuxActivity)?.isSessionActive(sessionName) ?: false

    /**
     * Pill de estado para el submenú de cada módulo con CLI (Claude/Codex/OpenCode/
     * Antigravity/Hermes/OpenClaw) — reusa pill() para mostrar si el TUI sigue corriendo en
     * segundo plano tras minimizarlo, sin que el usuario tenga que reabrir la terminal para
     * comprobarlo. Pedido explícito del usuario: "en el sub menu de cada uno puede salir si
     * esta corriendo la terminal con el cli".
     */
    protected fun terminalStatusPill(sessionName: String = getModuleName()): View {
        val active = isTerminalSessionActive(sessionName)
        return pill(if (active) getString(R.string.base_module_terminal_tui_background) else getString(R.string.base_module_terminal_tui_not_started), active)
    }

    /**
     * Inicia el módulo (script de start real, ej. ollama_start.sh) y refresca la card de
     * estado. [onDone] SIEMPRE llega en el hilo principal y con el Fragment confirmado
     * adjunto — antes cada Fragment (N8nFragment, OllamaFragment, etc.) tenía que acordarse
     * de guardar ese guard él mismo en su propio callback, y no todos lo hacían. Bug real
     * confirmado con stacktrace (ver docs/humano/humano57.md):
     * `IllegalStateException: Fragment N8nFragment... not attached to an activity` — el
     * usuario tocaba "Iniciar n8n" (que puede tardar bastante en proot) y navegaba a otra
     * pantalla antes de que el callback llegara; `requireActivity()` reventaba porque el
     * Fragment ya no estaba adjunto. Centralizar el guard acá corrige ese crash en TODOS los
     * módulos que usan este helper de una sola vez, no solo en n8n.
     */
    protected fun startModuleService(onDone: (Boolean, String) -> Unit) {
        val appContext = requireContext().applicationContext
        com.termux.app.ModuleController.startModule(getModuleId(), appContext) { ok, output ->
            if (!isAdded) return@startModule
            requireActivity().runOnUiThread { if (isAdded) onDone(ok, output) }
        }
    }

    /**
     * [startModuleService] con polling corto (2-3s) mientras se espera el callback final —
     * hallazgo de UX homelab pendiente de adoptar (2026-08-22, ver docs/humano/humano194.md/
     * humano201.md, patrón Umbrel): un arranque que puede tardar 5-60s (Ollama cargando el
     * modelo, servicios con health-check propio) se sentía "colgado" mientras la UI solo
     * esperaba el único callback final, sin ninguna señal intermedia de que sigue trabajando.
     * [onPoll] corre cada [intervalMs] mientras la instalación/arranque sigue en curso — un
     * Fragment lo usa típicamente para refrescar su pill/switch de estado real (ver
     * OllamaFragment.refreshRunningState() como piloto), no para simular progreso falso.
     * El polling se detiene solo (nunca sigue corriendo en background) apenas llega [onDone] o
     * el Fragment se desadjunta — mismo criterio "nunca corre nada solo sin que el usuario lo
     * pida" que el resto de paneles nativos del proyecto.
     */
    protected fun startModuleServiceWithPolling(
        intervalMs: Long = 2500,
        onPoll: () -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var polling = true
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!polling || !isAdded) return
                onPoll()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(pollRunnable, intervalMs)
        startModuleService { ok, output ->
            polling = false
            handler.removeCallbacks(pollRunnable)
            onDone(ok, output)
        }
    }

    /**
     * Detiene el módulo (script de stop real) y refresca la card de estado. También
     * cierra, si existe, la sesión de terminal TUI del mismo nombre (ver
     * TermuxActivity.stopSessionByName) — bug real reportado: "detener servidor" solo
     * mataba el servidor web (tmux), la sesión TUI interactiva seguía corriendo.
     * Mismo guard de Fragment-adjunto que [startModuleService] — ver docstring de ahí.
     */
    protected fun stopModuleService(onDone: (Boolean) -> Unit) {
        (activity as? com.termux.app.TermuxActivity)?.stopSessionByName(getModuleName())
        val appContext = requireContext().applicationContext
        com.termux.app.ModuleController.stopModule(getModuleId(), appContext) { ok ->
            if (!isAdded) return@stopModule
            requireActivity().runOnUiThread { if (isAdded) onDone(ok) }
        }
    }

    /**
     * Reinstala/actualiza el módulo (`ModuleController.installModule()`, sin variante
     * específica, con `--force`). Mismo guard de Fragment-adjunto que
     * [startModuleService]/[stopModuleService] — bug real confirmado (ver
     * docs/humano/humano63.md, auditoría de arquitectura central): 7 módulos (Antigravity,
     * Engram, Codex, Claude, Hermes, OpenCode, OpenClaw) llamaban a
     * `ModuleController.installModule()` DIRECTO desde su botón "Reinstalar/Actualizar", sin
     * pasar por ningún helper — mismo crash real ya corregido acá para start/stop
     * (`IllegalStateException: Fragment ... not attached to an activity`), solo que en la ruta
     * de reinstalación, que puede tardar minutos (n8n ~5min, OpenClaw ~2min).
     *
     * force=true (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): sin esto, este botón
     * era casi siempre un no-op — todos los scripts de instalación chequean "command -v X && !
     * $FORCE" y salen temprano si el binario ya existe, así que "Reinstalar/Actualizar" nunca
     * bajaba una versión nueva de nada, solo lo aparentaba (el usuario reportó "en ningún
     * módulo sale la opción de actualizar" — técnicamente existía el botón, pero no
     * funcionaba).
     */
    protected fun reinstallModuleService(onDone: (Boolean) -> Unit) {
        val id = getModuleId()
        // Chequeo ANTES de llamar a installModule() (mismo mecanismo que
        // installModuleInBackground() de más abajo — ver su comentario y el de
        // ModuleController.isInstalling()): evita que un doble-tap en "Actualizar" mientras el
        // módulo ya está instalando dispare una notificación falsa de "instalación falló".
        if (com.termux.app.ModuleController.isInstalling(id)) {
            toast(getString(R.string.base_module_already_installing, getModuleName()))
            onDone(false)
            return
        }
        com.termux.app.ModuleController.installModule(id, requireContext(), null, true, {}) { ok ->
            if (!isAdded) return@installModule
            requireActivity().runOnUiThread { if (isAdded) onDone(ok) }
        }
    }

    /** Alias semántico de [reinstallModuleService] para módulos que no tenían ningún botón de
     * reinstalación/actualización todavía (Ollama, n8n, Python, Expo, Remote) — mismo mecanismo
     * real (`--force`), solo un nombre más claro para un botón nuevo "🔄 Actualizar". */
    protected fun updateModuleService(onDone: (Boolean) -> Unit) = reinstallModuleService(onDone)

    /**
     * Instalación en segundo plano SIN bloquear la UI (pedido 2026-08-13, ver humano101):
     * el usuario toca "Instalar en segundo plano" en el fragment y sigue navegando mientras
     * el módulo se instala internamente. Mismo mecanismo real que el BottomSheet
     * (ModuleController.installModule → Thread → bash <script> --silent), solo que sin
     * forzar y sin esperar a que el proceso termine para dejar la pantalla.
     *
     * [variant] se pasa como `--variant` (n8n: udocker/proot-distro). [onDone] SIEMPRE
     * llega en el hilo principal y con el Fragment confirmado adjunto (mismo guard que
     * [startModuleService] — ver su docstring).
     */
    protected fun installModuleInBackground(variant: String? = null, onDone: (Boolean) -> Unit) {
        val appContext = requireContext().applicationContext
        val id = getModuleId()
        // Guard anti-duplicados del lado UI (pedido explícito del usuario, auditoría de UX de
        // instalación 2026-08-29 — ver comentario de ModuleController.isInstalling()): antes
        // este chequeo no existía acá, así que un doble-tap en "Instalar" mientras el módulo ya
        // estaba instalando SÍ llegaba a installModule() — el guard interno (activeInstalls) lo
        // rechazaba, pero como el onProgress real que pasa este método es un lambda vacío ({}),
        // el mensaje "ya se está instalando" del guard se perdía en silencio y onComplete(false)
        // disparaba la misma notificación Android que un fallo real ("install_failed"),
        // engañando al usuario. Chequear acá evita llamar a installModule() del todo para el
        // caso de doble-tap — respuesta inmediata y honesta, sin notificación falsa.
        if (com.termux.app.ModuleController.isInstalling(id)) {
            toast(getString(R.string.base_module_already_installing, getModuleName()))
            onDone(false)
            return
        }
        // Límite de instalaciones simultáneas (pedido explícito del usuario, ver
        // InstallQueueManager/docs/arquitectura/COLA_INSTALACION_MODULOS.md): con 4+ módulos
        // instalando ahora mismo, installModule() encola esta en vez de arrancarla — antes de
        // este fix el usuario tocaba "Instalar en segundo plano" y no pasaba NADA visible hasta
        // que le tocaba turno, sin ningún aviso de que había entrado en cola. isAtCapacity() se
        // chequea ANTES de llamar a installModule() (mismo hilo, sin carrera real: el peor caso
        // es un falso "instalando" si otro módulo libera su cupo en el instante exacto entre
        // este chequeo y el submit() interno — inofensivo, solo cambia el texto del toast) para
        // no duplicar el aviso con el que dispara internamente InstallQueueManager.
        toast(
            if (com.termux.app.util.InstallQueueManager.isAtCapacity()) {
                com.termux.app.ModuleController.INSTALL_QUEUED_MESSAGE
            } else {
                getString(R.string.base_module_installing_background, getModuleName())
            }
        )
        com.termux.app.ModuleController.installModule(id, requireContext(), variant, false, {}) { ok ->
            // Aviso confiable de fin de instalación (2026-08-20, ver cita real en
            // docs/viejo/AUDITORIA_UX_INSTALACION_2026-08-19.md — "ademas cuando termine
            // debe avisar"): antes esto SOLO llamaba onDone(ok), que el guard de Fragment-adjunto
            // de más abajo descarta en silencio si el usuario ya navegó a otra pantalla — el
            // caso de fallo (a diferencia de éxito, que sí cubre notify_event del lado bash en
            // ~52 modulos/*.sh) no tenía NINGÚN aviso proactivo. Notificación Android real,
            // independiente de isAdded, con el mismo mecanismo que
            // BottomSheetInstalacion.startSilentInstall().
            com.termux.app.util.ModuleEventBridge.notifyDirect(
                appContext, id, if (ok) "install_done" else "install_failed",
                if (ok) "" else appContext.getString(R.string.base_module_install_failed_log_hint, id)
            )
            if (!isAdded) return@installModule
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                onDone(ok)
            }
        }
    }

    /**
     * Helper genérico para reemplazar el boilerplate `Thread {} + isAdded + runOnUiThread`
     * repetido en todo el proyecto (confirmado: 526 ocurrencias en 59 archivos, ver
     * `docs/arquitectura/PROPUESTA_REFACTOR_THREADING_2026-08-25.md`, Opción A — recomendada
     * ahí en vez de Kotlin Coroutines porque el proyecto no tiene esa dependencia hoy y migrar
     * 526 call-sites sin compilador local disponible es un riesgo desproporcionado). [work]
     * corre en background; [onResult] corre en el hilo principal SOLO si el Fragment sigue
     * adjunto tanto antes como después de saltar al hilo principal — mismo doble guard `isAdded`
     * que el patrón manual ya usaba en todo el codebase (ver
     * `.claude/rules/kotlin-kairos-android-patterns.md`).
     *
     * Agregado 2026-08-25 SOLO como helper disponible — no reemplaza ningún `Thread {}`
     * existente en el resto del codebase; la adopción queda para rondas futuras, módulo por
     * módulo, cada vez que se toque un archivo por otro motivo (mismo criterio que recomienda
     * la propuesta). No usar para trabajo que necesita reportar progreso intermedio — para eso
     * ya existe `startModuleServiceWithPolling()` (línea ~721 de este archivo), mecanismo aparte.
     */
    protected fun <T> runInBackground(work: () -> T, onResult: (T) -> Unit) {
        Thread {
            val result = work()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) onResult(result)
            }
        }.start()
    }

    protected fun isModuleRunning(): Boolean =
        com.termux.app.ModuleController.isRunning(getModuleId())

    /**
     * Card "MANTENIMIENTO" (🔄 Actualizar + 🗑 Desinstalar) — GenericModuleFragment ya la
     * agrega gratis a CUALQUIER módulo sin pantalla propia (ver su buildContent()); los
     * Fragments con pantalla propia la pierden salvo que la agreguen a mano, y varios lo
     * hacían de forma inconsistente (solo Actualizar, o ninguna de las dos) — hallazgo real
     * de la auditoría de consistencia de menús (ver
     * docs/arquitectura/AUDITORIA_CONSISTENCIA_MENUS_SISTEMA_2026-08-19.md). Llamar al final
     * de buildContent() en cualquier Fragment con pantalla propia que gestione un módulo
     * instalable/desinstalable real.
     */
    protected fun addMaintenanceCard() {
        addCard(getString(R.string.base_module_card_mantenimiento)) {
            actionButton(getString(R.string.base_module_update), ButtonStyle.GHOST) {
                toast(getString(R.string.base_module_updating, getModuleName()))
                updateModuleService { ok ->
                    toast(if (ok) getString(R.string.base_module_updated, getModuleName()) else getString(R.string.base_module_update_failed, getModuleId()))
                }
            }
            actionButton(getString(R.string.base_module_uninstall), ButtonStyle.DANGER) { confirmUninstallModule() }
        }
    }

    /**
     * Mismo diálogo (desinstalación simple vs. profunda) que GenericModuleFragment.
     * confirmUninstall()/PluginsFragment.confirmUninstall() — reusable acá porque solo
     * depende de getModuleId()/getModuleName(), ya disponibles en cualquier subtipo. Ver
     * KDoc de [addMaintenanceCard].
     */
    protected fun confirmUninstallModule() {
        val deepCheckbox = android.widget.CheckBox(requireContext()).apply {
            text = getString(R.string.base_module_deep_uninstall_checkbox)
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, 0)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.base_module_confirm_uninstall_title, getModuleName()))
            .setMessage(getString(R.string.base_module_confirm_uninstall_message))
            .setView(deepCheckbox)
            .setPositiveButton(getString(R.string.base_module_uninstall_btn)) { _, _ ->
                if (deepCheckbox.isChecked) {
                    com.termux.app.ModuleController.deepUninstallModule(getModuleId()) { ok, message ->
                        if (!isAdded) return@deepUninstallModule
                        requireActivity().runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            toast(message)
                            if (ok) parentFragmentManager.popBackStack()
                        }
                    }
                } else {
                    com.termux.app.ModuleController.uninstallModule(getModuleId()) { ok ->
                        if (!isAdded) return@uninstallModule
                        requireActivity().runOnUiThread {
                            if (ok) {
                                toast(getString(R.string.base_module_uninstalled, getModuleName()))
                                parentFragmentManager.popBackStack()
                            } else toast(getString(R.string.base_module_uninstall_failed, getModuleName()))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.base_module_cancel), null)
            .show()
    }

    protected fun toast(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Botón "Conectar memoria de Engram (MCP)" — Engram (modulos/engram.sh, memoria
     * persistente para agentes IA en SQLite local) no tenía ninguna forma de conectarse a
     * los CLIs de agentes (Claude Code, Codex, OpenCode, Antigravity) más allá de las 2 tools
     * engram_remember/engram_recall que ya usa el motor de Chat IA (cactus_engine.py, ver
     * modulos/cactus.sh). El mecanismo REAL que expone el binario para esto es
     * `engram setup <agent>` — confirmado contra el README real de
     * github.com/Gentleman-Programming/engram: es agnóstico al agente, usa el protocolo MCP
     * (Model Context Protocol), escribe la config MCP/plugin del agente elegido y a partir de
     * ahí el CLI lanza `engram mcp` solo, como subproceso stdio corto en cada sesión — no hay
     * que arrancar ni mantener ningún server manualmente. Los slugs que este helper INTENTA son
     * 4 de los CLIs con fragment propio en Kairos: "claude-code", "codex", "opencode" y
     * "antigravity-cli" — NO incluye OpenClaw ni Hermes (no son clientes MCP reconocidos por
     * Engram: son un gateway de chat y un agente de mensajería, no agentes de código con
     * soporte MCP nativo), así que esos dos módulos no llaman a este helper.
     *
     * ⚠️ Bug real reportado 2026-08-25 (Codex y Antigravity: "error al conectar con Engram" —
     * ver docs/adb/AUDITORIA_MODULO_POR_MODULO_2026-08-24.md, ronda "validación real del
     * usuario"): el comentario anterior decía estos 4 slugs estaban "confirmados" — en realidad
     * `docs/modulos/ENGRAM.md` (§6, botón genérico "Configurar integración con agentes") deja
     * documentado con más cuidado que `engram setup [agent]` NO tiene valores cerrados
     * documentados en el propio README de Engram ("no se adivina") — por eso el botón genérico
     * de EngramFragment corre `engram setup` SIN argumento, dejando que el propio binario
     * pregunte interactivamente. Este helper sí adivinaba el slug como argumento no-interactivo
     * — si el binario real de Engram no reconoce ese slug exacto para un agente dado, falla en
     * vez de preguntar. Fix: encadenar con `|| engram setup` (sin argumento) como fallback — si
     * el slug adivinado es rechazado, el mismo comando cae al selector interactivo real de
     * Engram en la misma sesión de terminal, sin que el usuario tenga que volver a tocar nada.
     *
     * Se corre en terminal (no ProcessBuilder silencioso) porque `engram setup <agent>` puede
     * mostrar pasos/pedir confirmación según el agente — mismo criterio que ya usaba
     * EngramFragment.kt para "engram setup" a secas.
     */
    protected fun engramSetupButton(agentSlug: String) {
        actionButton(getString(R.string.base_module_connect_engram), ButtonStyle.GHOST) {
            if (!com.termux.app.util.isTermuxBinaryAvailable("engram")) {
                toast(getString(R.string.base_module_engram_not_installed))
                return@actionButton
            }
            launchTerminalCommand("engram setup $agentSlug || engram setup")
        }
    }

    protected fun dp(d: Int): Int = (d * resources.displayMetrics.density).toInt()

    /**
     * Panel nativo de servidores MCP — reemplaza el patrón viejo "abrir la terminal para
     * correr `<cli> mcp list`" (bug real reportado por el usuario, cita textual: "al poner
     * en ver mcp abre la terminal y deberia salir una pequeña casilla abajo con los mcp
     * sin necesidad de abrir la terminal, esa es la premisa del app"). Usa el mismo patrón
     * de AlertDialog + contenido custom que ya usa el resto de la app para listados
     * (EntornoFragment.promptDistroAppRemove/promptAutostart, ClaudeFragment.runDirectPrompt)
     * en vez de introducir un BottomSheetDialogFragment nuevo — no había ninguno en el
     * proyecto todavía y este panel no necesita gestos de swipe/half-expanded, solo listar +
     * acciones puntuales. Ver docs/arquitectura/AUDITORIA_PANEL_MCP_UI_2026-08-19.md.
     *
     * [loadServers] es la llamada de lectura real (ClaudeNative.mcpServers()/
     * OpenCodeNative.mcpServers()) — corre siempre en background. [onToggle] es opcional:
     * solo OpenCode expone hoy un campo "enabled" seguro de togglear en su schema real: si
     * es null, las filas no muestran acción de habilitar/deshabilitar (Claude Code no la
     * ofrece por ahora — ver ClaudeNative.mcpServers()). [onOpenTerminal] deja la vía vieja
     * disponible como alternativa explícita (instalar un MCP nuevo con comandos
     * interactivos sigue necesitando la terminal) — nunca se fuerza, es un botón más.
     */
    protected fun showMcpPanel(
        moduleLabel: String,
        loadServers: () -> org.json.JSONObject,
        onToggle: ((name: String, enabled: Boolean) -> org.json.JSONObject)? = null,
        onOpenTerminal: (() -> Unit)? = null,
        onViewDetail: ((name: String) -> org.json.JSONObject)? = null
    ) {
        if (!isAdded) return
        Thread {
            val json = loadServers()
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (isAdded) renderMcpPanel(moduleLabel, json, loadServers, onToggle, onOpenTerminal, onViewDetail)
            }
        }.start()
    }

    private fun renderMcpPanel(
        moduleLabel: String,
        json: org.json.JSONObject,
        loadServers: () -> org.json.JSONObject,
        onToggle: ((name: String, enabled: Boolean) -> org.json.JSONObject)?,
        onOpenTerminal: (() -> Unit)?,
        onViewDetail: ((name: String) -> org.json.JSONObject)? = null
    ) {
        val ctx = requireContext()
        if (!json.optBoolean("ok", false)) {
            toast(getString(R.string.base_module_mcp_read_error, json.optString("error", getString(R.string.base_module_error_unknown))))
            return
        }
        val configPath = json.optString("configPath", "")
        val servers = json.optJSONArray("servers")
        val list = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // Referencia mutable al diálogo actual — un toggle de "habilitar/deshabilitar" debe
        // CERRAR este diálogo antes de reabrir uno nuevo con datos frescos (showMcpPanel
        // vuelve a leer el archivo), si no quedan dos AlertDialog apilados uno sobre otro.
        var dialogRef: androidx.appcompat.app.AlertDialog? = null
        if (servers == null || servers.length() == 0) {
            list.addView(TextView(ctx).apply {
                text = if (json.optBoolean("configExists", true))
                    getString(R.string.base_module_mcp_no_servers)
                else
                    getString(R.string.base_module_mcp_config_missing, configPath)
                textSize = 13f
                setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                setPadding(dp(12), dp(16), dp(12), dp(16))
            })
        } else {
            for (i in 0 until servers.length()) {
                list.addView(
                    mcpServerRow(servers.getJSONObject(i), onToggle, onViewDetail) {
                        dialogRef?.dismiss()
                        showMcpPanel(moduleLabel, loadServers, onToggle, onOpenTerminal, onViewDetail)
                    }
                )
            }
        }
        // Spacer visual mínimo antes de las acciones de config — NO usa divider() porque ese
        // helper agrega directo a `container` (el contenedor del fragment), no al `list` de
        // este diálogo.
        list.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(8))
        })
        list.addView(createActionButton(getString(R.string.base_module_copy_config), ButtonStyle.GHOST) {
            copyMcpConfigToClipboard(configPath)
        })
        list.addView(createActionButton(getString(R.string.base_module_open_config_editor), ButtonStyle.GHOST) {
            if (File(configPath).isFile) {
                navigateTo(EditorFragment.newInstance(configPath))
            } else {
                toast(getString(R.string.base_module_config_missing_toast, configPath))
            }
        })
        if (onOpenTerminal != null) {
            list.addView(createActionButton(getString(R.string.base_module_open_terminal_advanced), ButtonStyle.GHOST) {
                onOpenTerminal()
            })
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(list) }
        dialogRef = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.base_module_mcp_dialog_title, moduleLabel))
            .setView(scroll)
            .setPositiveButton(getString(R.string.base_module_close), null)
            .show()
    }

    private fun mcpServerRow(
        server: org.json.JSONObject,
        onToggle: ((name: String, enabled: Boolean) -> org.json.JSONObject)?,
        onViewDetail: ((name: String) -> org.json.JSONObject)?,
        onChanged: () -> Unit
    ): View {
        val ctx = requireContext()
        val name = server.optString("name", "?")
        val type = server.optString("type", "")
        val transport = server.optString("transport", "")
        val scope = server.optString("scope", "")
        val hasEnabledField = server.has("enabled")
        val enabled = server.optBoolean("enabled", true)
        return LinearLayout(ctx).apply {
            orientation = VERTICAL
            setBackgroundColor(ctx.kairosThemeColor(R.attr.kairosBg3))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.bottomMargin = dp(8) }
            addView(LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply {
                    text = name
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                if (hasEnabledField) {
                    addView(pill(if (enabled) getString(R.string.base_module_mcp_active) else getString(R.string.base_module_mcp_inactive), enabled))
                }
            })
            val subtitle = listOfNotNull(
                type.takeIf { it.isNotBlank() }?.let { getString(R.string.base_module_mcp_type_prefix, it) },
                scope.takeIf { it.isNotBlank() }?.let { getString(R.string.base_module_mcp_scope_prefix, it) }
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                addView(TextView(ctx).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                    setPadding(0, dp(2), 0, 0)
                })
            }
            if (transport.isNotBlank()) {
                addView(TextView(ctx).apply {
                    text = transport
                    textSize = 12f
                    setTypeface(android.graphics.Typeface.MONOSPACE)
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                    maxLines = 2
                    setPadding(0, dp(2), 0, 0)
                })
            }
            val rowActions = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            if (onViewDetail != null) {
                rowActions.addView(TextView(ctx).apply {
                    text = getString(R.string.base_module_view_detail)
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText2))
                    setPadding(0, 0, dp(20), 0)
                    setOnClickListener { showMcpServerDetail(name, onViewDetail) }
                })
            }
            if (onToggle != null && hasEnabledField) {
                rowActions.addView(TextView(ctx).apply {
                    text = if (enabled) getString(R.string.base_module_disable) else getString(R.string.base_module_enable)
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosBlue))
                    setOnClickListener {
                        Thread {
                            val res = onToggle(name, !enabled)
                            if (!isAdded) return@Thread
                            requireActivity().runOnUiThread {
                                if (!isAdded) return@runOnUiThread
                                toast(
                                    if (res.optBoolean("ok", false)) res.optString("message", getString(R.string.base_module_saved_default))
                                    else getString(R.string.base_module_error_prefix, res.optString("error", getString(R.string.base_module_error_unknown)))
                                )
                                onChanged()
                            }
                        }.start()
                    }
                })
            }
            if (rowActions.childCount > 0) addView(rowActions)
        }
    }

    // Diálogo de solo lectura con comando/args/env vars de un servidor MCP puntual — ver
    // OpenCodeNative.mcpServerDetail() para el schema real leído. Corre en background (lectura
    // de archivo) con el guard estándar de Fragment-adjunto del proyecto.
    private fun showMcpServerDetail(name: String, onViewDetail: (name: String) -> org.json.JSONObject) {
        val ctx = requireContext()
        Thread {
            val detail = onViewDetail(name)
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (!detail.optBoolean("ok", false)) {
                    toast(getString(R.string.base_module_error_prefix, detail.optString("error", getString(R.string.base_module_error_unknown))))
                    return@runOnUiThread
                }
                val lines = mutableListOf<String>()
                lines += getString(R.string.base_module_mcp_detail_type, detail.optString("type", getString(R.string.base_module_mcp_detail_dash)))
                lines += getString(R.string.base_module_mcp_detail_enabled, if (detail.optBoolean("enabled", true)) getString(R.string.base_module_yes) else getString(R.string.base_module_no))
                if (detail.has("url")) {
                    lines += getString(R.string.base_module_mcp_detail_url, detail.optString("url"))
                    lines += getString(R.string.base_module_mcp_detail_oauth, if (detail.optBoolean("oauth", false)) getString(R.string.base_module_yes) else getString(R.string.base_module_no))
                }
                detail.optJSONArray("command")?.let { cmd ->
                    if (cmd.length() > 0) {
                        lines += getString(R.string.base_module_mcp_detail_command, cmd.optString(0))
                        if (cmd.length() > 1) {
                            lines += getString(R.string.base_module_mcp_detail_args, (1 until cmd.length()).joinToString(" ") { cmd.optString(it) })
                        }
                    }
                }
                val env = detail.optJSONObject("environment") ?: detail.optJSONObject("headers")
                if (env != null && env.length() > 0) {
                    lines += ""
                    lines += if (detail.has("headers")) getString(R.string.base_module_mcp_detail_headers) else getString(R.string.base_module_mcp_detail_env_vars)
                    env.names()?.let { names ->
                        for (i in 0 until names.length()) {
                            val key = names.getString(i)
                            lines += getString(R.string.base_module_mcp_detail_env_line, key, env.optString(key))
                        }
                    }
                }
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(name)
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton(getString(R.string.base_module_close), null)
                    .show()
            }
        }.start()
    }

    /** Copia el contenido crudo del archivo de config MCP al portapapeles — mismo patrón
     * ClipboardManager/ClipData ya usado en RemoteFragment/NubeFragment/TunnelFragment. */
    private fun copyMcpConfigToClipboard(configPath: String) {
        Thread {
            val text = try {
                File(configPath).takeIf { it.isFile }?.readText() ?: ""
            } catch (_: Exception) {
                ""
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (text.isBlank()) {
                    toast(getString(R.string.base_module_mcp_no_config_to_copy))
                    return@runOnUiThread
                }
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("mcp_config", text))
                toast(getString(R.string.base_module_config_copied))
            }
        }.start()
    }

    enum class ButtonStyle { PRIMARY, DANGER, GHOST }
}
