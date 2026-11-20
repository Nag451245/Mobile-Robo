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
import android.provider.Settings;
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

/** Settings and preview: set Blink as the wallpaper, try every moment, pick an outfit, switch moments on or off. */
public class MainActivity extends Activity {
    private float dp;
    private SharedPreferences prefs;
    private BlinkView preview;

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

        TextView title = text("Blink", 30, true);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        col.addView(title);
        col.addView(text("A cute robot for your home screen and lock screen. Set it as your live wallpaper, then unlock, charge, shake or swipe and watch it react.", 15, false));

        preview = new BlinkView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(420));
        lp.topMargin = px(14);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(px(24));
        frame.setColor(Color.WHITE);
        preview.setBackground(frame);
        preview.setClipToOutline(true);
        col.addView(preview, lp);

        col.addView(header("Floating Blink (like the desktop version)"));
        col.addView(text("Blink walks, flies and perches on top of your home screen and apps. Drag it anywhere, flick to throw, tap to poke, double-tap for a new outfit, long-press to come back here. It hides itself in landscape (videos and games).", 14, false));
        Button start = button("Start floating Blink", true);
        start.setOnClickListener(v -> startFloating());
        col.addView(start, full(px(10)));
        Button stop = button("Stop floating Blink", false);
        stop.setOnClickListener(v -> { stopService(new Intent(this, OverlayService.class)); toast("Floating Blink stopped."); });
        col.addView(stop, full(px(8)));
        String[][] sizes = {{"100", "Small"}, {"120", "Medium"}, {"150", "Large"}};
        col.addView(chipRow(sizes, id -> {
            prefs.edit().putInt("floatSize", Integer.parseInt(id)).apply();
            if (OverlayService.running) { stopService(new Intent(this, OverlayService.class)); startFloating(); }
            toast("Size saved.");
        }));

        col.addView(header("Wallpaper (home and lock screen)"));
        Button set = button("Set Blink as my wallpaper", false);
        set.setOnClickListener(v -> setWallpaper());
        col.addView(set, full(px(12)));
        col.addView(text("Tip: when asked, choose \"Home and lock screens\" so Blink shows up on both. Some phones (for example many Samsung models) only allow live wallpapers on the home screen.", 13, false));

        col.addView(header("Try a moment"));
        String[][] sims = {{"screen", "Screen on"}, {"unlock", "Unlock"}, {"missed", "Missed you"}, {"swipe", "Swipe page"}, {"charge", "Plug in"},
                {"lowbat", "Low battery"}, {"music", "Headphones"}, {"shake", "Shake"}, {"outfit", "New outfit"}, {"nudge", "30 min nudge"}};
        col.addView(chipRow(sims, id -> preview.blink.simulate(id)));
        col.addView(text("You can also tap, double-tap and stroke Blink in the preview.", 13, false));

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

    private void startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Turn on \"Allow display over other apps\" for Blink, then come back and tap Start again.");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        startForegroundService(new Intent(this, OverlayService.class));
        toast("Blink is floating! Go to your home screen.");
    }

    private void setWallpaper() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, BlinkWallpaperService.class));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); toast("Pick Blink from the list."); }
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
