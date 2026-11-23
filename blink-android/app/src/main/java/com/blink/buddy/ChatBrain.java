package com.blink.buddy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sweety's chat brain: Qwen3-0.6B (Apache-2.0, 4-bit, about 330 MB) running offline on Google's LiteRT-LM.
 * Downloaded once inside the app; after that, chit-chat and questions work without the internet.
 *
 * The engine itself runs in its own process ({@link ChatService}): if it ever crashes in native code on some phone,
 * only that process ends. After two such crashes Sweety stops starting it and says so, instead of crash-looping.
 */
final class ChatBrain {
    private static final String TAG = "SweetyChat";
    static final String MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm";
    private static final long MIN_SIZE = 250_000_000L;
    private static final int MAX_CRASHES = 2;
    private static final long REPLY_TIMEOUT_MS = 180_000;

    interface Progress { void onProgress(long done, long total); void onDone(boolean ok, String message); }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbox;
    private final Object lock = new Object();
    private volatile boolean downloading, ready;
    private volatile String failure;

    // connection to the chat process
    private Messenger service;
    private boolean bound;
    private int seq;
    private int pendingSeq = -1;
    private CountDownLatch pendingDone;
    private Bundle pendingResult;

    ChatBrain(Context app) {
        this.app = app.getApplicationContext();
        this.prefs = this.app.getSharedPreferences("blink", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("sweety-chat-replies");
        t.start();
        inbox = new Messenger(new Handler(t.getLooper(), this::onAnswer));
    }

    static File modelFile(Context c) { return new File(c.getFilesDir(), "sweety-chat.litertlm"); }
    File file() { return modelFile(app); }
    boolean downloaded() { File f = file(); return f.exists() && f.length() > MIN_SIZE; }
    boolean downloading() { return downloading; }
    boolean ready() { return ready; }

    String status() {
        if (gaveUp()) return "Chat brain: stopped after it crashed " + MAX_CRASHES + " times on this phone. Commands and Hey Sweety still work";
        if (ready) return "Chat brain (Qwen3-0.6B): ready";
        if (failure != null) return "Chat brain: " + failure;
        if (downloading) return "Chat brain: downloading…";
        return downloaded() ? "Chat brain: downloaded, starts on first chat" : "Chat brain: not downloaded";
    }

    /* ------------------------------------------------------------ crash guard */
    /** Crashes are counted per installed version, so an update (maybe with a fixed engine) tries again. */
    private String crashKey() {
        long v = 0;
        try { v = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime; } catch (Exception ignored) { }
        return "chat_crashes_" + v;
    }

    private boolean gaveUp() { return prefs.getInt(crashKey(), 0) >= MAX_CRASHES; }

    private void countCrash() {
        int n = prefs.getInt(crashKey(), 0) + 1;
        prefs.edit().putInt(crashKey(), n).apply();
        failure = "the chat engine stopped unexpectedly (" + n + " of " + MAX_CRASHES + ")";
        Log.w(TAG, "the chat engine process stopped (" + n + " of " + MAX_CRASHES + ")");
    }

    /* ------------------------------------------------------------ talking to the chat process */
    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            synchronized (lock) { service = new Messenger(b); lock.notifyAll(); }
            try { b.linkToDeath(ChatBrain.this::died, 0); } catch (Exception e) { died(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { died(); }
    };

    /** The chat process ended (a crash, or Android reclaiming memory). Anything waiting for it gets no answer. */
    private void died() {
        CountDownLatch waiting;
        synchronized (lock) {
            if (service == null && pendingDone == null) return;
            service = null;
            ready = false;
            waiting = pendingDone;
            pendingResult = null;
            pendingSeq = -1;
            pendingDone = null;
        }
        if (waiting != null) { countCrash(); waiting.countDown(); }
        main.post(() -> {
            if (bound) { try { app.unbindService(conn); } catch (Exception ignored) { } bound = false; }
        });
    }

    private boolean onAnswer(Message m) {
        synchronized (lock) {
            if (m.arg1 != pendingSeq || pendingDone == null) return true; // a late answer to a request we gave up on
            pendingResult = m.getData();
            pendingDone.countDown();
        }
        return true;
    }

    /** Binds to the chat process (from a background thread) and waits until it's connected. */
    private Messenger connect(long waitMs) throws InterruptedException {
        synchronized (lock) {
            if (service != null) return service;
        }
        main.post(() -> {
            if (!bound) bound = app.bindService(new Intent(app, ChatService.class), conn, Context.BIND_AUTO_CREATE);
        });
        long until = System.currentTimeMillis() + waitMs;
        synchronized (lock) {
            while (service == null && System.currentTimeMillis() < until) lock.wait(Math.max(1, until - System.currentTimeMillis()));
            return service;
        }
    }

    /** Sends one request to the chat process and waits for its answer (null if it timed out or the process died). */
    private Bundle call(int what, String text) {
        if (!downloaded() || gaveUp()) return null;
        try {
            Messenger s = connect(20_000);
            if (s == null) { failure = "could not start the chat engine"; return null; }
            CountDownLatch done = new CountDownLatch(1);
            Message m = Message.obtain(null, what);
            synchronized (lock) {
                m.arg1 = pendingSeq = ++seq;
                pendingDone = done;
                pendingResult = null;
            }
            Bundle in = new Bundle();
            if (text != null) in.putString("text", text);
            m.setData(in);
            m.replyTo = inbox;
            s.send(m);
            boolean answered = done.await(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Bundle r;
            synchronized (lock) { r = pendingResult; pendingDone = null; pendingSeq = -1; }
            if (!answered) { failure = "the chat engine took too long"; return null; }
            if (r != null) {
                ready = r.getBoolean("ready");
                failure = r.getString("failure");
                if (ready) prefs.edit().remove(crashKey()).apply(); // it works on this phone
            }
            return r;
        } catch (Exception e) {
            Log.w(TAG, "chat request failed", e);
            failure = "chat request failed (" + ChatEngine.why(e) + ")";
            return null;
        }
    }

    /** Starts the chat engine ahead of time so the first answer comes quicker. Blocking: call off the main thread. */
    void warm() { call(ChatService.WARM, null); }

    /** One short reply in Sweety's voice, or null if the chat brain isn't available. Blocking: call off the main thread. */
    String reply(String text) {
        Bundle r = call(ChatService.REPLY, text);
        return r == null ? null : r.getString("text");
    }

    /** Deletes the downloaded chat brain (and any half download) to free space; Sweety keeps her other brains. */
    long delete() {
        long freed = 0;
        main.post(() -> { if (bound) { try { app.unbindService(conn); } catch (Exception ignored) { } bound = false; } });
        synchronized (lock) { service = null; ready = false; }
        for (File f : new File[]{file(), new File(app.getFilesDir(), "sweety-chat.part")}) {
            if (f.exists()) { freed += f.length(); f.delete(); }
        }
        // LiteRT-LM's prepared-weights cache for the model
        File[] cached = app.getCacheDir().listFiles((d, n) -> n.startsWith("sweety-chat"));
        if (cached != null) for (File f : cached) { freed += f.length(); f.delete(); }
        failure = null;
        Log.i(TAG, "chat brain removed, freed " + (freed >> 20) + " MB");
        return freed;
    }

    /* ------------------------------------------------------------ download */
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
                failure = null;
                prefs.edit().remove(crashKey()).apply(); // a fresh download gets a fresh try
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
}
