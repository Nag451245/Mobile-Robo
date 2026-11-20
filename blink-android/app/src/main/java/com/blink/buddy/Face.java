package com.blink.buddy;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Blink's screen face: big sparkly eyes, blush and a mouth for each emotion. A port of the web version's drawFace. */
final class Face {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();
    private final RectF rf = new RectF();

    Face() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    private static float sin(double a) { return (float) Math.sin(a); }
    private static float cos(double a) { return (float) Math.cos(a); }

    void draw(Canvas c, RectF R, String emo, float open, float lx, float ly, float t, Skin sk) {
        float W = R.width(), H = R.height(), ox = R.left, oy = R.top;
        c.save();
        clip.reset();
        clip.addRoundRect(R, H * .34f, H * .34f, Path.Direction.CW);
        c.clipPath(clip);
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(0, oy, 0, oy + H, sk.bg0, sk.bg1, Shader.TileMode.CLAMP));
        c.drawRect(R, fill);
        fill.setShader(null);

        int col = "angry".equals(emo) ? 0xFFFF7A5C : "love".equals(emo) ? 0xFFFF6FAE : sk.eye;
        float r = H * .25f, dx = lx * r * .45f, dy = ly * r * .32f, ey = H * .43f;
        boolean strong = "happy".equals(emo) || "love".equals(emo) || "wink".equals(emo) || "laugh".equals(emo);

        fill.setColor(sk.blush);
        fill.setAlpha(strong ? 215 : 115);
        for (int s = -1; s <= 1; s += 2) {
            float bx = ox + W * .5f + s * W * .31f + dx * .5f, by = oy + H * .7f + dy * .4f;
            rf.set(bx - W * .06f, by - H * .055f, bx + W * .06f, by + H * .055f);
            c.drawOval(rf, fill);
        }
        fill.setAlpha(255);

        for (int side = -1; side <= 1; side += 2) {
            float cx = ox + W * .5f + side * W * .165f + dx, cy = oy + ey + dy;
            String e = emo;
            if ("wink".equals(e)) e = side < 0 ? "happy" : "neutral";
            switch (e) {
                case "happy": case "laugh":
                    arc(c, cx, cy + r * .35f, r * .6f, 201.6f, 136.8f, r * .34f, col); break;
                case "sleep":
                    arc(c, cx, cy - r * .3f, r * .58f, 32.4f, 115.2f, r * .24f, col); break;
                case "sleepy":
                    blob(c, cx, cy, r * 1.5f, r * 2 * open * .3f, col, r, false); break;
                case "sad":
                    c.save(); lid(c, cx, cy, r, side, cy - r * .95f, cy - r * .15f); blob(c, cx, cy, r * 1.5f, r * 1.9f * open, col, r, true); c.restore(); break;
                case "angry":
                    c.save(); lid(c, cx, cy, r, side, cy - r * .05f, cy - r * .9f); blob(c, cx, cy, r * 1.55f, r * 1.8f * open, col, r, false); c.restore(); break;
                case "surprised": {
                    float k = 1.1f + sin(t * 9) * .04f;
                    stroke.setColor(col); stroke.setStrokeWidth(r * .26f);
                    c.drawCircle(cx, cy, Math.max(1, r * k * open), stroke);
                    fill.setColor(col); c.drawCircle(cx, cy, r * .34f, fill);
                    break;
                }
                case "love":
                    heart(c, cx, cy, r * 1.15f * (1 + .08f * sin(t * 8)), col); break;
                case "dizzy": {
                    path.reset();
                    float rot = t * 6 * side;
                    for (int i = 0; i <= 64; i++) {
                        float a = i / 64f * 2.6f * (float) Math.PI * 2, rr = i / 64f * r * .95f;
                        float px = cx + cos(a + rot) * rr, py = cy + sin(a + rot) * rr;
                        if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                    }
                    stroke.setColor(col); stroke.setStrokeWidth(r * .16f); c.drawPath(path, stroke);
                    break;
                }
                case "curious": {
                    float s = side > 0 ? 1.2f : .86f;
                    blob(c, cx, cy, r * 1.5f * s, r * 2 * open * s, col, r, true);
                    break;
                }
                default:
                    blob(c, cx, cy, r * 1.5f, r * 2 * open, col, r, true);
            }
        }

