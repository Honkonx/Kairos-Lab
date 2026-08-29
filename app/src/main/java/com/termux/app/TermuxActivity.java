package com.termux.app;

import com.termux.app.util.ModuleEventBridge;
import com.termux.app.util.PermissionManager;
import com.termux.app.util.SessionPromptDetector;
import com.termux.app.wizard.WizardActivity;

import com.termux.app.ui.ModulesFragment;
import com.termux.app.ui.PluginsFragment;
import com.termux.app.ui.ChatFragment;
import com.termux.app.ui.MonitorFragment;
import com.termux.app.ui.FileManagerFragment;
import com.termux.app.ui.TunnelFragment;
import com.termux.app.ui.ConfigFragment;

import com.termux.app.ui.NubeFragment;
import com.termux.app.ui.EntornoFragment;

import com.termux.app.ui.studio.StudioFragment;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.termux.shared.view.KeyboardUtils;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.content.SharedPreferences;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private ModulesFragment mModulesFragment;
    private ChatFragment mChatFragment;
    private MonitorFragment mMonitorFragment;
    private FileManagerFragment mFileManagerFragment;
    private TunnelFragment mTunnelFragment;
    private NubeFragment mNubeFragment;
    private PluginsFragment mPluginsFragment;
    private ConfigFragment mSettingsFragment;
    private StudioFragment mStudioFragment;
    private EntornoFragment mEntornoFragment;
    private Fragment mCurrentFragment;
    private int mCurrentTabId;
    private View mTerminalOverlay;

    /**
     * Modo "terminal adaptada" — activo cuando el overlay de terminal se abrió para un CLI
     * de módulo (openTerminalWithCommand con sessionName no nulo: OpenCode/Claude/Codex/
     * Antigravity/Hermes/OpenClaw), a diferencia de la terminal normal de Termux (abierta con
     * el FAB, sin sessionName). En este modo se oculta el toolbar de teclas extra y se bloquea
     * el drawer de sesiones — esas dos cosas son propias de la terminal "pura" de Termux y no
     * tienen sentido para un CLI específico — y se muestra una barra propia con
     * Minimizar/Cerrar (ver applyTerminalModeUi()).
     */
    private boolean mTerminalAdaptedMode = false;
    /** Refleja si la paleta Tokyo Night está aplicada AHORA MISMO (ver applyAdaptedTerminalColors()) —
     *  necesario para no repetir el swap/backup cada vez que applyTerminalModeUi() corre sin que
     *  el modo haya cambiado de verdad (se llama desde varios puntos de entrada). */
    private boolean mAdaptedColorsActive = false;
    private String mTerminalAdaptedSessionName;

    // Loop de refresco de métricas CPU/RAM del panel de estado adaptado (ver
    // refreshAdaptedBarInfo()/startAdaptedMetricsLoop()) — pedido 2026-08-25, panel de estado
    // con métricas en vivo. Un solo Handler reusado entre inicio/parada, nunca instanciado más
    // de una vez, para poder cancelar el Runnable pendiente con removeCallbacks() sin perder la
    // referencia.
    private final android.os.Handler mAdaptedMetricsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long ADAPTED_METRICS_INTERVAL_MS = 3000L;

    // Command palette del sidebar adaptado (ver populateAdaptedDrawerContent()/
    // applyAdaptedDrawerFilter()) — pedido 2026-08-25. mAdaptedDrawerFilterText sobrevive a
    // populateAdaptedDrawerContent() reconstruyendo las filas (actions.removeAllViews()) para
    // que el filtro siga aplicado tras un refresh del sidebar (ej. al cambiar de módulo);
    // mAdaptedDrawerSearchWired evita agregar el TextWatcher más de una vez sobre el mismo
    // EditText (populateAdaptedDrawerContent() se llama repetidas veces sobre el mismo overlay).
    private String mAdaptedDrawerFilterText = "";
    private boolean mAdaptedDrawerSearchWired = false;
    private String mAdaptedDrawerLastSession;

    /**
     * Seteado brevemente por openTerminalWithCommand() justo antes de togglear el overlay para
     * que el bloque de "primera sesión genérica" dentro de toggleTerminalOverlay() (ver su
     * mTerminalView.post() interno) no cree/adjunte una sesión sin nombre y sin comando de más
     * — openTerminalWithCommand() ya va a crear/adjuntar la sesión correcta (con nombre y
     * comando) apenas termine el layout. Bug real encontrado: sin este flag, cada vez que se
     * abría un CLI de módulo con el overlay todavía cerrado se creaba una sesión huérfana
     * genérica además de la nombrada — inflaba el drawer de sesiones sin que el usuario la
     * pidiera y acercaba a MAX_SESSIONS=8 más rápido de lo esperado.
     */
    private boolean mSkipNextOverlayAutoSession = false;

    /**
     * Notificación real cuando una sesión de terminal nombrada (CLI de módulo — Claude Code,
     * Codex, OpenCode, etc., abierta vía openTerminalWithCommand con sessionName) produce
     * output nuevo mientras el usuario NO la está mirando (cambió de tab, minimizó el overlay,
     * o salió de la app entera) — patrón adoptado de whispercode-dev (ver
     * docs/referencias/REFERENCIA_WHISPERCODE.md), pedido explícito del usuario 2026-08-01. Reutiliza el
     * mecanismo de notificaciones que ya existe para "módulo caído" (ModulesFragment.
     * notifyModuleStopped, misma preferencia "pref_notify_modules").
     *
     * Heurística: cada onTextChanged() de una sesión nombrada en background reinicia un timer
     * de debounce por sesión (mSessionIdleCheckRunnables) — si no llega output nuevo en
     * SESSION_IDLE_NOTIFY_DELAY_MS, se asume que el CLI terminó de generar o quedó esperando
     * input, y se notifica UNA vez por ráfaga (mSessionAlreadyNotifiedForBurst evita spam si
     * el usuario sigue sin volver y el proceso sigue tirando output esporádico).
     */
    private static final long SESSION_IDLE_NOTIFY_DELAY_MS = 2500;
    private static final String EXTRA_FOCUS_SESSION_NAME = "com.termux.app.FOCUS_SESSION_NAME";
    private final Map<String, Runnable> mSessionIdleCheckRunnables = new HashMap<>();
    private final Set<String> mSessionAlreadyNotifiedForBurst = new HashSet<>();

    private int mNavBarHeight;

    private final ActivityResultLauncher<String> mNotifPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("notif_permission_requested", true).apply();
            if (granted) {
                com.google.android.material.snackbar.Snackbar.make(
                    findViewById(R.id.kairos_root),
                    "✓ Notificaciones activadas",
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
            }
        });

    private float mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        if (mProperties == null) {
            Logger.logError(LOG_TAG, "SharedProperties is null, skipping property-dependent setup");
        } else {
            reloadProperties();
        }

        setActivityTheme();

        super.onCreate(savedInstanceState);

        // Bug real, 4to intento (2026-08-07, ver docs/humano/humano89.md): revertido del
        // edge-to-edge manual (setDecorFitsSystemWindows(false) + 2 listeners de padding vía
        // WindowInsetsCompat, agregados en una ronda anterior) al modelo clásico que usa
        // termux-app real y todos los forks de referencia comparados — ninguno reimplementa
        // insets a mano, todos dejan que SOFT_INPUT_ADJUST_RESIZE haga el resize automático.
        // Se activa acá temprano (además de en TermuxTerminalViewClient.setSoftKeyboardState(),
        // que solo corre una vez que se abre la terminal) porque esta Activity también hospeda
        // pantallas fuera de la terminal (Chat, Módulos, etc. vía fragment_container) que
        // necesitan el mismo comportamiento de resize aunque el usuario nunca haya abierto la
        // terminal en la sesión — algo que termux-app real no necesita resolver porque ahí la
        // Activity ES la terminal, sin pantallas adicionales en el mismo Window.
        KeyboardUtils.setSoftInputModeAdjustResize(this);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        // Mostrar wizard si no se ha completado el setup
        if (!KairosBootstrap.isReady()) {
            // Ensure storage permission is requested before wizard
            com.termux.app.util.PermissionManager.requestAll(this);
            Intent intent = new Intent(this, WizardActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        // Autorreparación: si scripts/install/ quedó vacío por una extracción previa
        // fallida (ver KairosBootstrap.isAlreadyExtracted), esto lo vuelve a poblar.
        // El wizard (única otra llamada a extractAssets) se salta por completo una vez
        // que .kairos_ready existe, así que sin esto nunca se autorreparaba.
        KairosBootstrap.extractAssets(this);

        // Set up native shell layout with BottomNavigationView
        setContentView(R.layout.activity_kairos);

        // Request notification permission on first launch (with explanation dialog)
        SharedPreferences prefs = getSharedPreferences("kairos_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean("notif_permission_requested", false)) {
            requestNotificationPermission();
        }

        // "Auto-iniciar módulos" (Ajustes) — retoma al abrir la app los módulos con
        // switch que ya estaban instalados. No es un boot receiver real, ver
        // ModuleController.autoStartEligibleModules() para el alcance exacto.
        if (prefs.getBoolean("pref_auto_start", false)) {
            ModuleController.autoStartEligibleModules(getApplicationContext());
        }

        try {
            initializeAppUi(savedInstanceState);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "UI init error: " + e.getMessage());
            showToast("Error al cargar interfaz", true);
            mIsInvalidState = true;
        }
    }

    private void initializeAppUi(Bundle savedInstanceState) {
        // Setup fragments (only if not restoring from saved state)
        if (savedInstanceState == null) {
            mModulesFragment = new ModulesFragment();
            mChatFragment = new ChatFragment();
            mMonitorFragment = new MonitorFragment();
            mFileManagerFragment = new FileManagerFragment();
            mTunnelFragment = new TunnelFragment();
            mSettingsFragment = new ConfigFragment();
            mNubeFragment = new NubeFragment();
            mPluginsFragment = new PluginsFragment();
            mStudioFragment = new StudioFragment();
            mEntornoFragment = new EntornoFragment();

            getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, mModulesFragment, "modules")
                .add(R.id.fragment_container, mChatFragment, "chat")
                .add(R.id.fragment_container, mMonitorFragment, "monitor")
                .add(R.id.fragment_container, mFileManagerFragment, "files")
                .add(R.id.fragment_container, mTunnelFragment, "tunnel")
                .add(R.id.fragment_container, mSettingsFragment, "settings")
                .add(R.id.fragment_container, mNubeFragment, "nube")
                .add(R.id.fragment_container, mPluginsFragment, "plugins")
                .add(R.id.fragment_container, mStudioFragment, "studio")
                .add(R.id.fragment_container, mEntornoFragment, "entorno")
                .hide(mChatFragment)
                .hide(mMonitorFragment)
                .hide(mFileManagerFragment)
                .hide(mTunnelFragment)
                .hide(mSettingsFragment)
                .hide(mNubeFragment)
                .hide(mPluginsFragment)
                .hide(mStudioFragment)
                .hide(mEntornoFragment)
                .commit();

            mCurrentFragment = mModulesFragment;
            mCurrentTabId = R.id.nav_modules;
        } else {
            mModulesFragment = (ModulesFragment) getSupportFragmentManager().findFragmentByTag("modules");
            mChatFragment = (ChatFragment) getSupportFragmentManager().findFragmentByTag("chat");
            mMonitorFragment = (MonitorFragment) getSupportFragmentManager().findFragmentByTag("monitor");
            mFileManagerFragment = (FileManagerFragment) getSupportFragmentManager().findFragmentByTag("files");
            mTunnelFragment = (TunnelFragment) getSupportFragmentManager().findFragmentByTag("tunnel");
            mSettingsFragment = (ConfigFragment) getSupportFragmentManager().findFragmentByTag("settings");
            mNubeFragment = (NubeFragment) getSupportFragmentManager().findFragmentByTag("nube");
            mPluginsFragment = (PluginsFragment) getSupportFragmentManager().findFragmentByTag("plugins");
            mStudioFragment = (StudioFragment) getSupportFragmentManager().findFragmentByTag("studio");
            mEntornoFragment = (EntornoFragment) getSupportFragmentManager().findFragmentByTag("entorno");
            // Bug real confirmado por ADB (humano202, 2026-08-22 — freeze al cambiar de tema,
            // reproducido en vivo con uiautomator+logcat): esto ANTES reseteaba mCurrentFragment/
            // mCurrentTabId a Módulos sin condición, incluso cuando el usuario estaba parado en
            // OTRA pantalla (ej. Config) al momento de recreate() (el cambio de tema llama
            // requireActivity().recreate()). FragmentManager restaura el estado hidden/visible
            // real de cada fragment por su cuenta, así que la pantalla seguía viéndose bien —
            // pero switchFragment()/el listener de BottomNavigationView comparan contra
            // mCurrentFragment/mCurrentTabId, que quedaban apuntando a Módulos aunque Módulos
            // siguiera oculto: tocar CUALQUIER tab (incluido "Módulos") se volvía un no-op
            // silencioso ("itemId == mCurrentTabId" o "target == mCurrentFragment"), exactamente
            // el freeze reportado ("no me deja salir hasta que reabra la app"). Fix: detectar cuál
            // de los fragments restaurados es el que NO está oculto y usar ESE como actual.
            mCurrentFragment = mModulesFragment;
            mCurrentTabId = R.id.nav_modules;
            Fragment[] restoredFragments = {
                mModulesFragment, mChatFragment, mMonitorFragment,
                mFileManagerFragment, mTunnelFragment, mSettingsFragment, mNubeFragment,
                mPluginsFragment, mStudioFragment, mEntornoFragment
            };
            int[] restoredTabIds = {
                R.id.nav_modules, R.id.nav_chat, R.id.nav_monitor,
                R.id.nav_files, R.id.nav_tunnel, R.id.nav_settings, R.id.nav_nube,
                R.id.nav_plugins, R.id.nav_studio, R.id.nav_minipc
            };
            for (int i = 0; i < restoredFragments.length; i++) {
                Fragment f = restoredFragments[i];
                if (f != null && !f.isHidden()) {
                    mCurrentFragment = f;
                    mCurrentTabId = restoredTabIds[i];
                    break;
                }
            }
        }

        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_more) {
                showMoreNavMenu(bottomNav);
                return false;
            }
            if (itemId == mCurrentTabId) return false;
            switchFragment(itemId);
            return true;
        });

        FloatingActionButton fab = findViewById(R.id.fab_terminal);
        fab.setOnClickListener(v -> {
            // El FAB solo es visible cuando el overlay está oculto (ver toggleTerminalOverlay()),
            // así que este click siempre es una apertura, nunca un minimizado — es el punto
            // correcto para forzar el modo "terminal normal" (sin barra adaptada, con toolbar
            // de teclas extra y drawer de sesiones), sin importar qué modo haya quedado activo
            // la última vez que se usó el overlay para un CLI de módulo.
            mTerminalAdaptedMode = false;
            mTerminalAdaptedSessionName = null;
            toggleTerminalOverlay();
        });

        if (mProperties != null && mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        registerTermuxActivityBroadcastReceiver();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        // Mismo wiring que termux-app real (ver docs/humano/humano89.md) — reactiva el
        // mecanismo de margen de TermuxActivityRootView.onGlobalLayout() (caso borde de
        // teclados con fila de sugerencias, ver el JavaDoc completo de esa clase) cada vez
        // que la Activity vuelve a estar visible. Null-safe porque, a diferencia de
        // termux-app real (donde este root view ES el contentView de toda la Activity, ya
        // existe en onCreate()), en Kairos activity_termux_root_view recién existe una vez
        // que se abre el overlay de terminal por primera vez (ver toggleTerminalOverlay()) —
        // en un onStart() anterior a esa primera apertura, mTermuxActivityRootView sigue null.
        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();

        Logger.logVerbose(LOG_TAG, "onPause");

        if (mIsInvalidState) return;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        unregisterTermuxActivityBroadcastReceiver();

        // Antes solo se cancelaban desde onBackgroundSessionOutput() (reemplazo por ráfaga
        // nueva) — un postDelayed(2500ms) que quedaba pendiente al salir de la Activity podía
        // disparar sobre una sesión/vista ya en un estado inconsistente. Ver AUDITORIA_CODIGO_
        // 2026-08-13.md §1.12.
        cancelAllSessionIdleChecks();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
    }

    @Override
    public void onBackPressed() {
        if (mTerminalOverlay != null && mTerminalOverlay.getVisibility() == View.VISIBLE) {
            toggleTerminalOverlay();
            return;
        }
        DrawerLayout drawer = getDrawer();
        if (drawer != null && drawer.isDrawerOpen(Gravity.LEFT)) {
            drawer.closeDrawers();
            return;
        }
        // Bug real reportado: al tocar atrás desde el detalle de un módulo (ej.
        // OpenCodeFragment, abierto vía BaseModuleFragment.navigateTo() con
        // addToBackStack(null)) la app se cerraba directo en vez de volver a la lista
        // de Módulos — nunca se chequeaba si había algo en el back stack de fragments
        // antes de salir.
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return;
        }
        // Bug real reportado (ver docs/humano231.md): con backstack ya vacío (switchFragment()
        // lo vacía en CADA cambio de tab, ver comentario en esa función más abajo), si el
        // usuario abrió el detalle de un módulo desde "Módulos", cambió a otro tab, y tocó
        // atrás parado en la raíz de ESE tab, no había nada que popear y la app se cerraba
        // directo en vez de volver al tab Módulos. Fix: si el tab actual no es Módulos, volver
        // ahí (consumiendo el evento) en vez de cerrar la app; solo se cierra si ya se estaba
        // parado en Módulos sin backstack.
        if (mCurrentTabId != R.id.nav_modules) {
            // Bug real confirmado en dispositivo (ADB, docs/humano246.md #4): llamar
            // switchFragment(R.id.nav_modules) ACÁ y recién después bottomNav.setSelectedItemId()
            // dejaba el contenido correcto (Módulos) pero el resaltado visual del
            // BottomNavigationView atascado en el tab anterior — switchFragment() ya deja
            // mCurrentTabId = nav_modules (línea ~1693), así que cuando setSelectedItemId()
            // dispara el listener de arriba (línea ~516), "itemId == mCurrentTabId" es TRUE y
            // el listener devuelve false sin marcar la selección visual. Fix: no llamar
            // switchFragment() a mano acá — solo setSelectedItemId(), dejando que el propio
            // listener (que compara contra el mCurrentTabId TODAVÍA viejo en ese momento) haga
            // el switchFragment() y devuelva true, actualizando contenido Y resaltado juntos.
            BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
            if (bottomNav != null) {
                bottomNav.setSelectedItemId(R.id.nav_modules);
            } else {
                switchFragment(R.id.nav_modules);
            }
            return;
        }
        finishActivityIfNotFinishing();
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        try {
            mTermuxService = ((TermuxService.LocalBinder) service).service;

            // Setup bootstrap if needed (background) and create default session
            if (mTermuxService != null && mTermuxService.isTermuxSessionsEmpty()) {
                if (mIsVisible) {
                    TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                        if (mTermuxService == null) return;
                        // Create default session after bootstrap completes
                        if (mTermuxService.isTermuxSessionsEmpty()) {
                            mTermuxService.createTermuxSession(null, null, null, null, false, null);
                        }
                    });
                }
            }
            // If terminal overlay is already inflated, ensure its clients are set up
            if (mTerminalOverlay != null && mTerminalView != null) {
                setTermuxTerminalViewAndClients();
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "onServiceConnected error: " + e.getMessage());
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Reenvío de atajos Ctrl+<tecla> del teclado físico/Bluetooth hacia StudioFragment (tab
     * "Más" → Estudio, IDE integrado). Un Fragment no recibe el hook onKeyShortcut de Activity,
     * así que TermuxActivity intercepta acá cada KeyEvent cuando el Estudio es el tab activo y
     * le ofrece el evento a StudioFragment.handleKeyShortcut(); si lo consume (Ctrl+S guardar,
     * Ctrl+P paleta, etc.) se detiene la propagación. Cualquier otro evento devuelve false y
     * sigue el flujo normal (terminal, vista con foco, etc.). Ver
     * docs/ide/IDE_INTEGRADO.md y StudioFragment.handleKeyShortcut().
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mCurrentFragment == mStudioFragment && mStudioFragment != null) {
            if (mStudioFragment.handleKeyShortcut(event)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Tap en la notificación de "sesión necesita atención" (ver onBackgroundSessionOutput) —
        // launchMode="singleTask" (AndroidManifest) hace que esto reentre acá en vez de crear
        // una Activity nueva, así que reabrir la sesión correcta alcanza con reusar
        // openTerminalWithCommand(null, sessionName) — mismo camino que ya usa
        // isTerminalSessionActive()/findSessionByName() para reenfocar una sesión existente.
        String focusSessionName = intent.getStringExtra(EXTRA_FOCUS_SESSION_NAME);
        if (focusSessionName != null && !focusSessionName.isEmpty()) {
            openTerminalWithCommand(null, focusSessionName);
        }
    }






    private void reloadProperties() {
        if (mProperties == null) return;
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        // Selector de tema visual Kairos (2026-08-22, ver docs/humano/humano190.md) — Oscuro/Señal/
        // Claro, ver com.termux.app.util.KairosThemePrefs. Debe llamarse ANTES de
        // super.onCreate() (mismo requisito que setNightMode arriba) para que ?attr/kairos*
        // resuelva contra el tema elegido desde el primer layout inflado.
        com.termux.app.util.KairosThemePrefs.INSTANCE.applyTheme(this);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        if (relativeLayout == null) return;
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        // Null-safe (ver comentario en onStart(), docs/humano/humano89.md) — termux-app real no
        // necesita este guard porque ahí el root view existe desde onCreate().
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Register Activity client with service so sessions use it (not the service-only stub).
        // Without this, onTextChanged() is never forwarded to the view → no invalidate().
        if (mTermuxService != null) {
            mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
        }

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        // Antes, si getExtraKeysInfo() devolvía null (matrix corrupta/vacía tras un
        // JSONException al parsear "extra-keys"), el multiplicador quedaba en 0 y el
        // contenedor del toolbar colapsaba a altura 0 para siempre — las teclas seguían
        // ahí (ExtraKeysView.reload() las agrega igual) pero sin espacio para dibujarse,
        // lo cual en la práctica es indistinguible de un toolbar "roto". Usamos 1 como
        // piso para nunca perder por completo la fila de extra-keys, y ExtraKeysInfo
        // siempre trae al menos 1 fila cuando no es null, así que este piso es solo la
        // red de seguridad para el caso null.
        int rowCount = mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 1 :
            Math.max(1, mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight * rowCount *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setTerminalQuickSettingsButtonView() {
        findViewById(R.id.terminal_quick_settings_button).setOnClickListener(v -> {
            DrawerLayout drawer = getDrawer();
            if (drawer != null) drawer.closeDrawers();
            showTerminalQuickSettingsDialog();
        });
    }

    // Nombres de los temas curados de showTerminalThemePickerDialog() — separado
    // en su propio arreglo para poder mostrar la lista y resolver el nombre
    // elegido a sus colores reales (ver getTerminalThemeColorsProperties()) sin
    // duplicar la lista dos veces.
    private static final String[] TERMINAL_THEME_NAMES = {
        "Dracula", "Nord", "Gruvbox Dark", "Solarized Dark",
        "One Dark", "Monokai", "Tokyo Night", "Catppuccin Mocha"
    };

    private void showTerminalQuickSettingsDialog() {
        if (mTerminalView == null || mPreferences == null) return;

        int[] sizes = TermuxAppSharedPreferences.getDefaultFontSizes(this);
        final int minSize = sizes[1];
        final int maxSize = sizes[2];
        int currentSize = mPreferences.getFontSize();

        int density = (int) getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(16 * density, 16 * density, 16 * density, 16 * density);

        final TextView label = new TextView(this);
        label.setText(getString(R.string.label_font_size) + ": " + currentSize + "sp");
        container.addView(label);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(maxSize - minSize);
        seekBar.setProgress(currentSize - minSize);
        container.addView(seekBar);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int newSize = minSize + progress;
                label.setText(getString(R.string.label_font_size) + ": " + newSize + "sp");
                if (fromUser) {
                    mTerminalView.setTextSize(newSize);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mPreferences.setFontSize(minSize + seekBar.getProgress());
            }
        });

        // Selector de tema — 2026-07-28, pedido explícito del usuario (patrón de
        // UX inspirado en Podroid-main, ver docs/arquitectura/QUIZAS.md sección 11 — no se
        // copió código, es GPLv2, solo el concepto de "botón que abre lista de
        // temas"). Escribe ~/.termux/colors.properties (formato real que
        // termux-app ya sabe leer) y dispara el mismo broadcast de recarga de
        // estilo que ya usa el resto del motor (updateTermuxActivityStyling()).
        View themeDivider = new View(this);
        themeDivider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, density * 2));
        container.addView(themeDivider);

        SharedPreferences kairosPrefs = getSharedPreferences("kairos_prefs", MODE_PRIVATE);
        String currentTheme = kairosPrefs.getString("terminal_theme", "");

        final TextView themeLabel = new TextView(this);
        themeLabel.setText(getString(R.string.label_terminal_theme) + ": " + (currentTheme.isEmpty() ? getString(R.string.label_terminal_theme_default) : currentTheme));
        themeLabel.setPadding(0, 16 * density, 0, 0);
        container.addView(themeLabel);

        TextView themeButton = new TextView(this);
        themeButton.setText(R.string.action_change_terminal_theme);
        themeButton.setPadding(0, 8 * density, 0, 0);
        themeButton.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light));
        themeButton.setOnClickListener(v -> showTerminalThemePickerDialog(themeLabel));
        container.addView(themeButton);

        new AlertDialog.Builder(this)
            .setTitle(R.string.title_terminal_quick_settings)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /** Muestra la lista de temas curados — al elegir uno, lo aplica y actualiza themeLabel. */
    private void showTerminalThemePickerDialog(final TextView themeLabel) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.title_terminal_theme_picker)
            .setItems(TERMINAL_THEME_NAMES, (dialog, which) -> {
                String themeName = TERMINAL_THEME_NAMES[which];
                applyTerminalTheme(themeName);
                if (themeLabel != null) {
                    themeLabel.setText(getString(R.string.label_terminal_theme) + ": " + themeName);
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Escribe ~/.termux/colors.properties con los colores del tema elegido y
     * dispara la recarga real de estilo del motor (mismo broadcast que ya usa
     * TermuxService.createTermuxSession(), ver updateTermuxActivityStyling()).
     * recreateActivity=false — mismo criterio que TermuxService: es solo un
     * cambio de colores, no hace falta recrear toda la Activity.
     */
    private void applyTerminalTheme(String themeName) {
        try {
            File colorsFile = new File(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE_PATH);
            File colorsDir = colorsFile.getParentFile();
            if (colorsDir != null && !colorsDir.exists()) colorsDir.mkdirs();
            FileWriter writer = new FileWriter(colorsFile, false);
            writer.write(getTerminalThemeColorsProperties(themeName));
            writer.close();
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "No se pudo escribir colors.properties para el tema " + themeName, e);
            Logger.showToast(this, getString(R.string.error_terminal_theme_write_failed), true);
            return;
        }
        getSharedPreferences("kairos_prefs", MODE_PRIVATE).edit()
            .putString("terminal_theme", themeName)
            .apply();
        TermuxActivity.updateTermuxActivityStyling(this, false);
    }

    /** Valores reales (background/foreground/cursor/color0-15) de cada tema curado. */
    private static String getTerminalThemeColorsProperties(String themeName) {
        switch (themeName) {
            case "Dracula":
                return "background=#282a36\nforeground=#f8f8f2\ncursor=#f8f8f2\n" +
                    "color0=#000000\ncolor1=#ff5555\ncolor2=#50fa7b\ncolor3=#f1fa8c\n" +
                    "color4=#bd93f9\ncolor5=#ff79c6\ncolor6=#8be9fd\ncolor7=#bfbfbf\n" +
                    "color8=#4d4d4d\ncolor9=#ff6e6e\ncolor10=#69ff94\ncolor11=#ffffa5\n" +
                    "color12=#d6acff\ncolor13=#ff92df\ncolor14=#a4ffff\ncolor15=#ffffff\n";
            case "Nord":
                return "background=#2e3440\nforeground=#d8dee9\ncursor=#d8dee9\n" +
                    "color0=#3b4252\ncolor1=#bf616a\ncolor2=#a3be8c\ncolor3=#ebcb8b\n" +
                    "color4=#81a1c1\ncolor5=#b48ead\ncolor6=#88c0d0\ncolor7=#e5e9f0\n" +
                    "color8=#4c566a\ncolor9=#bf616a\ncolor10=#a3be8c\ncolor11=#ebcb8b\n" +
                    "color12=#81a1c1\ncolor13=#b48ead\ncolor14=#8fbcbb\ncolor15=#eceff4\n";
            case "Gruvbox Dark":
                return "background=#282828\nforeground=#ebdbb2\ncursor=#ebdbb2\n" +
                    "color0=#282828\ncolor1=#cc241d\ncolor2=#98971a\ncolor3=#d79921\n" +
                    "color4=#458588\ncolor5=#b16286\ncolor6=#689d6a\ncolor7=#a89984\n" +
                    "color8=#928374\ncolor9=#fb4934\ncolor10=#b8bb26\ncolor11=#fabd2f\n" +
                    "color12=#83a598\ncolor13=#d3869b\ncolor14=#8ec07c\ncolor15=#ebdbb2\n";
            case "Solarized Dark":
                return "background=#002b36\nforeground=#839496\ncursor=#839496\n" +
                    "color0=#073642\ncolor1=#dc322f\ncolor2=#859900\ncolor3=#b58900\n" +
                    "color4=#268bd2\ncolor5=#d33682\ncolor6=#2aa198\ncolor7=#eee8d5\n" +
                    "color8=#002b36\ncolor9=#cb4b16\ncolor10=#586e75\ncolor11=#657b83\n" +
                    "color12=#839496\ncolor13=#6c71c4\ncolor14=#93a1a1\ncolor15=#fdf6e3\n";
            case "One Dark":
                return "background=#282c34\nforeground=#abb2bf\ncursor=#abb2bf\n" +
                    "color0=#282c34\ncolor1=#e06c75\ncolor2=#98c379\ncolor3=#e5c07b\n" +
                    "color4=#61afef\ncolor5=#c678dd\ncolor6=#56b6c2\ncolor7=#abb2bf\n" +
                    "color8=#5c6370\ncolor9=#e06c75\ncolor10=#98c379\ncolor11=#e5c07b\n" +
                    "color12=#61afef\ncolor13=#c678dd\ncolor14=#56b6c2\ncolor15=#ffffff\n";
            case "Monokai":
                return "background=#272822\nforeground=#f8f8f2\ncursor=#f8f8f2\n" +
                    "color0=#272822\ncolor1=#f92672\ncolor2=#a6e22e\ncolor3=#f4bf75\n" +
                    "color4=#66d9ef\ncolor5=#ae81ff\ncolor6=#a1efe4\ncolor7=#f8f8f2\n" +
                    "color8=#75715e\ncolor9=#f92672\ncolor10=#a6e22e\ncolor11=#f4bf75\n" +
                    "color12=#66d9ef\ncolor13=#ae81ff\ncolor14=#a1efe4\ncolor15=#f9f8f5\n";
            case "Tokyo Night":
                return "background=#1a1b26\nforeground=#c0caf5\ncursor=#c0caf5\n" +
                    "color0=#15161e\ncolor1=#f7768e\ncolor2=#9ece6a\ncolor3=#e0af68\n" +
                    "color4=#7aa2f7\ncolor5=#bb9af7\ncolor6=#7dcfff\ncolor7=#a9b1d6\n" +
                    "color8=#414868\ncolor9=#f7768e\ncolor10=#9ece6a\ncolor11=#e0af68\n" +
                    "color12=#7aa2f7\ncolor13=#bb9af7\ncolor14=#7dcfff\ncolor15=#c0caf5\n";
            case "Catppuccin Mocha":
                return "background=#1e1e2e\nforeground=#cdd6f4\ncursor=#cdd6f4\n" +
                    "color0=#45475a\ncolor1=#f38ba8\ncolor2=#a6e3a1\ncolor3=#f9e2af\n" +
                    "color4=#89b4fa\ncolor5=#f5c2e7\ncolor6=#94e2d5\ncolor7=#bac2de\n" +
                    "color8=#585b70\ncolor9=#f38ba8\ncolor10=#a6e3a1\ncolor11=#f9e2af\n" +
                    "color12=#89b4fa\ncolor13=#f5c2e7\ncolor14=#94e2d5\ncolor15=#a6adc8\n";
            default:
                return "";
        }
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            DrawerLayout drawer = getDrawer();
            if (drawer != null) drawer.closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }





    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                new AlertDialog.Builder(this)
                    .setTitle("Notificaciones")
                    .setMessage(
                        "Kairos puede notificarte cuando un módulo se cae o " +
                        "hay errores en el stack. ¿Deseas activar las notificaciones?"
                    )
                    .setPositiveButton("Activar", (d, w) ->
                        mNotifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS))
                    .setNegativeButton("Ahora no", (d, w) ->
                        getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("notif_permission_requested", true).apply())
                    .show();
            } else {
                getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("notif_permission_requested", true).apply();
            }
        } else {
            getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("notif_permission_requested", true).apply();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                    getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));
                TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
            } else {
                Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                    "Almacenamiento no concedido. El terminal funciona, pero no podrás acceder a /sdcard.");
            }
        } else if (requestCode == 101) {
            // Step 1 complete → proceed to Step 2 (AlertDialog for MANAGE_EXTERNAL_STORAGE)
            PermissionManager.requestStorageStep2(this);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
        }
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            // reloadProperties() ya releyó "extra-keys"/"extra-keys-style" desde disco a
            // mProperties, pero mTermuxTerminalExtraKeys cacheaba su propia matrix en un
            // campo que solo se llenaba una vez, en su constructor — sin este refresh acá,
            // un reload de estilo sin recrear la activity (EXTRA_RECREATE_ACTIVITY=false)
            // repintaba el ExtraKeysView y recalculaba la altura del toolbar con la matrix
            // VIEJA, ignorando el cambio del usuario (ej. pasar de 1 a 2 filas de teclas).
            if (mTermuxTerminalExtraKeys != null) mTermuxTerminalExtraKeys.reloadExtraKeysInfo();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }

    // BottomNavigationView solo soporta 5 items (BottomNavigationView#getMaxItemCount(),
    // límite real de la librería que causaba un crash de arranque con los 7 destinos que
    // tenía la app: "Maximum number of items supported by BottomNavigationView is 5").
    // Monitor/Archivos/Túnel/Nube/Plugins/Sistema/Config quedaron detrás de este ítem "Más" —
    // sus R.id.nav_* siguen existiendo porque more_nav_menu.xml los declara ahí
    // (bottom_nav_menu.xml ya no los tiene), así que switchFragment() no necesitó ningún cambio.
    //
    // Fusión 2026-08-25 (ver docs/estructura/NAVEGACION_BOTTOM_SHEET_2026-08-25.md): antes acá
    // se mostraba un PopupMenu de lista vertical (androidx.appcompat.widget.PopupMenu, import
    // ya removido); ahora se abre un BottomSheetDialogFragment con grilla (MoreBottomSheetFragment),
    // mismo patrón de accesos rápidos que el mockup "Bottom Sheet" de
    // docs/mini-pc/MOCKUPS_NAVEGACION_2026-08-25.md. El parámetro `anchor` queda sin uso real
    // (el bottom sheet no necesita un View ancla como el PopupMenu) pero se conserva la firma
    // para no tocar el único call-site (el listener de bottom_navigation de arriba).
    private void showMoreNavMenu(View anchor) {
        com.termux.app.ui.MoreBottomSheetFragment sheet = new com.termux.app.ui.MoreBottomSheetFragment();
        sheet.setOnItemSelected(itemId -> {
            switchFragment(itemId);
            return kotlin.Unit.INSTANCE;
        });
        sheet.show(getSupportFragmentManager(), "more_bottom_sheet");
    }

    private void switchFragment(int itemId) {
        Fragment target;
        if (itemId == R.id.nav_chat) target = mChatFragment;
        else if (itemId == R.id.nav_monitor) target = mMonitorFragment;
        else if (itemId == R.id.nav_files) target = mFileManagerFragment;
        else if (itemId == R.id.nav_tunnel) target = mTunnelFragment;
        else if (itemId == R.id.nav_nube) target = mNubeFragment;
        else if (itemId == R.id.nav_plugins) target = mPluginsFragment;
        else if (itemId == R.id.nav_studio) target = mStudioFragment;
        else if (itemId == R.id.nav_minipc) target = mEntornoFragment;
        else if (itemId == R.id.nav_settings) target = mSettingsFragment;
        else target = mModulesFragment;

        if (target == null || target == mCurrentFragment) return;

        // Log interno de Kairos, nivel NORMAL — navegación entre tabs (ver docs/humano231.md,
        // ConfigFragment "Log Kairos"). Se loguea el itemId numérico, no el nombre resuelto del
        // recurso: alcanza para correlacionar eventos en el log, sin resolver getResources()
        // acá adentro de un método ya recargado.
        com.termux.app.util.KairosLogger.log(this, "Nav", "switchFragment() -> itemId=" + itemId);

        // Bug real reportado (2026-08-24, ver docs/humano211.md): tocar otro tab estando dentro
        // de un módulo no hacía nada visible hasta salir del módulo con "atrás". Causa raíz:
        // ModuleDetailNavigator.navigate()/BaseModuleFragment.navigateTo() empujan el detalle
        // del módulo con replace(R.id.fragment_container, ...) + addToBackStack(null) — el mismo
        // contenedor donde viven los 11 fragments de tab como hermanos hide/show. replace()
        // sobre un contenedor remueve TODOS los fragments actualmente agregados a ese
        // containerId (los 11 tabs), no solo "el actual" — quedan fuera del FragmentManager
        // mientras el detalle está abierto. El hide()/show() de acá abajo operaba entonces sobre
        // fragments ya removidos: no lanzaba excepción pero tampoco tenía ningún efecto visible.
        // Fix: si hay algo en el backstack (siempre es navegación DENTRO de un módulo, nunca
        // otra cosa — es el único lugar de la app que hace addToBackStack sobre este
        // FragmentManager), se revierte por completo antes de intentar el hide/show — eso
        // re-agrega los 11 tabs con su estado hide/show previo, dejando a mCurrentFragment
        // apuntando a un fragment que el FragmentManager sí reconoce como agregado.
        FragmentManager fm = getSupportFragmentManager();
        while (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate();
        }

        // Pulido visual 2026-08-25 (docs/estructura/ESTILO_VISUAL_2026-08-25.md): fade cruzado
        // corto entre tabs — antes el cambio era instantáneo/seco. setCustomAnimations() debe
        // llamarse ANTES de hide()/show() en la misma transacción para que aplique a ambos.
        fm.beginTransaction()
            .setCustomAnimations(R.anim.kairos_fade_in, R.anim.kairos_fade_out)
            .hide(mCurrentFragment)
            .show(target)
            .commit();

        mCurrentFragment = target;
        mCurrentTabId = itemId;
    }

    /**
     * Navegación pública hacia la Tienda de plugins (menú "Más" → Plugins). La usa el
     * estado vacío de ModulesFragment ("Ir a Plugins →") desde 2026-08-10, cuando la
     * pantalla Módulos pasó a mostrar SOLO lo instalado.
     */
    public void openPlugins() {
        switchFragment(R.id.nav_plugins);
    }

    private void toggleTerminalOverlay() {
        try {
            if (mTermuxService == null) return; // Service not connected yet

            if (mTerminalOverlay == null) {
                mTerminalOverlay = getLayoutInflater().inflate(
                    R.layout.activity_termux, null);
                addContentView(mTerminalOverlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
                mTerminalOverlay.setVisibility(View.GONE);

                // Bug real, 4to intento (2026-08-07, ver docs/humano/humano89.md): acá vivía un
                // listener manual de WindowInsetsCompat (padding a mano para status bar + IME)
                // que ningún proyecto de referencia (ni termux-app real) necesita — revertido al
                // mecanismo real de termux-app: asignar mTermuxActivityRootView de verdad y
                // dejar que TermuxActivityRootView.onGlobalLayout() + SOFT_INPUT_ADJUST_RESIZE
                // (activado en TermuxActivity.onCreate() y TermuxTerminalViewClient) hagan el
                // resize automático. Se hace acá (no en onCreate(), como hace termux-app real)
                // porque en Kairos activity_termux.xml es un overlay que se infla recién ahora,
                // no el contentView de toda la Activity.
                mTermuxActivityRootView = mTerminalOverlay.findViewById(R.id.activity_termux_root_view);
                mTermuxActivityBottomSpaceView = mTerminalOverlay.findViewById(R.id.activity_termux_bottom_space_view);
                if (mTermuxActivityRootView != null) {
                    mTermuxActivityRootView.setActivity(this);
                    mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());
                    // El overlay se agrega con addContentView() cuando el despacho de insets del
                    // DecorView ya ocurrió, así que sin este requestApplyInsets() el listener de
                    // arriba nunca recibía insets y no aplicaba el padding de la barra de estado
                    // (ver docs/humano/humano93.md).
                    mTermuxActivityRootView.requestApplyInsets();
                    if (mPreferences.isTerminalMarginAdjustmentEnabled())
                        addTermuxActivityRootViewGlobalLayoutListener();
                }

                setTermuxTerminalViewAndClients();
                if (mPreferences != null) {
                    mTerminalView.setTextSize(mPreferences.getFontSize());
                }
                setTermuxSessionsListView();
                setTerminalToolbarView(null);
                setToggleKeyboardView();
                setNewSessionButtonView();
                setTerminalQuickSettingsButtonView();
                setMargins();
                // Bug real confirmado (2026-08-01, ver docs/humano/humano* de esa ronda):
                // "mTermuxActivityRootView" llegó a estar sin asignar en una ronda intermedia de
                // esta sesión, lo que hacía que cualquier código que lo usara (ej. el mecanismo
                // de margen de TermuxActivityRootView.onGlobalLayout()) tirara
                // NullPointerException en silencio dentro del catch(Exception) genérico de este
                // método. Ya no aplica — el campo se asigna arriba, justo después de inflar el
                // overlay (ver docs/humano/humano89.md).
                setTerminalAdaptedBarView();
            }

            boolean show = mTerminalOverlay.getVisibility() != View.VISIBLE;
            mTerminalOverlay.setVisibility(show ? View.VISIBLE : View.GONE);

            if (show) {
                mTerminalOverlay.bringToFront();
                mTerminalView.requestFocus();
                applyTerminalModeUi();

                // Attach first session after layout pass to avoid black screen
                mTerminalView.post(() -> {
                    if (mTermuxService == null) return;
                    if (mTermuxTerminalSessionActivityClient == null) return;
                    if (mSkipNextOverlayAutoSession) {
                        // openTerminalWithCommand() nos pidió no crear/adjuntar ninguna sesión
                        // genérica acá — va a crear/adjuntar la suya (con nombre y comando) en
                        // su propio post() a continuación de este.
                        mSkipNextOverlayAutoSession = false;
                        return;
                    }
                    TerminalSession session;
                    if (mTermuxService.getTermuxSessions().isEmpty()) {
                        TermuxSession newSession = mTermuxService.createTermuxSession(null, null, null, null, false, null);
                        if (newSession == null) return;
                        session = newSession.getTerminalSession();
                    } else {
                        session = mTermuxService.getTermuxSessions().get(0).getTerminalSession();
                    }
                    if (session != null) {
                        mTermuxTerminalSessionActivityClient.setCurrentSession(session);
                    }
                    mTerminalView.invalidate();
                });

                // Hide main UI fragments when terminal is shown
                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                if (mCurrentFragment != null) {
                    transaction.hide(mCurrentFragment);
                }
                transaction.commit();

                findViewById(R.id.bottom_navigation).setVisibility(View.GONE);
                findViewById(R.id.fab_terminal).setVisibility(View.GONE);
            } else {
                // Show main UI fragments when terminal is hidden
                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                if (mCurrentFragment != null) {
                    transaction.show(mCurrentFragment);
                }
                transaction.commit();

                findViewById(R.id.bottom_navigation).setVisibility(View.VISIBLE);
                findViewById(R.id.fab_terminal).setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "toggleTerminalOverlay error: " + e.getMessage());
        }
    }

    /**
     * Conecta los botones de la barra de terminal adaptada (Minimizar/Cerrar, ver
     * activity_termux.xml#terminal_adapted_bar). Se llama una sola vez, en la inflación del
     * overlay — los listeners leen mTerminalAdaptedSessionName en el momento del click (no un
     * valor capturado), así sirven para cualquier sesión que esté activa en ese momento.
     */
    private void setTerminalAdaptedBarView() {
        if (mTerminalOverlay == null) return;
        View menuButton = mTerminalOverlay.findViewById(R.id.terminal_adapted_menu_button);
        View minimizeButton = mTerminalOverlay.findViewById(R.id.terminal_adapted_minimize_button);
        View closeButton = mTerminalOverlay.findViewById(R.id.terminal_adapted_close_button);
        // Bug real reportado (2026-08-13, ver docs/humano/humano116.md): el sidebar de acciones
        // rápidas (left_drawer_adapted_content, ver populateAdaptedDrawerContent()) ya existía
        // desde humano42, pero solo se podía abrir con un swipe desde el borde — sin ningún
        // botón visible, poco descubrible. Este botón hace exactamente lo que ya hacía el swipe.
        if (menuButton != null) {
            menuButton.setOnClickListener(v -> {
                DrawerLayout drawer = getDrawer();
                if (drawer != null) drawer.openDrawer(androidx.core.view.GravityCompat.START);
            });
        }
        if (minimizeButton != null) {
            // Minimizar = ocultar el overlay sin tocar la sesión (misma ruta que el botón
            // atrás del sistema) — el proceso/TUI sigue vivo en segundo plano.
            minimizeButton.setOnClickListener(v -> toggleTerminalOverlay());
        }
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> {
                if (mTerminalAdaptedSessionName != null) {
                    stopSessionByName(mTerminalAdaptedSessionName);
                }
                if (mTerminalOverlay.getVisibility() == View.VISIBLE) {
                    toggleTerminalOverlay();
                }
            });
        }
    }

    /**
     * Aplica el modo actual (mTerminalAdaptedMode) a la UI del overlay ya inflado: en modo
     * adaptado (CLI de módulo) oculta el toolbar de teclas extra, bloquea el drawer de
     * sesiones (ni el gesto de swipe desde el borde lo abre) y muestra la barra
     * Minimizar/Cerrar con el nombre de la sesión; en modo normal (terminal de Termux vía FAB)
     * restaura el toolbar según preferencia, desbloquea el drawer y oculta la barra adaptada.
     */
    private void applyTerminalModeUi() {
        if (mTerminalOverlay == null) return;

        View adaptedBar = mTerminalOverlay.findViewById(R.id.terminal_adapted_bar);
        TextView adaptedTitle = mTerminalOverlay.findViewById(R.id.terminal_adapted_title);
        View bottomBar = mTerminalOverlay.findViewById(R.id.terminal_adapted_bottom_bar);
        View normalDrawerContent = mTerminalOverlay.findViewById(R.id.left_drawer_normal_content);
        View adaptedDrawerContent = mTerminalOverlay.findViewById(R.id.left_drawer_adapted_content);
        ViewPager toolbarViewPager = getTerminalToolbarViewPager();
        DrawerLayout drawer = getDrawer();

        applyAdaptedTerminalColors(mTerminalAdaptedMode);

        if (mTerminalAdaptedMode) {
            if (adaptedBar != null) adaptedBar.setVisibility(View.VISIBLE);
            if (adaptedTitle != null) adaptedTitle.setText(mTerminalAdaptedSessionName);
            if (toolbarViewPager != null) toolbarViewPager.setVisibility(View.GONE);
            if (normalDrawerContent != null) normalDrawerContent.setVisibility(View.GONE);
            if (adaptedDrawerContent != null) adaptedDrawerContent.setVisibility(View.VISIBLE);
            // Pedido explícito del usuario (docs/humano/humano42.md): "un sidebar oculto que se abra
            // cuando deslicen... con otras opciones" — antes se bloqueaba cerrado del todo acá
            // (nunca deslizable), porque el contenido era la lista de sesiones genérica de
            // Termux, sin sentido en modo adaptado. Ahora que left_drawer_adapted_content tiene
            // acciones propias del módulo (ver populateAdaptedDrawerContent), desbloquearlo.
            if (drawer != null) drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            refreshAdaptedBarInfo();
            populateAdaptedDrawerContent();
            startAdaptedMetricsLoop();
        } else {
            stopAdaptedMetricsLoop();
            if (adaptedBar != null) adaptedBar.setVisibility(View.GONE);
            if (bottomBar != null) bottomBar.setVisibility(View.GONE);
            if (toolbarViewPager != null) {
                toolbarViewPager.setVisibility(
                    mPreferences != null && mPreferences.shouldShowTerminalToolbar() ? View.VISIBLE : View.GONE);
            }
            if (normalDrawerContent != null) normalDrawerContent.setVisibility(View.VISIBLE);
            if (adaptedDrawerContent != null) adaptedDrawerContent.setVisibility(View.GONE);
            if (drawer != null) drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        }

    }

    /**
     * Paleta Tokyo Night SOLO para la terminal ADAPTADA (mod), nunca para la terminal clásica
     * (humano202, 2026-08-22 — pedido explícito: "toca modificar la terminal... la original la
     * dejamos como esta"). No hay hoy ningún mecanismo separado de colores por modo — Termux usa
     * un único `~/.termux/colors.properties` global (`TerminalColors.COLOR_SCHEME`, leído por
     * TermuxTerminalSessionActivityClient.checkForFontAndColors()) — así que "solo modo
     * adaptado" se logra intercambiando ese archivo al entrar/salir del modo, restaurando el
     * `colors.properties` real del usuario (si tenía uno) al volver a la terminal clásica. El
     * backup vive en el storage interno de la app (`getFilesDir()`), no en `~/.termux/`, para no
     * mezclarse con lo que el usuario edite a mano ahí mismo.
     */
    private void applyAdaptedTerminalColors(boolean useAdaptedColors) {
        if (useAdaptedColors == mAdaptedColorsActive) return;
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File backupFile = new File(getFilesDir(), "terminal_adaptada_colors_backup.properties");
            File backupMarker = new File(getFilesDir(), "terminal_adaptada_no_previo.marker");
            if (useAdaptedColors) {
                if (!backupFile.exists() && !backupMarker.exists()) {
                    if (colorsFile.isFile()) {
                        copyFile(colorsFile, backupFile);
                    } else {
                        backupMarker.createNewFile();
                    }
                }
                writeTokyoNightColorsProperties(colorsFile);
            } else {
                if (backupFile.exists()) {
                    copyFile(backupFile, colorsFile);
                    backupFile.delete();
                } else if (backupMarker.exists()) {
                    colorsFile.delete();
                    backupMarker.delete();
                }
            }
            mAdaptedColorsActive = useAdaptedColors;
            if (mTermuxTerminalSessionActivityClient != null) {
                mTermuxTerminalSessionActivityClient.checkForFontAndColors();
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "applyAdaptedTerminalColors() falló", e);
        }
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(source);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    /** Paleta "Tokyo Night" (variante clásica, no Storm/Light) — valores públicos de
     *  https://github.com/enkia/tokyo-night-vscode-theme, ya validados como dirección de color
     *  por el usuario (docs/humano/humano191.md: negro + azul + verde neón débil). */
    private static void writeTokyoNightColorsProperties(File colorsFile) throws IOException {
        try (FileWriter w = new FileWriter(colorsFile)) {
            w.write("background=#1a1b26\n");
            w.write("foreground=#c0caf5\n");
            w.write("cursor=#c0caf5\n");
            w.write("color0=#15161e\n");
            w.write("color1=#f7768e\n");
            w.write("color2=#9ece6a\n");
            w.write("color3=#e0af68\n");
            w.write("color4=#7aa2f7\n");
            w.write("color5=#bb9af7\n");
            w.write("color6=#7dcfff\n");
            w.write("color7=#a9b1d6\n");
            w.write("color8=#414868\n");
            w.write("color9=#f7768e\n");
            w.write("color10=#9ece6a\n");
            w.write("color11=#e0af68\n");
            w.write("color12=#7aa2f7\n");
            w.write("color13=#bb9af7\n");
            w.write("color14=#7dcfff\n");
            w.write("color15=#c0caf5\n");
        }
    }

    /**
     * module id (modules.json) por nombre de sesión (mTerminalAdaptedSessionName, el mismo
     * "name" de modules.json — ver BaseModuleFragment.launchTerminalCommand(), sessionName por
     * defecto es getModuleName()). Mapa chico y estable, mismo criterio que
     * ModuleController.getModuleStartScript()/getTmuxSession() (hardcodeado, sin leer
     * modules.json de nuevo) — solo cubre los módulos con CLI real en modo adaptado.
     */
    private static String adaptedSessionNameToModuleId(String sessionName) {
        if (sessionName == null) return null;
        switch (sessionName) {
            case "Ollama": return "ollama";
            case "n8n": return "n8n";
            case "Python": return "python";
            case "Claude Code": return "claude";
            case "Codex CLI": return "codex";
            case "Antigravity CLI": return "antigravity";
            case "OpenClaw": return "openclaw";
            case "OpenCode": return "opencode";
            case "Hermes": return "hermes";
            case "Remote": return "remote";
            case "Expo": return "expo";
            case "Engram": return "engram";
            case "Entorno": return "entorno";
            default: return null;
        }
    }

    // Puerto real por módulo, para la barra inferior — copia intencional (no reusable desde
    // Java) del mismo mapa privado que ModuleController.kt mantiene para
    // waitForPortOpen()/startModule(). Solo se usa acá para MOSTRAR info, no para lógica de
    // arranque — la fuente de verdad real sigue siendo ModuleController.
    private static Integer adaptedModuleIdToPort(String id) {
        if (id == null) return null;
        switch (id) {
            case "ollama": return 11434;
            case "n8n": return 5678;
            case "openclaw": return 18789;
            case "opencode": return 3000;
            case "remote": return 8022;
            default: return null;
        }
    }

    // Dependencias de backend IA local por módulo, para los chips de la barra adaptada (ver
    // refreshAdaptedBarInfo() más abajo). Pedido explícito del usuario (docs/humano/humano169.md):
    // "si estan en opencode ver si ollama/llama esta corriendo" — mapa chico y EXTENSIBLE a
    // propósito (moduleId -> lista de moduleId de los que depende), no una solución hardcodeada
    // solo para OpenCode: cualquier CLI futuro que dependa de un backend local (Ollama,
    // llama-server, u otro módulo) solo necesita una entrada más acá, sin tocar el resto del
    // mecanismo (refreshAdaptedBarInfo()/el layout ya soportan N chips, no un chip fijo).
    private static java.util.List<String> adaptedModuleDependencies(String moduleId) {
        if (moduleId == null) return java.util.Collections.emptyList();
        switch (moduleId) {
            case "opencode": return java.util.Arrays.asList("ollama", "llamaserver");
            default: return java.util.Collections.emptyList();
        }
    }

    // Comando "listar servidores MCP" por módulo, para el atajo del drawer adaptado (ver
    // populateAdaptedDrawerContent()) — mismo pedido de humano169.md ("los mcp, añadir
    // opciones"). Solo se listan acá los CLIs con soporte MCP ya CONFIRMADO por otro código real
    // del proyecto: "claude mcp list" ya es un actionButton existente en ClaudeFragment.kt; para
    // "openclaw mcp ..." OpenClawNative.kt ya confirma (con cita a docs.openclaw.ai/cli/mcp) el
    // subcomando "openclaw mcp add" — se asume que el mismo CLI también expone "list" (convención
    // habitual add/list/remove), sin evidencia directa de esa variante puntual; si no existiera,
    // el peor caso es un error de CLI mostrado en la propia terminal, no un crash. Extensible:
    // agregar una entrada más alcanza para otro módulo con MCP confirmado (ej. si opencode suma
    // soporte MCP propio en el futuro).
    private static String adaptedModuleMcpCommand(String moduleId) {
        if (moduleId == null) return null;
        switch (moduleId) {
            case "claude": return "claude mcp list";
            case "openclaw": return "openclaw mcp list";
            default: return null;
        }
    }

    /** Chip chico (punto + texto) para el estado de una dependencia en la barra adaptada — mismo
     *  estilo visual que BaseModuleFragment.pill() (Kotlin, no reusable directo desde Java), sin
     *  duplicar su lógica de negocio: solo pinta running=true/false. */
    private View createAdaptedDependencyChip(String label, boolean running) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padH = (int) (8 * getResources().getDisplayMetrics().density);
        int padV = (int) (3 * getResources().getDisplayMetrics().density);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackgroundColor(running ? android.graphics.Color.parseColor("#1A22C55E")
            : androidx.core.content.ContextCompat.getColor(this, R.color.kairos_bg3));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipParams.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
        chip.setLayoutParams(chipParams);

        View dot = new View(this);
        int dotSize = (int) (6 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotParams.rightMargin = (int) (5 * getResources().getDisplayMetrics().density);
        dot.setLayoutParams(dotParams);
        dot.setBackgroundColor(running
            ? androidx.core.content.ContextCompat.getColor(this, R.color.kairos_green)
            : androidx.core.content.ContextCompat.getColor(this, R.color.kairos_text3));
        chip.addView(dot);

        TextView text = new TextView(this);
        text.setText(label);
        // 12.5sp (antes 11sp) — legibilidad de la terminal adaptada, mismo criterio del texto
        // chico repetido en otras pantallas esta sesión (pedido explícito del usuario).
        text.setTextSize(12.5f);
        text.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.kairos_text2));
        chip.addView(text);

        return chip;
    }

    /** Estado + versión reales en la barra superior adaptada, y puerto/URL (si aplica) en la inferior — ver activity_termux.xml, terminal_adapted_subtitle/terminal_adapted_bottom_bar. */
    private void refreshAdaptedBarInfo() {
        if (mTerminalOverlay == null || mTerminalAdaptedSessionName == null) return;
        String moduleId = adaptedSessionNameToModuleId(mTerminalAdaptedSessionName);
        TextView subtitle = mTerminalOverlay.findViewById(R.id.terminal_adapted_subtitle);
        TextView bottomBar = mTerminalOverlay.findViewById(R.id.terminal_adapted_bottom_bar);
        if (moduleId == null) {
            if (subtitle != null) subtitle.setText("");
            if (bottomBar != null) bottomBar.setVisibility(View.GONE);
            return;
        }
        final String finalModuleId = moduleId;
        final java.util.List<String> dependencyIds = adaptedModuleDependencies(finalModuleId);
        new Thread(() -> {
            boolean running;
            String version = null;
            boolean portOpen = false;
            try {
                running = ModuleController.INSTANCE.isRunning(finalModuleId);
            } catch (Exception e) {
                running = false;
            }
            try {
                version = new com.termux.app.data.ModuleRegistry(this).load().get(finalModuleId + ".version");
            } catch (Exception e) {
                // Sin versión disponible — se omite en el texto, no es un error visible.
            }
            Integer port = adaptedModuleIdToPort(finalModuleId);
            if (port != null && running) {
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                    portOpen = true;
                } catch (Exception e) {
                    portOpen = false;
                }
            }
            // Estado real de cada dependencia (ej. Ollama/llama-server para OpenCode) — mismo
            // isRunning() que ya usa el propio módulo arriba, sin reimplementar el chequeo (ver
            // adaptedModuleDependencies()).
            final java.util.LinkedHashMap<String, Boolean> depsStatus = new java.util.LinkedHashMap<>();
            for (String depId : dependencyIds) {
                boolean depRunning;
                try {
                    depRunning = ModuleController.INSTANCE.isRunning(depId);
                } catch (Exception e) {
                    depRunning = false;
                }
                depsStatus.put(depId, depRunning);
            }
            final boolean finalRunning = running;
            final String finalVersion = (version != null && !version.isEmpty()) ? version : null;
            final boolean finalPortOpen = portOpen;
            final Integer finalPort = port;
            runOnUiThread(() -> {
                if (!isVisible() || mTerminalOverlay == null) return;
                if (subtitle != null) {
                    String stateText = finalRunning ? "● Activo" : "○ Inactivo";
                    String versionText = finalVersion != null ? " · v" + finalVersion : "";
                    subtitle.setText(stateText + versionText);
                }
                if (bottomBar != null) {
                    if (finalPortOpen && finalPort != null) {
                        bottomBar.setText("⏺ escuchando en http://127.0.0.1:" + finalPort);
                        bottomBar.setVisibility(View.VISIBLE);
                    } else {
                        bottomBar.setVisibility(View.GONE);
                    }
                }
                LinearLayout depsRow = mTerminalOverlay.findViewById(R.id.terminal_adapted_deps_row);
                if (depsRow != null) {
                    depsRow.removeAllViews();
                    if (depsStatus.isEmpty()) {
                        depsRow.setVisibility(View.GONE);
                    } else {
                        for (java.util.Map.Entry<String, Boolean> entry : depsStatus.entrySet()) {
                            String label = adaptedModuleIdToDisplayName(entry.getKey());
                            depsRow.addView(createAdaptedDependencyChip(label, entry.getValue()));
                        }
                        depsRow.setVisibility(View.VISIBLE);
                    }
                }
            });
        }).start();
    }

    /**
     * Panel de estado con métricas en vivo (CPU/RAM del dispositivo) — pedido 2026-08-25.
     * Loop self-rescheduling propio en vez de reusar refreshAdaptedBarInfo(): esa función hace
     * isRunning()/versión/deps/conexión de socket por dependencia en cada llamada (varias
     * llamadas de red/proceso), demasiado costoso para correr cada 3s; esta función solo lee
     * /proc/stat + /proc/meminfo (ManagerNativeUtils.systemMetricsSummary()), liviano.
     */
    private void startAdaptedMetricsLoop() {
        mAdaptedMetricsHandler.removeCallbacksAndMessages(null);
        mAdaptedMetricsHandler.post(mAdaptedMetricsRunnable);
    }

    private void stopAdaptedMetricsLoop() {
        mAdaptedMetricsHandler.removeCallbacksAndMessages(null);
    }

    private final Runnable mAdaptedMetricsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mTerminalAdaptedMode || mTerminalOverlay == null || !isVisible()
                || mTerminalOverlay.getVisibility() != View.VISIBLE) {
                return; // Overlay minimizado/cerrado o modo ya cambiado — el loop se auto-detiene.
            }
            new Thread(() -> {
                final String summary;
                try {
                    summary = com.termux.app.util.ManagerNativeUtils.INSTANCE.systemMetricsSummary();
                } catch (Exception e) {
                    return;
                }
                runOnUiThread(() -> {
                    if (!isVisible() || mTerminalOverlay == null) return;
                    TextView metrics = mTerminalOverlay.findViewById(R.id.terminal_adapted_metrics);
                    if (metrics != null) {
                        if (summary != null) {
                            metrics.setText(summary);
                            metrics.setVisibility(View.VISIBLE);
                        } else {
                            metrics.setVisibility(View.GONE);
                        }
                    }
                });
            }).start();
            mAdaptedMetricsHandler.postDelayed(this, ADAPTED_METRICS_INTERVAL_MS);
        }
    };

    // Nombre corto para mostrar en el chip de dependencia — copia intencional (mismo criterio
    // que adaptedModuleIdToPort()) del "name" real de modules.json para los módulos que hoy
    // pueden aparecer como dependencia (ver adaptedModuleDependencies()).
    private static String adaptedModuleIdToDisplayName(String moduleId) {
        if (moduleId == null) return "";
        switch (moduleId) {
            case "ollama": return "Ollama";
            case "llamaserver": return "llama-server";
            default: return moduleId;
        }
    }

    /**
     * Acciones rápidas del sidebar en modo adaptado (ver left_drawer_adapted_content en
     * activity_termux.xml) — Minimizar/Cerrar (mismas acciones que la barra superior, también
     * accesibles desde acá) + Reiniciar módulo + Ver logs. Reconstruye el contenido cada vez
     * que se llama (mismo criterio que setTerminalAdaptedBarView() para los botones de la
     * barra) — barato dado que son pocas filas de texto.
     */
    private void populateAdaptedDrawerContent() {
        if (mTerminalOverlay == null || mTerminalAdaptedSessionName == null) return;
        TextView title = mTerminalOverlay.findViewById(R.id.adapted_drawer_title);
        android.widget.LinearLayout actions = mTerminalOverlay.findViewById(R.id.adapted_drawer_actions);
        if (actions == null) return;
        if (title != null) title.setText(mTerminalAdaptedSessionName.toUpperCase(java.util.Locale.getDefault()));
        actions.removeAllViews();

        // Filtro de búsqueda no debe sobrevivir a un cambio de módulo (ej. filtrar "log" en
        // OpenCode y después abrir Ollama no debería seguir ocultando sus acciones).
        if (!mTerminalAdaptedSessionName.equals(mAdaptedDrawerLastSession)) {
            mAdaptedDrawerLastSession = mTerminalAdaptedSessionName;
            mAdaptedDrawerFilterText = "";
            android.widget.EditText search = mTerminalOverlay.findViewById(R.id.adapted_drawer_search);
            if (search != null && search.getText().length() > 0) search.setText("");
        }

        String moduleId = adaptedSessionNameToModuleId(mTerminalAdaptedSessionName);

        addAdaptedDrawerAction(actions, "▾ Minimizar", v -> {
            if (getDrawer() != null) getDrawer().closeDrawers();
            toggleTerminalOverlay();
        });
        addAdaptedDrawerAction(actions, "✕ Cerrar sesión", v -> {
            if (getDrawer() != null) getDrawer().closeDrawers();
            if (mTerminalAdaptedSessionName != null) stopSessionByName(mTerminalAdaptedSessionName);
            if (mTerminalOverlay != null && mTerminalOverlay.getVisibility() == View.VISIBLE) {
                toggleTerminalOverlay();
            }
        });
        if (moduleId != null) {
            addAdaptedDrawerAction(actions, "↻ Reiniciar módulo", v -> {
                if (getDrawer() != null) getDrawer().closeDrawers();
                restartModuleFromDrawer(moduleId);
            });
            addAdaptedDrawerAction(actions, "▤ Ver logs", v -> {
                if (getDrawer() != null) getDrawer().closeDrawers();
                showModuleLogDialog(moduleId);
            });
            // Atajo a servidores MCP sin salir de la terminal adaptada (pedido explícito,
            // docs/humano/humano169.md: "los mcp, añadir opciones") — solo aparece para módulos
            // con soporte MCP confirmado (ver adaptedModuleMcpCommand()). Escribe el comando en
            // la MISMA sesión activa (mismo mecanismo que "Reiniciar módulo"/writeCommandOnceSessionReady
            // vía openTerminalWithCommand), no abre una sesión nueva.
            String mcpCommand = adaptedModuleMcpCommand(moduleId);
            if (mcpCommand != null) {
                addAdaptedDrawerAction(actions, "🔌 Servidores MCP", v -> {
                    if (getDrawer() != null) getDrawer().closeDrawers();
                    openTerminalWithCommand(mcpCommand, mTerminalAdaptedSessionName);
                });
            }
        }
        // Pedido explícito del usuario (2026-08-13, ver docs/humano/humano116.md): "poder
        // monitorear... ver las extenciones" desde la terminal adaptada. "Extensiones" son las
        // ExtraKeys (teclas extra) — el toolbar real (terminal_toolbar_view_pager) ya existe,
        // solo estaba forzado a GONE en modo adaptado (applyTerminalModeUi()); acá se hace
        // opt-in en vez de mostrarlo siempre, para no arriesgar el layout de la barra inferior
        // de info (mismo riesgo que ya se había identificado antes de tocar esto).
        addAdaptedDrawerAction(actions, "⌨ Teclas extra", v -> {
            if (getDrawer() != null) getDrawer().closeDrawers();
            toggleAdaptedExtraKeys();
        });
        addAdaptedDrawerAction(actions, "📊 Monitor", v -> {
            if (getDrawer() != null) getDrawer().closeDrawers();
            toggleTerminalOverlay();
            switchFragment(R.id.nav_monitor);
        });

        setupAdaptedDrawerSearchIfNeeded();
        applyAdaptedDrawerFilter(); // reaplica el filtro activo a las filas recién reconstruidas
    }

    /**
     * Command palette chico sobre el sidebar adaptado (adapted_drawer_search en
     * activity_termux.xml) — filtra en vivo las filas ya generadas por
     * populateAdaptedDrawerContent(), sin adapter/backend nuevo (ver addAdaptedDrawerAction():
     * cada fila es un TextView plano dentro de adapted_drawer_actions, no un
     * RecyclerView/ListView). Se registra una sola vez por overlay — populateAdaptedDrawerContent()
     * se llama repetidas veces sobre el mismo EditText (cambio de módulo, refresh manual).
     */
    private void setupAdaptedDrawerSearchIfNeeded() {
        if (mAdaptedDrawerSearchWired || mTerminalOverlay == null) return;
        android.widget.EditText search = mTerminalOverlay.findViewById(R.id.adapted_drawer_search);
        if (search == null) return;
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mAdaptedDrawerFilterText = s.toString();
                applyAdaptedDrawerFilter();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) { }
        });
        mAdaptedDrawerSearchWired = true;
    }

    /** Muestra/oculta cada fila de adapted_drawer_actions según si su texto contiene
     *  mAdaptedDrawerFilterText (sin distinguir mayúsculas/acentos no se normaliza, alcance
     *  simple a propósito — son ~7 acciones, no una lista larga que necesite fuzzy-match). */
    private void applyAdaptedDrawerFilter() {
        if (mTerminalOverlay == null) return;
        android.widget.LinearLayout actions = mTerminalOverlay.findViewById(R.id.adapted_drawer_actions);
        if (actions == null) return;
        String query = mAdaptedDrawerFilterText.trim().toLowerCase(java.util.Locale.getDefault());
        for (int i = 0; i < actions.getChildCount(); i++) {
            View child = actions.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            String label = ((TextView) child).getText().toString().toLowerCase(java.util.Locale.getDefault());
            child.setVisibility(query.isEmpty() || label.contains(query) ? View.VISIBLE : View.GONE);
        }
    }

    /** Muestra/oculta el toolbar real de ExtraKeys (terminal_toolbar_view_pager) sobre el modo
     *  adaptado — normalmente GONE ahí (ver applyTerminalModeUi()) porque colisiona con la
     *  barra inferior de info del módulo; acá el usuario lo pide explícitamente por sesión. */
    private void toggleAdaptedExtraKeys() {
        ViewPager toolbarViewPager = getTerminalToolbarViewPager();
        if (toolbarViewPager == null) return;
        toolbarViewPager.setVisibility(
            toolbarViewPager.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void addAdaptedDrawerAction(android.widget.LinearLayout container, String label, View.OnClickListener onClick) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextSize(14f);
        row.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.kairos_text));
        int padH = (int) (16 * getResources().getDisplayMetrics().density);
        int padV = (int) (14 * getResources().getDisplayMetrics().density);
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId != 0 ? outValue.resourceId : 0);
        row.setOnClickListener(onClick);
        container.addView(row);
    }

    /** Reinicia el módulo (stop, esperar un momento, start) — reusa ModuleController, mismo camino que el toggle de ModulesFragment. */
    private void restartModuleFromDrawer(String moduleId) {
        showToast("Reiniciando " + mTerminalAdaptedSessionName + "…", false);
        ModuleController.INSTANCE.stopModule(moduleId, stopped -> {
            new Thread(() -> {
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                ModuleController.INSTANCE.startModule(moduleId, (success, output) -> {
                    runOnUiThread(() -> {
                        if (!isVisible()) return;
                        showToast(success ? mTerminalAdaptedSessionName + " reiniciado" : "Error reiniciando: " + output, success ? false : true);
                        refreshAdaptedBarInfo();
                    });
                    return kotlin.Unit.INSTANCE;
                });
            }).start();
            return kotlin.Unit.INSTANCE;
        });
    }

    /** Muestra el log real de instalación del módulo (~/kairos_logs/install_<id>.log) en un diálogo simple con scroll — sin navegar a otra pantalla. */
    private void showModuleLogDialog(String moduleId) {
        new Thread(() -> {
            String content;
            try {
                java.io.File logFile = ModuleController.INSTANCE.installLogFile(moduleId);
                content = logFile.exists()
                    ? new String(java.nio.file.Files.readAllBytes(logFile.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                    : "Sin log de instalación todavía.";
            } catch (Exception e) {
                content = "No se pudo leer el log: " + e.getMessage();
            }
            final String finalContent = content.length() > 20000 ? content.substring(content.length() - 20000) : content;
            runOnUiThread(() -> {
                if (!isVisible()) return;
                TextView textView = new TextView(this);
                textView.setText(finalContent.isEmpty() ? "(vacío)" : finalContent);
                textView.setTextSize(11f);
                textView.setTypeface(android.graphics.Typeface.MONOSPACE);
                int pad = (int) (16 * getResources().getDisplayMetrics().density);
                textView.setPadding(pad, pad, pad, pad);
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(textView);
                new AlertDialog.Builder(this)
                    .setTitle("Log — " + mTerminalAdaptedSessionName)
                    .setView(scroll)
                    .setPositiveButton("Cerrar", null)
                    .show();
            });
        }).start();
    }

    /**
     * Abre el overlay de terminal y corre un comando en una sesión nueva — usado por los
     * fragments de detalle de módulo ("TUI en terminal", etc.) para lanzar herramientas CLI
     * (claude/codex/opencode/agy/python3) sin que el usuario tenga que escribirlas a mano.
     */
    public void openTerminalWithCommand(String command) {
        openTerminalWithCommand(command, null);
    }

    /**
     * Igual que {@link #openTerminalWithCommand(String)} pero nombrando la sesión — así el
     * drawer de sesiones del overlay muestra "Claude Code"/"Codex"/etc. en vez de un número
     * genérico, y el usuario puede distinguir qué terminal es de qué herramienta (fix
     * "terminales adaptadas por CLI" — antes todas las herramientas usaban exactamente el
     * mismo mecanismo genérico sin ninguna diferenciación).
     */
    public void openTerminalWithCommand(String command, String sessionName) {
        // Pedido explícito del usuario (2026-08-13, ver docs/humano/humano118.md): toggle en
        // Ajustes ("Terminal clásica (sin UI adaptada)", ver ConfigFragment.kt) — mismo
        // SharedPreferences "kairos_prefs" que ya usan los demás toggles de esa pantalla.
        boolean classicTerminalPreferred = getSharedPreferences("kairos_prefs", MODE_PRIVATE)
            .getBoolean("pref_classic_terminal", false);
        mTerminalAdaptedMode = !classicTerminalPreferred && sessionName != null && !sessionName.isEmpty();
        mTerminalAdaptedSessionName = classicTerminalPreferred ? null : sessionName;

        boolean wasVisible = mTerminalOverlay != null && mTerminalOverlay.getVisibility() == View.VISIBLE;
        if (!wasVisible) {
            // toggleTerminalOverlay() ya aplica el modo (ver su rama "show") leyendo los
            // campos de arriba — se setean ANTES de llamarlo para que quede bien desde el
            // primer frame, no hace falta aplicar de nuevo acá. Se le pide además que no cree
            // su propia sesión genérica (ver mSkipNextOverlayAutoSession) porque esta función
            // ya va a crear/adjuntar la suya, con nombre y comando.
            mSkipNextOverlayAutoSession = true;
            toggleTerminalOverlay();
        } else {
            // El overlay ya estaba visible (ej. el usuario tenía la terminal normal abierta y
            // tocó "TUI en terminal" desde un módulo) — no hay transición show/hide que dispare
            // applyTerminalModeUi(), así que se aplica a mano.
            applyTerminalModeUi();
        }
        if (mTerminalView == null) return;
        mTerminalView.post(() -> {
            if (mTermuxTerminalSessionActivityClient == null) return;
            // Bug real confirmado con capturas de dispositivo: antes esto llamaba
            // addNewSession() incondicionalmente cada vez que se abría un CLI (TUI en
            // terminal, agy, etc.) — cada tap acumulaba una sesión nueva (el selector de
            // sesiones de Termux llegó a mostrar 3 sesiones distintas para lo que el
            // usuario esperaba fuera "la terminal de OpenCode", una sola). Ahora se busca
            // primero una sesión existente con ese mismo nombre y se reusa en vez de crear
            // otra — solo se manda el comando de nuevo si la sesión es realmente nueva
            // (reenviarlo a una sesión ya corriendo el TUI reiniciaría la herramienta sin
            // que el usuario lo pidiera).
            TerminalSession existing = findSessionByName(sessionName);
            if (existing != null) {
                mTermuxTerminalSessionActivityClient.setCurrentSession(existing);
                mTerminalView.invalidate();
                return;
            }
            TerminalSession previousCurrent = getCurrentSession();
            mTermuxTerminalSessionActivityClient.addNewSession(false, sessionName);
            TerminalSession session = getCurrentSession();
            // Si addNewSession() no pudo crear la sesión (ej. se llegó a MAX_SESSIONS=8 y se
            // mostró el diálogo de aviso), getCurrentSession() sigue devolviendo la sesión
            // anterior (o null) — sin este chequeo el comando se mandaría a la sesión
            // equivocada en vez de simplemente no mandarse.
            if (session == null || session == previousCurrent) return;
            if (command != null && !command.isEmpty()) {
                writeCommandOnceSessionReady(session, command);
            }
        });
    }

    /**
     * Escribe {@code command} en {@code session} una vez que el proceso del shell ya arrancó de
     * verdad — no apenas se crea el objeto {@link TerminalSession}.
     *
     * Bug real confirmado leyendo el código fuente (no solo hipótesis): {@code TerminalSession.
     * write()} es un no-op silencioso si {@code mShellPid <= 0} (terminal-emulator/.../
     * TerminalSession.java, método write()), y {@code mShellPid} SOLO se setea dentro de
     * {@code initializeEmulator()} (que hace el fork/exec real vía {@code JNI.createSubprocess}),
     * método que a su vez SOLO se llama desde {@code TerminalSession.updateSize()}, invocado por
     * {@code TerminalView.updateSize()} — y ese método hace early-return si {@code getWidth()}/
     * {@code getHeight()} de la vista todavía son 0 (terminal-view/.../TerminalView.java,
     * método updateSize()), es decir, si la vista todavía no pasó por un layout pass real.
     *
     * Cuando el overlay de terminal se muestra por primera vez en la sesión de la Activity (o
     * después de estar oculto), no hay garantía de que mTerminalView ya tenga medidas reales en
     * el momento exacto en que corre este mTerminalView.post() — de ahí el bug reportado con
     * captura de dispositivo: la terminal mostraba el MOTD crudo de Termux con el prompt vacío
     * ("~ $") sin el comando "agy" tipeado en ningún lado. El shell SÍ termina arrancando (el
     * layout real llega poco después y dispara initializeEmulator() desde
     * TerminalView.onSizeChanged()), pero el write() anterior ya se había perdido en silencio
     * porque corrió con mShellPid todavía en 0 — no es un problema de "el shell tarda en estar
     * listo para leer stdin" (el pty bufferea igual aunque el proceso no esté leyendo todavía),
     * es que el proceso ni se había forkeado.
     *
     * {@link TerminalSession#getEmulator()} es el único indicador confiable disponible sin tocar
     * los módulos protegidos: se setea en la primera línea de initializeEmulator(), antes de
     * mShellPid, así que "emulator != null" implica "el proceso ya se forkeó". Si todavía es
     * null, se espera al próximo layout real vía ViewTreeObserver (que se dispara después de que
     * TerminalView.onSizeChanged() ya haya corrido updateSize() con medidas reales) en vez de
     * confiar en que post() ya corrió después del layout, que no está garantizado en todos los
     * casos (overlay recién inflado o recién hecho visible).
     */
    private void writeCommandOnceSessionReady(TerminalSession session, String command) {
        if (session.getEmulator() != null) {
            session.write(command + "\n");
            return;
        }
        mTerminalView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (mTerminalView == null) return;
                if (mTerminalView.getWidth() <= 0 || mTerminalView.getHeight() <= 0) return;
                ViewTreeObserver observer = mTerminalView.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                // Red de seguridad: si por algún motivo onSizeChanged() no llegó a inicializar
                // el emulador de esta sesión todavía (ej. quedó de fondo, sin ser la sesión
                // actual, en algún reordenamiento), se fuerza acá — es un no-op si ya corrió.
                if (getCurrentSession() == session) {
                    mTerminalView.updateSize();
                }
                if (session.getEmulator() != null) {
                    session.write(command + "\n");
                }
            }
        });
    }

    /** Busca una sesión ya abierta por nombre (ver openTerminalWithCommand) — null si sessionName es null/vacío o no hay match. */
    private TerminalSession findSessionByName(String sessionName) {
        if (sessionName == null || sessionName.isEmpty() || mTermuxService == null) return null;
        for (TermuxSession termuxSession : mTermuxService.getTermuxSessions()) {
            TerminalSession session = termuxSession.getTerminalSession();
            if (session != null && sessionName.equals(session.mSessionName)) {
                return session;
            }
        }
        return null;
    }

    /**
     * Llamado desde TermuxTerminalSessionActivityClient.onTextChanged() para CADA sesión con
     * output nuevo, incluso si la Activity entera no está visible (a diferencia del resto de
     * ese callback, que sí filtra por isVisible() — acá es al revés: el caso que más importa
     * notificar es justo cuando el usuario salió de la app del todo). Ver comentario de
     * SESSION_IDLE_NOTIFY_DELAY_MS más arriba para la heurística completa.
     */
    public void onBackgroundSessionOutput(TerminalSession changedSession) {
        String sessionName = changedSession.mSessionName;
        // Solo sesiones nombradas (modo adaptado, CLI de un módulo) — la terminal genérica de
        // Termux (sesión sin nombre, vía FAB) no tiene un "módulo" al que asociar la notificación.
        if (sessionName == null || sessionName.isEmpty()) return;

        if (isVisible() && isSessionCurrentlyShown(changedSession)) {
            // El usuario está mirando esta sesión activamente ahora mismo — no hace falta
            // avisar, y cualquier check pendiente de una ráfaga anterior queda obsoleto.
            cancelSessionIdleCheck(sessionName);
            mSessionAlreadyNotifiedForBurst.remove(sessionName);
            return;
        }

        // Output nuevo real: la ráfaga sigue viva, así que un aviso previo para ESTA ráfaga
        // (si lo hubo) ya no aplica — se re-arma para poder notificar de nuevo si vuelve a
        // quedar idle.
        mSessionAlreadyNotifiedForBurst.remove(sessionName);
        cancelSessionIdleCheck(sessionName);

        if (mTerminalView == null) return; // sin vista todavía no hay dónde postDelayed
        Runnable idleCheck = () -> {
            mSessionIdleCheckRunnables.remove(sessionName);
            if (mSessionAlreadyNotifiedForBurst.contains(sessionName)) return;
            TerminalSession stillAlive = findSessionByName(sessionName);
            if (stillAlive == null) return; // la sesión terminó/se cerró mientras esperábamos
            if (isVisible() && isSessionCurrentlyShown(stillAlive)) return; // el usuario volvió
            mSessionAlreadyNotifiedForBurst.add(sessionName);
            notifySessionNeedsAttention(sessionName);
            notifyModuleEventBridgeForBackgroundSession(sessionName, stillAlive);
        };
        mSessionIdleCheckRunnables.put(sessionName, idleCheck);
        mTerminalView.postDelayed(idleCheck, SESSION_IDLE_NOTIFY_DELAY_MS);
    }

    /**
     * Lado session_idle/session_permission del contrato de sesión (ver header de
     * ModuleEventBridge.kt) — enganchado a la MISMA ráfaga "output se calmó y el usuario no
     * está mirando" que ya dispara notifySessionNeedsAttention() (whispercode-dev), en vez de
     * inventar un mecanismo de polling nuevo. Solo aplica a los CLIs con mapeo confirmado en
     * SessionPromptDetector (hoy: Claude Code) — cualquier otra sesión nombrada no emite nada
     * acá y sigue recibiendo solo la notificación genérica de arriba.
     *
     * Nota honesta: esto NO es "sin actividad hace N minutos" (lo que pide literalmente el
     * contrato para session_idle) — es "una ráfaga de output se asentó mientras la sesión
     * estaba en segundo plano". Para un CLI interactivo es un proxy razonable (el proceso
     * terminó de imprimir o quedó esperando input), pero no aplica a módulos de servicio sin
     * TerminalSession propia (ollama/n8n/openclaw corren en tmux detached, sin este hook) —
     * ver MEJORAS_PENDIENTES.md para el detalle de por qué esos quedan fuera esta ronda. El
     * cooldown de 15/30 min de ModuleEventBridge.notify() es el que evita que esto spamee
     * aunque la ráfaga se repita seguido.
     */
    private void notifyModuleEventBridgeForBackgroundSession(String sessionName, TerminalSession session) {
        String moduleId = SessionPromptDetector.moduleIdFor(sessionName);
        if (moduleId == null) return;
        String screenText = SessionPromptDetector.visibleScreenText(session);
        if (SessionPromptDetector.looksLikePermissionPrompt(moduleId, screenText)) {
            ModuleEventBridge.notifySessionEvent(this, moduleId, ModuleEventBridge.SessionEvent.PERMISSION,
                "Está esperando que apruebes una acción — abrí la terminal para responder.");
        } else {
            ModuleEventBridge.notifySessionEvent(this, moduleId, ModuleEventBridge.SessionEvent.IDLE,
                "Dejó de imprimir output nuevo — puede estar esperando algo o haber terminado.");
        }
    }

    private boolean isSessionCurrentlyShown(TerminalSession session) {
        return mTerminalOverlay != null
            && mTerminalOverlay.getVisibility() == View.VISIBLE
            && getCurrentSession() == session;
    }

    private void cancelSessionIdleCheck(String sessionName) {
        Runnable pending = mSessionIdleCheckRunnables.remove(sessionName);
        if (pending != null && mTerminalView != null) mTerminalView.removeCallbacks(pending);
    }

    /** Cancela TODOS los postDelayed() pendientes de mSessionIdleCheckRunnables — llamado
     * desde onStop() para no dejar callbacks colgados apuntando a esta Activity/vista una vez
     * que dejó de estar visible. */
    private void cancelAllSessionIdleChecks() {
        if (mTerminalView != null) {
            for (Runnable pending : mSessionIdleCheckRunnables.values()) {
                mTerminalView.removeCallbacks(pending);
            }
        }
        mSessionIdleCheckRunnables.clear();
    }

    /** Notificación real "sessionName necesita atención" — mismo patrón/preferencia que ModulesFragment.notifyModuleStopped(), con tap que reabre esa sesión (ver onNewIntent/EXTRA_FOCUS_SESSION_NAME). */
    private void notifySessionNeedsAttention(String sessionName) {
        boolean notifyEnabled = getSharedPreferences("kairos_prefs", MODE_PRIVATE)
            .getBoolean("pref_notify_modules", true);
        if (!notifyEnabled) return;

        String channelId = "kairos_session_attention";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    new NotificationChannel(channelId, "Sesiones que necesitan atención", NotificationManager.IMPORTANCE_DEFAULT)
                );
            }
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;

        Intent intent = new Intent(this, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_FOCUS_SESSION_NAME, sessionName);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, sessionName.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(sessionName + " necesita atención")
            .setContentText("Terminó de generar o está esperando tu input — tocá para volver.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true);
        NotificationManagerCompat.from(this).notify(sessionName.hashCode(), notification.build());
    }

    /**
     * true si hay una sesión de terminal (minimizada o visible) con este nombre — usada por
     * las pantallas de detalle de módulo (BaseModuleFragment y subclases) para mostrar un
     * indicador de "TUI corriendo en segundo plano" sin depender de leer el estado del script
     * (la sesión TUI es un shell interactivo dentro del overlay, no tiene relación con tmux).
     */
    public boolean isSessionActive(String sessionName) {
        return findSessionByName(sessionName) != null;
    }

    /**
     * Cierra la sesión de terminal (TUI en la app, ej. "agy"/"opencode" corriendo
     * interactivo) que coincide con sessionName, si existe — no-op si no hay ninguna.
     * Bug real reportado: "Detener servidor" en un módulo con web + TUI (ej. OpenCode)
     * solo mataba la sesión tmux del servidor web (opencode_stop.sh) — la sesión TUI
     * (un shell interactivo dentro del overlay, sin relación con tmux) seguía viva.
     * Se llama desde BaseModuleFragment.stopModuleService() además del script de stop
     * normal, para que "detener" de verdad pare las dos cosas.
     */
    // Bug real (2026-08-07, ver docs/humano/humano91.md): "al tocar cerrar de la terminal no
    // se cierra bien... es como si se pusiera exit pero no se tocara enter" — antes esto
    // llamaba finishIfRunning() (terminal-emulator/, protegido) directo, que manda SIGKILL
    // solo al PID del shell. El shell corre con setsid() (termux.c), líder de su propio
    // grupo de procesos, pero bash mueve cada job en PRIMER PLANO (una TUI, un script) a su
    // propio grupo — un SIGKILL al shell no se propaga ahí, así que cualquier proceso hijo en
    // foreground queda huérfano sosteniendo el pty abierto por detrás, aunque la UI ya haya
    // ocultado la sesión. No se puede tocar finishIfRunning() (protegido), pero sí se puede
    // intentar un cierre limpio ANTES de la fuerza bruta: Ctrl-C (interrumpe cualquier job en
    // foreground) + "exit" con Enter real (cierra el shell solo, dejando que un trap EXIT/
    // cleanup del script corra) — mismo principio (gracia antes de fuerza) que el patrón
    // SIGTERM→esperar→SIGKILL real de referencia/interfaz/openclaw-termux-main/.../GatewayService.kt,
    // adaptado acá a un PTY en vez de un Process. removeFinishedSession() se llama YA (no
    // espera) — es solo housekeeping de la lista/drawer, no depende de que el proceso ya esté
    // muerto de verdad.
    public void stopSessionByName(String sessionName) {
        TerminalSession session = findSessionByName(sessionName);
        if (session == null || mTermuxTerminalSessionActivityClient == null) return;
        // Bug real reproducido por ADB (2026-08-25): el sidebar de la terminal
        // adaptada no cerraba la sesion por completo -- el Ctrl-C y el "exit" se
        // mandaban sin ninguna pausa entre los dos, sin darle tiempo al proceso en
        // foreground de procesar la interrupcion antes de que llegara "exit", asi que
        // ese "exit" podia terminar tragado por el propio proceso en foreground en
        // vez de llegar a un prompt de shell real. Fix: separar el "exit" en un
        // runnable con una pausa corta despues del Ctrl-C.
        try {
            session.write("");
            session.write(""); // Ctrl-C: interrumpe cualquier job en foreground
        } catch (Exception ignored) {
            // Sesion ya sin pty valido -- el finishIfRunning() de abajo la limpia igual.
        }
        Runnable sendExit = () -> {
            try {
                session.write("exit\r");
            } catch (Exception ignored) {
                // Sesion ya sin pty valido a esta altura -- no-op.
            }
        };
        if (mTerminalView != null) {
            mTerminalView.postDelayed(sendExit, 300);
        } else {
            sendExit.run();
        }
        mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
        if (mTerminalView != null) {
            mTerminalView.postDelayed(() -> killSessionProcessGroup(session), 1500);
        } else {
            killSessionProcessGroup(session);
        }
    }

    // Bug real confirmado en dispositivo (ADB, docs/humano246.md #5 "las terminales se siguen
    // quedando abiertas al darle cerrar sesión"): con una sesión adaptada abierta (ej. Claude
    // Code, bash PID padre + un hijo "claude" en foreground) se disparó "Cerrar sesión" y,
    // aunque el drawer se cerraba y la UI ya no mostraba la sesión, `ps` seguía mostrando AMBOS
    // procesos vivos varios segundos después. Causa raíz real (no solo Ctrl-C/exit tragado, ese
    // bug ya se había arreglado antes): `TerminalSession.finishIfRunning()`
    // (terminal-emulator/, protegido — ver CLAUDE.md "Protected Files") hace
    // `Os.kill(mShellPid, SIGKILL)` — un SIGKILL al PID del shell nada más. `termux.c` línea
    // ~78 llama `setsid()` antes del exec, así que el shell es líder de su propio grupo de
    // procesos (pgid == pid) — pero un SIGKILL a un PID puntual (positivo) mata SOLO ese
    // proceso, nunca al grupo. Cualquier hijo en foreground que bash ya movió a su propio
    // grupo (todo job en foreground real, no solo TUIs) queda huérfano, reparentado a init,
    // sosteniendo el pty — literalmente el síntoma reportado. No se puede tocar
    // finishIfRunning() (protegido), pero `TerminalSession.getPid()` es público — un SIGKILL a
    // `-pid` (PID negativo) es la syscall POSIX real para "matar todo el grupo de procesos", no
    // hace falta tocar terminal-emulator/ para eso. Se intenta el kill de grupo PRIMERO (mata
    // shell + cualquier hijo del grupo de una sola vez) y SIEMPRE se llama finishIfRunning()
    // después (idempotente si el PID ya murió — TerminalSession.isRunning() lo re-chequea) para
    // no perder el housekeeping normal (cleanupResources(), etc.) que ya hacía este call-site.
    private void killSessionProcessGroup(TerminalSession session) {
        if (session == null) return;
        int pid = session.getPid();
        if (pid > 0) {
            try {
                android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL);
            } catch (Exception ignored) {
                // ESRCH si el grupo ya no existe (proceso ya murió solo) -- no-op,
                // finishIfRunning() de abajo confirma el estado real igual.
            }
        }
        session.finishIfRunning();
    }

    // Bug real reportado (ver docs/humano231.md): el botón "Salir (detener todo y cerrar)" de
    // ConfigFragment solo corría scripts de stop de módulos (ModuleController.stopAllModules)
    // y mataba el proceso entero con exitProcess(0) sin ningún delay — nunca tocaba las
    // TerminalSession abiertas, así que cualquier pty con un job en foreground (una TUI, un
    // script) quedaba literalmente "esperando ENTER" (el mismo bug de humano91 que
    // stopSessionByName() ya arregla para una sesión puntual) hasta que Android mataba el
    // proceso de golpe. A diferencia de stopSessionByName() (que espera 300ms+1500ms POR
    // sesión, pensado para cerrar una sesión sin apurar), acá la app entera se está cerrando —
    // no tiene sentido esperar eso secuencial por N sesiones, así que se manda Ctrl-C+"exit" a
    // TODAS en paralelo con un único margen compartido antes de invocar onDone.
    public void requestExitAllSessions(@NonNull final Runnable onDone) {
        if (mTermuxService == null) {
            onDone.run();
            return;
        }
        final java.util.List<TermuxSession> sessions = mTermuxService.getTermuxSessions();
        if (sessions.isEmpty()) {
            onDone.run();
            return;
        }
        for (TermuxSession termuxSession : sessions) {
            TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;
            try {
                session.write("");
                session.write(""); // Ctrl-C: interrumpe cualquier job en foreground
            } catch (Exception ignored) {
                // Sesion ya sin pty valido -- se ignora, exitProcess(0) la limpia igual.
            }
        }
        Runnable sendExit = () -> {
            for (TermuxSession termuxSession : sessions) {
                TerminalSession session = termuxSession.getTerminalSession();
                if (session == null) continue;
                try {
                    session.write("exit\r");
                } catch (Exception ignored) {
                    // Sesion ya sin pty valido a esta altura -- no-op.
                }
            }
        };
        // Mismo bug/mismo fix que stopSessionByName() -> killSessionProcessGroup() (ver ese
        // docstring): matar el proceso Java de Kairos (exitProcess(0), llamado por onDone acá)
        // NO mata los procesos bash/hijos forkeados con setsid() -- son procesos Linux
        // independientes, quedan huérfanos reparentados a init en vez de morir con la app. Un
        // SIGKILL de GRUPO (PID negativo) por sesión, justo antes de onDone, cierra ese gap
        // también en el camino de "Salir" global, no solo al cerrar una sesión puntual.
        Runnable killAllProcessGroups = () -> {
            for (TermuxSession termuxSession : sessions) {
                killSessionProcessGroup(termuxSession.getTerminalSession());
            }
        };
        if (mTerminalView != null) {
            mTerminalView.postDelayed(sendExit, 300);
            mTerminalView.postDelayed(killAllProcessGroups, 500);
            mTerminalView.postDelayed(onDone, 550);
        } else {
            sendExit.run();
            killAllProcessGroups.run();
            onDone.run();
        }
    }

    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
