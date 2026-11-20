package com.blink.buddy;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sweety the assistant: understands a sentence (Needle 2 first, rules as backup), performs the action with
 * ordinary Android intents, and answers out loud. Calls and messages always open the dialer or messaging app
 * so the user presses the final button.
 */
final class Assistant {
    interface Host {
        void say(String text);
        void cue(String action, String emotion);
        /** Show the contact picker; call back with the chosen number (or null). */
        void pickContact(String forName, NumberCallback cb);
        Activity activity();
    }

    interface NumberCallback { void onNumber(String number); }

    private static Assistant instance;

    static synchronized Assistant get(Context c) {
        if (instance == null) instance = new Assistant(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final NeedleBrain needle = new NeedleBrain();
    private final RuleBrain rules = new RuleBrain();
    private final Random rnd = new Random();
    private TextToSpeech tts;
    private boolean ttsReady;

    private Assistant(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("blink", Context.MODE_PRIVATE);
        tts = new TextToSpeech(app, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) { tts.setPitch(1.55f); tts.setSpeechRate(1.05f); }
        });
        worker.execute(() -> needle.ensure(app));
    }

    String brainStatus() { return needle.status; }

    /** True when Whistle is loaded, so Sweety can hear fully offline. */
    boolean localHearing() { return needle.speechReady(); }

    interface TextCallback { void onText(String text); }

    /** Offline speech to text with Whistle, on the brain's worker thread; the callback runs on the main thread. */
    void transcribe(float[] pcm, TextCallback cb) {
        worker.execute(() -> {
            needle.ensure(app);
            String text = needle.transcribe(pcm);
            main.post(() -> cb.onText(text));
        });
    }

    boolean voiceOn() { return prefs.getBoolean("voice", true); }
    void setVoiceOn(boolean on) { prefs.edit().putBoolean("voice", on).apply(); if (!on) stopTalking(); }

    void speak(String text) {
        if (!voiceOn() || !ttsReady || text == null) return;
        tts.speak(text.replaceAll("[^\\p{L}\\p{N}\\p{P}\\s]", ""), TextToSpeech.QUEUE_FLUSH, null, "sweety");
    }

    void stopTalking() { if (tts != null) tts.stop(); }

    /** Understand and act on one sentence. Callbacks to the host happen on the main thread. */
    void handle(String text, Host host) {
        if (text == null || text.trim().isEmpty()) return;
        host.cue("nod", "curious");
        worker.execute(() -> {
            Call call = null;
            Call ruled = rules.parse(text);
            needle.ensure(app);
            if (needle.textReady()) {
                try { call = needle.parse(text); } catch (Throwable ignored) { }
            }
            // Trust Needle when it is confident; otherwise fall back to the rules.
            if (call == null || call.confidence < .45f || !known(call.name)) call = ruled;
            else if (ruled != null && ("ask".equals(ruled.name) || "read_notes".equals(ruled.name))) call = ruled;
            final Call fc = call;
            main.post(() -> run(fc, text, host));
        });
    }

    private static boolean known(String n) {
        switch (n) {
            case "set_alarm": case "set_timer": case "call_contact": case "send_message": case "flashlight":
            case "media_control": case "phone_status": case "take_note": case "sweety_fun": return true;
            default: return false;
        }
    }

    private void reply(Host host, String text, String action, String emotion) {
        host.say(text);
        host.cue(action, emotion);
        speak(text);
    }

