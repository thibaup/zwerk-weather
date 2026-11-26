package com.zwerk.weather;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.system.Os;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores API keys encrypted at rest with Android Keystore AES/GCM. */
final class ApiKeyStore {
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "zwerk_api_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ENC_PREFIX = "ENC1:";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_KEY_FILE_BYTES = 16 * 1024;
    private static final Object LOCK = new Object();

    private ApiKeyStore() { }

    static String read(Context context, String fileName) {
        if (context == null || fileName == null || fileName.trim().isEmpty()) return "";
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        synchronized (LOCK) {
            File file = new File(app.getFilesDir(), fileName);
            if (!file.isFile()) return "";
            String raw = readUtf8Trimmed(file);
            if (raw.isEmpty()) return "";
            if (raw.startsWith(ENC_PREFIX)) {
                return decrypt(raw.substring(ENC_PREFIX.length()));
            }
            // Legacy plaintext key: transparently re-encrypt in place if Keystore succeeds.
            try {
                String encrypted = encrypt(raw);
                if (encrypted != null) {
                    writeAtomically(app.getFilesDir(), fileName, encrypted);
                }
            } catch (Exception ignored) { }
            return raw;
        }
    }

    static void write(Context context, String fileName, String value) {
        if (context == null || fileName == null || fileName.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        String trimmed = value == null ? "" : value.trim();
        synchronized (LOCK) {
            if (trimmed.isEmpty()) {
                deleteLocked(app.getFilesDir(), fileName);
                return;
            }
            String payload = trimmed;
            try {
                String encrypted = encrypt(trimmed);
                if (encrypted != null) payload = encrypted;
            } catch (Exception ignored) {
                // Fall back to plaintext only if the OEM keystore is broken.
            }
            try {
                writeAtomically(app.getFilesDir(), fileName, payload);
            } catch (Exception ignored) { }
        }
    }

    static void delete(Context context, String fileName) {
        if (context == null || fileName == null || fileName.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        synchronized (LOCK) {
            deleteLocked(app.getFilesDir(), fileName);
        }
    }

    private static void deleteLocked(File dir, String fileName) {
        if (dir == null) return;
        File target = new File(dir, fileName);
        File temp = new File(dir, fileName + ".tmp");
        if (target.exists()) target.delete();
        if (temp.exists()) temp.delete();
    }

    private static String encrypt(String plaintext) throws Exception {
        SecretKey key = getOrCreateKey();
        if (key == null) return null;
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LENGTH_BYTES) return null;
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) {
        try {
            byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
            if (combined == null || combined.length <= IV_LENGTH_BYTES) return "";
            SecretKey key = loadExistingKey();
            if (key == null) return "";
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH_BYTES, combined.length - IV_LENGTH_BYTES);
            return new String(plain, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SecretKey loadExistingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) return null;
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        return null;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        SecretKey existing = loadExistingKey();
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    private static String readUtf8Trimmed(File file) {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_KEY_FILE_BYTES) return "";
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void writeAtomically(File dir, String fileName, String content) throws Exception {
        File target = new File(dir, fileName);
        File temp = new File(dir, fileName + ".tmp");
        try {
            if (temp.exists() && !temp.delete()) return;
            byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(encoded);
                out.flush();
                out.getFD().sync();
            }
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } finally {
            if (temp.exists()) temp.delete();
        }
    }
}
