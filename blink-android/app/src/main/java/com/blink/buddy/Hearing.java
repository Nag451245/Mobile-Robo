package com.blink.buddy;

import android.content.Context;

/** Picks how Sweety hears: offline Whistle when it is loaded, otherwise the phone's own speech service. */
final class Hearing {
    private final Context ctx;
    private final Ears phone;
    private final LocalEars local;

    Hearing(Context ctx, Ears.Callback cb) {
        this.ctx = ctx;
        phone = new Ears(ctx, cb);
        local = new LocalEars(ctx, cb);
    }

    void listen() {
        if (Assistant.get(ctx).localHearing() || !phone.available()) local.listen();
        else phone.listen();
    }

    boolean listening() { return phone.listening() || local.listening(); }

    void stop() { phone.stop(); local.stop(); }

    void destroy() { local.stop(); phone.destroy(); }
}
