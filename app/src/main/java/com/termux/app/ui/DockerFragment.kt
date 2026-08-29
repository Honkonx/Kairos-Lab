package com.termux.app.ui

import android.widget.TextView
import com.termux.R
import com.termux.app.util.kairosThemeColor

/**
 * docker — pantalla propia del módulo (antes caía en GenericModuleFragment, solo "Abrir en
 * terminal" sin ninguna acción útil: modulos/docker.sh no instala nada, solo imprime una
 * explicación y sale). Investigación real ya documentada en la cabecera de
 * modulos/docker.sh (leída antes de escribir esto, no reimplementada acá): Docker real
 * (dockerd) necesita namespaces + cgroups del kernel de Linux accedidos directo por un
 * daemon con privilegios, y overlayfs para las capas de imagen — Android bloquea eso a apps
 * sin root por diseño del sandbox de la plataforma (no es una limitación de Termux, es del
 * sistema operativo). Sin rootear el dispositivo no hay forma de sortearlo, y rootear está
 * fuera de scope de Kairos.
 *
 * Esta pantalla reemplaza el "Abrir en terminal" genérico (que no haría nada útil, el script
 * solo imprime texto y sale) por la explicación en lenguaje simple + un botón directo a
 * UdockerFragment (alternativa real ya en Kairos: corre imágenes de Docker Hub en userspace
 * vía PRoot, sin namespaces/cgroups reales pero sin necesitar root).
 */
class DockerFragment : BaseModuleFragment() {
    override fun getModuleId() = "docker"
    override fun getModuleName() = getString(R.string.docker_module_name)

    override fun buildContent() {
        addCard {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.docker_not_working_title)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText))
                setPadding(dp(14), dp(14), dp(14), dp(6))
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.docker_not_working_body)
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                setPadding(dp(14), 0, dp(14), dp(14))
            })
        }

        addCard(getString(R.string.docker_alternative_card_title)) {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.docker_alternative_body)
                textSize = 13f
                setTextColor(requireContext().kairosThemeColor(R.attr.kairosText2))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            })
        }

        actionButton(getString(R.string.docker_go_to_udocker_button), ButtonStyle.PRIMARY) {
            navigateTo(UdockerFragment())
        }
    }
}
