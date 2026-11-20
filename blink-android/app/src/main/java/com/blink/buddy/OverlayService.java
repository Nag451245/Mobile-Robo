package com.blink.buddy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Random;

/**
 * Floating Blink: the real 3D Blink in a small transparent window that walks, flies and perches on top of
 * the home screen and other apps, like the desktop version. Touches outside Blink go to the app underneath.
 */
public class OverlayService extends Service implements SensorEventListener, Bus.Listener, Bus.SweetyListener {
    static final String ACTION_STOP = "com.blink.buddy.STOP_FLOATING";
    static final String ACTION_HIDE = "com.blink.buddy.HIDE_FLOATING";
    static volatile boolean running;

    private static final String CHANNEL = "blink_floating";
    private static final int STAND = 0, WALK = 1, FALL = 2, DRAG = 3, FLY = 4;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final ArrayList<String> pending = new ArrayList<>();
    private final ArrayList<float[]> samples = new ArrayList<>();
    private final ArrayList<Long> pokes = new ArrayList<>();

    private WindowManager wm;
    private FrameLayout root;
    private WebView web;
    private TextView bubble;
    private WindowManager.LayoutParams lp, blp;
    private SharedPreferences prefs;
    private boolean ready, hidden, landscapeHidden;
    private float dp;
    private int sw, sh, topInset, botInset, winW, winH;

    private float x, y, vx, vy, tx, ty, speed = 1;
    private int mode = STAND;
    private Runnable arrive;
    private String lastMove = "";
    private long lastFrame, nextIdle, hideUntil, bubbleUntil, downT, lastTapT;
    private float downX, downY, offX, offY;
    private boolean dragging;
    private Runnable longPress;

    private SensorManager sensors;
    private float shakeLast;
    private long shakeAt;
    private AudioManager audio;
    private boolean headphones, charging, batterySeen;

    private final Runnable tick = this::tick;

