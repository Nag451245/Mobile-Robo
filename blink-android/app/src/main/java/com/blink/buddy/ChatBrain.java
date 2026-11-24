package com.blink.buddy;

import android.app.ActivityManager;
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
 * Sweety's offline chat brains, running on the phone on Google's LiteRT-LM, downloaded once inside the app:
 * <ul>
 *   <li><b>Gemma 4 E2B</b> (Google, Apache-2.0, about 2.6 GB): the big, smart one, for phones with 6 GB+ RAM.
 *       Runs on the GPU when it can (fast), else on the CPU.</li>
 *   <li><b>Qwen3-0.6B</b> (Apache-2.0, 4-bit, about 330 MB): the small one, for any phone.</li>
 * </ul>
 * The big one is used when it's downloaded and works on this phone. Answers stream, so she starts speaking at the
 * first sentence.
 *
 * The engine runs in its own process ({@link ChatService}): if it ever crashes in native code on some phone, only
 * that process ends. A GPU crash moves the big brain to the CPU; after repeated crashes Sweety stops starting that
 * brain and says so, instead of crash-looping.
 */
final class ChatBrain {
    private static final String TAG = "SweetyChat";
    private static final long REPLY_TIMEOUT_MS = 180_000;

    /** One downloadable brain. */
    static final class Model {
        final String id, name, url, fileName, size;
        final long minBytes;
        final boolean big;
        Model(String id, String name, String url, String fileName, long minBytes, String size, boolean big) {
            this.id = id; this.name = name; this.url = url; this.fileName = fileName; this.minBytes = minBytes; this.size = size; this.big = big;
        }
        File file(Context c) { return new File(c.getFilesDir(), fileName); }
        File part(Context c) { return new File(c.getFilesDir(), fileName + ".part"); }
        int maxCrashes() { return big ? 3 : 2; } // the big one gets a try on the GPU, then two on the CPU
    }

