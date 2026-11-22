package com.blink.buddy;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Draws {@link Critter}s: a pink butterfly with flapping wings, or a little blue bird. */
final class CritterArt {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path path = new Path();

    /** cx, cy: centre; s: size in pixels; t: time in seconds (wing beats). */
    void draw(Canvas c, String kind, float cx, float cy, float s, float t, boolean faceLeft, boolean resting) {
        c.save();
        c.translate(cx, cy);
        if (faceLeft) c.scale(-1, 1);
        if ("bird".equals(kind)) bird(c, s, t, resting); else butterfly(c, s, t, resting);
        c.restore();
    }

    private void butterfly(Canvas c, float s, float t, boolean resting) {
        float flap = resting ? .35f + .1f * (float) Math.sin(t * 3) : .25f + .75f * Math.abs((float) Math.cos(t * 14));
        p.setStyle(Paint.Style.FILL);
        for (int side = -1; side <= 1; side += 2) {
            c.save();
            c.scale(side * flap, 1);
            p.setColor(0xFFFF7EB6); // upper wing
            rf.set(0, -s * .48f, s * .5f, s * .06f); c.drawOval(rf, p);
            p.setColor(0xFFB57BFF); // lower wing
            rf.set(0, -s * .02f, s * .36f, s * .38f); c.drawOval(rf, p);
            p.setColor(0xFFFFFFFF); // spots
            c.drawCircle(s * .3f, -s * .22f, s * .06f, p);
            c.drawCircle(s * .2f, s * .18f, s * .04f, p);
            c.restore();
        }
        p.setColor(0xFF3A2C4F); // body and feelers
        rf.set(-s * .05f, -s * .3f, s * .05f, s * .3f); c.drawOval(rf, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.5f, s * .03f)); p.setStrokeCap(Paint.Cap.ROUND);
        path.reset(); path.moveTo(0, -s * .28f); path.quadTo(-s * .06f, -s * .44f, -s * .14f, -s * .5f);
        path.moveTo(0, -s * .28f); path.quadTo(s * .06f, -s * .44f, s * .14f, -s * .5f);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void bird(Canvas c, float s, float t, boolean resting) {
        float flap = resting ? 0 : (float) Math.sin(t * 16);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF4FA8FF); // tail
        path.reset(); path.moveTo(-s * .3f, 0); path.lineTo(-s * .55f, -s * .12f); path.lineTo(-s * .55f, s * .14f); path.close(); c.drawPath(path, p);
        p.setColor(0xFF6EC1FF); // body
        rf.set(-s * .35f, -s * .2f, s * .25f, s * .22f); c.drawOval(rf, p);
        p.setColor(0xFFE8F6FF); // belly
        rf.set(-s * .15f, -s * .02f, s * .2f, s * .2f); c.drawOval(rf, p);
        p.setColor(0xFF6EC1FF); // head
        c.drawCircle(s * .28f, -s * .16f, s * .16f, p);
        p.setColor(0xFFFFB13D); // beak
        path.reset(); path.moveTo(s * .42f, -s * .2f); path.lineTo(s * .58f, -s * .14f); path.lineTo(s * .42f, -s * .09f); path.close(); c.drawPath(path, p);
        p.setColor(0xFF231B33); // eye
        c.drawCircle(s * .32f, -s * .2f, s * .035f, p);
        p.setColor(0xFFFF8FB1); // cheek
        c.drawCircle(s * .25f, -s * .1f, s * .035f, p);
        p.setColor(0xFF3D8EE6); // wing
        c.save();
        c.rotate(-25 - flap * 45, -s * .05f, -s * .05f);
        rf.set(-s * .3f, -s * .14f, s * .12f, s * .06f); c.drawOval(rf, p);
        c.restore();
    }

    /** A tiny see-through view for the floating Sweety's critter window. */
    static final class CritterView extends View {
        private final CritterArt art = new CritterArt();
        Critter critter;
        CritterView(android.content.Context ctx) { super(ctx); }
        @Override protected void onDraw(Canvas c) {
            Critter k = critter;
            if (k == null) return;
            art.draw(c, k.kind, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) * .8f, k.t, k.faceLeft, k.state == Critter.CAUGHT);
        }
    }
}
