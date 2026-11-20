package com.blink.buddy;

import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-process event bus so the notification listener can poke the running wallpaper. */
final class Bus {
    interface Listener { void onNotificationPosted(); }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    static void add(Listener l) { LISTENERS.add(l); }
    static void remove(Listener l) { LISTENERS.remove(l); }
    static void notificationPosted() { for (Listener l : LISTENERS) l.onNotificationPosted(); }

    private Bus() {}
}
