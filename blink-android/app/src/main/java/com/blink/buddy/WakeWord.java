package com.blink.buddy;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

/**
 * "Hey Sweety" detector: a tiny streaming keyword-spotting model (sherpa-onnx, Apache-2.0, about 5 MB, in the app's
 * assets under kws/) that listens continuously and only wakes up for the phrases in kws/keywords.txt.
 * Much more reliable and lighter than transcribing every sound. Not thread-safe: use from the listener thread.
 */
final class WakeWord {
    private static final String TAG = "SweetyWake";
    private KeywordSpotter kws;
    private OnlineStream stream;
    private WakeModel own; // Sweety's own trained "Hey Sweety" model, used when the app has it (assets/wake/)
    String failure;

    /** Loads the model; returns false (and keeps the reason) if it can't, so the old Whistle listener takes over. */
    boolean load(Context c) {
        if (kws != null || own != null) return true;
        if (WakeModel.present(c)) {
            try { WakeModel m = new WakeModel(); m.load(c); own = m; return true; }
            catch (Throwable e) { Log.w(TAG, "Hey Sweety model failed, using the keyword spotter", e); }
        }
        try {
            String[] files = c.getAssets().list("kws");
            if (files == null || files.length == 0) { failure = "wake-word model missing"; return false; }
            OnlineTransducerModelConfig t = new OnlineTransducerModelConfig();
            t.setEncoder("kws/encoder.onnx");
            t.setDecoder("kws/decoder.onnx");
            t.setJoiner("kws/joiner.onnx");
            OnlineModelConfig m = new OnlineModelConfig();
            m.setTransducer(t);
            m.setTokens("kws/tokens.txt");
            m.setNumThreads(1);
            m.setProvider("cpu");
            FeatureConfig f = new FeatureConfig();
            f.setSampleRate(16000);
            f.setFeatureDim(80);
            KeywordSpotterConfig k = new KeywordSpotterConfig();
            k.setFeatConfig(f);
            k.setModelConfig(m);
            k.setKeywordsFile("kws/keywords.txt");
            k.setMaxActivePaths(4);
            k.setNumTrailingBlanks(1);
            kws = new KeywordSpotter(c.getAssets(), k);
            stream = kws.createStream("");
            Log.i(TAG, "wake-word detector ready");
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "wake-word detector failed", e);
            failure = e.getClass().getSimpleName();
            kws = null;
            return false;
        }
    }

    boolean ready() { return kws != null || own != null; }

    /** Which detector is listening, for the status line. */
    String kind() { return own != null ? "Sweety's own Hey Sweety model" : kws != null ? "the keyword spotter" : "none"; }

    /** The own model's highest score in the last few seconds (-1 with the keyword spotter). */
    float takePeak() { return own != null ? own.takePeak() : -1f; }

    /** Feeds 16 kHz mono audio; returns the keyword heard (e.g. "HEY SWEETY") or null. */
    String accept(float[] pcm, int n) {
        if (own != null) {
            try { return own.accept(pcm, n); } catch (Exception e) { Log.w(TAG, "Hey Sweety model", e); return null; }
        }
        if (kws == null) return null;
        float[] a = n == pcm.length ? pcm : java.util.Arrays.copyOf(pcm, n);
        stream.acceptWaveform(a, 16000);
        while (kws.isReady(stream)) {
            kws.decode(stream);
            String word = kws.getResult(stream).getKeyword();
            if (word != null && !word.isEmpty()) {
                kws.reset(stream);
                return word;
            }
        }
        return null;
    }

    /** Forget any half-heard audio (after Sweety talked, or the mic was closed). */
    void reset() {
        if (own != null) { own.reset(); return; }
        if (kws == null) return;
        try { stream.release(); } catch (Throwable ignored) { }
        stream = kws.createStream("");
    }
}
