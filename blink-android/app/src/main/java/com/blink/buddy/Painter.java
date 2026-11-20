package com.blink.buddy;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Draws Blink in 2D with soft shading so it reads like the 3D model: chibi body, big screen face,
 * stubby arms, and the skin's accessories. Units: u = 1/2.7 of Blink's height; y grows downward.
 */
final class Painter {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ps = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    final Face face = new Face();

    Painter() {
        ps.setStyle(Paint.Style.STROKE);
        ps.setStrokeCap(Paint.Cap.ROUND);
        ps.setStrokeJoin(Paint.Join.ROUND);
    }

    static float sin(double a) { return (float) Math.sin(a); }
    static float cos(double a) { return (float) Math.cos(a); }

    static int mix(int a, int b, float t) {
        return Color.argb(255,
            (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
            (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
            (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private void ball(Canvas c, float cx, float cy, float rx, float ry, int col) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - rx * .35f, cy - ry * .4f, Math.max(rx, ry) * 1.3f,
            new int[]{mix(col, 0xFFFFFFFF, .55f), col, mix(col, 0xFF3A2A55, .25f)}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        rf.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(rf, p);
        p.setShader(null);
    }

    private void flat(Canvas c, float cx, float cy, float rx, float ry, int col) {
        p.setStyle(Paint.Style.FILL); p.setShader(null); p.setColor(col);
        rf.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(rf, p);
    }

    /** Draws Blink with its feet at (X, Y) in screen pixels. */
    void drawRobot(Canvas c, Blink b, float X, float Y) {
        final float u = b.u, t = b.t;
        final Skin sk = b.skin;
        final float[] k = b.cur;
        float rootY = k[Blink.ROOTY], cs = cos(k[Blink.SPIN]);
        if (Math.abs(cs) < .06f) cs = cs < 0 ? -.06f : .06f;
        boolean back = cs < 0;
        float v = b.vanish;
        if (v < .01f) return;

        // contact shadow
        if (b.mode == Blink.GROUND || b.mode == Blink.FALL) {
            float sh = Math.max(.3f, 1 - rootY * .4f) * v;
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0x30281446);
            rf.set(X - .72f * u * sh, Y - .1f * u * sh, X + .72f * u * sh, Y + .1f * u * sh);
            c.drawOval(rf, p);
        }

        c.save();
        c.translate(X, Y - rootY * u);
        c.rotate(b.tilt * 57.3f, 0, -1.3f * u);
        c.scale(k[Blink.SX] * cs * v, k[Blink.SY] * v);

        float bodyCY = -k[Blink.BODY] * u;
        float walkS = b.walk > 0 ? sin(t * 11) : 0;

        // tail (behind)
        if (sk.has("tail")) {
            c.save();
            c.rotate(sin(t * 3) * 14, .25f * u, bodyCY + .25f * u);
            ps.setColor(sk.shell); ps.setStrokeWidth(.15f * u);
            path.reset();
            path.moveTo(.25f * u, bodyCY + .25f * u);
            path.cubicTo(.75f * u, bodyCY + .2f * u, .85f * u, bodyCY - .35f * u, .62f * u, bodyCY - .55f * u);
            c.drawPath(path, ps);
            flat(c, .62f * u, bodyCY - .58f * u, .1f * u, .1f * u, sk.inner);
            c.restore();
        }

        // feet
        for (int s = -1; s <= 1; s += 2) {
            float lift = b.walk > 0 ? Math.max(0, s * walkS) * .1f * u : 0;
            ball(c, s * .26f * u, -.11f * u - lift, .21f * u, .12f * u, sk.trim);
        }

        // body
        ball(c, 0, bodyCY, .55f * u, .506f * u, sk.shell);
        p.setShader(null);
        flat(c, 0, bodyCY + .05f * u, .35f * u, .36f * u, sk.belly);
        int heartCol = (sk.accent == 0xFF7ED957 || sk.accent == 0xFF7BD88F) ? 0xFFFF7AB0 : sk.accent;
        face.heart(c, 0, bodyCY + .05f * u, .14f * u * b.heartPulse, heartCol);

        // scarf (Snow Buddy)
        if (sk.has("beanie")) {
            p.setColor(sk.hat); p.setStyle(Paint.Style.FILL);
            rf.set(-.42f * u, bodyCY - .58f * u, .42f * u, bodyCY - .38f * u);
            c.drawRoundRect(rf, .1f * u, .1f * u, p);
            c.save(); c.rotate(10 + sin(t * 2.4f) * 6, .22f * u, bodyCY - .45f * u);
            rf.set(.14f * u, bodyCY - .45f * u, .3f * u, bodyCY - .02f * u);
            c.drawRoundRect(rf, .05f * u, .05f * u, p);
            c.restore();
        }

        // head group (pivot at the neck)
        float pivotY = bodyCY - .5f * u;
        c.save();
        c.rotate(-k[Blink.HZ] * 57.3f, 0, pivotY);
        c.translate(0, k[Blink.HX] * .2f * u);
        float headC = pivotY - .62f * u;
        float fx = (k[Blink.HY] * .22f + b.face * .18f) * u;

        // accessories behind the head
        if (sk.has("antenna")) {
            c.save(); c.rotate(-sin(t * 4.5f) * 8, 0, headC - .7f * u);
            ps.setColor(sk.trim); ps.setStrokeWidth(.07f * u);
            c.drawLine(0, headC - .7f * u, 0, headC - 1.05f * u, ps);
            p.setColor(sk.accent); p.setAlpha(80); c.drawCircle(0, headC - 1.1f * u, .2f * u, p); p.setAlpha(255);
            ball(c, 0, headC - 1.1f * u, .12f * u, .12f * u, sk.accent);
            c.restore();
        }
        if (sk.has("leaf")) {
            c.save(); c.rotate(-sin(t * 2.2f) * 9, 0, headC - .72f * u);
            ps.setColor(0xFF3E9B4F); ps.setStrokeWidth(.06f * u);
            c.drawLine(0, headC - .7f * u, 0, headC - .98f * u, ps);
            for (int s = -1; s <= 1; s += 2) {
                c.save(); c.rotate(-s * 26, s * .17f * u, headC - 1.02f * u);
                flat(c, s * .17f * u, headC - 1.02f * u, .2f * u, .075f * u, sk.leaf);
                c.restore();
            }
            c.restore();
        }
        if (sk.has("catEars")) {
            for (int s = -1; s <= 1; s += 2) {
                float tw = (float) Math.pow(Math.max(0, sin(t * 1.3f + s * .7f)), 24);
                c.save(); c.translate(s * .5f * u, headC - .55f * u); c.rotate(s * (22 + tw * 17));
                path.reset(); path.moveTo(-.27f * u, 0); path.lineTo(.27f * u, 0); path.lineTo(0, -.5f * u); path.close();
                p.setColor(sk.shell); p.setStyle(Paint.Style.FILL); c.drawPath(path, p);
                path.reset(); path.moveTo(-.14f * u, -.04f * u); path.lineTo(.14f * u, -.04f * u); path.lineTo(0, -.34f * u); path.close();
                p.setColor(sk.inner); c.drawPath(path, p);
                c.restore();
            }
        }
        if (sk.has("bunnyEars")) {
            for (int s = -1; s <= 1; s += 2) {
                boolean flop = s > 0;
                float rot = s * 7 + (flop ? 32 + sin(t * 2) * 5 : sin(t * 1.7f) * 3);
                c.save(); c.translate(s * .3f * u, headC - .6f * u); c.rotate(rot);
                ball(c, 0, -.48f * u, .16f * u, .5f * u, sk.shell);
                flat(c, 0, -.46f * u, .085f * u, .38f * u, sk.inner);
                c.restore();
            }
        }
        if (sk.has("bearEars")) {
            for (int s = -1; s <= 1; s += 2) {
                ball(c, s * .62f * u, headC - .58f * u, .22f * u, .22f * u, sk.trim);
                flat(c, s * .62f * u, headC - .58f * u, .11f * u, .11f * u, sk.inner);
            }
        }

        // head
        ball(c, 0, headC, .984f * u, .78f * u, sk.shell);

        if (!back) {
            p.setColor(sk.trim); p.setStyle(Paint.Style.FILL);
            rf.set(fx - .86f * u, headC - .43f * u, fx + .86f * u, headC + .47f * u);
            c.drawRoundRect(rf, .38f * u, .38f * u, p);
            rf.set(fx - .8f * u, headC - .37f * u, fx + .8f * u, headC + .41f * u);
            face.draw(c, rf, b.emo, b.open, b.lookX, b.lookY, t, sk);
        } else {
            flat(c, 0, headC - .05f * u, .22f * u, .22f * u, mix(sk.shell, 0xFF000000, .12f));
        }

        if (sk.has("discs")) {
            for (int s = -1; s <= 1; s += 2) {
                ball(c, s * .97f * u, headC, .2f * u, .2f * u, sk.trim);
                flat(c, s * .99f * u, headC, .11f * u, .11f * u, sk.accent);
            }
        }
        if (sk.has("bow")) {
            c.save(); c.translate(.6f * u, headC - .6f * u); c.rotate(28); c.scale(1 + sin(t * 3) * .04f, 1 + sin(t * 3) * .04f);
            p.setColor(sk.bow); p.setStyle(Paint.Style.FILL);
            for (int s = -1; s <= 1; s += 2) {
                path.reset(); path.moveTo(0, 0); path.lineTo(s * .3f * u, -.16f * u); path.lineTo(s * .3f * u, .16f * u); path.close();
                c.drawPath(path, p);
            }
            c.drawCircle(0, 0, .08f * u, p);
            c.restore();
        }
        if (sk.has("beanie")) {
            c.save();
            c.clipRect(-1.2f * u, headC - 1.3f * u, 1.2f * u, headC - .36f * u);
            ball(c, 0, headC - .02f * u, 1.0f * u, .82f * u, sk.hat);
            c.restore();
            p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.FILL);
            rf.set(-.92f * u, headC - .47f * u, .92f * u, headC - .3f * u);
            c.drawRoundRect(rf, .08f * u, .08f * u, p);
            ball(c, 0, headC - .88f * u + sin(t * 4) * .02f * u, .17f * u, .17f * u, 0xFFFFFFFF);
        }
        if (sk.has("nightcap")) {
            c.save(); c.translate(-.05f * u, headC - .55f * u); c.rotate(-(31 + sin(t * 1.6f) * 4));
            path.reset(); path.moveTo(-.58f * u, 0); path.lineTo(.58f * u, 0); path.lineTo(0, -1.15f * u); path.close();
            p.setColor(sk.hat); p.setStyle(Paint.Style.FILL); c.drawPath(path, p);
            p.setColor(0xFFFFE27A);
            for (int i = 0; i < 4; i++) c.drawCircle((i % 2 == 0 ? -.15f : .12f) * u, -(.22f + i * .2f) * u, .04f * u, p);
            p.setColor(0xFFFFFFFF);
            rf.set(-.62f * u, -.08f * u, .62f * u, .08f * u); c.drawRoundRect(rf, .08f * u, .08f * u, p);
            ball(c, 0, -1.15f * u, .13f * u, .13f * u, 0xFFFFE27A);
            c.restore();
        }
        if (b.headphones) {
            ps.setColor(0xFF3A3150); ps.setStrokeWidth(.1f * u);
            rf.set(-1.0f * u, headC - .95f * u, 1.0f * u, headC + .6f * u);
            c.drawArc(rf, 200, 140, false, ps);
            for (int s = -1; s <= 1; s += 2) {
                p.setColor(0xFFE2468C); p.setStyle(Paint.Style.FILL);
                rf.set(s * 1.0f * u - .15f * u, headC - .25f * u, s * 1.0f * u + .15f * u, headC + .2f * u);
                c.drawRoundRect(rf, .1f * u, .1f * u, p);
            }
        }
        c.restore(); // head group

        // arms (in front)
        for (int s = -1; s <= 1; s += 2) {
            float theta = s > 0 ? k[Blink.AR] : -k[Blink.AL];
            float swing = s > 0 ? k[Blink.ARX] : k[Blink.ALX];
            float len = .34f * u * (.7f + .3f * cos(swing));
            float shx = s * .5f * u, shy = bodyCY - .06f * u;
            float dx = s * sin(theta), dy = cos(theta);
            float hx = shx + dx * len, hy = shy + dy * len;
            ps.setColor(sk.limb); ps.setStrokeWidth(.19f * u);
            c.drawLine(shx, shy, shx + dx * len * .7f, shy + dy * len * .7f, ps);
            ball(c, hx, hy, .13f * u, .13f * u, sk.trim);
        }
        c.restore();
    }
}
