package com.blink.buddy;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Sweety's home: talk to her, preview her nature scenes, start floating Sweety, set the wallpaper, tune moments. */
public class MainActivity extends Activity implements Assistant.Host, Ears.Callback {
    private static final int REQ_MIC = 21, REQ_CONTACT = 22, REQ_WAKE = 23;
    private TextView brainLine, chatInfo, wakeInfo;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Live line under the Hey Sweety switch: what the listener is doing and the last thing it heard. */
    private final Runnable wakeTick = new Runnable() {
        @Override public void run() {
            if (wakeInfo != null) {
                if (!WakeService.enabled(MainActivity.this)) wakeInfo.setText("");
                else {
                    String heardLast = WakeService.lastHeard;
                    wakeInfo.setText("Status: " + WakeService.state + (heardLast.isEmpty() ? "" : "\nLast heard: \"" + heardLast + "\""));
                }
            }
            ui.postDelayed(this, 700);
        }
    };

    /** Replies from "Hey Sweety" (and the talk window) also show here and move the preview. */
    private final Bus.SweetyListener echo = this::onEcho;

    private void onEcho(String text, String action, String emotion) {
        ui.post(() -> {
            if (text != null) { reply.setText(text); preview.blink.say(text, Math.max(3, text.length() / 14f)); }
            if (action != null || emotion != null) preview.blink.cue(action, emotion);
        });
    }
    private CheckBox wakeBox;
    private Button chatBtn;
    private float dp;
    private SharedPreferences prefs;
    private BlinkView preview;
    private Hearing ears;
    private TextView micBtn, reply, heardText;
    private Assistant.NumberCallback contactCb;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("blink", MODE_PRIVATE);
        getWindow().setStatusBarColor(0xFFF6EEF8);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(18), px(18), px(18), px(32));
        col.setBackgroundColor(0xFFF6EEF8);

        TextView title = text("Sweety", 30, true);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        col.addView(title);
        col.addView(text("Your cute robot helper. Talk to her, and she sets alarms and reminders, calls or texts people, switches the torch, controls music and more.", 15, false));

        preview = new BlinkView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(420));
        lp.topMargin = px(14);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(px(24));
        frame.setColor(Color.WHITE);
        preview.setBackground(frame);
        preview.setClipToOutline(true);
        col.addView(preview, lp);

        // Talk to Sweety
        LinearLayout talk = new LinearLayout(this);
        talk.setOrientation(LinearLayout.VERTICAL);
        talk.setPadding(px(16), px(14), px(16), px(14));
        GradientDrawable tb = new GradientDrawable(); tb.setColor(Color.WHITE); tb.setCornerRadius(px(20)); tb.setStroke(px(1), 0xFFE0D6EB);
        talk.setBackground(tb);
        TextView th = text("Talk to Sweety", 18, true);
        talk.addView(th);
        heardText = text("Tap Talk and say: \"set an alarm for 7 am\", \"remind me in 10 minutes to drink water\", \"call mom\", \"torch on\", \"what's my battery\".", 14, false);
        talk.addView(heardText);
        reply = text("", 17, true);
        reply.setTextColor(0xFFA11F5C);
        talk.addView(reply);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this); input.setHint("Or type to Sweety"); input.setSingleLine(true); input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, e) -> { String s = input.getText().toString(); input.setText(""); if (!s.trim().isEmpty()) ask(s); return true; });
        micBtn = text("Talk", 16, true); micBtn.setTextColor(Color.WHITE); micBtn.setGravity(Gravity.CENTER);
        GradientDrawable mb = new GradientDrawable(); mb.setColor(0xFFE2468C); mb.setCornerRadius(px(24)); micBtn.setBackground(mb);
        micBtn.setOnClickListener(v -> { if (ears.listening()) ears.stop(); else listen(); });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(88), px(48)); mp.leftMargin = px(10);
        row.addView(micBtn, mp);
        talk.addView(row);
        CheckBox voice = new CheckBox(this); voice.setText("Sweety talks out loud"); voice.setTextColor(0xFF2A1D40);
        voice.setChecked(Assistant.get(this).voiceOn());
        voice.setOnCheckedChangeListener((b, on) -> Assistant.get(this).setVoiceOn(on));
        talk.addView(voice);
        talk.addView(text("Her voice (tap to hear):", 13, false));
        String[][] voices = new String[Assistant.VOICES.length][2];
        for (int i = 0; i < voices.length; i++) { voices[i][0] = Assistant.VOICES[i][0]; voices[i][1] = Assistant.VOICES[i][1]; }
        talk.addView(chipRow(voices, id -> {
            Assistant a = Assistant.get(this);
            a.setVoiceStyle(id);
            String line = "Hi! I'm Sweety. Do you like my voice?";
            say(line); cue("wave", "happy");
            int ms = Sounds.play("hello");
            preview.postDelayed(() -> a.speak(line), ms + 60);
        }));
        talk.addView(text("Tricks:", 13, false));
        String[][] tricks = {{"wave", "Wave"}, {"dance", "Dance"}, {"flip", "Backflip"}, {"cartwheel", "Cartwheel"}, {"laugh", "Giggle"}, {"spin", "Spin"}, {"jump", "Jump"}};
        talk.addView(chipRow(tricks, id -> ask(id.equals("laugh") ? "giggle" : id.equals("flip") ? "do a backflip" : id.equals("cartwheel") ? "do a cartwheel" : id)));
        TextView brain = text(Assistant.get(this).brainStatus(), 12, false);
        talk.addView(brain);
        LinearLayout.LayoutParams tlp = full(px(14));
        col.addView(talk, tlp);
        ears = new Hearing(this, this);
        brainLine = brain;
        preview.postDelayed(this::refreshBrain, 4000);

        // Hey Sweety: hands-free while the screen is on
        col.addView(header("Hey Sweety (hands-free)"));
        col.addView(text("Say \"Hey Sweety\" anywhere on your phone. She chirps, her talk card pops up, and you say what you need, like \"set an alarm for 7 am\". "
            + "She listens for her name only while the screen is on and unlocked (the detector runs on the phone), and a notification shows while it's on. "
            + "Also allow \"display over other apps\" (Start floating Sweety asks for it) so her card can pop up over any app.", 14, false));
        wakeBox = new CheckBox(this); wakeBox.setText("Listen for \"Hey Sweety\""); wakeBox.setTextColor(0xFF2A1D40); wakeBox.setTextSize(16);
        wakeBox.setChecked(WakeService.enabled(this));
        wakeBox.setOnCheckedChangeListener((b, on) -> setWake(on));
        col.addView(wakeBox, full(px(6)));
        wakeInfo = text("", 13, false);
        wakeInfo.setTextColor(0xFF2E7D5B);
        col.addView(wakeInfo);
        col.addView(text("Like Gemini: make Sweety your phone's assistant, then hold the power or home button (or swipe up from a bottom corner) to call her from anywhere, even the lock screen.", 14, false));
        Button assistant = button("Make Sweety my assistant", false);
        assistant.setOnClickListener(v -> makeAssistant());
        col.addView(assistant, full(px(8)));

        // Chat brain: a small offline language model for real conversations
        col.addView(header("Chat brain"));
        col.addView(text("Needle 3 handles commands. For chatting and questions, Sweety needs a small chat brain (Qwen3-0.6B, about 330 MB). It downloads once, then works offline. Use Wi-Fi.", 14, false));
        chatBtn = button("Download chat brain (330 MB)", true);
        chatBtn.setOnClickListener(v -> downloadChat());
        col.addView(chatBtn, full(px(10)));
        chatInfo = text("", 13, false);
        col.addView(chatInfo);
        refreshChat();

        // Smart brain: Gemini with the user's own free key
        col.addView(header("Smart brain (Gemini)"));
        col.addView(text("For a truly smart assistant, Sweety can think with Google's Gemini: real answers, follow-up questions, and she remembers what you tell her. "
            + "Get a free API key from Google AI Studio and paste it here. What you ask is sent to Google when Gemini answers; without internet she uses her offline brain.", 14, false));
        EditText keyBox = new EditText(this);
        keyBox.setHint(Assistant.get(this).gemini().configured() ? "Key saved ✓ (paste a new one to change it)" : "Paste your Gemini API key");
        keyBox.setSingleLine(true);
        keyBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(keyBox, full(px(6)));
        Button saveKey = button("Save key", true);
        saveKey.setOnClickListener(v -> {
            String k = keyBox.getText().toString().trim();
            if (k.length() < 20) { toast("That doesn't look like a Gemini key."); return; }
            prefs.edit().putString("gemini_key", k).putString("gemini_model", "").apply();
            keyBox.setText(""); keyBox.setHint("Key saved ✓ (paste a new one to change it)");
            toast("Saved! Ask Sweety anything.");
            ask("Hi Sweety, are you there?");
        });
        col.addView(saveKey, full(px(8)));
        Button getKey = button("Get a free key (Google AI Studio)", false);
        getKey.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))); } catch (ActivityNotFoundException e) { toast("Open aistudio.google.com/apikey in your browser."); } });
        col.addView(getKey, full(px(6)));
        Button forgetKey = button("Remove key and forget what she remembers", false);
        forgetKey.setOnClickListener(v -> {
            prefs.edit().remove("gemini_key").remove("gemini_model").remove("memory").apply();
            Assistant.get(this).gemini().forget();
            keyBox.setHint("Paste your Gemini API key");
            toast("Key removed. Sweety uses her offline brain.");
        });
        col.addView(forgetKey, full(px(6)));

        // Natural voice: Kokoro on the phone
        col.addView(header("Natural voice"));
        col.addView(text("A warm, natural voice that runs on the phone (Kokoro, about 100 MB, downloaded once on Wi-Fi). Until then Sweety uses the phone's own voice.", 14, false));
        voiceBtn = button("Download natural voice (100 MB)", true);
        voiceBtn.setOnClickListener(v -> downloadVoice());
        col.addView(voiceBtn, full(px(8)));
        voiceInfo = text("", 13, false);
        col.addView(voiceInfo);
        col.addView(text("Pick her natural voice (tap to hear):", 13, false));
        col.addView(chipRow(KokoroVoice.SPEAKERS, id -> {
            Assistant a = Assistant.get(this);
            a.voice().setSpeaker(id);
            if (!a.voice().downloaded()) { toast("Download the natural voice first."); return; }
            String line = "Hi! I'm Sweety. Do you like my voice?";
            say(line); cue("wave", "happy");
            a.speak(line);
        }));
        refreshVoice();

        col.addView(header("Floating Sweety (like the desktop version)"));
        col.addView(text("Sweety walks, flies and perches on top of your home screen and apps. Drag her anywhere, flick to throw, tap to poke, double-tap for a new outfit, long-press to talk. She hides in landscape (videos and games).", 14, false));
        Button start = button("Start floating Sweety", true);
        start.setOnClickListener(v -> startFloating());
        col.addView(start, full(px(10)));
        Button stop = button("Stop floating Sweety", false);
        stop.setOnClickListener(v -> { stopService(new Intent(this, OverlayService.class)); toast("Floating Sweety stopped."); });
        col.addView(stop, full(px(8)));
        String[][] sizes = {{"100", "Small"}, {"120", "Medium"}, {"150", "Large"}};
        col.addView(chipRow(sizes, id -> {
            prefs.edit().putInt("floatSize", Integer.parseInt(id)).apply();
            if (OverlayService.running) { stopService(new Intent(this, OverlayService.class)); startFloating(); }
            toast("Size saved.");
        }));

        col.addView(header("Wallpaper (home and lock screen)"));
        col.addView(text("Sweety lives in a nature scene that matches her outfit: sunny sky with birds, meadow with butterflies, lavender sunset, bamboo forest, rainbow garden, snowy pines, starry night, candy balloons or the ocean.", 14, false));
        Button set = button("Set Sweety as my wallpaper", false);
        set.setOnClickListener(v -> setWallpaper());
        col.addView(set, full(px(12)));
        col.addView(text("Tip: when asked, choose \"Home and lock screens\" so Sweety shows up on both. Some phones (for example many Samsung models) only allow live wallpapers on the home screen.", 13, false));

        col.addView(header("Try a moment"));
        String[][] sims = {{"screen", "Screen on"}, {"unlock", "Unlock"}, {"missed", "Missed you"}, {"swipe", "Swipe page"}, {"charge", "Plug in"},
                {"lowbat", "Low battery"}, {"music", "Headphones"}, {"shake", "Shake"}, {"outfit", "New outfit"}, {"nudge", "30 min nudge"}};
        col.addView(chipRow(sims, id -> preview.blink.simulate(id)));
        col.addView(text("You can also tap, double-tap and stroke Sweety in the preview.", 13, false));

        col.addView(header("Outfit"));
        String[][] outfits = new String[Skin.ALL.length][2];
        for (int i = 0; i < Skin.ALL.length; i++) { outfits[i][0] = Skin.ALL[i].id; outfits[i][1] = Skin.ALL[i].name; }
        col.addView(chipRow(outfits, id -> preview.blink.wear(Skin.byId(id), true)));

        col.addView(header("Moments"));
        for (String[] m : Moments.LIST) {
            CheckBox cb = new CheckBox(this);
            cb.setText(m[1] + "\n" + m[2]);
            cb.setTextSize(14);
            cb.setTextColor(0xFF2A1D40);
            cb.setChecked(Moments.on(prefs, m[0]));
            cb.setPadding(px(6), px(8), 0, px(8));
            cb.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("m_" + m[0], on).apply());
            col.addView(cb);
        }


        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    /* ------------------------------------------------------------ talking */
    private void listen() {
        Assistant.get(this).stopTalking();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        ears.listen();
    }

    private void ask(String text) {
        heardText.setText("You: " + text);
        Assistant.get(this).handle(text, this);
    }

    /* ------------------------------------------------------------ Hey Sweety + chat brain */
    @Override
    protected void onResume() {
        super.onResume();
        // Android only lets the microphone service start while Sweety is on screen, so (re)start it here.
        WakeService.start(this);
        Assistant.get(this).warmChat();
        Bus.addSweety(echo);
        ui.removeCallbacks(wakeTick);
        ui.post(wakeTick);
        if (wakeBox != null && wakeBox.isChecked() != WakeService.enabled(this)) {
            syncing = true; wakeBox.setChecked(WakeService.enabled(this)); syncing = false;
        }
        refreshChat();
        refreshVoice();
    }

    @Override
    protected void onPause() {
        Bus.removeSweety(echo);
        ui.removeCallbacks(wakeTick);
        super.onPause();
    }

    /** Asks Android not to put Sweety to sleep in the background (some phones stop the listener otherwise). */
    private void askToStayAwake() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()))
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) { }
    }

    private boolean syncing;

    private void setWake(boolean on) {
        if (syncing) return;
        if (!on) { WakeService.setEnabled(this, false); toast("Hey Sweety is off."); return; }
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), REQ_WAKE); return; }
        WakeService.setEnabled(this, true);
        askToStayAwake();
        if (!Settings.canDrawOverlays(this)) {
            toast("Hey Sweety is on. Also allow \"Display over other apps\" so she can open the clock or dialer from any app.");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException e) { /* optional */ }
        } else {
            toast("Hey Sweety is on. Try: \"Hey Sweety, what time is it?\"");
        }
    }

    private void refreshBrain() { if (brainLine != null) brainLine.setText(Assistant.get(this).brainStatus()); }

    private void refreshChat() {
        if (chatBtn == null) return;
        ChatBrain cb = Assistant.get(this).chatBrain();
        if (cb.downloaded()) {
            chatBtn.setVisibility(View.GONE);
            chatInfo.setText("Chat brain downloaded. Ask Sweety anything: \"tell me a story\", \"why is the sky blue?\", \"I'm bored\".");
        } else if (!cb.downloading()) {
            chatBtn.setVisibility(View.VISIBLE);
            chatBtn.setEnabled(true);
        }
        refreshBrain();
    }

    private Button voiceBtn;
    private TextView voiceInfo;

    private void refreshVoice() {
        if (voiceBtn == null) return;
        KokoroVoice v = Assistant.get(this).voice();
        if (v.downloaded()) { voiceBtn.setVisibility(View.GONE); voiceInfo.setText("Natural voice downloaded. Pick one below."); }
        else if (!v.downloading()) { voiceBtn.setVisibility(View.VISIBLE); voiceBtn.setEnabled(true); }
    }

    private void downloadVoice() {
        voiceBtn.setEnabled(false);
        voiceInfo.setText("Starting download…");
        Assistant.get(this).voice().download(new ChatBrain.Progress() {
            @Override public void onProgress(long done, long total) {
                if (done < 0) { voiceInfo.setText("Unpacking…"); return; }
                long mb = done >> 20, all = total >> 20;
                voiceInfo.setText(total > 0 ? "Downloading… " + mb + " of " + all + " MB (" + (done * 100 / total) + "%)" : "Downloading… " + mb + " MB");
            }
            @Override public void onDone(boolean ok, String message) {
                voiceInfo.setText(message);
                voiceBtn.setEnabled(true);
                if (ok) {
                    refreshVoice(); refreshBrain();
                    String line = "Ta-da! This is my real voice. Nice to meet you properly!";
                    say(line); cue("jump", "love");
                    Assistant.get(MainActivity.this).speak(line);
                } else voiceBtn.setText("Resume download");
            }
        });
    }

    /** Asks Android to make Sweety the default digital assistant (hold power/home to call her). */
    private void makeAssistant() {
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) { toast("Sweety is already your assistant. Hold the power or home button to call her!"); return; }
                try { startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT), 31); return; } catch (Exception ignored) { }
            }
        }
        // Many phones only allow choosing the assistant in Settings.
        toast("Choose Sweety under \"Digital assistant app\".");
        for (String action : new String[]{Settings.ACTION_VOICE_INPUT_SETTINGS, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", Settings.ACTION_SETTINGS}) {
            try { startActivity(new Intent(action)); return; } catch (Exception ignored) { }
        }
    }

    private void downloadChat() {
        ChatBrain cb = Assistant.get(this).chatBrain();
        chatBtn.setEnabled(false);
        chatInfo.setText("Starting download…");
        cb.download(new ChatBrain.Progress() {
            @Override public void onProgress(long done, long total) {
                long mb = done >> 20, all = total >> 20;
                chatInfo.setText(total > 0 ? "Downloading… " + mb + " of " + all + " MB (" + (done * 100 / total) + "%)" : "Downloading… " + mb + " MB");
            }
            @Override public void onDone(boolean ok, String message) {
                chatInfo.setText(message);
                chatBtn.setEnabled(true);
                if (ok) {
                    Assistant.get(MainActivity.this).warmChat();
                    refreshChat();
                    say("Yay, I feel so much smarter now! Ask me anything.");
                    cue("jump", "happy");
                    Assistant.get(MainActivity.this).speak("Yay, I feel so much smarter now! Ask me anything.");
                } else {
                    chatBtn.setText("Resume download");
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_WAKE) {
            boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (mic) setWake(true);
            else { syncing = true; wakeBox.setChecked(false); syncing = false; toast("Hey Sweety needs the microphone."); }
            return;
        }
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) ears.listen();
            else reply.setText("I need the microphone to hear you. You can type instead.");
        }
    }

    @Override public void onListening(boolean on) { micBtn.setText(on ? "Stop" : "Talk"); if (on) { reply.setText("Listening…"); preview.blink.setEmotion("curious", 6); } }
    @Override public void onPartial(String text) { heardText.setText("You: " + text + "…"); }
    @Override public void onHeard(String text) { ask(text); }
    @Override public void onProblem(String message) { reply.setText(message); }

    @Override public void say(String text) { reply.setText(text); preview.blink.say(text, Math.max(3, text.length() / 14f)); }
    @Override public void cue(String action, String emotion) { preview.blink.cue(action, emotion); }
    @Override public Activity activity() { return this; }

    @Override
    public void pickContact(String forName, Assistant.NumberCallback cb) {
        contactCb = cb;
        try { startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT); }
        catch (ActivityNotFoundException e) { contactCb = null; cb.onNumber(null); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 31) { toast(res == RESULT_OK ? "Done! Hold the power or home button to call Sweety." : "No problem. You can do this later."); return; }
        if (req != REQ_CONTACT || contactCb == null) return;
        Assistant.NumberCallback cb = contactCb; contactCb = null;
        cb.onNumber(res == RESULT_OK && data != null ? TalkActivity.numberFrom(this, data.getData()) : null);
    }

    @Override
    protected void onDestroy() {
        if (ears != null) ears.destroy();
        super.onDestroy();
    }

    private void startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Turn on \"Allow display over other apps\" for Sweety, then come back and tap Start again.");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        startForegroundService(new Intent(this, OverlayService.class));
        toast("Sweety is floating! Go to your home screen.");
    }

    private void setWallpaper() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, BlinkWallpaperService.class));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); toast("Pick Sweety from the list."); }
            catch (ActivityNotFoundException e2) { toast("This phone does not support live wallpapers."); }
        }
    }

    private interface OnPick { void pick(String id); }

    private View chipRow(String[][] items, OnPick onPick) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(6), 0, px(6));
        for (String[] it : items) {
            TextView chip = text(it[1], 14, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(px(14), px(9), px(14), px(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(20));
            bg.setColor(Color.WHITE);
            bg.setStroke(px(1), 0xFFE0D6EB);
            chip.setBackground(bg);
            chip.setOnClickListener(v -> onPick.pick(it[0]));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = px(8);
            row.addView(chip, p);
        }
        hs.addView(row);
        return hs;
    }

    private TextView header(String s) {
        TextView t = text(s, 19, true);
        t.setPadding(0, px(20), 0, px(4));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(bold ? 0xFF211832 : 0xFF6A5F80);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, px(4), 0, px(4));
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(primary ? Color.WHITE : 0xFFA11F5C);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(16));
        bg.setColor(primary ? 0xFFE2468C : Color.WHITE);
        if (!primary) bg.setStroke(px(2), 0xFFE2468C);
        b.setBackground(bg);
        b.setPadding(px(16), px(14), px(16), px(14));
        return b;
    }

    private LinearLayout.LayoutParams full(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private int px(float v) { return Math.round(v * dp); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
