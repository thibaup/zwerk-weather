package com.zwerk.weather;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Pure-Java helpers for collision-resistant cache filenames and legacy file cleanup. */
final class CacheKeys {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CacheKeys() { }

    static String sha256Hex16(String input) {
        String safe = input == null ? "" : input;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[16];
            for (int i = 0; i < 8; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException unreachable) {
            long h1 = safe.hashCode() & 0xFFFFFFFFL;
            long h2 = (safe.length() * 2654435761L) & 0xFFFFFFFFL;
            return String.format(java.util.Locale.US, "%08x%08x", h1, h2);
        }
    }

    static void deleteLegacyFiles(File dir, String... prefixes) {
        if (dir == null || prefixes == null || prefixes.length == 0) return;
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Exception ignored) {
            return;
        }
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            for (String prefix : prefixes) {
                if (prefix != null && !prefix.isEmpty() && name.startsWith(prefix)) {
                    try {
                        file.delete();
                    } catch (Exception ignored) { }
                    break;
                }
            }
        }
    }
}
