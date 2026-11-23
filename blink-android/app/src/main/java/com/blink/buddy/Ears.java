package com.blink.buddy;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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

    void stop() { if (recognizer != null && listening) recognizer.stopListening(); }

    void destroy() { if (recognizer != null) recognizer.destroy(); }

    private void done() { listening = false; cb.onListening(false); }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onError(int error) {
        done();
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
