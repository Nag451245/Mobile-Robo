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

    /** Milliseconds until the current sound ends (0 if none). */
    static long remainingMs() { return Math.max(0, busyUntil - SystemClock.uptimeMillis()); }

    /* ------------------------------------------------------------ waking up */

    /**
     * Her wake-up sound, which fits the time of day: birds and her "bee-doo!" in the morning, chattering sparrows at
     * midday, a mellow songbird and the first crickets in the evening, crickets and a soft owl at night with a
     * sleepy hello. Randomised every time, so it never sounds quite the same. Returns its length in ms.
     */
    static int wake(int hour) {
        int part = DayTime.part(hour);
        float[] out = new float[RATE * 1300 / 1000];
        switch (part) {
            case DayTime.EARLY: case DayTime.MORNING: {
                // dawn chorus: two or three different birds, one after another and overlapping
                int[] who = shuffled(4);
                int at = RND.nextInt(60);
                for (int b = 0; b < 2 + RND.nextInt(2); b++) { at = bird(out, who[b], at, .9f + RND.nextFloat() * .25f) - 120 - RND.nextInt(120); }
                mixRobot(out, 600 + RND.nextInt(150), hello());
                break;
            }
            case DayTime.NOON: case DayTime.AFTERNOON: {
                int at = sparrows(out, RND.nextInt(40), 5 + RND.nextInt(4));
                bird(out, RND.nextBoolean() ? 0 : 3, Math.max(0, at - 250), 1f);
                mixRobot(out, 520 + RND.nextInt(120), hello());
                break;
            }
            case DayTime.EVENING: {
                crickets(out, 0, 1250, .10f);
                bird(out, 2, 40 + RND.nextInt(80), .85f);   // a mellow evening song
                mixRobot(out, 650 + RND.nextInt(120), hello());
                break;
            }
            default: { // night and the small hours: quiet crickets, a soft owl, a sleepy hello
                crickets(out, 0, 1250, .12f);
                if (RND.nextInt(3) > 0) owl(out, 30 + RND.nextInt(80));
                List<Seg> sleepy = new ArrayList<>();
                sleepy.add(w(560, 900, 160)); sleepy.add(gap(40)); sleepy.add(w(820, 600, 240));
                mixRobot(out, 700, sleepy);
            }
        }
        return play(out);
    }

    private static List<Seg> hello() {
        List<Seg> q = new ArrayList<>();
        float k = .94f + RND.nextFloat() * .12f; // a slightly different "bee-doo!" each time
        q.add(s(700 * k, 1150 * k, 110)); q.add(gap(40)); q.add(w(950 * k, 1500 * k, 150));
        return q;
    }

    private static int[] shuffled(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        for (int i = n - 1; i > 0; i--) { int j = RND.nextInt(i + 1), t = a[i]; a[i] = a[j]; a[j] = t; }
        return a;
    }

    /** One of four made-up birds, from atMs; pitch scales it. Returns when it ends (ms). */
    private static int bird(float[] out, int kind, int atMs, float pitch) {
        int t = atMs;
        switch (kind) {
            case 0: { // "tsee-tsee-tsee": quick falling whistles
                int n = 2 + RND.nextInt(3);
                for (int i = 0; i < n; i++) { chirp(out, t, 4300 * pitch, 3100 * pitch, 65, .32f, 0); t += 65 + 45 + RND.nextInt(25); }
                break;
            }
            case 1: { // a fast trill
                int n = 6 + RND.nextInt(5);
                for (int i = 0; i < n; i++) { chirp(out, t, 5100 * pitch, 4500 * pitch, 24, .22f, 0); t += 24 + 14; }
                break;
            }
            case 2: { // a mellow, wobbly two-part song
                chirp(out, t, 2000 * pitch, 2600 * pitch, 150, .28f, 1); t += 170;
                chirp(out, t, 2700 * pitch, 2200 * pitch, 190, .26f, 1); t += 200;
                if (RND.nextBoolean()) { chirp(out, t, 2400 * pitch, 2900 * pitch, 90, .22f, 1); t += 100; }
                break;
            }
            default: { // "chee-wit!": a rising call
                chirp(out, t, 2800 * pitch, 3600 * pitch, 110, .3f, 0); t += 140;
                chirp(out, t, 3200 * pitch, 4400 * pitch, 80, .28f, 0); t += 90;
            }
        }
        return t;
    }

    /** Busy little chirps from a few sparrows. */
    private static int sparrows(float[] out, int atMs, int n) {
        int t = atMs;
        for (int i = 0; i < n; i++) {
            float f = 3400 + RND.nextInt(1400);
            chirp(out, t, f, f * (.82f + RND.nextFloat() * .1f), 28 + RND.nextInt(25), .2f + RND.nextFloat() * .08f, 0);
            t += 55 + RND.nextInt(70);
        }
        return t;
    }

    /** Crickets: soft pulses of a high tone, in little groups. */
    private static void crickets(float[] out, int atMs, int ms, float vol) {
        float f = 4300 + RND.nextInt(500);
        for (int t = atMs + RND.nextInt(80); t < atMs + ms; t += 330 + RND.nextInt(120)) {
            for (int k = 0; k < 3; k++) chirp(out, t + k * 38, f, f, 22, vol, 0);
        }
    }

    /** A soft, friendly "hoo-hoo", finished before her sleepy hello. */
    private static void owl(float[] out, int atMs) {
        chirp(out, atMs, 400, 360, 240, .3f, 0);
        chirp(out, atMs + 320, 405, 345, 260, .27f, 0);
    }

    /** Adds a pure-tone glide (birds are near pure whistles) with a smooth swell; wobble 1 adds a song-like vibrato. */
    private static void chirp(float[] out, int atMs, float f0, float f1, int ms, float vol, float wobble) {
        int start = atMs * RATE / 1000, n = ms * RATE / 1000;
        double phase = 0;
        for (int k = 0; k < n && start + k < out.length; k++) {
            if (start + k < 0) continue;
            float p = (float) k / n;
            double f = f0 + (f1 - f0) * p;
            if (wobble > 0) f *= 1 + .02 * Math.sin(2 * Math.PI * 9 * k / RATE);
            phase += 2 * Math.PI * f / RATE;
            double env = Math.sin(Math.PI * p);            // swells in and out, no clicks
            out[start + k] += (float) (Math.sin(phase) * env * env * vol);
        }
    }

    /** Adds her robot voice (the same tone as her other noises) into a mix. */
    private static void mixRobot(float[] out, int atMs, List<Seg> q) {
        float[] v = synth(q);
        int start = atMs * RATE / 1000;
        for (int i = 0; i < v.length && start + i < out.length; i++) out[start + i] += v[i];
    }

    /* ------------------------------------------------------------ rendering */

    private static int render(List<Seg> q) { return play(synth(q)); }

    private static float[] synth(List<Seg> q) {
        int total = 0;
        for (Seg g : q) total += g.ms * RATE / 1000;
        float[] pcm = new float[total];
        int i = 0;
        double phase = 0;
        for (Seg g : q) {
            int n = g.ms * RATE / 1000;
            int attack = Math.min(n / 4, RATE * 8 / 1000), release = Math.min(n / 3, RATE * 30 / 1000);
            for (int k = 0; k < n; k++, i++) {
                if (g.f0 <= 0) continue;
                float p = (float) k / n;
                double f = g.f0 + (g.f1 - g.f0) * p;
                if (g.wobble > 0) f *= 1 + .035 * Math.sin(2 * Math.PI * 22 * k / RATE);   // cute vibrato
                phase += 2 * Math.PI * f / RATE;
                // soft, round "robot voice" tone: fundamental plus a little second and third harmonic
                double v = Math.sin(phase) + .28 * Math.sin(2 * phase) + .08 * Math.sin(3 * phase);
                float env = Math.min(1f, Math.min((float) k / Math.max(1, attack), (float) (n - k) / Math.max(1, release)));
                pcm[i] = (float) (v / 1.36 * env * g.vol * 0.55);
            }
        }
        return pcm;
    }

    /** Plays a mix (values about -1..1) and returns its length in ms. */
    private static int play(float[] mix) {
        // trim trailing silence, and keep loud overlaps from clipping
        int total = mix.length;
        while (total > 0 && Math.abs(mix[total - 1]) < 1e-4f) total--;
        float peak = 0;
        for (int i = 0; i < total; i++) peak = Math.max(peak, Math.abs(mix[i]));
        float gain = peak > .95f ? .95f / peak : 1f;
        short[] pcm = new short[total];
        for (int i = 0; i < total; i++) pcm[i] = (short) (mix[i] * gain * Short.MAX_VALUE);
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
