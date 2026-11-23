package com.blink.buddy;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Plain-Java checks of Sweety's wake phrase and command routing, run on the CI machine.
 *   java HostTest                       built-in cases
 *   java HostTest transcripts.tsv       lines "expect<TAB>file<TAB>what Whistle heard", expect = wake | nowake
 */
public class HostTest {
    static int fails;

    static void wake(String heard, String expectRest) {
        String got = WakePhrase.find(heard);
        boolean ok = expectRest == null ? got == null : expectRest.equals(got);
        if (!ok) fails++;
        System.out.println((ok ? "ok    " : "FAIL  ") + "wake [" + heard + "] -> " + (got == null ? "(no wake)" : "[" + got + "]")
            + (ok ? "" : "   expected " + (expectRest == null ? "(no wake)" : "[" + expectRest + "]")));
    }

    static void route(String text, String needleTool, float conf, String expect) {
        Call needle = needleTool == null ? null : new Call(needleTool, null, conf, "needle3");
        Call ruled = new RuleBrain().parse(text);
        Call c = RuleBrain.decide(needle, ruled, text);
        String got = c == null ? "chat" : c.name;
        boolean ok = got.equals(expect);
        if (!ok) fails++;
        System.out.println((ok ? "ok    " : "FAIL  ") + "route [" + text + "] needle=" + needleTool + " -> " + got + (ok ? "" : "   expected " + expect));
    }

    static void alarm(String text, int h, int m) {
        Call c = new RuleBrain().parse(text);
        boolean ok = c != null && "set_alarm".equals(c.name) && c.args.optInt("hour", -1) == h && c.args.optInt("minute", -1) == m;
        if (!ok) fails++;
        System.out.println((ok ? "ok    " : "FAIL  ") + "alarm [" + text + "] -> " + (c == null ? "none" : c.name + " " + c.args) + (ok ? "" : "   expected " + h + ":" + m));
    }

    static void greet(String text, int h, int m, String expectAction, String expectWords) {
        String[] g = DayTime.greet(text, h, m, new java.util.Random(1));
        boolean ok = expectAction == null ? g == null : g != null && g[1].equals(expectAction) && g[0].toLowerCase().matches("(?s).*(" + expectWords + ").*");
        if (!ok) fails++;
        System.out.println((ok ? "ok    " : "FAIL  ") + "time " + DayTime.clock(h, m) + " [" + text + "] -> " + (g == null ? "(not a greeting)" : "[" + g[1] + "/" + g[2] + "] " + g[0])
            + (ok ? "" : "   expected " + expectAction + " ~ " + expectWords));
    }

    /** Every hour of the day: a sensible part of day, valid antics and lines, the right greeting. */
    static void dayTime() {
        java.util.Set<String> moves = new java.util.HashSet<>(java.util.Arrays.asList("sleep", "stretch", "nod", "wave", "jump", "dance", "spin", "cartwheel", "flip", "shrug"));
        java.util.Random r = new java.util.Random(7);
        for (int h = 0; h < 24; h++) {
            int p = DayTime.part(h);
            boolean ok = true;
            for (int k = 0; k < 30; k++) { String[] o = DayTime.idle(p, r); ok &= moves.contains(o[0]) && o[1] != null; ok &= DayTime.idleLine(p, r) != null; }
            String[] hr = DayTime.hourly(h, r);
            ok &= moves.contains(hr[1]) && hr[0].startsWith(h == 0 ? "It's midnight" : h == 12 ? "It's noon" : "It's " + DayTime.clock(h, 0));
            ok &= DayTime.greet("hi", h, 0, r) != null;
            if (!ok) fails++;
            System.out.println((ok ? "ok    " : "FAIL  ") + "hour " + h + ": " + DayTime.NAMES[p] + ", e.g. " + String.join("/", DayTime.idle(p, r)) + ", \"" + hr[0] + "\"");
        }
        boolean nightDozes = false, afternoonDances = false;
        for (int k = 0; k < 200; k++) { nightDozes |= "sleep".equals(DayTime.idle(DayTime.LATE, r)[0]); afternoonDances |= "dance".equals(DayTime.idle(DayTime.AFTERNOON, r)[0]); }
        if (!nightDozes || !afternoonDances) fails++;
        System.out.println((nightDozes && afternoonDances ? "ok    " : "FAIL  ") + "she dozes after midnight and dances in the afternoon");
        greet("Good morning!", 8, 15, "stretch", "morning");
        greet("good morning sweety", 23, 10, "laugh", "night|silly");
        greet("Good morning", 15, 0, "laugh", "already|sleepyhead");
        greet("Good night", 22, 30, "sleep", "dream|night|sleep");
        greet("good night", 14, 0, "laugh", "nap|only");
        greet("Hi", 2, 5, "wave", "awake|late");
        greet("Hey Sweety!", 12, 30, "wave", "lunch");
        greet("Hello there", 19, 0, "wave", "evening");
        greet("Hi, set an alarm for 7", 9, 0, null, "");
        greet("What time is it?", 9, 0, null, "");
        java.util.Calendar c = java.util.Calendar.getInstance(); c.set(java.util.Calendar.HOUR_OF_DAY, 1); c.set(java.util.Calendar.MINUTE, 30);
        String ctx = DayTime.chatContext(c);
        boolean ok = ctx.contains("1:30 AM") && ctx.contains("sleepy");
        if (!ok) fails++;
        System.out.println((ok ? "ok    " : "FAIL  ") + "chat brain is told: " + ctx);
        String tw = DayTime.lateTwist("dance", r);
        if (tw == null) fails++;
        System.out.println((tw != null ? "ok    " : "FAIL  ") + "midnight dance: " + tw);
        freshLines();
    }