    public class Bridge {
        @JavascriptInterface
        public void ready() {
            h.post(() -> {
                ready = true;
                for (String js : pending) web.evaluateJavascript(js, null);
                pending.clear();
            });
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null || root == null) return;
            switch (a) {
                case Intent.ACTION_USER_PRESENT:
                    js("B.sleep(false)");
                    if (!hidden && Moments.on(prefs, "unlock-hello")) {
                        act("B.play('jump',.6)"); h.postDelayed(() -> act("B.play('wave');B.emote('happy',2.4)"), 600);
                        say(pick("Welcome back!", "Hi again!", "There you are!", "Hello, friend!"), 2200);
                    }
                    break;
                case Intent.ACTION_SCREEN_OFF: js("B.sleep(true)"); break;
                case Intent.ACTION_POWER_CONNECTED:
                    charging = true;
                    if (!hidden && Moments.on(prefs, "charging")) { act("B.play('giggle');B.emote('love',3)"); say(pick("Mmm, fresh electrons!", "Snack time!", "Yum, power!"), 2400); }
                    break;
                case Intent.ACTION_POWER_DISCONNECTED: charging = false; break;
                default: break;
            }
        }
    };

    private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { checkHeadphones(true); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { checkHeadphones(false); }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) { stopSelf(); return START_NOT_STICKY; }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (root == null) setup();
        if (ACTION_HIDE.equals(a)) hideFor(3600_000L);
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Floating Sweety", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent hide = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_blink)
                .setContentTitle("Sweety is on your screen")
                .setContentText("Long-press Sweety to talk. Tap here for settings.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Hide for 1 hour", hide).build())
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    private int px(float v) { return Math.round(v * dp); }
    private float rand(float a, float b) { return a + rnd.nextFloat() * (b - a); }
    private String pick(String... a) { return a[rnd.nextInt(a.length)]; }
    private float groundY() { return sh - botInset - px(2); }

    private void measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics m = wm.getCurrentWindowMetrics();
            Rect b = m.getBounds();
            sw = b.width(); sh = b.height();
            Insets in = m.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            topInset = in.top; botInset = in.bottom;
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            sw = dm.widthPixels; sh = dm.heightPixels;
            topInset = dimen("status_bar_height"); botInset = dimen("navigation_bar_height");
        }
    }

    private int dimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void setup() {
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("blink", MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        measureScreen();
        float size = prefs.getInt("floatSize", 120);
        winW = px(size * .95f);
        winH = px(size * 1.35f);

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/overlay.html?skin=" + prefs.getString("skin", "bubblegum"));

        root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View touch = new View(this);
        touch.setOnTouchListener((v, e) -> onTouch(e));
        root.addView(touch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        lp = new WindowManager.LayoutParams(winW, winH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        x = prefs.getFloat("floatX", sw - px(80));
        y = prefs.getFloat("floatY", groundY());
        x = Math.max(winW * .4f, Math.min(sw - winW * .4f, x));
        y = Math.max(topInset + winH * .8f, Math.min(groundY(), y));
        place();
        wm.addView(root, lp);

        bubble = new TextView(this);
        bubble.setTextColor(0xFF2A1D40);
        bubble.setTextSize(14);
        bubble.setMaxWidth(px(220));
        bubble.setPadding(px(12), px(8), px(12), px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(px(16));
        bg.setStroke(px(2), 0xFFF3C6DC);
        bubble.setBackground(bg);
        bubble.setVisibility(View.GONE);
        // Not touchable, and at most 80% opaque so Android lets touches pass through it to the app below.
        blp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.alpha = .8f;
        wm.addView(bubble, blp);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor acc = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null) { headphones = anyHeadphones(); audio.registerAudioDeviceCallback(audioCallback, h); }
        Bus.add(this);
        Bus.addSweety(this);
        running = true;

        nextIdle = SystemClock.uptimeMillis() + 15000;
        landscapeHidden = sw > sh;
        if (landscapeHidden) root.setVisibility(View.GONE);
        h.post(tick);
        h.postDelayed(() -> { act("B.play('wave');B.emote('happy',2.5)"); say("Hi! Drag me anywhere. Double-tap me for a new outfit.", 3500); }, 1500);
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        if (sensors != null) sensors.unregisterListener(this);
        if (audio != null) audio.unregisterAudioDeviceCallback(audioCallback);
        Bus.remove(this);
        Bus.removeSweety(this);
        try { if (root != null) wm.removeView(root); } catch (Exception ignored) { }
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) { }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (wm == null || root == null) return;
        measureScreen();
        landscapeHidden = sw > sh; // videos and games: stay out of the way
        root.setVisibility(landscapeHidden || hidden ? View.GONE : View.VISIBLE);
        if (landscapeHidden) bubble.setVisibility(View.GONE);
        x = Math.max(winW * .4f, Math.min(sw - winW * .4f, x));
        y = Math.min(groundY(), y);
        place();
    }

    /* ------------------------------------------------------------ talking to the 3D view */
    private void js(String code) {
        if (ready) web.evaluateJavascript(code, null);
        else pending.add(code);
    }

    private void act(String code) { if (!hidden && !landscapeHidden) js(code); }

    private void say(String text, long ms) {
        if (hidden || landscapeHidden || bubble == null) return;
        bubble.setText(text);
        bubble.setVisibility(View.VISIBLE);
        bubbleUntil = SystemClock.uptimeMillis() + ms;
        placeBubble();
    }

    private void place() {
        lp.x = Math.round(x - winW / 2f);
        lp.y = Math.round(y - winH);
        if (root != null && root.isAttachedToWindow()) wm.updateViewLayout(root, lp);
        placeBubble();
    }

    private void placeBubble() {
        if (bubble == null || bubble.getVisibility() != View.VISIBLE) return;
        bubble.measure(View.MeasureSpec.makeMeasureSpec(px(240), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = bubble.getMeasuredWidth(), bh = bubble.getMeasuredHeight();
        blp.x = Math.round(Math.max(px(8), Math.min(sw - bw - px(8), x - bw / 2f)));
        int above = Math.round(y - winH * .82f - bh);
        blp.y = above < topInset + px(4) ? Math.round(y + px(6)) : above;
        if (bubble.isAttachedToWindow()) wm.updateViewLayout(bubble, blp);
    }

    /* ------------------------------------------------------------ movement */
    private void walkTo(float nx, Runnable then) { tx = nx; ty = groundY(); arrive = then; mode = Math.abs(y - groundY()) < 2 ? WALK : FLY; }
    private void flyTo(float nx, float ny, Runnable then) { tx = nx; ty = ny; arrive = then; mode = FLY; }

    private void tick() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 1 / 30f : Math.min(.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (hidden && hideUntil > 0 && System.currentTimeMillis() > hideUntil) show();
        if (bubble != null && bubble.getVisibility() == View.VISIBLE && now > bubbleUntil) bubble.setVisibility(View.GONE);
        if (hidden || landscapeHidden) { h.postDelayed(tick, 500); return; }

        float walk = 0, fly = 0, face = 0, tilt = 0;
        float gy = groundY();
        switch (mode) {
            case FALL: {
                vy += 2600 * dp / 2.6f * dt;
                x += vx * dt; y += vy * dt; vx *= 1 - dt * .5f;
                float half = winW * .35f;
                if (x < half) { x = half; vx = Math.abs(vx) * .5f; }
                if (x > sw - half) { x = sw - half; vx = -Math.abs(vx) * .5f; }
                if (y - winH * .8f < topInset && vy < 0) { y = topInset + winH * .8f; vy = -vy * .4f; }
                face = Math.max(-.8f, Math.min(.8f, vx / (800 * dp / 2.6f)));
                if (y >= gy) {
                    y = gy; float imp = vy; vx = 0; vy = 0; mode = STAND;
                    if (imp > 1500 * dp / 2.6f) { act("B.play('giggle',.6);B.emote('dizzy',2.2)"); say(pick("Whoa! Again! …or not.", "Ouch, my circuits!", "Stuck the landing. Mostly."), 2000); }
                    else act("B.play('giggle',.6);B.emote('happy',1.4)");
                    savePos();
                }
                break;
            }
            case WALK: {
                float d = tx - x, step = px(70) * speed * dt;
                walk = 1; face = Math.signum(d) * .9f; y = gy;
                if (Math.abs(d) <= step) { x = tx; mode = STAND; walk = 0; face = 0; savePos(); runArrive(); }
                else x += Math.signum(d) * step;
                break;
            }
            case FLY: {
                float dx = tx - x, dy = ty - y, d = (float) Math.hypot(dx, dy), step = Math.min(px(380), px(90) + d * 1.8f) * dt;
                fly = 1;
                if (d <= step + .5f) { x = tx; y = ty; mode = STAND; savePos(); runArrive(); }
                else { x += dx / d * step; y += dy / d * step; face = dx / d * .85f; tilt = -dx / d * .28f; }
                break;
            }
            default:
                if (mode == STAND && y < gy - 2) fly = .6f;
        }
        if (mode == DRAG) { walk = 0; fly = 0; }
        String mv = "B.move(" + walk + "," + fly + "," + round2(face) + "," + round2(tilt) + ")";
        if (!mv.equals(lastMove)) { lastMove = mv; js(mv); }
        place();
        if (mode == STAND && now > nextIdle) idle(now);
        h.postDelayed(tick, mode == STAND ? 50 : 33);
    }

    private static String round2(float v) { return String.valueOf(Math.round(v * 100) / 100f); }

    private void runArrive() { Runnable r = arrive; arrive = null; if (r != null) r.run(); }

    private void savePos() { prefs.edit().putFloat("floatX", x).putFloat("floatY", y).apply(); }

    private int lastHourSaid = -1;

    private void idle(long now) {
        nextIdle = now + (long) rand(18000, 40000);
        float r = rnd.nextFloat();
        float gy = groundY();
        if (Moments.on(prefs, "time-of-day")) {
            int hour = DayTime.hour(), part = DayTime.part(hour);
            // A little hello at the top of each hour.
            if (DayTime.minute() < 10 && hour != lastHourSaid) {
                lastHourSaid = hour;
                String[] o = DayTime.hourly(hour, rnd);
                act("B.sleep(false);B.play('" + o[1] + "');B.emote('" + o[2] + "',3)");
                say(o[0], 3200);
                return;
            }
            // Antics that fit the time: dozing after midnight, stretching at dawn, dancing in the afternoon…
            float chance = part == DayTime.LATE ? .7f : DayTime.sleepy(part) || part == DayTime.EARLY ? .55f : .4f;
            if (r < chance) {
                String[] o = DayTime.idle(part, rnd);
                if ("sleep".equals(o[0])) {
                    act("B.sleep(true)");
                    if (rnd.nextFloat() < .3f) say("Zzz…", 1800);
                    h.postDelayed(() -> act("B.sleep(false);B.play('stretch');B.emote('sleepy',2)"), (long) rand(9000, 20000));
                } else act("B.sleep(false);B.play('" + o[0] + "');B.emote('" + o[1] + "',2.6)");
                if (rnd.nextFloat() < .3f) say(DayTime.idleLine(part, rnd), 3200);
                return;
            }
        }
        if (r < .35f) {
            String[] a = {"B.play('wave');B.emote('happy',2.4)", "B.play('dance');B.emote('happy',4.2)", "B.play('spin');B.emote('dizzy',2.5)", "B.play('jump');B.emote('surprised',1.2)", "B.play('stretch');B.emote('happy',2.6)"};
            act(a[rnd.nextInt(a.length)]);
            if (rnd.nextFloat() < .25f) say(pick("Psst, you're doing great.", "Sip some water?", "Stretch break? Roll those shoulders.", "Why did the robot go on vacation? To recharge.", "I'd tell you a joke about RAM, but I forgot it."), 3200);
        } else if (r < .6f) {
            if (y < gy - 2) flyTo(x, gy, () -> act("B.play('giggle',.5)"));
            else { speed = 1; walkTo(rand(winW * .6f, sw - winW * .6f), null); }
        } else if (r < .75f) {
            boolean left = rnd.nextBoolean();
            flyTo(left ? winW * .55f : sw - winW * .55f, rand(sh * .3f, sh * .7f), () -> act("B.play('giggle',.5);B.emote('happy',1.5)"));
        } else if (r < .85f && Math.abs(y - gy) < 2) {
            boolean right = x > sw / 2f;
            speed = 1.4f;
            walkTo(right ? sw + winW * .05f : -winW * .05f, () -> h.postDelayed(() -> walkTo(right ? sw - winW * .6f : winW * .6f, () -> { act("B.play('wave');B.emote('happy',2)"); say("Peekaboo!", 1800); speed = 1; }), 1200));
        } else {
            act("B.sleep(true)");
            h.postDelayed(() -> act("B.sleep(false);B.play('stretch')"), 9000);
        }
    }

    private void hideFor(long ms) {
        hidden = true; hideUntil = System.currentTimeMillis() + ms;
        if (root != null) root.setVisibility(View.GONE);
        if (bubble != null) bubble.setVisibility(View.GONE);
    }

    private void show() {
        hidden = false; hideUntil = 0;
        if (root != null && !landscapeHidden) root.setVisibility(View.VISIBLE);
        act("B.play('jump');B.emote('happy',2)");
        say("I'm back!", 1800);
    }

    /* ------------------------------------------------------------ touch: poke, double-tap, drag and throw, long-press */
    private boolean onTouch(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY(); offX = x - downX; offY = y - downY; downT = now;
                dragging = false; samples.clear();
                longPress = () -> {
                    if (dragging) return;
                    lastTapT = 0;
                    act("B.emote('curious',3);B.play('nod')");
                    say("I'm listening!", 1500);
                    Intent i = new Intent(this, TalkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    try { startActivity(i); } catch (Exception ignored) { }
                };
                h.postDelayed(longPress, 650);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > px(10)) {
                    dragging = true; h.removeCallbacks(longPress);
                    mode = DRAG; arrive = null;
                    act("B.play('carried',999);B.emote('surprised',0)");
                    say(pick("Whoa, where are we going?", "Wheee!", "Hold me gently!"), 1500);
                }
                if (dragging) {
                    x = e.getRawX() + offX; y = e.getRawY() + offY;
                    samples.add(new float[]{x, y, (float) (now - downT)});
                    if (samples.size() > 6) samples.remove(0);
                    place();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    float svx = 0, svy = 0;
                    if (samples.size() > 1) {
                        float[] a = samples.get(0), b = samples.get(samples.size() - 1);
                        float d = Math.max(1, b[2] - a[2]) / 1000f;
                        svx = (b[0] - a[0]) / d; svy = (b[1] - a[1]) / d;
                    }
                    js("B.stop()");
                    float lim = 2600 * dp / 2.6f;
                    if (Math.hypot(svx, svy) < 400 * dp / 2.6f) {
                        mode = STAND; y = Math.min(y, groundY());
                        act("B.emote('happy',1.5)");
                        if (y < groundY() - 2) say("I'll float here.", 1500);
                        savePos();
                    } else {
                        mode = FALL; vx = Math.max(-lim, Math.min(lim, svx)); vy = Math.max(-lim, Math.min(lim, svy));
                        act("B.emote('surprised',3)");
                    }
                    nextIdle = now + 15000;
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP && now - downT < 400) {
                    if (now - lastTapT < 350) { lastTapT = 0; newOutfit(); }
                    else { lastTapT = now; h.postDelayed(this::pokeIfSingle, 360); }
                }
                return true;
            default:
                return true;
        }
    }

    private void pokeIfSingle() {
        if (lastTapT == 0) return;
        long now = SystemClock.uptimeMillis();
        pokes.add(now);
        while (!pokes.isEmpty() && now - pokes.get(0) > 10000) pokes.remove(0);
        if (pokes.size() >= 5) { pokes.clear(); act("B.play('shrug');B.emote('angry',3)"); say("Hey! Personal space, please.", 2200); return; }
        act("B.play('giggle');B.emote('" + (rnd.nextFloat() < .3f ? "love" : "happy") + "',1.6)");
        say(pick("Hehe, that tickles!", "Boop received.", "You found me!", "Again? Again!"), 1600);
    }

    private void newOutfit() {
        String cur = prefs.getString("skin", "bubblegum");
        Skin s;
        do { s = Skin.ALL[rnd.nextInt(Skin.ALL.length)]; } while (s.id.equals(cur) || "nightcap".equals(s.id));
        prefs.edit().putString("skin", s.id).apply();
        act("B.wear('" + s.id + "')");
        say("Ta-da! " + s.name + " time.", 2000);
    }

    /** Replies from the assistant (talk window): show the bubble and animate the floating Sweety. */
    @Override
    public void onSweety(String text, String action, String emotion) {
        h.post(() -> {
            if (root == null) return;
            if (text != null) say(text, Math.max(2500, text.length() * 70L));
            if (action != null) {
                if (action.startsWith("outfit:")) { String id = action.substring(7); prefs.edit().putString("skin", id).apply(); act("B.wear('" + id + "')"); }
                else if ("sleep".equals(action)) act("B.sleep(true)");
                else act("B.sleep(false);B.play('" + action + "')");
            }
            if (emotion != null && !"sleep".equals(action)) act("B.emote('" + emotion + "',2.6)");
            nextIdle = SystemClock.uptimeMillis() + 15000;
        });
    }

    /* ------------------------------------------------------------ phone events */
    @Override
    public void onNotificationPosted() {
        h.post(() -> {
            if (!Moments.on(prefs, "mail")) return;
            act("B.play('wave');B.emote('happy',2)");
            say(pick("You've got a message!", "Special delivery!", "Something new arrived!"), 2400);
        });
    }

    private boolean anyHeadphones() {
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
        }
        return false;
    }

    private void checkHeadphones(boolean maybeAdded) {
        boolean now = anyHeadphones(), was = headphones;
        headphones = now;
        if (now && !was && maybeAdded && Moments.on(prefs, "headphones")) {
            act("B.play('dance');B.emote('happy',4.2)");
            say(pick("Ooh, music time!", "This one's a banger.", "Turn it up!"), 2200);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float gx = e.values[0], gy = e.values[1], gz = e.values[2];
        float g = (float) Math.sqrt(gx * gx + gy * gy + gz * gz) / SensorManager.GRAVITY_EARTH;
        long now = SystemClock.uptimeMillis();
        if (g > 2.3f && Math.abs(g - shakeLast) > .8f && now - shakeAt > 2500 && mode != DRAG && !hidden && !landscapeHidden && Moments.on(prefs, "shake")) {
            shakeAt = now;
            mode = FALL; arrive = null;
            vx = rand(-1500, 1500) * dp / 2.6f; vy = -rand(1400, 2100) * dp / 2.6f;
            act("B.emote('surprised',2)");
            say(pick("Whoaaa!", "Earthquake!", "Wheee!"), 1400);
        }
        shakeLast = g;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
