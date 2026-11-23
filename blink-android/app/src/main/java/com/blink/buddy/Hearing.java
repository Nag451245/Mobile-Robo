package com.blink.buddy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Picks how Sweety hears. First choice: the phone's own speech recogniser (Google's on most phones), which copes
 * with accents and noisy rooms far better than a tiny offline model. If it isn't there, or its service fails
 * (no internet and no offline pack, busy, …), offline Whistle takes over.
 * While it listens, the "Hey Sweety" listener steps aside so the two don't fight over the microphone.
 */
final class Hearing {
    private final Context ctx;
    private final Ears phone;
    private final LocalEars local;
    private final Handler h = new Handler(Looper.getMainLooper());

    Hearing(Context ctx, Ears.Callback cb) {
        this.ctx = ctx;
        phone = new Ears(ctx, cb);
        local = new LocalEars(ctx, cb);
        phone.fallback = () -> {
            if (Assistant.get(ctx).localHearing()) local.listen();
            else cb.onProblem("Speech recognition isn't working right now. You can type to me instead.");
        };
    }

    void listen() {
        boolean wakeWasOn = WakeService.hold(20000);
        Runnable go = () -> {
            if (phone.available()) phone.listen();
            else local.listen();
        };
        if (wakeWasOn) h.postDelayed(go, 250); else go.run(); // give the wake listener a moment to close the mic
    }

    /** The rest of "Hey Sweety, ..." said in one breath: the phone's recogniser if it can take a recording, else Whistle. */
    void recognize(float[] pcm) {
        if (!phone.recognizeAudio(pcm, () -> local.transcribe(pcm))) local.transcribe(pcm);
    }

    boolean listening() { return phone.listening() || local.listening(); }

    void stop() { phone.stop(); local.stop(); WakeService.hold(2500); }

    void destroy() { h.removeCallbacksAndMessages(null); local.stop(); phone.destroy(); WakeService.release(); }
}
