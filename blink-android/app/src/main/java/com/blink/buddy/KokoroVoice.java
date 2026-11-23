package com.blink.buddy;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sweety's natural voice: Kokoro-82M (Apache-2.0), a small neural text-to-speech model, running fully on the phone
 * with sherpa-onnx. Downloaded once (about 100 MB) from this app's release page; until then the phone's own voice
 * is used. Speaks sentence by sentence so the first words come quickly.
 */
final class KokoroVoice {
    private static final String TAG = "SweetyVoice";
    static final String URL_ZIP = "https://github.com/Nag451245/Mobile-Robo/releases/download/latest/sweety-voice-kokoro.zip";
    /** Kokoro v0.19 speakers that suit Sweety: id, name. */
    static final String[][] SPEAKERS = {{"1", "Bella (warm)"}, {"4", "Sky (soft, young)"}, {"2", "Nicole (gentle)"}, {"3", "Sarah (bright)"}, {"7", "Emma (British)"}};

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService speaker = Executors.newSingleThreadExecutor();
    private volatile OfflineTts tts;
    private volatile boolean downloading, playing, loading;
    private volatile String failure;
    private volatile int generation; // bumps on stop(), so queued sentences are dropped
    private AudioTrack track;

    KokoroVoice(Context app) {
        this.app = app.getApplicationContext();
        this.prefs = this.app.getSharedPreferences("blink", Context.MODE_PRIVATE);
    }

    File dir() { return new File(app.getFilesDir(), "kokoro"); }
    private File model() { File f = new File(dir(), "model.int8.onnx"); return f.exists() ? f : new File(dir(), "model.onnx"); }
    boolean downloaded() { return model().exists() && new File(dir(), "voices.bin").exists() && new File(dir(), "tokens.txt").exists() && new File(dir(), "espeak-ng-data").isDirectory(); }
    boolean downloading() { return downloading; }
    boolean ready() { return tts != null; }
    boolean playing() { return playing; }
    boolean enabled() { return prefs.getBoolean("naturalVoice", true); }
    int speakerId() { try { return Integer.parseInt(prefs.getString("kokoroSpeaker", "1")); } catch (Exception e) { return 1; } }
    void setSpeaker(String id) { prefs.edit().putString("kokoroSpeaker", id).apply(); }

    String status() {
        if (tts != null) return "Natural voice (Kokoro): ready";
        if (failure != null) return "Natural voice: " + failure;
        if (downloading) return "Natural voice: downloading…";
        if (loading) return "Natural voice: starting…";
        return downloaded() ? "Natural voice: downloaded, starting…" : "Natural voice: not downloaded (using the phone's voice)";
    }

    /** Loads the voice model if it's downloaded. Blocking: call off the main thread. */
    synchronized void warm() {
        if (tts != null || !downloaded()) return;
        loading = true;
        long t0 = System.currentTimeMillis();
        try {
            OfflineTtsKokoroModelConfig k = new OfflineTtsKokoroModelConfig();
            k.setModel(model().getAbsolutePath());
            k.setVoices(new File(dir(), "voices.bin").getAbsolutePath());
            k.setTokens(new File(dir(), "tokens.txt").getAbsolutePath());
            k.setDataDir(new File(dir(), "espeak-ng-data").getAbsolutePath());
            k.setLengthScale(1.0f);
            k.setLang("en-us"); // espeak-ng turns words into sounds for this Kokoro model
            OfflineTtsModelConfig m = new OfflineTtsModelConfig();
            m.setKokoro(k);
            m.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            m.setProvider("cpu");
            OfflineTtsConfig c = new OfflineTtsConfig();
            c.setModel(m);
            c.setMaxNumSentences(1);
            tts = new OfflineTts(null, c); // null: the files are on disk, not in the app's assets
            failure = null;
            Log.i(TAG, "natural voice ready in " + (System.currentTimeMillis() - t0) + " ms, " + tts.numSpeakers() + " speakers");
        } catch (Throwable e) {
            Log.w(TAG, "natural voice failed to start", e);
            failure = "could not start (" + e.getClass().getSimpleName() + ")";
            tts = null;
        } finally { loading = false; }
    }

