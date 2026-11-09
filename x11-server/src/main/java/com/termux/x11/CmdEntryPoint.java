package com.termux.x11;

import static android.system.Os.getuid;
import static android.system.Os.getenv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Keep;

import java.io.DataInputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.Executors;

@Keep
@SuppressLint({"StaticFieldLeak", "UnsafeDynamicallyLoadedCode"})
public class CmdEntryPoint extends ICmdEntryInterface.Stub {
    public static final String ACTION_START = "com.termux.x11.CmdEntryPoint.ACTION_START";
    public static final int PORT = 7893;
    //    public static final int PORT = 7892;
    public static final byte[] MAGIC = "0xDEADBEEF".getBytes();
    private static final Handler handler;
    public static Context ctx;
    public static boolean initFlag = false;
    // Kairos: "display" real de ESTA instancia (ej. ":1"/":2") -- antes el comentario de
    // sendBroadcast() decía textualmente "We should not care about multiple instances" porque
    // termux-x11 standalone nunca corre 2 a la vez. Kairos sí lo hace (ver EntornoNative.kt,
    // "2 instancias X11 conmutables" -- nativa en :1 vía X11Service, distro en :2 vía
    // X11Service2, cada una en su propio proceso Android, mismo .so prebuilt libXlorie
    // cargado 2 veces en 2 espacios de memoria distintos -- sin colisión esperada porque el
    // display number ya es lo que el binario nativo usa para derivar rutas de socket/recursos
    // propias de X, confirmado por el propio uso existente de args[0] en main()). Sin esto, el
    // visor (KairosX11MainActivity) no tendría forma de saber de CUÁL de las 2 instancias vino
    // un broadcast — las 2 usan la MISMA action ACTION_START.
    private final String display;

