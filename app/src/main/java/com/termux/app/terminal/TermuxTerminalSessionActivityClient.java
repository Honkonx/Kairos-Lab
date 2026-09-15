package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.text.TextUtils;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.io.BellHandler;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/** The {@link TerminalSessionClient} implementation that may require an {@link Activity} for its interface methods. */
public class TermuxTerminalSessionActivityClient extends TermuxTerminalSessionClientBase {

    private final TermuxActivity mActivity;

    private static final int MAX_SESSIONS = 8;

    private SoundPool mBellSoundPool;

    private int mBellSoundId;

    private static final String LOG_TAG = "TermuxTerminalSessionActivityClient";

    public TermuxTerminalSessionActivityClient(TermuxActivity activity) {
        this.mActivity = activity;
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by {@link #onStop} if its valid,
        // otherwise get the last session currently running.
        if (mActivity.getTermuxService() != null) {
            setCurrentSession(getCurrentStoredSessionOrLast());
            termuxSessionListNotifyUpdated();
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        mActivity.getTerminalView().onScreenUpdated();
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        loadBellSoundPool();
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        setCurrentStoredSession();

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }



    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        // Antes de isVisible(): la notificación de "sesión necesita atención" (whispercode-dev,
        // ver docs/referencias/REFERENCIA_WHISPERCODE.md) tiene que evaluarse SIEMPRE, no solo cuando la
        // Activity está visible — el caso que más importa avisar es justo cuando el usuario
        // salió de la app del todo (isVisible()==false), lo opuesto al resto de este método.
        mActivity.onBackgroundSessionOutput(changedSession);

        if (!mActivity.isVisible()) return;

        if (mActivity.getCurrentSession() == changedSession) mActivity.getTerminalView().onScreenUpdated();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        if (!mActivity.isVisible()) return;

        if (updatedSession != mActivity.getCurrentSession()) {
            // Only show toast for other sessions than the current one, since the user
            // probably consciously caused the title change to change in the current session
            // and don't want an annoying toast for that.
            mActivity.showToast(toToastTitle(updatedSession), true);
        }

        termuxSessionListNotifyUpdated();
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        // Log interno de Kairos, nivel FULL — actividad de terminal (ver docs/humano231.md,
        // ConfigFragment "Log Kairos"). Solo observa, no cambia el comportamiento real de cierre.
        com.termux.app.util.KairosLogger.log(
            mActivity, "Terminal",
            "onSessionFinished() - sesion cerrada: " + finishedSession.mSessionName
                + " (exitStatus=" + finishedSession.getExitStatus() + ")",
            com.termux.app.util.KairosLogger.Level.FULL
        );

        TermuxService service = mActivity.getTermuxService();

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing();
            return;
        }

        int index = service.getIndexOfSession(finishedSession);

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        boolean isPluginExecutionCommandWithPendingResult = false;
        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult();
            if (isPluginExecutionCommandWithPendingResult)
                Logger.logVerbose(LOG_TAG, "The \"" + finishedSession.mSessionName + "\" session will be force finished automatically since result in pending.");
        }

