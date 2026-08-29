package com.termux.app.ui.studio.filetree

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.termux.R

/**
 * Iconografía por tipo de archivo: misma familia de formas (código / config / marcado / doc /
 * imagen / genérico, ver drawable/ic_file_*.xml) tintadas con un color distinto por categoría de
 * lenguaje (values/colors.xml, tokens `ide_lang_*`). No son logos reales de cada lenguaje
 * (evita choques de marca y mantiene el set de assets chico) — la distinción visual es forma +
 * color, como en referencia/ides/android-code-studio-dev (layout_filetree_item.xml).
 */
object FileIconProvider {

    data class IconSpec(@DrawableRes val drawableRes: Int, @ColorRes val colorRes: Int)

    private val CODE_EXTENSIONS = setOf(
        "c", "cpp", "h", "hpp", "rs", "go", "php", "rb", "swift", "dart", "cs"
    )
    private val CONFIG_EXTENSIONS = setOf("json", "properties", "yml", "yaml", "toml", "ini", "lock")
    private val MARKUP_EXTENSIONS = setOf("xml", "html", "htm", "svg")
    private val DOC_EXTENSIONS = setOf("md", "markdown", "txt", "rst", "log")
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val WEB_EXTENSIONS = setOf("js", "ts", "jsx", "tsx", "css", "scss")
    private val SHELL_EXTENSIONS = setOf("sh", "bash", "zsh")

    fun iconFor(name: String, isDirectory: Boolean, isExpanded: Boolean): IconSpec {
        if (isDirectory) {
            return if (isExpanded) {
                IconSpec(R.drawable.studio_ic_folder_open, R.color.ide_folder)
            } else {
                IconSpec(R.drawable.studio_ic_folder, R.color.ide_folder)
            }
        }

        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension == "kt" || extension == "kts" -> IconSpec(R.drawable.studio_ic_file_code, R.color.ide_lang_kotlin)
            extension == "java" -> IconSpec(R.drawable.studio_ic_file_code, R.color.ide_lang_java)
            extension == "py" -> IconSpec(R.drawable.studio_ic_file_code, R.color.ide_lang_python)
            extension == "gradle" -> IconSpec(R.drawable.studio_ic_file_config, R.color.ide_lang_gradle)
            extension in SHELL_EXTENSIONS -> IconSpec(R.drawable.studio_ic_file_code, R.color.ide_lang_shell)
            extension in WEB_EXTENSIONS -> IconSpec(R.drawable.studio_ic_file_code, R.color.ide_lang_web)
            extension in CODE_EXTENSIONS -> IconSpec(R.drawable.studio_ic_file_code, R.color.ide_lang_default)
            extension in CONFIG_EXTENSIONS -> IconSpec(R.drawable.studio_ic_file_config, R.color.ide_lang_json)
            extension in MARKUP_EXTENSIONS -> IconSpec(R.drawable.studio_ic_file_markup, R.color.ide_lang_xml)
            extension in DOC_EXTENSIONS -> IconSpec(R.drawable.studio_ic_file_doc, R.color.ide_lang_markdown)
            extension in IMAGE_EXTENSIONS -> IconSpec(R.drawable.studio_ic_file_image, R.color.ide_lang_image)
            else -> IconSpec(R.drawable.studio_ic_file_generic, R.color.ide_lang_default)
        }
    }
}
