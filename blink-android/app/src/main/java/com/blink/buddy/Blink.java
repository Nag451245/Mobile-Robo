package com.blink.buddy;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Random;

/**
 * Blink's world: position, animation, emotions, props, speech bubble, and the reactions to phone events.
 * Hosted by the live wallpaper engine (home + lock screen) and by the preview view in the app.
 */
final class Blink {
    static final int ROOTY = 0, SPIN = 1, SX = 2, SY = 3, BODY = 4, HX = 5, HY = 6, HZ = 7, AL = 8, AR = 9, ALX = 10, ARX = 11, ROLL = 12, RX = 13;
    static final int GROUND = 0, FLY = 1, HOVER = 2, FALL = 3;
    private static final float TAU = (float) (Math.PI * 2);

    final Context ctx;
    final SharedPreferences prefs;
    final float dp;
    final Painter painter = new Painter();
    private final Scene scene = new Scene();
    private final Random rnd = new Random();

    int W = 1080, H = 2200, pages = 1;
    float u = 40, groundY = 1700, scroll = 0;
    Skin skin;
    private Skin pendingSkin, daySkin;

    // position (world x: includes home-screen scroll) and movement
    float x, y, vx, vy;
    int mode = GROUND;
    private boolean hasTarget;
    private float targetX, speedMul = 1, flyX, flyY, hoverUntil = -1;
    private Runnable arrive;
    private int arriveSeq;

    // pose and expression
    float face, tilt, vanish = 1, heartPulse = 1, walk, fly, t, open = 1, lookX, lookY;
    final float[] cur = {0, 0, 1, 1, .72f, 0, 0, 0, -.2f, .2f, 0, 0, 0, 0};
    private final float[] tg = new float[14];
    String emo = "neutral";
    private String baseEmo = "neutral", action;
    private float emoUntil = Float.MAX_VALUE, aT, aDur, blinkNext = 2, blinkT = -1, lookTX, lookTY, lookAt;
    private boolean sleeping, swapped;
    boolean headphones;

    // phone state
    boolean visible = true, locked, charging, headphonesConnected;
    private boolean lowWarned, pendingMail, pendingHello;
    private long unlockedSince, appLeftAt, lastNudge, lastMail, lastBedNote;
    private float nextIdle = 6, lastTapT = -9, petDist, petCool, lastTouchT = -9, touchX, touchY, nextMinute = 0;

    // bubble and props
    private String bubble;
    private float bubbleUntil;
    private StaticLayout bubbleLayout;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path path = new Path();

    private static final class Prop { String kind, text; float x, y, vx, vy, life, max, size; int follow, color; }
    private final ArrayList<Prop> props = new ArrayList<>();

    private static final class Tm { float at; Runnable fn; int seq; }
    private final ArrayList<Tm> timers = new ArrayList<>();
    private int seq;

    // background cache
    private Bitmap bg;
    private boolean bgDirty = true, bgNight;

    Blink(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences("blink", Context.MODE_PRIVATE);
        this.dp = ctx.getResources().getDisplayMetrics().density;
        skin = Skin.byId(prefs.getString("skin", "bubblegum"));
        tp.setTextSize(15 * dp);
        tp.setColor(0xFF2A1D40);
        x = W * .5f;
        y = groundY;
    }

