package com.blink.buddy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks lines so Sweety doesn't repeat herself: every line in a set is used once (in random order) before any comes
 * back, and the last one never comes straight back after a reshuffle. What's been used is remembered across restarts
 * (the app plugs in its settings as the store). Plain Java, so the CI tests run it.
 */
final class Fresh {
    interface Store { String get(String key); void put(String key, String value); }

    /** In memory until the app plugs in its settings. */
    static volatile Store store = new Store() {
        private final Map<String, String> m = new HashMap<>();
        @Override public synchronized String get(String k) { return m.get(k); }
        @Override public synchronized void put(String k, String v) { m.put(k, v); }
    };

    private Fresh() {}

    /** A line from the set called key that hasn't been used since the set was last used up. */
    static synchronized String pick(String key, String[] pool, Random r) {
        if (pool.length == 1) return pool[0];
        // The set's size is part of its name, so changing a set in an update starts it afresh.
        String k = "fresh." + key + "." + pool.length;
        String saved = store.get(k);
        boolean[] used = new boolean[pool.length];
        int last = -1, nUsed = 0;
        if (saved != null && !saved.isEmpty()) {
            for (String s : saved.split(",")) {
                try {
                    int i = Integer.parseInt(s);
                    if (i >= 0 && i < pool.length && !used[i]) { used[i] = true; nUsed++; last = i; }
                } catch (NumberFormatException ignored) { }
            }
        }
        if (nUsed >= pool.length) { // all used: start a new round, but not with the line just said
            used = new boolean[pool.length];
            saved = last >= 0 ? String.valueOf(last) : "";
            if (last >= 0) used[last] = true;
        }
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < pool.length; i++) if (!used[i]) free.add(i);
        int choice = free.get(r.nextInt(free.size()));
        // In a new round the line just said is only a placeholder: drop it once something else has been picked.
        boolean newRound = nUsed >= pool.length;
        store.put(k, (newRound || saved == null || saved.isEmpty() ? "" : saved + ",") + choice);
        return pool[choice];
    }
}
