package com.blink.buddy;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Voice Match: "Hey Sweety" in the user's own voice, like Google's Voice Match. The user says it a few times in the
 * app; each recording becomes a template, a short sequence of speech embeddings (Google's speech_embedding, the same
 * features the wake model uses, one every 80 ms). While listening, every new embedding updates an end-anchored
 * dynamic-time-warping match against each template (tolerant of saying it faster or slower, a few hundred
 * multiply-adds per 80 ms), and she wakes when the live speech comes close enough to any of them.
 * Tested on 11 voices before it went in: at distance 0.10 it caught 60% of clearly spoken and 44% of phone-mic
 * "Hey Sweety"s and woke on 1 of 2112 near-miss sentences; it adds to the wake model rather than replacing it.
 */
final class VoiceMatch {
    private static final String TAG = "SweetyWake";
    static final float DISTANCE = 0.10f; // see above
    static final int SAMPLES = 4;

    private final List<float[][]> templates = new ArrayList<>();
    private double[][] prev, prevN; // per template: the last DTW column (cost, path length)
    private int cooldown;
    private float best = 9; // closest match since the last status line

    static File file(Context c) { return new File(c.getFilesDir(), "voicematch.json"); }
    static boolean enrolled(Context c) { return file(c).exists(); }

    /** Loads the saved templates; false if the user hasn't set up Voice Match. */
    boolean load(Context c) {
        templates.clear();
        try {
            if (!enrolled(c)) return false;
            JSONArray ts = new JSONObject(new String(java.nio.file.Files.readAllBytes(file(c).toPath()), "UTF-8")).getJSONArray("templates");
            for (int i = 0; i < ts.length(); i++) {
                JSONArray t = ts.getJSONArray(i);
                float[][] frames = new float[t.length()][];
                for (int k = 0; k < t.length(); k++) {
                    JSONArray v = t.getJSONArray(k);
                    frames[k] = new float[v.length()];
                    for (int j = 0; j < v.length(); j++) frames[k][j] = (float) v.getDouble(j);
                }
                if (frames.length >= 3) templates.add(frames);
            }
            reset();
            Log.i(TAG, "Voice Match ready: " + templates.size() + " samples of your voice");
            return !templates.isEmpty();
        } catch (Exception e) {
            Log.w(TAG, "Voice Match couldn't load", e);
            templates.clear();
            return false;
        }
    }

    boolean ready() { return !templates.isEmpty(); }

    void reset() {
        prev = new double[templates.size()][];
        prevN = new double[templates.size()][];
        cooldown = 0;
    }

    /** Closest match since the last call (for the listener's status line); 9 when nothing came close. */
    float takeBest() { float b = best; best = 9; return b; }

    /** The next 80 ms embedding (unit length); true when the user just said "Hey Sweety". */
    boolean accept(float[] e) {
        if (templates.isEmpty()) return false;
        double closest = Double.MAX_VALUE;
        for (int t = 0; t < templates.size(); t++) {
            float[][] T = templates.get(t);
            int L = T.length;
            double[] cur = new double[L], curN = new double[L];
            double[] p = prev[t], pn = prevN[t];
            for (int i = 0; i < L; i++) {
                double d = 1 - dot(T[i], e);
                if (i == 0) { cur[i] = d; curN[i] = 1; continue; } // a match can start anywhere
                // the cheapest way here, by average cost along the path: diagonal, from below, or from the left
                double bc = Double.MAX_VALUE, bn = 1;
                if (p != null && avg(p[i - 1], pn[i - 1]) < bc / bn) { bc = p[i - 1]; bn = pn[i - 1]; }
                if (avg(cur[i - 1], curN[i - 1]) < bc / bn) { bc = cur[i - 1]; bn = curN[i - 1]; }
                if (p != null && avg(p[i], pn[i]) < bc / bn) { bc = p[i]; bn = pn[i]; }
                cur[i] = bc + d; curN[i] = bn + 1;
            }
            prev[t] = cur; prevN[t] = curN;
            closest = Math.min(closest, cur[L - 1] / curN[L - 1]);
        }
        best = (float) Math.min(best, closest);
        if (cooldown > 0) { cooldown--; return false; }
        if (closest <= DISTANCE) { cooldown = 25; reset(); return true; } // then 2 s of rest
        return false;
    }

    private static double avg(double c, double n) { return n <= 0 ? Double.MAX_VALUE : c / n; }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /* ------------------------------------------------------------ enrollment */

    /**
     * A template from one recording of "Hey Sweety" (16 kHz, -1..1): its speech embeddings from once most of "hey" is
     * in view to the end of "Sweety". The embeddings are computed exactly as when listening (WakeModel.embed).
     */
    static float[][] template(WakeModel features, float[] said) throws Exception {
        int lead = 9600, tail = 6400; // 0.6 s of quiet before, 0.4 s after
        float[] a = new float[lead + said.length + tail];
        System.arraycopy(said, 0, a, lead, said.length);
        List<float[]> e = features.embed(a);
        float peak = 0;
        for (float v : said) peak = Math.max(peak, Math.abs(v));
        int s = -1, t = -1;
        for (int i = 0; i < said.length; i++) if (Math.abs(said[i]) > 0.1f * peak) { if (s < 0) s = i; t = i; }
        if (s < 0) return null;
        int k0 = (lead + s) / 1280 + 3, k1 = (lead + t) / 1280;
        if (k1 - k0 < 2) k0 = Math.max(0, k1 - 3);
        List<float[]> out = new ArrayList<>();
        for (int k = Math.max(0, k0); k <= Math.min(e.size() - 1, k1); k++) out.add(e.get(k));
        return out.toArray(new float[0][]);
    }

    /** Saves the templates (replacing any earlier Voice Match). */
    static void save(Context c, List<float[][]> ts) throws Exception {
        JSONArray all = new JSONArray();
        for (float[][] t : ts) {
            JSONArray frames = new JSONArray();
            for (float[] v : t) { JSONArray row = new JSONArray(); for (float x : v) row.put((double) x); frames.put(row); }
            all.put(frames);
        }
        java.nio.file.Files.write(file(c).toPath(), new JSONObject().put("templates", all).put("distance", DISTANCE).toString().getBytes("UTF-8"));
    }

    static void forget(Context c) { file(c).delete(); }

    /** Trims a recording to the spoken part (plus 100 ms each side); null if there's hardly any speech. */
    static float[] spoken(float[] pcm) {
        float peak = 0;
        for (float v : pcm) peak = Math.max(peak, Math.abs(v));
        if (peak < 0.02f) return null;
        int s = -1, t = -1;
        for (int i = 0; i < pcm.length; i++) if (Math.abs(pcm[i]) > 0.12f * peak) { if (s < 0) s = i; t = i; }
        if (t - s < 16000 * 3 / 10 || t - s > 16000 * 3) return null; // "Hey Sweety" takes 0.3-3 s
        s = Math.max(0, s - 1600); t = Math.min(pcm.length - 1, t + 1600);
        // kept at the microphone's own level: live listening isn't turned up or down either
        return java.util.Arrays.copyOfRange(pcm, s, t + 1);
    }
}