    /* ------------------------------------------------------------ helpers */
    private static float sin(double a) { return (float) Math.sin(a); }
    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }
    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    private static float smooth(float k) { k = clamp(k, 0, 1); return k * k * (3 - 2 * k); }
    private static float easeInOut(float k) { return k < .5f ? 2 * k * k : 1 - (float) Math.pow(-2 * k + 2, 2) / 2; }
    private static float backOut(float k) { float c1 = 1.70158f, c3 = c1 + 1; return 1 + c3 * (float) Math.pow(k - 1, 3) + c1 * (float) Math.pow(k - 1, 2); }
    private float rand(float a, float b) { return a + rnd.nextFloat() * (b - a); }
    private <T> T pick(T[] a) { return a[rnd.nextInt(a.length)]; }
    private boolean on(String id) { return Moments.on(prefs, id); }
    float hPx() { return 2.7f * u; }
    float screenX() { return x - scroll; }
    private static int hour() { return Calendar.getInstance().get(Calendar.HOUR_OF_DAY); }
    private static int dayKey() { Calendar c = Calendar.getInstance(); return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR); }
    private static boolean night() { int h = hour(); return h >= 22 || h < 6; }

    private void later(float s, Runnable r) { Tm m = new Tm(); m.at = t + s; m.fn = r; m.seq = seq; timers.add(m); }

    private void interrupt() {
        seq++;
        timers.clear();
        hasTarget = false;
        arrive = null;
        speedMul = 1;
        vanish = Math.max(vanish, .05f);
        if (mode == FLY) mode = y >= groundY - 2 ? GROUND : HOVER;
        if (action != null && !"change".equals(action)) stopAction();
    }

    private void stopAction() {
        if ("change".equals(action) && pendingSkin != null) { applySkin(pendingSkin); pendingSkin = null; }
        if ("spin".equals(action) || "change".equals(action)) cur[SPIN] = 0;
        cur[ROLL] = 0; cur[RX] = 0;
        action = null;
    }

    void play(String name, float dur) {
        if ("change".equals(action) && pendingSkin != null) { applySkin(pendingSkin); pendingSkin = null; cur[SPIN] = 0; }
        action = name; aT = 0; aDur = dur; swapped = false;
        if (sleeping) sleeping = false;
    }

    void play(String name) {
        float d;
        switch (name) {
            case "wave": d = 2.4f; break; case "dance": d = 4.2f; break; case "jump": d = 1.1f; break; case "spin": d = 1.3f; break;
            case "stretch": d = 2.6f; break; case "laugh": d = 2.2f; break; case "shrug": d = 1.8f; break; case "nod": d = 1.4f; break;
            case "giggle": d = 1.2f; break; case "change": d = 1.2f; break; case "flip": d = 1.3f; break; case "cartwheel": d = 1.7f; break; default: d = 2f;
        }
        play(name, d);
    }

    void setEmotion(String e, float dur) { emo = e; emoUntil = dur > 0 ? t + dur : Float.MAX_VALUE; }

    void say(String text, float secs) {
        bubble = text; bubbleUntil = t + secs;
        int maxW = (int) Math.min(260 * dp, W * .7f);
        bubbleLayout = StaticLayout.Builder.obtain(text, 0, text.length(), tp, maxW).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
    }

    private void applySkin(Skin s) { skin = s; bgDirty = true; }

    void wear(Skin s, boolean save) {
        if (s == null) return;
        if (save) prefs.edit().putString("skin", s.id).apply();
        if (!visible) { applySkin(s); return; }
        pendingSkin = s;
        confetti(screenX(), y - hPx() * .5f, 24);
        play("change");
        setEmotion("happy", 2.2f);
    }

    private Skin randomSkin() {
        Skin s;
        do { s = Skin.ALL[rnd.nextInt(Skin.ALL.length)]; } while (s == skin || "nightcap".equals(s.id));
        return s;
    }

    private void walkTo(float wx, Runnable then) { hasTarget = true; targetX = wx; arrive = then; arriveSeq = seq; if (mode != GROUND) flyTo(wx, groundY, then); }
    private void flyTo(float wx, float wy, Runnable then) {
        hasTarget = false; mode = FLY;
        flyX = clamp(wx, scroll + u * 1.3f, scroll + W - u * 1.3f);
        flyY = clamp(wy, hPx() + 70 * dp, groundY);
        arrive = then; arriveSeq = seq;
    }
    private void goTo(float wx, float wy, Runnable then) { if (mode == GROUND && wy >= groundY - 4) walkTo(wx, then); else flyTo(wx, wy, then); }
    private void doArrive() { Runnable r = arrive; arrive = null; if (r != null && arriveSeq == seq) r.run(); }

    /* ------------------------------------------------------------ size and host events */
    void setSize(int w, int h) {
        boolean first = W != w || H != h;
        W = w; H = h;
        u = Math.min(Math.min(w, h) * .30f, 150 * dp) / 2.7f;
        groundY = h * .79f;
        if (mode == GROUND) y = groundY;
        if (first) { x = scroll + w * .5f; y = groundY; bgDirty = true; }
    }

    void onOffsets(float xOffset, float xStep) {
        pages = xStep > 0 && xStep < 1 ? Math.round(1f / xStep) + 1 : 1;
        float ns = xOffset * (pages - 1) * W;
        float delta = ns - scroll;
        if (Math.abs(delta) < 1) return;
        scroll = ns;
        if (!on("page-walk") || mode != GROUND || sleeping) { x += delta; return; }
        float sx = screenX();
        if (sx < -u || sx > W + u || Math.abs(delta) > W * .4f) {
            final int my = seq;
            later(.25f, () -> {
                if (my != seq) return;
                speedMul = 3.2f;
                if (rnd.nextFloat() < .4f) say(pick(new String[]{"Wait for me!", "Coming!", "Hey, not so fast!"}), 1.6f);
                walkTo(scroll + W * rand(.35f, .65f), () -> { speedMul = 1; play("giggle", .6f); });
            });
        }
    }

    void onVisibility(boolean v, boolean keyguardLocked) {
        boolean was = visible;
        visible = v;
        locked = keyguardLocked;
        long now = System.currentTimeMillis();
        if (!v) {
            if (!locked && unlockedSince > 0) appLeftAt = now;
            return;
        }
        if (was) return;
        bgDirty = bgDirty || bgNight != night();
        Skin want = Skin.byId(prefs.getString("skin", "bubblegum"));
        if (want != skin && !"nightcap".equals(skin.id) && pendingSkin == null) applySkin(want);
        headphones = headphonesConnected && on("headphones");
        if (pendingHello && !locked) { pendingHello = false; greetUnlock(); return; }
        if (appLeftAt > 0 && !locked) {
            long gone = now - appLeftAt;
            appLeftAt = 0;
            if (maybeNudge()) return;
            if (on("see-you")) {
                if (gone < 20000) { play("jump"); setEmotion("surprised", 1.2f); say(pick(new String[]{"Back already?", "That was quick!", "Forgot something?"}), 2); }
                else if (gone > 10 * 60000) { play("wave"); setEmotion("happy", 2); say(pick(new String[]{"That was a long one!", "Welcome back, explorer!", "I kept your spot warm."}), 2.6f); }
                else if (rnd.nextFloat() < .35f) { play("wave"); setEmotion("happy", 1.6f); }
            }
        }
        if (pendingMail) { pendingMail = false; mailCarrier(); }
    }

    void onScreenOff() {
        interrupt();
        sleeping = true;
        emoUntil = Float.MAX_VALUE;
        unlockedSince = 0;
        appLeftAt = 0;
        locked = true;
        mode = GROUND;
        y = groundY;
        x = scroll + W * .5f;
        bubble = null;
    }

    void onScreenOn(boolean keyguardLocked) {
        locked = keyguardLocked;
        if (!keyguardLocked) return;
        if (on("wake-yawn")) {
            interrupt();
            sleeping = true;
            later(.9f, () -> {
                sleeping = false;
                setEmotion("sleepy", 1.6f);
                say(pick(new String[]{"Mm… five more minutes…", "Huh? Oh, hi!", "*yawn* Morning already?"}), 2.2f);
                later(1.6f, () -> { play("stretch"); setEmotion("happy", 2); });
            });
        } else sleeping = false;
        if (pendingMail) { pendingMail = false; later(3.2f, this::mailCarrier); }
    }

    void onUnlock() {
        long now = System.currentTimeMillis();
        locked = false;
        long last = prefs.getLong("lastUnlock", 0);
        int day = dayKey();
        boolean firstOfDay = prefs.getInt("unlockDay", 0) != day;
        int count = firstOfDay ? 1 : prefs.getInt("unlockCount", 0) + 1;
        prefs.edit().putLong("lastUnlock", now).putInt("unlockDay", day).putInt("unlockCount", count).apply();
        unlockedSince = now;
        appLeftAt = 0;
        sleeping = false;
        greetLast = last; greetFirst = firstOfDay; greetCount = count;
        if (visible) greetUnlock(); else pendingHello = true;
    }

    private long greetLast; private boolean greetFirst; private int greetCount;

    private void greetUnlock() {
        long now = System.currentTimeMillis();
        int h = hour();
        interrupt();
        sleeping = false;
        bedtimeSkin();
        if (on("missed-you") && greetLast > 0 && now - greetLast > 3 * 3600000L) {
            x = scroll - u * 2; mode = GROUND; y = groundY; speedMul = 2.2f;
            walkTo(scroll + W * .5f, () -> {
                speedMul = 1; play("jump"); setEmotion("love", 3);
                say(pick(new String[]{"I missed you!", "You're back! Yay!", "There you are! I waited forever."}), 2.6f);
                for (int i = 0; i < 3; i++) floatProp("heart", screenX() + rand(-u, u), y - hPx(), 0xFFFF6FAE);
            });
        } else if (on("morning") && greetFirst && h >= 5 && h < 12) {
            floatProp("sun", W - 60 * dp, H * .2f, 0xFFFFC93C);
            play("stretch"); setEmotion("happy", 3);
            say(pick(new String[]{"Good morning, sunshine!", "Rise and shine!", "Morning! Ready for today?"}), 2.8f);
        } else if (on("unlock-count") && (greetCount == 25 || greetCount == 50 || greetCount == 75 || greetCount == 100 || greetCount == 150)) {
            play("nod"); setEmotion("curious", 3);
            sign(greetCount + " peeks!", 3.5f);
            say(greetCount >= 75 ? "That's a lot of peeks today. Phone break?" : "That's " + greetCount + " unlocks today. Coffee break?", 3.4f);
        } else if (on("bedtime") && night() && System.currentTimeMillis() - lastBedNote > 45 * 60000L) {
            lastBedNote = System.currentTimeMillis();
            setEmotion("sleepy", 3); play("stretch");
            say(pick(new String[]{"Phone down soon? Sweet dreams.", "It's late… bedtime?", "*yawn* Time to rest those eyes."}), 3);
        } else if (on("unlock-hello")) {
            play("jump", .6f);
            later(.6f, () -> { play("wave"); setEmotion("happy", 2.4f); });
            say(pick(new String[]{"Welcome back!", "Hi again!", "There you are!", "Hello, friend!", "Missed me?"}), 2.2f);
        }
        if (on("outfit-day") && greetFirst && !night()) {
            later(3.6f, () -> { wear(randomSkin(), true); later(.8f, () -> say("New outfit for today!", 2)); });
        }
        if (on("headphones") && headphonesConnected) headphones = true;
    }

    private boolean maybeNudge() {
        long now = System.currentTimeMillis();
        if (!on("break") || unlockedSince == 0) return false;
        if (now - unlockedSince > 30 * 60000L && now - lastNudge > 30 * 60000L) {
            lastNudge = now;
            interrupt(); play("stretch"); setEmotion("sleepy", 1.2f);
            later(1.3f, () -> setEmotion("happy", 2));
            say("You've been on your phone for 30 minutes. Stretch break?", 3.6f);
            return true;
        }
        return false;
    }

    void onPower(boolean connected) {
        boolean was = charging;
        charging = connected;
        if (connected && !was && on("charging") && visible) {
            interrupt(); setEmotion("love", 3); play("giggle");
            floatProp("bolt", screenX() - u * 1.4f, y - hPx() * .7f, 0xFFFFD447);
            say(pick(new String[]{"Mmm, fresh electrons!", "Snack time!", "Yum, power!"}), 2.6f);
            lowWarned = false; baseEmo = "neutral";
        }
    }

    void onBattery(int pct) {
        if (pct < 0) return;
        if (pct <= 15 && !charging && !lowWarned && on("low-battery")) {
            lowWarned = true; baseEmo = "sleepy"; emo = "sleepy";
            if (visible) { say("So… sleepy… find a charger…", 3); floatProp("lowbat", screenX() - u * 1.4f, y - hPx() * .8f, 0xFFE04848); }
        } else if ((pct > 20 || charging) && lowWarned) { lowWarned = false; baseEmo = "neutral"; }
        if (pct >= 100 && charging && visible && rnd.nextFloat() < .02f) { play("jump"); say("100%! Feeling strong!", 2); }
    }

    void onHeadphones(boolean connected) {
        boolean was = headphonesConnected;
        headphonesConnected = connected;
        headphones = connected && on("headphones");
        if (connected && !was && headphones && visible) {
            interrupt(); play("dance"); setEmotion("happy", 4.2f);
            say(pick(new String[]{"Ooh, music time!", "This one's a banger.", "Turn it up!"}), 2.2f);
            later(.4f, () -> floatProp("note", screenX() + u * 1.2f, y - hPx(), 0xFF7B5CE0));
            later(1.2f, () -> floatProp("note", screenX() - u * 1.2f, y - hPx() * 1.1f, 0xFF7B5CE0));
        }
    }

    void onShake() {
        if (!on("shake") || !visible) return;
        if (mode == FALL) return;
        interrupt();
        sleeping = false;
        mode = FALL;
        vx = rand(-18, 18) * u;
        vy = -rand(16, 24) * u;
        setEmotion("surprised", 2);
        say(pick(new String[]{"Whoaaa!", "Earthquake!", "Wheee!"}), 1.4f);
    }

    void onNotification() {
        if (!on("mail")) return;
        long now = System.currentTimeMillis();
        if (now - lastMail < 20000) return;
        lastMail = now;
        if (!visible) { pendingMail = true; return; }
        mailCarrier();
    }

    private void mailCarrier() {
        interrupt();
        sleeping = false;
        boolean fromLeft = screenX() > W * .5f;
        x = scroll + (fromLeft ? -u * 1.5f : W + u * 1.5f);
        mode = GROUND; y = groundY; speedMul = 2;
        Prop env = followProp("mail", 2, 0xFFE2468C);
        walkTo(scroll + W * .5f, () -> {
            speedMul = 1; play("wave"); setEmotion("happy", 2.4f);
            say(pick(new String[]{"You've got a message!", "Special delivery!", "Something new arrived!"}), 2.6f);
            later(2.6f, () -> props.remove(env));
        });
    }

    void onTap(float sx, float sy) {
        lastTouchT = t; touchX = sx; touchY = sy;
        float wx = sx + scroll;
        if (overBlink(sx, sy)) {
            if (t - lastTapT < .4f) {
                lastTapT = -9;
                if (on("outfit-day")) { interrupt(); wear(randomSkin(), true); later(.8f, () -> say("How about this one?", 1.8f)); }
                else { play("jump"); setEmotion("happy", 1.5f); }
                return;
            }
            lastTapT = t;
            if (sleeping) { sleeping = false; play("stretch"); setEmotion("happy", 2); say("Oh! Hi!", 1.6f); return; }
            play("giggle"); setEmotion(rnd.nextFloat() < .3f ? "love" : "happy", 1.6f);
            say(pick(new String[]{"Hehe, that tickles!", "Boop received.", "You found me!"}), 1.6f);
            return;
        }
        if (!on("tap-hop") || locked) return;
        interrupt();
        sleeping = false;
        floatProp("tap", sx, sy, 0xFFFFFFFF);
        if (sy < groundY - hPx() * 1.2f) {
            flyTo(wx, sy + hPx() * .55f, () -> { play("giggle", .6f); setEmotion("happy", 1.6f); hoverUntil = t + 4; });
            say(pick(new String[]{"Wheee!", "On my way!"}), 1.2f);
        } else {
            speedMul = 2.2f;
            walkTo(wx, () -> { speedMul = 1; play("jump", .7f); setEmotion("happy", 1.4f); });
        }
    }

    void onTouch(int action, float sx, float sy) {
        lastTouchT = t; touchX = sx; touchY = sy;
        if (action != 2) { petDist = 0; return; } // 2 = MotionEvent.ACTION_MOVE
        if (!on("pet") || !overBlink(sx, sy)) return;
        petDist += 6 * dp;
        if (petDist > 140 * dp && t > petCool) {
            petDist = 0; petCool = t + 3;
            play("giggle"); setEmotion("love", 2.6f);
            say(pick(new String[]{"Purrrr…", "Hehe, more please!", "I like you."}), 1.8f);
            floatProp("heart", sx, sy - 30 * dp, 0xFFFF6FAE);
        }
    }

    boolean overBlink(float sx, float sy) {
        float cx = screenX(), top = y - hPx() * 1.05f - cur[ROOTY] * u;
        return sx > cx - u * 1.15f && sx < cx + u * 1.15f && sy > top && sy < y + u * .2f;
    }

    private void bedtimeSkin() {
        if (!on("bedtime")) return;
        if (night() && !"nightcap".equals(skin.id)) { daySkin = skin; wear(Skin.byId("nightcap"), false); }
        else if (!night() && "nightcap".equals(skin.id)) {
            Skin back = daySkin != null ? daySkin : Skin.byId(prefs.getString("skin", "bubblegum"));
            if ("nightcap".equals(back.id)) back = Skin.ALL[0];
            wear(back, false);
        }
    }

    /* ------------------------------------------------------------ props */
    private void floatProp(String kind, float sx, float sy, int color) {
        Prop p = new Prop(); p.kind = kind; p.x = sx; p.y = sy; p.vy = -50 * dp; p.max = 2.2f; p.size = 22 * dp; p.color = color;
        props.add(p);
    }
    private Prop followProp(String kind, int follow, int color) {
        Prop p = new Prop(); p.kind = kind; p.follow = follow; p.max = 999; p.size = 24 * dp; p.color = color;
        props.add(p); return p;
    }
    private void sign(String text, float life) {
        Prop p = new Prop(); p.kind = "sign"; p.text = text; p.follow = 1; p.max = life; p.size = 16 * dp; props.add(p);
    }
    private void confetti(float sx, float sy, int n) {
        int[] cols = {skin.shell, skin.accent, skin.inner, 0xFFFFFFFF, skin.trim, skin.blush};
        for (int i = 0; i < n; i++) {
            Prop p = new Prop(); p.kind = "dot"; p.x = sx; p.y = sy;
            float a = rand(0, TAU), sp = rand(3, 9) * u;
            p.vx = (float) Math.cos(a) * sp; p.vy = (float) Math.sin(a) * sp - 3 * u;
            p.max = rand(.5f, .9f); p.size = rand(.07f, .13f) * u; p.color = cols[i % cols.length];
            props.add(p);
        }
    }

    /* ------------------------------------------------------------ simulation */
    boolean busy() { return action != null || hasTarget || mode != GROUND || !props.isEmpty() || bubble != null || walk > .01f; }

    void step(float dt) {
        t += dt;
        if (!timers.isEmpty()) {
            ArrayList<Tm> due = new ArrayList<>();
            for (int i = timers.size() - 1; i >= 0; i--) if (timers.get(i).at <= t) due.add(0, timers.remove(i));
            for (Tm m : due) if (m.seq == seq) m.fn.run();
        }
        if (t > nextMinute) { nextMinute = t + 30; if (visible && !locked) bedtimeSkin(); if (bgNight != night()) bgDirty = true; }

        // idle life
        if (action == null && !hasTarget && mode == GROUND && timers.isEmpty() && !sleeping && t > nextIdle) {
            nextIdle = t + (locked ? rand(12, 24) : rand(8, 18));
            float r = rnd.nextFloat();
            if (locked) { if (r < .5f) play("nod"); else play("wave"); }
            else if (r < .45f) { speedMul = lowWarned ? .5f : 1; walkTo(scroll + W * rand(.2f, .8f), null); }
            else if (r < .6f) play("jump");
            else if (r < .75f) play("wave");
            else if (r < .85f) play("stretch");
            else { setEmotion("curious", 1.8f); play("shrug"); }
        }
        if (mode == HOVER && hoverUntil > 0 && t > hoverUntil && action == null) { hoverUntil = -1; flyTo(x, groundY, null); }

        // where to look
        if (t - lastTouchT < 3) { lookTX = clamp((touchX - screenX()) / (90 * dp), -1, 1); lookTY = clamp((touchY - (y - hPx() * .6f)) / (90 * dp), -1, 1); }
        else if (t > lookAt) { lookAt = t + rand(1.5f, 3.5f); lookTX = rand(-.8f, .8f); lookTY = rand(-.4f, .5f); }

        move(dt);
        pose(dt);

        for (int i = props.size() - 1; i >= 0; i--) {
            Prop p = props.get(i);
            p.life += dt;
            if (p.follow == 0) { p.x += p.vx * dt; p.y += p.vy * dt; if ("dot".equals(p.kind)) { p.vy += 12 * u * dt; p.vx *= 1 - dt * 2; } }
            if (p.life >= p.max) props.remove(i);
        }
        if (bubble != null && t > bubbleUntil) bubble = null;
    }

    private void move(float dt) {
        float tiltT = 0;
        float flyPose = 0;
        switch (mode) {
            case FALL: {
                vy += 65 * u * dt; x += vx * dt; y += vy * dt; vx *= 1 - dt * .5f;
                float sx = screenX(), half = u * 1.1f;
                if (sx < half) { x = scroll + half; vx = Math.abs(vx) * .5f; }
                if (sx > W - half) { x = scroll + W - half; vx = -Math.abs(vx) * .5f; }
                float top = hPx() + 60 * dp;
                if (y < top && vy < 0) { y = top; vy = -vy * .4f; }
                face = clamp(vx / (20 * u), -.8f, .8f);
                if (y >= groundY) {
                    y = groundY; float imp = vy; vx = 0; vy = 0; mode = GROUND;
                    if (imp > 25 * u) { play("giggle", .6f); setEmotion("dizzy", 2.4f); say(pick(new String[]{"Whoa… the room is spinning.", "Again! …or not.", "Stuck the landing. Mostly."}), 2); }
                    else { play("giggle", .6f); setEmotion("happy", 1.4f); }
                }
                break;
            }
            case FLY: {
                float dx = flyX - x, dy = flyY - y, d = (float) Math.hypot(dx, dy);
                float step = Math.min(11 * u, 3 * u + d * 1.8f) * dt;
                flyPose = 1;
                if (d <= step + .5f) {
                    x = flyX; y = flyY;
                    if (y >= groundY - 2) { y = groundY; mode = GROUND; play("giggle", .5f); } else mode = HOVER;
                    doArrive();
                } else {
                    x += dx / d * step; y += dy / d * step;
                    face = clamp(dx / d, -1, 1) * .85f; tiltT = clamp(dx / d, -1, 1) * .28f;
                    if (rnd.nextFloat() < dt * 18) floatPuff();
                }
                break;
            }
            case HOVER:
                flyPose = .6f; face = 0;
                if (rnd.nextFloat() < dt * 5) floatPuff();
                break;
            default: {
                if (hasTarget) {
                    float dx = targetX - x, step = 2.6f * u * speedMul * (lowWarned ? .5f : 1) * dt;
                    if (Math.abs(dx) <= step) { x = targetX; hasTarget = false; walk = 0; face = 0; doArrive(); }
                    else { x += Math.signum(dx) * step; walk = 1; face = Math.signum(dx) * .9f; }
                } else { walk = 0; face = 0; }
                y = groundY;
            }
        }
        if (mode != GROUND) walk = 0;
        fly = lerp(fly, flyPose, Math.min(1, dt * 6));
        tilt = lerp(tilt, tiltT, Math.min(1, dt * 6));
    }

    private void floatPuff() {
        Prop p = new Prop(); p.kind = "dot"; p.x = screenX() + rand(-.2f, .2f) * u; p.y = y;
        p.vx = rand(-.5f, .5f) * u; p.vy = rand(2, 3.5f) * u; p.max = rand(.35f, .6f); p.size = rand(.06f, .1f) * u;
        p.color = rnd.nextBoolean() ? 0xFFFFFFFF : skin.accent;
        props.add(p);
    }

    private void pose(float dt) {
        if (t > emoUntil) { emo = sleeping ? "sleep" : baseEmo; emoUntil = Float.MAX_VALUE; }
        if (sleeping && emoUntil == Float.MAX_VALUE) emo = "sleep";
        float br = sin(t * 2.2f);
        float[] T = tg;
        T[ROOTY] = 0; T[SPIN] = 0; T[ROLL] = 0; T[RX] = 0; T[SX] = 1; T[SY] = 1; T[BODY] = .72f + br * .022f;
        T[HX] = -lookY * .2f; T[HY] = lookX * .42f; T[HZ] = sin(t * .9f) * .05f;
        T[AL] = -.2f - br * .05f; T[AR] = .2f + br * .05f; T[ALX] = 0; T[ARX] = 0;
        if (walk > 0) { float s = t * 11 * Math.min(2, speedMul); T[ROOTY] += Math.abs(sin(s)) * .08f * walk; T[ALX] = sin(s) * .7f * walk; T[ARX] = -sin(s) * .7f * walk; T[HZ] += sin(s) * .06f * walk; }
        if (fly > .01f) { T[AL] = lerp(T[AL], -1.3f, fly); T[AR] = lerp(T[AR], 1.3f, fly); T[ROOTY] += sin(t * 4) * .05f * fly; }
        if (sleeping) { T[BODY] = .68f + sin(t * 1.1f) * .02f; T[HX] = .3f; T[HY] = 0; T[HZ] = .16f; T[AL] = -.05f; T[AR] = .05f; }
        if (action != null) {
            aT += dt;
            float a = aT, p = Math.min(1, a / aDur);
            switch (action) {
                case "wave": { float up = smooth(a / .3f) * smooth((aDur - a) / .3f); T[AR] = .2f + up * (2.35f + sin(a * 12) * .35f); T[HZ] += up * .14f; break; }
                case "dance": { float s = a * 8; T[ROOTY] += Math.abs(sin(s)) * .16f; T[HY] += sin(a * 4) * .5f; T[AL] = -.4f - (sin(s) * .5f + .5f) * 1.6f; T[AR] = .4f + ((float) Math.cos(s) * .5f + .5f) * 1.6f; T[HZ] += sin(a * 4) * .2f; break; }
                case "jump": {
                    if (p < .18f) { T[SY] = 1 - .18f * sin(p / .18f * Math.PI); T[SX] = 1 + (1 - T[SY]) * .6f; }
                    else if (p < .82f) { float q = (p - .18f) / .64f, kk = sin(q * Math.PI); T[ROOTY] += kk * 1.1f; T[SY] = 1 + .08f * kk; T[SX] = 1 - .04f * kk; T[AL] = -.2f - 2.2f * kk; T[AR] = .2f + 2.2f * kk; }
                    else { float q = (p - .82f) / .18f; T[SY] = 1 - .14f * sin(q * Math.PI); T[SX] = 1 + (1 - T[SY]) * .6f; }
                    break;
                }
                case "spin": { T[SPIN] = easeInOut(p) * TAU; cur[SPIN] = T[SPIN]; float kk = sin(p * Math.PI); T[ROOTY] += kk * .3f; T[AL] = -.2f - 1.2f * kk; T[AR] = .2f + 1.2f * kk; break; }
                case "change": {
                    T[SPIN] = easeInOut(p) * TAU; cur[SPIN] = T[SPIN];
                    float kk = p < .5f ? 1 - smooth(p / .5f) * .8f : .2f + .8f * backOut((p - .5f) / .5f);
                    T[SX] = kk; T[SY] = kk; cur[SX] = kk; cur[SY] = kk; T[ROOTY] += sin(p * Math.PI) * .45f; T[AL] = -1.4f; T[AR] = 1.4f;
                    if (p >= .5f && !swapped) { swapped = true; if (pendingSkin != null) { applySkin(pendingSkin); pendingSkin = null; } confetti(screenX(), y - hPx() * .5f, 18); }
                    break;
                }
                case "stretch": { float up = smooth(a / .5f) * smooth((aDur - a) / .5f); T[AL] = -.2f - up * 2.75f; T[AR] = .2f + up * 2.75f; T[SY] = 1 + up * .06f; T[HX] = -.25f * up; break; }
                case "laugh": { T[BODY] += Math.abs(sin(a * 18)) * .05f; T[HX] = -.15f; T[HZ] += sin(a * 9) * .07f; T[AL] = -.5f; T[AR] = .5f; break; }
                case "shrug": { float kk = sin(p * Math.PI); T[AL] = -.2f - kk * .9f; T[AR] = .2f + kk * .9f; T[ALX] = -kk * .6f; T[ARX] = -kk * .6f; T[HZ] += kk * .25f; T[BODY] += kk * .04f; break; }
                case "giggle": { T[ROOTY] += Math.abs(sin(a * 14)) * .12f * (1 - p); T[HZ] += sin(a * 20) * .08f * (1 - p); break; }
                case "nod": { T[HX] = sin(p * Math.PI * 4) * .25f; break; }
                case "flip": {
                    if (p < .15f) { T[SY] = 1 - .2f * sin(p / .15f * Math.PI); T[SX] = 1 + (1 - T[SY]) * .6f; }
                    else if (p < .85f) { float q = (p - .15f) / .7f; T[ROOTY] += sin(q * Math.PI) * 1.4f; T[ROLL] = -easeInOut(q) * TAU; cur[ROLL] = T[ROLL]; T[AL] = -2.4f; T[AR] = 2.4f; T[SY] = .92f; }
                    else { float q = (p - .85f) / .15f; T[SY] = 1 - .15f * sin(q * Math.PI); T[SX] = 1 + (1 - T[SY]) * .6f; T[AL] = -1; T[AR] = 1; }
                    break;
                }
                case "cartwheel": { T[ROLL] = easeInOut(p) * TAU; cur[ROLL] = T[ROLL]; T[RX] = sin(p * Math.PI) * 1.2f; T[ROOTY] += sin(p * Math.PI) * .3f; T[AL] = -2.1f; T[AR] = 2.1f; break; }
                default: break;
            }
            if (action != null && aT >= aDur) {
                String done = action; action = null;
                if ("spin".equals(done) || "change".equals(done)) { cur[SPIN] -= TAU; T[SPIN] = 0; }
                if ("flip".equals(done)) { cur[ROLL] += TAU; T[ROLL] = 0; }
                if ("cartwheel".equals(done)) { cur[ROLL] -= TAU; T[ROLL] = 0; }
                if ("change".equals(done) && pendingSkin != null) { applySkin(pendingSkin); pendingSkin = null; }
            }
        }
        float k = 1 - (float) Math.exp(-dt * (action != null ? 30 : 9));
        for (int i = 0; i < 14; i++) cur[i] = lerp(cur[i], T[i], k);

        if (blinkT < 0 && t > blinkNext) blinkT = 0;
        open = 1;
        if (blinkT >= 0) { blinkT += dt; float b = blinkT / .16f; open = Math.abs(1 - 2 * Math.min(1, b)); if (b >= 1) { blinkT = -1; blinkNext = t + rand(2, 6); open = 1; } }
        float kl = Math.min(1, dt * 7);
        lookX += (lookTX - lookX) * kl; lookY += (lookTY - lookY) * kl;
        float pulse = "sleep".equals(emo) || "sleepy".equals(emo) ? .1f : (float) Math.pow(Math.max(0, sin(t * 3)), 6);
        heartPulse = 1 + .18f * pulse + (charging ? .1f * sin(t * 2) : 0);
    }

    /* ------------------------------------------------------------ drawing */
    private void buildBackground() {
        if (bg == null || bg.getWidth() != W || bg.getHeight() != H) {
            if (bg != null) bg.recycle();
            bg = Bitmap.createBitmap(Math.max(1, W), Math.max(1, H), Bitmap.Config.ARGB_8888);
        }
        bgNight = night();
        Canvas c = new Canvas(bg);
        int top = Painter.mix(skin.shell, 0xFFFFF7FB, .55f), bottom = Painter.mix(skin.shell, 0xFFEEE6FF, .8f);
        if (bgNight) { top = Painter.mix(top, 0xFF221B44, .7f); bottom = Painter.mix(bottom, 0xFF120E2A, .75f); }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, H, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        p.setColor(bgNight ? 0x33FFFFFF : 0x66FFFFFF);
        float g = 26 * dp, r = 1.6f * dp;
        for (float yy = g / 2; yy < H; yy += g) for (float xx = g / 2; xx < W; xx += g) c.drawCircle(xx, yy, r, p);
        if (bgNight) { p.setColor(0x99FFFFFF); Random rr = new Random(7); for (int i = 0; i < 40; i++) c.drawCircle(rr.nextFloat() * W, rr.nextFloat() * H * .6f, (1 + rr.nextFloat() * 1.5f) * dp, p); }
        bgDirty = false;
    }

    void draw(Canvas c) {
        scene.draw(c, skin.id, W, H, groundY, t, dp, night());
        float sx = screenX();
        float bob = mode == HOVER ? sin(t * 2.2f) * 5 * dp : 0;
        float headTop = y + bob - hPx() * cur[SY] - cur[ROOTY] * u;

        if (charging && on("charging") && vanish > .5f) {
            pp.setStyle(Paint.Style.STROKE); pp.setStrokeCap(Paint.Cap.ROUND); pp.setStrokeWidth(5 * dp); pp.setColor(0xFF3A3150);
            path.reset(); path.moveTo(sx + u * .2f, y + bob - u * .7f);
            path.cubicTo(sx + u * 1.4f, y + bob - u * .4f, sx + u * 1.2f, H - 40 * dp, sx + u * 1.6f, H + 10);
            c.drawPath(path, pp);
            pp.setStyle(Paint.Style.FILL);
        }

        painter.drawRobot(c, this, sx, y + bob);

        for (Prop p : props) drawProp(c, p, sx, y + bob, headTop);

        if (bubble != null && bubbleLayout != null) {
            float pad = 10 * dp, bh = bubbleLayout.getHeight(), bw = 0;
            for (int i = 0; i < bubbleLayout.getLineCount(); i++) bw = Math.max(bw, bubbleLayout.getLineWidth(i));
            float extra = skin.has("bunnyEars") || skin.has("nightcap") ? u * .9f : skin.has("antenna") ? u * .4f : 0;
            float bx = clamp(sx - bw / 2 - pad, 12 * dp, W - bw - pad * 2 - 12 * dp);
            float by = Math.max(56 * dp, headTop - extra - bh - pad * 2 - 12 * dp);
            pp.setStyle(Paint.Style.FILL); pp.setColor(0x33281446);
            rf.set(bx, by + 3 * dp, bx + bw + pad * 2, by + bh + pad * 2 + 3 * dp); c.drawRoundRect(rf, 16 * dp, 16 * dp, pp);
            pp.setColor(0xFFFFFFFF); rf.offset(0, -3 * dp); c.drawRoundRect(rf, 16 * dp, 16 * dp, pp);
            pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(2 * dp); pp.setColor(0xFFF3C6DC); c.drawRoundRect(rf, 16 * dp, 16 * dp, pp);
            pp.setStyle(Paint.Style.FILL);
            c.save(); c.translate(bx + pad, by + pad); bubbleLayout.draw(c); c.restore();
        }
    }

    private void drawProp(Canvas c, Prop p, float sx, float fy, float headTop) {
        float px = p.x, py = p.y, a = 1;
        if (p.follow == 1) { px = sx; py = headTop - 34 * dp; }
        else if (p.follow == 2) { px = sx + u * 1.05f; py = fy - u * 1.0f; }
        if (p.follow == 0 && !"dot".equals(p.kind)) a = Math.max(0, 1 - p.life / p.max);
        int alpha = (int) (255 * a);
        float s = p.size;
        pp.setStyle(Paint.Style.FILL);
        switch (p.kind) {
            case "dot":
                pp.setColor(p.color); pp.setAlpha((int) (255 * Math.max(0, 1 - p.life / p.max)));
                c.drawCircle(px, py, s, pp); break;
            case "heart":
                painter.face.heart(c, px, py, s * .55f, (alpha << 24) | (p.color & 0xFFFFFF)); break;
            case "tap":
                pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(3 * dp); pp.setColor(0xFFFFFFFF); pp.setAlpha(alpha);
                c.drawCircle(px, py, s * (.6f + p.life), pp); pp.setStyle(Paint.Style.FILL); break;
            case "sign": {
                pp.setTextSize(s); pp.setFakeBoldText(true);
                float tw = pp.measureText(p.text), pad = 8 * dp;
                rf.set(px - tw / 2 - pad, py - s - pad, px + tw / 2 + pad, py + pad * .6f);
                pp.setColor(0xFFFFF6D6); c.drawRoundRect(rf, 8 * dp, 8 * dp, pp);
                pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(2 * dp); pp.setColor(0xFFE3B95A); c.drawRoundRect(rf, 8 * dp, 8 * dp, pp);
                pp.setStyle(Paint.Style.FILL); pp.setColor(0xFF5A3A00); pp.setTextAlign(Paint.Align.CENTER);
                c.drawText(p.text, px, py - pad * .2f, pp); pp.setTextAlign(Paint.Align.LEFT); pp.setFakeBoldText(false);
                break;
            }
            case "mail": {
                rf.set(px - s * .8f, py - s * .55f, px + s * .8f, py + s * .55f);
                pp.setColor(0xFFFFFFFF); c.drawRoundRect(rf, 3 * dp, 3 * dp, pp);
                pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(2.2f * dp); pp.setColor(p.color); c.drawRoundRect(rf, 3 * dp, 3 * dp, pp);
                path.reset(); path.moveTo(rf.left, rf.top); path.lineTo(px, py + s * .1f); path.lineTo(rf.right, rf.top); c.drawPath(path, pp);
                pp.setStyle(Paint.Style.FILL); break;
            }
            case "bolt": {
                path.reset(); path.moveTo(px + s * .2f, py - s); path.lineTo(px - s * .6f, py + s * .1f); path.lineTo(px - s * .05f, py + s * .1f);
                path.lineTo(px - s * .2f, py + s); path.lineTo(px + s * .6f, py - s * .15f); path.lineTo(px + s * .05f, py - s * .15f); path.close();
                pp.setColor(p.color); pp.setAlpha(alpha); c.drawPath(path, pp); break;
            }
            case "lowbat": {
                pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(3 * dp); pp.setColor(p.color); pp.setAlpha(alpha);
                rf.set(px - s * .8f, py - s * .45f, px + s * .7f, py + s * .45f); c.drawRoundRect(rf, 3 * dp, 3 * dp, pp);
                pp.setStyle(Paint.Style.FILL); rf.set(px - s * .65f, py - s * .3f, px - s * .35f, py + s * .3f); c.drawRect(rf, pp);
                rf.set(px + s * .72f, py - s * .2f, px + s * .85f, py + s * .2f); c.drawRect(rf, pp); break;
            }
            case "note": {
                pp.setColor(p.color); pp.setAlpha(alpha);
                c.drawCircle(px - s * .3f, py + s * .5f, s * .28f, pp);
                pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(3 * dp);
                c.drawLine(px - s * .04f, py + s * .5f, px - s * .04f, py - s * .6f, pp);
                c.drawLine(px - s * .04f, py - s * .6f, px + s * .4f, py - s * .4f, pp);
                pp.setStyle(Paint.Style.FILL); break;
            }
            case "sun": {
                pp.setColor(p.color); pp.setAlpha(alpha); c.drawCircle(px, py, s * .55f, pp);
                pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(3 * dp);
                for (int i = 0; i < 8; i++) { double ang = i * Math.PI / 4 + t; c.drawLine(px + (float) Math.cos(ang) * s * .8f, py + (float) Math.sin(ang) * s * .8f, px + (float) Math.cos(ang) * s * 1.1f, py + (float) Math.sin(ang) * s * 1.1f, pp); }
                pp.setStyle(Paint.Style.FILL); break;
            }
            default: break;
        }
        pp.setAlpha(255);
    }

    /** Animation cue from the assistant: an action name (or "outfit:id", "sleep") plus an emotion. */
    void cue(String act, String emotion) {
        if (act != null) {
            if (act.startsWith("outfit:")) { interrupt(); wear(Skin.byId(act.substring(7)), true); }
            else if ("sleep".equals(act)) { interrupt(); sleeping = true; emoUntil = Float.MAX_VALUE; }
            else { sleeping = false; play(act); }
        }
        if (emotion != null && !"sleep".equals(act)) setEmotion(emotion, 2.6f);
    }

    /** Lets the preview screen simulate phone events. */
    void simulate(String what) {
        switch (what) {
            case "screen": onScreenOff(); onScreenOn(true); break;
            case "unlock": locked = false; greetLast = 0; greetFirst = false; greetCount = 1; greetUnlock(); break;
            case "missed": locked = false; greetLast = 1; greetFirst = false; greetCount = 1; greetUnlock(); break;
            case "charge": onPower(!charging); break;
            case "lowbat": lowWarned = false; boolean ch = charging; charging = false; onBattery(12); charging = ch; break;
            case "music": onHeadphones(!headphonesConnected); break;
            case "shake": onShake(); break;
            case "mail": lastMail = 0; onNotification(); break;
            case "outfit": interrupt(); wear(randomSkin(), true); break;
            case "swipe": onOffsets(scroll > 0 ? 0 : 1, .5f); break;
            case "nudge": unlockedSince = System.currentTimeMillis() - 31 * 60000L; lastNudge = 0; maybeNudge(); break;
            default: break;
        }
    }
}
