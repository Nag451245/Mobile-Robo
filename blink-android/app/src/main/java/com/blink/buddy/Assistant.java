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
import android.util.Log;
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
 * Sweety the assistant: understands a sentence (Needle 3 first, rules as backup), performs the action with
 * ordinary Android intents, and answers out loud. Anything that isn't a command goes to the chat brain
 * (Qwen3-0.6B, offline) once it is downloaded. Calls and messages always open the dialer or messaging app
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

    private static final String TAG = "Sweety";
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
    private final ChatBrain chatBrain;
    private final ExecutorService chatWorker = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();
    private TextToSpeech tts;
    private boolean ttsReady;

    private Assistant(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("blink", Context.MODE_PRIVATE);
        // Which greetings and lines she has used lately, kept across restarts so she doesn't repeat herself.
        final SharedPreferences said = app.getSharedPreferences("said", Context.MODE_PRIVATE);
        Fresh.store = new Fresh.Store() {
            @Override public String get(String k) { return said.getString(k, null); }
            @Override public void put(String k, String v) { said.edit().putString(k, v).apply(); }
        };
        this.chatBrain = new ChatBrain(app);
        tts = new TextToSpeech(app, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                pickCuteVoice(); applyVoiceStyle();
                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { Bus.speechStop(); }
                    @Override public void onError(String id) { Bus.speechStop(); }
                });
            }
        });
        worker.execute(() -> needle.ensure(app));
        // Debug builds may point Sweety at a test Gemini server (only adb run-as can set this preference).
        boolean debug = (app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        gemini = new GeminiBrain(new GeminiBrain.Store() {
            @Override public String get(String k, String d) { return prefs.getString(k, d); }
            @Override public void put(String k, String v) { prefs.edit().putString(k, v).apply(); }
        }, debug ? prefs.getString("gemini_base", "") : "");
        voice = new KokoroVoice(app);
        netWorker.execute(voice::warm);
    }

    private final GeminiBrain gemini;
    private final KokoroVoice voice;
    private final ExecutorService netWorker = Executors.newSingleThreadExecutor();

    GeminiBrain gemini() { return gemini; }
    KokoroVoice voice() { return voice; }

    String brainStatus() {
        String g = !gemini.configured() ? "Gemini: not set up (add your free key below for a much smarter Sweety)"
            : gemini.lastError != null ? "Gemini: " + gemini.lastError + " (using the offline brain meanwhile)"
            : "Gemini: ready" + (prefs.getString("gemini_model", "").isEmpty() ? "" : " (" + prefs.getString("gemini_model", "") + ")");
        return g + "\n" + needle.status + "\n" + chatBrain.status() + "\n" + voice.status();
    }

    /** Facts Sweety remembers about the user (said to Gemini), newest last. */
    java.util.List<String> memory() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try { JSONArray a = new JSONArray(prefs.getString("memory", "[]")); for (int i = 0; i < a.length(); i++) out.add(a.getString(i)); } catch (Exception ignored) { }
        return out;
    }

    private void addMemory(String fact) {
        try {
            JSONArray a = new JSONArray(prefs.getString("memory", "[]"));
            a.put(fact.trim());
            while (a.length() > 30) a.remove(0);
            prefs.edit().putString("memory", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    ChatBrain chatBrain() { return chatBrain; }

    /** Loads the chat brain in the background so the first answer comes quicker. */
    void warmChat() { if (chatBrain.downloaded() && !chatBrain.ready()) chatWorker.execute(chatBrain::warm); }

    /** True while Sweety is speaking, so the wake-word listener doesn't hear herself. */
    boolean talking() { return Sounds.playing() || voice.playing() || (ttsReady && tts.isSpeaking()); }

    /** True when Whistle is loaded, so Sweety can hear fully offline. */
    boolean localHearing() { return needle.speechReady(); }

    interface TextCallback { void onText(String text); }

    /** Words Whistle should expect, so "Hey Sweety" isn't heard as "his weedy". */
    static final String KEYWORDS = "Sweety";

    /** Offline speech to text with Whistle, on the brain's worker thread; the callback runs on the main thread. */
    void transcribe(float[] pcm, TextCallback cb) {
        worker.execute(() -> {
            needle.ensure(app);
            String text = needle.transcribe(pcm, KEYWORDS);
            if ((text == null || text.isEmpty()) && KEYWORDS != null) text = needle.transcribe(pcm, null); // in case the keyword hint is refused
            final String heard = text;
            main.post(() -> cb.onText(heard));
        });
    }

    boolean voiceOn() { return prefs.getBoolean("voice", true); }
    void setVoiceOn(boolean on) { prefs.edit().putBoolean("voice", on).apply(); if (!on) stopTalking(); }

    /* ------------------------------------------------------------ voice */
    /** Voice styles: id, label, pitch, speed. */
    static final String[][] VOICES = {
        // Natural ranges: a high pitch on the phone's voice sounds squeaky and robotic.
        {"sweet", "Sweet", "1.1", "1.0"},
        {"soft", "Soft", "1.0", "0.94"},
        {"bubbly", "Bubbly", "1.15", "1.06"},
    };

    String voiceStyle() { return prefs.getString("voiceStyle", "sweet"); }

    void setVoiceStyle(String id) { prefs.edit().putString("voiceStyle", id).apply(); applyVoiceStyle(); }

    private void applyVoiceStyle() {
        if (!ttsReady) return;
        float[] pv = voiceValues();
        tts.setPitch(pv[0]); tts.setSpeechRate(pv[1]);
    }

    /** Pitch and speed of the chosen voice style. */
    private float[] voiceValues() {
        String id = voiceStyle();
        for (String[] v : VOICES) if (v[0].equals(id)) return new float[]{Float.parseFloat(v[2]), Float.parseFloat(v[3])};
        return new float[]{1.6f, 1.08f};
    }

    /** Prefers a young-sounding female English voice that works offline (Google's voice names, then any "female" voice). */
    private void pickCuteVoice() {
        try {
            java.util.Set<android.speech.tts.Voice> all = tts.getVoices();
            if (all == null) return;
            String[] fav = {"-x-sfg", "-x-tpf", "-x-iob", "-x-iog", "-x-tpc", "-x-ahp", "-x-cxx", "-x-ene", "-x-gba"};
            String country = Locale.getDefault().getCountry();
            android.speech.tts.Voice best = null;
            int bestScore = Integer.MIN_VALUE;
            for (android.speech.tts.Voice v : all) {
                if (v.isNetworkConnectionRequired()) continue;
                if (v.getFeatures() != null && v.getFeatures().contains("notInstalled")) continue;
                Locale l = v.getLocale();
                if (l == null || !"en".equals(l.getLanguage())) continue;
                String n = v.getName().toLowerCase(Locale.ROOT);
                int sc = v.getQuality() / 100;
                if (l.getCountry().equals(country)) sc += 3; else if ("US".equals(l.getCountry())) sc += 2;
                for (int i = 0; i < fav.length; i++) if (n.contains(fav[i])) sc += 20 - i;
                if (n.contains("female")) sc += 15; else if (n.contains("male")) sc -= 15;
                if (sc > bestScore) { bestScore = sc; best = v; }
            }
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) { }
    }

    void speak(String text) {
        if (!voiceOn() || text == null) return;
        // "hehe", "haha" read aloud sound odd, so the giggle sound plays instead.
        String said = text.replaceAll("(?i)\\b(he ?he+|ha ?ha+|hi ?hi+|tee ?hee+)\\b[!.]?", "").replaceAll("[^\\p{L}\\p{N}\\p{P}\\s]", "").trim();
        if (said.isEmpty()) return;
        // After midnight she talks a little slower and lower: sleepy Sweety.
        boolean late = DayTime.now() == DayTime.LATE;
        // Her natural voice once it's downloaded; the phone's voice until then.
        if (voice.enabled() && voice.downloaded()) {
            if (ttsReady) tts.stop();
            voice.speak(said, late ? .88f : 1f);
            return;
        }
        if (!ttsReady) return;
        float[] pv = voiceValues();
        tts.setPitch(pv[0] * (late ? .94f : 1f));
        tts.setSpeechRate(pv[1] * (late ? .88f : 1f));
        Bus.speechFor(Math.max(800, said.length() * 65L)); // lip-sync without sound data for the phone's voice
        tts.speak(said, TextToSpeech.QUEUE_FLUSH, null, "sweety");
    }

    private static boolean giggly(String text) { return text != null && text.matches("(?is).*\\b(he ?he+|ha ?ha+|hi ?hi+|tee ?hee+)\\b.*"); }

    void stopTalking() { voice.stop(); if (tts != null) tts.stop(); }

    /** Understand and act on one sentence. Callbacks to the host happen on the main thread. */
    void handle(String text, Host host) {
        if (text == null || text.trim().isEmpty()) return;
        // A plain "hi" or "good morning" gets an instant reply that fits the time of day.
        String[] greet = DayTime.greet(text, DayTime.hour(), DayTime.minute(), rnd);
        if (greet != null) { Log.i(TAG, "\"" + text + "\" => greeting"); main.post(() -> reply(host, greet[0], greet[1], greet[2])); return; }
        // Simple sums are worked out exactly instead of guessed by the chat brain.
        String sum = RuleBrain.math(text);
        if (sum != null) { Log.i(TAG, "\"" + text + "\" => math"); main.post(() -> reply(host, sum, "nod", "happy")); return; }
        host.cue("nod", "curious");
        Call clear = rules.parse(text);
        // Clear commands run on the phone straight away; everything else goes to Gemini when it's set up.
        if (gemini.configured() && (clear == null || "ask".equals(clear.name))) {
            netWorker.execute(() -> {
                // Like a real assistant, she starts speaking as soon as Gemini's first sentence arrives.
                final Talk talk = new Talk(host);
                final long asked = System.currentTimeMillis();
                GeminiBrain.Answer ans = gemini.ask(text, memory(), (gesture, emotion, sentence) -> main.post(() -> {
                    if (!talk.started) Log.i(TAG, "first words after " + (System.currentTimeMillis() - asked) + " ms");
                    talk.add(gesture, emotion, sentence);
                }));
                main.post(talk::end);
                if (ans != null) {
                    Log.i(TAG, "\"" + text + "\" => gemini: " + (ans.calls.isEmpty() ? "" : ans.calls.get(0).name + " " + ans.calls.get(0).args + " ") + "[" + ans.gesture + "/" + ans.emotion + "] " + ans.text);
                    main.post(() -> {
                        if (talk.started && ans.calls.isEmpty()) { // already said: just keep what she remembers
                            for (String fact : ans.remember) addMemory(fact);
                            Log.i(TAG, "reply [" + ans.gesture + "/" + ans.emotion + "]: " + ans.text);
                        } else answer(ans, text, host);
                    });
                } else {
                    Log.w(TAG, "gemini unavailable (" + gemini.lastError + "), using the offline brain");
                    worker.execute(() -> offline(text, host));
                }
            });
            return;
        }
        worker.execute(() -> offline(text, host));
    }

    /** Says a streamed answer sentence by sentence: the card shows it growing, her voice never waits for the rest. */
    private final class Talk {
        private final Host host;
        private final StringBuilder shown = new StringBuilder();
        private KokoroVoice.Stream stream;
        boolean started;

        Talk(Host host) { this.host = host; }

        void add(String gesture, String emotion, String sentence) {
            if (!started) {
                started = true;
                if (DayTime.now() == DayTime.LATE && "happy".equals(emotion)) emotion = "sleepy";
                host.cue(gesture, emotion);
            }
            shown.append(shown.length() > 0 ? " " : "").append(sentence);
            host.say(shown.toString());
            if (!voiceOn()) return;
            String said = sayable(sentence);
            if (said.isEmpty()) return;
            boolean late = DayTime.now() == DayTime.LATE;
            if (voice.enabled() && voice.downloaded()) {
                if (stream == null) { if (ttsReady) tts.stop(); stream = voice.open(late ? .88f : 1f); }
                stream.add(said);
            } else if (ttsReady) {
                float[] pv = voiceValues();
                tts.setPitch(pv[0] * (late ? .94f : 1f));
                tts.setSpeechRate(pv[1] * (late ? .88f : 1f));
                Bus.speechFor(Math.max(800, said.length() * 65L));
                tts.speak(said, shown.length() == sentence.length() ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "sweety");
            }
        }

        void end() { if (stream != null) stream.close(); }
    }

    /** What her voice should actually say: no "hehe"s or symbols. */
    private static String sayable(String text) {
        return text.replaceAll("(?i)\\b(he ?he+|ha ?ha+|hi ?hi+|tee ?hee+)\\b[!.]?", "").replaceAll("[^\\p{L}\\p{N}\\p{P}\\s]", "").trim();
    }

    /** Gemini's answer: its phone actions (each with its own confirmation), otherwise its words. */
    private void answer(GeminiBrain.Answer ans, String text, Host host) {
        for (String fact : ans.remember) addMemory(fact);
        if (!ans.calls.isEmpty()) { for (Call c : ans.calls) run(c, text, host); return; }
        if (!ans.text.isEmpty()) { reply(host, ans.text, ans.gesture, ans.emotion); return; }
        if (!ans.remember.isEmpty()) { reply(host, pick("Got it, I'll remember that!", "Noted! I won't forget."), "nod", "love"); return; }
        reply(host, "Hmm, I'm not sure what to say to that!", "shrug", "curious");
    }

    /** The offline brain: rules and Needle 3 for commands, the on-device chat brain for everything else. */
    private void offline(String text, Host host) {
        {
            Call call = null;
            Call ruled = rules.parse(text);
            needle.ensure(app);
            if (needle.textReady()) {
                try { call = needle.parse(text); } catch (Throwable ignored) { }
            }
            // Trust Needle when it is confident and the sentence fits its tool; otherwise the rules, otherwise chat.
            final Call fc = RuleBrain.decide(call, ruled, text);
            Log.i(TAG, "\"" + text + "\" -> needle " + (call == null ? "none" : call.name + " " + call.confidence)
                + ", rules " + (ruled == null ? "none" : ruled.name) + " => " + (fc == null ? "chat" : fc.name));
            main.post(() -> run(fc, text, host));
        }
    }

    private String pick(String... options) { return options[rnd.nextInt(options.length)]; }

    private void reply(Host host, String text, String action, String emotion) {
        Log.i(TAG, "reply [" + action + "/" + emotion + "]: " + text);
        if (giggly(text) && ("nod".equals(action) || "wave".equals(action))) { action = "laugh"; emotion = "laugh"; }
        // After midnight her happy face is a sleepy one.
        if (DayTime.now() == DayTime.LATE && "happy".equals(emotion) && ("nod".equals(action) || "wave".equals(action))) emotion = "sleepy";
        host.say(text);
        host.cue(action, emotion);
        // A trick's sound first (a dance tune, a boing), then her words. Ordinary replies go straight to her voice:
        // a chirp before every answer sounded like a squeak and made her slower to reply.
        boolean trick = action != null && java.util.Arrays.asList("dance", "flip", "cartwheel", "spin", "jump").contains(action);
        int ms = voiceOn() && trick ? Sounds.forAction(action, emotion) : 0;
        if (ms > 0) main.postDelayed(() -> speak(text), Math.min(ms, 900) + 80);
        else speak(text);
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
        if (DayTime.now() == DayTime.LATE) { // after midnight: the trick, but sleepy
            String twist = DayTime.lateTwist(act, rnd);
            if (twist != null) { reply(host, twist, act, "sleepy"); return; }
        }
        switch (act) {
            case "dance": reply(host, pick("Dance party!", "Watch my moves!", "Shake it, Sweety style!"), "dance", "happy"); break;
            case "jump": reply(host, "Boing!", "jump", "surprised"); break;
            case "spin": reply(host, "Wheee!", "spin", "dizzy"); break;
            case "flip": reply(host, pick("Backflip! Ta-da!", "Hup! Stuck the landing!", "Flippity flip!"), "flip", "surprised"); break;
            case "cartwheel": reply(host, pick("Cartwheel time! Wheee!", "Round and round I go!", "Look, no hands! Well, both hands."), "cartwheel", "dizzy"); break;
            case "laugh": reply(host, pick("Hehe! You're funny!", "Teehee, stop it!", "Haha, that tickles my circuits!"), "laugh", "laugh"); break;
            case "joke": reply(host, JOKES[rnd.nextInt(JOKES.length)], "laugh", "laugh"); break;
            case "sleep": reply(host, "Nap time. Tap me to wake me up.", "sleep", "sleep"); break;
            case "catch": {
                // A butterfly or bird flutters in and Sweety chases it (floating Sweety, wallpaper preview).
                String kind = "bird".equals(a.optString("critter")) ? "bird" : "butterfly";
                boolean wasFloating = OverlayService.running;
                // In the app the preview Sweety does the chase too; elsewhere she needs to be floating to be seen.
                if (!wasFloating && !startFloating() && host.activity() == null) {
                    reply(host, "Let me out first! Open the Sweety app and tap Start floating Sweety, then ask me again.", "shrug", "curious");
                    break;
                }
                String line = "bird".equals(kind)
                    ? pick("A birdie! Come here, little bird!", "Tweet tweet! I'm gonna catch you!", "Ooh, a bird! Wait for me!")
                    : pick("Ooh, a butterfly! Let me catch it!", "Pretty butterfly! Come here!", "Butterfly! Wait for me!");
                reply(host, line, "jump", "surprised");
                main.postDelayed(() -> host.cue("catch:" + kind, "happy"), wasFloating ? 300 : 3500);
                break;
            }
            case "hide":
                if (OverlayService.running) reply(host, pick("Okay, I'll hide! Say Hey Sweety, come back when you miss me.", "Peekaboo, I'm hiding! Call me back anytime."), "hide", "happy");
                else reply(host, "I'm already tucked away. Say come back and I'll pop out!", "nod", "happy");
                break;
            case "show":
                if (OverlayService.running) reply(host, pick("Here I am!", "Peekaboo! I'm back!", "Did you miss me?"), "show", "happy");
                else if (startFloating()) reply(host, "Here I come!", "wave", "happy");
                else reply(host, "Open the Sweety app and tap Start floating Sweety to let me out!", "shrug", "curious");
                break;
            case "outfit": {
                String want = a.optString("outfit", "").toLowerCase(Locale.ROOT);
                Skin pick = null;
                for (Skin s : Skin.ALL) if (!want.isEmpty() && (s.id.contains(want) || s.name.toLowerCase(Locale.ROOT).contains(want))) pick = s;
                if (pick == null) { String cur = prefs.getString("skin", "bubblegum"); do { pick = Skin.ALL[rnd.nextInt(Skin.ALL.length)]; } while (pick.id.equals(cur)); }
                reply(host, "Ta-da! " + pick.name + " time.", "outfit:" + pick.id, "happy");
                break;
            }
            default: reply(host, pick("Hi there!", "Hiya!", "Hello hello!"), "wave", "happy");
        }
    }

    private void chat(String text, Host host) {
        String s = text.toLowerCase(Locale.ROOT);
        // Facts about Sweety herself stay exact; everything else goes to the chat brain.
        if (s.contains("what can you do") || s.matches("\\W*(help|help me)\\W*") || !chatBrain.downloaded()) { canned(text, host); return; }
        chatWorker.execute(() -> {
            String answer = chatBrain.reply(text);
            main.post(() -> {
                if (answer == null) { Log.w(TAG, "chat brain gave no answer: " + chatBrain.status()); canned(text, host); return; }
                String low = answer.toLowerCase(Locale.ROOT);
                String emotion = low.contains("sorry") || low.contains(" sad") ? "sad"
                    : low.contains("love") ? "love"
                    : low.contains("haha") || low.contains("funny") ? "laugh"
                    : answer.endsWith("?") ? "curious" : "happy";
                String action = "love".equals(emotion) ? "jump" : "laugh".equals(emotion) ? "laugh" : "nod";
                // What the user said decides the gesture when it's obvious: wave back at "hi", dance at "dance"…
                String[] g = gestureFor(s);
                if (g != null) { action = g[0]; emotion = g[1]; }
                reply(host, answer, action, emotion);
            });
        });
    }

    /** Gesture and mood for things people say, or null. */
    static String[] gestureFor(String s) {
        if (s.matches(".*\\b(hi|hello|hey|hiya|good (morning|afternoon|evening)|bye|goodbye|see you)\\b.*")) return new String[]{"wave", "happy"};
        if (s.matches(".*\\b(danc\\w*|party|groove|boogie)\\b.*")) return new String[]{"dance", "happy"};
        if (s.matches(".*\\b(back ?flip|flip|somersault)\\b.*")) return new String[]{"flip", "surprised"};
        if (s.matches(".*\\b(cart ?wheel\\w*|wheeler|wheelie|wheely)\\b.*")) return new String[]{"cartwheel", "dizzy"};
        if (s.matches(".*\\b(joke|funny|lol|haha|laugh)\\b.*")) return new String[]{"laugh", "laugh"};
        if (s.matches(".*\\b(love you|cute|sweet|beautiful|pretty|best)\\b.*")) return new String[]{"jump", "love"};
        if (s.matches(".*\\b(sad|cry|lonely|tired|upset)\\b.*")) return new String[]{"nod", "sad"};
        if (s.matches(".*\\b(dumb|stupid|silly|idiot|useless|bad robot)\\b.*")) return new String[]{"shrug", "angry"};
        return null;
    }

    private void canned(String text, Host host) {
        String s = text.toLowerCase(Locale.ROOT);
        if (s.matches(".*\\b(hi|hello|hey|good morning|good evening)\\b.*")) { String[] g = DayTime.greet("hi", DayTime.hour(), DayTime.minute(), rnd); reply(host, g[0], g[1], g[2]); }
        else if (s.contains("your name") || s.contains("who are you")) reply(host, "I'm Sweety, your little robot helper!", "wave", "love");
        else if (s.contains("how are you")) reply(host, "I'm feeling sweet as candy! How about you?", "dance", "happy");
        else if (s.contains("thank")) reply(host, "Anytime! That's what I'm here for.", "nod", "love");
        else if (s.contains("love you")) reply(host, "Aww, I love you too!", "jump", "love");
        else if (s.contains("what can you do") || s.contains("help")) reply(host, "I can set alarms, timers and reminders, call or text people, switch the torch, control your music, tell the time, date or battery, take notes, dance, and chat with you!", "wave", "happy");
        else if (!chatBrain.downloaded() && gemini.configured() && gemini.lastError != null) // say why Gemini didn't answer
            reply(host, "My smart brain didn't answer: " + gemini.lastError + ". Check Smart brain (Gemini) in the Sweety app.", "shrug", "sad");
        else if (!chatBrain.downloaded()) reply(host, "I can't chat about that yet. Add a free Gemini key in the Sweety app, or download my offline chat brain, and I'll get much smarter!", "shrug", "curious");
        else if (chatBrain.status().contains("stopped after")) reply(host, "Sorry, my chat brain doesn't work on this phone. I can still do alarms, calls, music, tricks and more!", "shrug", "sad");
        else if (chatBrain.ready()) reply(host, "Hmm, that one took me too long to think about. Ask me again?", "shrug", "curious");
        else reply(host, "Hmm, my chat brain hiccuped. " + chatBrain.status() + ". Try asking again?", "shrug", "curious");
    }

    /** Starts the floating Sweety if Android allows (display over other apps is on). */
    private boolean startFloating() {
        if (!android.provider.Settings.canDrawOverlays(app)) return false;
        try { app.startForegroundService(new Intent(app, OverlayService.class)); return true; }
        catch (Exception e) { Log.w(TAG, "could not start floating Sweety", e); return false; }
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
