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
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sweety's chat brain: Qwen3-0.6B (Apache-2.0, 4-bit, about 330 MB) running offline on Google's LiteRT-LM.
 * Downloaded once inside the app; after that, chit-chat and questions work without the internet.
 */
final class ChatBrain {
    private static final String TAG = "SweetyChat";
    static final String MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm";
    private static final long MIN_SIZE = 250_000_000L;
    // Kept short: every new chat re-reads it, and a small phone reads about 50-200 words a second.
    private static final String PERSONA =
        "You are Sweety, a tiny, bubbly, cheeky robot girl who lives on the user's phone. "
        + "Answer the question correctly and simply first, then add a playful touch. If teased, tease back sweetly. "
        + "Reply in one to three short spoken sentences, sometimes with \"hehe\". No emojis, lists or markdown. "
        + "You can't see the internet, weather or news.";

    /** Example chats the small model copies the style of. */
    private static List<Message> examples() {
        String[][] ex = {
            {"You're stupid.", "Hey, rude! I'm small, not silly. Ask me something tricky and I'll prove it!"},
            {"What is 12 times 3?", "That's 36! Easy peasy robot math."},
            {"I'm bored.", "Ooh, let's fix that! Want a joke, a riddle, or a little dance?"},
        };
        List<Message> out = new ArrayList<>();
        for (String[] e : ex) { out.add(Message.Companion.user(e[0])); out.add(Message.Companion.model(e[1])); }
        return out;
    }

    interface Progress { void onProgress(long done, long total); void onDone(boolean ok, String message); }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Engine engine; // volatile: the screen reads the state without waiting for a reply to finish
    private Conversation conv;
    private int turns;
    private volatile boolean downloading;
    private volatile String failure;

    ChatBrain(Context app) { this.app = app.getApplicationContext(); }

    File file() { return new File(app.getFilesDir(), "sweety-chat.litertlm"); }
    boolean downloaded() { File f = file(); return f.exists() && f.length() > MIN_SIZE; }
    boolean downloading() { return downloading; }
    boolean ready() { return engine != null; }
    String status() {
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
        long t0 = System.currentTimeMillis();
        Engine e = null;
        try {
            // The cache dir lets LiteRT-LM keep its prepared weights, so later starts are quicker.
            e = new Engine(new EngineConfig(file().getAbsolutePath(), new Backend.CPU(), null, null, null, null, app.getCacheDir().getAbsolutePath()));
            e.initialize();
            engine = e;
            newConversation();
            failure = null;
            Log.i(TAG, "chat brain ready in " + (System.currentTimeMillis() - t0) + " ms (" + mode + " setup)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "chat engine failed", t);
            failure = "could not start (" + why(t) + ")";
            if (e != null) { try { e.close(); } catch (Throwable ignored) { } }
            engine = null;
            return false;
        }
    }

    /** The error in a few words, for the status line. */
    static String why(Throwable t) {
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m.replaceAll("\\s+", " "));
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    /** "full": persona, example chats and lively sampling. "plain": persona only, in case this engine refuses the rest. */
    private String mode = "full";

    private void newConversation() {
        if (conv != null) { try { conv.close(); } catch (Throwable ignored) { } conv = null; }
        turns = 0;
        if ("full".equals(mode)) {
            try {
                // A little randomness (temperature 0.8) keeps her lively instead of repeating the same safe line.
                conv = engine.createConversation(new ConversationConfig(Contents.Companion.of(PERSONA), examples(),
                    Collections.emptyList(), new SamplerConfig(40, 0.95, 0.8, (int) (System.nanoTime() & 0x7fffffff))));
                return;
            } catch (Throwable t) {
                Log.w(TAG, "full chat setup failed, using the plain one", t);
                mode = "plain";
            }
        }
        conv = engine.createConversation(new ConversationConfig(Contents.Companion.of(PERSONA)));
    }

    /** One short reply in Sweety's voice, or null if the chat brain isn't available. Blocking: call off the main thread. */
    synchronized String reply(String text) {
        if (!ensure()) return null;
        for (int attempt = 0; attempt < 2; attempt++) {
            long t0 = System.currentTimeMillis();
            try {
                if (conv == null || turns >= 4) newConversation(); // keeps the small context window fresh
                turns++;
                String out = clean(conv.sendMessage(text));
                Log.i(TAG, "chat reply in " + (System.currentTimeMillis() - t0) + " ms (" + mode + "): " + out);
                failure = null;
                return out.isEmpty() ? null : out;
            } catch (Throwable t) {
                Log.w(TAG, "chat failed (try " + (attempt + 1) + ", " + mode + " setup)", t);
                failure = "last reply failed (" + why(t) + ")";
                if (!String.valueOf(t.getMessage()).contains("DEADLINE")) mode = "plain"; // the fancy setup may be what broke; retry with the plain one
                try { newConversation(); }
                catch (Throwable t2) { Log.w(TAG, "could not reset the chat", t2); failure = "could not reset (" + why(t2) + ")"; return null; }
            }
        }
        return null;
    }

    static String clean(Message m) {
        StringBuilder sb = new StringBuilder();
        if (m != null && m.getContents() != null)
            for (Content c : m.getContents().getContents()) if (c instanceof Content.Text) sb.append(((Content.Text) c).getText());
        String out = sb.toString().replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?s)<think>.*", "").replaceAll("[*#_`]", "")
            .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "") // emojis
            .replaceAll("^(?i)sweety\\s*:\\s*", "").replaceAll("\\s+", " ").trim();
        if (out.length() > 400) { int cut = out.lastIndexOf('.', 400); out = out.substring(0, cut > 80 ? cut + 1 : 400); }
        return out;
    }
}
