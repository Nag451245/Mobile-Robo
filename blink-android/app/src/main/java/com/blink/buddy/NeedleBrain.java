package com.blink.buddy;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Sweety's main brain: Needle 2 (45M parameters, offline) turns a sentence into one tool call. */
final class NeedleBrain {
    private static final String TAG = "SweetyNeedle";
    private boolean ready, failed;
    String status = "not started";

    synchronized boolean ensure(Context ctx) {
        if (ready) return true;
        if (failed) return false;
        if (!NeedleNative.LOADED) { failed = true; status = "native library not available on this phone"; return false; }
        try {
            byte[] model = readAsset(ctx, "needle2.cact");
            if (model == null) { failed = true; status = "model file missing"; return false; }
            int rc = NeedleNative.load(model);
            if (rc < 0) { failed = true; status = "model load failed (" + rc + ")"; return false; }
            rc = NeedleNative.init(Call.SYSTEM_PROMPT, Call.TOOLS_JSON);
            if (rc < 0) { failed = true; status = "init failed (" + rc + ")"; return false; }
            ready = true; status = "ready";
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Needle failed to start", t);
            failed = true; status = "error: " + t.getClass().getSimpleName();
            return false;
        }
    }

    /** Returns the tool call, or null when Needle answers with no call. */
    synchronized Call parse(String text) {
        if (!ready) return null;
        String out;
        try {
            NeedleNative.reset();
            out = NeedleNative.complete(text, 160);
            if (out == null) {
                NeedleNative.init(Call.SYSTEM_PROMPT, Call.TOOLS_JSON);
                out = NeedleNative.complete(text, 160);
            }
        } catch (Throwable t) { Log.w(TAG, "complete failed", t); return null; }
        if (out == null) return null;
        Log.d(TAG, "needle: " + out);
        try {
            int start = out.indexOf('{');
            JSONObject o = new JSONObject(start > 0 ? out.substring(start) : out);
            float conf = (float) o.optDouble("confidence", .8);
            JSONArray calls = o.optJSONArray("function_calls");
            if (calls == null || calls.length() == 0) return null;
            JSONObject first = calls.getJSONObject(0);
            Object a = first.opt("arguments");
            JSONObject args = a instanceof JSONObject ? (JSONObject) a : a instanceof String ? new JSONObject((String) a) : new JSONObject();
            return new Call(first.optString("name"), args, conf, "needle");
        } catch (Exception e) {
            Log.w(TAG, "could not read needle output", e);
            return null;
        }
    }

    private static byte[] readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(16 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Exception e) { return null; }
    }
}
