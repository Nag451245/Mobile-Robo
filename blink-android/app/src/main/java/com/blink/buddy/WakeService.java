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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * "hey sweety", "hi sweetie", "ok sweaty", "hey sweet tea"… as Whistle may spell it. The name alone counts only
     * at the start of a phrase, so "you're such a sweetie" in a video doesn't wake her.
     */
    private static final String NAME = "(?:swee?t(?:y|ie|ey|i|ee|ty|tie|ies|ys)|sweaty|swetty|swety|suit(?:e)?y|sweet\\s?(?:tea|e|ee|y))";
    static final Pattern WAKE = Pattern.compile(
        "(?:^\\W*(?:a\\W+)?" + NAME + "|\\b(?:hey|hay|hei|hi|hello|ok|okay|oh)\\W+(?:" + NAME + "|sweet\\w*|swee\\w*|swi\\w*ty|sweat\\w*))\\b[,.!?]*",
        Pattern.CASE_INSENSITIVE);

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
        if (!active) { state = "paused: screen is off or locked"; return false; }
        if (busy) { state = "thinking about what it heard…"; return false; }
        if (System.currentTimeMillis() <= holdUntil) { state = "paused while the talk window uses the mic"; return false; }
        if (Assistant.get(this).talking()) { state = "paused while Sweety talks"; return false; }
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
        while (alive) {
            try {
                if (!shouldListen()) {
                    if (ar != null) { closeMic(ar); ar = null; }
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
                }
                if (!inSpeech) state = awaitUntil > 0 ? "listening for your request…" : "listening for \"Hey Sweety\"…";
                int r = ar.read(buf, 0, buf.length);
                if (r <= 0) { closeMic(ar); ar = null; Thread.sleep(500); continue; }
                double sum = 0;
                for (int i = 0; i < r; i++) { float v = buf[i] / 32768f; sum += v * v; }
                double rms = Math.sqrt(sum / r);
                // Phones record voice-recognition audio quite quietly, so the bar is low and follows the room's noise.
                double threshold = Math.max(0.005, noise * 2.6);
                boolean voiced = rms > threshold;

                if (!inSpeech) {
                    noise = noise * 0.97 + Math.min(rms, 0.03) * 0.03;
                    // keep a short pre-roll so the start of "hey" isn't cut off
                    for (int i = 0; i < r; i++) { pre[preN % pre.length] = buf[i] / 32768f; preN++; }
                    if (voiced) {
                        inSpeech = true; silentMs = 0; speechMs = 100; n = 0;
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
                    busy = true;
                    closeMic(ar); ar = null; // free the mic while thinking
                    main.post(() -> heard(audio));
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
    private void heard(float[] audio) {
        Assistant a = Assistant.get(this);
        if (!a.localHearing()) { busy = false; state = "can't hear: Whistle (offline hearing) didn't load"; updateNote("Hey Sweety needs Whistle, Sweety's offline hearing, which didn't load on this phone."); return; }
        // Turn quiet recordings up so Whistle hears them clearly.
        float peak = 0;
        for (float v : audio) peak = Math.max(peak, Math.abs(v));
        if (peak > 0 && peak < .5f) { float g = Math.min(8f, .9f / peak); for (int i = 0; i < audio.length; i++) audio[i] *= g; }
        a.transcribe(audio, text -> {
            String t = text == null ? "" : text.trim();
            if (!t.isEmpty()) lastHeard = t;
            boolean awaiting = awaitUntil > 0 && System.currentTimeMillis() < awaitUntil;
            if (t.isEmpty()) { busy = false; return; }
            if (awaiting) { awaitUntil = 0; command(t); return; }
            Matcher m = WAKE.matcher(t);
            if (!m.find()) { busy = false; return; }
            String rest = t.substring(m.end()).replaceAll("^[\\s,.!?]+", "").replaceAll("[.!]+$", "").trim();
            if (rest.split("\\s+").length >= 2 || rest.length() > 6) { command(rest); return; }
            // Just "Hey Sweety": answer and listen for the command.
            String[] hi = {"Yes?", "I'm here!", "Hi! What's up?", "Yes, sweetie?"};
            String say = hi[(int) (System.nanoTime() % hi.length + hi.length) % hi.length];
            Bus.sweety(say, "wave", "curious");
            updateNote("Listening… " + say);
            int ms = a.voiceOn() ? Sounds.play("hello") : 0;
            main.postDelayed(() -> a.speak(say), ms + 60);
            awaitUntil = System.currentTimeMillis() + 8000;
            busy = false;
        });
    }

    private int handled;

    private void command(String text) {
        final int seq = ++handled;
        main.postDelayed(() -> { if (seq == handled) busy = false; }, 25000); // never stay deaf if a reply goes missing
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
