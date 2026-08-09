package com.birdie.brillbody;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records 16 kHz mono PCM and packages it as WAV. Wake-triggered recording can stop
 * after speech followed by silence; hold-to-talk recording stops when the button is released.
 */
public final class AudioRecorder {
    public interface Listener {
        void onLevel(float normalizedLevel);
        void onFinished(File wavFile, long durationMs);
        void onError(Exception error);
    }

    public static final int SAMPLE_RATE = 16_000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final long MAX_AUTO_MS = 20_000L;
    private static final long MIN_AUTO_MS = 650L;
    private static final long SILENCE_AFTER_SPEECH_MS = 1_050L;
    private static final double SPEECH_RMS_THRESHOLD = 850.0;

    private final Context context;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private volatile AudioRecord audioRecord;
    private volatile Thread worker;

    public AudioRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized boolean start(boolean autoStop, Listener listener) {
        if (recording.get()) return false;

        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minimum <= 0) {
            listener.onError(new IllegalStateException("This device reported no usable microphone buffer size."));
            return false;
        }
        int bufferBytes = Math.max(minimum * 2, 4096);

        try {
            audioRecord = makeAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferBytes);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = makeAudioRecord(MediaRecorder.AudioSource.MIC, bufferBytes);
            }
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = null;
                listener.onError(new IllegalStateException("Android could not initialize the microphone."));
                return false;
            }
        } catch (SecurityException e) {
            listener.onError(e);
            return false;
        }

        recording.set(true);
        final int chosenBuffer = bufferBytes;
        worker = new Thread(() -> recordLoop(autoStop, chosenBuffer, listener), "BrillAudioRecorder");
        worker.start();
        return true;
    }

    public void stop() {
        // Let the blocking read finish one small buffer naturally; calling AudioRecord.stop()
        // from the UI thread can turn a clean button release into ERROR_INVALID_OPERATION.
        recording.set(false);
    }

    public boolean isRecording() {
        return recording.get();
    }

    private AudioRecord makeAudioRecord(int source, int bufferBytes) {
        return new AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes);
    }

    private void recordLoop(boolean autoStop, int bufferBytes, Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        File wavFile = new File(context.getCacheDir(), "brill-recording-" + System.currentTimeMillis() + ".wav");
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] samples = new short[Math.max(1024, bufferBytes / 2)];
        long startedAt = System.currentTimeMillis();
        long lastSpeechAt = startedAt;
        boolean heardSpeech = false;

        try {
            AudioRecord local = audioRecord;
            if (local == null) throw new IllegalStateException("Microphone disappeared before recording began.");
            local.startRecording();
            if (local.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("The microphone refused to enter recording state.");
            }

            while (recording.get()) {
                int read = local.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (read < 0) throw new IOException("AudioRecord read failed with code " + read);
                if (read == 0) continue;

                double sumSquares = 0.0;
                for (int i = 0; i < read; i++) {
                    short sample = samples[i];
                    pcm.write(sample & 0xff);
                    pcm.write((sample >>> 8) & 0xff);
                    sumSquares += (double) sample * sample;
                }
                double rms = Math.sqrt(sumSquares / read);
                listener.onLevel((float) Math.min(1.0, rms / 7000.0));

                long now = System.currentTimeMillis();
                if (rms >= SPEECH_RMS_THRESHOLD) {
                    heardSpeech = true;
                    lastSpeechAt = now;
                }

                if (autoStop) {
                    long elapsed = now - startedAt;
                    if (elapsed >= MAX_AUTO_MS ||
                            (heardSpeech && elapsed >= MIN_AUTO_MS && now - lastSpeechAt >= SILENCE_AFTER_SPEECH_MS)) {
                        recording.set(false);
                    }
                }
            }

            try {
                local.stop();
            } catch (IllegalStateException ignored) {
            }
            byte[] pcmBytes = pcm.toByteArray();
            if (pcmBytes.length < SAMPLE_RATE / 4) {
                throw new IOException("The recording was too short to transcribe.");
            }
            writeWav(wavFile, pcmBytes, SAMPLE_RATE, 1, 16);
            long durationMs = Math.round((pcmBytes.length / 2.0 / SAMPLE_RATE) * 1000.0);
            listener.onFinished(wavFile, durationMs);
        } catch (Exception e) {
            if (wavFile.exists()) wavFile.delete();
            listener.onError(e);
        } finally {
            recording.set(false);
            AudioRecord local = audioRecord;
            audioRecord = null;
            if (local != null) local.release();
            worker = null;
            try {
                pcm.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Creates a shorter, pitch-raised WAV by resampling PCM frames. This is intentionally
     * simple and experimental; it is designed to test the "faster upload" hypothesis, not
     * to replace a proper time-stretch DSP library.
     */
    public static File createSpedUpCopy(File input, float factor, File output) throws IOException {
        if (factor <= 1.001f) return input;
        byte[] wav = readAll(input);
        if (wav.length < 46 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            throw new IOException("Expected a standard PCM WAV file.");
        }
        int dataOffset = findDataOffset(wav);
        if (dataOffset < 0) throw new IOException("WAV data chunk was not found.");
        int dataLength = littleEndianInt(wav, dataOffset - 4);
        dataLength = Math.min(dataLength, wav.length - dataOffset);
        int sourceSamples = dataLength / 2;
        int outputSamples = Math.max(1, (int) Math.floor(sourceSamples / factor));
        byte[] resultPcm = new byte[outputSamples * 2];

        for (int i = 0; i < outputSamples; i++) {
            double sourcePosition = i * (double) factor;
            int leftIndex = Math.min(sourceSamples - 1, (int) Math.floor(sourcePosition));
            int rightIndex = Math.min(sourceSamples - 1, leftIndex + 1);
            double fraction = sourcePosition - leftIndex;
            short left = littleEndianShort(wav, dataOffset + leftIndex * 2);
            short right = littleEndianShort(wav, dataOffset + rightIndex * 2);
            int interpolated = (int) Math.round(left + (right - left) * fraction);
            short sample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, interpolated));
            resultPcm[i * 2] = (byte) (sample & 0xff);
            resultPcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        writeWav(output, resultPcm, SAMPLE_RATE, 1, 16);
        return output;
    }

    private static int findDataOffset(byte[] wav) {
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String id = new String(wav, offset, 4);
            int size = littleEndianInt(wav, offset + 4);
            if ("data".equals(id)) return offset + 8;
            offset += 8 + size + (size & 1);
        }
        return -1;
    }

    private static short littleEndianShort(byte[] data, int offset) {
        return (short) ((data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8));
    }

    private static int littleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xff) |
                ((data[offset + 1] & 0xff) << 8) |
                ((data[offset + 2] & 0xff) << 16) |
                ((data[offset + 3] & 0xff) << 24);
    }

    private static byte[] readAll(File file) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void writeWav(File output, byte[] pcm, int sampleRate, int channels, int bitsPerSample) throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + pcm.length);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(pcm.length);

        try (BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(output))) {
            stream.write(header.array());
            stream.write(pcm);
        }
    }
}
