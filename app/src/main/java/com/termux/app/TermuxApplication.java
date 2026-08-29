package com.termux.app;

import android.app.Application;
import android.content.Context;

import com.termux.BuildConfig;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.module.ModuleManager;

public class TermuxApplication extends Application {

    private static final String LOG_TAG = "TermuxApplication";

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Log interno de Kairos (nivel OFF/NORMAL/FULL, ver ConfigFragment "Log Kairos" —
        // docs/humano231.md) — instala el handler de excepciones no capturadas ANTES de
        // cualquier otra inicialización que pueda fallar, envolviendo al handler de
        // TermuxCrashUtils.setDefaultCrashHandler() de arriba (no lo reemplaza).
        com.termux.app.util.KairosLogger.installUncaughtExceptionHandler(context);
        com.termux.app.util.KairosLogger.log(context, "App", "onCreate() — arrancando TermuxApplication");

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT
        TermuxBootstrap.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        // Init app wide SharedProperties loaded from termux.properties
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context);
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }

        // Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        TermuxShellEnvironment.init(this);

        if (isTermuxFilesDirectoryAccessible) {
            TermuxShellEnvironment.writeEnvironmentToFile(this);
        }

        // Initialize module manager and load module catalog
        ModuleManager.getInstance(this).loadModules();

        // Bridge módulo→app sin polling (patrón adoptado de Podroid, ver
        // docs/referencias/REFERENCIA_PODROID.md) — arranca una sola vez por proceso, acá en vez de en
        // TermuxActivity.onCreate() porque este método corre siempre que el proceso de la app
        // esté vivo (ej. arrancado por el widget flotante o un servicio en background), no
        // solo cuando el usuario abre la Activity principal.
        com.termux.app.util.ModuleEventBridge.start(context);

        // Chequeo periódico (cada 7 días) de actualizaciones del rootfs base — antes solo
        // corría si el usuario tocaba el botón a mano en Ajustes/wizard. No bloquea el
        // arranque (Thread propio adentro) — ver RootfsUpdateScheduler.kt para el detalle.
        com.termux.app.util.RootfsUpdateScheduler.maybeCheckPeriodically(context);
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
