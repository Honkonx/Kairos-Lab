package com.termux.app.ui

import android.net.Uri
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import com.termux.R
import com.termux.app.util.ManagerNativeUtils
import com.termux.app.util.TERMUX_PREFIX_PATH
import java.io.File
import kotlin.concurrent.thread

/** Descargador único de vídeo/audio: UI nativa, yt-dlp/ffmpeg como motor y Movies por defecto. */
class MediaDownloaderFragment : BaseModuleFragment() {

    override fun getModuleId() = "media-downloader"
    override fun getModuleName() = getString(R.string.media_downloader_title)

    private lateinit var etUrl: EditText
    private lateinit var formatGroup: RadioGroup
    private lateinit var qualitySpinner: Spinner
    private lateinit var qualityGroup: View
    private lateinit var tvPath: TextView
    private lateinit var btnDownload: Button
    private lateinit var progressGroup: View
    private lateinit var tvProgressText: TextView
    private lateinit var progressBar: ProgressBar
    private var destinationTree: Uri? = null

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && isAdded) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* algunos proveedores no ofrecen permiso persistente */ }
            destinationTree = uri
            requireContext().getSharedPreferences("media_downloader", 0).edit()
                .putString("destination_tree", uri.toString()).apply()
            tvPath.text = DocumentFile.fromTreeUri(requireContext(), uri)?.name ?: getString(R.string.media_downloader_custom_folder)
        }
    }

    override fun buildContent() {
        val customView = LayoutInflater.from(requireContext()).inflate(R.layout.fragment_media_downloader, container, false)
        container.addView(customView)
        etUrl = customView.findViewById(R.id.et_url)
        formatGroup = customView.findViewById(R.id.rg_format)
        qualitySpinner = customView.findViewById(R.id.sp_video_quality)
        qualityGroup = customView.findViewById(R.id.ll_video_quality)
        qualitySpinner.adapter = ArrayAdapter.createFromResource(
            requireContext(), R.array.media_downloader_qualities, android.R.layout.simple_spinner_dropdown_item
        )
        formatGroup.setOnCheckedChangeListener { _, checkedId ->
            qualityGroup.visibility = if (checkedId == R.id.rb_audio_only) View.GONE else View.VISIBLE
        }
        tvPath = customView.findViewById(R.id.tv_path)
        btnDownload = customView.findViewById(R.id.btn_download)
        progressGroup = customView.findViewById(R.id.ll_progress)
        tvProgressText = customView.findViewById(R.id.tv_progress_text)
        progressBar = customView.findViewById(R.id.progress_bar)

        val prefs = requireContext().getSharedPreferences("media_downloader", 0)
        destinationTree = prefs.getString("destination_tree", null)?.let(Uri::parse)
        tvPath.text = destinationTree?.let { DocumentFile.fromTreeUri(requireContext(), it)?.name }
            ?: DEFAULT_MOVIES

        customView.findViewById<Button>(R.id.btn_change_path).setOnClickListener { chooseFolder.launch(null) }
        btnDownload.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                etUrl.error = getString(R.string.media_downloader_url_required)
                return@setOnClickListener
            }
            startDownload(url)
        }
    }

    private fun startDownload(url: String) {
        val ctx = context ?: return
        val destinationAtStart = destinationTree
        val destinationLabel = tvPath.text.toString()
        val outDir = File(ctx.cacheDir, "media-download-${System.currentTimeMillis()}").apply { mkdirs() }
        progressGroup.visibility = View.VISIBLE
        progressBar.isIndeterminate = true // runExec devuelve el log al terminar; no simular porcentaje.
        tvProgressText.text = getString(R.string.media_downloader_working)
        btnDownload.isEnabled = false
        val height = qualitySpinner.selectedItem?.toString()?.substringBefore('p')?.toIntOrNull() ?: 1440
        val formatArgs = when (formatGroup.checkedRadioButtonId) {
            R.id.rb_video_silent -> listOf(
                "-f", "bestvideo[height<=${height}][ext=mp4]/bestvideo[height<=${height}]"
            )
            R.id.rb_audio_only -> listOf("-x", "--audio-format", "mp3")
            else -> listOf(
                "-f", "bestvideo[height<=${height}][ext=mp4]+bestaudio[ext=m4a]/best[height<=${height}][ext=mp4]/best[height<=${height}]",
                "--merge-output-format", "mp4"
            )
        }
        val args = listOf(
            "$TERMUX_PREFIX_PATH/bin/yt-dlp", "--newline", "--no-playlist", "--no-progress",
            *formatArgs.toTypedArray(), "-o", "${outDir.path}/%(title).180B.%(ext)s", url
        )

        thread(name = "media-downloader") {
            val result = ManagerNativeUtils.runExec(args, 3600)
            val files = outDir.listFiles()?.filter { it.isFile }.orEmpty()
            var copyError: String? = null
            if (result.first == 0 && files.isNotEmpty()) {
                for (file in files) {
                    try {
                        copyToDestination(file, destinationAtStart, ctx)
                    } catch (e: Exception) {
                        copyError = e.message ?: ctx.getString(R.string.media_downloader_copy_failed)
                        break
                    }
                }
            }
            outDir.deleteRecursively()
            if (!isAdded) return@thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                progressGroup.visibility = View.GONE
                btnDownload.isEnabled = true
                when {
                    result.first != 0 -> showResult(false, (result.third.ifBlank { result.second }).takeLast(1600))
                    files.isEmpty() -> showResult(false, getString(R.string.media_downloader_no_output))
                    copyError != null -> showResult(false, copyError!!)
                    else -> showResult(true, getString(R.string.media_downloader_saved_to, destinationLabel))
                }
            }
        }
    }

    private fun copyToDestination(source: File, tree: Uri?, appContext: Context) {
        if (tree == null) {
            val dest = File(DEFAULT_MOVIES)
            if (!dest.exists() && !dest.mkdirs()) throw java.io.IOException(appContext.getString(R.string.media_downloader_folder_unavailable))
            val target = File(dest, nextAvailableName(dest, source.name))
            source.copyTo(target, overwrite = false)
            return
        }
        val directory = DocumentFile.fromTreeUri(appContext, tree)
            ?: throw java.io.IOException(appContext.getString(R.string.media_downloader_folder_unavailable))
        val mime = when (source.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }
        val output = directory.createFile(mime, nextAvailableName(directory, source.name))
            ?: throw java.io.IOException(appContext.getString(R.string.media_downloader_folder_unavailable))
        appContext.contentResolver.openOutputStream(output.uri, "w")?.use { sink ->
            source.inputStream().use { it.copyTo(sink) }
        } ?: throw java.io.IOException(appContext.getString(R.string.media_downloader_folder_unavailable))
    }

    private fun nextAvailableName(directory: File, name: String): String {
        if (!File(directory, name).exists()) return name
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        val stem = if (extension == null) name else name.removeSuffix(".$extension")
        var suffix = 2
        var candidate: String
        do {
            candidate = if (extension == null) "$stem ($suffix)" else "$stem ($suffix).$extension"
            suffix++
        } while (File(directory, candidate).exists())
        return candidate
    }

    private fun nextAvailableName(directory: DocumentFile, name: String): String {
        if (directory.findFile(name) == null) return name
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        val stem = if (extension == null) name else name.removeSuffix(".$extension")
        var suffix = 2
        var candidate: String
        do {
            candidate = if (extension == null) "$stem ($suffix)" else "$stem ($suffix).$extension"
            suffix++
        } while (directory.findFile(candidate) != null)
        return candidate
    }

    private fun showResult(success: Boolean, message: String) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(if (success) getString(R.string.media_downloader_success) else getString(R.string.media_downloader_error))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val DEFAULT_MOVIES = "/storage/emulated/0/Movies"
    }
}