        if (mActivity.isVisible() && finishedSession != mActivity.getCurrentSession()) {
            // Show toast for non-current sessions that exit.
            // Verify that session was not removed before we got told about it finishing:
            if (index >= 0)
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true);
        }

        if (mActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.getTermuxSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            //
            // Bug real confirmado por log de dispositivo (2026-08-27, ver docs/humano256.md):
            // "las terminales siguen sin cerrarse por completo... sale 'Process completed
            // (signal 9) - press enter'". kairos_app.log mostraba, para TODAS las sesiones
            // cerradas con el botón "Cerrar sesión"/"Salir" (killSessionProcessGroup(), agregado
            // ayer): "onSessionFinished() - sesion cerrada: <nombre> (exitStatus=-9)". Causa
            // raíz real: termux.c línea ~208 devuelve `-WTERMSIG(status)` cuando el shell termina
            // por señal en vez de salir solo — un SIGKILL (9) da exitStatus=-9 SIEMPRE, nunca 0
            // ni 130. La condición de auto-cierre de abajo es la de termux-app original, pensada
            // solo para un "exit" tipeado a mano (0) o un Ctrl-C que bash re-eleva como 130 —
            // nunca contempló un cierre forzado por la propia app (killSessionProcessGroup()).
            // Resultado: la sesión NUNCA se removía de la lista tras "Cerrar sesión", quedaba
            // mostrando el mensaje "Process completed (signal 9) - press enter" indefinidamente
            // (el usuario tenía que dismissarlo a mano, y volvía a aparecer al reabrir). Fix:
            // cualquier exitStatus negativo (terminación por señal, sea nuestro SIGKILL de grupo
            // u otra señal real como un OOM-kill) también dispara el auto-cierre — no hay ningún
            // caso legítimo en el que dejar "press enter" colgado para una sesión que ya no tiene
            // proceso vivo sea el comportamiento deseado.
            if (finishedSession.getExitStatus() == 0 || finishedSession.getExitStatus() == 130
                || finishedSession.getExitStatus() < 0 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        if (!mActivity.isVisible()) return;

        ShareUtils.copyTextToClipboard(mActivity, text);
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (!mActivity.isVisible()) return;

        String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
        if (text == null) return;

        // Mismo camino de revisión que TermuxTerminalViewClient.doPaste() — este es el paste
        // que dispara el menú contextual "Pegar" de la selección de texto (ver
        // TextSelectionCursorController.java -> TerminalSession.onPasteTextFromClipboard() ->
        // acá). Ver docs/referencias/terminal/REFERENCIA_TTYX.md.
        com.termux.app.util.PasteSafetyUtils.reviewAndPaste(mActivity, text, () -> mActivity.getTerminalView().mEmulator.paste(text));
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        if (!mActivity.isVisible()) return;

        switch (mActivity.getProperties().getBellBehaviour()) {
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE:
                BellHandler.getInstance(mActivity).doBell();
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP:
                loadBellSoundPool();
                if (mBellSoundPool != null)
                    mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE:
                // Ignore the bell character.
                break;
        }
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
        if (mActivity.getCurrentSession() == changedSession)
            updateBackgroundColor();
    }

    @Override
    public void onTerminalCursorStateChange(boolean enabled) {
        // Do not start cursor blinking thread if activity is not visible
        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible");
            return;
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        mActivity.getTerminalView().setTerminalCursorBlinkerState(enabled, false);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }


    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    public void onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
    }



    @Override
    public Integer getTerminalCursorStyle() {
        return mActivity.getProperties().getTerminalCursorStyle();
    }



    /** Load mBellSoundPool */
    private synchronized void loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();

            try {
                mBellSoundId = mBellSoundPool.load(mActivity, com.termux.shared.R.raw.bell, 1);
            } catch (Exception e){
                // Catch java.lang.RuntimeException: Unable to resume activity {com.termux/com.termux.app.TermuxActivity}: android.content.res.Resources$NotFoundException: File res/raw/bell.ogg from drawable resource ID
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e);
            }
        }
    }

    /** Release mBellSoundPool resources */
    private synchronized void releaseBellSoundPool() {
        if (mBellSoundPool != null) {
            mBellSoundPool.release();
            mBellSoundPool = null;
        }
    }



    /** Try switching to session. */
    public void setCurrentSession(TerminalSession session) {
        if (session == null) return;

        if (mActivity.getTerminalView().attachSession(session)) {
            // notify about switched session if not already displaying the session
            notifyOfSessionChange();
        }

        // We call the following even when the session is already being displayed since config may
        // be stale, like current session not selected or scrolled to.
        checkAndScrollToSession(session);
        updateBackgroundColor();

        // Fix real (auditoría QA 2026-09-14, docs/humano338.md): esta función nunca llamaba
        // checkForFontAndColors() — el reset real de paleta (session.getEmulator().mColors
        // .reset()) solo corría en las 2 transiciones de modo (ver los otros 2 call-sites de
        // checkForFontAndColors() en este archivo), no acá. Si openTerminalWithCommand()
        // aplica el tema del modo adaptado y RECIÉN DESPUÉS adjunta una sesión ya existente
        // (creada antes de ese cambio de tema) vía setCurrentSession(), esa sesión podía
        // quedar con la paleta vieja hasta el próximo cambio de modo real — sin depender del
        // orden exacto entre "aplicar modo" y "adjuntar sesión". checkForFontAndColors() ya
        // es idempotente (mismo criterio que sus otros 2 usos) y relee un archivo de
        // propiedades chico — costo aceptable en cada cambio de sesión/tab.
        checkForFontAndColors();
    }

    void notifyOfSessionChange() {
        if (!mActivity.isVisible()) return;

        if (!mActivity.getProperties().areTerminalSessionChangeToastsDisabled()) {
            TerminalSession session = mActivity.getCurrentSession();
            mActivity.showToast(toToastTitle(session), false);
        }
    }

    public void switchToSession(boolean forward) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession currentTerminalSession = mActivity.getCurrentSession();
        int index = service.getIndexOfSession(currentTerminalSession);
        int size = service.getTermuxSessionsSize();
        if (forward) {
            if (++index >= size) index = 0;
        } else {
            if (--index < 0) index = size - 1;
        }

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    public void switchToSession(int index) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    @SuppressLint("InflateParams")
    public void renameSession(final TerminalSession sessionToRename) {
        if (sessionToRename == null) return;

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName, R.string.action_rename_session_confirm, text -> {
            renameSession(sessionToRename, text);
            termuxSessionListNotifyUpdated();
        }, -1, null, -1, null, null);
    }

    private void renameSession(TerminalSession sessionToRename, String text) {
        if (sessionToRename == null) return;
        sessionToRename.mSessionName = text;
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename);
            if (termuxSession != null)
                termuxSession.getExecutionCommand().shellName = text;
        }
    }

    public void addNewSession(boolean isFailSafe, String sessionName) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
        } else {
            TerminalSession currentSession = mActivity.getCurrentSession();

            String workingDirectory;
            if (currentSession == null) {
                workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
            } else {
                workingDirectory = currentSession.getCwd();
            }

            TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName);
            if (newTermuxSession == null) return;

            TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
            setCurrentSession(newTerminalSession);

            // Log interno de Kairos, nivel FULL — actividad de terminal (ver docs/humano231.md,
            // ConfigFragment "Log Kairos"). Solo observa, no cambia ningún comportamiento real
            // de la sesión.
            com.termux.app.util.KairosLogger.log(
                mActivity, "Terminal",
                "addNewSession() - sesion abierta: " + newTerminalSession.mSessionName,
                com.termux.app.util.KairosLogger.Level.FULL
            );

            mActivity.getDrawer().closeDrawers();
        }
    }

    public void setCurrentStoredSession() {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle);
        else
            mActivity.getPreferences().setCurrentSession(null);
    }

    /** The current session as stored or the last one if that does not exist. */
    public TerminalSession getCurrentStoredSessionOrLast() {
        TerminalSession stored = getCurrentStoredSession();

        if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            return stored;
        } else {
            // Else return the last session currently running
            TermuxService service = mActivity.getTermuxService();
            if (service == null) return null;

            TermuxSession termuxSession = service.getLastTermuxSession();
            if (termuxSession != null)
                return termuxSession.getTerminalSession();
            else
                return null;
        }
    }

    private TerminalSession getCurrentStoredSession() {
        String sessionHandle = mActivity.getPreferences().getCurrentSession();

        // If no session is stored in shared preferences
        if (sessionHandle == null)
            return null;

        // Check if the session handle found matches one of the currently running sessions
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        return service.getTerminalSessionForHandle(sessionHandle);
    }

    public void removeFinishedSession(TerminalSession finishedSession) {
        // Return pressed with finished session - remove it.
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        int index = service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing();
        } else {
            if (index >= size) {
                index = size - 1;
            }
            TermuxSession termuxSession = service.getTermuxSession(index);
            if (termuxSession != null)
                setCurrentSession(termuxSession.getTerminalSession());
        }
    }

    public void termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated();
    }

    public void checkAndScrollToSession(TerminalSession session) {
        if (!mActivity.isVisible()) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return;
        final ListView termuxSessionsListView = mActivity.findViewById(R.id.terminal_sessions_list);
        if (termuxSessionsListView == null) return;

        termuxSessionsListView.setItemChecked(indexOfSession, true);
        // Delay is necessary otherwise sometimes scroll to newly added session does not happen
        termuxSessionsListView.postDelayed(() -> termuxSessionsListView.smoothScrollToPosition(indexOfSession), 1000);
    }


    String toToastTitle(TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return null;
        StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName);
        }
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(session.mSessionName == null ? " " : "\n");
            toastTitle.append(title);
        }
        return toastTitle.toString();
    }


    public void checkForFontAndColors() {
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;

            final Properties props = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props);
            TerminalSession session = mActivity.getCurrentSession();
            if (session != null && session.getEmulator() != null) {
                session.getEmulator().mColors.reset();
            }
            updateBackgroundColor();

            final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0) ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            mActivity.getTerminalView().setTypeface(newTypeface);

            // Bug real reportado por el usuario: el selector de temas escribía
            // colors.properties y este método releía los colores nuevos a
            // mColors/TerminalColors.COLOR_SCHEME correctamente, pero nada forzaba
            // un repaint real de TerminalView — mColors.reset() solo actualiza el
            // array en memoria, TerminalView.onDraw() solo repinta cuando algo llama
            // invalidate() (ver terminal-view/.../TerminalView.java). Sin este
            // invalidate() el cambio de tema quedaba "aplicado" pero invisible hasta
            // que otro evento (tipear, blink del cursor, scroll) disparara un redraw
            // por su cuenta — el usuario lo reportó como "vi la opción pero no
            // funciona" (ver docs/humano* de esta ronda).
            mActivity.getTerminalView().invalidate();

            applyTerminalBackgroundImageOrColor(props);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e);
        }
    }

    /**
     * Fondo de imagen de terminal — pedido explícito del usuario ("cambiar el fondo por una
     * imagen"). Viable SIN tocar terminal-view/ (protegido, ver CLAUDE.md § Protected Files):
     * TerminalRenderer.drawTextRun() (terminal-view/.../TerminalRenderer.java, comentario real
     * "Only draw non-default background.") solo pinta un rect de fondo por celda cuando su
     * color difiere del color de fondo DEFAULT del tema activo — las celdas con fondo default
     * nunca se pintan. TerminalView tampoco fuerza un canvas.drawColor() propio salvo en modo
     * reverseVideo (ver TerminalView.onDraw()/TerminalRenderer.render()) ni tiene
     * android:background propio en activity_termux.xml — esas celdas ya son transparentes por
     * diseño y muestran lo que haya DETRÁS de TerminalView en el layout. Se aprovecha eso: un
     * ImageView (R.id.terminal_bg_image, agregado en activity_termux.xml como hermano ANTERIOR
     * a terminal_view dentro del mismo DrawerLayout — DrawerLayout no exige un único content
     * view, se comporta como FrameLayout para los hijos sin layout_gravity) queda visible a
     * través de esas celdas transparentes cuando hay una imagen elegida.
     *
     * Contrapartida real de ese mismo hallazgo: sin imagen activa hay que fijar
     * terminal_view.setBackgroundColor() al "background=" real que colors.properties tiene en
     * este momento (reusa el mismo Properties que updateWith() ya parseó arriba, sin releer el
     * archivo) — si no, las celdas "vacías" del tema elegido (la mayoría de la pantalla) se
     * verían con el color de fondo de la APP (kairosBg), no con el fondo real del tema de
     * terminal elegido. Esto cubre tanto los temas curados (showTerminalThemePickerDialog())
     * como el forzado de Tokyo Night del modo adaptado (TermuxActivity#applyAdaptedTerminalColors())
     * — ambos escriben colors.properties y pasan por este mismo método, sin duplicar lógica.
     */
    private void applyTerminalBackgroundImageOrColor(Properties props) {
        android.widget.ImageView bgImage = mActivity.findViewById(R.id.terminal_bg_image);
        android.content.SharedPreferences kairosPrefs = mActivity.getSharedPreferences("kairos_prefs", Context.MODE_PRIVATE);
        String imageUriString = kairosPrefs.getString("terminal_bg_image_uri", null);
        if (imageUriString != null && bgImage != null) {
            try {
                bgImage.setImageURI(android.net.Uri.parse(imageUriString));
                bgImage.setVisibility(android.view.View.VISIBLE);
                mActivity.getTerminalView().setBackgroundColor(android.graphics.Color.TRANSPARENT);
                return;
            } catch (SecurityException e) {
                // El permiso persistido sobre la imagen se pudo haber revocado (ej. el usuario
                // borró el archivo original, o Android liberó el permiso) — se descarta en vez
                // de dejar un ImageView roto/en blanco tapando la terminal.
                Logger.logStackTraceWithMessage(LOG_TAG, "Permiso perdido sobre la imagen de fondo de terminal, se descarta", e);
                kairosPrefs.edit().remove("terminal_bg_image_uri").apply();
            }
        }
        if (bgImage != null) bgImage.setVisibility(android.view.View.GONE);
        String backgroundHex = props.getProperty("background");
        if (backgroundHex != null && !backgroundHex.isEmpty()) {
            try {
                mActivity.getTerminalView().setBackgroundColor(android.graphics.Color.parseColor(backgroundHex));
                return;
            } catch (IllegalArgumentException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "background= inválido en colors.properties: " + backgroundHex, e);
            }
        }
        // Sin tema/imagen (colors.properties no tiene "background=", caso "Por defecto"):
        // mismo comportamiento que existía antes de esta ronda, deja ver kairosBg detrás.
        mActivity.getTerminalView().setBackgroundColor(android.graphics.Color.TRANSPARENT);
    }

    public void updateBackgroundColor() {
        if (!mActivity.isVisible()) return;
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            mActivity.getWindow().getDecorView().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

}
