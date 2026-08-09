package com.birdie.brillbody;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** The standalone Brill prototype body: avatar, voice capture, OpenAI, TTS, and wake phrase. */
public final class MainActivity extends Activity {
    private enum State { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    private static final int REQUEST_AUDIO_PERMISSIONS = 4101;
    private static final int REQUEST_SETTINGS = 4102;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private AppConfig config;
    private AudioRecorder audioRecorder;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean receiverRegistered;
    private boolean suppressWakeSwitch;
    private boolean pendingWakeEnable;
    private String previousResponseId;
    private String finalTtsUtteranceId;
    private State currentState = State.IDLE;
    private ObjectAnimator speakingPulse;
    private int currentPoseRes = R.drawable.brill_neutral;
    private long lastLevelUiAt;

    private ImageView avatar;
    private TextView statusText;
    private TextView transcriptText;
    private ScrollView transcriptScroll;
    private EditText messageInput;
    private Button talkButton;
    private Switch wakeSwitch;

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!WakeWordService.ACTION_WAKE.equals(intent.getAction())) return;
            String command = intent.getStringExtra(WakeWordService.EXTRA_COMMAND);
            if (command != null && !command.trim().isEmpty()) {
                submitText(command.trim(), "wake recognizer");
            } else {
                startRecording(true, true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        enterImmersiveMode();

        config = new AppConfig(this);
        audioRecorder = new AudioRecorder(this);
        bindViews();
        configureUi();
        initializeTts();

        if (config.getApiKey().isEmpty()) {
            mainHandler.postDelayed(() -> {
                Toast.makeText(this, "Give Brill an API key in Settings to begin 🩷", Toast.LENGTH_LONG).show();
                openSettings();
            }, 450L);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(WakeWordService.ACTION_WAKE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wakeReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        refreshWakeSwitch();
        if (config.isWakeEnabled() && hasMicrophonePermission()) {
            WakeWordService.start(this);
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(wakeReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (audioRecorder != null && audioRecorder.isRecording()) audioRecorder.stop();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (speakingPulse != null) speakingPulse.cancel();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void bindViews() {
        avatar = findViewById(R.id.avatar);
        statusText = findViewById(R.id.statusText);
        transcriptText = findViewById(R.id.transcriptText);
        transcriptScroll = findViewById(R.id.transcriptScroll);
        messageInput = findViewById(R.id.messageInput);
        talkButton = findViewById(R.id.talkButton);
        wakeSwitch = findViewById(R.id.wakeSwitch);
    }

    private void configureUi() {
        Button settingsButton = findViewById(R.id.settingsButton);
        Button sendButton = findViewById(R.id.sendButton);
        Button resetButton = findViewById(R.id.resetButton);

        settingsButton.setOnClickListener(v -> openSettings());
        sendButton.setOnClickListener(v -> sendTypedMessage());
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage();
                return true;
            }
            return false;
        });

        resetButton.setOnClickListener(v -> {
            previousResponseId = null;
            transcriptText.setText("Brill: Fresh conversational branch opened. The little timeline has been pruned. ✂️🩷");
            setState(State.IDLE, null);
        });

        talkButton.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                if (!hasMicrophonePermission()) {
                    requestAudioPermissions(false);
                    return true;
                }
                startRecording(false, false);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                if (audioRecorder.isRecording()) audioRecorder.stop();
                return true;
            }
            return true;
        });

        wakeSwitch.setOnCheckedChangeListener((buttonView, enabled) -> {
            if (suppressWakeSwitch) return;
            if (enabled) {
                if (!hasMicrophonePermission()) {
                    pendingWakeEnable = true;
                    requestAudioPermissions(true);
                    setWakeSwitch(false);
                    return;
                }
                config.setWakeEnabled(true);
                WakeWordService.start(this);
                Toast.makeText(this, "Wake phrase armed. Pause briefly after “" + config.getWakePhrase() + "”.", Toast.LENGTH_LONG).show();
            } else {
                config.setWakeEnabled(false);
                WakeWordService.stop(this);
            }
        });
    }