        // mouth
        float mx = ox + W * .5f + dx * .6f, my = oy + H * .76f + dy * .4f, m = H * .055f, lw = H * .028f;
        switch (emo) {
            case "happy": case "laugh": case "wink": {
                float k = "laugh".equals(emo) ? 1.25f + Math.abs(sin(t * 14)) * .25f : 1.05f;
                rf.set(mx - m * k, my - m * .4f - m * k, mx + m * k, my - m * .4f + m * k);
                path.reset(); path.arcTo(rf, 0, 180, true); path.close();
                fill.setColor(col); c.drawPath(path, fill);
                fill.setColor(0xFFFF8FB1);
                rf.set(mx - m * .5f * k, my + m * .32f * k - m * .24f * k, mx + m * .5f * k, my + m * .32f * k + m * .24f * k);
                c.drawOval(rf, fill);
                break;
            }
            case "sad": arc(c, mx, my + m * .85f, m * .85f, 216, 108, lw, col); break;
            case "surprised":
                stroke.setColor(col); stroke.setStrokeWidth(lw);
                rf.set(mx - m * .5f, my - m * .65f, mx + m * .5f, my + m * .65f); c.drawOval(rf, stroke); break;
            case "angry":
                path.reset(); path.moveTo(mx - m, my); path.lineTo(mx - m * .5f, my - m * .35f); path.lineTo(mx, my);
                path.lineTo(mx + m * .5f, my - m * .35f); path.lineTo(mx + m, my);
                stroke.setColor(col); stroke.setStrokeWidth(lw); c.drawPath(path, stroke); break;
            case "dizzy":
                path.reset();
                for (int i = 0; i <= 20; i++) {
                    float px = mx - m * 1.2f + i / 20f * m * 2.4f, py = my + sin(i / 20f * Math.PI * 4 + t * 6) * m * .25f;
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                stroke.setColor(col); stroke.setStrokeWidth(lw); c.drawPath(path, stroke); break;
            case "sleep":
                fill.setColor(col); c.drawCircle(mx, my, m * .28f * (1 + .2f * sin(t * 1.5f)), fill); break;
            case "sleepy":
                stroke.setColor(col); stroke.setStrokeWidth(lw); c.drawLine(mx - m * .4f, my, mx + m * .4f, my, stroke); break;
            case "love":
                catMouth(c, mx, my, m, lw, col); break;
            default:
                if (sk.catMouth) catMouth(c, mx, my, m, lw, col);
                else arc(c, mx, my - m * .65f, m * .85f, 36, 108, lw, col);
        }

        // extras
        text.setColor(col);
        if ("sad".equals(emo)) {
            float p = (t * .7f) % 1f;
            fill.setColor(0xFF8FDCFF); fill.setAlpha((int) (255 * (1 - p)));
            c.drawCircle(ox + W * .375f + dx, oy + H * .62f + p * H * .3f, H * .035f, fill);
            fill.setAlpha(255);
        }
        if ("sleep".equals(emo)) {
            text.setTextSize(H * .15f);
            for (int i = 0; i < 3; i++) {
                float p = (t * .35f + i / 3f) % 1f;
                text.setAlpha((int) (255 * Math.max(0, sin(p * Math.PI))));
                c.drawText("z", ox + W * .82f + p * W * .08f, oy + H * .6f - p * H * .42f, text);
            }
            text.setAlpha(255);
        }
        if ("curious".equals(emo)) { text.setTextSize(H * .22f); c.drawText("?", ox + W * .9f, oy + H * .36f + sin(t * 3) * H * .03f, text); }
        if ("surprised".equals(emo)) { text.setTextSize(H * .22f); c.drawText("!", ox + W * .9f, oy + H * .34f, text); }
        if ("love".equals(emo)) {
            for (int i = 0; i < 2; i++) {
                float p = (t * .4f + i / 2f) % 1f;
                int a = (int) (255 * Math.max(0, sin(p * Math.PI)));
                heart(c, ox + W * (.12f + i * .76f), oy + H * .5f - p * H * .35f, H * .05f, (a << 24) | 0x00FF8FC0);
            }
        }
        c.restore();
    }

    private void arc(Canvas c, float cx, float cy, float rad, float start, float sweep, float w, int col) {
        rf.set(cx - rad, cy - rad, cx + rad, cy + rad);
        stroke.setColor(col); stroke.setStrokeWidth(w);
        c.drawArc(rf, start, sweep, false, stroke);
    }

    private void catMouth(Canvas c, float mx, float my, float m, float lw, int col) {
        arc(c, mx - m * .48f, my - m * .25f, m * .48f, 0, 180, lw, col);
        arc(c, mx + m * .48f, my - m * .25f, m * .48f, 0, 180, lw, col);
    }

    private void blob(Canvas c, float cx, float cy, float w, float h, int col, float r, boolean shine) {
        h = Math.max(h, r * .12f);
        float rad = Math.min(w, h) / 2f, g = r * .1f;
        fill.setColor(col); fill.setAlpha(70);
        rf.set(cx - w / 2 - g, cy - h / 2 - g, cx + w / 2 + g, cy + h / 2 + g);
        c.drawRoundRect(rf, rad + g, rad + g, fill);
        fill.setAlpha(255);
        rf.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        c.drawRoundRect(rf, rad, rad, fill);
        if (shine && h >= r * .9f) {
            fill.setColor(0xF2FFFFFF);
            c.drawCircle(cx - w * .2f, cy - h * .22f, r * .2f, fill);
            c.drawCircle(cx + w * .17f, cy + h * .2f, r * .09f, fill);
        }
    }

    private void lid(Canvas c, float cx, float cy, float r, int side, float yi, float yo) {
        float xi = cx - side * r, xo = cx + side * r, s = (yo - yi) / (xo - xi), xl = cx - 2.2f * r, xr = cx + 2.2f * r;
        path.reset();
        path.moveTo(xl, yi + s * (xl - xi)); path.lineTo(xr, yi + s * (xr - xi));
        path.lineTo(xr, cy + 3 * r); path.lineTo(xl, cy + 3 * r); path.close();
        c.clipPath(path);
    }

    void heart(Canvas c, float cx, float cy, float s, int col) {
        path.reset();
        path.moveTo(cx, cy + .7f * s);
        path.cubicTo(cx - 1.15f * s, cy, cx - .6f * s, cy - .95f * s, cx, cy - .35f * s);
        path.cubicTo(cx + .6f * s, cy - .95f * s, cx + 1.15f * s, cy, cx, cy + .7f * s);
        path.close();
        fill.setColor(col);
        c.drawPath(path, fill);
    }
}
