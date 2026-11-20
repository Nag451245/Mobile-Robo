package com.blink.buddy;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Sweety's sense of time: which part of the day it is, and how she acts and talks in it.
 * Sleepy and yawny late at night, stretchy in the early morning, chirpy in the morning, hungry at lunch,
 * dancy in the afternoon, cosy in the evening. Plain Java, so the CI tests run it for every hour.
 */
final class DayTime {
    static final int LATE = 0, EARLY = 1, MORNING = 2, NOON = 3, AFTERNOON = 4, EVENING = 5, NIGHT = 6;
    static final String[] NAMES = {"late at night", "early morning", "morning", "lunchtime", "afternoon", "evening", "night"};

    private DayTime() {}

    static int part(int hour) {
        if (hour < 5) return LATE;
        if (hour < 8) return EARLY;
        if (hour < 12) return MORNING;
        if (hour < 14) return NOON;
        if (hour < 17) return AFTERNOON;
        if (hour < 21) return EVENING;
        return NIGHT;
    }

    static int hour() { return Calendar.getInstance().get(Calendar.HOUR_OF_DAY); }
    static int minute() { return Calendar.getInstance().get(Calendar.MINUTE); }
    static int now() { return part(hour()); }
    static boolean sleepy(int part) { return part == LATE || part == NIGHT; }

    static String clock(int h, int m) {
        int hh = h % 12 == 0 ? 12 : h % 12;
        return hh + (m == 0 ? "" : String.format(Locale.ROOT, ":%02d", m)) + (h < 12 ? " AM" : " PM");
    }

    private static <T> T pick(Random r, T[] a) { return a[r.nextInt(a.length)]; }

    /** An idle antic for this time of day: {action, emotion}. "sleep" means a little doze. */
    static String[] idle(int part, Random r) {
        String[][] o;
        switch (part) {
            case LATE: o = new String[][]{{"sleep", "sleep"}, {"sleep", "sleep"}, {"sleep", "sleep"}, {"stretch", "sleepy"}, {"nod", "sleepy"}}; break;
            case EARLY: o = new String[][]{{"stretch", "sleepy"}, {"stretch", "sleepy"}, {"stretch", "happy"}, {"wave", "happy"}, {"nod", "sleepy"}}; break;
            case MORNING: o = new String[][]{{"stretch", "happy"}, {"wave", "happy"}, {"jump", "happy"}, {"dance", "happy"}, {"nod", "curious"}}; break;
            case NOON: o = new String[][]{{"dance", "happy"}, {"jump", "happy"}, {"wave", "happy"}, {"nod", "happy"}, {"shrug", "curious"}}; break;
            case AFTERNOON: o = new String[][]{{"dance", "happy"}, {"dance", "happy"}, {"spin", "dizzy"}, {"jump", "surprised"}, {"cartwheel", "dizzy"}, {"flip", "surprised"}}; break;
            case EVENING: o = new String[][]{{"wave", "happy"}, {"spin", "dizzy"}, {"dance", "happy"}, {"stretch", "happy"}, {"nod", "love"}}; break;
            default: o = new String[][]{{"stretch", "sleepy"}, {"stretch", "sleepy"}, {"nod", "sleepy"}, {"wave", "sleepy"}, {"sleep", "sleep"}};
        }
        return pick(r, o);
    }

    /** Something she might say out of the blue at this time of day. */
    static String idleLine(int part, Random r) {
        switch (part) {
            case LATE: return pick(r, new String[]{"It's past midnight… shouldn't we both be asleep?", "*yawn* Even robots need sleep, you know.", "Shh, the whole world is sleeping.", "Five more minutes of phone, then bed. Deal?"});
            case EARLY: return pick(r, new String[]{"So early! The birds are still yawning.", "*stretch* Is it morning already?", "Early bird! Fresh start today."});
            case MORNING: return pick(r, new String[]{"Good morning! Had your breakfast?", "Sunny brain, sunny day!", "Morning energy: one hundred percent!"});
            case NOON: return pick(r, new String[]{"Lunch time! I'd have a battery sandwich.", "Did you eat yet? Food first!", "Midday break? I'll guard the phone."});
            case AFTERNOON: return pick(r, new String[]{"Afternoon slump? Let's dance it off!", "Sip some water, okay?", "Snack o'clock soon!"});
            case EVENING: return pick(r, new String[]{"Golden hour! The sky looks like candy.", "How was your day?", "Evening! Time to relax a little."});
            default: return pick(r, new String[]{"It's getting late. Phone down soon?", "Cosy night vibes.", "*yawn* Almost bedtime…"});
        }
    }

    /** A little announcement at the top of the hour, with a matching antic: {text, action, emotion}. */
    static String[] hourly(int hour, Random r) {
        String t = hour == 0 ? "It's midnight! " : hour == 12 ? "It's noon! " : "It's " + clock(hour, 0) + "! ";
        switch (part(hour)) {
            case LATE: return new String[]{t + "Way past bedtime. *yawn*", "stretch", "sleepy"};
            case EARLY: return new String[]{t + "Rise and shine!", "stretch", "happy"};
            case MORNING: return new String[]{t + pick(r, new String[]{"Let's get things done!", "Fresh morning hour!"}), "jump", "happy"};
            case NOON: return new String[]{t + "Lunch break!", "dance", "happy"};
            case AFTERNOON: return new String[]{t + pick(r, new String[]{"Wiggle break!", "Stretch your legs!"}), "dance", "happy"};
            case EVENING: return new String[]{t + "Evening already!", "wave", "happy"};
            default: return new String[]{t + "Getting sleepy…", "stretch", "sleepy"};
        }
    }

