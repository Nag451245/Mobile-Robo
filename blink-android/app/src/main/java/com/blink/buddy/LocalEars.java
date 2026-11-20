package com.blink.buddy;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;

/**
 * Offline hearing: records one phrase from the microphone (16 kHz mono), stops when you go quiet,
 * and transcribes it on the phone with Whistle. Same callbacks as {@link Ears}.
 */
final class LocalEars {
    private static final int RATE = 16000;
    private final Context ctx;
    private final Ears.Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean recording;

    LocalEars(Context ctx, Ears.Callback cb) { this.ctx = ctx.getApplicationContext(); this.cb = cb; }

    boolean listening() { return recording; }

    void stop() { recording = false; }

    void listen() {
        if (recording) return;
        recording = true;
        cb.onListening(true);
        new Thread(this::record, "sweety-mic").start();
    }

    private void record() {
        AudioRecord ar = null;
        float[] pcm = new float[RATE * 12];
        int n = 0;
        boolean spoke = false;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) { finish(false, "I can't open the microphone right now."); return; }
            short[] buf = new short[RATE / 10]; // 100 ms
            int silentMs = 0;
            long start = System.currentTimeMillis();
            ar.startRecording();
            while (recording && n < pcm.length) {
                int r = ar.read(buf, 0, buf.length);
                if (r <= 0) break;
                double sum = 0;
                for (int i = 0; i < r && n < pcm.length; i++) { float f = buf[i] / 32768f; pcm[n++] = f; sum += f * f; }
                double rms = Math.sqrt(sum / r);
                if (rms > .018) { spoke = true; silentMs = 0; }
                else if (spoke) silentMs += r * 1000 / RATE;
                if (spoke && silentMs > 1100) break;             // you stopped talking
                if (!spoke && System.currentTimeMillis() - start > 6000) break; // nothing said
            }
        } catch (SecurityException e) {
            finish(false, "I need the microphone permission to hear you.");
            return;
        } catch (Exception e) {
            finish(false, "Something went wrong with the microphone.");
            return;
        } finally {
            if (ar != null) { try { ar.stop(); } catch (Exception ignored) { } ar.release(); }
        }
        if (!spoke) { finish(false, "I didn't hear anything."); return; }
        final float[] clip = Arrays.copyOf(pcm, n);
        main.post(() -> {
            recording = false;
            cb.onListening(false);
            cb.onPartial("(thinking)");
            Assistant.get(ctx).transcribe(clip, text -> {
                if (text == null || text.isEmpty()) cb.onProblem("I didn't catch that. Try again?");
                else cb.onHeard(text);
            });
        });
    }

    private void finish(boolean ok, String problem) {
        main.post(() -> { recording = false; cb.onListening(false); if (!ok) cb.onProblem(problem); });
    }
}
