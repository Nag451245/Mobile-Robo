package com.blink.buddy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Picks how Sweety hears: offline Whistle when it is loaded, otherwise the phone's own speech service.
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
    }

    void listen() {
        boolean wakeWasOn = WakeService.hold(20000);
        Runnable go = () -> {
            if (Assistant.get(ctx).localHearing() || !phone.available()) local.listen();
            else phone.listen();
        };
        if (wakeWasOn) h.postDelayed(go, 250); else go.run(); // give the wake listener a moment to close the mic
    }

    boolean listening() { return phone.listening() || local.listening(); }

    void stop() { phone.stop(); local.stop(); WakeService.hold(2500); }

    void destroy() { h.removeCallbacksAndMessages(null); local.stop(); phone.destroy(); WakeService.release(); }
}
