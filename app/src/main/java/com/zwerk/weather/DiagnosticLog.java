package com.zwerk.weather;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class DiagnosticLog {
    enum Area { APP, WEATHER, OPEN_METEO, PRECIPITATION, OPTIONAL, RADAR, MAP, WIDGET }
    enum Event {
        STARTED, REQUEST_LIMIT, RETRY, CACHE_HIT, CACHE_FALLBACK, INVALID_RESPONSE,
        WIDGET_UPDATE, WIDGET_RESIZE, REFRESH_STARTED, REFRESH_SUCCEEDED, REFRESH_PENDING,
        REFRESH_STOPPED, TIMELINE_READY, LOADING_TIMEOUT, PLAYBACK_STARTED, PLAYBACK_STOPPED,
        EXPORT_REQUESTED, EXPORT_SAVED, EXPORT_FAILED, SNAPSHOT_MISSING, CACHE_EXPIRED
    }

    static final int FILE_BYTES = 128 * 1024;
    private static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final String TAG = "ZwerkWeather";
    private static volatile Thread writerThread;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), runnable -> {
                writerThread = new Thread(runnable, "ZwerkDiagnostics");
                return writerThread;
            });
    private static volatile File directory;

    private DiagnosticLog() { }

    static synchronized void initialize(Context context) {
        if (directory != null) return;
        directory = new File(context.getFilesDir(), "diagnostics");
        directory.mkdirs();
        for (String name : new String[]{"current.log", "previous.log"}) {
            File file = new File(directory, name);
            if (file.lastModified() < System.currentTimeMillis() - RETENTION_MS) file.delete();
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { crash(error); }
            finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
        event(Area.APP, Event.STARTED);
    }

    static void event(Area area, Event event) {
        enqueue("I " + area + " " + event);
    }

    static void http(Area area, int status, long elapsedMs) {
        enqueue("I " + area + " HTTP status=" + status + " elapsedMs=" + Math.max(0L, elapsedMs));
    }

    static void error(Area area, Throwable error) {
        enqueue("E " + area + " " + frames(error, 4));
    }

    private static void enqueue(String safeEntry) {
        Log.i(TAG, safeEntry);
        String line = Instant.now() + " " + safeEntry + "\n";
        try { WRITER.execute(() -> write(line)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Logcat still receives the event when the bounded disk queue is full.
        }
    }

    static void crash(Throwable error) {
        String safeEntry = "E APP CRASH " + frames(error, 12);
        Log.e(TAG, safeEntry);
        flush();
        write(Instant.now() + " " + safeEntry + "\n");
    }

    private static boolean flush() {
        if (Thread.currentThread() == writerThread) return true;
        try {
            WRITER.submit(() -> { }).get(3L, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) { }
        return false;
    }

    // Exception messages can contain URLs, credentials and coordinates. Only code frames are kept.
    private static String frames(Throwable error, int limit) {
        StringBuilder text = new StringBuilder();
        for (int cause = 0; error != null && cause < 4; cause++, error = error.getCause()) {
            if (cause > 0) text.append(" causedBy=");
            text.append(error.getClass().getName());
            StackTraceElement[] stack = error.getStackTrace();
            for (int i = 0; i < Math.min(limit, stack.length); i++) {
                text.append("\n  at ").append(stack[i].getClassName()).append('.')
                        .append(stack[i].getMethodName()).append(':').append(stack[i].getLineNumber());
            }
        }
        return text.toString();
    }

    private static synchronized void write(String line) {
        if (directory == null) return;
        try { append(directory, line); }
        catch (IOException | RuntimeException ignored) {
            Log.w(TAG, "Diagnostic log write failed");
        }
    }

    static void append(File folder, String line) throws IOException {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FILE_BYTES) return;
        File current = new File(folder, "current.log");
        if (current.length() + bytes.length > FILE_BYTES) {
            Files.move(current.toPath(), new File(folder, "previous.log").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        try (FileOutputStream stream = new FileOutputStream(current, true)) { stream.write(bytes); }
    }

    static String report(Context context) throws IOException {
        boolean flushed = flush();
        StringBuilder report = new StringBuilder("Zwerk Weather diagnostic report\n");
        report.append("Exported: ").append(Instant.now()).append("\n")
                .append("App: ").append(BuildConfig.VERSION_NAME).append(" (")
                .append(BuildConfig.VERSION_CODE).append(")\n")
                .append("Android: ").append(Build.VERSION.RELEASE).append(" / API ")
                .append(Build.VERSION.SDK_INT).append("\n")
                .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
                .append("Android background access: ").append(WidgetBackgroundAccess.state(context)).append("\n")
                .append("Widget background access confirmed by user: ")
                .append(WidgetBackgroundAccess.preferences(context)
                        .getBoolean(WidgetBackgroundAccess.PREF_CONFIRMED, false)).append("\n")
                .append("Weather provider: ").append(OpenMeteoConfig.isOpenMeteo(context) ? "Open-Meteo" : "Google")
                .append("\nPrecipitation provider: ").append(OpenMeteoConfig.OPEN_METEO.equals(
                        OpenMeteoConfig.precipitationProvider(context)) ? "Open-Meteo" : "Google")
                .append("\n\nAPI keys, URLs, response bodies and locations are not recorded.\n")
                .append("Times are UTC. Logs rotate at 128 KiB per file; two files are retained.\n");
        if (!flushed) report.append("Some queued events may not yet be included.\n");
        report.append("\n--- Events ---\n");
        synchronized (DiagnosticLog.class) {
            if (directory != null) {
                for (String name : new String[]{"previous.log", "current.log"}) {
                    File file = new File(directory, name);
                    if (file.isFile()) report.append(new String(Files.readAllBytes(file.toPath()),
                            StandardCharsets.UTF_8));
                }
            }
        }
        return report.toString();
    }
}
