package com.blink.buddy;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * Animated nature scene behind Sweety, one per outfit:
 * Sunny sky with birds and a tree, mint meadow with butterflies, lavender sunset with fireflies,
 * bamboo forest, garden rainbow, snowy pines, starry night, cotton-candy balloons, and the ocean.
 * Static layers are cached in a bitmap; moving things are drawn every frame.
 */
final class Scene {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ps = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final Random rnd = new Random();

    private Bitmap base;
    private String baseKey = "";
    private int W, H;
    private float G, dp, t;

    // moving things (reused across scenes)
    private final float[][] items = new float[40][8];
    private final Bird[] birds = new Bird[4];
    private float nextShoot = 4, shootT = -1, shootX, shootY;

    private static final class Bird { float x, y, vx, phase, timer; int state, slot; int color; }

    Scene() {
        ps.setStyle(Paint.Style.STROKE);
        ps.setStrokeCap(Paint.Cap.ROUND);
        ps.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < birds.length; i++) birds[i] = new Bird();
    }

    private static float sin(double a) { return (float) Math.sin(a); }
    private float rand(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    void draw(Canvas c, String id, int w, int h, float groundY, float time, float density, boolean night) {
        t = time; dp = density;
        String key = id + w + "x" + h;
        if (!key.equals(baseKey) || base == null) {
            W = w; H = h; G = groundY;
            buildBase(id);
            seed(id);
            baseKey = key;
        }
        c.drawBitmap(base, 0, 0, null);
        switch (id) {
            case "sunny": sunny(c); break;
            case "kitty": meadow(c); break;
            case "bunny": lavender(c); break;
            case "panda": bamboo(c); break;
            case "sprout": garden(c); break;
            case "snow": winter(c); break;
            case "nightcap": starry(c); break;
            case "classic": ocean(c); break;
            default: candy(c); break;
        }
        if (night && !"nightcap".equals(id)) { p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x55101040); c.drawRect(0, 0, W, H, p); }
    }

    /* ------------------------------------------------------------ static layers */
    private void sky(Canvas c, int... cols) {
        float[] pos = new float[cols.length];
        for (int i = 0; i < cols.length; i++) pos[i] = i / (float) (cols.length - 1);
        p.setShader(new LinearGradient(0, 0, 0, G, cols, pos, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
    }

    private void hill(Canvas c, float baseY, float amp, float waves, float phase, int top, int bottom) {
        path.reset();
        path.moveTo(0, H);
        for (int i = 0; i <= 40; i++) { float x = W * i / 40f; path.lineTo(x, baseY - amp * (.5f + .5f * sin(x / W * Math.PI * waves + phase))); }
        path.lineTo(W, H); path.close();
        p.setShader(new LinearGradient(0, baseY - amp, 0, H, top, bottom, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
    }

    private void glow(Canvas c, float x, float y, float r, int col) {
        p.setShader(new RadialGradient(x, y, r, new int[]{col, col & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
    }

    private void pine(Canvas c, float x, float baseY, float hgt, boolean snow) {
        p.setColor(0xFF6B4A2E); rf.set(x - hgt * .04f, baseY - hgt * .15f, x + hgt * .04f, baseY); c.drawRect(rf, p);
        for (int i = 0; i < 3; i++) {
            float top = baseY - hgt * (1f - i * .22f), bot = baseY - hgt * (.42f - i * .14f), half = hgt * (.22f + i * .07f);
            path.reset(); path.moveTo(x, top); path.lineTo(x + half, bot); path.lineTo(x - half, bot); path.close();
            p.setColor(0xFF2F7A55); c.drawPath(path, p);
            if (snow) { path.reset(); path.moveTo(x, top); path.lineTo(x + half * .45f, top + (bot - top) * .45f); path.lineTo(x - half * .45f, top + (bot - top) * .45f); path.close(); p.setColor(0xFFFFFFFF); c.drawPath(path, p); }
        }
    }

    private void buildBase(String id) {
        if (base == null || base.getWidth() != W || base.getHeight() != H) {
            if (base != null) base.recycle();
            base = Bitmap.createBitmap(Math.max(1, W), Math.max(1, H), Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(base);
        p.setStyle(Paint.Style.FILL);
        switch (id) {
            case "sunny": {
                sky(c, 0xFF3FA4F0, 0xFF7CC4F7, 0xFFCDEBFF);
                hill(c, G - H * .06f, H * .05f, 1.6f, .4f, 0xFF9BD873, 0xFF6DBB4E);
                hill(c, G + H * .02f, H * .035f, 2.4f, 2f, 0xFF7ACB5A, 0xFF4E9E3B);
                // tree
                float tx = W * .8f, ty = G - H * .02f;
                p.setColor(0xFF7A4E2D);
                path.reset(); path.moveTo(tx - 14 * dp, ty); path.lineTo(tx - 7 * dp, ty - H * .26f); path.lineTo(tx + 7 * dp, ty - H * .26f); path.lineTo(tx + 14 * dp, ty); path.close(); c.drawPath(path, p);
                ps.setColor(0xFF7A4E2D); ps.setStrokeWidth(7 * dp);
                c.drawLine(tx, ty - H * .2f, tx - W * .12f, ty - H * .26f, ps);
                c.drawLine(tx, ty - H * .16f, tx + W * .1f, ty - H * .22f, ps);
                int[] greens = {0xFF3E9E45, 0xFF4FAF4A, 0xFF62C155};
                float[][] blobs = {{0, -.36f, .13f}, {-.11f, -.3f, .1f}, {.1f, -.31f, .1f}, {-.05f, -.42f, .09f}, {.06f, -.41f, .09f}, {-.15f, -.25f, .07f}, {.13f, -.26f, .07f}};
                for (int i = 0; i < blobs.length; i++) { p.setColor(greens[i % 3]); c.drawCircle(tx + blobs[i][0] * W, ty + blobs[i][1] * H, blobs[i][2] * W, p); }
                break;
            }
            case "kitty": {
                sky(c, 0xFF8FDCCB, 0xFFC9F3E6, 0xFFF2FFF9);
                hill(c, G - H * .1f, H * .06f, 1.2f, 1f, 0xFFA8E2B4, 0xFF84CF97);
                hill(c, G - H * .02f, H * .04f, 2f, 3f, 0xFF7DCB8C, 0xFF56A86A);
                break;
            }
            case "bunny": {
                sky(c, 0xFF4E3A9E, 0xFF9C6BC8, 0xFFF59AB5, 0xFFFFC58A);
                glow(c, W * .5f, G - H * .12f, W * .45f, 0x66FFD38A);
                p.setColor(0xFFFFB86B); c.drawCircle(W * .5f, G - H * .12f, W * .13f, p);
                hill(c, G - H * .1f, H * .03f, 1.4f, .3f, 0xFF7A5CB8, 0xFF5E4596);
                hill(c, G - H * .02f, H * .02f, 2.2f, 1f, 0xFF8E6BD0, 0xFF5B3F98);
                break;
            }
            case "panda": {
                sky(c, 0xFFDDF5DA, 0xFFB5E3B0, 0xFF8CCB8A);
                p.setColor(0x33FFFFFF);
                for (int i = 0; i < 4; i++) { path.reset(); float x = W * (.1f + i * .27f); path.moveTo(x, 0); path.lineTo(x + W * .12f, 0); path.lineTo(x + W * .32f, G); path.lineTo(x + W * .2f, G); path.close(); c.drawPath(path, p); }
                for (int i = 0; i < 9; i++) { float x = W * (i / 8f) + (i % 2) * 10 * dp; p.setColor(0x6698C98A); rf.set(x - 5 * dp, 0, x + 5 * dp, G); c.drawRect(rf, p); }
                hill(c, G - H * .01f, H * .02f, 2f, 1f, 0xFF7DBE6A, 0xFF4F8E42);
                break;
            }
            case "sprout": {
                sky(c, 0xFFA9DBFF, 0xFFD6EEFF, 0xFFF3FAFF);
                int[] rainbow = {0xFFFF6B6B, 0xFFFFA94D, 0xFFFFE066, 0xFF69DB7C, 0xFF4DABF7, 0xFF748FFC, 0xFFB197FC};
                ps.setStrokeWidth(9 * dp);
                for (int i = 0; i < rainbow.length; i++) { ps.setColor((rainbow[i] & 0x00FFFFFF) | 0xAA000000); float r = W * .62f - i * 9 * dp; rf.set(W * .5f - r, G - H * .02f - r, W * .5f + r, G - H * .02f + r); c.drawArc(rf, 180, 180, false, ps); }
                hill(c, G - H * .05f, H * .04f, 1.8f, 2f, 0xFF9BE07A, 0xFF6FC155);
                hill(c, G + H * .01f, H * .03f, 2.6f, .5f, 0xFF7BCB5E, 0xFF4FA03E);
                break;
            }
            case "snow": {
                sky(c, 0xFF9CC4EA, 0xFFC8E0F7, 0xFFEFF7FF);
                hill(c, G - H * .1f, H * .05f, 1.3f, .8f, 0xFFF4F9FF, 0xFFD6E6F7);
                for (int i = 0; i < 5; i++) pine(c, W * (.08f + i * .22f) + (i % 2) * 14 * dp, G - H * .07f, H * (.16f + (i % 3) * .03f), true);
                hill(c, G - H * .01f, H * .03f, 2.2f, 2f, 0xFFFFFFFF, 0xFFDCE9F7);
                break;
            }
            case "nightcap": {
                sky(c, 0xFF070A2E, 0xFF1B1856, 0xFF3A2C7A);
                p.setColor(0xFFFFF3C4); c.drawCircle(W * .78f, H * .14f, W * .08f, p);
                p.setColor(0xFF120F45); c.drawCircle(W * .78f + W * .035f, H * .14f - W * .02f, W * .075f, p);
                glow(c, W * .78f, H * .14f, W * .2f, 0x33FFF3C4);
                hill(c, G - H * .07f, H * .05f, 1.5f, .6f, 0xFF1E1A50, 0xFF141038);
                hill(c, G - H * .01f, H * .03f, 2.3f, 2.2f, 0xFF27215E, 0xFF120E33);
                break;
            }
            case "classic": {
                sky(c, 0xFF6EC3EB, 0xFFA9DCF5, 0xFFE3F6FF);
                glow(c, W * .82f, H * .12f, W * .25f, 0x66FFF2B0);
                p.setColor(0xFFFFE680); c.drawCircle(W * .82f, H * .12f, W * .07f, p);
                p.setShader(new LinearGradient(0, H * .52f, 0, G - H * .03f, 0xFF2E8BD0, 0xFF57B6E8, Shader.TileMode.CLAMP));
                c.drawRect(0, H * .52f, W, G, p); p.setShader(null);
                hill(c, G - H * .015f, H * .015f, 3f, 0, 0xFFF6E2B0, 0xFFE9CB8B);
                break;
            }
            default: { // bubblegum
                sky(c, 0xFFFFB3D6, 0xFFFFD6E4, 0xFFFFEAD6, 0xFFE6D8FF);
                hill(c, G - H * .05f, H * .04f, 1.5f, 1f, 0xFFFFC9E3, 0xFFF7A8CF);
                hill(c, G + H * .01f, H * .03f, 2.4f, 2.5f, 0xFFF9B4D6, 0xFFE58AB8);
                break;
            }
        }
    }

    /* ------------------------------------------------------------ moving things */
    private void seed(String id) {
        for (float[] it : items) {
            it[0] = rand(0, W); it[1] = rand(0, H); it[2] = rand(.6f, 1.4f); it[3] = rand(0, 6.28f);
            it[4] = rand(0, 1); it[5] = rand(0, 1); it[6] = rand(0, 1); it[7] = 0;
        }
        int[] cols = {0xFF3A3150, 0xFF4C78D6, 0xFFE2468C, 0xFF3A3150};
        for (int i = 0; i < birds.length; i++) {
            Bird b = birds[i]; b.state = 0; b.x = rand(-W * .5f, W); b.y = rand(H * .12f, H * .4f); b.vx = rand(40, 80) * dp; b.phase = rand(0, 6); b.slot = -1; b.color = cols[i];
        }
        nextShoot = t + 3;
    }

    private void cloud(Canvas c, float x, float y, float s, int col) {
        p.setColor(col);
        c.drawCircle(x, y, 22 * s * dp, p);
        c.drawCircle(x + 24 * s * dp, y - 10 * s * dp, 26 * s * dp, p);
        c.drawCircle(x + 50 * s * dp, y, 20 * s * dp, p);
        rf.set(x - 8 * s * dp, y, x + 62 * s * dp, y + 16 * s * dp); c.drawRoundRect(rf, 10 * s * dp, 10 * s * dp, p);
    }

    private void clouds(Canvas c, int n, float yMax, int col, float speed) {
        for (int i = 0; i < n; i++) {
            float[] it = items[30 + i];
            float span = W + 160 * dp;
            float x = ((it[4] * span + t * speed * dp * it[2]) % span) - 90 * dp;
            cloud(c, x, H * (.06f + it[5] * yMax), .8f + it[6] * .7f, col);
        }
    }

    private void flyingBird(Canvas c, float x, float y, float s, float flap, int col) {
        ps.setColor(col); ps.setStrokeWidth(2.4f * dp);
        path.reset(); path.moveTo(x - 9 * s, y); path.quadTo(x - 4 * s, y - 7 * s * flap, x, y); path.quadTo(x + 4 * s, y - 7 * s * flap, x + 9 * s, y);
        c.drawPath(path, ps);
    }

    private void sittingBird(Canvas c, float x, float y, float s, int col, float hop) {
        y -= hop;
        p.setColor(col); rf.set(x - 7 * s, y - 6 * s, x + 7 * s, y + 4 * s); c.drawOval(rf, p);
        c.drawCircle(x + 6 * s, y - 7 * s, 4.2f * s, p);
        path.reset(); path.moveTo(x - 6 * s, y - 1 * s); path.lineTo(x - 13 * s, y - 4 * s); path.lineTo(x - 11 * s, y + 1 * s); path.close(); c.drawPath(path, p);
        p.setColor(0xFFFF9F43); path.reset(); path.moveTo(x + 10 * s, y - 8 * s); path.lineTo(x + 14 * s, y - 6.5f * s); path.lineTo(x + 10 * s, y - 5.5f * s); path.close(); c.drawPath(path, p);
        p.setColor(0xFFFFFFFF); c.drawCircle(x + 7.5f * s, y - 8 * s, 1.3f * s, p);
    }

    private void sunny(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        float sx = W * .2f, sy = H * .14f, sr = W * .075f;
        glow(c, sx, sy, sr * 3.2f, 0x66FFF1A8);
        ps.setColor(0xCCFFE066); ps.setStrokeWidth(3.5f * dp);
        for (int i = 0; i < 10; i++) { double a = i * Math.PI / 5 + t * .3f; c.drawLine(sx + (float) Math.cos(a) * sr * 1.3f, sy + (float) Math.sin(a) * sr * 1.3f, sx + (float) Math.cos(a) * sr * 1.75f, sy + (float) Math.sin(a) * sr * 1.75f, ps); }
        p.setColor(0xFFFFD43B); c.drawCircle(sx, sy, sr, p);
        clouds(c, 4, .22f, 0xF2FFFFFF, 14);

        // birds: fly across, some land on the tree and sit for a while
        float tx = W * .8f, ty = G - H * .02f;
        float[][] slots = {{tx - W * .12f, ty - H * .26f - 4 * dp}, {tx + W * .1f, ty - H * .22f - 4 * dp}, {tx - W * .02f, ty - H * .49f}};
        boolean[] used = new boolean[slots.length];
        for (Bird b : birds) if (b.slot >= 0) used[b.slot] = true;
        for (Bird b : birds) {
            float dt = 1 / 30f;
            if (b.state == 0) {
                b.x += b.vx * dt; b.y += sin(t * 1.3f + b.phase) * 8 * dp * dt;
                if (b.x > W + 30 * dp) { b.x = -30 * dp; b.y = rand(H * .1f, H * .38f); }
                if (b.slot < 0 && b.x > tx - W * .45f && b.x < tx - W * .3f && rnd.nextFloat() < .01f) {
                    for (int s = 0; s < slots.length; s++) if (!used[s]) { b.slot = s; used[s] = true; b.state = 1; break; }
                }
                flyingBird(c, b.x, b.y, dp, sin(t * 10 + b.phase), b.color);
            } else if (b.state == 1) {
                float dx = slots[b.slot][0] - b.x, dy = slots[b.slot][1] - b.y, d = (float) Math.hypot(dx, dy), step = 70 * dp * dt;
                if (d < step) { b.x = slots[b.slot][0]; b.y = slots[b.slot][1]; b.state = 2; b.timer = rand(6, 12); }
                else { b.x += dx / d * step; b.y += dy / d * step; }
                flyingBird(c, b.x, b.y, dp, sin(t * 14 + b.phase), b.color);
            } else if (b.state == 2) {
                b.timer -= dt;
                float hop = Math.max(0, sin(t * 3 + b.phase)) > .97f ? 3 * dp : 0;
                sittingBird(c, b.x, b.y, dp * 1.1f, b.color, hop);
                if (b.timer < 0) { b.state = 0; b.slot = -1; b.y -= 10 * dp; }
            }
        }
        // grass tufts
        ps.setColor(0xFF4E9E3B); ps.setStrokeWidth(2 * dp);
        for (int i = 0; i < 26; i++) { float x = W * i / 25f, sway = sin(t * 2 + i) * 4 * dp; c.drawLine(x, G + 6 * dp, x + sway, G - 8 * dp, ps); }
    }

    private void flower(Canvas c, float x, float y, float s, int petal, float sway) {
        ps.setColor(0xFF4F9E4A); ps.setStrokeWidth(2.2f * dp);
        c.drawLine(x, y, x + sway, y - 26 * s, ps);
        float fx = x + sway, fy = y - 26 * s;
        p.setColor(petal);
        for (int k = 0; k < 5; k++) { double a = k * Math.PI * 2 / 5 + t * .2f; c.drawCircle(fx + (float) Math.cos(a) * 5 * s, fy + (float) Math.sin(a) * 5 * s, 4 * s, p); }
        p.setColor(0xFFFFE066); c.drawCircle(fx, fy, 3.2f * s, p);
    }

    private void meadow(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        clouds(c, 3, .18f, 0xE6FFFFFF, 10);
        int[] petals = {0xFFFF9EC7, 0xFFFFFFFF, 0xFFFFD43B, 0xFFB197FC};
        for (int i = 0; i < 14; i++) { float x = W * (i + .5f) / 14f + (i % 3) * 6 * dp; flower(c, x, G + 10 * dp, dp * (.9f + (i % 3) * .15f), petals[i % 4], sin(t * 1.6f + i) * 5 * dp); }
        // butterflies
        int[] wings = {0xFFFF8FC0, 0xFFFFC94D, 0xFF8EC5FF};
        for (int i = 0; i < 3; i++) {
            float[] it = items[i];
            float x = W * (.2f + .3f * i) + sin(t * .4f + it[3]) * W * .18f, y = H * (.35f + .12f * i) + sin(t * .9f + it[3] * 2) * H * .06f;
            float flap = Math.abs(sin(t * 12 + i));
            p.setColor(wings[i]);
            rf.set(x - 11 * dp * flap, y - 9 * dp, x, y + 2 * dp); c.drawOval(rf, p);
            rf.set(x, y - 9 * dp, x + 11 * dp * flap, y + 2 * dp); c.drawOval(rf, p);
            rf.set(x - 7 * dp * flap, y, x, y + 8 * dp); c.drawOval(rf, p);
            rf.set(x, y, x + 7 * dp * flap, y + 8 * dp); c.drawOval(rf, p);
            p.setColor(0xFF3A3150); rf.set(x - 1.5f * dp, y - 8 * dp, x + 1.5f * dp, y + 7 * dp); c.drawRoundRect(rf, dp, dp, p);
        }
        ps.setColor(0xFF56A86A); ps.setStrokeWidth(2 * dp);
        for (int i = 0; i < 30; i++) { float x = W * i / 29f, sway = sin(t * 2.2f + i * .7f) * 5 * dp; c.drawLine(x, G + 8 * dp, x + sway, G - 10 * dp, ps); }
    }

    private void lavender(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for (int row = 0; row < 3; row++) {
            float y = G - H * .04f + row * 10 * dp, s = .7f + row * .2f;
            for (int i = 0; i < 18; i++) {
                float x = W * (i + .5f) / 18f + row * 7 * dp, sway = sin(t * 1.4f + i * .6f + row) * 5 * dp * s;
                ps.setColor(0xFF5E8F55); ps.setStrokeWidth(1.6f * dp); c.drawLine(x, y + 12 * dp, x + sway, y - 20 * dp * s, ps);
                p.setColor(row == 2 ? 0xFFB197FC : 0xFF9775FA);
                for (int k = 0; k < 4; k++) c.drawCircle(x + sway * (1 - k * .08f), y - 20 * dp * s + k * 4 * dp * s, 2.6f * dp * s, p);
            }
        }
        for (int i = 0; i < 16; i++) {
            float[] it = items[i];
            float x = (it[0] + sin(t * .5f + it[3]) * 30 * dp), y = H * .35f + it[5] * (G - H * .35f) + sin(t * .7f + it[3]) * 15 * dp;
            float a = .5f + .5f * sin(t * 3 * it[2] + it[3]);
            glow(c, x, y, 9 * dp, ((int) (a * 160) << 24) | 0xFFF59F);
            p.setColor(((int) (a * 255) << 24) | 0xFFFBD0); c.drawCircle(x, y, 2 * dp, p);
        }
    }

    private void bambooStalk(Canvas c, float x, float w, float sway, int col) {
        float top = -10 * dp;
        p.setColor(col);
        path.reset(); path.moveTo(x - w / 2, G + 10 * dp); path.lineTo(x - w / 2 + sway, top); path.lineTo(x + w / 2 + sway, top); path.lineTo(x + w / 2, G + 10 * dp); path.close(); c.drawPath(path, p);
        ps.setColor(0x55204020); ps.setStrokeWidth(2 * dp);
        for (float y = G - 30 * dp; y > 0; y -= 70 * dp) { float k = 1 - y / G; c.drawLine(x - w / 2 + sway * k, y, x + w / 2 + sway * k, y, ps); }
        p.setColor(0xFF5FB35A);
        for (float y = G - 100 * dp; y > 40 * dp; y -= 140 * dp) {
            float k = 1 - y / G, lx = x + sway * k;
            path.reset(); path.moveTo(lx, y); path.quadTo(lx + 22 * dp, y - 14 * dp, lx + 42 * dp, y - 6 * dp); path.quadTo(lx + 20 * dp, y - 2 * dp, lx, y); c.drawPath(path, p);
        }
    }

    private void bamboo(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        float sway = sin(t * .8f) * 8 * dp;
        bambooStalk(c, W * .08f, 16 * dp, sway, 0xFF6DAE55);
        bambooStalk(c, W * .26f, 13 * dp, sway * .7f, 0xFF7DBF62);
        bambooStalk(c, W * .78f, 15 * dp, sway * .9f, 0xFF6DAE55);
        bambooStalk(c, W * .94f, 18 * dp, sway, 0xFF5E9E4A);
        for (int i = 0; i < 9; i++) {
            float[] it = items[i];
            float fall = (it[1] + t * 30 * dp * it[2]) % (G + 40 * dp) - 20 * dp;
            float x = it[0] + sin(t * 1.2f + it[3]) * 24 * dp;
            c.save(); c.rotate(sin(t * 2 + it[3]) * 50, x, fall);
            p.setColor(i % 2 == 0 ? 0xFF7CC66C : 0xFFA5D87E); rf.set(x - 8 * dp, fall - 3 * dp, x + 8 * dp, fall + 3 * dp); c.drawOval(rf, p);
            c.restore();
        }
    }

    private void tulip(Canvas c, float x, float y, int col, float sway) {
        ps.setColor(0xFF4F9E4A); ps.setStrokeWidth(2.4f * dp); c.drawLine(x, y, x + sway, y - 30 * dp, ps);
        p.setColor(col);
        float fx = x + sway, fy = y - 30 * dp;
        path.reset(); path.moveTo(fx - 7 * dp, fy - 8 * dp); path.lineTo(fx - 3.5f * dp, fy - 3 * dp); path.lineTo(fx, fy - 9 * dp); path.lineTo(fx + 3.5f * dp, fy - 3 * dp); path.lineTo(fx + 7 * dp, fy - 8 * dp);
        path.quadTo(fx + 7 * dp, fy + 4 * dp, fx, fy + 4 * dp); path.quadTo(fx - 7 * dp, fy + 4 * dp, fx - 7 * dp, fy - 8 * dp); path.close();
        c.drawPath(path, p);
    }

    private void garden(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        clouds(c, 3, .15f, 0xF0F4F8FF, 9);
        // a little drizzle under the first cloud, fading in and out
        float rain = Math.max(0, sin(t * .15f));
        if (rain > .05f) {
            float[] cl = items[30];
            float span = W + 160 * dp, cx = ((cl[4] * span + t * 9 * dp * cl[2]) % span) - 90 * dp + 25 * dp, cy = H * (.06f + cl[5] * .15f) + 18 * dp;
            ps.setColor(((int) (rain * 150) << 24) | 0x4DABF7); ps.setStrokeWidth(1.6f * dp);
            for (int i = 0; i < 10; i++) { float[] it = items[i]; float y = cy + (it[1] + t * 260 * dp) % (H * .25f); float x = cx - 20 * dp + it[4] * 60 * dp; c.drawLine(x, y, x - 2 * dp, y + 9 * dp, ps); }
        }
        int[] cols = {0xFFFF6B8B, 0xFFFFC94D, 0xFFB197FC, 0xFFFF8FC0, 0xFFFF922B};
        for (int i = 0; i < 12; i++) tulip(c, W * (i + .5f) / 12f, G + 12 * dp, cols[i % cols.length], sin(t * 1.5f + i) * 4 * dp);
        for (int i = 0; i < 8; i++) { float[] it = items[12 + i]; float a = Math.max(0, sin(t * 2 + it[3])); p.setColor(((int) (a * 220) << 24) | 0xFFFFFF); c.drawCircle(it[0], G - 6 * dp + it[5] * 14 * dp, 1.8f * dp, p); }
    }

    private void winter(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 40; i++) {
            float[] it = items[i];
            float y = (it[1] + t * 28 * dp * it[2]) % (H + 20 * dp) - 10 * dp;
            float x = (it[0] + sin(t * .8f + it[3]) * 18 * dp);
            p.setColor(i % 4 == 0 ? 0xCCFFFFFF : 0xFFFFFFFF);
            c.drawCircle(x, y, (1.5f + it[2] * 1.8f) * dp, p);
        }
    }

    private void starry(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 40; i++) {
            float[] it = items[i];
            float a = .35f + .65f * Math.abs(sin(t * (.8f + it[2]) + it[3]));
            float y = it[5] * (G - H * .12f);
            p.setColor(((int) (a * 255) << 24) | 0xFFFFFF);
            float r = (it[2] > 1.25f ? 2.2f : 1.3f) * dp;
            c.drawCircle(it[0], y, r, p);
            if (it[2] > 1.3f) { ps.setColor(((int) (a * 160) << 24) | 0xFFFFFF); ps.setStrokeWidth(dp); c.drawLine(it[0] - r * 3, y, it[0] + r * 3, y, ps); c.drawLine(it[0], y - r * 3, it[0], y + r * 3, ps); }
        }
        if (shootT < 0 && t > nextShoot) { shootT = 0; shootX = rand(W * .1f, W * .7f); shootY = rand(H * .05f, H * .25f); }
        if (shootT >= 0) {
            shootT += 1 / 30f;
            float k = shootT / .9f, x = shootX + k * W * .35f, y = shootY + k * H * .12f;
            ps.setShader(new LinearGradient(x, y, x - 60 * dp, y - 20 * dp, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            ps.setStrokeWidth(2.4f * dp); c.drawLine(x, y, x - 60 * dp, y - 20 * dp, ps); ps.setShader(null);
            if (k >= 1) { shootT = -1; nextShoot = t + rand(5, 10); }
        }
    }

    private void balloon(Canvas c, float x, float y, float s, int a, int b) {
        ps.setColor(0xFF7A4E2D); ps.setStrokeWidth(1.2f * dp);
        c.drawLine(x - 8 * s, y + 20 * s, x - 5 * s, y + 34 * s, ps); c.drawLine(x + 8 * s, y + 20 * s, x + 5 * s, y + 34 * s, ps);
        p.setColor(0xFF9C6B3E); rf.set(x - 6 * s, y + 33 * s, x + 6 * s, y + 41 * s); c.drawRoundRect(rf, 2 * s, 2 * s, p);
        p.setColor(a); rf.set(x - 20 * s, y - 24 * s, x + 20 * s, y + 22 * s); c.drawOval(rf, p);
        p.setColor(b); rf.set(x - 8 * s, y - 24 * s, x + 8 * s, y + 22 * s); c.drawOval(rf, p);
        p.setColor(0x55FFFFFF); c.drawCircle(x - 9 * s, y - 10 * s, 5 * s, p);
    }

    private void candy(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        clouds(c, 4, .3f, 0xE6FFE3F1, 11);
        int[][] cols = {{0xFFFF6FAE, 0xFFFFD43B}, {0xFF8EC5FF, 0xFFFFFFFF}, {0xFFB197FC, 0xFFFF9EC7}};
        for (int i = 0; i < 3; i++) {
            float[] it = items[i];
            float span = G + 120 * dp;
            float y = span - ((it[1] + t * 12 * dp * it[2]) % span) - 60 * dp;
            float x = W * (.15f + .33f * i) + sin(t * .5f + it[3]) * 20 * dp;
            balloon(c, x, y, dp * (.9f + i * .15f), cols[i][0], cols[i][1]);
        }
        for (int i = 0; i < 6; i++) {
            float[] it = items[10 + i];
            float y = G - ((it[1] + t * 20 * dp) % (G * .7f)), x = it[0] + sin(t + it[3]) * 12 * dp;
            int a = (int) (200 * Math.min(1, (G - y) / (G * .2f)) * Math.min(1, (y - G * .3f) / (G * .2f)));
            if (a <= 0) continue;
            heart(c, x, y, 6 * dp, (a << 24) | 0xFF6FAE);
        }
    }

    private void heart(Canvas c, float cx, float cy, float s, int col) {
        path.reset(); path.moveTo(cx, cy + .7f * s);
        path.cubicTo(cx - 1.15f * s, cy, cx - .6f * s, cy - .95f * s, cx, cy - .35f * s);
        path.cubicTo(cx + .6f * s, cy - .95f * s, cx + 1.15f * s, cy, cx, cy + .7f * s);
        p.setColor(col); c.drawPath(path, p);
    }

    private void ocean(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        clouds(c, 3, .16f, 0xF2FFFFFF, 12);
        float sea = H * .52f;
        ps.setStrokeWidth(2.2f * dp);
        for (int row = 0; row < 6; row++) {
            float y = sea + 14 * dp + row * (G - sea - 20 * dp) / 6f;
            ps.setColor(0x88FFFFFF);
            path.reset();
            for (int i = 0; i <= 30; i++) { float x = W * i / 30f, yy = y + sin(x / (40 * dp) + t * (1.2f + row * .2f) + row) * 3 * dp; if (i == 0) path.moveTo(x, yy); else path.lineTo(x, yy); }
            c.drawPath(path, ps);
        }
        // sailboat
        float bx = (W * .2f + t * 6 * dp) % (W + 80 * dp) - 40 * dp, by = sea + 22 * dp + sin(t * 1.5f) * 3 * dp;
        p.setColor(0xFFE2468C); path.reset(); path.moveTo(bx - 20 * dp, by); path.lineTo(bx + 20 * dp, by); path.lineTo(bx + 14 * dp, by + 8 * dp); path.lineTo(bx - 14 * dp, by + 8 * dp); path.close(); c.drawPath(path, p);
        p.setColor(0xFFFFFFFF); path.reset(); path.moveTo(bx, by - 2 * dp); path.lineTo(bx, by - 32 * dp); path.lineTo(bx + 18 * dp, by - 4 * dp); path.close(); c.drawPath(path, p);
        // seagulls
        for (int i = 0; i < 3; i++) {
            Bird b = birds[i];
            b.x += b.vx * .8f / 30f; if (b.x > W + 30 * dp) { b.x = -30 * dp; b.y = rand(H * .1f, H * .4f); }
            flyingBird(c, b.x, b.y + sin(t + b.phase) * 10 * dp, dp * 1.2f, sin(t * 6 + b.phase), 0xFF4A5568);
        }
        // sun glints on the water
        for (int i = 0; i < 8; i++) { float[] it = items[i]; float a = Math.max(0, sin(t * 2.5f + it[3])); p.setColor(((int) (a * 230) << 24) | 0xFFFFFF); c.drawCircle(W * .55f + it[4] * W * .4f, sea + 10 * dp + it[5] * (G - sea) * .5f, 1.8f * dp, p); }
    }
}