    private void initializeTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false;
                appendSystemLine("Android TTS did not initialize; text replies still work.");
                return;
            }
            int languageResult = tts.setLanguage(Locale.US);
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setSpeechRate(config.getTtsRate());
            tts.setPitch(config.getTtsPitch());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }

                @Override
                public void onDone(String utteranceId) {
                    if (utteranceId != null && utteranceId.equals(finalTtsUtteranceId)) {
                        runOnUiThread(() -> finishSpeaking());
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    if (utteranceId != null && utteranceId.equals(finalTtsUtteranceId)) {
                        runOnUiThread(() -> finishSpeaking());
                    }
                }
            });
        });
    }

    private void sendTypedMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        messageInput.setText("");
        submitText(text, "typed");
    }

    private void submitText(String text, String source) {
        if (audioRecorder.isRecording()) {
            Toast.makeText(this, "Finish the current recording first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!processing.compareAndSet(false, true)) {
            Toast.makeText(this, "Brill is still thinking about the previous thing.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (config.getApiKey().isEmpty()) {
            processing.set(false);
            openSettings();
            Toast.makeText(this, "An API key is needed first.", Toast.LENGTH_LONG).show();
            return;
        }

        pauseWakeWord();
        appendConversation("You", text);
        if (!"typed".equals(source)) appendSystemLine("Input source: " + source);
        setState(State.THINKING, null);

        networkExecutor.execute(() -> {
            try {
                OpenAiProvider provider = new OpenAiProvider(config);
                OpenAiClient.BrainResponse response = provider.respond(text, previousResponseId);
                previousResponseId = response.id;
                runOnUiThread(() -> {
                    processing.set(false);
                    appendConversation("Brill", response.text);
                    appendUsageLine(response.inputTokens, response.outputTokens, response.requestDurationMs);
                    speakReply(response.text);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    processing.set(false);
                    showError(e);
                });
            }
        });
    }

    private void startRecording(boolean autoStop, boolean acknowledgeWake) {
        if (acknowledgeWake) {
            ListeningSounds.playWakeAcknowledged();
            mainHandler.postDelayed(() -> beginRecording(autoStop),
                    ListeningSounds.WAKE_CUE_DURATION_MS + 80L);
            return;
        }
        beginRecording(autoStop);
    }

    private void beginRecording(boolean autoStop) {
        if (processing.get()) {
            Toast.makeText(this, "Brill is already thinking.", Toast.LENGTH_SHORT).show();
            resumeWakeWord();
            return;
        }
        if (audioRecorder.isRecording()) return;
        if (!hasMicrophonePermission()) {
            requestAudioPermissions(autoStop);
            return;
        }
        if (config.getApiKey().isEmpty()) {
            openSettings();
            resumeWakeWord();
            return;
        }

        pauseWakeWord();
        setState(State.LISTENING, autoStop ? "Listening for your question…" : null);
        talkButton.setText(autoStop ? "Listening…" : "Release to Send");

        boolean started = audioRecorder.start(autoStop, new AudioRecorder.Listener() {
            @Override
            public void onLevel(float normalizedLevel) {
                long now = System.currentTimeMillis();
                if (now - lastLevelUiAt < 90L) return;
                lastLevelUiAt = now;
                runOnUiThread(() -> {
                    if (currentState == State.LISTENING) {
                        float scale = 1.015f + normalizedLevel * 0.035f;
                        avatar.setScaleX(scale);
                        avatar.setScaleY(scale);
                    }
                });
            }

            @Override
            public void onFinished(File wavFile, long durationMs) {
                runOnUiThread(() -> talkButton.setText("Hold Talk"));
                processAudio(wavFile, durationMs);
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    talkButton.setText("Hold Talk");
                    showError(error);
                });
            }
        });

        if (!started) {
            talkButton.setText("Hold Talk");
            resumeWakeWord();
        }
    }

    private void processAudio(File originalWav, long originalDurationMs) {
        if (!processing.compareAndSet(false, true)) {
            originalWav.delete();
            return;
        }
        runOnUiThread(() -> setState(State.THINKING, "Transcribing…"));

        networkExecutor.execute(() -> {
            File uploadFile = originalWav;
            File spedFile = null;
            boolean usedFallback = false;
            float speed = config.getAudioSpeed();
            try {
                OpenAiProvider provider = new OpenAiProvider(config);
                if (speed > 1.001f) {
                    spedFile = new File(getCacheDir(), "brill-sped-" + System.currentTimeMillis() + ".wav");
                    uploadFile = AudioRecorder.createSpedUpCopy(originalWav, speed, spedFile);
                }

                OpenAiClient.Transcription transcription;
                try {
                    transcription = provider.transcribe(uploadFile);
                } catch (Exception speedError) {
                    if (speed > 1.001f && config.isAudioFallbackEnabled()) {
                        usedFallback = true;
                        transcription = provider.transcribe(originalWav);
                    } else {
                        throw speedError;
                    }
                }

                final boolean fallbackForUi = usedFallback;
                final OpenAiClient.Transcription tx = transcription;
                runOnUiThread(() -> {
                    appendSystemLine(String.format(
                            Locale.US,
                            "Audio: %.2f s · upload %.2f× · transcription %.2f s%s%s",
                            originalDurationMs / 1000.0,
                            speed,
                            tx.requestDurationMs / 1000.0,
                            tx.audioTokens >= 0 ? " · audio tokens " + tx.audioTokens : "",
                            fallbackForUi ? " · retried original" : ""));
                    appendSystemLine(tx.confidence >= 0.0
                            ? String.format(Locale.US, "Transcription confidence: %.0f%% (token estimate)", tx.confidence * 100.0)
                            : "Transcription confidence: unavailable");
                    ListeningSounds.playTranscriptionComplete(tx.confidence);
                });

                OpenAiClient.BrainResponse response = provider.respond(transcription.text, previousResponseId);
                previousResponseId = response.id;
                final OpenAiClient.Transcription finalTranscription = transcription;
                runOnUiThread(() -> {
                    processing.set(false);
                    appendConversation("You", finalTranscription.text);
                    appendConversation("Brill", response.text);
                    appendUsageLine(response.inputTokens, response.outputTokens, response.requestDurationMs);
                    speakReply(response.text);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    processing.set(false);
                    ListeningSounds.playListeningFailed();
                    showError(e);
                });
            } finally {
                originalWav.delete();
                if (spedFile != null) spedFile.delete();
            }
        });
    }

    private void speakReply(String text) {
        if (!ttsReady || tts == null) {
            setState(State.IDLE, "Reply ready · Android TTS unavailable");
            resumeWakeWord();
            return;
        }
        tts.stop();
        tts.setSpeechRate(config.getTtsRate());
        tts.setPitch(config.getTtsPitch());
        setState(State.SPEAKING, null);

        List<String> chunks = splitForTts(text, 3300);
        if (chunks.isEmpty()) {
            finishSpeaking();
            return;
        }
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < chunks.size(); i++) {
            String id = "brill-tts-" + stamp + "-" + i;
            if (i == chunks.size() - 1) finalTtsUtteranceId = id;
            int queueMode = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = tts.speak(chunks.get(i), queueMode, null, id);
            if (result == TextToSpeech.ERROR) {
                finishSpeaking();
                return;
            }
        }
    }

    private void finishSpeaking() {
        finalTtsUtteranceId = null;
        setState(State.IDLE, null);
        resumeWakeWord();
    }

    private static List<String> splitForTts(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        String remaining = text == null ? "" : text.trim();
        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxLength) {
                chunks.add(remaining);
                break;
            }
            int cut = remaining.lastIndexOf('.', maxLength);
            if (cut < maxLength / 2) cut = remaining.lastIndexOf(' ', maxLength);
            if (cut < maxLength / 2) cut = maxLength;
            chunks.add(remaining.substring(0, cut + (remaining.charAt(cut) == '.' ? 1 : 0)).trim());
            remaining = remaining.substring(Math.min(remaining.length(), cut + 1)).trim();
        }
        return chunks;
    }

    private void appendConversation(String speaker, String text) {
        String existing = transcriptText.getText().toString();
        String prefix = existing.trim().isEmpty() ? "" : "\n\n";
        transcriptText.append(prefix + speaker + ": " + text.trim());
        scrollTranscriptToBottom();
    }

    private void appendSystemLine(String text) {
        transcriptText.append("\n\n‹ " + text + " ›");
        scrollTranscriptToBottom();
    }

    private void appendUsageLine(int inputTokens, int outputTokens, long durationMs) {
        String tokenText = inputTokens >= 0 && outputTokens >= 0
                ? " · tokens " + inputTokens + " in / " + outputTokens + " out"
                : "";
        appendSystemLine(String.format(Locale.US, "Response %.2f s%s", durationMs / 1000.0, tokenText));
    }

    private void scrollTranscriptToBottom() {
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showError(Exception error) {
        String message = friendlyMessage(error);
        appendSystemLine("Error: " + message);
        setState(State.ERROR, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        resumeWakeWord();
        mainHandler.postDelayed(() -> {
            if (currentState == State.ERROR) setState(State.IDLE, null);
        }, 5000L);
    }

    private static String friendlyMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getMessage() == null) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) message = current.getClass().getSimpleName();
        message = message.replaceAll("sk-[A-Za-z0-9_-]+", "[redacted key]");
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }

    private void setState(State state, String overrideText) {
        currentState = state;
        setAvatarPose(state);
        if (speakingPulse != null) {
            speakingPulse.cancel();
            speakingPulse = null;
        }
        avatar.animate().cancel();
        avatar.setRotation(0f);

        switch (state) {
            case LISTENING:
                statusText.setText(overrideText == null ? getString(R.string.status_listening) : overrideText);
                avatar.animate().alpha(1f).scaleX(1.035f).scaleY(1.035f).setDuration(220L).start();
                break;
            case THINKING:
                statusText.setText(overrideText == null ? getString(R.string.status_thinking) : overrideText);
                avatar.animate().alpha(0.86f).scaleX(0.99f).scaleY(0.99f).rotation(-0.6f).setDuration(360L).start();
                break;
            case SPEAKING:
                statusText.setText(overrideText == null ? getString(R.string.status_speaking) : overrideText);
                avatar.setAlpha(1f);
                speakingPulse = ObjectAnimator.ofFloat(avatar, View.SCALE_X, 1.0f, 1.025f);
                speakingPulse.setDuration(420L);
                speakingPulse.setRepeatMode(ValueAnimator.REVERSE);
                speakingPulse.setRepeatCount(ValueAnimator.INFINITE);
                speakingPulse.addUpdateListener(animation -> avatar.setScaleY((float) animation.getAnimatedValue()));
                speakingPulse.start();
                break;
            case ERROR:
                statusText.setText(overrideText == null ? getString(R.string.status_error) : overrideText);
                avatar.animate().alpha(0.72f).scaleX(0.985f).scaleY(0.985f).setDuration(220L).start();
                break;
            case IDLE:
            default:
                statusText.setText(overrideText == null ? getString(R.string.status_idle) : overrideText);
                avatar.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(280L).start();
                break;
        }
    }


    private void setAvatarPose(State state) {
        int nextRes;
        switch (state) {
            case LISTENING: nextRes = R.drawable.brill_listening; break;
            case THINKING: nextRes = R.drawable.brill_thinking; break;
            case SPEAKING: nextRes = R.drawable.brill_speaking; break;
            case ERROR: nextRes = R.drawable.brill_error; break;
            case IDLE:
            default: nextRes = R.drawable.brill_neutral; break;
        }
        if (nextRes == currentPoseRes) return;
        Drawable oldDrawable = avatar.getDrawable();
        Drawable newDrawable = getDrawable(nextRes);
        if (oldDrawable != null && newDrawable != null) {
            TransitionDrawable transition = new TransitionDrawable(new Drawable[]{oldDrawable, newDrawable});
            transition.setCrossFadeEnabled(true);
            avatar.setImageDrawable(transition);
            transition.startTransition(180);
        } else {
            avatar.setImageResource(nextRes);
        }
        currentPoseRes = nextRes;
    }

    private void pauseWakeWord() {
        if (config.isWakeEnabled()) WakeWordService.pause(this);
    }

    private void resumeWakeWord() {
        if (config.isWakeEnabled() && hasMicrophonePermission()) WakeWordService.resume(this);
    }

    private void refreshWakeSwitch() {
        setWakeSwitch(config.isWakeEnabled());
    }

    private void setWakeSwitch(boolean checked) {
        suppressWakeSwitch = true;
        wakeSwitch.setChecked(checked);
        suppressWakeSwitch = false;
    }

    private boolean hasMicrophonePermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermissions(boolean forWakeWord) {
        pendingWakeEnable = forWakeWord;
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_AUDIO_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO_PERMISSIONS) return;
        boolean micGranted = hasMicrophonePermission();
        if (micGranted && pendingWakeEnable) {
            config.setWakeEnabled(true);
            setWakeSwitch(true);
            WakeWordService.start(this);
        } else if (!micGranted) {
            config.setWakeEnabled(false);
            setWakeSwitch(false);
            Toast.makeText(this, "Microphone permission is required for voice and wake-word mode.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Microphone ready — hold Talk again.", Toast.LENGTH_SHORT).show();
        }
        pendingWakeEnable = false;
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS) {
            config = new AppConfig(this);
            if (ttsReady && tts != null) {
                tts.setSpeechRate(config.getTtsRate());
                tts.setPitch(config.getTtsPitch());
            }
            refreshWakeSwitch();
            if (config.isWakeEnabled() && hasMicrophonePermission()) WakeWordService.start(this);
        }
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