    static {
        try {
            ctx = createContext();
        } catch (Throwable e) {
            Log.e("Context", "Failed to instantiate context:", e);
            ctx = null;
        }
    }

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        Log.i("CmdEntryPoint", "commit " + BuildConfig.COMMIT);
        handler.post(() -> new CmdEntryPoint(args));
        Looper.loop();
    }

    CmdEntryPoint(String[] args) {
        Log.i("CmdEntryPoint", "x11 port: " + Arrays.toString(args));
        this.display = (args.length > 0 && args[0] != null) ? args[0] : ":1";
        if (!start(args))
            System.exit(1);

//        spawnListeningThread();
        sendBroadcastDelayed();
    }

    @SuppressLint({"WrongConstant", "PrivateApi"})
    void sendBroadcast() {
        String targetPackage = getenv("TERMUX_DISPLAY_OVERRIDE_PACKAGE");
        if (targetPackage == null)
            targetPackage = "com.termux";
        // We should not care about multiple instances, it should be called only by `Termux:X11` app
        // which is single instance... (Kairos SÍ corre múltiples instancias a propósito -- ver
        // el campo "display" arriba. El extra "kairos_display" abajo es lo que distingue una
        // broadcast de la otra sin romper el contrato original de termux-x11 -- una app que no
        // lo lea (comportamiento upstream) simplemente lo ignora.)
        Bundle bundle = new Bundle();
        bundle.putBinder("", this);

        Intent intent = new Intent(ACTION_START);
        intent.putExtra("", bundle);
        intent.putExtra("kairos_display", display);
        intent.setPackage(targetPackage);

        if (getuid() == 0 || getuid() == 2000)
            intent.setFlags(0x00400000 /* FLAG_RECEIVER_FROM_SHELL */);

        try {
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            if (e instanceof NullPointerException && ctx == null)
                Log.i("Broadcast", "Context is null, falling back to manual broadcasting");
            else
                Log.e("Broadcast", "Falling back to manual broadcasting, failed to broadcast intent through Context:", e);

            // Kairos: el bloque original de fallback usaba clases ocultas del framework
            // (android.app.IActivityManager, android.content.IIntentSender/IIntentReceiver)
            // que NO existen en android.jar del SDK (ni de compileSdk 36) y por lo tanto no
            // compilaban. En Kairos el servidor X corre SIEMPRE dentro del proceso Android
            // (:xserver, vía X11Service) donde ctx.sendBroadcast() funciona — el fallback
            // manual solo era necesario para el caso de arranque vía shell (app_process),
            // que Kairos no usa. Ver docs/x11/X11_EMBEBIDO.md.
            Log.e("Broadcast", "Manual broadcast fallback removed in Kairos (hidden framework APIs)");
        }
    }

    // In some cases Android Activity part can not connect opened port.
    // In this case opened port works like a lock file.
    private void sendBroadcastDelayed() {
        if (!connected())
            sendBroadcast();

        handler.postDelayed(this::sendBroadcastDelayed, 1000);
    }

    void spawnListeningThread() {
        new Thread(() -> { // New thread is needed to avoid android.os.NetworkOnMainThreadException
            /*
                The purpose of this function is simple. If the application has not been launched
                before running termux-x11, the initial sendBroadcast had no effect because no one
                received the intent. To allow the application to reconnect freely, we will listen on
                port `PORT` and when receiving a magic phrase, we will send another intent.
             */
            Log.e("CmdEntryPoint", "Listening port " + PORT);
            try (ServerSocket listeningSocket =
                     new ServerSocket(PORT, 0, InetAddress.getByName("127.0.0.1"))) {
                listeningSocket.setReuseAddress(true);
                while (true) {
                    try (Socket client = listeningSocket.accept()) {
                        Log.e("CmdEntryPoint", "Somebody connected!");
                        // We should ensure that it is some
                        byte[] b = new byte[MAGIC.length];
                        DataInputStream reader = new DataInputStream(client.getInputStream());
                        reader.readFully(b);
                        if (Arrays.equals(MAGIC, b)) {
                            Log.e("CmdEntryPoint", "New client connection!");
                            sendBroadcast();
                        }
                    } catch (Exception e) {
                        e.printStackTrace(System.err);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }).start();
    }

    public static void requestConnection() {
        System.err.println("Requesting connection...");
        new Thread(() -> { // New thread is needed to avoid android.os.NetworkOnMainThreadException
            try (Socket socket = new Socket("127.0.0.1", CmdEntryPoint.PORT)) {
                socket.getOutputStream().write(CmdEntryPoint.MAGIC);
            } catch (ConnectException e) {
                if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                    Log.e("CmdEntryPoint", "ECONNREFUSED: Connection has been refused by the server");
                } else
                    Log.e("CmdEntryPoint", "Something went wrong when we requested connection", e);
            } catch (Exception e) {
                Log.e("CmdEntryPoint", "Something went wrong when we requested connection", e);
            }
        }).start();
    }

    /**
     * @noinspection DataFlowIssue
     */
    @SuppressLint("DiscouragedPrivateApi")
    public static Context createContext() {
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            // Kairos: android.app.ActivityThread es API oculta (no existe en android.jar del SDK),
            // por lo que se accede por reflexión pura en vez de cast directo. En runtime el método
            // existe y funciona igual. Ver docs/x11/X11_EMBEBIDO.md.
            Object activityThread = Class.forName("sun.misc.Unsafe")
                .getMethod("allocateInstance", Class.class)
                .invoke(unsafe, Class.forName("android.app.ActivityThread"));
            return (Context) activityThread.getClass().getMethod("getSystemContext").invoke(activityThread);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static native boolean start(String[] args);

    public native void windowChanged(Surface surface, String name);

    public native ParcelFileDescriptor getXConnection();

    public native ParcelFileDescriptor getLogcatOutput();

    private static native boolean connected();
    //cgefhdrwfger

    static {
        // Kairos: en vez del getResource("lib/<abi>/libXlorie.so") + System.load() original
        // (que exige extractNativeLibs=false / .so sin comprimir en el APK), se usa
        // System.loadLibrary("Xlorie") que resuelve libXlorie.so desde nativeLibraryDir
        // (funciona con el packaging legacy de Kairos, useLegacyPackaging true). El servidor
        // X11 corre SIEMPRE dentro del proceso Android (:xserver), nunca desde la shell, así
        // que no hace falta el branch del shell-loader (MainActivity.getInstance() == null).
        // Ver docs/x11/X11_EMBEBIDO.md.
        try {
            System.loadLibrary("Xlorie");
        } catch (UnsatisfiedLinkError e) {
            Log.e("CmdEntryPoint", "Failed to dlopen libXlorie.so via System.loadLibrary", e);
            System.err.println("Failed to load native library. Did you install the right apk? Try the universal one.");
            System.exit(134);
        }

        try {
            if (Looper.getMainLooper() == null)
                Looper.prepareMainLooper();
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Something went wrong when preparing MainLooper", e);
        }
        handler = new Handler();
    }
}
