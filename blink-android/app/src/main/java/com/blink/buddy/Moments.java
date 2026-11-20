package com.blink.buddy;

import android.content.SharedPreferences;

/** The starter-pack moments. Each can be switched off in the app; all are on by default. */
final class Moments {
    static final String[][] LIST = {
        {"wake-yawn", "Sleepy wake-up", "Screen turns on: Sweety wakes up rubbing her eyes."},
        {"unlock-hello", "Unlock hello", "Unlock: Sweety hops and waves with a new greeting each time."},
        {"missed-you", "Missed you", "First unlock after 3+ hours: heart eyes and a big hello."},
        {"unlock-count", "Unlock counter", "Holds up a sign at 25, 50, 75 and 100 unlocks a day."},
        {"page-walk", "Page-swipe walk", "Swipe home pages and Sweety walks or runs after you."},
        {"tap-hop", "Tap and hop", "Tap empty wallpaper and Sweety hops or flies there."},
        {"see-you", "See you soon", "Says something when you come back from an app."},
        {"morning", "Good morning", "First unlock of the morning gets a sunny hello."},
        {"bedtime", "Bedtime nightcap", "After 10 pm: nightcap on and a gentle \"phone down soon?\"."},
        {"charging", "Charging snack", "Plug in and Sweety sips electrons through a cable."},
        {"low-battery", "Low battery yawn", "Below 15% Sweety gets slow and sleepy."},
        {"headphones", "Headphone groove", "Headphones connected: Sweety puts on her own and grooves."},
        {"shake", "Shake it", "Shake the phone to bounce Sweety around."},
        {"pet", "Pet and purr", "Stroke Sweety with your finger for heart eyes."},
        {"outfit-day", "Outfit of the day", "A new outfit every day. Double-tap Sweety to change it yourself."},
        {"break", "Screen-time nudge", "After 30 minutes unlocked, Sweety suggests a stretch."}
    };

    static boolean on(SharedPreferences p, String id) { return p.getBoolean("m_" + id, true); }

    private Moments() {}
}
