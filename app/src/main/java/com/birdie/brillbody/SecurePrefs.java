package com.birdie.brillbody;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts the prototype API key using a non-exportable Android Keystore key. */
public final class SecurePrefs {
    private static final String TAG = "SecurePrefs";
    private static final String STORE = "brill_body_secrets";
    private static final String KEY_ALIAS = "brill_body_api_key_v1";
    private static final String PREF_CIPHERTEXT = "api_key_ciphertext";
    private static final String PREF_IV = "api_key_iv";

    private final SharedPreferences prefs;

    public SecurePrefs(Context context) {
        prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public void setApiKey(String value) {
        if (value == null || value.trim().isEmpty()) {
            clearApiKey();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                    .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the API key", e);
        }
    }

    public String getApiKey() {
        String ciphertextEncoded = prefs.getString(PREF_CIPHERTEXT, "");
        String ivEncoded = prefs.getString(PREF_IV, "");
        if (ciphertextEncoded.isEmpty() || ivEncoded.isEmpty()) return "";
        try {
            byte[] ciphertext = Base64.decode(ciphertextEncoded, Base64.NO_WRAP);
            byte[] iv = Base64.decode(ivEncoded, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Unable to decrypt stored API key; clearing the damaged value", e);
            clearApiKey();
            return "";
        }
    }

    public void clearApiKey() {
        prefs.edit().remove(PREF_CIPHERTEXT).remove(PREF_IV).apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
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
}
