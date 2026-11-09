package com.termux.app.util

import android.content.Context

/**
 * Preferencias del avatar flotante del Agente (AgentAvatarService.kt) — mismo archivo de
 * SharedPreferences que ConfigFragment.kt usa para el widget flotante de módulos
 * (pref_floating_widget), "kairos_prefs", para no fragmentar el estado del usuario en varios
 * archivos sin necesidad real.
 */
/** Qué dibuja AgentAvatarView — "cara" (figura mínima dibujada a mano, sin cambios) o uno de los
 *  avatares con imagen real generados por el usuario (ver ver/avatar_femenino|masculino,
 *  app/src/main/assets/agent/kari|kairo). Pedido explícito del usuario (2026-10-30, docs/
 *): "poner para escoger si desea a kari la chica, kairo el chico, o kairos la
 *  cara". */
enum class AgentAvatarKind(val assetDir: String?) {
    CARA(null), KARI("kari"), KAIRO("kairo");

    companion object {
        fun fromId(id: String?): AgentAvatarKind = entries.firstOrNull { it.name == id } ?: CARA
    }
}

/**
 * Pose de cuerpo completo del avatar (2026-11-02, pedido del usuario: "mueva la cara... y las
 * articulaciones como brazos, manos, pies... si solo falta que se acueste, esté sentado, de
 * pie"). Investigación previa (ver docs/agente/ANIMACION_2D_INVESTIGACION_2026-11-02.md):
 * Spine2D/Live2D son el estándar de la industria para rigging óseo real, pero AMBOS requieren
 * licencia comercial para distribuir — incompatible con un proyecto que se reparte gratis por
 * GitHub (ver .claude/rules/kairos-port-or-build-own.md). En vez de eso, Kairos usa el mismo
 * patrón que ya tenía para las 3 caras (neutral/mouth_open/blink): cada pose es un set de
 * imágenes PNG completas, intercambiadas con crossfade — sin huesos independientes, pero sin
 * ninguna dependencia de terceros ni licencia.
 *
 * [dirPrefix] es el subdirectorio dentro de `assets/agent/<kind>/` donde viven
 * neutral.png/mouth_open.png/blink.png de ESA pose — "" (DE_PIE) usa los archivos sueltos ya
 * existentes (`assets/agent/kari/neutral.png`), compatibilidad con el set de imágenes que el
 * usuario ya generó. Las poses nuevas necesitan su propio set en `assets/agent/<kind>/<pose>/`
 * — si no existe todavía, [AgentAvatarView] cae de vuelta a DE_PIE automáticamente (mismo
 * principio best-effort que ya usaba loadAvatarBitmaps: nunca una vista en blanco).
 */
enum class AgentAvatarPose(val dirPrefix: String) {
    DE_PIE(""), SENTADA("sentada"), ACOSTADA("acostada");

    companion object {
        /** Elige una pose distinta a la actual al azar — usado por la rotación periódica en
         *  AgentAvatarService mientras el avatar está idle (no hablando). */
        fun randomOtherThan(current: AgentAvatarPose): AgentAvatarPose =
            entries.filter { it != current }.random()
    }
}

object AgentAvatarPrefs {

    private const val PREFS_NAME = "kairos_prefs"
    private const val KEY_ENABLED = "pref_agent_avatar"
    private const val KEY_VOICE_ENABLED = "pref_agent_avatar_voice"
    private const val KEY_KIND = "pref_agent_avatar_kind"
    private const val KEY_X = "agent_avatar_x"
    private const val KEY_Y = "agent_avatar_y"

    /** Sin posición guardada todavía — el servicio calcula la esquina inferior derecha por defecto. */
    const val UNSET = -1

    fun kind(context: Context): AgentAvatarKind =
        AgentAvatarKind.fromId(prefs(context).getString(KEY_KIND, null))

    fun setKind(context: Context, kind: AgentAvatarKind) {
        prefs(context).edit().putString(KEY_KIND, kind.name).apply()
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Voz real (TextToSpeech) ademas de la burbuja de texto — apagado por defecto (pedido
     * explicito del usuario,: no todos quieren que el telefono hable solo
     *  cada tanto). Ver AgentVoice.kt. */
    fun isVoiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_ENABLED, false)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
    }

    fun savedX(context: Context): Int = prefs(context).getInt(KEY_X, UNSET)
    fun savedY(context: Context): Int = prefs(context).getInt(KEY_Y, UNSET)

    fun savePosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
