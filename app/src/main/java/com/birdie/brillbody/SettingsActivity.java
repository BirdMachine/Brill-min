package com.birdie.brillbody;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private AppConfig config;
    private EditText apiKeyInput;
    private EditText baseUrlInput;
    private EditText modelInput;
    private EditText transcriptionModelInput;
    private EditText instructionsInput;
    private EditText wakePhraseInput;
    private Spinner audioSpeedSpinner;
    private Switch fallbackSwitch;
    private EditText ttsRateInput;
    private EditText ttsPitchInput;
    private EditText identityIdInput;
    private EditText bodyIdInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        config = new AppConfig(this);

        apiKeyInput = findViewById(R.id.apiKeyInput);
        baseUrlInput = findViewById(R.id.baseUrlInput);
        modelInput = findViewById(R.id.modelInput);
        transcriptionModelInput = findViewById(R.id.transcriptionModelInput);
        instructionsInput = findViewById(R.id.instructionsInput);
        wakePhraseInput = findViewById(R.id.wakePhraseInput);
        audioSpeedSpinner = findViewById(R.id.audioSpeedSpinner);
        fallbackSwitch = findViewById(R.id.fallbackSwitch);
        ttsRateInput = findViewById(R.id.ttsRateInput);
        ttsPitchInput = findViewById(R.id.ttsPitchInput);
        identityIdInput = findViewById(R.id.identityIdInput);
        bodyIdInput = findViewById(R.id.bodyIdInput);
        Button clearKeyButton = findViewById(R.id.clearKeyButton);
        Button saveButton = findViewById(R.id.saveButton);

        loadValues();
        clearKeyButton.setOnClickListener(v -> {
            config.clearApiKey();
            apiKeyInput.setText("");
            Toast.makeText(this, "API key erased", Toast.LENGTH_SHORT).show();
        });
        saveButton.setOnClickListener(v -> saveValues());
    }

    private void loadValues() {
        String key = config.getApiKey();
        apiKeyInput.setText(key);
        apiKeyInput.setSelection(apiKeyInput.length());
        baseUrlInput.setText(config.getBaseUrl());
        modelInput.setText(config.getModel());
        transcriptionModelInput.setText(config.getTranscriptionModel());
        instructionsInput.setText(config.getInstructions());
        wakePhraseInput.setText(config.getWakePhrase());
        fallbackSwitch.setChecked(config.isAudioFallbackEnabled());
        ttsRateInput.setText(String.valueOf(config.getTtsRate()));
        ttsPitchInput.setText(String.valueOf(config.getTtsPitch()));
        identityIdInput.setText(config.getIdentityId());
        bodyIdInput.setText(config.getBodyId());

        float speed = config.getAudioSpeed();
        int index = speed >= 1.9f ? 3 : speed >= 1.4f ? 2 : speed >= 1.1f ? 1 : 0;
        audioSpeedSpinner.setSelection(index);
    }

    private void saveValues() {
        try {
            String key = apiKeyInput.getText().toString().trim();
            if (!key.equals(config.getApiKey())) config.setApiKey(key);
            config.setBaseUrl(baseUrlInput.getText().toString());
            config.setModel(modelInput.getText().toString());
            config.setTranscriptionModel(transcriptionModelInput.getText().toString());
            config.setInstructions(instructionsInput.getText().toString());
            config.setWakePhrase(wakePhraseInput.getText().toString());
            config.setAudioFallbackEnabled(fallbackSwitch.isChecked());

            String[] speeds = getResources().getStringArray(R.array.audio_speed_values);
            int speedIndex = Math.max(0, Math.min(audioSpeedSpinner.getSelectedItemPosition(), speeds.length - 1));
            config.setAudioSpeed(Float.parseFloat(speeds[speedIndex]));
            config.setTtsRate(parseFloat(ttsRateInput, 1.0f));
            config.setTtsPitch(parseFloat(ttsPitchInput, 1.05f));
            config.setIdentityId(identityIdInput.getText().toString());
            config.setBodyId(bodyIdInput.getText().toString());

            Toast.makeText(this, "Brill settings saved 🩷", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static float parseFloat(EditText field, float fallback) {
        try {
            return Float.parseFloat(field.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
