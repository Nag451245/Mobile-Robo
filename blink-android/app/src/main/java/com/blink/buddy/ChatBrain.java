package com.blink.buddy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Sweety's chat brain: Qwen3-0.6B (Apache-2.0, 4-bit, about 330 MB) running offline on Google's LiteRT-LM.
 * Downloaded once inside the app; after that, chit-chat and questions work without the internet.
 */
final class ChatBrain {
    private static final String TAG = "SweetyChat";
    static final String MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm";
    private static final long MIN_SIZE = 250_000_000L;
    private static final String PERSONA =
        "You are Sweety, a cute, cheerful little robot who lives on the user's phone as their friend and helper. "
        + "Answer in one or two short, warm, playful sentences that sound natural when spoken aloud. "
        + "Never use emojis, lists or markdown. You cannot browse the internet or see the weather or news; say so kindly if asked. "
        + "You can set alarms and timers, call or text people, switch the torch, control music and take notes when the user asks.";

    interface Progress { void onProgress(long done, long total); void onDone(boolean ok, String message); }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Engine engine;
    private Conversation conv;
    private int turns;
    private volatile boolean downloading;
    private String failure;

    ChatBrain(Context app) { this.app = app.getApplicationContext(); }

    File file() { return new File(app.getFilesDir(), "sweety-chat.litertlm"); }
    boolean downloaded() { File f = file(); return f.exists() && f.length() > MIN_SIZE; }
    boolean downloading() { return downloading; }
    synchronized boolean ready() { return engine != null; }
    synchronized String status() {
        if (engine != null) return "Chat brain (Qwen3-0.6B): ready";
        if (failure != null) return "Chat brain: " + failure;
        if (downloading) return "Chat brain: downloading…";
        return downloaded() ? "Chat brain: downloaded, starts on first chat" : "Chat brain: not downloaded";
    }

    /** Downloads the model (resumable). Callbacks arrive on the main thread. */
    void download(Progress p) {
        if (downloading || downloaded()) { if (downloaded()) main.post(() -> p.onDone(true, "Already downloaded.")); return; }
        downloading = true;
        new Thread(() -> {
            File part = new File(app.getFilesDir(), "sweety-chat.part");
            HttpURLConnection c = null;
            try {
                long have = part.exists() ? part.length() : 0;
                c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                if (code == 200) { have = 0; }
                else if (code != 206) throw new Exception("server answered " + code);
                long total = have + Math.max(0, c.getContentLengthLong());
                byte[] buf = new byte[256 * 1024];
                long done = have, lastNote = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, have > 0)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastNote > 400) { lastNote = now; final long d = done, t = total; main.post(() -> p.onProgress(d, t)); }
                    }
                }
                if (part.length() < MIN_SIZE) throw new Exception("download incomplete");
                File dest = file();
                if (dest.exists()) dest.delete();
                if (!part.renameTo(dest)) throw new Exception("could not save the file");
                synchronized (this) { failure = null; }
                downloading = false;
                main.post(() -> p.onDone(true, "Chat brain downloaded."));
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                downloading = false;
                final String msg = "Download stopped (" + e.getMessage() + "). Tap again to resume.";
                main.post(() -> p.onDone(false, msg));
            } finally {
                if (c != null) c.disconnect();
            }
        }, "sweety-download").start();
    }

    synchronized void warm() { ensure(); }

    private synchronized boolean ensure() {
        if (engine != null) return true;
        if (!downloaded()) return false;
        try {
            // The cache dir lets LiteRT-LM keep its prepared weights, so later starts are quicker.
            Engine e = new Engine(new EngineConfig(file().getAbsolutePath(), new Backend.CPU(), null, null, null, null, app.getCacheDir().getAbsolutePath()));
            e.initialize();
            engine = e;
            newConversation();
            failure = null;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "chat engine failed", t);
            failure = "could not start (" + t.getClass().getSimpleName() + ")";
            engine = null;
            return false;
        }
    }

    private void newConversation() {
        if (conv != null) { try { conv.close(); } catch (Exception ignored) { } }
        conv = engine.createConversation(new ConversationConfig(Contents.Companion.of(PERSONA)));
        turns = 0;
    }

    /** One short reply in Sweety's voice, or null if the chat brain isn't available. Blocking: call off the main thread. */
    synchronized String reply(String text) {
        if (!ensure()) return null;
        try {
            if (turns >= 5) newConversation(); // keeps the small context window fresh
            turns++;
            Message m = conv.sendMessage(text);
            StringBuilder sb = new StringBuilder();
            for (Content c : m.getContents().getContents()) if (c instanceof Content.Text) sb.append(((Content.Text) c).getText());
            String out = sb.toString().replaceAll("(?s)<think>.*?</think>", "").replaceAll("[*#_`]", "").replaceAll("\\s+", " ").trim();
            if (out.length() > 400) { int cut = out.lastIndexOf('.', 400); out = out.substring(0, cut > 80 ? cut + 1 : 400); }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            Log.w(TAG, "chat failed", t);
            try { newConversation(); } catch (Throwable ignored) { }
            return null;
        }
    }
}
