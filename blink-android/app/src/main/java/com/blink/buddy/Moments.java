package com.blink.buddy;

import android.content.SharedPreferences;

/** The starter-pack moments. Each can be switched off in the app; all are on by default. */
final class Moments {
    static final String[][] LIST = {
        {"wake-yawn", "Sleepy wake-up", "Screen turns on: Blink wakes up rubbing its eyes."},
        {"unlock-hello", "Unlock hello", "Unlock: Blink hops and waves with a new greeting each time."},
        {"missed-you", "Missed you", "First unlock after 3+ hours: heart eyes and a big hello."},
        {"unlock-count", "Unlock counter", "Holds up a sign at 25, 50, 75 and 100 unlocks a day."},
        {"page-walk", "Page-swipe walk", "Swipe home pages and Blink walks or runs after you."},
        {"tap-hop", "Tap and hop", "Tap empty wallpaper and Blink hops or flies there."},
        {"see-you", "See you soon", "Says something when you come back from an app."},
        {"morning", "Good morning", "First unlock of the morning gets a sunny hello."},
        {"bedtime", "Bedtime nightcap", "After 10 pm: nightcap on and a gentle \"phone down soon?\"."},
        {"charging", "Charging snack", "Plug in and Blink sips electrons through a cable."},
        {"low-battery", "Low battery yawn", "Below 15% Blink gets slow and sleepy."},
        {"headphones", "Headphone groove", "Headphones connected: Blink puts on its own and grooves."},
        {"shake", "Shake it", "Shake the phone to bounce Blink around."},
        {"pet", "Pet and purr", "Stroke Blink with your finger for heart eyes."},
        {"outfit-day", "Outfit of the day", "A new outfit every day. Double-tap Blink to change it yourself."},
        {"break", "Screen-time nudge", "After 30 minutes unlocked, Blink suggests a stretch."}
    };

    static boolean on(SharedPreferences p, String id) { return p.getBoolean("m_" + id, true); }

    private Moments() {}
}
