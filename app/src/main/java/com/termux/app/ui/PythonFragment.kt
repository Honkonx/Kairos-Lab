package com.termux.app.ui

import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.ui.BaseModuleFragment.ButtonStyle.GHOST
import com.termux.app.util.TERMUX_PREFIX_PATH
import com.termux.app.util.applyTermuxEnv
import com.termux.app.util.friendlyProcessErrorMessage
import com.termux.app.util.isTermuxBinaryAvailable
import com.termux.app.util.promptOpenLocation
import com.termux.shared.termux.TermuxConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class PythonFragment : BaseModuleFragment() {
    override fun getModuleId() = "python"
    override fun getModuleName() = "Python"

    // Holds latest python info for UI updates
    private var pythonInfo: JSONObject? = null

    private var versionValue: android.widget.TextView? = null
    private var pipValue: android.widget.TextView? = null

    // El registry (~/.android_server_registry) puede decir "python.installed=true" con un
    // dispositivo real donde python3 no se puede ejecutar — evidencia real confirmada
    // (capturas + logs, docs/humano*.md 2026-07-31: "Cannot run program python3" repetido
    // en 6+ pantallas). Python es el módulo con más evidencia de estar en ese estado, así
    // que acá se verifica el binario de verdad en vez de confiar ciegamente en el registry
    // (ver isTermuxBinaryAvailable en ProcessBuilderExt.kt).
    override fun isModuleInstalled(): Boolean {
        return super.isModuleInstalled() && isTermuxBinaryAvailable("python3")
    }

    override fun buildContent() {
        if (!isModuleInstalled()) { showNotInstalled(getModuleName()); return }
        // Info card
        // Bug real (2026-08-07, ver docs/humano/humano91.md): "Versión"/"pip"
        // quedaban en "—" para siempre — buildInfoJson() SÍ trae los datos reales (y
        // "Ver versión e info" SÍ los pide), pero runPythonAction() nunca los volvía a
        // pintar en la card, solo guardaba pythonInfo sin usarlo. Ahora se cargan solos al
        // abrir la pantalla y se refrescan cada vez que se toca "Ver versión e info".
        // 2026-08-10: el SQLite se sacó de esta pantalla — ahora es el módulo "db" (DbFragment).
        addCard("ESTADO") {
            addView(infoRow(getString(R.string.python_status_version), "—").also { versionValue = it.valueTextView() })
            addView(infoRow(getString(R.string.python_status_pip), "—").also { pipValue = it.valueTextView() })
        }
        actionButton(getString(R.string.python_btn_info), GHOST) { runPythonAction("info") }
        actionButton(getString(R.string.python_btn_repl), GHOST) { openRepl() }
        actionButton(getString(R.string.python_btn_pip_install), GHOST) { promptAndRun("pip-install") }
        // 2026-08-11 (humano97 R2 — pedido usuario): submenú de paquetes por categoría
        // (algoritmos / IA / datos / web / ciencia / herramientas). Recopila paquetes Python
        // normal (PyPI) Y los que tienen binarios para Termux. Ver showPackageCategories().
        actionButton(getString(R.string.python_btn_categories), GHOST) { showPackageCategories() }
        actionButton(getString(R.string.python_btn_pip_list), GHOST) { runPythonAction("pip-list") }
        // pip.pypa.io/en/stable/cli/pip_list/ — "pip list --outdated" es una acción oficial
        // de pip sin ningún equivalente en la app hasta ahora. Antes "pip-list" ya traía el
        // JSON completo de paquetes instalados (buildPipListJson()) pero runPythonAction()
        // lo descartaba en un Snackbar "OK" genérico sin mostrar el contenido — mismo patrón
        // de bug que "info" tenía en humano91 (JSON armado, nunca pintado). Ahora ambos
        // ("pip-list" y "pip-outdated") se renderizan en un diálogo nativo con la lista real.
        actionButton(getString(R.string.python_btn_pip_outdated), GHOST) { runPythonAction("pip-outdated") }
        actionButton(getString(R.string.python_btn_run_script), GHOST) { pickAndRunScript() }
        // Pedido de esta ronda: Python no tenía gestión real de entornos por proyecto —
        // "pip install" siempre instalaba global (--break-system-packages), sin forma de
        // aislar dependencias de un proyecto de otro ni de detectar/instalar un
        // requirements.txt existente. venv con --system-site-packages porque en Termux los
        // paquetes pesados (numpy/scipy/pandas) vienen precompilados vía "pkg install" — un
        // venv aislado del todo no podría instalarlos por pip (no hay wheels ARM64 en PyPI
        // para varios de ellos), así que el venv hereda esos paquetes del sistema y solo
        // aísla lo que el proyecto instale con pip.
        actionButton(getString(R.string.python_btn_venv), GHOST) { pickProjectForVenv() }
        // Bug real (auditoría 2026-08-05, ver docs/humano65.md/humano66.md): ningún módulo sin
        // CLI dedicada (Python/Ollama/n8n/Expo/Remote) tenía forma de actualizar desde la app.
        actionButton(getString(R.string.python_btn_update), GHOST) {
            toast(getString(R.string.python_toast_updating))
            updateModuleService { ok ->
                toast(if (ok) getString(R.string.python_toast_updated) else getString(R.string.python_toast_update_failed))
            }
        }
        runPythonAction("info")
    }

    // submenu_python() opcion [6] en termux-ai-stack-dev/scripts/menu_nativo.sh no le pide al
    // usuario una ruta a ciegas: primero busca .py en ~/python, ~ y Download y los muestra
    // numerados para elegir. Antes esto pasaba por kairos_manager.py ("python find-scripts"),
    // corriendo un subproceso Python para recorrer directorios — algo que Kotlin puede hacer
    // directo con java.io.File, sin subproceso de por medio (ver findPyScripts() más abajo).
    // Si la busqueda falla o no encuentra nada, cae al mismo dialogo manual de antes.
    private fun pickAndRunScript() {
        Thread {
            val json = try {
                buildFindScriptsJson(null)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", friendlyProcessErrorMessage(e, "Python (python3)"))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                val scripts = if (json.optBoolean("ok", false)) json.optJSONArray("scripts") else null
                if (scripts == null || scripts.length() == 0) {
                    promptAndRun("run-script")
                    return@runOnUiThread
                }
                val displays = ArrayList<String>()
                val paths = ArrayList<String>()
                for (i in 0 until scripts.length()) {
                    val s = scripts.getJSONObject(i)
                    displays.add(s.optString("display", s.optString("path")))
                    paths.add(s.optString("path"))
                }
                displays.add(getString(R.string.python_item_manual_path))
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.python_title_choose_script))
                    .setItems(displays.toTypedArray()) { _, which ->
                        if (which == paths.size) promptAndRun("run-script")
                        else runPythonAction("run-script", paths[which])
                    }
                    .setNegativeButton(getString(R.string.python_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    // Antes esta acción invocaba "python3 kairos_manager.py python <action>" — un script
    // Python para terminar corriendo python3 igual, con el intermediario de por medio. Ahora
    // Kotlin lanza python3 directo por ProcessBuilder (ver runCommand()) o usa File I/O nativo
    // (find-scripts) — un paso menos, mismo resultado, mismo contrato JSON de salida.
    private fun runPythonAction(action: String, vararg extraArgs: String) {
        Thread {
            val json = try {
                when (action) {
                    "info" -> buildInfoJson()
                    "pip-install" -> buildPipInstallJson(extraArgs.getOrNull(0))
                    "pip-list" -> buildPipListJson()
                    "pip-outdated" -> buildPipOutdatedJson()
                    "pip-upgrade" -> buildPipUpgradeJson(extraArgs.getOrNull(0))
                    "find-scripts" -> buildFindScriptsJson(extraArgs.getOrNull(0))
                    "run-script" -> buildRunScriptJson(extraArgs.getOrNull(0))
                    else -> JSONObject().put("ok", false).put("error", getString(R.string.python_error_unknown_action, action))
                }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", friendlyProcessErrorMessage(e, "Python (python3)"))
            }
            val ok = json.optBoolean("ok", false)
            if (ok && action == "info") pythonInfo = json
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok && action == "info") {
                    versionValue?.text = if (json.isNull("python")) "—" else json.optString("python", "—")
                    pipValue?.text = if (json.isNull("pip")) "—" else json.optString("pip", "—")
                    return@runOnUiThread
                }
                if (ok && (action == "pip-list" || action == "pip-outdated")) {
                    showPackageListDialog(json.optJSONArray("packages") ?: JSONArray(), action == "pip-outdated")
                    return@runOnUiThread
                }
                val msg = if (ok) getString(R.string.python_ok) else getString(R.string.python_error_prefixed, json.optString("error", getString(R.string.python_error_unknown)))
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun buildInfoJson(): JSONObject {
        val ver = runCommand(listOf("python3", "--version"), 5)
        val pip = runCommand(listOf("python3", "-m", "pip", "--version"), 5)
        // "command" es un builtin de bash, no un binario en $PREFIX/bin — hay que pasarlo
        // por bash -c como en isTermuxBinaryAvailable(), no se puede invocar directo.
        val pathRes = runCommand(listOf("bash", "-c", "command -v python3"), 5)
        val packages = JSONObject()
        for (m in listOf("numpy", "scipy", "pandas", "requests", "websockets", "PIL", "bs4")) {
            val r = runCommand(listOf("python3", "-c", "import $m"), 5)
            packages.put(m, r.exitCode == 0)
        }
        val pipVersion = pip.stdout.trim().split(Regex("\\s+")).getOrNull(1)
        return JSONObject()
            .put("ok", true)
            .put("python", if (ver.exitCode == 0) ver.stdout.removePrefix("Python ").trim() else JSONObject.NULL)
            .put("pip", if (pip.exitCode == 0 && !pipVersion.isNullOrBlank()) pipVersion else JSONObject.NULL)
            .put("path", if (pathRes.exitCode == 0 && pathRes.stdout.isNotBlank()) pathRes.stdout else JSONObject.NULL)
            .put("packages", packages)
    }

    private fun buildPipInstallJson(pkg: String?): JSONObject {
        if (pkg.isNullOrBlank()) return JSONObject().put("ok", false).put("error", getString(R.string.python_error_missing_pkg))
        val r = runCommand(listOf("python3", "-m", "pip", "install", "--break-system-packages", pkg), 120)
        return if (r.exitCode == 0) {
            JSONObject().put("ok", true).put("message", getString(R.string.python_msg_pkg_installed, pkg)).put("output", r.stdout.takeLast(500))
        } else {
            JSONObject().put("ok", false).put("error", getString(R.string.python_error_generic)).put("output", r.stderr.takeLast(500))
        }
    }

    private fun buildPipListJson(): JSONObject {
        val r = runCommand(listOf("python3", "-m", "pip", "list", "--format=json"), 15)
        if (r.exitCode == 0 && r.stdout.isNotBlank()) {
            try {
                val arr = JSONArray(r.stdout)
                return JSONObject().put("ok", true).put("packages", arr).put("count", arr.length())
            } catch (_: Exception) {
                // fallback abajo al parseo de texto plano
            }
        }
        val r2 = runCommand(listOf("python3", "-m", "pip", "list"), 15)
        val arr = JSONArray()
        val lines = r2.stdout.trim().split("\n").drop(2)
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) arr.put(JSONObject().put("name", parts[0]).put("version", parts[1]))
        }
        return JSONObject().put("ok", true).put("packages", arr).put("count", arr.length())
    }

    // pip.pypa.io/en/stable/cli/pip_list/ confirma "--outdated" (alias -o) combinado con
    // "--format=json" — devuelve un array con name/version/latest_version/latest_filetype
    // por paquete desactualizado. Parseo defensivo con optString: si algún campo cambia de
    // nombre entre versiones de pip, se muestra "?" en vez de romper el diálogo.
    private fun buildPipOutdatedJson(): JSONObject {
        val r = runCommand(listOf("python3", "-m", "pip", "list", "--outdated", "--format=json"), 30)
        if (r.exitCode == 0 && r.stdout.isNotBlank()) {
            try {
                val arr = JSONArray(r.stdout)
                return JSONObject().put("ok", true).put("packages", arr).put("count", arr.length())
            } catch (_: Exception) {
                // fallback abajo
            }
        }
        return JSONObject().put("ok", false).put("error", getString(R.string.python_error_pip_outdated_failed))
            .put("output", r.stderr.ifBlank { r.stdout }.takeLast(500))
    }

    private fun buildPipUpgradeJson(pkg: String?): JSONObject {
        if (pkg.isNullOrBlank()) return JSONObject().put("ok", false).put("error", getString(R.string.python_error_missing_pkg))
        val r = runCommand(listOf("python3", "-m", "pip", "install", "--upgrade", "--break-system-packages", pkg), 120)
        return if (r.exitCode == 0) {
            JSONObject().put("ok", true).put("message", getString(R.string.python_msg_pkg_upgraded, pkg)).put("output", r.stdout.takeLast(500))
        } else {
            JSONObject().put("ok", false).put("error", getString(R.string.python_error_upgrading_pkg, pkg)).put("output", r.stderr.takeLast(500))
        }
    }

    // Render nativo compartido por "pip-list" y "pip-outdated" — antes ambos armaban el JSON
    // real (buildPipListJson/buildPipOutdatedJson) y runPythonAction() lo tiraba a un
    // Snackbar "OK" sin mostrar ni un paquete. Para "outdated" cada fila es tocable y ofrece
    // actualizar ese paquete puntual (pip install --upgrade) sin salir del diálogo.
    private fun showPackageListDialog(packages: JSONArray, outdated: Boolean) {
        if (packages.length() == 0) {
            Snackbar.make(requireView(), if (outdated) getString(R.string.python_msg_all_updated) else getString(R.string.python_msg_no_packages), Snackbar.LENGTH_SHORT).show()
            return
        }
        val names = ArrayList<String>()
        val labels = ArrayList<String>()
        for (i in 0 until packages.length()) {
            val p = packages.getJSONObject(i)
            val name = p.optString("name", "?")
            names.add(name)
            labels.add(
                if (outdated) {
                    "$name   ${p.optString("version", "?")} → ${p.optString("latest_version", "?")}"
                } else {
                    "$name   ${p.optString("version", "?")}"
                }
            )
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (outdated) getString(R.string.python_title_outdated_count, packages.length()) else getString(R.string.python_title_installed_count, packages.length()))
            .setItems(labels.toTypedArray()) { _, which ->
                if (!outdated) return@setItems
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.python_title_update_pkg, names[which]))
                    .setMessage(getString(R.string.python_msg_confirm_pip_upgrade, names[which]))
                    .setPositiveButton(getString(R.string.python_btn_update_confirm)) { _, _ ->
                        toast(getString(R.string.python_toast_updating_pkg, names[which]))
                        runPythonAction("pip-upgrade", names[which])
                    }
                    .setNegativeButton(getString(R.string.python_btn_cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.python_btn_close), null)
        dialog.show()
    }

    // Auditoría 2026-07-27 del original (kairos_manager.py cmd_python "find-scripts"): un
    // guard de profundidad (paths distintos según sea $HOME u otra carpeta) más una lista de
    // carpetas a saltear (_SKIP_DIRS) para no recorrer node_modules/.git/venv/etc enteros.
    // Se porta 1:1 acá con recursión manual sobre java.io.File — mismo guard, misma lista.
    private val skipDirs = setOf("node_modules", ".git", "venv", ".venv", ".gradle", "build", "__pycache__")

    private fun buildFindScriptsJson(customPath: String?): JSONObject {
        val home = TermuxConstants.TERMUX_HOME_DIR_PATH
        val bases = mutableListOf(File(home, "python").absolutePath, home, "/storage/emulated/0/Download")
        if (!customPath.isNullOrBlank()) bases.add(0, customPath)
        val seen = mutableSetOf<String>()
        val scripts = JSONArray()
        for (base in bases) {
            val baseFile = File(base)
            if (!baseFile.isDirectory) continue
            val maxDepth = if (base == home) 1 else 2
            collectPyScripts(baseFile, 0, maxDepth, home, seen, scripts)
        }
        return JSONObject().put("ok", true).put("scripts", scripts).put("count", scripts.length())
    }

    private fun collectPyScripts(
        dir: File,
        depth: Int,
        maxDepth: Int,
        homePath: String,
        seen: MutableSet<String>,
        out: JSONArray
    ) {
        if (depth >= maxDepth) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isFile && f.extension == "py") {
                val abs = f.absolutePath
                if (seen.add(abs)) {
                    out.put(
                        JSONObject()
                            .put("path", abs)
                            .put("name", f.name)
                            .put("size", f.length())
                            .put("display", abs.replace(homePath, "~"))
                    )
                }
            }
        }
        for (d in children) {
            if (d.isDirectory && d.name !in skipDirs) collectPyScripts(d, depth + 1, maxDepth, homePath, seen, out)
        }
    }

    private fun buildRunScriptJson(path: String?): JSONObject {
        if (path.isNullOrBlank()) return JSONObject().put("ok", false).put("error", getString(R.string.python_error_missing_path))
        val f = File(path)
        if (!f.exists()) return JSONObject().put("ok", false).put("error", getString(R.string.python_error_path_not_exist, path))
        // Sin shell de por medio no hace falta shlex.quote() (auditoría 2026-07-27 del
        // original) — cada argumento va como elemento separado de la lista, ProcessBuilder
        // nunca los reinterpreta como sintaxis de shell.
        val r = runCommand(listOf("python3", f.absolutePath), 120, workDir = f.parentFile)
        return JSONObject()
            .put("ok", true)
            .put("exit_code", r.exitCode)
            .put("stdout", r.stdout.takeLast(2000))
            .put("stderr", r.stderr.takeLast(2000))
    }

    private fun promptAndRun(action: String) {
        val ctx = requireContext()
        val edit = EditText(ctx)
        edit.hint = getString(R.string.python_hint_value)
        AlertDialog.Builder(ctx)
            .setTitle(action)
            .setView(edit)
            .setPositiveButton(getString(R.string.python_ok)) { _, _ ->
                val value = edit.text.toString()
                if (value.isNotBlank()) runPythonAction(action, value)
            }
            .setNegativeButton(getString(R.string.python_btn_cancel), null)
            .show()
    }

    // 2026-08-11 (humano97 R2 — pedido usuario): "los demás paquetes que se instalaban como
    // pandas etc debe estar en su propia pantalla u opción dentro del submenú de python, ejemplo
    // poner paquetes de algoritmos, paquetes de IA, de datos etc, así el usuario elige.
    // Recopilar bastantes paquetes Python normales y para termux, además de los que tenemos."
    //
    // Catálogo curado — paquetes PyPI (instalables con `pip install --break-system-packages`) y
    // binarios disponibles en el repo termux (usa wheel Python, casi todo corre). Agrupados por
    // categoría para que el usuario elija sin tener que memorizar nombres. La instalación corre
    // por runPythonAction("pip-install", nombre) = pip install --break-system-packages.
    // nameRes en vez de un String resuelto: esta lista es un campo de instancia inicializado
    // antes de que el Fragment tenga Context adjunto (onAttach corre después del constructor),
    // así que no se puede llamar a getString() acá — se resuelve recién en showPackageCategories(),
    // que sí corre con Context disponible.
    private data class PkgCategory(val nameRes: Int, val packages: List<String>)

    private val pkgCategories = listOf(
        PkgCategory(R.string.python_cat_algorithms, listOf(
            "numpy", "scipy", "sympy", "mpmath", "fractions", "statistics", "random",
            "networkx", "pydantic", "more-itertools", "z3-solver"
        )),
        PkgCategory(R.string.python_cat_data, listOf(
            "pandas", "numpy", "openpyxl", "xlsxwriter", "pyarrow", "h5py", "tabulate"
        )),
        PkgCategory(R.string.python_cat_ai_ml, listOf(
            "scikit-learn", "torch", "tensorflow", "transformers", "sentence-transformers",
            "onnxruntime", "xgboost", "lightgbm", "catboost", "llama-cpp-python"
        )),
        PkgCategory(R.string.python_cat_nlp, listOf(
            "nltk", "spacy", "jieba", "wordcloud", "textblob", "langdetect"
        )),
        PkgCategory(R.string.python_cat_web, listOf(
            "requests", "httpx", "beautifulsoup4", "lxml", "scrapy", "selenium", "playwright",
            "aiohttp", "websockets", "flask", "fastapi", "uvicorn", "django"
        )),
        PkgCategory(R.string.python_cat_science, listOf(
            "matplotlib", "seaborn", "plotly", "bokeh", "pandas",
            "statsmodels", "lmfit", "uncertainties"
        )),
        PkgCategory(R.string.python_cat_tools, listOf(
            "pip", "setuptools", "wheel", "virtualenv", "pipenv", "poetry",
            "jupyter", "jupyterlab", "ipython", "click", "rich", "colorama",
            "pillow", "imageio", "opencv-python"
        )),
        PkgCategory(R.string.python_cat_cybersecurity, listOf(
            "requests", "scapy", "dpkt", "cryptography", "hashlib", "socket"
        ))
    )

    private fun showPackageCategories() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.python_title_categories))
            .setItems(pkgCategories.map { getString(it.nameRes) }.toTypedArray()) { _, which ->
                val cat = pkgCategories[which]
                val labels = cat.packages.map { pkg -> pkg }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(getString(cat.nameRes))
                    .setItems(labels) { _, idx ->
                        val pkg = cat.packages[idx]
                        AlertDialog.Builder(ctx)
                            .setTitle(getString(R.string.python_title_install_pkg, pkg))
                            .setMessage(getString(R.string.python_msg_confirm_pip_install, pkg))
                            .setPositiveButton(getString(R.string.python_btn_install)) { _, _ ->
                                runPythonAction("pip-install", pkg)
                            }
                            .setNegativeButton(getString(R.string.python_btn_cancel), null)
                            .show()
                    }
                    .setNegativeButton(getString(R.string.python_btn_cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.python_btn_cancel), null)
            .show()
    }

    // Reusa ~/proyectos (misma carpeta que ya usa ExpoFragment.listProjectDirs()) — es donde
    // el resto de la app (Claude/Codex/OpenCode/etc.) ya deja los proyectos del usuario vía
    // "Gestionar proyectos" (symlink/copiar), así que un proyecto Python creado desde
    // cualquier otro módulo aparece acá también, sin tener que reimplementar esa carpeta.
    private fun listProjectDirs(): List<String> {
        val dir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "proyectos")
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isDirectory }.map { it.name }.sorted()
    }

    private fun pickProjectForVenv() {
        Thread {
            val names = try { listProjectDirs() } catch (e: Exception) { emptyList() }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (names.isEmpty()) {
                    Snackbar.make(requireView(), getString(R.string.python_msg_no_projects), Snackbar.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.python_title_choose_project))
                    .setItems(names.toTypedArray()) { _, which ->
                        val path = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "proyectos/${names[which]}").absolutePath
                        showVenvActions(path, names[which])
                    }
                    .setNegativeButton(getString(R.string.python_btn_cancel), null)
                    .show()
            }
        }.start()
    }

    private fun venvDir(projectPath: String) = File(projectPath, ".venv")
    private fun requirementsFile(projectPath: String) = File(projectPath, "requirements.txt")

    private fun showVenvActions(projectPath: String, projectName: String) {
        val hasVenv = venvDir(projectPath).isDirectory
        val hasReqs = requirementsFile(projectPath).isFile
        val actionCreateVenv = getString(R.string.python_action_create_venv)
        val actionInstallReqsVenv = getString(R.string.python_action_install_reqs_venv)
        val actionInstallReqsGlobal = getString(R.string.python_action_install_reqs_global)
        val actionDeleteVenv = getString(R.string.python_action_delete_venv)
        val actions = mutableListOf<String>()
        if (!hasVenv) actions.add(actionCreateVenv)
        if (hasReqs) actions.add(if (hasVenv) actionInstallReqsVenv else actionInstallReqsGlobal)
        if (hasVenv) actions.add(actionDeleteVenv)
        if (actions.isEmpty()) {
            Snackbar.make(requireView(), getString(R.string.python_msg_no_reqs_or_venv, projectName), Snackbar.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(projectName + if (hasVenv) getString(R.string.python_suffix_venv_active) else "")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    actionCreateVenv -> runVenvOp("create-venv", projectPath)
                    actionInstallReqsVenv, actionInstallReqsGlobal -> runVenvOp("install-reqs", projectPath)
                    actionDeleteVenv -> runVenvOp("delete-venv", projectPath)
                }
            }
            .setNegativeButton(getString(R.string.python_btn_cancel), null)
            .show()
    }

    private fun runVenvOp(op: String, projectPath: String) {
        toast("$op…")
        Thread {
            val json = try {
                when (op) {
                    "create-venv" -> buildVenvCreateJson(projectPath)
                    "install-reqs" -> buildVenvInstallReqsJson(projectPath)
                    "delete-venv" -> buildVenvDeleteJson(projectPath)
                    else -> JSONObject().put("ok", false).put("error", getString(R.string.python_error_unknown_action, op))
                }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", friendlyProcessErrorMessage(e, "Python (venv)"))
            }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val ok = json.optBoolean("ok", false)
                val msg = if (ok) json.optString("message", getString(R.string.python_ok)) else getString(R.string.python_error_prefixed, json.optString("error", getString(R.string.python_error_unknown)))
                Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun buildVenvCreateJson(projectPath: String): JSONObject {
        val project = File(projectPath)
        if (!project.isDirectory) return JSONObject().put("ok", false).put("error", getString(R.string.python_error_path_not_exist, projectPath))
        val r = runCommand(listOf("python3", "-m", "venv", "--system-site-packages", ".venv"), 60, workDir = project)
        return if (r.exitCode == 0 && venvDir(projectPath).isDirectory) {
            JSONObject().put("ok", true).put("message", getString(R.string.python_msg_venv_created, projectPath))
        } else {
            JSONObject().put("ok", false).put("error", getString(R.string.python_error_venv_create_failed)).put("output", r.stderr.ifBlank { r.stdout }.takeLast(500))
        }
    }

    private fun buildVenvInstallReqsJson(projectPath: String): JSONObject {
        val reqs = requirementsFile(projectPath)
        if (!reqs.isFile) return JSONObject().put("ok", false).put("error", getString(R.string.python_error_no_reqs_file, projectPath))
        val project = File(projectPath)
        val venvPip = File(venvDir(projectPath), "bin/pip")
        val r = if (venvPip.isFile) {
            runCommand(listOf(venvPip.absolutePath, "install", "-r", "requirements.txt"), 300, workDir = project)
        } else {
            runCommand(listOf("python3", "-m", "pip", "install", "--break-system-packages", "-r", "requirements.txt"), 300, workDir = project)
        }
        return if (r.exitCode == 0) {
            JSONObject().put("ok", true).put("message", getString(R.string.python_msg_reqs_installed)).put("output", r.stdout.takeLast(500))
        } else {
            JSONObject().put("ok", false).put("error", getString(R.string.python_error_install_failed)).put("output", r.stderr.ifBlank { r.stdout }.takeLast(500))
        }
    }

    private fun buildVenvDeleteJson(projectPath: String): JSONObject {
        val venv = venvDir(projectPath)
        if (!venv.isDirectory) return JSONObject().put("ok", false).put("error", getString(R.string.python_error_no_venv, projectPath))
        val deleted = venv.deleteRecursively()
        return if (deleted) {
            JSONObject().put("ok", true).put("message", getString(R.string.python_msg_venv_deleted))
        } else {
            JSONObject().put("ok", false).put("error", getString(R.string.python_error_venv_delete_failed))
        }
    }

    // Mockup aprobado por el usuario 2026-08-26 (ver
    // docs/estructura/ABRIR_TUI_EN_CARPETA_2026-08-26.md), extendido a "todos los CLI" tras
    // corrección explícita del usuario — Python quedó afuera de la ronda original.
    private fun openRepl() {
        // El intent ACTION_VIEW con extra "command" no funcionaba — TermuxActivity nunca
        // lee ese extra (confirmado por grep), así que solo reabría la activity sin
        // ejecutar nada. launchTerminalCommand() es el mecanismo real (BaseModuleFragment).
        promptOpenLocation(
            onDefault = { launchTerminalCommand("python3") },
            onChooseFolder = { path -> launchTerminalCommand("cd '$path' && python3") }
        )
    }

    private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

    // Corre un binario de Termux directo (sin pasar por kairos_manager.py). Lee stdout y
    // stderr en threads separados — si se leyeran en secuencia, un comando que llene el pipe
    // de uno mientras el proceso sigue escribiendo en el otro (ej. pip install con mucho
    // output) puede colgar el proceso hijo esperando que alguien vacíe ese pipe.
    // Resuelve el primer elemento (python3/bash) a su ruta absoluta bajo $PREFIX/bin — bug
    // real confirmado esta sesión (ver docs/humano/humano63.md); el usuario ya había
    // reportado que Python "funciona a medias". Si el elemento YA es una ruta absoluta (ej.
    // el pip de un venv de proyecto, "<proyecto>/.venv/bin/pip") se deja tal cual — anteponer
    // $PREFIX/bin rompería esa ruta (bug real detectado al agregar la gestión de venv: sin
    // este guard, runVenvOp("install-reqs") con venv terminaba invocando una ruta inválida).
    private fun runCommand(cmd: List<String>, timeoutSec: Long, workDir: File? = null): CmdResult {
        return try {
            val resolvedCmd = cmd.toMutableList().also {
                if (!it[0].startsWith("/")) it[0] = "$TERMUX_PREFIX_PATH/bin/${it[0]}"
            }
            val pb = ProcessBuilder(resolvedCmd)
            pb.applyTermuxEnv()
            if (workDir != null) pb.directory(workDir)
            val process = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            // Bug real confirmado por ADB (2026-08-24, ver docs/humano222.md): sin try/catch
            // acá, destroyForcibly() de abajo cierra los streams mientras este Thread está
            // bloqueado en readText() — la excepción sin capturar mata TODO el proceso de la
            // app (mismo patrón real confirmado en ModuleController.startModule()).
            val tOut = Thread { try { stdout.append(process.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
            val tErr = Thread { try { stderr.append(process.errorStream.bufferedReader().readText()) } catch (_: Exception) {} }
            tOut.start(); tErr.start()
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            tOut.join(2000); tErr.join(2000)
            CmdResult(if (finished) process.exitValue() else -1, stdout.toString().trim(), stderr.toString().trim())
        } catch (e: Exception) {
            CmdResult(-1, "", e.message ?: "error")
        }
    }
}
