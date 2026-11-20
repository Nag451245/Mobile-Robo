package com.blink.buddy;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Sweety's little robot noises (giggles, chirps, boings, a dance tune), synthesised on the phone so there are
 * no sound files to ship. Each sound is a list of pitch glides with a soft, round tone and a bit of wobble.
 */
final class Sounds {
    private static final int RATE = 22050;
    private static final Random RND = new Random();
    private static volatile long busyUntil;

    private Sounds() {}

    /** True while a sound is playing (the "Hey Sweety" listener ignores it). */
    static boolean playing() { return SystemClock.uptimeMillis() < busyUntil; }

    /** A glide from f0 to f1 Hz over ms milliseconds; f0 = 0 means silence. */
    private static final class Seg {
        final float f0, f1, vol, wobble; final int ms;
        Seg(float f0, float f1, int ms, float vol, float wobble) { this.f0 = f0; this.f1 = f1; this.ms = ms; this.vol = vol; this.wobble = wobble; }
    }

    private static Seg s(float f0, float f1, int ms) { return new Seg(f0, f1, ms, .8f, 0); }
    private static Seg w(float f0, float f1, int ms) { return new Seg(f0, f1, ms, .8f, 1); }
    private static Seg gap(int ms) { return new Seg(0, 0, ms, 0, 0); }

    /** Plays the sound that goes with a gesture or mood; returns how long it lasts in ms (0 if none). */
    static int forAction(String action, String emotion) {
        String key = action;
        if (key == null || "nod".equals(key)) key = "laugh".equals(emotion) ? "giggle" : "sad".equals(emotion) ? "uhoh" : null;
        if (key == null) return 0;
        if (key.startsWith("outfit:")) key = "tada";
        return play(key);
    }

    static int play(String name) {
        List<Seg> q = new ArrayList<>();
        switch (name) {
            case "giggle": case "laugh": {
                // "tee-hee-hee-hee": quick bouncy chirps that tumble down in pitch
                float base = 1150 + RND.nextInt(150);
                int n = "laugh".equals(name) ? 6 : 4;
                for (int i = 0; i < n; i++) { float f = base - i * 70; q.add(w(f * 1.12f, f, 75)); q.add(gap(45)); }
                break;
            }
            case "wave": case "hello":   // "bee-doo!"
                q.add(s(700, 1150, 110)); q.add(gap(40)); q.add(w(950, 1500, 150)); break;
            case "dance": {              // a happy little arpeggio
                float[] notes = {784, 988, 1175, 1568, 1175, 1568};
                for (float f : notes) { q.add(s(f, f, 95)); q.add(gap(25)); }
                break;
            }
            case "jump": case "boing":
                q.add(w(300, 900, 140)); q.add(w(900, 600, 120)); break;
            case "spin": case "whee":
                q.add(w(500, 1700, 420)); break;
            case "flip":
                q.add(w(450, 1600, 330)); q.add(gap(60)); q.add(s(1200, 1500, 90)); break;
            case "cartwheel":
                q.add(w(600, 1300, 200)); q.add(w(1300, 700, 200)); q.add(w(700, 1500, 220)); break;
            case "shrug": case "hmm":    // "hmm?"
                q.add(s(520, 480, 140)); q.add(gap(30)); q.add(s(520, 820, 170)); break;
            case "uhoh":
                q.add(s(820, 820, 140)); q.add(gap(50)); q.add(s(600, 560, 220)); break;
            case "tada":
                q.add(s(880, 880, 90)); q.add(gap(20)); q.add(s(1320, 1320, 90)); q.add(gap(20)); q.add(w(1760, 1760, 260)); break;
            case "sleep": case "yawn":
                q.add(w(700, 380, 650)); break;
            case "stretch":
                q.add(w(400, 900, 500)); break;
            default: return 0;
        }
        return render(q);
    }

    private static int render(List<Seg> q) {
        int total = 0;
        for (Seg g : q) total += g.ms * RATE / 1000;
        short[] pcm = new short[total];
        int i = 0;
        double phase = 0;
        for (Seg g : q) {
            int n = g.ms * RATE / 1000;
            int attack = Math.min(n / 4, RATE * 8 / 1000), release = Math.min(n / 3, RATE * 30 / 1000);
            for (int k = 0; k < n; k++, i++) {
                if (g.f0 <= 0) { pcm[i] = 0; continue; }
                float p = (float) k / n;
                double f = g.f0 + (g.f1 - g.f0) * p;
                if (g.wobble > 0) f *= 1 + .035 * Math.sin(2 * Math.PI * 22 * k / RATE);   // cute vibrato
                phase += 2 * Math.PI * f / RATE;
                // soft, round "robot voice" tone: fundamental plus a little second and third harmonic
                double v = Math.sin(phase) + .28 * Math.sin(2 * phase) + .08 * Math.sin(3 * phase);
                float env = Math.min(1f, Math.min((float) k / Math.max(1, attack), (float) (n - k) / Math.max(1, release)));
                pcm[i] = (short) (v / 1.36 * env * g.vol * 0.55 * Short.MAX_VALUE);
            }
        }
        final int ms = total * 1000 / RATE;
        busyUntil = SystemClock.uptimeMillis() + ms + 150;
        new Thread(() -> {
            AudioTrack t = null;
            try {
                t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
                t.write(pcm, 0, pcm.length);
                t.play();
                Thread.sleep(ms + 80);
            } catch (Exception ignored) {
            } finally {
                if (t != null) { try { t.stop(); } catch (Exception ignored) { } t.release(); }
            }
        }, "sweety-sound").start();
        return ms;
    }
}
