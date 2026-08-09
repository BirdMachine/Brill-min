package com.birdie.brillbody;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Experimental wake phrase listener using Android SpeechRecognizer. We request offline
 * recognition, but the installed recognition service ultimately decides whether audio stays local.
 */
public final class WakeWordService extends Service implements RecognitionListener {
    public static final String ACTION_WAKE = "com.birdie.brillbody.WAKE";
    public static final String EXTRA_COMMAND = "recognized_command";
    private static final String ACTION_START = "com.birdie.brillbody.START_WAKE";
    private static final String ACTION_PAUSE = "com.birdie.brillbody.PAUSE_WAKE";
    private static final String ACTION_RESUME = "com.birdie.brillbody.RESUME_WAKE";
    private static final String ACTION_STOP = "com.birdie.brillbody.STOP_WAKE";
    private static final String CHANNEL_ID = "brill_wake_word";
    private static final int NOTIFICATION_ID = 6102;
    private static final String TAG = "BrillWakeWord";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean paused;
    private boolean destroyed;
    private boolean listening;
    private long lastWakeAt;

    public static void start(Context context) {
        Intent intent = new Intent(context, WakeWordService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, WakeWordService.class).setAction(ACTION_STOP));
    }

    public static void pause(Context context) {
        context.startService(new Intent(context, WakeWordService.class).setAction(ACTION_PAUSE));
    }

    public static void resume(Context context) {
        Intent intent = new Intent(context, WakeWordService.class).setAction(ACTION_RESUME);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Listening for “Hey Brill”"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            paused = true;
            stopRecognition();
            updateNotification("Paused while Brill is using audio");
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action) || ACTION_START.equals(action) || action == null) {
            paused = false;
            updateNotification("Listening for “" + new AppConfig(this).getWakePhrase() + "”");
            scheduleListen(150L);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        stopRecognition();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleListen(long delayMs) {
        handler.removeCallbacks(startListeningRunnable);
        if (!destroyed && !paused) handler.postDelayed(startListeningRunnable, delayMs);
    }

    private final Runnable startListeningRunnable = this::startListening;

    private void startListening() {
        if (destroyed || paused || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Microphone permission required");
            stopSelf();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("No Android speech recognizer is installed");
            scheduleListen(15_000L);
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(this);
            }
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            request.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            request.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
            request.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
            recognizer.startListening(request);
            listening = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Wake listener failed to start", e);
            listening = false;
            resetRecognizer();
            scheduleListen(2000L);
        }
    }

    private void stopRecognition() {
        listening = false;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
            }
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
            recognizer = null;
        }
    }

    private void resetRecognizer() {
        stopRecognition();
    }

    private void inspectResults(Bundle results) {
        if (results == null) return;
        ArrayList<String> phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (phrases == null) return;
        String wakePhrase = normalize(new AppConfig(this).getWakePhrase());
        for (String phrase : phrases) {
            String normalized = normalize(phrase);
            int wakeIndex = normalized.indexOf(wakePhrase);
            if (wakeIndex >= 0) {
                long now = System.currentTimeMillis();
                if (now - lastWakeAt < 3000L) return;
                lastWakeAt = now;
                paused = true;
                stopRecognition();
                updateNotification("Wake phrase heard — handing audio to Brill");
                String command = normalized.substring(wakeIndex + wakePhrase.length()).trim();
                Intent wake = new Intent(ACTION_WAKE)
                        .setPackage(getPackageName())
                        .putExtra(EXTRA_COMMAND, command);
                sendBroadcast(wake);
                return;
            }
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }

    @Override
    public void onEndOfSpeech() {
        listening = false;
    }

    @Override
    public void onError(int error) {
        listening = false;
        if (paused || destroyed) return;
        long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER) ? 2500L : 600L;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            updateNotification("Microphone permission required");
            stopSelf();
            return;
        }
        if (error == SpeechRecognizer.ERROR_CLIENT) resetRecognizer();
        scheduleListen(delay);
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        inspectResults(results);
        if (!paused) scheduleListen(350L);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        // Wait for the final phrase so “Hey Brill, do the thing” can carry its command too.
    }

    @Override public void onEvent(int eventType, Bundle params) { }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Brill wake word",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps Brill's experimental wake phrase listener active.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openApp, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_brill)
                .setContentTitle("Brill is listening")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