    /** Splits a reply into sentences, so the first one plays while the next is being made. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[.!?…])\\s+")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            if (!out.isEmpty() && out.get(out.size() - 1).length() < 25) out.set(out.size() - 1, out.get(out.size() - 1) + " " + s);
            else out.add(s);
        }
        return out;
    }

    /** Says the text in the natural voice. speed 1 = normal. */
    void speak(String text, float speed) {
        final int gen = ++generation;
        playing = true;
        speaker.execute(() -> {
            try {
                if (tts == null) warm();
                OfflineTts t = tts;
                if (t == null) return;
                for (String s : sentences(text)) {
                    if (gen != generation) return;
                    long t0 = System.currentTimeMillis();
                    GeneratedAudio a = t.generate(s, speakerId(), speed);
                    float[] pcm = a.getSamples();
                    if (pcm == null || pcm.length == 0 || a.getSampleRate() <= 0) { Log.w(TAG, "no audio for: " + s); continue; }
                    Log.i(TAG, "spoke " + (pcm.length * 1000L / a.getSampleRate()) + " ms of audio, made in " + (System.currentTimeMillis() - t0) + " ms");
                    if (gen != generation) return;
                    play(pcm, a.getSampleRate(), gen);
                }
            } catch (Throwable e) {
                Log.w(TAG, "could not speak", e);
            } finally {
                if (gen == generation) playing = false;
            }
        });
    }

    private void play(float[] pcm, int rate, int gen) {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack at = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(Math.max(min, rate)).setTransferMode(AudioTrack.MODE_STREAM).build();
        synchronized (this) { track = at; }
        try {
            at.play();
            // Lip-sync: the avatar gets this sentence's loudness and brightness as it starts playing.
            float[][] env = Bus.envelope(pcm, rate, 30);
            Bus.speech(env[0], env[1], 30);
            int off = 0;
            while (off < pcm.length && gen == generation) {
                int n = Math.min(4096, pcm.length - off);
                int w = at.write(pcm, off, n, AudioTrack.WRITE_BLOCKING);
                if (w <= 0) break;
                off += w;
            }
            if (gen == generation) { at.stop(); Thread.sleep(Math.min(400, pcm.length * 1000L / rate / 10)); }
        } catch (Exception ignored) {
        } finally {
            synchronized (this) { if (track == at) track = null; }
            at.release();
        }
    }

    void stop() {
        Bus.speechStop();
        generation++;
        playing = false;
        synchronized (this) { if (track != null) try { track.pause(); track.flush(); } catch (Exception ignored) { } }
    }

    /* ------------------------------------------------------------ download */
    /** Downloads (resumable) and unpacks the voice. Callbacks arrive on the main thread. */
    void download(ChatBrain.Progress p) {
        if (downloading) return;
        if (downloaded()) { main.post(() -> p.onDone(true, "Already downloaded.")); return; }
        downloading = true;
        failure = null;
        new Thread(() -> {
            File part = new File(app.getFilesDir(), "kokoro.part");
            HttpURLConnection c = null;
            try {
                long have = part.exists() ? part.length() : 0;
                c = (HttpURLConnection) new URL(URL_ZIP).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                if (code == 200) have = 0;
                else if (code != 206) throw new Exception("server answered " + code);
                long total = have + Math.max(0, c.getContentLengthLong()), done = have, lastNote = 0;
                byte[] buf = new byte[256 * 1024];
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, have > 0)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastNote > 400) { lastNote = now; final long d = done, t = total; main.post(() -> p.onProgress(d, t)); }
                    }
                }
                main.post(() -> p.onProgress(-1, -1)); // unpacking
                unzip(part, dir());
                part.delete();
                if (!downloaded()) throw new Exception("the voice files are incomplete");
                downloading = false;
                warm();
                main.post(() -> p.onDone(true, "Natural voice ready."));
            } catch (Exception e) {
                Log.w(TAG, "voice download failed", e);
                downloading = false;
                final String msg = "Download stopped (" + e.getMessage() + "). Tap again to resume.";
                main.post(() -> p.onDone(false, msg));
            } finally { if (c != null) c.disconnect(); }
        }, "sweety-voice-download").start();
    }

    /** Unpacks into a temporary folder first, so a half-unpacked voice is never used. Refuses paths that leave the folder. */
    static void unzip(File zip, File dest) throws Exception {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        deleteAll(tmp);
        String root = tmp.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[256 * 1024];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(tmp, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) throw new Exception("bad file name in the voice download");
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) { int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
            }
        }
        deleteAll(dest);
        if (!tmp.renameTo(dest)) throw new Exception("could not save the voice");
    }

    static void deleteAll(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