    private static final java.util.regex.Pattern GREETING = java.util.regex.Pattern.compile(
        "\\W*(?:(?:hey|hi|hello|hiya|yo|oh|okay|ok)\\W+)*(hi|hello|hey|hiya|yo|howdy|good (morning|afternoon|evening|night|day)|morning|evening|night night|nighty night|gm|gn)"
        + "(?:\\W+(?:sweety|sweetie|sweet|there|buddy|friend|to you))*\\W*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * A reply to a plain greeting that fits the time: {text, action, emotion}, or null if it's not a plain greeting.
     * "Good morning" at 11 pm gets teased; "good night" makes her doze off.
     */
    static String[] greet(String text, int hour, int minute, Random r) {
        if (text == null) return null;
        java.util.regex.Matcher m = GREETING.matcher(text.trim());
        if (!m.matches()) return null;
        String said = m.group(1).toLowerCase(Locale.ROOT);
        String time = m.group(2) != null ? m.group(2).toLowerCase(Locale.ROOT) : said.equals("morning") || said.equals("gm") ? "morning"
            : said.equals("evening") ? "evening" : said.contains("night") || said.equals("gn") ? "night" : null;
        int p = part(hour);
        String now = clock(hour, minute);
        if ("night".equals(time)) {
            if (sleepy(p) || p == EVENING && hour >= 20) return new String[]{pick(r, new String[]{"Good night! Sweet dreams!", "Night night! Sleep tight!", "Sweet dreams! I'll keep watch."}), "sleep", "sleep"};
            return new String[]{"Good night? It's only " + now + "! Are you taking a nap? Hehe.", "laugh", "laugh"};
        }
        if ("morning".equals(time)) {
            if (p == EARLY || p == MORNING) return new String[]{pick(r, new String[]{"Good morning, sunshine!", "Good morning to you too! Rise and shine!", "Morning! Ready for a great day?"}), "stretch", "happy"};
            if (p == NOON || p == AFTERNOON) return new String[]{"Morning? It's already " + now + ", sleepyhead! Hehe.", "laugh", "laugh"};
            return new String[]{"Morning? It's " + now + " at night, silly! Hehe.", "laugh", "laugh"};
        }
        if ("afternoon".equals(time)) {
            if (p == NOON || p == AFTERNOON) return new String[]{"Good afternoon! What's up?", "wave", "happy"};
            return new String[]{"Afternoon? It's " + now + "! Hehe, lost track of time?", "laugh", "laugh"};
        }
        if ("evening".equals(time)) {
            if (p == EVENING || p == NIGHT) return new String[]{"Good evening! How was your day?", "wave", "happy"};
            return new String[]{"Evening? It's only " + now + "! Hehe.", "laugh", "laugh"};
        }
        if ("day".equals(time)) return new String[]{"Good day to you too!", "wave", "happy"};
        switch (p) {
            case LATE: return new String[]{"Hi! It's " + now + "… why are we awake? Hehe.", "wave", "sleepy"};
            case EARLY: return new String[]{"Good morning, early bird!", "stretch", "happy"};
            case MORNING: return new String[]{pick(r, new String[]{"Good morning! How can I help?", "Morning! What shall we do?"}), "wave", "happy"};
            case NOON: return new String[]{"Hi! Happy lunchtime!", "wave", "happy"};
            case AFTERNOON: return new String[]{"Good afternoon! What's up?", "wave", "happy"};
            case EVENING: return new String[]{"Good evening! How was your day?", "wave", "happy"};
            default: return new String[]{"Hi! It's getting late, but I'm here for you.", "wave", "sleepy"};
        }
    }

    /** The time, said the way the chat brain should know it. */
    static String chatContext(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        String day = c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH);
        int p = part(h);
        String mood = p == LATE ? " It's very late, so you're sleepy and yawn a little, and gently suggest sleep."
            : p == NIGHT ? " It's late evening, so you're cosy and a bit sleepy."
            : p == EARLY ? " It's early, so you're just waking up."
            : p == NOON ? " It's lunchtime." : "";
        return "Right now it is " + clock(h, m) + " on " + day + " " + NAMES[p] + "." + mood;
    }

    /** Small requests late at night get a sleepy twist. */
    static String lateTwist(String action, Random r) {
        switch (action) {
            case "dance": return pick(r, new String[]{"Shh, a tiny midnight dance!", "Quiet dance party, the neighbours are asleep!"});
            case "jump": return "Boing… softly, it's late!";
            case "spin": return "Wheee… *yawn* …ee!";
            case "flip": return "A sleepy backflip! Ta-da… *yawn*";
            case "cartwheel": return "Midnight cartwheel! Don't tell anyone.";
            default: return null;
        }
    }
}
