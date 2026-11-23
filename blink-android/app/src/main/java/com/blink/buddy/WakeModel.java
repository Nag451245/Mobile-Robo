package com.blink.buddy;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Sweety's own "Hey Sweety" model (trained by blink-android/wakeword/train.py, openWakeWord approach).
 * Every 80 ms: a mel spectrogram of the newest 80 ms plus the 30 ms before it (8 frames, scaled x/10 + 2), one
 * speech embedding (Google speech_embedding) from the latest 76 mel frames, and a score from the latest 16
 * embeddings (1.28 s). This is exactly how the trainer computes its features (window_feats / stream_scores), so the
 * phone sees what training saw. Models live in the app's assets under wake/. Not thread-safe: use from one thread.
 */
final class WakeModel {
    private static final String TAG = "SweetyWake";
    static final String LABEL = "HEY_SWEETY";
    private static final int CHUNK = 1280, CONTEXT = 480, MEL = 32, EMB = 96;

    private OrtEnvironment env;
    private OrtSession mel, emb, head;
    private String melIn, embIn, headIn;
    float threshold = 0.5f;

    private final float[] window = new float[CONTEXT + CHUNK]; // last 30 ms + the 80 ms being filled
    private int filled;                                         // samples of the current 80 ms chunk
    private final ArrayDeque<float[]> mels = new ArrayDeque<>();
    private final ArrayDeque<float[]> embs = new ArrayDeque<>();
    private int cooldown;
    private float peak;

    static boolean present(Context c) {
        try { c.getAssets().open("wake/hey_sweety.onnx").close(); return true; } catch (Exception e) { return false; }
    }

    boolean load(Context c) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(1);
        mel = env.createSession(asset(c, "wake/melspectrogram.onnx"), o);
        emb = env.createSession(asset(c, "wake/embedding_model.onnx"), o);
        head = env.createSession(asset(c, "wake/hey_sweety.onnx"), o);
        melIn = mel.getInputNames().iterator().next();
        embIn = emb.getInputNames().iterator().next();
        headIn = head.getInputNames().iterator().next();
        try {
            String m = new String(asset(c, "wake/metrics.json"), "UTF-8");
            threshold = (float) new org.json.JSONObject(m).getDouble("threshold");
        } catch (Exception e) { Log.w(TAG, "no metrics.json, threshold " + threshold); }
        reset();
        Log.i(TAG, "Hey Sweety model ready, threshold " + threshold);
        return true;
    }

    private static byte[] asset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[64 * 1024]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /** Forget what was heard: like the trainer's start, 30 ms of silence and a mel buffer of ones. */
    void reset() {
        java.util.Arrays.fill(window, 0f);
        filled = 0;
        mels.clear();
        embs.clear();
        for (int i = 0; i < 76; i++) { float[] r = new float[MEL]; java.util.Arrays.fill(r, 1f); mels.add(r); }
        cooldown = 0;
    }

    /** Highest score since the last call (for the listener's 3-second status line). */
    float takePeak() { float p = peak; peak = 0; return p; }

    /** Feeds 16 kHz mono audio (-1..1); returns LABEL when "Hey Sweety" just ended, else null. */
    String accept(float[] pcm, int n) throws Exception {
        String hit = null;
        for (int i = 0; i < n; i++) {
            window[CONTEXT + filled++] = pcm[i] * 32768f; // the models expect 16-bit sample values
            if (filled == CHUNK) {
                if (step()) hit = LABEL;
                System.arraycopy(window, CHUNK, window, 0, CONTEXT); // keep the last 30 ms as the next context
                filled = 0;
            }
        }
        return hit;
    }

    private boolean step() throws Exception {
        float[] m;
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), new long[]{1, window.length});
             OrtSession.Result r = mel.run(Collections.singletonMap(melIn, t))) {
            m = flatten(r.get(0).getValue()); // [1,1,8,32]
        }
        for (int f = 0; f + MEL <= m.length; f += MEL) {
            float[] row = new float[MEL];
            for (int j = 0; j < MEL; j++) row[j] = m[f + j] / 10f + 2f;
            mels.add(row);
            if (mels.size() > 76) mels.poll();
        }
        float[] in = new float[76 * MEL];
        int k = 0;
        for (float[] row : mels) { System.arraycopy(row, 0, in, k, MEL); k += MEL; }
        float[] e;
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 76, MEL, 1});
             OrtSession.Result r = emb.run(Collections.singletonMap(embIn, t))) {
            e = flatten(r.get(0).getValue()); // [1,1,1,96]
        }
        embs.add(e);
        if (embs.size() > 16) embs.poll();
        if (cooldown > 0) cooldown--;
        if (embs.size() < 16) return false;
        float[] h = new float[16 * EMB];
        k = 0;
        for (float[] v : embs) { System.arraycopy(v, 0, h, k, EMB); k += EMB; }
        float score;
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), new long[]{1, 16, EMB});
             OrtSession.Result r = head.run(Collections.singletonMap(headIn, t))) {
            score = flatten(r.get(0).getValue())[0];
        }
        peak = Math.max(peak, score);
        if (score >= threshold && cooldown == 0) { cooldown = 25; return true; } // then 2 s of rest
        return false;
    }

    /** Any nested float array from onnxruntime, flattened in order. */
    private static float[] flatten(Object o) {
        java.util.ArrayList<Float> out = new java.util.ArrayList<>();
        walk(o, out);
        float[] f = new float[out.size()];
        for (int i = 0; i < f.length; i++) f[i] = out.get(i);
        return f;
    }

    private static void walk(Object o, java.util.List<Float> out) {
        if (o instanceof float[]) { for (float v : (float[]) o) out.add(v); }
        else if (o instanceof Object[]) { for (Object x : (Object[]) o) walk(x, out); }
    }

    void close() {
        try { if (mel != null) mel.close(); if (emb != null) emb.close(); if (head != null) head.close(); } catch (Exception ignored) { }
    }
}
