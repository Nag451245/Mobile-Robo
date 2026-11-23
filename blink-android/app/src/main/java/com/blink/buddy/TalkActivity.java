package com.blink.buddy;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Sweety's assistant card: slides up over whatever app is open (from "Hey Sweety", the assistant button, or
 * long-pressing the floating Sweety) with her 3D self on top. She listens (glowing with your voice), thinks,
 * and answers with lip-sync, gestures and moods.
 */
public class TalkActivity extends Activity implements Assistant.Host, Ears.Callback, Bus.SpeechListener {
    private AvatarView avatar;
    private static final int REQ_MIC = 11, REQ_CONTACT = 12;
    /** A sentence the "Hey Sweety" listener heard but needs a screen for (picking a contact). */
    static final String EXTRA_ASK = "ask";
    /** Opened because the wake-word detector heard "Hey Sweety". */
    static final String EXTRA_WOKE = "woke";
    private final Handler h = new Handler(Looper.getMainLooper());
    private Hearing ears;
    private TextView status, heard;
    private TextView mic;
    private Assistant.NumberCallback contactCb;
    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0x44140A28));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(20), px(6), px(20), px(22));
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFBE7F3, 0xFFFFFFFF});
        bg.setCornerRadii(new float[]{px(28), px(28), px(28), px(28), 0, 0, 0, 0});
        card.setBackground(bg);

        // Her 3D self: robot Sweety or the VRM character you made
        avatar = new AvatarView(this);
        int avatarH = Math.round(getResources().getDisplayMetrics().heightPixels * .36f);
        card.addView(avatar.web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, avatarH));
        Bus.addSpeech(this);

        TextView title = new TextView(this); title.setText("Sweety"); title.setTextSize(22); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(0xFF211832);
        status = new TextView(this); status.setTextSize(16); status.setTextColor(0xFF6A5F80); status.setPadding(0, px(4), 0, px(4));
        heard = new TextView(this); heard.setTextSize(18); heard.setTextColor(0xFF211832); heard.setPadding(0, px(6), 0, px(10));

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this); input.setHint("Or type: set an alarm for 7 am"); input.setSingleLine(true); input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, e) -> { String s = input.getText().toString(); input.setText(""); if (!s.trim().isEmpty()) ask(s); return true; });
        mic = new TextView(this); mic.setText("Talk"); mic.setTextColor(Color.WHITE); mic.setTextSize(16); mic.setTypeface(Typeface.DEFAULT_BOLD); mic.setGravity(Gravity.CENTER);
        GradientDrawable mb = new GradientDrawable(); mb.setColor(0xFFE2468C); mb.setCornerRadius(px(24)); mic.setBackground(mb);
        mic.setOnClickListener(v -> { if (ears.listening()) ears.stop(); else startListening(); });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(84), px(48)); mp.leftMargin = px(10);
        row.addView(mic, mp);

        card.addView(title); card.addView(status); card.addView(heard); card.addView(row);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(card, cp);
        setContentView(root);

        // Summoned from the lock screen (holding power/home with Sweety as the assistant): show over it.
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }

        ears = new Hearing(this, this);
        String handed = getIntent().getStringExtra(EXTRA_ASK);
        if (handed != null) { status.setText("One moment…"); ask(handed); return; }
        status.setText("Listening… try \"remind me in 10 minutes to drink water\"");
        listenFor(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String handed = intent.getStringExtra(EXTRA_ASK);
        if (handed != null) { ask(handed); return; }
        status.setText("Listening…");
        listenFor(intent);
    }

    /** Opened by "Hey Sweety" (after her chirp), by the assistant button, or by long-pressing the floating Sweety. */
    private void listenFor(Intent i) {
        boolean woke = i.getBooleanExtra(EXTRA_WOKE, false);
        boolean assist = Intent.ACTION_ASSIST.equals(i.getAction()) || Intent.ACTION_VOICE_COMMAND.equals(i.getAction());
        android.util.Log.i("SweetyTalk", "listening (" + (woke ? "Hey Sweety" : assist ? "assistant button" : "tap") + ")");
        if (woke) { Bus.sweety(null, "wave", "curious"); h.postDelayed(this::startListening, 450); } // let her chirp finish first
        else startListening();
    }

    private int px(float v) { return Math.round(v * dp); }

    private void startListening() {
        Assistant.get(this).stopTalking();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        ears.listen();
    }

    private void ask(String text) {
        heard.setText("“" + text + "”");
        h.removeCallbacksAndMessages(null);
        Assistant.get(this).handle(text, this);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) ears.listen();
            else status.setText("I need the microphone to hear you. You can type instead.");
        }
    }

    /* Ears.Callback */
    @Override public void onListening(boolean on) {
        mic.setText(on ? "Stop" : "Talk");
        if (on) { status.setText("Listening…"); Bus.sweety(null, null, "curious"); avatar.state("listening"); }
    }
    @Override public void onPartial(String text) { heard.setText(text + "…"); }
    @Override public void onHeard(String text) { ask(text); }
    @Override public void onProblem(String message) { status.setText(message); avatar.state("idle"); avatar.mood("curious", 2.5f); }
    @Override public void onLevel(float level) { avatar.level(level); }

    /* Bus.SpeechListener: her voice drives her mouth */
    @Override public void onSpeech(float[] loud, float[] bright, int fps) { avatar.speak(loud, bright, fps); }
    @Override public void onSpeechFor(long ms) { avatar.speakFor(ms); }
    @Override public void onSpeechStop() { avatar.stop(); }

    /* Assistant.Host */
    @Override public void say(String text) {
        status.setText(text);
        Bus.sweety(text, null, null);
        h.postDelayed(this::closeWhenQuiet, Math.max(3500, text.length() * 75L));
    }

    /** Closes the card once she has finished talking (long answers in her natural voice take a while). */
    private void closeWhenQuiet() {
        if (Assistant.get(this).talking() || ears.listening()) h.postDelayed(this::closeWhenQuiet, 500);
        else h.postDelayed(this::finish, 1200);
    }
    @Override public void cue(String action, String emotion) {
        Bus.sweety(null, action, emotion);
        if ("nod".equals(action) && "curious".equals(emotion)) { avatar.state("thinking"); return; } // "let me think"
        avatar.state("idle");
        avatar.gesture(action);
        avatar.mood(emotion, 3.5f);
    }
    @Override public Activity activity() { return this; }

    @Override
    public void pickContact(String forName, Assistant.NumberCallback cb) {
        h.removeCallbacksAndMessages(null);
        contactCb = cb;
        try { startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT); }
        catch (Exception e) { contactCb = null; cb.onNumber(null); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CONTACT || contactCb == null) return;
        Assistant.NumberCallback cb = contactCb; contactCb = null;
        cb.onNumber(res == RESULT_OK && data != null ? numberFrom(this, data.getData()) : null);
    }

    static String numberFrom(Activity a, Uri uri) {
        if (uri == null) return null;
        try (Cursor c = a.getContentResolver().query(uri, new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    @Override
    protected void onDestroy() {
        Bus.removeSpeech(this);
        if (avatar != null) avatar.destroy();
        if (ears != null) ears.destroy();
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
