package com.blink.buddy;

import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-process event bus: lets the talk window drive the floating Sweety's bubble and animations. */
final class Bus {
    interface Listener { void onNotificationPosted(); }
    interface SweetyListener { void onSweety(String text, String action, String emotion); }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<SweetyListener> SWEETY = new CopyOnWriteArrayList<>();

    static void add(Listener l) { LISTENERS.add(l); }
    static void remove(Listener l) { LISTENERS.remove(l); }
    static void notificationPosted() { for (Listener l : LISTENERS) l.onNotificationPosted(); }

    static void addSweety(SweetyListener l) { SWEETY.add(l); }
    static void removeSweety(SweetyListener l) { SWEETY.remove(l); }
    static void sweety(String text, String action, String emotion) { for (SweetyListener l : SWEETY) l.onSweety(text, action, emotion); }

    /** Her voice, for lip-sync: loudness and brightness (zero crossings) per frame, or just "talking for ms". */
    interface SpeechListener {
        void onSpeech(float[] loudness, float[] brightness, int fps);
        void onSpeechFor(long ms);
        void onSpeechStop();
    }

    private static final CopyOnWriteArrayList<SpeechListener> SPEECH = new CopyOnWriteArrayList<>();
    static void addSpeech(SpeechListener l) { SPEECH.add(l); }
    static void removeSpeech(SpeechListener l) { SPEECH.remove(l); }
    static void speech(float[] loud, float[] bright, int fps) { for (SpeechListener l : SPEECH) l.onSpeech(loud, bright, fps); }
    static void speechFor(long ms) { for (SpeechListener l : SPEECH) l.onSpeechFor(ms); }
    static void speechStop() { for (SpeechListener l : SPEECH) l.onSpeechStop(); }

    /** Loudness (RMS) and brightness (zero-crossing rate) of a sentence, fps frames per second, for lip-sync. */
    static float[][] envelope(float[] pcm, int rate, int fps) {
        int hop = Math.max(1, rate / fps), n = pcm.length / hop;
        float[] loud = new float[n], bright = new float[n];
        for (int f = 0; f < n; f++) {
            double sum = 0; int zc = 0;
            for (int i = f * hop, end = i + hop; i < end; i++) {
                sum += pcm[i] * pcm[i];
                if (i > f * hop && (pcm[i] >= 0) != (pcm[i - 1] >= 0)) zc++;
            }
            loud[f] = (float) Math.sqrt(sum / hop);
            bright[f] = zc / (float) hop;
        }
        return new float[][]{loud, bright};
    }

    private Bus() {}
}
