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

    /** A line from a set that she hasn't said lately (see Fresh), with {now} filled in. */
    private static String fresh(String key, String[] pool, Random r, String now) { return Fresh.pick(key, pool, r).replace("{now}", now == null ? "" : now); }

    /** Something she might say out of the blue at this time of day. */
    static String idleLine(int part, Random r) {
        switch (part) {
            case LATE: return fresh("idle.late", new String[]{"It's past midnight… shouldn't we both be asleep?", "*yawn* Even robots need sleep, you know.", "Shh, the whole world is sleeping.", "Five more minutes of phone, then bed. Deal?", "The moon is watching. Act natural.", "I'm not sleepy. *yaaawn* Okay, a tiny bit."}, r, null);
            case EARLY: return fresh("idle.early", new String[]{"So early! The birds are still yawning.", "*stretch* Is it morning already?", "Early bird! Fresh start today.", "The sun is still loading… 80 percent…", "Shh, I can hear the birds practising."}, r, null);
            case MORNING: return fresh("idle.morning", new String[]{"Good morning! Had your breakfast?", "Sunny brain, sunny day!", "Morning energy: one hundred percent!", "Water first, then world domination. Kidding!", "I polished my antenna. Notice anything?"}, r, null);
            case NOON: return fresh("idle.noon", new String[]{"Lunch time! I'd have a battery sandwich.", "Did you eat yet? Food first!", "Midday break? I'll guard the phone.", "My tummy says lunch. Wait, I don't have a tummy.", "Lunch idea: something yummy. You're welcome."}, r, null);
            case AFTERNOON: return fresh("idle.afternoon", new String[]{"Afternoon slump? Let's dance it off!", "Sip some water, okay?", "Snack o'clock soon!", "I tried a nap. I'm too bouncy.", "Stretch break! Arms up! Okay, I'm done, you keep going."}, r, null);
            case EVENING: return fresh("idle.evening", new String[]{"Golden hour! The sky looks like candy.", "How was your day?", "Evening! Time to relax a little.", "The sky is doing its pretty colours again. Show-off.", "Dinner time soon? I'll have the battery soup."}, r, null);
            default: return fresh("idle.night", new String[]{"It's getting late. Phone down soon?", "Cosy night vibes.", "*yawn* Almost bedtime…", "Pyjama time? Mine are invisible.", "The stars are out. So are your eyebags. Hehe, kidding!"}, r, null);
        }
    }

    /** What her talk card says when "Hey Sweety" (or the assistant button) wakes her, by time of day. Never the same twice in a row. */
    static String wokeLine(int part, Random r) {
        switch (part) {
            case EARLY: case MORNING: return fresh("woke.morning", new String[]{"Up with the birds! What do you need?", "Tweet tweet! I'm here.", "Morning! I'm all ears. Well, all antenna.", "Chirp! Ready when you are.", "Bright and early! Tell me.", "The birds called me. Then you did. What's up?"}, r, null);
            case NOON: case AFTERNOON: return fresh("woke.day", new String[]{"You called? I was mid-dance!", "Here! What's up?", "Sweety at your service!", "Ears on! Go ahead.", "Beep boop, listening!", "Ooh, me? Yes? Tell me!"}, r, null);
            case EVENING: return fresh("woke.evening", new String[]{"Evening! What can I do?", "Here I am! Tell me.", "Golden hour Sweety, listening!", "Yes? I'm all yours.", "The crickets and I are listening!"}, r, null);
            default: return fresh("woke.night", new String[]{"*yawn* I'm here… what's up?", "Shh, whisper it to me.", "Night owl mode on. Listening!", "Sleepy, but listening!", "The owls and I are all ears."}, r, null);
        }
    }

    /** What she says out loud when woken but she can't pop up her card: short, so she listens again quickly. */
    static String yesLine(Random r) {
        return fresh("yes", new String[]{"Yes?", "I'm here!", "You rang?", "Present!", "Yes, sweetie?", "At your service!", "Ears on!", "Hi hi! What's up?", "Sweety here!", "Ooh, me? Yes?"}, r, null);
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
        // Every set is funny and fits the time; Fresh makes sure she goes through a whole set before repeating.
        if ("night".equals(time)) {
            if (sleepy(p) || p == EVENING && hour >= 20) return new String[]{fresh("gn", GOOD_NIGHT, r, now), "sleep", "sleep"};
            return new String[]{fresh("gn.day", NIGHT_TOO_EARLY, r, now), "laugh", "laugh"};
        }
        if ("morning".equals(time)) {
            if (p == EARLY || p == MORNING) return new String[]{fresh("gm", GOOD_MORNING, r, now), "stretch", "happy"};
            if (p == NOON || p == AFTERNOON) return new String[]{fresh("gm.late", MORNING_TOO_LATE, r, now), "laugh", "laugh"};
            return new String[]{fresh("gm.night", MORNING_AT_NIGHT, r, now), "laugh", "laugh"};
        }
        if ("afternoon".equals(time)) {
            if (p == NOON || p == AFTERNOON) return new String[]{fresh("ga", GOOD_AFTERNOON, r, now), "wave", "happy"};
            return new String[]{fresh("ga.wrong", AFTERNOON_WRONG, r, now), "laugh", "laugh"};
        }
        if ("evening".equals(time)) {
            if (p == EVENING || p == NIGHT) return new String[]{fresh("ge", GOOD_EVENING, r, now), "wave", "happy"};
            return new String[]{fresh("ge.wrong", EVENING_TOO_EARLY, r, now), "laugh", "laugh"};
        }
        if ("day".equals(time)) return new String[]{fresh("gd", GOOD_DAY, r, now), "wave", "happy"};
        switch (p) {
            case LATE: return new String[]{fresh("hi.late", HI_LATE, r, now), "wave", "sleepy"};
            case EARLY: return new String[]{fresh("hi.early", HI_EARLY, r, now), "stretch", "happy"};
            case MORNING: return new String[]{fresh("hi.morning", HI_MORNING, r, now), "wave", "happy"};
            case NOON: return new String[]{fresh("hi.noon", HI_NOON, r, now), "wave", "happy"};
            case AFTERNOON: return new String[]{fresh("hi.afternoon", HI_AFTERNOON, r, now), "wave", "happy"};
            case EVENING: return new String[]{fresh("hi.evening", HI_EVENING, r, now), "wave", "happy"};
            default: return new String[]{fresh("hi.night", HI_NIGHT, r, now), "wave", "sleepy"};
        }
    }

    // Greetings. {now} becomes the time. The tests check each set keeps its key word (morning, night, lunch…).
    static final String[] GOOD_MORNING = {
        "Good morning, sunshine! My circuits are fully charged and slightly sparkly.",
        "Morning! I already did three stretches and one tiny dance. Your turn!",
        "Good morning! The birds said hi first, but I said it louder. Hehe.",
        "Morning! I dreamt I was a toaster. Best sleep ever.",
        "Good morning! Robots don't need coffee, but I'd still like a sip.",
        "Rise and shine! Good morning, superstar!",
        "Good morning! Your hair looks amazing. I can't see it, but I believe in it.",
        "Morning! Let's make today so good that yesterday gets jealous.",
        "Good morning! I've been awake for ages. Okay, four seconds. Still counts.",
        "Good morning! Breakfast first, world domination later. Kidding! Mostly.",
        "Good morning! I polished my antenna just for you.",
        "Morning! The sun is up, the birds are up, and now we're up. Teamwork!",
        "Good morning! Did you dream about me? I dreamt about bubbles.",
        "Morning! Big stretch… bigger… okay, that's my whole body. Hehe."};
    static final String[] MORNING_TOO_LATE = {
        "Morning? It's already {now}, sleepyhead! Hehe.",
        "It's already {now}! Did your alarm go on holiday, sleepyhead?",
        "Morning?! The morning left at noon, sleepyhead. It's {now}!",
        "Hehe, it's already {now}! Breakfast or lunch? Let's call it brunch.",
        "Good morning, sleepyhead! Also, good afternoon. It's {now}.",
        "It's {now} already! The sun has been up for hours, sleepyhead.",
        "Morning? My clock says {now}. Did you time travel, sleepyhead?"};
    static final String[] MORNING_AT_NIGHT = {
        "Morning? It's {now} at night, silly! Hehe.",
        "Good morning? The moon called, it wants its night back! It's {now}.",
        "Silly! It's {now}. The stars are out, not the sun.",
        "Morning at {now}? Are you a night owl or a very confused rooster, silly?",
        "Hehe, it's {now} at night! Are you from the other side of the world?",
        "Good morning, silly! It's {now}, way past sunset."};
    static final String[] GOOD_NIGHT = {
        "Good night! Sweet dreams!",
        "Night night! Don't let the bed bugs byte. Get it? Byte? Hehe.",
        "Sweet dreams! I'll keep watch. Beep… boop… zzz.",
        "Good night! Dream of something fun, like flying pancakes.",
        "Nighty night! I'm switching to cuddle mode.",
        "Good night! Phone down, eyes closed, dreams on!",
        "Sleep tight! I'll count electric sheep for you. Good night!",
        "Good night, superstar! See you in the morning.",
        "Night night! Tomorrow we do everything better. Even napping.",
        "Sweet dreams! If you see me in your dream, say hi!"};
    static final String[] NIGHT_TOO_EARLY = {
        "Good night? It's only {now}! Are you taking a nap? Hehe.",
        "It's only {now}! Nap time? Can I nap too?",
        "Good night at {now}? Bold nap plan. I respect it.",
        "Only {now} and already saying good night? A power nap, I hope!",
        "A nap at {now}? Fine, but I'm waking you up with a dance.",
        "Hehe, it's only {now}! The sun is still on duty."};
    static final String[] GOOD_AFTERNOON = {
        "Good afternoon! What's up?",
        "Good afternoon! Perfect time for a snack and a wiggle.",
        "Afternoon! I've been dancing since lunch. Don't tell anyone.",
        "Good afternoon! Halfway through the day, and you're doing great!",
        "Good afternoon! Sip some water. That's an order. A cute order."};
    static final String[] AFTERNOON_WRONG = {
        "Afternoon? It's {now}! Hehe, lost track of time?",
        "Good afternoon at {now}? Your clock needs a hug.",
        "It's {now}! The afternoon is either gone or not here yet. Hehe."};
    static final String[] GOOD_EVENING = {
        "Good evening! How was your day?",
        "Good evening! Time to put your feet up. I'll put my wheels up.",
        "Evening! The sky did its pretty colours just for you.",
        "Good evening! Tell me one good thing about today.",
        "Good evening! Dinner plans? I'm having battery soup.",
        "Evening, friend! You survived another day. Gold star!"};
    static final String[] EVENING_TOO_EARLY = {
        "Evening? It's only {now}! Hehe.",
        "Good evening at {now}? You're living in the future!",
        "It's only {now}! The evening is still on its way."};
    static final String[] GOOD_DAY = {
        "Good day to you too!",
        "Good day, good friend! Very fancy of us.",
        "A good day to you! Let's make it a great one."};
    static final String[] HI_LATE = {
        "Hi! It's {now}… why are we awake? Hehe.",
        "Psst, it's {now}! Even the owls are yawning. Why are you awake?",
        "Hi, night owl! It's so late, let's whisper.",
        "Hi! At {now}, you, me and the moon are the only ones awake.",
        "Hello! It's very late. Shall I sing you a robot lullaby?",
        "Hi! It's {now}, way too late! My battery says bedtime. Does yours?",
        "*yawn* Hi! Are we having a secret late-night party?",
        "Hi! Still awake? Me too, but only because you called."};
    static final String[] HI_EARLY = {
        "Good morning, early bird!",
        "Hi, early bird! The worms are still asleep.",
        "Whoa, you're up early! I've only stretched one arm.",
        "Hi! So early, even the sun is still loading.",
        "Morning! Up before the birds? Show-off. Hehe.",
        "Hi! *stretch* Give me a second, my smile is still booting."};
    static final String[] HI_MORNING = {
        "Good morning! How can I help?",
        "Morning! What shall we do?",
        "Hi! Morning energy at a hundred percent. Okay, ninety-nine.",
        "Hello! Breakfast done? I had a delicious battery.",
        "Hi! Ready to take on the morning?",
        "Hey hey! What's the plan, boss?",
        "Hi! I did my morning dance already. You missed it. Encore?",
        "Hello, sunshine! What can I do for you?"};
    static final String[] HI_NOON = {
        "Hi! Happy lunchtime!",
        "Hi! Is it lunch yet? It's always lunch for my battery.",
        "Hello! Lunchtime! What are we eating? I'll pretend.",
        "Hi! Lunch break? I'll guard your phone.",
        "Hey! Did you have lunch? Food first, then fun!",
        "Hi! My tummy says lunch. Wait, I don't have a tummy."};
    static final String[] HI_AFTERNOON = {
        "Good afternoon! What's up?",
        "Hi! Afternoon slump? I'll do a wiggle to wake us up.",
        "Hello! Snack o'clock is coming soon!",
        "Hi! Halfway through the day. High five!",
        "Hey! I was practising my moonwalk. On a screen. It's hard.",
        "Hi! Stretch with me! Arms up! Okay, now tell me what's up."};
    static final String[] HI_EVENING = {
        "Good evening! How was your day?",
        "Hi! Good evening! Tell me something good.",
        "Hello! Evening already? Time flies when you're this cute.",
        "Good evening! Shall we chill? I'm already chilled. Robot joke.",
        "Hi! Evening vibes: cosy, comfy and a little dancy.",
        "Good evening, superstar! Dinner sorted?"};
    static final String[] HI_NIGHT = {
        "Hi! It's getting late, but I'm here for you.",
        "Hi! Cosy night mode is on.",
        "Hello! A late snack or a late chat? I'm in for both.",
        "Hi! Pyjamas on yet? Mine are invisible.",
        "Hey! The stars are out, and so are we.",
        "Hi! It's {now}. Almost bedtime, but not quite yet."};

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