    static final Model GEMMA = new Model("gemma4", "Gemma 4 E2B",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        "sweety-gemma4.litertlm", 2_400_000_000L, "2.6 GB", true);
    static final Model QWEN = new Model("qwen", "Qwen3-0.6B",
        "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        "sweety-chat.litertlm", 250_000_000L, "330 MB", false);
    static final Model[] ALL = {GEMMA, QWEN};

    /** The big brain needs a phone with 6 GB of RAM (Android reports a little less than the label). */
    static final long BIG_RAM = 5_200_000_000L;

    interface Progress { void onProgress(long done, long total); void onDone(boolean ok, String message); }
    /** Pieces of the answer as they are written (called on a background thread). */
    interface Pieces { void piece(String text); }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbox;
    private final Object lock = new Object();
    private volatile Model downloading;
    private volatile boolean ready;
    private volatile String failure, backend;

    // connection to the chat process
    private Messenger service;
    private boolean bound;
    private int seq;
    private int pendingSeq = -1;
    private CountDownLatch pendingDone;
    private Bundle pendingResult;
    private Pieces pendingPieces;
    private Model pendingModel;

    ChatBrain(Context app) {
        this.app = app.getApplicationContext();
        this.prefs = this.app.getSharedPreferences("blink", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("sweety-chat-replies");
        t.start();
        inbox = new Messenger(new Handler(t.getLooper(), this::onAnswer));
    }

    /** The small brain's file (kept for {@link ChatService}'s first start). */
    static File modelFile(Context c) { return QWEN.file(c); }

    boolean downloaded(Model m) { File f = m.file(app); return f.exists() && f.length() > m.minBytes; }
    /** True when there's a brain to chat with. */
    boolean downloaded() { return active() != null; }
    boolean downloading() { return downloading != null; }
    boolean downloading(Model m) { return downloading == m; }
    boolean ready() { return ready; }

    /** The brain she chats with: the big one when it's there and works on this phone, else the small one. */
    Model active() {
        for (Model m : ALL) if (downloaded(m) && !gaveUp(m)) return m;
        return null;
    }

    static long ramBytes(Context c) {
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            return mi.totalMem;
        } catch (Exception e) { return 0; }
    }

    /** Whether this phone can run the brain (the big one needs the memory). */
    boolean fits(Model m) { return !m.big || ramBytes(app) >= BIG_RAM; }

    String status() {
        Model m = active();
        if (m == null) {
            for (Model x : ALL) if (downloaded(x) && gaveUp(x)) return "Chat brain (" + x.name + "): stopped after it crashed " + x.maxCrashes() + " times on this phone. Commands and Hey Sweety still work";
            if (downloading != null) return "Chat brain: downloading " + downloading.name + "…";
            return "Chat brain: not downloaded";
        }
        if (ready) return "Chat brain (" + m.name + (backend == null ? "" : ", " + backend) + "): ready";
        if (failure != null) return "Chat brain (" + m.name + "): " + failure;
        return "Chat brain (" + m.name + "): downloaded, starts on first chat";
    }

    /* ------------------------------------------------------------ crash guard */
    /** Crashes are counted per brain and installed version, so an update (maybe with a fixed engine) tries again. */
    private String crashKey(Model m) {
        long v = 0;
        try { v = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime; } catch (Exception ignored) { }
        return "chat_crashes_" + m.id + "_" + v;
    }

    private int crashes(Model m) { return prefs.getInt(crashKey(m), 0); }
    private boolean gaveUp(Model m) { return crashes(m) >= m.maxCrashes(); }
    /** The big brain tries the GPU until it has crashed once; then the CPU. */
    private boolean useGpu(Model m) { return m.big && crashes(m) == 0 && !prefs.getBoolean("chat_gpu_failed_" + m.id, false); }

    private void countCrash(Model m) {
        if (m == null) return;
        int n = crashes(m) + 1;
        prefs.edit().putInt(crashKey(m), n).apply();
        failure = "the chat engine stopped unexpectedly (" + n + " of " + m.maxCrashes() + ")";
        Log.w(TAG, m.name + ": the chat engine process stopped (" + n + " of " + m.maxCrashes() + ")");
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
        Model m;
        synchronized (lock) {
            if (service == null && pendingDone == null) return;
            service = null;
            ready = false;
            waiting = pendingDone;
            m = pendingModel;
            pendingResult = null;
            pendingSeq = -1;
            pendingDone = null;
            pendingPieces = null;
        }
        if (waiting != null) { countCrash(m); waiting.countDown(); }
        main.post(this::unbind);
    }

    private void unbind() { if (bound) { try { app.unbindService(conn); } catch (Exception ignored) { } bound = false; } }

    private boolean onAnswer(Message m) {
        synchronized (lock) {
            if (m.arg1 != pendingSeq || pendingDone == null) return true; // a late answer to a request we gave up on
            if (m.what == ChatService.PIECE) {
                Pieces p = pendingPieces;
                String t = m.getData().getString("text");
                if (p != null && t != null) { try { p.piece(t); } catch (Exception e) { Log.w(TAG, "piece", e); } }
                return true;
            }
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
    private Bundle call(int what, String text, Pieces pieces) {
        Model model = active();
        if (model == null) return null;
        try {
            Messenger s = connect(20_000);
            if (s == null) { failure = "could not start the chat engine"; return null; }
            CountDownLatch done = new CountDownLatch(1);
            Message m = Message.obtain(null, what);
            synchronized (lock) {
                m.arg1 = pendingSeq = ++seq;
                pendingDone = done;
                pendingResult = null;
                pendingPieces = pieces;
                pendingModel = model;
            }
            Bundle in = new Bundle();
            if (text != null) in.putString("text", text);
            in.putString("model", model.file(app).getAbsolutePath());
            in.putBoolean("big", model.big);
            in.putBoolean("gpu", useGpu(model));
            in.putBoolean("stream", pieces != null);
            m.setData(in);
            m.replyTo = inbox;
            s.send(m);
            boolean answered = done.await(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Bundle r;
            synchronized (lock) { r = pendingResult; pendingDone = null; pendingSeq = -1; pendingPieces = null; }
            if (!answered) { failure = "the chat engine took too long"; return null; }
            if (r != null) {
                ready = r.getBoolean("ready");
                failure = r.getString("failure");
                backend = r.getString("backend");
                if (r.getBoolean("gpuFailed")) prefs.edit().putBoolean("chat_gpu_failed_" + model.id, true).apply();
                if (ready) prefs.edit().remove(crashKey(model)).apply(); // it works on this phone
            }
            return r;
        } catch (Exception e) {
            Log.w(TAG, "chat request failed", e);
            failure = "chat request failed (" + ChatEngine.why(e) + ")";
            return null;
        }
    }

    /** Starts the chat engine ahead of time so the first answer comes quicker. Blocking: call off the main thread. */
    void warm() { call(ChatService.WARM, null, null); }

    /** One short reply in Sweety's voice, or null if the chat brain isn't available. Blocking: call off the main thread. */
    String reply(String text) { return reply(text, null); }

    /** As {@link #reply(String)}, handing over the answer piece by piece as it is written (so she can start speaking). */
    String reply(String text, Pieces pieces) {
        Bundle r = call(ChatService.REPLY, text, pieces);
        return r == null ? null : r.getString("text");
    }

    /** Deletes a downloaded brain (and any half download) to free space; Sweety keeps her other brains. */
    long delete(Model m) {
        long freed = 0;
        main.post(this::unbind);
        synchronized (lock) { service = null; ready = false; }
        File[] files = m.big ? new File[]{m.file(app), m.part(app)} : new File[]{m.file(app), m.part(app), new File(app.getFilesDir(), "sweety-chat.part")};
        for (File f : files) if (f.exists()) { freed += f.length(); f.delete(); }
        // LiteRT-LM's prepared-weights cache for the model
        String prefix = m.fileName.replace(".litertlm", "");
        File[] cached = app.getCacheDir().listFiles((d, n) -> n.startsWith(prefix));
        if (cached != null) for (File f : cached) { freed += f.length(); f.delete(); }
        failure = null;
        backend = null;
        Log.i(TAG, m.name + " removed, freed " + (freed >> 20) + " MB");
        return freed;
    }

    /* ------------------------------------------------------------ download */
    /** Downloads a brain (resumable). Callbacks arrive on the main thread. */
    void download(Model m, Progress p) {
        if (downloaded(m)) { main.post(() -> p.onDone(true, "Already downloaded.")); return; }
        if (downloading != null) { main.post(() -> p.onDone(false, "Another brain is downloading. Wait for it to finish.")); return; }
        downloading = m;
        new Thread(() -> {
            File part = m.part(app);
            // the small brain's half download used to have another name
            File old = new File(app.getFilesDir(), "sweety-chat.part");
            if (!m.big && old.exists() && !part.exists()) old.renameTo(part);
            HttpURLConnection c = null;
            try {
                long have = part.exists() ? part.length() : 0;
                c = (HttpURLConnection) new URL(m.url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                if (code == 200) { have = 0; }
                else if (code != 206) throw new Exception("server answered " + code);
                long total = have + Math.max(0, c.getContentLengthLong());
                long needed = Math.max(0, total - have) + 200_000_000L;
                if (app.getFilesDir().getUsableSpace() < needed)
                    throw new Exception("not enough space on the phone: it needs about " + String.format(java.util.Locale.ROOT, "%.1f GB", needed / 1e9) + " free");
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
                if (part.length() < m.minBytes) throw new Exception("download incomplete");
                File dest = m.file(app);
                if (dest.exists()) dest.delete();
                if (!part.renameTo(dest)) throw new Exception("could not save the file");
                failure = null;
                prefs.edit().remove(crashKey(m)).remove("chat_gpu_failed_" + m.id).apply(); // a fresh download gets a fresh try
                downloading = null;
                Log.i(TAG, m.name + " downloaded (" + (dest.length() >> 20) + " MB)");
                main.post(() -> p.onDone(true, m.name + " downloaded."));
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                downloading = null;
                final String msg = "Download stopped (" + e.getMessage() + "). Tap again to resume.";
                main.post(() -> p.onDone(false, msg));
            } finally {
                if (c != null) c.disconnect();
            }
        }, "sweety-download").start();
    }
}
