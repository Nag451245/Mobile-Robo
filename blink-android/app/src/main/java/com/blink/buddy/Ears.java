package com.blink.buddy;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/** Sweety's ears: Android's speech recognizer, one phrase at a time. Must be used on the main thread. */
final class Ears implements RecognitionListener {
    interface Callback {
        void onListening(boolean listening);
        void onPartial(String text);
        /** Your voice level while listening, 0..1 (the avatar glows with it). */
        default void onLevel(float level) { }
        void onHeard(String text);
        void onProblem(String message);
    }

    private final SpeechRecognizer recognizer;
    private final Callback cb;
    private boolean listening;
    /** Called instead of a problem message when the phone's recogniser itself fails (no internet, busy service…), so Whistle can take over. */
    Runnable fallback;
    /** Words Google's recogniser should expect (Android 13+), so "cartwheel" isn't heard as "Wilkart". */
    static final String[] HINTS = {"Sweety", "Hey Sweety", "cartwheel", "backflip", "butterfly", "dance", "wave your hand", "alarm", "reminder", "torch", "WhatsApp"};

    Ears(Context ctx, Callback cb) {
        this.cb = cb;
        SpeechRecognizer r = null;
        try { if (SpeechRecognizer.isRecognitionAvailable(ctx)) r = SpeechRecognizer.createSpeechRecognizer(ctx); } catch (Exception ignored) { }
        recognizer = r;
        if (recognizer != null) recognizer.setRecognitionListener(this);
    }

    boolean available() { return recognizer != null; }
    boolean listening() { return listening; }

    void listen() {
        if (recognizer == null) { cb.onProblem("Speech recognition isn't available on this phone. You can type to me instead."); return; }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        if (android.os.Build.VERSION.SDK_INT >= 33) i.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, new ArrayList<>(java.util.Arrays.asList(HINTS)));
        Log.i("SweetyEars", "listening with the phone's speech recogniser");
        try { recognizer.startListening(i); listening = true; cb.onListening(true); }
        catch (Exception e) { cb.onProblem("I couldn't start listening."); }
    }

    /** While recognising a handed-over recording: what to do if the recogniser can't (Whistle takes it). */
    private Runnable audioFallback;

    /**
     * Recognises a recording instead of the microphone: the rest of "Hey Sweety, ..." said in one breath, which the
     * wake listener kept. Android 13+ lets an app hand the phone's recogniser audio through a pipe. Returns false if
     * that isn't possible here; on any error later, orElse runs (Whistle transcribes it instead).
     */
    boolean recognizeAudio(float[] pcm, Runnable orElse) {
        if (recognizer == null || Build.VERSION.SDK_INT < 33) return false;
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, new ArrayList<>(java.util.Arrays.asList(HINTS)));
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
            audioFallback = orElse;
            recognizer.startListening(i);
            listening = true;
            Log.i("SweetyEars", "recognising the kept request with the phone's speech recogniser");
            new Thread(() -> {
                // 16-bit little-endian PCM, then a second of quiet so the recogniser knows the speech ended
                byte[] b = new byte[(pcm.length + 16000) * 2];
                for (int k = 0; k < pcm.length; k++) { int v = Math.max(-32768, Math.min(32767, Math.round(pcm[k] * 32767))); b[2 * k] = (byte) v; b[2 * k + 1] = (byte) (v >> 8); }
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(b); }
                catch (Exception e) { Log.w("SweetyEars", "couldn't hand over the recording", e); }
                try { pipe[0].close(); } catch (Exception ignored) { }
            }, "sweety-handover").start();
            return true;
        } catch (Exception e) {
            Log.w("SweetyEars", "can't hand the recording to the recogniser", e);
            audioFallback = null;
            return false;
        }
    }

    void stop() { if (recognizer != null && listening) recognizer.stopListening(); }

    void destroy() { if (recognizer != null) recognizer.destroy(); }

    private void done() { listening = false; cb.onListening(false); }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { cb.onLevel(Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f))); }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onError(int error) {
        done();
        if (audioFallback != null) { Runnable r = audioFallback; audioFallback = null; Log.i("SweetyEars", "phone recogniser error " + error + " on the kept request; Whistle takes it"); r.run(); return; }
        Log.i("SweetyEars", "phone recogniser error " + error);
        boolean serviceTrouble = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER
            || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error >= 10; // 10+: server disconnected, language unavailable…
        if (serviceTrouble && fallback != null) { fallback.run(); return; }
        String msg;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH: msg = "I didn't catch that. Try again?"; break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: msg = "I didn't hear anything."; break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "I need the microphone permission to hear you."; break;
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "Speech recognition needs the internet on this phone."; break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: msg = "Hold on, I'm still listening."; break;
            default: msg = "Something went wrong while listening. Try again?";
        }
        cb.onProblem(msg);
    }

    @Override
    public void onResults(Bundle results) {
        done();
        audioFallback = null;
        ArrayList<String> list = results != null ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
        if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) cb.onProblem("I didn't catch that. Try again?");
        else { Log.i("SweetyEars", "heard: \"" + list.get(0) + "\""); cb.onHeard(list.get(0)); }
    }

    @Override
    public void onPartialResults(Bundle partial) {
        ArrayList<String> list = partial != null ? partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
        if (list != null && !list.isEmpty()) cb.onPartial(list.get(0));
    }
}