    /** No repeats: a whole set is used before any line comes back, never the same line twice in a row; every greeting fits its time. */
    static void freshLines() {
        java.util.Random r = new java.util.Random(3);
        final java.util.Map<String, String> mem = new java.util.HashMap<>(); // start from a clean memory
        Fresh.store = new Fresh.Store() { public String get(String k) { return mem.get(k); } public void put(String k, String v) { mem.put(k, v); } };
        String[] pool = {"a", "b", "c", "d", "e", "f", "g"};
        boolean ok = true;
        String prev = null;
        for (int round = 0; round < 30; round++) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < pool.length; i++) {
                String s = Fresh.pick("test", pool, r);
                ok &= seen.add(s) && !s.equals(prev);
                prev = s;
            }
        }
        check(ok, "lines never repeat until the whole set is used, and never twice in a row (30 rounds of 7)");
        String[] two = {"x", "y"};
        boolean alt = true; prev = null;
        for (int i = 0; i < 40; i++) { String s = Fresh.pick("two", two, r); alt &= !s.equals(prev); prev = s; }
        check(alt, "even a set of two lines alternates");

        // 20 greetings in a row at 8:15 AM: all different, all about the morning
        java.util.Set<String> mornings = new java.util.HashSet<>();
        for (int i = 0; i < DayTime.GOOD_MORNING.length; i++) mornings.add(DayTime.greet("good morning", 8, 15, r)[0]);
        check(mornings.size() == DayTime.GOOD_MORNING.length, mornings.size() + " different good-morning replies in " + DayTime.GOOD_MORNING.length + " tries");

        // every line keeps the word its moment needs (the emulator test and the tone depend on it)
        Object[][] sets = {
            {DayTime.GOOD_MORNING, "morning"}, {DayTime.MORNING_TOO_LATE, "already|sleepyhead"}, {DayTime.MORNING_AT_NIGHT, "night|silly"},
            {DayTime.GOOD_NIGHT, "dream|night"}, {DayTime.NIGHT_TOO_EARLY, "nap|only"}, {DayTime.GOOD_AFTERNOON, "afternoon"},
            {DayTime.GOOD_EVENING, "evening"}, {DayTime.HI_LATE, "awake|late"}, {DayTime.HI_NOON, "lunch"}, {DayTime.HI_EVENING, "evening"}};
        for (Object[] s : sets) {
            String[] lines = (String[]) s[0];
            java.util.List<String> bad = new java.util.ArrayList<>();
            for (String l : lines) if (!l.toLowerCase().matches("(?s).*(" + s[1] + ").*")) bad.add(l);
            check(bad.isEmpty() && new java.util.HashSet<>(java.util.Arrays.asList(lines)).size() == lines.length,
                lines.length + " lines about \"" + s[1] + "\", no duplicates" + (bad.isEmpty() ? "" : "; off: " + bad));
        }
        String late = DayTime.greet("hi", 1, 30, r)[0];
        check(late.contains("1:30 AM") || !late.contains("{now}"), "the time is filled in: " + late);
        boolean woke = true;
        for (int p = 0; p < 7; p++) for (int i = 0; i < 12; i++) woke &= DayTime.wokeLine(p, r) != null && !DayTime.yesLine(r).isEmpty();
        check(woke, "wake-up lines for every time of day, e.g. \"" + DayTime.wokeLine(DayTime.MORNING, r) + "\" / \"" + DayTime.yesLine(r) + "\"");
    }

    /**
     * Everything she says, made ready for the natural voice, must be words the voice's dictionary knows, or Kokoro
     * silently skips them.   java HostTest speakable lexicon-en-us.txt
     */
    static void speakable(String lexiconFile) throws Exception {
        java.util.Set<String> lex = new java.util.HashSet<>();
        java.util.List<String> entries = new java.util.ArrayList<>(Files.readAllLines(Paths.get(lexiconFile), java.nio.charset.StandardCharsets.UTF_8));
        entries.addAll(java.util.Arrays.asList(SpeakText.EXTRA_LEXICON.split("\n"))); // her own word list, shipped next to it
        for (String l : entries) {
            int bar = l.indexOf(" || ");
            if (bar > 0 && !l.startsWith("#")) lex.add(l.substring(0, bar).trim().toLowerCase());
        }
        java.util.List<String> said = new java.util.ArrayList<>(java.util.Arrays.asList(
            "It's 7:30 PM!", "It's 11:05 AM.", "I set an alarm for 7 am.", "Alarm set for 6:45 a.m.", "It's 12:00.",
            "7 plus 5 is 12! Easy peasy robot math.", "25 minus 30 is -5!", "1.5 plus 2 is 3.5!", "100 divided by 4 is 25!",
            "Your battery is at 87%.", "It's 31°C outside.", "That costs ₹1,250.", "Okay! I'll remind you to drink water in 30 minutes.",
            "Timer set for 5-10 minutes.", "The 21st of March, in 2026.", "Hi! I'm Sweety. *giggle*", "Beep… boop… zzz.",
            "Sweety’s here! You’re the best.", "Call 9876543210?", "You asked: What's my name?"));
        java.util.Random r = new java.util.Random(5);
        for (String[] set : new String[][]{DayTime.GOOD_MORNING, DayTime.MORNING_TOO_LATE, DayTime.MORNING_AT_NIGHT, DayTime.GOOD_NIGHT,
            DayTime.NIGHT_TOO_EARLY, DayTime.GOOD_AFTERNOON, DayTime.AFTERNOON_WRONG, DayTime.GOOD_EVENING, DayTime.EVENING_TOO_EARLY,
            DayTime.GOOD_DAY, DayTime.HI_LATE, DayTime.HI_EARLY, DayTime.HI_MORNING, DayTime.HI_NOON, DayTime.HI_AFTERNOON,
            DayTime.HI_EVENING, DayTime.HI_NIGHT}) for (String l : set) said.add(l.replace("{now}", DayTime.clock(1 + r.nextInt(12), r.nextInt(60))));
        for (int p = 0; p < 7; p++) for (int i = 0; i < 12; i++) { said.add(DayTime.idleLine(p, r)); said.add(DayTime.wokeLine(p, r)); said.add(DayTime.yesLine(r)); }
        for (int h = 0; h < 24; h++) said.add(DayTime.hourly(h, r)[0]);
        for (String act : new String[]{"dance", "jump", "spin", "flip", "cartwheel"}) said.add(DayTime.lateTwist(act, r));
        java.util.Map<String, String> oov = new java.util.TreeMap<>();
        for (String line : new java.util.LinkedHashSet<>(said)) {
            String ready = SpeakText.prepare(line);
            for (String w : ready.split("[\\s…]+")) {
                String k = w.toLowerCase().replaceAll("^[^\\p{L}\\p{N}']+|[^\\p{L}\\p{N}']+$", "");
                if (k.isEmpty()) continue;
                boolean ok = lex.contains(k);
                if (!ok && k.contains("-")) { ok = true; for (String part : k.split("-")) ok &= part.isEmpty() || lex.contains(part); }
                if (!ok) oov.putIfAbsent(k, line + "  ->  " + ready);
            }
        }
        for (String s : new String[]{"It's 7:30 PM!", "25 minus 30 is -5!", "That costs ₹1,250.", "Hi! I'm Sweety. *giggle*", "Call 9876543210?"})
            System.out.println("      " + s + "  ->  " + SpeakText.prepare(s));
        check(oov.isEmpty(), new java.util.LinkedHashSet<>(said).size() + " lines she says are all words her natural voice can say"
            + (oov.isEmpty() ? "" : "; missing from the dictionary: " + oov));
        check(SpeakText.prepare("It's 7:30 PM!").equals("It's seven thirty pee em!"), "times are said the way people say them");
        check(SpeakText.prepare("1,205").equals("one thousand two hundred five") && SpeakText.prepare("3.5").equals("three point five"), "numbers become words");
    }

    static void check(boolean ok, String what) {
        if (!ok) fails++;
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
    }

    /** Sweety's Gemini brain against the stand-in server (test/mock_gemini.py). */
    static void gemini(String base) {
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        GeminiBrain.Store st = new GeminiBrain.Store() {
            @Override public String get(String k, String d) { return kv.getOrDefault(k, d); }
            @Override public void put(String k, String v) { kv.put(k, v); }
        };
        kv.put("gemini_key", "test-key");
        GeminiBrain g = new GeminiBrain(st, base);
        java.util.List<String> memory = new java.util.ArrayList<>();
        check("gemini-4.0-flash".equals(g.model()), "picks the newest plain Flash model first: " + g.model());

        GeminiBrain.Answer a = g.ask("Why is the sky blue?", memory);
        // gemini-4.0-flash isn't on this key's free tier (429, limit 0): she must move on to a model that answers
        check(a != null && "gemini-3.8-flash".equals(kv.get("gemini_model")), "the newest model isn't on the free tier, so she switches to one that works: "
            + kv.get("gemini_model") + (a == null ? " (" + g.lastError + ")" : ""));
        check(g.check() == null && "gemini-3.8-flash".equals(kv.get("gemini_model")), "the Save key check says Gemini works");
        // Streamed like a real assistant: sentences arrive one by one (she starts speaking at the first)
        java.util.List<String> said = new java.util.ArrayList<>();
        String[] ge = new String[2];
        GeminiBrain.Answer streamed = g.ask("Why is the sky blue?", memory, (gs, em, t) -> { said.add(t); ge[0] = gs; ge[1] = em; });
        check(streamed != null && said.size() >= 2 && said.get(0).startsWith("Sunlight") && "nod".equals(ge[0]) && "happy".equals(ge[1])
            && String.join(" ", said).equals(streamed.text), "streams the answer sentence by sentence: " + said + (streamed == null ? " (" + g.lastError + ")" : ""));
        g.forget();
        check(a != null && a.text.startsWith("Sunlight") && a.gesture.equals("nod") && a.emotion.equals("happy") && a.calls.isEmpty(),
            "answers a question with words, gesture and mood: " + (a == null ? g.lastError : "[" + a.gesture + "/" + a.emotion + "] " + a.text));

        a = g.ask("Remind me to drink water when I get home", memory);
        check(a != null && a.calls.size() == 1 && a.calls.get(0).name.equals("set_timer") && a.calls.get(0).args.optInt("minutes") == 30,
            "turns a request into a phone action: " + (a == null ? g.lastError : a.calls.isEmpty() ? "(none)" : a.calls.get(0).name + " " + a.calls.get(0).args));

        a = g.ask("My name is Alex", memory);
        check(a != null && a.remember.size() == 1 && a.remember.get(0).contains("Alex") && a.text.contains("Nice to meet you"),
            "remembers facts the user shares: " + (a == null ? g.lastError : a.remember + " / " + a.text));
        if (a != null) memory.addAll(a.remember);

        a = g.ask("What's my name?", memory);
        check(a != null && a.text.contains("Alex"), "uses what she remembers: " + (a == null ? g.lastError : a.text));

        a = g.ask("What did I just ask?", memory);
        check(a != null && a.text.toLowerCase(java.util.Locale.ROOT).contains("what's my name"), "keeps the conversation going: " + (a == null ? g.lastError : a.text));

        a = g.ask("Catch the butterfly please", memory);
        check(a != null && a.calls.size() == 1 && "catch".equals(a.calls.get(0).args.optString("action")), "can start a trick: " + (a == null ? g.lastError : a.calls.isEmpty() ? "(none)" : a.calls.get(0).args));

        kv.put("gemini_key", "wrong-key");
        GeminiBrain bad = new GeminiBrain(st, base);
        a = bad.ask("Hello?", memory);
        check(a == null && bad.lastError != null && bad.lastError.contains("key"), "a wrong key is reported clearly: " + bad.lastError);

        GeminiBrain nokey = new GeminiBrain(new GeminiBrain.Store() {
            @Override public String get(String k, String d) { return d; }
            @Override public void put(String k, String v) { }
        }, base);
        check(!nokey.configured() && nokey.ask("hi", memory) == null, "without a key she doesn't call Gemini at all");
    }

    /** The new voice tricks: catch a butterfly or bird, hide, come back. */
    static void tricks() {
        String[][] cases = {
            {"Catch the butterfly", "catch", "butterfly"}, {"Hey Sweety, catch the bird!", "catch", "bird"}, {"chase that birdie", "catch", "bird"},
            {"can you catch a butterfly for me", "catch", "butterfly"}, {"go after the bug", "catch", "butterfly"},
            {"hide", "hide", null}, {"go away for a bit", "hide", null}, {"disappear", "hide", null},
            {"come back", "show", null}, {"where are you?", "show", null}, {"come out Sweety", "show", null},
        };
        for (String[] c : cases) {
            Call call = new RuleBrain().parse(c[0]);
            boolean ok = call != null && "sweety_fun".equals(call.name) && c[1].equals(call.args.optString("action"))
                && (c[2] == null || c[2].equals(call.args.optString("critter")));
            ok &= RuleBrain.decide(null, call, c[0]) == call;
            if (!ok) fails++;
            System.out.println((ok ? "ok    " : "FAIL  ") + "trick [" + c[0] + "] -> " + (call == null ? "none" : call.name + " " + call.args) + (ok ? "" : "   expected " + c[1] + " " + c[2]));
        }
        // Needle saying "sweety_fun" for these must be believed too.
        for (String s : new String[]{"catch the butterfly", "hide", "come back"}) {
            Call n = new Call("sweety_fun", null, .9f, "needle3");
            boolean ok = RuleBrain.plausible("sweety_fun", s);
            if (!ok) fails++;
            System.out.println((ok ? "ok    " : "FAIL  ") + "needle trick [" + s + "] plausible");
        }
    }

    /** A whole chase: Sweety flies after the critter like on the phone; it must dodge once, get caught, fly free and leave. */
    static void chase(String kind) {
        java.util.Random r = new java.util.Random(kind.hashCode());
        float w = 1080, h = 2200, sx = 900, sy = 1800, speed = 900; // px and px/s, like the floating Sweety
        Critter k = new Critter(kind, w, h, 400, 1400, 110, r);
        StringBuilder events = new StringBuilder();
        float t = 0, caughtAt = -1;
        boolean held = true;
        while (t < 60 && !k.done()) {
            float dt = 1 / 30f;
            t += dt;
            float hx = sx, hy = sy - 300; // her hands
            String ev = k.step(dt, hx, hy, 220);
            if (ev != null) { events.append(ev).append(' '); if (ev.equals("caught")) caughtAt = t; }
            if (k.state == Critter.CAUGHT) held &= Math.abs(k.x - hx) < 1 && Math.abs(k.y - hy) < 1;
            if (k.state <= Critter.FLUTTER) { // fly towards it
                float dx = k.x - sx, dy = k.y + 300 - sy, d = (float) Math.hypot(dx, dy), step = Math.min(d, speed * dt);
                if (d > 0) { sx += dx / d * step; sy += dy / d * step; }
            }
        }
        String got = events.toString().trim();
        boolean ok = got.equals("dodge caught free done") && caughtAt > 2.5f && caughtAt < 30 && held && t < 60;
        if (!ok) fails++;
        System.out.printf(java.util.Locale.ROOT, "%schase %s: %s (caught after %.1f s, gone after %.1f s)%s%n", ok ? "ok    " : "FAIL  ", kind, got, caughtAt, t,
            ok ? "" : "   expected: dodge caught free done, caught after 2.5-30 s (a visible chase), held in her hands");
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("tools")) { System.out.print(Call.TOOLS_JSON); return; }
        if (a.length > 1 && a[0].equals("gemini")) { gemini(a[1]); finish(); return; }
        if (a.length > 1 && a[0].equals("needle")) { needle(a[1]); finish(); return; }
        if (a.length > 1 && a[0].equals("speakable")) { speakable(a[1]); finish(); return; }
        if (a.length > 1 && a[0].equals("lexicon")) { Files.write(Paths.get(a[1]), SpeakText.EXTRA_LEXICON.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return; }
        if (a.length > 0) { transcripts(a[0]); finish(); return; }
        wake("Hey Sweety.", "");
        wake("Hey, sweetie, set an alarm for 7 am.", "set an alarm for 7 am");
        wake("Hi Sweaty what time is it", "what time is it");
        wake("Okay sweet tea, turn on the torch.", "turn on the torch");
        wake("Sweety, call mom.", "call mom");
        wake(" Hey sweety! Tell me a joke.", "Tell me a joke");
        wake("Hey sweet, dance for me", "dance for me");
        wake("A sweetie, what's up", "what's up");
        wake("Hey Switty", "");
        wake("Hey Swathi, what time is it?", "what time is it?");
        wake("Hey Sweeney tell me a story", "tell me a story");
        wake("Hey, Sweetie.", "");
        wake("Hello, Sweety?", "");
        wake("Hey Suity, do a backflip", "do a backflip");
        wake("Oh, hey sweety, dance", "dance");
        // What Whistle really wrote on the Android 14 emulator
        wake("His weedy, what Tim is it?", "what Tim is it?");
        wake("I sweety dance for me.", "dance for me");
        wake("HASE WITTY.", "");
        wake("His sweetty set an alarm for seven I am.", "set an alarm for seven I am");
        wake("Heysweety, what time is it", "what time is it");
        wake("Well, Kate Sweety, tell me a joke.", "tell me a joke");
        wake("A LUS WEADY.", "");
        wake("He is sweety, turn on the torch.", "turn on the torch");
        wake("O Swedy, turn on the torch.", "turn on the torch");
        wake("Okay, Swedie, tell me a joke.", "tell me a joke");
        wake("Because Swedy tell me a joke.", "tell me a joke");
        wake("Sweedy, do a backflip.", "do a backflip");
        wake("A Swedie turned on the torch.", "turned on the torch");
        wake("Pay Swedy turn on the torch.", "turn on the torch");
        wake("The Swedes won", null);
        wake("HELLOW SWITY.", "");
        wake("It was weird", null);
        wake("This was weedy grass", null);
        wake("Is wet today", null);
        wake("What time is it?", null);
        wake("I love eating sweets.", null);
        wake("His wife is here", null);
        wake("I sweat a lot", null);
        wake("You're such a sweetie.", null);
        wake("That was sweet everything", null);
        wake("hey there", null);
        wake("I bought sweets", null);
        wake("Sweet.", null);
        wake("Hey Siri, what's the weather", null);
        wake("Hey, Swiggy order", null);
        wake("Hey Sweden", null);
        wake("Thank you.", null);
        wake("", null);

        dayTime();
        tricks();
        chase("butterfly");
        chase("bird");
        String[][] sums = {{"What is 7 plus 5?", "7 plus 5 is 12!"}, {"what's seven times eight", "7 times 8 is 56!"}, {"Hey Sweety, 100 divided by 4", "100 divided by 4 is 25!"},
            {"twenty five minus thirty", "25 minus 30 is -5!"}, {"what is 10 divided by 0", "zero"}, {"What is 1.5 plus 2", "1.5 plus 2 is 3.5!"},
            {"What is the capital of France?", null}, {"Call mom and dad", null}, {"remind me in 10 minutes", null}};
        for (String[] t : sums) {
            String got = RuleBrain.math(t[0]);
            boolean ok = t[1] == null ? got == null : got != null && got.contains(t[1]);
            if (!ok) fails++;
            System.out.println((ok ? "ok    " : "FAIL  ") + "math [" + t[0] + "] -> " + got + (ok ? "" : "   expected " + t[1]));
        }

        route("are you dumb?", "sweety_fun", .95f, "chat");
        route("are you dumb?", null, 0, "chat");
        route("why is the sky blue", "phone_status", .9f, "chat");
        route("tell me about the moon", "sweety_fun", .9f, "chat");
        route("how are you today", "phone_status", .9f, "chat");
        route("what day is it today", "phone_status", .9f, "phone_status");
        route("dance for me", "sweety_fun", .9f, "sweety_fun");
        route("do a backflip", null, 0, "sweety_fun");
        route("do a cartwheel", null, 0, "sweety_fun");
        route("set an alarm for 7 am", "set_alarm", .95f, "set_alarm");
        route("set an alarm for 7 am", null, 0, "set_alarm");
        alarm("set an alarm for seven I am", 7, 0);
        alarm("set an alarm for six thirty p m", 18, 30);
        alarm("wake me up at 6:45 am", 6, 45);
        alarm("set an alarm for half past eight in the morning", 8, 30);
        alarm("set an alarm for 7 30 pm", 19, 30);
        alarm("alarm at ten o clock at night", 22, 0);
        route("what time is it", "phone_status", .9f, "phone_status");
        route("what Tim is it?", null, 0, "phone_status");
        route("call Tim", null, 0, "call_contact");
        route("sat in alarm for seven I am", null, 0, "set_alarm");
        route("remind me in 10 minutes to drink water", "set_timer", .9f, "set_timer");
        route("turn on the torch", "flashlight", .9f, "flashlight");
        route("call mom", "call_contact", .9f, "call_contact");
        route("hi", "sweety_fun", .9f, "chat");
        route("tell me a joke", "sweety_fun", .9f, "sweety_fun");
        route("I love you", "sweety_fun", .9f, "chat");
        finish();
    }

    /**
     * Whistle's transcripts of the test recordings. Speech recognition is never perfect, so this passes when at least
     * 90% of the natural-voice "Hey Sweety" clips wake her and NO ordinary sentence does. The robotic espeak voice is
     * a stress test: reported, not required.
     */
    static void transcripts(String path) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(path));
        int wakes = 0, wakeOk = 0, falseWakes = 0;
        for (String line : lines) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            String heard = text(p[2]);
            String got = WakePhrase.find(heard);
            boolean want = p[0].startsWith("wake"), robotic = p[1].contains("espeak");
            boolean ok = (got != null) == want;
            if (want && !robotic) { wakes++; if (ok) wakeOk++; }
            if (!want && !ok) falseWakes++;
            String mark = ok ? "ok    " : robotic && want ? "miss  " : want ? "MISS  " : "FALSE ";
            System.out.println(mark + p[1] + "  heard [" + heard + "] -> " + (got == null ? "(no wake)" : "wake, then [" + got + "]"));
        }
        double rate = wakes == 0 ? 0 : wakeOk * 1.0 / wakes;
        System.out.printf(java.util.Locale.ROOT, "natural voices: %d of %d \"Hey Sweety\" clips woke her (%.0f%%, need 90%%); ordinary sentences that woke her: %d (need 0)%n",
            wakeOk, wakes, rate * 100, falseWakes);
        if (rate < .9 || falseWakes > 0) fails++;
    }

    /** Whistle answers with JSON {"text": ...}; the phone reads it the same way. */
    static String text(String out) {
        int i = out.indexOf('{');
        if (i < 0) return out.trim();
        try { return new org.json.JSONObject(out.substring(i)).optString("text", "").trim(); } catch (Exception e) { return out.trim(); }
    }

    /** Needle 3's real answers ("expect<TAB>sentence<TAB>output"), routed exactly like on the phone. */
    static void needle(String path) throws Exception {
        for (String line : Files.readAllLines(Paths.get(path))) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            Call n = null;
            try {
                org.json.JSONObject o = new org.json.JSONObject(p[2].substring(p[2].indexOf('{')));
                org.json.JSONArray calls = o.optJSONArray("function_calls");
                if (calls != null && calls.length() > 0) {
                    org.json.JSONObject first = calls.getJSONObject(0);
                    n = new Call(first.optString("name"), null, (float) o.optDouble("confidence", .8), "needle3");
                }
            } catch (Exception ignored) { }
            Call ruled = new RuleBrain().parse(p[1]);
            Call c = RuleBrain.decide(n, ruled, p[1]);
            String got = c == null ? "chat" : c.name;
            boolean ok = got.equals(p[0]);
            if (!ok) fails++;
            System.out.println((ok ? "ok    " : "FAIL  ") + "[" + p[1] + "] needle=" + (n == null ? "none" : n.name + " " + n.confidence)
                + " rules=" + (ruled == null ? "none" : ruled.name) + " -> " + got + (ok ? "" : "   expected " + p[0]));
        }
    }

    static void finish() {
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
