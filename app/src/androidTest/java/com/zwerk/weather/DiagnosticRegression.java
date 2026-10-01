package com.zwerk.weather;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class DiagnosticRegression {
    private int checks;

    int run(Context context) throws Exception {
        rotation(context);
        DiagnosticLog.report(context);
        File folder = new File(context.getFilesDir(), "diagnostics");
        File current = new File(folder, "current.log");
        File previous = new File(folder, "previous.log");
        byte[] currentBefore = current.isFile() ? Files.readAllBytes(current.toPath()) : null;
        byte[] previousBefore = previous.isFile() ? Files.readAllBytes(previous.toPath()) : null;
        try {
            privacy(context);
            ordering(context);
        } finally {
            DiagnosticLog.report(context);
            synchronized (DiagnosticLog.class) {
                restore(current, currentBefore);
                restore(previous, previousBefore);
            }
        }
        return checks;
    }

    private void rotation(Context context) throws Exception {
        File folder = new File(context.getCacheDir(), "diagnostic-regression");
        folder.mkdirs();
        File current = new File(folder, "current.log");
        File previous = new File(folder, "previous.log");
        current.delete();
        previous.delete();
        try {
            String full = "a".repeat(DiagnosticLog.FILE_BYTES - 1) + "\n";
            DiagnosticLog.append(folder, full);
            check(current.length() == DiagnosticLog.FILE_BYTES, "exact size limit must be accepted");
            check(!previous.exists(), "first full file must not rotate early");
            String unicode = "next é\n";
            DiagnosticLog.append(folder, unicode);
            check(previous.length() == DiagnosticLog.FILE_BYTES, "rotation must retain the full previous file");
            check(new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8).equals(unicode),
                    "rotation must preserve UTF-8");
            DiagnosticLog.append(folder, full);
            check(new String(Files.readAllBytes(previous.toPath()), StandardCharsets.UTF_8).equals(unicode),
                    "second rotation must replace the oldest file");
            check(current.length() + previous.length() <= 2L * DiagnosticLog.FILE_BYTES,
                    "combined log size must stay bounded");
            DiagnosticLog.append(folder, full + "oversized");
            check(current.length() == DiagnosticLog.FILE_BYTES, "oversized entries must be skipped");
            check(folder.list().length == 2, "only two log files must be retained");
        } finally {
            current.delete();
            previous.delete();
            folder.delete();
        }
    }

    private void privacy(Context context) throws Exception {
        String secret = "DIAGNOSTIC_PRIVATE_MESSAGE";
        IllegalArgumentException cause = new IllegalArgumentException(secret + " latitude=51.501 longitude=-0.142");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("ExampleSource", "fetch", secret + ".java", 42)});
        IllegalStateException failure = new IllegalStateException(
                secret + " https://example.invalid?key=PRIVATE_API_KEY", cause);
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("ExampleScreen", "refresh", secret + ".java", 23)});
        DiagnosticLog.error(DiagnosticLog.Area.WEATHER, failure);
        String report = DiagnosticLog.report(context);
        check(report.contains("App: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"),
                "report must identify the build");
        check(report.contains("Android:") && report.contains("Device:"), "report must identify the platform");
        check(report.contains("ExampleScreen.refresh:23"), "safe exception frames must remain useful");
        check(report.contains("causedBy=java.lang.IllegalArgumentException"), "cause classes must be retained");
        check(report.contains("ExampleSource.fetch:42"), "safe cause frames must be retained");
        check(!report.contains(secret), "messages and source filenames must be excluded");
        check(!report.contains("PRIVATE_API_KEY"), "API keys in messages must be excluded");
        check(!report.contains("example.invalid"), "URLs in messages must be excluded");
        check(!report.contains("51.501") && !report.contains("-0.142"), "coordinates in messages must be excluded");
    }

    private void ordering(Context context) throws Exception {
        for (int i = 0; i < 100; i++) DiagnosticLog.http(DiagnosticLog.Area.APP, 800 + i, i);
        DiagnosticLog.crash(new IllegalStateException("DIAGNOSTIC_PRIVATE_MESSAGE"));
        String report = DiagnosticLog.report(context);
        int position = -1;
        for (int i = 0; i < 100; i++) {
            int next = report.indexOf("APP HTTP status=" + (800 + i) + " elapsedMs=" + i, position + 1);
            check(next > position, "accepted events must persist in FIFO order before the crash");
            position = next;
        }
        check(report.indexOf("APP CRASH", position) > position, "crash must follow preceding queued events");
        check(!report.contains("DIAGNOSTIC_PRIVATE_MESSAGE"), "crash messages must be excluded");
        check(!report.contains("Some queued events"), "normal exports must flush the queue");
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void restore(File file, byte[] bytes) throws Exception {
        if (bytes == null) file.delete();
        else Files.write(file.toPath(), bytes);
    }
}
