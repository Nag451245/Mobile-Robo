package com.blink.buddy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sweety's 3D avatar in a transparent WebView (assets/avatar.html): the built-in robot Sweety, or the VRM character
 * the user imported. The app drives it: listening (with your voice level), thinking, speaking (lip-sync to her
 * real voice), gestures and moods.
 */
final class AvatarView {
    private static final String TAG = "SweetyAvatar";
    /** The imported VRM is served to the page from the app's private storage at this made-up address. */
    private static final String VRM_URL = "https://sweety.local/avatar.vrm";

    final WebView web;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> queue = new ArrayList<>();
    private boolean ready;
    private long lastLevel;

    static File vrmFile(Context c) { return new File(c.getFilesDir(), "avatar.vrm"); }
    static boolean usingVrm(Context c) { return "vrm".equals(prefs(c).getString("avatar", "robot")) && vrmFile(c).exists(); }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("blink", Context.MODE_PRIVATE); }

    @SuppressLint("SetJavaScriptEnabled")
    AvatarView(Context c) {
        ctx = c;
        web = new WebView(c);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "AndroidAvatar");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!"sweety.local".equals(u.getHost())) return null;
                File f = vrmFile(ctx);
                Map<String, String> h = new HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                try {
                    if (!"/avatar.vrm".equals(u.getPath()) || !f.exists()) return new WebResourceResponse("text/plain", null, 404, "Not Found", h, null);
                    return new WebResourceResponse("model/gltf-binary", null, 200, "OK", h, new FileInputStream(f));
                } catch (Exception e) { return null; }
            }
        });
        String skin = prefs(c).getString("skin", "bubblegum");
        String url = "file:///android_asset/avatar.html?skin=" + Uri.encode(skin)
            + (usingVrm(c) ? "&kind=vrm&src=" + Uri.encode(VRM_URL) : "&kind=robot");
        web.loadUrl(url);
    }

    private final class Bridge {
        @JavascriptInterface public void ready(String info) {
            Log.i(TAG, "page ready " + info);
            main.post(() -> { ready = true; for (String js : queue) web.evaluateJavascript(js, null); queue.clear(); });
        }
        @JavascriptInterface public void log(String m) { Log.i(TAG, m); }
    }

    private void js(String code) {
        main.post(() -> {
            if (ready) web.evaluateJavascript(code, null);
            else { queue.add(code); while (queue.size() > 20) queue.remove(0); }
        });
    }

    private static String q(String s) { return s == null ? "null" : "'" + s.replaceAll("[^a-zA-Z0-9_:\\-]", "") + "'"; }

    void state(String s) { js("Avatar.state(" + q(s) + ")"); }

    /** Your voice level, 0..1, at most ten times a second. */
    void level(float l) {
        long now = System.currentTimeMillis();
        if (now - lastLevel < 100) return;
        lastLevel = now;
        js(String.format(Locale.ROOT, "Avatar.level(%.2f)", l));
    }

    void gesture(String g) { if (g != null) js("Avatar.gesture(" + q(g) + ")"); }
    void mood(String m, float secs) { if (m != null) js(String.format(Locale.ROOT, "Avatar.mood(%s,%.1f)", q(m), secs)); }

    void speak(float[] loud, float[] bright, int fps) {
        StringBuilder a = new StringBuilder("["), b = new StringBuilder("[");
        for (int i = 0; i < loud.length; i++) {
            if (i > 0) { a.append(','); b.append(','); }
            a.append(String.format(Locale.ROOT, "%.3f", loud[i]));
            b.append(String.format(Locale.ROOT, "%.3f", bright[i]));
        }
        js("Avatar.speak(" + a.append(']') + "," + b.append(']') + "," + fps + ",80)"); // ~80 ms: the sound reaching the speaker
    }

    void speakFor(long ms) { js("Avatar.speakFor(" + ms + ")"); }
    void stop() { js("Avatar.stop()"); }

    void destroy() {
        main.removeCallbacksAndMessages(null);
        try { web.destroy(); } catch (Exception ignored) { }
    }
}
