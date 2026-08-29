package com.termux.app.ui

import android.widget.LinearLayout
import android.widget.TextView
import com.termux.R
import com.termux.app.util.kairosThemeColor

/**
 * Módulo consolidado "Lenguajes" (pedido explícito del usuario, 2026-08-18 — ver
 * docs/modulos/LENGUAJES.md): reemplaza las 6 tarjetas sueltas que tenía la Tienda para
 * nodejs/perl/php/rust/clang/golang (ahora `internal: true` en modules.json, sin tocar sus
 * scripts reales en `modulos/`) por un solo switch por lenguaje acá adentro.
 *
 * Todos corren NATIVOS en Termux (bionic, sin proot) — cada uno TAMBIÉN funciona dentro de
 * una distro Linux completa (Debian/Ubuntu vía Entorno/Stacks, proot) con su propio
 * `apt install <paquete>` independiente, porque ahí es un sistema de archivos glibc aparte;
 * ese segundo camino no lo gestiona este módulo (evita instalar/desinstalar 2 copias sin que
 * el usuario lo pida) — se deja anotado como referencia en cada nota.
 */
class LanguagesFragment : ConsolidatedModuleFragment() {

    override val screenId = "languages"
    // get()/lazy computado (no valor eager en el constructor): getString() necesita un Context
    // real, que el Fragment todavía no tiene en el momento en que se inicializan las propiedades
    // de la clase (antes de onAttach) — mismo motivo por el que `items` de más abajo también
    // pasó a ser un getter en vez de un `val` con la lista construida en el constructor.
    override val screenName: String get() = getString(R.string.languages_screen_name)
    override val introText: String get() = getString(R.string.languages_intro_text)

    // RunAction agregadas en la ronda de continuación 2026-08-19 (auditoría terminal→UI,
    // ver docs/arquitectura/AUDITORIA_MODULOS_IA_DEV_VS_OFICIAL_2026-08-19.md): estos 6
    // runtimes son paquetes `pkg` de Termux — a diferencia de nvm/pyenv/rustup, `pkg` solo
    // ofrece UNA versión por paquete (la que trae el repo de Termux en cada momento), así que
    // "seleccionar versión de runtime" (lo que sí tendría sentido en Python 3.11 vs 3.12, Node
    // 18 vs 20) NO es una opción real acá — no hay un flag ni un mecanismo de Termux para
    // instalar 2 versiones de nodejs-lts en paralelo. Lo que SÍ es real y hoy solo estaba
    // disponible abriendo terminal a mano: un atajo de "estado del gestor de paquetes" de una
    // sola pasada, output corto y parseable — mismo patrón [RunAction] ya usado en
    // PackagesFragment para ncu/prettier/vercel. Cada comando confirmado contra documentación
    // oficial (búsqueda 2026-08-19): `npm list -g --depth=0` (docs.npmjs.com/cli/v10/commands/
    // npm-ls), `php -m` (php.net/manual/en/features.commandline.options.php, "-m: Show compiled
    // in modules"), `cargo install --list` (doc.rust-lang.org/cargo/commands/cargo-install.html),
    // `go env` (`go help env`, imprime la config real del toolchain: GOPATH/GOROOT/GOOS/etc),
    // `perl -V` (perldoc perlrun, "-V: prints summary of major perl configuration values").
    override val items: List<SwitchModuleItem>
        get() = listOf(
        SwitchModuleItem("nodejs", getString(R.string.languages_item_nodejs_name), "node --version",
            getString(R.string.languages_item_nodejs_note),
            runActions = listOf(RunAction(getString(R.string.languages_action_npm_packages), "npm list -g --depth=0", timeoutSeconds = 20))),
        SwitchModuleItem("perl", getString(R.string.languages_item_perl_name), "perl --version",
            getString(R.string.languages_item_perl_note),
            runActions = listOf(RunAction(getString(R.string.languages_action_perl_config), "perl -V", timeoutSeconds = 15))),
        SwitchModuleItem("php", getString(R.string.languages_item_php_name), "php --version", getString(R.string.languages_item_php_note),
            runActions = listOf(RunAction(getString(R.string.languages_action_php_modules), "php -m", timeoutSeconds = 15))),
        SwitchModuleItem("rust", getString(R.string.languages_item_rust_name), "rustc --version",
            getString(R.string.languages_item_rust_note),
            runActions = listOf(RunAction(getString(R.string.languages_action_cargo_binaries), "cargo install --list", timeoutSeconds = 15))),
        // Sin RunAction — clang no tiene gestor de paquetes propio (no hay `pkg`/`cargo`/`go
        // get` equivalente para C/C++) ni un flag de una sola pasada con valor real más allá de
        // la versión que ya muestra `versionCommand`; `--print-targets` solo lista arquitecturas
        // de compilación, sin uso práctico para el usuario de Kairos. Documentado como "sin
        // candidato real" en vez de forzar una conversión de bajo valor.
        SwitchModuleItem("clang", getString(R.string.languages_item_clang_name), "clang --version", getString(R.string.languages_item_clang_note)),
        SwitchModuleItem("golang", getString(R.string.languages_item_golang_name), "go version", getString(R.string.languages_item_golang_note),
            runActions = listOf(RunAction(getString(R.string.languages_action_go_env), "go env", timeoutSeconds = 15))),
        // Agregados 2026-08-25 (auditoría de código+docs oficiales pedida explícitamente por el
        // usuario, "toca agregar mas lenguajes") — confirmado real contra packages.termux.dev:
        // "ruby" y "kotlin" existen como paquetes oficiales de Termux, mismo patrón exacto que
        // los 6 ya existentes (script propio con install_single_pkg, ver modulos/ruby.sh y
        // modulos/kotlin.sh). "gem list" (docs.ruby-lang.org) y "kotlinc -version" confirmados
        // como comandos de una sola pasada reales para el atajo de estado.
        SwitchModuleItem("ruby", getString(R.string.languages_item_ruby_name), "ruby --version", getString(R.string.languages_item_ruby_note),
            runActions = listOf(RunAction(getString(R.string.languages_action_ruby_gems), "gem list", timeoutSeconds = 15))),
        SwitchModuleItem("kotlin", getString(R.string.languages_item_kotlin_name), "kotlinc -version", getString(R.string.languages_item_kotlin_note)),
    )

    // Python ya es su propio módulo de primer nivel (PythonFragment, con venv/pip/gestión de
    // paquetes propia) — investigado antes de esta ronda: no se duplica su instalador acá para
    // no tener 2 caminos que puedan desincronizarse, pero se deja visible y accesible desde
    // Lenguajes para que el catálogo se sienta centralizado, como pidió el usuario.
    override fun addExtraRows() {
        addCard(getString(R.string.languages_card_already_installed_title)) {
            val ctx = requireContext()
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { navigateTo(PythonFragment()) }
                addView(TextView(ctx).apply {
                    text = getString(R.string.languages_python_row_label)
                    textSize = 13f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    text = getString(R.string.languages_open_arrow)
                    textSize = 12f
                    setTextColor(ctx.kairosThemeColor(R.attr.kairosText3))
                })
            })
        }
    }

    override fun sectionTitle() = getString(R.string.languages_section_title)
}
