package com.zwerk.weather;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class UpdateChecker {
    private static final String RELEASE_API =
            "https://api.github.com/repos/thibaup/zwerk-weather/releases/latest";
    private static final String RELEASES_PAGE =
            "https://github.com/thibaup/zwerk-weather/releases/latest";
    private static final String PREFS = "zwerk_update_checker";
    private static final String KEY_LAST_ATTEMPT = "last_attempt_ms";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_RESPONSE_CHARS = 512 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private UpdateChecker() { }

    static void checkForUpdates(Activity activity, boolean manual) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual) {
            long lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L);
            if (lastAttempt > 0L && now - lastAttempt >= 0L
                    && now - lastAttempt < AUTO_CHECK_INTERVAL_MS) {
                return;
            }
            prefs.edit().putLong(KEY_LAST_ATTEMPT, now).apply();
        }

        if (manual) Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            try {
                UpdateInfo update = fetchLatestRelease();
                String currentVersion = installedVersion(activity);
                boolean newer = compareVersions(update.version, currentVersion) > 0;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (newer) {
                        showUpdateDialog(activity, currentVersion, update);
                    } else if (manual) {
                        Toast.makeText(
                                activity,
                                "Zwerk Weather " + currentVersion + " is up to date.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception ignored) {
                if (!manual) return;
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(
                                activity,
                                "Couldn’t check for updates. Try again when you’re online.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private static UpdateInfo fetchLatestRelease() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Zwerk-Weather-Android");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("GitHub release check failed: HTTP " + status);
            }

            JSONObject release = new JSONObject(readResponse(connection.getInputStream()));
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                throw new IllegalStateException("Latest release is not a stable public release");
            }
            String tag = release.optString("tag_name", "").trim();
            String version = normalizeVersion(tag);
            if (version.isEmpty()) throw new IllegalStateException("Release has no version tag");

            String releaseUrl = release.optString("html_url", RELEASES_PAGE).trim();
            String downloadUrl = "";
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
                    String contentType = asset.optString("content_type", "");
                    if (name.endsWith(".apk")
                            || "application/vnd.android.package-archive".equals(contentType)) {
                        downloadUrl = asset.optString("browser_download_url", "").trim();
                        if (!downloadUrl.isEmpty()) break;
                    }
                }
            }
            boolean directApk = !downloadUrl.isEmpty();
            if (!directApk) downloadUrl = releaseUrl.isEmpty() ? RELEASES_PAGE : releaseUrl;
            String name = release.optString("name", "Zwerk Weather " + version).trim();
            return new UpdateInfo(version, name, downloadUrl, directApk);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readResponse(InputStream stream) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (body.length() + read > MAX_RESPONSE_CHARS) {
                    throw new IllegalStateException("GitHub response is too large");
                }
                body.append(buffer, 0, read);
            }
        }
        return body.toString();
    }

    private static String installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            return normalizeVersion(info.versionName);
        } catch (Exception ignored) {
            return "0";
        }
    }

    static int compareVersions(String left, String right) {
        int[] a = numericParts(normalizeVersion(left));
        int[] b = numericParts(normalizeVersion(right));
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] numericParts(String version) {
        if (version.isEmpty()) return new int[]{0};
        String core = version.split("[-+]", 2)[0];
        String[] pieces = core.split("\\.");
        int[] values = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            String digits = pieces[i].replaceAll("[^0-9]", "");
            try {
                values[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                values[i] = Integer.MAX_VALUE;
            }
        }
        return values;
    }

    private static String normalizeVersion(String version) {
        if (version == null) return "";
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized.trim();
    }

    private static void showUpdateDialog(
            Activity activity,
            String currentVersion,
            UpdateInfo update) {
        String message = update.name + " is available.\n\nInstalled version: "
                + currentVersion + "\nLatest version: " + update.version;
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage(message)
                .setNegativeButton("Later", null)
                .setPositiveButton(update.directApk ? "Download APK" : "View release", (dialog, which) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(update.url)));
                    } catch (Exception ignored) {
                        Toast.makeText(activity, "Couldn’t open the download link.", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private static final class UpdateInfo {
        final String version;
        final String name;
        final String url;
        final boolean directApk;

        UpdateInfo(String version, String name, String url, boolean directApk) {
            this.version = version;
            this.name = name;
            this.url = url;
            this.directApk = directApk;
        }
    }
}
