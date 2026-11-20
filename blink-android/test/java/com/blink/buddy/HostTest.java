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

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("tools")) { System.out.print(Call.TOOLS_JSON); return; }
        if (a.length > 1 && a[0].equals("needle")) { needle(a[1]); finish(); return; }
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
        route("remind me in 10 minutes to drink water", "set_timer", .9f, "set_timer");
        route("turn on the torch", "flashlight", .9f, "flashlight");
        route("call mom", "call_contact", .9f, "call_contact");
        route("hi", "sweety_fun", .9f, "chat");
        route("tell me a joke", "sweety_fun", .9f, "sweety_fun");
        route("I love you", "sweety_fun", .9f, "chat");
        finish();
    }

    /** Whistle's transcripts of the test recordings: every "wake" clip must wake her, no "nowake" clip may. */
    static void transcripts(String path) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(path));
        int wakes = 0, wakeOk = 0;
        for (String line : lines) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            String heard = text(p[2]);
            p[2] = heard;
            String got = WakePhrase.find(heard);
            boolean want = p[0].startsWith("wake");
            boolean ok = (got != null) == want;
            if (want) { wakes++; if (ok) wakeOk++; }
            if (!ok) fails++;
            System.out.println((ok ? "ok    " : "FAIL  ") + p[1] + "  heard [" + p[2] + "] -> " + (got == null ? "(no wake)" : "wake, then [" + got + "]"));
        }
        System.out.println("wake clips recognised: " + wakeOk + " / " + wakes);
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
