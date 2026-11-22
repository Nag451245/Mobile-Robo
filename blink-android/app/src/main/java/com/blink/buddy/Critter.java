package com.blink.buddy;

import java.util.Random;

/**
 * A butterfly or a little bird for "Sweety, catch the butterfly / the bird". It flutters in from a screen edge,
 * dodges Sweety once ("Hey, come back!"), lets her catch it, sits in her hands for a moment, then flies away free.
 * Plain Java (no drawing), so the CI tests can play whole chases; {@link CritterArt} draws it.
 */
final class Critter {
    static final int ENTER = 0, FLUTTER = 1, CAUGHT = 2, FREE = 3, DONE = 4;

    final String kind; // "butterfly" or "bird"
    float x, y, size;
    int state = ENTER;
    float t, stateT;
    boolean faceLeft;
    int dodges;
    private float tx, ty, dodgedAt = -10;
    private final float w, h, top, bottom;
    private final Random r;

    /** w, h: the play area; top/bottom: the band of heights it flutters in; size: how big it is drawn. */
    Critter(String kind, float w, float h, float top, float bottom, float size, Random r) {
        this.kind = "bird".equals(kind) ? "bird" : "butterfly";
        this.w = w; this.h = h; this.top = top; this.bottom = bottom; this.size = size; this.r = r;
        boolean fromLeft = r.nextBoolean();
        x = fromLeft ? -size : w + size;
        y = top + r.nextFloat() * (bottom - top) * .6f;
        faceLeft = !fromLeft;
        pickTarget();
    }

    boolean bird() { return "bird".equals(kind); }

    private float speed() {
        float s = (bird() ? .30f : .20f) * w;
        if (stateT > 14 && state == FLUTTER) s *= .35f; // tired out: easy to catch, so a chase never goes on forever
        return s;
    }

    private void pickTarget() {
        tx = w * (.15f + r.nextFloat() * .7f);
        ty = top + r.nextFloat() * (bottom - top);
    }

    /**
     * One step. hx, hy: where Sweety's hands are; reach: how close counts as caught.
     * Returns "dodge", "caught", "free" or "done" when something happens, otherwise null.
     */
    String step(float dt, float hx, float hy, float reach) {
        t += dt; stateT += dt;
        switch (state) {
            case ENTER:
            case FLUTTER: {
                // flutter towards a spot, wobbling (butterflies loop more, birds glide)
                float dx = tx - x, dy = ty - y, d = (float) Math.hypot(dx, dy);
                if (d < size) pickTarget();
                else {
                    float sp = speed() * dt;
                    x += dx / d * sp;
                    y += dy / d * sp + (float) Math.sin(t * (bird() ? 6 : 9)) * size * (bird() ? .6f : 1.4f) * dt;
                    if (Math.abs(dx) > size * .2f) faceLeft = dx < 0;
                }
                if (state == ENTER && stateT > 1.5f) { state = FLUTTER; stateT = 0; }
                if (state == FLUTTER && t - dodgedAt > 1.5f && Math.hypot(x - hx, y - hy) < reach) {
                    if (dodges == 0) {
                        // the first grab misses: it zips away to the other side, and can't be caught for a moment
                        dodges++;
                        dodgedAt = t;
                        tx = hx < w / 2 ? w * (.7f + r.nextFloat() * .2f) : w * (.1f + r.nextFloat() * .2f);
                        ty = top + r.nextFloat() * (bottom - top) * .5f;
                        return "dodge";
                    }
                    state = CAUGHT; stateT = 0;
                    x = hx; y = hy;
                    return "caught";
                }
                return null;
            }
            case CAUGHT:
                x = hx; y = hy;
                if (stateT > 2.4f) {
                    state = FREE; stateT = 0;
                    faceLeft = r.nextBoolean();
                    tx = faceLeft ? -size * 3 : w + size * 3;
                    ty = -size * 3;
                    return "free";
                }
                return null;
            case FREE: {
                float dx = tx - x, dy = ty - y, d = (float) Math.hypot(dx, dy), sp = speed() * 1.3f * dt;
                if (d <= sp || y < -size * 2 || x < -size * 2 || x > w + size * 2) { state = DONE; return "done"; }
                x += dx / d * sp;
                y += dy / d * sp + (float) Math.sin(t * 9) * size * .8f * dt;
                return null;
            }
            default:
                return null;
        }
    }

    boolean done() { return state == DONE; }

    /* ------------------------------------------------------------ what Sweety says */
    String startLine(Random r) {
        return bird()
            ? pick(r, "A birdie! Come here, little bird!", "Tweet tweet! I'm gonna catch you!", "Ooh, a bird! Wait for me!")
            : pick(r, "Ooh, a butterfly! Let me catch it!", "Pretty butterfly! Come here!", "Butterfly! Butterfly! Wait for me!");
    }
    String dodgeLine(Random r) { return pick(r, "Hey, come back!", "Whoa, so fast!", "Almost had it!"); }
    String caughtLine(Random r) { return bird() ? pick(r, "Got you, little birdie! Hehe!", "Caught you! Hello, birdie!") : pick(r, "Got you! Hehe, it tickles!", "Caught it! So pretty!"); }
    String freeLine(Random r) { return pick(r, "Fly free, little friend!", "Bye bye! Come visit again!", "Off you go!"); }

    private static String pick(Random r, String... a) { return a[r.nextInt(a.length)]; }
}
