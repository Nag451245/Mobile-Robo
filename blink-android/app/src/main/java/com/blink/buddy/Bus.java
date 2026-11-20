package com.blink.buddy;

import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny in-process event bus: lets the talk window drive the floating Sweety's bubble and animations. */
final class Bus {
    interface Listener { void onNotificationPosted(); }
    interface SweetyListener { void onSweety(String text, String action, String emotion); }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<SweetyListener> SWEETY = new CopyOnWriteArrayList<>();

    static void add(Listener l) { LISTENERS.add(l); }
    static void remove(Listener l) { LISTENERS.remove(l); }
    static void notificationPosted() { for (Listener l : LISTENERS) l.onNotificationPosted(); }

    static void addSweety(SweetyListener l) { SWEETY.add(l); }
    static void removeSweety(SweetyListener l) { SWEETY.remove(l); }
    static void sweety(String text, String action, String emotion) { for (SweetyListener l : SWEETY) l.onSweety(text, action, emotion); }

    private Bus() {}
}
