package com.termux.shared.module;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModuleManager {

    private static final String LOG_TAG = "ModuleManager";
    private static final String SCRIPTS_DIR = "kairos_scripts";
    private static final String ASSETS_SCRIPTS = "scripts";
    private static final String GITHUB_RAW = "https://raw.githubusercontent.com";
    private static final String DEFAULT_REPO = "Honkonx/Kairos";
    private static final String DEFAULT_BRANCH = "main";
    private static final String MODULES_JSON = "modules.json";

    private static ModuleManager sInstance;

    private final Context mContext;
    private final Handler mMainHandler;
    private final ExecutorService mExecutor;
    private final List<ModuleDefinition> mModules;
    private final List<ModuleListener> mListeners;
    private final Map<String, Process> mRunningProcesses;
    private final Map<String, StringBuilder> mProcessOutput;
    private File mScriptsDir;

    public interface ModuleListener {
        void onStatusChanged(String moduleId, String status);
        void onLog(String moduleId, String line);
        void onError(String moduleId, String error);
    }

    private ModuleManager(Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadExecutor();
        mModules = new ArrayList<>();
        mListeners = new ArrayList<>();
        mRunningProcesses = new HashMap<>();
        mProcessOutput = new HashMap<>();
        mScriptsDir = new File(mContext.getFilesDir(), SCRIPTS_DIR);
        if (!mScriptsDir.exists()) {
            mScriptsDir.mkdirs();
        }
    }

    public static synchronized ModuleManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ModuleManager(context);
        }
        return sInstance;
    }

    public void addListener(ModuleListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(ModuleListener listener) {
        mListeners.remove(listener);
    }

    public List<ModuleDefinition> getModules() {
        return mModules;
    }

    public ModuleDefinition getModule(String id) {
        for (ModuleDefinition m : mModules) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    public String getModuleOutput(String moduleId) {
        StringBuilder sb = mProcessOutput.get(moduleId);
        return sb != null ? sb.toString() : "";
    }

    public void loadModules() {
        mExecutor.execute(() -> {
            try {
                InputStream is = mContext.getAssets().open(MODULES_JSON);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONArray arr = new JSONArray(sb.toString());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    ModuleDefinition def = new ModuleDefinition(
                        obj.getString("id"),
                        obj.optString("name", obj.getString("id")),
                        obj.optString("description", ""),
                        obj.optString("repo", DEFAULT_REPO),
                        obj.optString("script", "install_" + obj.getString("id") + ".sh"),
                        obj.optString("icon", "")
                    );
                    mModules.add(def);
                }
                Logger.logInfo(LOG_TAG, "Loaded " + mModules.size() + " modules from assets");
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to load modules from assets: " + e.getMessage());
            }
        });
    }

    /**
     * Copies a script from assets to the scripts directory.
     * Returns the script file, or null on failure.
     */
    private File copyScriptFromAssets(String assetPath) {
        try {
            File outFile = new File(mScriptsDir, assetPath);
            if (outFile.exists()) return outFile;

            InputStream in = mContext.getAssets().open(ASSETS_SCRIPTS + "/" + assetPath);
            OutputStream out = new FileOutputStream(outFile);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            outFile.setExecutable(true);
            Logger.logInfo(LOG_TAG, "Copied script from assets: " + assetPath);
            return outFile;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to copy script from assets: " + assetPath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Downloads a script from GitHub to the scripts directory.
     * Returns the script file, or null on failure.
     */
    private File downloadScript(String repo, String scriptName) {
        try {
            File outFile = new File(mScriptsDir, scriptName);
            String url = GITHUB_RAW + "/" + repo + "/" + DEFAULT_BRANCH + "/scripts/" + scriptName;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            if (conn.getResponseCode() == 200) {
                InputStream in = conn.getInputStream();
                OutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.close();
                in.close();
                outFile.setExecutable(true);
                Logger.logInfo(LOG_TAG, "Downloaded script: " + scriptName);
            }
            conn.disconnect();
            return outFile.exists() ? outFile : null;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to download script " + scriptName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Ensures a script file is available, trying assets first, then GitHub.
     */
    private File ensureScript(String moduleId) {
        ModuleDefinition mod = getModule(moduleId);
        if (mod == null) return null;

        File scriptFile = new File(mScriptsDir, mod.script);

        // Try cached copy first
        if (scriptFile.exists()) return scriptFile;

        // Try assets
        scriptFile = copyScriptFromAssets(mod.script);
        if (scriptFile != null && scriptFile.exists()) return scriptFile;

        // Fallback to GitHub
        return downloadScript(mod.repo, mod.script);
    }

    /**
     * Executes a given script file and tracks its process.
     */
    private void executeScript(String moduleId, File scriptFile) {
        ModuleDefinition mod = getModule(moduleId);
        if (mod == null) return;

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", scriptFile.getAbsolutePath());
            pb.directory(mContext.getFilesDir());
            pb.environment().put("HOME", mContext.getFilesDir().getAbsolutePath());
            pb.environment().put("PREFIX", mContext.getFilesDir().getAbsolutePath() + "/usr");
            pb.environment().put("TERMUX_APP_NAME", "Kairos");

            Process process = pb.start();
            mRunningProcesses.put(moduleId, process);
            mProcessOutput.put(moduleId, new StringBuilder());

            mod.setStatus("running");
            notifyStatus(moduleId, "running");

            BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;

            while ((line = stdout.readLine()) != null) {
                mProcessOutput.get(moduleId).append(line).append("\n");
                notifyLog(moduleId, line);
            }
            while ((line = stderr.readLine()) != null) {
                mProcessOutput.get(moduleId).append("[ERR] ").append(line).append("\n");
                notifyLog(moduleId, "[ERR] " + line);
            }

            int exitCode = process.waitFor();
            mRunningProcesses.remove(moduleId);

            if (exitCode == 0) {
                mod.setStatus("installed");
                mod.setVersion("1.0");
                notifyStatus(moduleId, "installed");
            } else {
                mod.setStatus("error");
                String err = mProcessOutput.get(moduleId) != null
                    ? mProcessOutput.get(moduleId).toString().trim()
                    : "Exit code " + exitCode;
                mod.setError(err);
                notifyError(moduleId, err);
            }

        } catch (Exception e) {
            mRunningProcesses.remove(moduleId);
            mod.setStatus("error");
            mod.setError("Execution failed: " + e.getMessage());
            notifyError(moduleId, mod.getError());
        }
    }

    /**
     * Install (first run) a module: get its script and run it.
     */
    public void installModule(String moduleId, ModuleListener listener) {
        addListener(listener);
        mExecutor.execute(() -> {
            ModuleDefinition mod = getModule(moduleId);
            if (mod == null) {
                notifyError(moduleId, "Module not found");
                return;
            }

            mod.setStatus("installing");
            notifyStatus(moduleId, "installing");

            File scriptFile = ensureScript(moduleId);
            if (scriptFile == null || !scriptFile.exists()) {
                mod.setStatus("error");
                mod.setError("Script not available");
                notifyError(moduleId, "Script not available");
                return;
            }

            executeScript(moduleId, scriptFile);
        });
    }

    /**
     * Run an already-installed module (re-execute its script).
     */
    public void runModule(String moduleId, ModuleListener listener) {
        addListener(listener);
        mExecutor.execute(() -> {
            ModuleDefinition mod = getModule(moduleId);
            if (mod == null) {
                notifyError(moduleId, "Module not found");
                return;
            }
            if (mRunningProcesses.containsKey(moduleId)) {
                notifyError(moduleId, "Module already running");
                return;
            }

            File scriptFile = ensureScript(moduleId);
            if (scriptFile == null || !scriptFile.exists()) {
                mod.setStatus("error");
                mod.setError("Script not available for " + moduleId);
                notifyError(moduleId, mod.getError());
                return;
            }

            executeScript(moduleId, scriptFile);
        });
    }

    /**
     * Stop a running module.
     */
    public void stopModule(String moduleId) {
        Process p = mRunningProcesses.get(moduleId);
        if (p != null) {
            p.destroy();
            mRunningProcesses.remove(moduleId);
            ModuleDefinition mod = getModule(moduleId);
            if (mod != null) {
                mod.setStatus("installed");
                notifyStatus(moduleId, "installed");
            }
        }
    }

    /**
     * Alias for stopModule — kept for compatibility.
     */
    public void cancelInstall(String moduleId) {
        stopModule(moduleId);
    }

    private void notifyStatus(String moduleId, String status) {
        mMainHandler.post(() -> {
            for (ModuleListener l : mListeners) {
                l.onStatusChanged(moduleId, status);
            }
        });
    }

    private void notifyLog(String moduleId, String line) {
        mMainHandler.post(() -> {
            for (ModuleListener l : mListeners) {
                l.onLog(moduleId, line);
            }
        });
    }

    private void notifyError(String moduleId, String error) {
        mMainHandler.post(() -> {
            for (ModuleListener l : mListeners) {
                l.onError(moduleId, error);
            }
        });
    }
}
