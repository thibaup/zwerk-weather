package com.zwerk.weather;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Google requests made by scheduled jobs share the same local request budget. */
final class BackgroundWeatherRequest {
    static JSONObject request(Context context, String address, DiagnosticLog.Area area) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!ApiRequestBudgetManager.tryAcquire(context, ApiRequestBudgetManager.Category.WEATHER).allowed) {
            DiagnosticLog.event(area, DiagnosticLog.Event.REQUEST_LIMIT);
            throw new IllegalStateException("Request limit reached");
        }
        long diagnosticStart = android.os.SystemClock.elapsedRealtime();
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(18_000);
        connection.setRequestProperty("Accept", "application/json");
        RainAlertManager.applyAndroidApiKeyRestrictionHeaders(context, connection);
        try {
            int status = connection.getResponseCode();
            DiagnosticLog.http(area, status, android.os.SystemClock.elapsedRealtime() - diagnosticStart);
            if (status != 200) throw new IllegalStateException("Weather service unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + count > 2 * 1024 * 1024) throw new IllegalStateException("Response too large");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }
}
