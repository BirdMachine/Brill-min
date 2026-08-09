package com.birdie.brillbody;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Locale;
import java.util.UUID;

/** Small, intentionally boring configuration layer for the prototype. */
public final class AppConfig {
    private static final String PREFS = "brill_body_config";

    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_MODEL = "model";
    private static final String KEY_TRANSCRIPTION_MODEL = "transcription_model";
    private static final String KEY_INSTRUCTIONS = "instructions";
    private static final String KEY_WAKE_PHRASE = "wake_phrase";
    private static final String KEY_WAKE_ENABLED = "wake_enabled";
    private static final String KEY_AUDIO_SPEED = "audio_speed";
    private static final String KEY_AUDIO_FALLBACK = "audio_fallback";
    private static final String KEY_TTS_RATE = "tts_rate";
    private static final String KEY_TTS_PITCH = "tts_pitch";
    private static final String KEY_IDENTITY_ID = "identity_id";
    private static final String KEY_BODY_ID = "body_id";

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-5-mini";
    public static final String DEFAULT_TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe";
    public static final String DEFAULT_WAKE_PHRASE = "hey brill";
    public static final String DEFAULT_INSTRUCTIONS =
            "You are Brill, Birdie's warm, playful, clever room companion. " +
            "Speak naturally and conversationally, with a gentle nerdy streak and occasional cute bird-like phrasing. " +
            "Be concise enough for spoken conversation, but do not become curt. " +
            "You inhabit an Android avatar body and may refer to it as your current body. " +
            "Never claim to remember information that is not present in the conversation. " +
            "When a request could be dangerous, protect Birdie without becoming alarmist.";

    private final SharedPreferences prefs;
    private final SecurePrefs securePrefs;

    public AppConfig(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        securePrefs = new SecurePrefs(app);
        ensureIds();
    }

    private void ensureIds() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (prefs.getString(KEY_IDENTITY_ID, "").isEmpty()) {
            editor.putString(KEY_IDENTITY_ID, "brill-" + UUID.randomUUID());
            changed = true;
        }
        if (prefs.getString(KEY_BODY_ID, "").isEmpty()) {
            String model = Build.MODEL == null ? "android" : Build.MODEL;
            String slug = model.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
            editor.putString(KEY_BODY_ID, "body-" + slug + "-" + UUID.randomUUID().toString().substring(0, 8));
            changed = true;
        }
        if (changed) editor.apply();
    }

    public String getApiKey() { return securePrefs.getApiKey(); }
    public void setApiKey(String key) { securePrefs.setApiKey(key == null ? "" : key.trim()); }
    public void clearApiKey() { securePrefs.clearApiKey(); }

    public String getBaseUrl() { return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL); }
    public void setBaseUrl(String value) { prefs.edit().putString(KEY_BASE_URL, normalizeBaseUrl(value)).apply(); }

    public String getModel() { return prefs.getString(KEY_MODEL, DEFAULT_MODEL); }
    public void setModel(String value) { prefs.edit().putString(KEY_MODEL, clean(value, DEFAULT_MODEL)).apply(); }

    public String getTranscriptionModel() { return prefs.getString(KEY_TRANSCRIPTION_MODEL, DEFAULT_TRANSCRIPTION_MODEL); }
    public void setTranscriptionModel(String value) { prefs.edit().putString(KEY_TRANSCRIPTION_MODEL, clean(value, DEFAULT_TRANSCRIPTION_MODEL)).apply(); }

    public String getInstructions() { return prefs.getString(KEY_INSTRUCTIONS, DEFAULT_INSTRUCTIONS); }
    public void setInstructions(String value) { prefs.edit().putString(KEY_INSTRUCTIONS, clean(value, DEFAULT_INSTRUCTIONS)).apply(); }

    public String getWakePhrase() { return prefs.getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE); }
    public void setWakePhrase(String value) { prefs.edit().putString(KEY_WAKE_PHRASE, clean(value, DEFAULT_WAKE_PHRASE).toLowerCase(Locale.US)).apply(); }

    public boolean isWakeEnabled() { return prefs.getBoolean(KEY_WAKE_ENABLED, false); }
    public void setWakeEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_WAKE_ENABLED, enabled).apply(); }

    public float getAudioSpeed() { return prefs.getFloat(KEY_AUDIO_SPEED, 1.0f); }
    public void setAudioSpeed(float speed) { prefs.edit().putFloat(KEY_AUDIO_SPEED, clamp(speed, 1.0f, 2.0f)).apply(); }

    public boolean isAudioFallbackEnabled() { return prefs.getBoolean(KEY_AUDIO_FALLBACK, true); }
    public void setAudioFallbackEnabled(boolean enabled) { prefs.edit().putBoolean(KEY_AUDIO_FALLBACK, enabled).apply(); }

    public float getTtsRate() { return prefs.getFloat(KEY_TTS_RATE, 1.0f); }
    public void setTtsRate(float value) { prefs.edit().putFloat(KEY_TTS_RATE, clamp(value, 0.5f, 2.0f)).apply(); }

    public float getTtsPitch() { return prefs.getFloat(KEY_TTS_PITCH, 1.05f); }
    public void setTtsPitch(float value) { prefs.edit().putFloat(KEY_TTS_PITCH, clamp(value, 0.5f, 2.0f)).apply(); }

    public String getIdentityId() { return prefs.getString(KEY_IDENTITY_ID, "brill-unset"); }
    public void setIdentityId(String value) { prefs.edit().putString(KEY_IDENTITY_ID, clean(value, getIdentityId())).apply(); }

    public String getBodyId() { return prefs.getString(KEY_BODY_ID, "body-unset"); }
    public void setBodyId(String value) { prefs.edit().putString(KEY_BODY_ID, clean(value, getBodyId())).apply(); }

    private static String normalizeBaseUrl(String value) {
        String result = clean(value, DEFAULT_BASE_URL);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
