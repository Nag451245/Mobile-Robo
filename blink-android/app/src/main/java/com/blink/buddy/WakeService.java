package com.blink.buddy;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.Arrays;
import java.util.Locale;

/**
 * "Hey Sweety": listens for the wake phrase while the screen is on and unlocked, fully on the phone with Whistle.
 * Nothing is recorded or sent anywhere; when the screen goes off or locks, the microphone is closed.
 */
public class WakeService extends Service {
    private static final String TAG = "SweetyWake";
    private static final String CHANNEL = "sweety_wake";
    private static final String ACTION_STOP = "com.blink.buddy.WAKE_STOP";
    private static final int RATE = 16000, FRAME = RATE / 10; // 100 ms frames
    private static final int NOTE_ID = 7;

    private static volatile boolean running;
    private static volatile long holdUntil;

    /** What the listener is doing right now, shown in the app so you can see it working. */
    static volatile String state = "off";
    static volatile String lastHeard = "";

    static boolean enabled(Context c) { return c.getSharedPreferences("blink", MODE_PRIVATE).getBoolean("wake", false); }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("blink", MODE_PRIVATE).edit().putBoolean("wake", on).apply();
        if (on) start(c); else c.stopService(new Intent(c, WakeService.class));
    }

    /** Starts the listener if the user turned it on (needs the microphone permission already granted). */
    static void start(Context c) {
        if (!enabled(c) || c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try { c.startForegroundService(new Intent(c, WakeService.class)); }
        catch (Exception e) { Log.w(TAG, "could not start", e); }
    }

    /** Another part of Sweety needs the microphone (talk window): step aside for a while. Returns true if we were listening. */
    static boolean hold(long ms) { holdUntil = System.currentTimeMillis() + ms; return running; }
    static void release() { holdUntil = 0; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean alive;
    private volatile boolean active;        // screen on and unlocked
    private volatile boolean busy;          // a clip is being transcribed / handled
    private volatile long awaitUntil;       // after "Hey Sweety" alone, the next phrase is the command
    private Thread loop;
    private PowerManager pm;
    private KeyguardManager km;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshActive(); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("blink", MODE_PRIVATE).edit().putBoolean("wake", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = note("Say \"Hey Sweety\" while your screen is on");
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTE_ID, n);
        } catch (Exception e) {
            // Android only lets the microphone service start while Sweety is on screen; try again next time the app opens.
            Log.w(TAG, "startForeground refused", e);
            state = "Android blocked the microphone service: " + e.getClass().getSimpleName();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!alive) {
            alive = true;
            running = true;
            refreshActive();
            loop = new Thread(this::listenLoop, "sweety-wake");
            loop.start();
            Assistant.get(this).warmChat();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        running = false;
        if (!state.startsWith("Android blocked")) state = "off";
        try { unregisterReceiver(screen); } catch (Exception ignored) { }
        if (loop != null) loop.interrupt();
        super.onDestroy();
    }

    private void refreshActive() {
        boolean on = pm != null && pm.isInteractive() && (km == null || !km.isKeyguardLocked());
        active = on;
    }

    private Notification note(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Hey Sweety", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 10, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 11, new Intent(this, WakeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_blink)
            .setContentTitle("Sweety is listening for \"Hey Sweety\"")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
            .build();
    }

    private void updateNote(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && alive) nm.notify(NOTE_ID, note(text));
    }

    private boolean shouldListen() {
        if (!alive) return false;
        refreshActive(); // cheap, and some phones send the screen broadcasts late
        if (!active) { state = "paused: screen is off or locked"; return false; }
        if (busy) { state = "thinking about what it heard…"; return false; }
        if (System.currentTimeMillis() <= holdUntil) { state = "paused while the talk window uses the mic"; return false; }
        // Don't listen to herself, except while recording the request after "Hey Sweety" (her chirp plays then).
        if (!capturing && Assistant.get(this).talking()) { state = "paused while Sweety talks"; return false; }
        return true;
    }

    /* ------------------------------------------------------------ microphone loop */
    private void listenLoop() {
        AudioRecord ar = null;
        short[] buf = new short[FRAME];
        float[] clip = new float[RATE * 7];
        int n = 0, silentMs = 0, speechMs = 0;
        boolean inSpeech = false;
        double noise = 0.003;
        float[] pre = new float[RATE * 3 / 10]; // 300 ms before the voice starts
        int preN = 0;
        long quietSince = 0;
        long statSince = System.currentTimeMillis(), zeroSince = 0;
        double maxRms = 0;
        float[] fbuf = new float[FRAME];
        // One breath ("Hey Sweety, what time is it?"): after the wake word the mic stays open a moment, and if you
        // keep talking, the rest is kept and handed over, instead of being lost while the card opens.
        float[] recent = new float[RATE * 3 / 10]; // the last 300 ms: a request can start before the detector fires
        long recentN = 0;
        float[] follow = new float[RATE * 8];
        int followN = 0, followSpeechMs = 0, followSilentMs = 0;
        long followStart = 0;
        boolean following = false;
        boolean cardFirst = false; // the card is already up, showing "Listening…", while this records the request
        boolean kws = wake.load(this);
        Log.i(TAG, kws ? "listening with the wake-word detector (" + wake.kind() + ")" : "wake-word detector unavailable (" + wake.failure + "), using Whistle");
        while (alive) {
            try {
                if (reloadDetectors) {
                    reloadDetectors = false;
                    if (ar != null) { closeMic(ar); ar = null; }
                    wake.unload();
                    kws = wake.load(this);
                    Log.i(TAG, "detectors reloaded: " + wake.kind());
                }
                if (!shouldListen()) {
                    if (ar != null) { Log.i(TAG, "mic closed: " + state); closeMic(ar); ar = null; wake.reset(); following = false; capturing = false; }
                    inSpeech = false; n = 0;
                    quietSince = System.currentTimeMillis();
                    Thread.sleep(250);
                    continue;
                }
                // Skip the tail of Sweety's own voice after she stops talking.
                if (System.currentTimeMillis() - quietSince < 500) { Thread.sleep(100); continue; }
                if (ar == null) {
                    ar = openMic();
                    if (ar == null) { state = "microphone busy or blocked, retrying…"; Thread.sleep(3000); continue; }
                    Log.i(TAG, "mic open" + (kws && awaitUntil == 0 ? ", listening for the wake word" : ", listening for a request"));
                }
                if (!inSpeech) state = (awaitUntil > 0 ? "listening for your request…" : "listening for \"Hey Sweety\"…") + levelText();
                int r = ar.read(buf, 0, buf.length);
                if (r <= 0) { closeMic(ar); ar = null; Thread.sleep(500); continue; }
                feed(buf, r);
                double sum = 0;
                for (int i = 0; i < r; i++) { float v = buf[i] / 32768f; sum += v * v; }
                double rms = Math.sqrt(sum / r);
                long now = System.currentTimeMillis();
                if (sum > 0) zeroSince = 0; else if (zeroSince == 0) zeroSince = now;
                maxRms = Math.max(maxRms, rms);
                if (now - statSince >= 3000) {
                    level = maxRms;
                    float wp = wake.takePeak();
                    Log.i(TAG, String.format(Locale.ROOT, "mic: loudest %.4f, room noise %.4f, bar %.4f", maxRms, noise, Math.max(0.005, noise * 2.6))
                        + (wp >= 0 ? String.format(Locale.ROOT, ", Hey Sweety score up to %.2f", wp) : ""));
                    statSince = now; maxRms = 0;
                }
                if (zeroSince > 0 && now - zeroSince > 3000) {
                    // Android hands an app pure silence when it blocks the mic (another app recording, or a background limit).
                    state = "the microphone gives only silence: another app may be using it, or the phone blocks it in the background";
                    continue;
                }
                // The wake-word detector listens to everything and only reacts to "Hey Sweety".
                if (kws && awaitUntil == 0) {
                    for (int i = 0; i < r; i++) { fbuf[i] = buf[i] / 32768f; recent[(int) (recentN++ % recent.length)] = fbuf[i]; }
                    double bar = Math.max(0.006, noise * 2.6);
                    if (following) {
                        if (cardFirst && cardGone) { // the user closed the card: stop, and go back to waiting for "Hey Sweety"
                            following = false; capturing = false; cardFirst = false; wake.reset();
                            Log.i(TAG, "card closed: stopped listening for the request");
                            continue;
                        }
                        boolean chirp = Sounds.playing(); // her own short wake-up chirp doesn't count as the user talking
                        int k = Math.min(r, follow.length - followN);
                        System.arraycopy(fbuf, 0, follow, followN, k);
                        followN += k;
                        if (rms > bar && !chirp) { followSpeechMs += 100; followSilentMs = 0; } else followSilentMs += 100;
                        boolean talking = followSpeechMs >= 300; // a real request, not the tail of "Sweety"
                        boolean done = talking ? followSilentMs >= 800 || followN >= follow.length
                                               : now - followStart >= (cardFirst ? 5000 : 500);
                        if (done) {
                            following = false; capturing = false;
                            busy = true;
                            closeMic(ar); ar = null; wake.reset();
                            final float[] request = talking ? java.util.Arrays.copyOf(follow, followN) : null;
                            final boolean toCard = cardFirst;
                            cardFirst = false;
                            if (request != null) Log.i(TAG, "one breath: kept " + (request.length * 1000 / RATE) + " ms of your request");
                            if (toCard) main.post(() -> toCard(request));
                            else main.post(() -> onWakeWord(request));
                        }
                        continue;
                    }
                    if (rms < bar) noise = noise * 0.97 + Math.min(rms, 0.03) * 0.03; // the room's quiet level
                    String word = wake.accept(fbuf, r);
                    if (word != null) {
                        Log.i(TAG, "wake word: " + word);
                        lastHeard = word.toLowerCase(Locale.ROOT);
                        following = true; capturing = true; followStart = now; followSpeechMs = 0; followSilentMs = 0;
                        // Like a real assistant: the card pops up right away while this same microphone keeps listening.
                        cardFirst = android.provider.Settings.canDrawOverlays(this);
                        if (cardFirst) { cardGone = false; main.post(this::openCardListening); }
                        int keep = (int) Math.min(recentN, recent.length);
                        for (int i = 0; i < keep; i++) follow[i] = recent[(int) ((recentN - keep + i) % recent.length)];
                        followN = keep;
                    }
                    continue;
                }
                // Phones record voice-recognition audio quite quietly, so the bar is low and follows the room's noise.
                double threshold = Math.max(0.005, noise * 2.6);
                boolean voiced = rms > threshold;

                if (!inSpeech) {
                    noise = noise * 0.97 + Math.min(rms, 0.03) * 0.03;
                    // keep a short pre-roll so the start of "hey" isn't cut off
                    for (int i = 0; i < r; i++) { pre[preN % pre.length] = buf[i] / 32768f; preN++; }
                    if (voiced) {
                        inSpeech = true; silentMs = 0; speechMs = 100; n = 0; speechStart = now;
                        int have = Math.min(preN, pre.length);
                        for (int i = 0; i < have; i++) clip[n++] = pre[(preN - have + i) % pre.length];
                        preN = 0;
                    } else if (awaitUntil > 0 && System.currentTimeMillis() > awaitUntil) {
                        awaitUntil = 0;
                        main.post(() -> { Bus.sweety(null, "shrug", "neutral"); updateNote("Say \"Hey Sweety\" while your screen is on"); });
                    }
                    continue;
                }
                for (int i = 0; i < r && n < clip.length; i++) clip[n++] = buf[i] / 32768f;
                if (voiced) { silentMs = 0; speechMs += 100; } else silentMs += 100;
                boolean ended = silentMs >= 700 || n >= clip.length;
                if (!ended) continue;
                inSpeech = false;
                if (speechMs >= 300) {
                    final float[] audio = Arrays.copyOf(clip, n);
                    Log.i(TAG, "heard a sound for " + (n * 1000 / RATE) + " ms, transcribing");
                    busy = true;
                    closeMic(ar); ar = null; // free the mic while thinking
                    final long began = speechStart;
                    main.post(() -> heard(audio, began));
                }
                n = 0;
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.w(TAG, "mic loop", e);
                if (ar != null) { closeMic(ar); ar = null; }
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }
        if (ar != null) closeMic(ar);
    }

    /*
     * Test hook for the CI emulator, debug builds only: a 16 kHz 16-bit mono recording placed in the app's private
     * files as mic-test.pcm (only adb run-as can put it there) replaces what the microphone hears, then is deleted.
     * The real microphone is still opened and read, so the background-recording path is tested too.
     */
    private short[] feed;
    private int feedPos;
    private long feedCheck;

    private void feed(short[] buf, int r) {
        if (feed == null) {
            long now = System.currentTimeMillis();
            if (now - feedCheck < 500) return;
            feedCheck = now;
            if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
            java.io.File f = new java.io.File(getFilesDir(), "mic-test.pcm");
            if (!f.exists()) return;
            try {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                short[] s = new short[b.length / 2];
                java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
                feed = s; feedPos = 0;
                Log.i(TAG, "test recording: " + (s.length * 1000 / RATE) + " ms");
            } catch (Exception e) { Log.w(TAG, "test recording unreadable", e); }
            f.delete();
            if (feed == null) return;
        }
        for (int i = 0; i < r; i++) buf[i] = feedPos < feed.length ? feed[feedPos++] : 0;
        if (feedPos >= feed.length) feed = null;
    }

    private AudioRecord openMic() {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) { ar.release(); return null; }
            ar.startRecording();
            return ar;
        } catch (SecurityException e) {
            Log.w(TAG, "no mic permission", e);
            main.post(this::stopSelf);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "mic busy", e);
            return null;
        }
    }

    private static void closeMic(AudioRecord ar) {
        try { ar.stop(); } catch (Exception ignored) { }
        try { ar.release(); } catch (Exception ignored) { }
    }

    /* ------------------------------------------------------------ what was said */
    private long speechStart;

    /** began: when the voice started, so a request said within the 8 s after "Yes?" counts however long Whistle takes. */
    private void heard(float[] audio, long began) {
        Assistant a = Assistant.get(this);
        if (!a.localHearing()) { busy = false; state = "can't hear: Whistle (offline hearing) didn't load"; updateNote("Hey Sweety needs Whistle, Sweety's offline hearing, which didn't load on this phone."); return; }
        // Turn quiet recordings up so Whistle hears them clearly.
        float peak = 0;
        for (float v : audio) peak = Math.max(peak, Math.abs(v));
        if (peak > 0 && peak < .5f) { float g = Math.min(8f, .9f / peak); for (int i = 0; i < audio.length; i++) audio[i] *= g; }
        a.transcribe(audio, text -> {
            String t = text == null ? "" : text.trim();
            if (!t.isEmpty()) lastHeard = t;
            Log.i(TAG, "Whistle heard: \"" + t + "\"");
            boolean awaiting = awaitUntil > 0 && began < awaitUntil;
            if (t.isEmpty()) { busy = false; return; }
            if (awaiting) { awaitUntil = 0; command(t); return; }
            String rest = WakePhrase.find(t);
            if (rest == null) { busy = false; updateNote("Heard “" + shorten(t) + "”. Start with “Hey Sweety” to wake me."); return; }
            Log.i(TAG, "wake phrase found, rest: \"" + rest + "\"");
            if (rest.split("\\s+").length >= 2 || rest.length() > 6) { command(rest); return; }
            // Just "Hey Sweety": answer and listen for the command.
            String say = DayTime.yesLine(new java.util.Random());
            Bus.sweety(say, "wave", "curious");
            updateNote("Listening… " + say);
            int ms = a.voiceOn() ? Sounds.wake(DayTime.hour()) : 0;
            main.postDelayed(() -> a.speak(say), ms + 60);
            awaitUntil = System.currentTimeMillis() + ms + 9000; // her "Yes?" plus 8 s to ask
            busy = false;
        });
    }

    private final WakeWord wake = new WakeWord();
    /** Set when Voice Match changes: the listener reloads its detectors. */
    static volatile boolean reloadDetectors;
    /**
     * "Hey Sweety" heard. Like Gemini's overlay, a small Sweety card pops up over whatever app is open and listens with
     * the phone's own speech recogniser. Android lets Sweety open it from the background because she may draw over
     * other apps; without that permission she answers "Yes?" and listens for the request herself.
     */
    private void onWakeWord(float[] request) {
        Assistant a = Assistant.get(this);
        Bus.sweety(null, "wave", "curious");
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                if (a.voiceOn()) Sounds.wake(DayTime.hour()); // the card waits for it to finish before listening
                Intent card = new Intent(this, TalkActivity.class).putExtra(TalkActivity.EXTRA_WOKE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                String kept = request != null ? saveRequest(request) : null;
                if (kept != null) card.putExtra(TalkActivity.EXTRA_REQUEST_AUDIO, kept); // she works it out instead of listening again
                startActivity(card);
                Log.i(TAG, "opened the talk card");
                updateNote("Listening… say what you need.");
                busy = false;
                return;
            } catch (Exception e) { Log.w(TAG, "could not open the talk card", e); }
        }
        if (request != null && a.localHearing()) { // said in one breath: Whistle works out the rest
            heardRequest(request);
            return;
        }
        String say = DayTime.yesLine(new java.util.Random());
        Bus.sweety(say, "wave", "curious");
        updateNote("Listening… " + say);
        int ms = a.voiceOn() ? Sounds.wake(DayTime.hour()) : 0;
        main.postDelayed(() -> a.speak(say), ms + 60);
        awaitUntil = System.currentTimeMillis() + ms + 9000;
        busy = false;
    }

    /** Without the card: the request kept from "Hey Sweety, ..." goes to Whistle, then straight to the brain. */
    private void heardRequest(float[] request) {
        Assistant a = Assistant.get(this);
        float peak = 0;
        for (float v : request) peak = Math.max(peak, Math.abs(v));
        if (peak > 0 && peak < .5f) { float g = Math.min(8f, .9f / peak); for (int i = 0; i < request.length; i++) request[i] *= g; }
        a.transcribe(request, text -> {
            String t = text == null ? "" : text.trim();
            Log.i(TAG, "Whistle heard: \"" + t + "\"");
            if (t.isEmpty()) { onWakeWord(null); return; } // nothing usable: answer "Yes?" and listen
            lastHeard = t;
            String rest = WakePhrase.find(t); // the kept audio can start with the end of "Hey Sweety"
            command(rest != null && !rest.isEmpty() ? rest : t);
        });
    }

    /** Set by the card when it's closed, so the request isn't recorded or delivered any more. */
    static volatile boolean cardGone;
    /** Recording the request after "Hey Sweety": the mic stays open even while her chirp plays. */
    private volatile boolean capturing;

    /** "Hey Sweety" heard: the card pops up showing "Listening…" (with a short chirp) while the mic keeps recording. */
    private void openCardListening() {
        Assistant a = Assistant.get(this);
        Bus.sweety(null, "wave", "curious");
        try {
            if (a.voiceOn()) Sounds.wakeShort(DayTime.hour());
            startActivity(new Intent(this, TalkActivity.class).putExtra(TalkActivity.EXTRA_WOKE, true)
                .putExtra(TalkActivity.EXTRA_SERVICE_LISTENS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            Log.i(TAG, "opened the talk card");
            updateNote("Listening… say what you need.");
        } catch (Exception e) { Log.w(TAG, "could not open the talk card", e); cardGone = true; }
    }

    /** The request recorded after "Hey Sweety" goes to the card (or nothing was said). */
    private void toCard(float[] request) {
        busy = false;
        if (cardGone) return;
        Intent i = new Intent(this, TalkActivity.class).putExtra(TalkActivity.EXTRA_WOKE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        String kept = request != null ? saveRequest(request) : null;
        if (kept != null) i.putExtra(TalkActivity.EXTRA_REQUEST_AUDIO, kept);
        else i.putExtra(TalkActivity.EXTRA_NOTHING_HEARD, true);
        try { startActivity(i); } catch (Exception e) { Log.w(TAG, "could not reach the talk card", e); }
    }

    /** The kept request as 16-bit PCM in the app's cache, for the talk card. */
    private String saveRequest(float[] pcm) {
        try {
            java.io.File f = new java.io.File(getCacheDir(), "request.pcm");
            byte[] b = new byte[pcm.length * 2];
            for (int k = 0; k < pcm.length; k++) { int v = Math.max(-32768, Math.min(32767, Math.round(pcm[k] * 32767))); b[2 * k] = (byte) v; b[2 * k + 1] = (byte) (v >> 8); }
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(b); }
            return f.getAbsolutePath();
        } catch (Exception e) { Log.w(TAG, "couldn't keep the request", e); return null; }
    }

    private static String shorten(String t) { return t.length() > 60 ? t.substring(0, 60) + "…" : t; }

    /** Loudest mic level in the last few seconds (-1 before the first reading). */
    static volatile double level = -1;

    private static String levelText() {
        double l = level;
        if (l < 0) return "";
        return l < 0.002 ? " (mic very quiet)" : String.format(Locale.ROOT, " (mic level %.3f)", l);
    }

    private int handled;

    private void command(String text) {
        final int seq = ++handled;
        main.postDelayed(() -> { if (seq == handled) busy = false; }, 25000); // never stay deaf if a reply goes missing
        Log.i(TAG, "command: \"" + text + "\"");
        updateNote("You said: \"" + text + "\"");
        Bus.sweety(null, "nod", "curious");
        Assistant.get(this).handle(text, new WakeHost(text));
    }

    /** Replies go to the floating Sweety, the notification and her voice. */
    private final class WakeHost implements Assistant.Host {
        private final String text;
        WakeHost(String text) { this.text = text; }

        @Override public void say(String reply) {
            Bus.sweety(reply, null, null);
            updateNote(reply);
            busy = false;
        }
        @Override public void cue(String action, String emotion) { Bus.sweety(null, action, emotion); }
        @Override public Activity activity() { return null; }

        @Override public void pickContact(String forName, Assistant.NumberCallback cb) {
            // Picking a contact needs a screen: hand this request to the talk window, which shows the picker.
            busy = false;
            try {
                startActivity(new Intent(WakeService.this, TalkActivity.class)
                    .putExtra(TalkActivity.EXTRA_ASK, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            } catch (Exception e) {
                updateNote("Open Sweety once to pick " + forName + " from your contacts.");
            }
        }
    }

}
