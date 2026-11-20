package com.blink.buddy;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Sweety's offline brain on the Cactus 4 engine:
 * Needle 3 (121M parameters) turns a sentence into one tool call, and Whistle (17 MB) turns speech into text.
 * The engine keeps one global state, so every call goes through this object on one worker thread.
 */
final class NeedleBrain {
    private static final String TAG = "SweetyBrain";
    // volatile, and read without the lock: the screen must never wait while Needle or Whistle is busy
    private volatile boolean started, textReady, speechReady;
    volatile String status = "starting…";

    synchronized void ensure(Context ctx) {
        if (started) return;
        started = true;
        if (!CactusNative.LOADED) { status = "Offline brain not available on this phone, using built-in rules"; return; }
        try {
            CactusNative.setThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
            byte[] needle = readAsset(ctx, "needle3.cact");
            if (needle != null && CactusNative.load(needle) >= 0) {
                int rc = CactusNative.init(Call.SYSTEM_PROMPT, Call.TOOLS_JSON);
                textReady = rc >= 0;
                if (!textReady) Log.w(TAG, "needle init failed: " + CactusNative.lastError());
            } else Log.w(TAG, "needle load failed: " + CactusNative.lastError());
            byte[] whistle = readAsset(ctx, "whistle.cact");
            if (whistle != null && CactusNative.load(whistle) >= 0) speechReady = true;
            else Log.w(TAG, "whistle load failed: " + CactusNative.lastError());
            if (textReady) { // loading Whistle after init must not drop Needle's tools; re-init to be safe
                textReady = CactusNative.init(Call.SYSTEM_PROMPT, Call.TOOLS_JSON) >= 0;
            }
            status = (textReady ? "Needle 3: ready" : "Needle 3: not loaded (" + CactusNative.lastError() + ")")
                + " · " + (speechReady ? "Whistle: ready, hearing works offline" : "Whistle: not loaded, using the phone's speech service");
            if (speechReady) {
                // Warm-up: Whistle's first transcription is much slower than the rest, so do it now on half a second
                // of silence instead of on the user's first "Hey Sweety".
                long t0 = System.currentTimeMillis();
                float[] hum = new float[16000]; // a quiet hum, so the whole model runs (pure silence is skipped)
                for (int i = 0; i < hum.length; i++) hum[i] = (float) (Math.sin(i * 2 * Math.PI * 220 / 16000) * .05);
                try { CactusNative.transcribe(hum, "en", Assistant.KEYWORDS); } catch (Throwable ignored) { }
                Log.i(TAG, "Whistle warmed up in " + (System.currentTimeMillis() - t0) + " ms");
            }
        } catch (Throwable t) {
            Log.w(TAG, "brain failed to start", t);
            status = "Offline brain error: " + t.getClass().getSimpleName() + ", using built-in rules";
        }
    }

    boolean textReady() { return textReady; }
    boolean speechReady() { return speechReady; }

    /** Returns the tool call, or null when Needle answers with no call. */
    synchronized Call parse(String text) {
        if (!textReady) return null;
        String out;
        try {
            CactusNative.reset();
            out = CactusNative.complete(text, 160);
            if (out == null) {
                CactusNative.init(Call.SYSTEM_PROMPT, Call.TOOLS_JSON);
                out = CactusNative.complete(text, 160);
            }
        } catch (Throwable t) { Log.w(TAG, "complete failed", t); return null; }
        if (out == null) return null;
        Log.d(TAG, "needle3: " + out);
        try {
            JSONObject o = json(out);
            if (o == null) return null;
            float conf = (float) o.optDouble("confidence", .8);
            JSONArray calls = o.optJSONArray("function_calls");
            if (calls == null || calls.length() == 0) return null;
            JSONObject first = calls.getJSONObject(0);
            Object a = first.opt("arguments");
            JSONObject args = a instanceof JSONObject ? (JSONObject) a : a instanceof String ? new JSONObject((String) a) : new JSONObject();
            return new Call(first.optString("name"), args, conf, "needle3");
        } catch (Exception e) {
            Log.w(TAG, "could not read needle output", e);
            return null;
        }
    }

    /** Speech to text with Whistle. pcm: 16 kHz mono floats in [-1, 1]. Returns null if it fails. */
    synchronized String transcribe(float[] pcm, String keywords) {
        if (!speechReady) return null;
        try {
            String out = CactusNative.transcribe(pcm, "en", keywords);
            if (out == null) { Log.w(TAG, "transcribe failed: " + CactusNative.lastError()); return null; }
            JSONObject o = json(out);
            String text = o != null ? o.optString("text", "") : out;
            return text.trim();
        } catch (Throwable t) { Log.w(TAG, "transcribe failed", t); return null; }
    }

    private static JSONObject json(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        try { return new JSONObject(s.substring(start)); } catch (Exception e) { return null; }
    }

    private static byte[] readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(36 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Exception e) { return null; }
    }
}
