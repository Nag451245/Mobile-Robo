package com.blink.buddy;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** In-app preview: the same Blink as the wallpaper, so every moment can be tried before setting it. */
public class BlinkView extends View {
    final Blink blink;
    private long last;

    public BlinkView(Context ctx) {
        super(ctx);
        blink = new Blink(ctx);
        blink.locked = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { blink.setSize(w, h); }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 1 / 60f : Math.min(.1f, (now - last) / 1000f);
        last = now;
        blink.step(dt);
        blink.draw(c);
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        blink.onTouch(a, e.getX(), e.getY());
        if (a == MotionEvent.ACTION_UP && e.getEventTime() - e.getDownTime() < 300) blink.onTap(e.getX(), e.getY());
        return true;
    }
}
