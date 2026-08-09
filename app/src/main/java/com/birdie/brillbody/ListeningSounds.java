package com.birdie.brillbody;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/** Short synthesized placeholder cues. No bundled audio assets are required. */
public final class ListeningSounds {
    public static final long WAKE_CUE_DURATION_MS = 130L;
    private static final int VOLUME = 72;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ListeningSounds() { }

    /** A bright two-note-ish acknowledgement played before command recording begins. */
    public static void playWakeAcknowledged() {
        playTone(ToneGenerator.TONE_PROP_BEEP2, (int) WAKE_CUE_DURATION_MS);
    }

    /**
     * Plays a pitch-coded transcription-confidence cue, then an acknowledgement tone.
     * Confidence describes the transcription tokens—not whether Brill's eventual answer is true.
     */
    public static void playTranscriptionComplete(double confidence) {
        int confidenceTone;
        if (confidence < 0.0) {
            confidenceTone = ToneGenerator.TONE_DTMF_5; // neutral / unavailable
        } else if (confidence >= 0.88) {
            confidenceTone = ToneGenerator.TONE_DTMF_9; // high
        } else if (confidence >= 0.68) {
            confidenceTone = ToneGenerator.TONE_DTMF_5; // medium
        } else {
            confidenceTone = ToneGenerator.TONE_DTMF_1; // low
        }

        playTone(confidenceTone, 115);
        MAIN.postDelayed(() -> playTone(ToneGenerator.TONE_PROP_ACK, 105), 155L);
    }

    /** Error-path ending cue when no usable transcription was returned. */
    public static void playListeningFailed() {
        playTone(ToneGenerator.TONE_PROP_NACK, 150);
    }

    private static void playTone(int tone, int durationMs) {
        MAIN.post(() -> {
            ToneGenerator generator = null;
            try {
                generator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME);
                generator.startTone(tone, durationMs);
                ToneGenerator finalGenerator = generator;
                MAIN.postDelayed(() -> {
                    try {
                        finalGenerator.stopTone();
                    } catch (RuntimeException ignored) { }
                    finalGenerator.release();
                }, durationMs + 40L);
            } catch (RuntimeException ignored) {
                if (generator != null) generator.release();
            }
        });
    }
}
