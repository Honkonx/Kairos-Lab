package com.termux.app.util

import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Paridad de módulo real para el gateway (2026-10-16, ver meta 4-5) — mismo
 * `yt-dlp` real que `MediaDownloaderFragment.startDownload()`, con los mismos argumentos de
 * formato/calidad. Simplificado respecto a la app: el destino siempre es
 * `$HOME/kairos_downloads/` (carpeta fija en Termux) en vez del árbol SAF (`DocumentFile`/`Uri`)
 * que la app usa — ese mecanismo pide un selector de carpeta de Android, algo que una acción HTTP
 * sin estado no puede ofrecer.
 */
object MediaDownloaderManager {
    private val runner: CommandRunner = ShellCommandRunner
    private fun downloadsDir(): File = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "kairos_downloads").apply { mkdirs() }

    fun download(url: String, format: String, heightMax: Int, timeoutSec: Long = 3600): PrivilegeResult {
        if (url.isBlank()) return PrivilegeResult(false, "falta la URL", 400)
        val formatArgs = when (format) {
            "video_silent" -> listOf("-f", "bestvideo[height<=$heightMax][ext=mp4]/bestvideo[height<=$heightMax]")
            "audio_only" -> listOf("-x", "--audio-format", "mp3")
            else -> listOf(
                "-f", "bestvideo[height<=$heightMax][ext=mp4]+bestaudio[ext=m4a]/best[height<=$heightMax][ext=mp4]/best[height<=$heightMax]",
                "--merge-output-format", "mp4"
            )
        }
        val out = downloadsDir()
        val args = listOf("yt-dlp", "--newline", "--no-playlist", "--no-progress") + formatArgs +
            listOf("-o", "${out.path}/%(title).180B.%(ext)s", url)
        val (code, output) = runner.run(args, timeoutSec)
        return PrivilegeResult(code == 0, if (code == 0) out.path else output.takeLast(1600), if (code == 0) 200 else 500)
    }
}