    private void run(Call c, String text, Host host) {
        if (c == null) { chat(text, host); return; }
        JSONObject a = c.args;
        try {
            switch (c.name) {
                case "ask": reply(host, a.optString("text"), "shrug", "curious"); break;
                case "set_alarm": alarm(a, host); break;
                case "set_timer": timer(a, host); break;
                case "call_contact": withNumber(a, host, number -> {
                    open(host, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
                    reply(host, "Calling " + who(a, number) + ". Just tap the call button.", "wave", "happy");
                }); break;
                case "send_message": withNumber(a, host, number -> message(a, number, host)); break;
                case "flashlight": torch(a.optBoolean("on", true), host); break;
                case "media_control": media(a.optString("action", "play"), host); break;
                case "phone_status": status(a.optString("item", "time"), host); break;
                case "take_note": note(a.optString("text"), host); break;
                case "read_notes": readNotes(host); break;
                case "sweety_fun": fun(a, host); break;
                default: chat(text, host);
            }
        } catch (Exception e) {
            reply(host, "Oops, I couldn't do that one. Can you say it another way?", "shrug", "sad");
        }
    }

    /* ------------------------------------------------------------ actions */
    private void alarm(JSONObject a, Host host) {
        if (!a.has("hour")) { reply(host, "What time should I set the alarm for?", "shrug", "curious"); return; }
        int h = clamp(a.optInt("hour", 7), 0, 23), m = clamp(a.optInt("minute", 0), 0, 59);
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        String label = a.optString("label", "");
        if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        if (open(host, i)) reply(host, "Alarm set for " + clock(h, m) + ".", "jump", "happy");
        else reply(host, "I couldn't find a clock app to set the alarm.", "shrug", "sad");
    }

    private void timer(JSONObject a, Host host) {
        double minutes = a.optDouble("minutes", 0);
        int seconds = (int) Math.round(minutes * 60) + a.optInt("seconds", 0);
        if (seconds <= 0) { reply(host, "For how long? Say something like: timer for 5 minutes.", "shrug", "curious"); return; }
        String label = a.optString("label", "");
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        String span = span(seconds);
        if (!open(host, i)) { reply(host, "I couldn't find a clock app for the timer.", "shrug", "sad"); return; }
        if (!label.isEmpty()) reply(host, "Okay! I'll remind you to " + label + " in " + span + ".", "nod", "happy");
        else reply(host, "Timer set for " + span + ".", "nod", "happy");
    }

    private void withNumber(JSONObject a, Host host, NumberCallback then) {
        String number = a.optString("number", "").replaceAll("[^+\\d]", "");
        if (number.length() >= 3) { then.onNumber(number); return; }
        String name = a.optString("name", "").trim();
        if (name.isEmpty()) { reply(host, "Who should I contact?", "shrug", "curious"); return; }
        String saved = prefs.getString("contact_" + name.toLowerCase(Locale.ROOT), null);
        if (saved != null) { then.onNumber(saved); return; }
        reply(host, "Which one is " + name + "? Pick them once and I'll remember.", "nod", "curious");
        host.pickContact(name, num -> {
            if (num == null) { reply(host, "Okay, never mind.", "shrug", "neutral"); return; }
            prefs.edit().putString("contact_" + name.toLowerCase(Locale.ROOT), num).apply();
            then.onNumber(num);
        });
    }

    private void message(JSONObject a, String number, Host host) {
        String text = a.optString("text", "");
        boolean whatsapp = "whatsapp".equals(a.optString("app", "sms"));
        Intent i;
        if (whatsapp) {
            i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number)).setPackage("com.whatsapp").putExtra("sms_body", text);
            if (!open(host, i)) {
                String digits = number.replaceAll("[^\\d]", "");
                if (digits.length() == 10) digits = "91" + digits;
                open(host, new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(text))));
            }
        } else {
            i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text);
            open(host, i);
        }
        reply(host, "Here's your message to " + who(a, number) + ". Tap send when you're ready.", "wave", "happy");
    }

    private void torch(boolean on, Host host) {
        try {
            CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) { cm.setTorchMode(id, on); reply(host, on ? "Torch on!" : "Torch off.", on ? "jump" : "nod", "happy"); return; }
            }
            reply(host, "This phone doesn't seem to have a flashlight.", "shrug", "sad");
        } catch (Exception e) { reply(host, "I couldn't switch the torch. Is the camera busy?", "shrug", "sad"); }
    }

    private void media(String action, Host host) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        switch (action) {
            case "volume_up": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); reply(host, "Louder!", "dance", "happy"); return;
            case "volume_down": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); reply(host, "A bit quieter.", "nod", "happy"); return;
            default: break;
        }
        int key = "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT : "previous".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : "pause".equals(action) ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        String said = "next".equals(action) ? "Next song!" : "previous".equals(action) ? "Going back one." : "pause".equals(action) ? "Paused." : "Playing your music!";
        host.say(said);
        host.cue("play".equals(action) ? "dance" : "nod", "happy");
        if (!"play".equals(action)) speak(said);
    }

    private void status(String item, Host host) {
        if ("battery".equals(item)) {
            BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean charging = bm.isCharging();
            reply(host, "Your battery is at " + pct + " percent" + (charging ? " and charging." : "."), pct < 20 ? "stretch" : "nod", pct < 20 ? "sleepy" : "happy");
        } else if ("date".equals(item)) {
            reply(host, "Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(new Date()) + ".", "nod", "happy");
        } else {
            Calendar c = Calendar.getInstance();
            reply(host, "It's " + clock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + ".", "nod", "happy");
        }
    }

    private void note(String text, Host host) {
        if (text == null || text.trim().isEmpty()) { reply(host, "What should I write down?", "shrug", "curious"); return; }
        try {
            JSONArray notes = new JSONArray(prefs.getString("notes", "[]"));
            notes.put(text.trim());
            while (notes.length() > 50) notes.remove(0);
            prefs.edit().putString("notes", notes.toString()).apply();
            reply(host, "Got it, I wrote that down.", "nod", "happy");
        } catch (Exception e) { reply(host, "I couldn't save that note.", "shrug", "sad"); }
    }

    private void readNotes(Host host) {
        try {
            JSONArray notes = new JSONArray(prefs.getString("notes", "[]"));
            if (notes.length() == 0) { reply(host, "You don't have any notes yet.", "shrug", "neutral"); return; }
            StringBuilder sb = new StringBuilder("Your notes: ");
            for (int i = Math.max(0, notes.length() - 5); i < notes.length(); i++) sb.append(notes.getString(i)).append(i < notes.length() - 1 ? ". " : ".");
            reply(host, sb.toString(), "nod", "happy");
        } catch (Exception e) { reply(host, "I couldn't read your notes.", "shrug", "sad"); }
    }

    private static final String[] JOKES = {
        "Why did the robot go on vacation? It needed to recharge.",
        "I would tell you a joke about RAM, but I forgot it.",
        "Why was the phone wearing glasses? It lost its contacts.",
        "I tried to catch some fog this morning. I mist.",
        "What do you call a sleepy robot? A nap-top."
    };

    private void fun(JSONObject a, Host host) {
        String act = a.optString("action", "wave");
        switch (act) {
            case "dance": reply(host, "Dance party!", "dance", "happy"); break;
            case "jump": reply(host, "Boing!", "jump", "surprised"); break;
            case "spin": reply(host, "Wheee!", "spin", "dizzy"); break;
            case "joke": reply(host, JOKES[rnd.nextInt(JOKES.length)], "laugh", "laugh"); break;
            case "sleep": reply(host, "Nap time. Tap me to wake me up.", "sleep", "sleep"); break;
            case "outfit": {
                String want = a.optString("outfit", "").toLowerCase(Locale.ROOT);
                Skin pick = null;
                for (Skin s : Skin.ALL) if (!want.isEmpty() && (s.id.contains(want) || s.name.toLowerCase(Locale.ROOT).contains(want))) pick = s;
                if (pick == null) { String cur = prefs.getString("skin", "bubblegum"); do { pick = Skin.ALL[rnd.nextInt(Skin.ALL.length)]; } while (pick.id.equals(cur)); }
                reply(host, "Ta-da! " + pick.name + " time.", "outfit:" + pick.id, "happy");
                break;
            }
            default: reply(host, "Hi there!", "wave", "happy");
        }
    }

    private void chat(String text, Host host) {
        String s = text.toLowerCase(Locale.ROOT);
        if (s.matches(".*\\b(hi|hello|hey|good morning|good evening)\\b.*")) reply(host, "Hi! I'm Sweety. How can I help?", "wave", "happy");
        else if (s.contains("your name") || s.contains("who are you")) reply(host, "I'm Sweety, your little robot helper!", "wave", "love");
        else if (s.contains("how are you")) reply(host, "I'm feeling sweet as candy! How about you?", "dance", "happy");
        else if (s.contains("thank")) reply(host, "Anytime! That's what I'm here for.", "nod", "love");
        else if (s.contains("love you")) reply(host, "Aww, I love you too!", "jump", "love");
        else if (s.contains("what can you do") || s.contains("help")) reply(host, "I can set alarms, timers and reminders, call or text people, switch the torch, control your music, tell the time, date or battery, take notes, and dance!", "wave", "happy");
        else reply(host, "Hmm, I don't know that one yet. Try: set an alarm for 7 am, or remind me in 10 minutes to drink water.", "shrug", "curious");
    }

    /* ------------------------------------------------------------ helpers */
    private boolean open(Host host, Intent i) {
        Activity act = host.activity();
        try {
            if (act != null) act.startActivity(i);
            else app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) { return false; }
    }

    private static String who(JSONObject a, String number) { String n = a.optString("name", ""); return n.isEmpty() ? number : n; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String clock(int h, int m) {
        int hh = h % 12 == 0 ? 12 : h % 12;
        return hh + (m == 0 ? "" : String.format(Locale.ROOT, ":%02d", m)) + (h < 12 ? " AM" : " PM");
    }

    private static String span(int seconds) {
        if (seconds < 60) return seconds + " seconds";
        int min = seconds / 60, h = min / 60;
        if (h > 0) return h + (h == 1 ? " hour" : " hours") + (min % 60 > 0 ? " and " + (min % 60) + " minutes" : "");
        return min + (min == 1 ? " minute" : " minutes") + (seconds % 60 > 0 ? " and " + (seconds % 60) + " seconds" : "");
    }
}
