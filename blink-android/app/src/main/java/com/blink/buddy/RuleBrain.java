package com.blink.buddy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Backup brain: understands common phrasings with simple rules, so Sweety still works without Needle. */
final class RuleBrain {
    private static final Pattern TIME = Pattern.compile("\\b(\\d{1,2})(?:[:. ](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|in the morning|in the evening|at night|tonight)?");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?|a|an|one|two|three|five|ten|fifteen|twenty|thirty|half an?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)");
    private static final Pattern NUMBER = Pattern.compile("(\\+?[\\d][\\d\\s-]{2,}\\d)");

    Call parse(String raw) {
        String s = " " + raw.toLowerCase(Locale.ROOT).replaceAll("[?!,]", " ").replaceAll("\\s+", " ").trim() + " ";
        s = s.replaceFirst("^ (hey |hi |ok |okay )?(sweety|sweetie|sweet|buddy)\\b", " ").replaceAll("\\s+", " ");
        s = misheard(s);
        String orig = " " + raw.replaceAll("[?!,]", " ").replaceAll("\\s+", " ").trim() + " "; // keeps the user's capitals for message and note text
        Call more = actions(s);
        if (more != null) return more;

        // notes
        if (s.matches(".*\\b(read|show|what are|tell me) (me )?my notes.*")) return new Call("read_notes", null, 1, "rules");
        Matcher note = Pattern.compile("\\b(?:take a note|make a note|note that|note down|write down|remember that|remember)\\s*:?\\s*(.+)", Pattern.CASE_INSENSITIVE).matcher(orig);
        if (note.find() && !s.contains("remind")) return new Call("take_note", Call.obj("text", note.group(1).trim()), 1, "rules");

        // timers and reminders
        if (s.contains("timer") || s.contains("remind") || s.contains("countdown")) {
            Matcher d = DURATION.matcher(s);
            double minutes = 0; boolean found = false;
            while (d.find()) {
                double v = word(d.group(1));
                String unit = d.group(2);
                if (unit.startsWith("s")) minutes += v / 60.0; else if (unit.startsWith("h")) minutes += v * 60; else minutes += v;
                found = true;
            }
            String label = null;
            Matcher lm = Pattern.compile("\\b(?:to|that|about)\\s+(.+?)\\s*(?:in \\d+.*|after .*)?$").matcher(s.trim());
            if (s.contains("remind") && lm.find()) label = lm.group(1).trim();
            if (found) return new Call("set_timer", Call.obj("minutes", minutes, "label", label), 1, "rules");
            if (s.contains("remind")) return new Call("ask", Call.obj("text", "When should I remind you? Say something like: remind me in 10 minutes to drink water."), 1, "rules");
        }

        // alarms
        if (s.contains("alarm") || s.contains("wake me")) {
            Matcher m = TIME.matcher(spokenTime(s));
            while (m.find()) {
                int h = Integer.parseInt(m.group(1));
                if (h > 23) continue;
                int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                String ap = m.group(3) != null ? m.group(3) : "";
                if ((ap.startsWith("p") || ap.contains("evening") || ap.contains("night")) && h < 12) h += 12;
                if (ap.startsWith("a") && h == 12) h = 0;
                return new Call("set_alarm", Call.obj("hour", h, "minute", min), 1, "rules");
            }
            return new Call("ask", Call.obj("text", "What time should I set the alarm for?"), 1, "rules");
        }

        // calls
        Matcher call = Pattern.compile("\\b(?:call|phone|dial|ring)\\s+(.+)").matcher(s);
        if (call.find() && !s.contains("what") ) {
            String who = call.group(1).trim().replaceAll("^(to |up )", "");
            Matcher n = NUMBER.matcher(who);
            if (n.find()) return new Call("call_contact", Call.obj("number", n.group(1).replaceAll("[\\s-]", "")), 1, "rules");
            return new Call("call_contact", Call.obj("name", cleanName(who)), 1, "rules");
        }

        // messages
        Matcher msg = Pattern.compile("\\b(?:text|message|msg|sms|whatsapp|send)\\s+(?:a message |a text |a whatsapp )?(?:to )?(.+?)\\s+(?:saying|that says|that|says|with|:)\\s+(.+)", Pattern.CASE_INSENSITIVE).matcher(orig);
        if (msg.find()) {
            String who = msg.group(1).trim().toLowerCase(Locale.ROOT), text = msg.group(2).trim();
            String app = s.contains("whatsapp") ? "whatsapp" : "sms";
            who = who.replaceAll("\\b(on|via) whatsapp\\b", "").trim();
            Matcher n = NUMBER.matcher(who);
            if (n.find()) return new Call("send_message", Call.obj("number", n.group(1).replaceAll("[\\s-]", ""), "text", text, "app", app), 1, "rules");
            return new Call("send_message", Call.obj("name", cleanName(who), "text", text, "app", app), 1, "rules");
        }

        // flashlight
        if (s.matches(".*\\b(torch|flashlight|flash light|light)\\b.*") && !s.contains("night light")) {
            boolean off = s.matches(".*\\b(off|stop|disable)\\b.*");
            if (off || s.matches(".*\\b(on|turn on|switch on|enable)\\b.*") || s.contains("torch") || s.contains("flash")) return new Call("flashlight", Call.obj("on", !off), 1, "rules");
        }

        // music and volume
        if (s.matches(".*\\b(volume up|louder|turn it up|increase volume|turn up)\\b.*")) return new Call("media_control", Call.obj("action", "volume_up"), 1, "rules");
        if (s.matches(".*\\b(volume down|quieter|softer|turn it down|decrease volume|lower the volume|turn down)\\b.*")) return new Call("media_control", Call.obj("action", "volume_down"), 1, "rules");
        if (s.matches(".*\\b(next song|skip|next track)\\b.*")) return new Call("media_control", Call.obj("action", "next"), 1, "rules");
        if (s.matches(".*\\b(previous song|last song|previous track|go back)\\b.*")) return new Call("media_control", Call.obj("action", "previous"), 1, "rules");
        if (s.matches(".*\\b(pause|stop the music|stop music)\\b.*")) return new Call("media_control", Call.obj("action", "pause"), 1, "rules");
        if (s.matches(".*\\b(play music|play some music|resume|play the music|play)\\b.*") && !s.contains("play with")) return new Call("media_control", Call.obj("action", "play"), 1, "rules");

        // phone status
        if (s.matches(".*\\b(battery|charge left|how much charge)\\b.*")) return new Call("phone_status", Call.obj("item", "battery"), 1, "rules");
        if (s.matches(".*\\b(what time|time is it|the time)\\b.*")) return new Call("phone_status", Call.obj("item", "time"), 1, "rules");
        if (s.matches(".*\\b(date|what day|which day|today's)\\b.*")) return new Call("phone_status", Call.obj("item", "date"), 1, "rules");

        // Sweety's own fun
        Matcher critter = Pattern.compile("\\b(catch|chase|get|grab|go after|run after)\\b.*?\\b(birds?|birdie|sparrow|parrot|butter ?fl(?:y|ies)|bug)\\b").matcher(s);
        if (critter.find()) return new Call("sweety_fun", Call.obj("action", "catch", "critter", critter.group(2).matches("birds?|birdie|sparrow|parrot") ? "bird" : "butterfly"), 1, "rules");
        if (s.matches(".*\\b(come back|come out|show yourself|where are you|unhide|reappear)\\b.*")) return new Call("sweety_fun", Call.obj("action", "show"), 1, "rules");
        if (s.matches(".*\\b(hide|hide yourself|go away|go hide|disappear|vanish|leave me alone)\\b.*")) return new Call("sweety_fun", Call.obj("action", "hide"), 1, "rules");
        for (Skin sk : Skin.ALL) {
            String key = sk.id.equals("kitty") ? "(kitty|cat)" : sk.id.equals("bunny") ? "(bunny|rabbit)" : sk.id.equals("nightcap") ? "(nightcap|pajama|pyjama|sleepy cap)" : sk.id.equals("snow") ? "(snow|winter)" : sk.id.equals("panda") ? "(panda|bear)" : sk.id;
            if (s.matches(".*\\b(wear|be a|become|change to|switch to|dress)\\b.*") && s.matches(".*\\b" + key + "\\b.*")) return new Call("sweety_fun", Call.obj("action", "outfit", "outfit", sk.id), 1, "rules");
        }
        if (s.matches(".*\\b(outfit|change clothes|new clothes|dress up)\\b.*")) return new Call("sweety_fun", Call.obj("action", "outfit"), 1, "rules");
        if (s.matches(".*\\b(danc\\w*|party|groove|boogie)\\b.*")) return new Call("sweety_fun", Call.obj("action", "dance"), 1, "rules");
        if (s.matches(".*\\b(back ?flip|flip|somersault)\\b.*")) return new Call("sweety_fun", Call.obj("action", "flip"), 1, "rules");
        if (s.matches(".*\\b(cart ?wheel\\w*|wheeler|wheelie|wheely)\\b.*")) return new Call("sweety_fun", Call.obj("action", "cartwheel"), 1, "rules");
        if (s.matches("\\W*(laugh|giggle)( for me)?\\W*")) return new Call("sweety_fun", Call.obj("action", "laugh"), 1, "rules");
        if (s.matches(".*\\b(joke|funny|make me laugh)\\b.*")) return new Call("sweety_fun", Call.obj("action", "joke"), 1, "rules");
        if (s.matches(".*\\b(jump|hop)\\b.*")) return new Call("sweety_fun", Call.obj("action", "jump"), 1, "rules");
        if (s.matches(".*\\b(spin|twirl)\\b.*")) return new Call("sweety_fun", Call.obj("action", "spin"), 1, "rules");
        if (s.matches(".*\\b(go to sleep|sleep|nap|good night|goodnight)\\b.*")) return new Call("sweety_fun", Call.obj("action", "sleep"), 1, "rules");
        if (s.matches(".*\\b(wave|say hi)\\b.*")) return new Call("sweety_fun", Call.obj("action", "wave"), 1, "rules");
        // "open YouTube": last, so "open the camera" or "start a timer" are handled above
        Matcher open = Pattern.compile("^ (?:please |can you |could you )?(?:open|launch|start|run)(?: up)? (?:the |my )?(.+?)(?: app| application)?(?: for me)?(?: please)? $").matcher(s);
        if (open.find() && !open.group(1).matches(".*\\b(timer|alarm|stopwatch|music|song|dance|party|conversation|chat)\\b.*"))
            return new Call("open_app", Call.obj("name", open.group(1).trim()), 1, "rules");
        return null;
    }

    private static final String LIST = "(shopping|grocery|groceries|to ?do|to-do|todo)";

    static String listName(String w) { return w.matches("shopping|grocery|groceries") ? "shopping" : "todo"; }

    /** The newer phone actions (Call.EXTRA): checked first, as some share words with older ones ("ring my phone"). */
    static Call actions(String s) {
        if (s.matches(".*\\b(find my phone|where'?s my phone|where is my phone|ring my phone|i can'?t find my phone|make (a )?(loud )?(noise|sound) so i can find)\\b.*"))
            return new Call("find_phone", null, 1, "rules");
        if (s.matches(".*\\bstop ?watch\\b.*")) {
            String a = s.matches(".*\\b(stop|pause|end|halt)\\b(?! ?watch).*|.*\\bstop the stop ?watch\\b.*") ? "stop"
                : s.matches(".*\\b(reset|clear|restart)\\b.*") ? "reset"
                : s.matches(".*\\b(how long|how much time|what'?s|what is|read|check|time on)\\b.*") ? "read" : "start";
            return new Call("stopwatch", Call.obj("action", a), 1, "rules");
        }
        if (s.matches(".*\\b(do not disturb|don'?t disturb|dnd|silence (my )?(phone|notifications)|silent mode)\\b.*"))
            return new Call("do_not_disturb", Call.obj("on", !s.matches(".*\\b(off|turn off|disable|end|stop|cancel|exit)\\b.*")), 1, "rules");
        Matcher add = Pattern.compile("\\b(?:add|put)\\s+(.+?)\\s+(?:to|on|in)\\s+(?:my |the )?" + LIST + "(?: list)?\\b").matcher(s);
        if (add.find()) return new Call("list", Call.obj("action", "add", "list", listName(add.group(2).replace(" ", "")), "item", add.group(1).trim()), 1, "rules");
        Matcher rm = Pattern.compile("\\b(?:remove|take|cross|delete|tick)\\s+(.+?)\\s+(?:off|from|out of)\\s+(?:my |the )?" + LIST + "(?: list)?\\b").matcher(s);
        if (rm.find()) return new Call("list", Call.obj("action", "remove", "list", listName(rm.group(2).replace(" ", "")), "item", rm.group(1).replaceAll("^off ", "").trim()), 1, "rules");
        Matcher clr = Pattern.compile("\\b(?:clear|empty|delete|reset|wipe)\\s+(?:my |the )?" + LIST + " list\\b").matcher(s);
        if (clr.find()) return new Call("list", Call.obj("action", "clear", "list", listName(clr.group(1).replace(" ", ""))), 1, "rules");
        Matcher rd = Pattern.compile("\\b(?:what'?s|what is|read|show|tell me|check)(?: me)?(?: on| in)? (?:my |the )?" + LIST + " list\\b").matcher(s);
        if (rd.find()) return new Call("list", Call.obj("action", "read", "list", listName(rd.group(1).replace(" ", ""))), 1, "rules");
        if (s.matches(".*\\b(take|click|snap) (a |an )?(selfie|photo|picture|pic|video)\\b.*|.*\\b(open|start) (the )?(camera|selfie camera|front camera)\\b.*|^ selfie( time)? $"))
            return new Call("camera", Call.obj("mode", s.matches(".*\\b(selfie|front)\\b.*") ? "selfie" : s.contains("video") ? "video" : "photo"), 1, "rules");
        Matcher onApp = Pattern.compile("\\b(?:play|put on)\\s+(.+?)\\s+on\\s+(youtube music|youtube|spotify)\\b").matcher(s);
        if (onApp.find()) return new Call("play_media", Call.obj("query", onApp.group(1).trim(), "app", onApp.group(2).replace(" ", "_")), 1, "rules");
        Matcher song = Pattern.compile("\\b(?:play|put on)\\s+(?:the song |the track |the album |the playlist |song |some )?(.+?\\s+by\\s+.+?|.+?\\s+songs|the song .+)\\s*$").matcher(s);
        if (song.find() && !song.group(1).matches("(music|some music|the music|it|something)")) return new Call("play_media", Call.obj("query", song.group(1).trim(), "app", "any"), 1, "rules");
        Matcher nav = Pattern.compile("\\b(?:navigate|directions|take me|drive me|get me|route|how do i get|how to get|how can i get|show me the way)\\s+(?:me\\s+)?(?:to\\s+|back\\s+)?(home|work|the office|.+?)(?: please)?\\s*$").matcher(s);
        if (nav.find() && !nav.group(1).isEmpty()) {
            String mode = s.contains("walk") ? "walking" : s.matches(".*\\b(bus|train|metro|transit)\\b.*") ? "transit" : "driving";
            return new Call("navigate", Call.obj("to", nav.group(1).replaceAll("\\s+(by|on) (foot|walking|bus|train|metro|car)$", "").trim(), "mode", mode), 1, "rules");
        }
        Matcher near = Pattern.compile("\\b(?:find |show me |search for |where'?s |where is |any )?(?:an? |the )?(?:nearest|closest|nearby)\\s+(.+?)\\s*$|^ (?:find |show me |search for |any )?(.+?)\\s+(?:near me|nearby|around here|close by)\\s*$").matcher(s);
        if (near.find()) return new Call("navigate", Call.obj("to", (near.group(1) != null ? near.group(1) : near.group(2)).trim(), "nearby", true), 1, "rules");
        Matcher search = Pattern.compile("\\b(?:search|google|look up|search the web for|search online for)\\s+(?:for\\s+)?(.+?)\\s*$").matcher(s);
        if (search.find() && !s.contains("contact")) return new Call("web_search", Call.obj("query", search.group(1).trim()), 1, "rules");
        Matcher set = Pattern.compile("\\b(wi-?fi|wifi|bluetooth|mobile data|cellular data|airplane mode|aeroplane mode|flight mode|hotspot|nfc|brightness|location|gps|battery saver|power saving|power saver)\\b").matcher(s);
        if (set.find() && s.matches(".*\\b(turn|switch|open|enable|disable|on|off|settings?|connect|disconnect|increase|decrease|up|down|change|toggle|show)\\b.*")) {
            String w = set.group(1);
            String k = w.startsWith("wi") ? "wifi" : w.equals("bluetooth") ? "bluetooth" : w.contains("data") ? "mobile_data" : w.matches("(airplane|aeroplane|flight) mode") ? "airplane"
                : w.equals("hotspot") ? "hotspot" : w.equals("nfc") ? "nfc" : w.equals("brightness") ? "brightness" : w.matches("location|gps") ? "location" : "battery_saver";
            return new Call("phone_setting", Call.obj("setting", k), 1, "rules");
        }
        if (s.matches("^ (open |show )?(my |the )?(phone )?settings $")) return new Call("phone_setting", Call.obj("setting", "all"), 1, "rules");
        return null;
    }

    /**
     * Needle picks a tool for almost anything, so small talk ("are you dumb?") can come back as a trick.
     * A command only counts when the sentence has at least one word that belongs to it; otherwise it's chat.
     */
    /** Picks between Needle's call and the rules' call; null means "just chat". */
    static Call decide(Call needle, Call ruled, String text) {
        if (ruled != null && ("ask".equals(ruled.name) || "read_notes".equals(ruled.name) || Call.EXTRA.contains(ruled.name))) return ruled; // Needle doesn't know the newer actions
        // When no command words matched, Needle must be very sure, so small talk reaches the chat brain.
        float bar = ruled == null ? .8f : .45f;
        if (needle == null || needle.confidence < bar || !known(needle.name) || !plausible(needle.name, text)) return ruled;
        return needle;
    }

    static boolean known(String n) {
        switch (n) {
            case "set_alarm": case "set_timer": case "call_contact": case "send_message": case "flashlight":
            case "media_control": case "phone_status": case "take_note": case "sweety_fun": return true;
            default: return false;
        }
    }

    static boolean plausible(String tool, String text) {
        String k;
        switch (tool) {
            case "set_alarm": k = "alarm|wake me|wake up|morning"; break;
            case "set_timer": k = "timer|remind|reminder|countdown|minutes?|mins?|seconds?|hours?"; break;
            case "call_contact": k = "call|ring|phone|dial"; break;
            case "send_message": k = "message|text|sms|whatsapp|send|msg"; break;
            case "flashlight": k = "torch|flash\\w*|light"; break;
            case "media_control": k = "music|song|songs|play|pause|next|previous|skip|volume|louder|quieter|softer|track|resume"; break;
            case "phone_status": k = "battery|charge|charging|time|clock|date|what day|which day"; break;
            case "take_note": k = "note|notes|write|remember|jot"; break;
            case "sweety_fun": k = "danc\\w*|party|groove|boogie|jump|hop|spin|twirl|flip|backflip|somersault|cart ?wheel\\w*|wheeler|wheelie|laugh|giggle|joke|jokes|funny|sleep|nap|night|outfit|clothes|dress|wear|costume|wave|kitty|bunny|panda|snow|sunny|trick|tricks|catch|chase|birds?|birdie|butter ?fl(?:y|ies)|hide|go away|disappear|vanish|come back|come out|where are you|appear|reappear"; break;
            default: return true;
        }
        return Pattern.compile("\\b(" + k + ")\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static final String[] SMALL = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
    private static final Pattern MATH = Pattern.compile(
        "(?:what(?:'s| is)|how much is|calculate|tell me)?\\s*(-?\\d+(?:\\.\\d+)?)\\s*(plus|\\+|and|minus|-|less|times|x|\\*|multiplied by|into|divided by|over|/)\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\??");

    /** Simple sums are worked out exactly (the small chat brain sometimes guesses): "what is 7 plus 5" -> "7 plus 5 is 12!". */
    static String math(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[?!,.](?!\\d)", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceFirst("^(hey |hi |ok |okay )?(sweety|sweetie)\\s*", "");
        for (int t = 9; t >= 2; t--) for (int u = 9; u >= 1; u--) s = s.replaceAll("\\b" + TENS[t] + "[ -]" + SMALL[u] + "\\b", String.valueOf(t * 10 + u));
        for (int t = 9; t >= 2; t--) s = s.replaceAll("\\b" + TENS[t] + "\\b", String.valueOf(t * 10));
        for (int i = SMALL.length - 1; i >= 0; i--) s = s.replaceAll("\\b" + SMALL[i] + "\\b", String.valueOf(i));
        s = s.replaceAll("\\bhundred\\b", "100");
        Matcher m = MATH.matcher(s);
        if (!m.matches()) return null;
        double a = Double.parseDouble(m.group(1)), b = Double.parseDouble(m.group(3));
        String op = m.group(2), word;
        double r;
        if (op.matches("plus|\\+|and")) { r = a + b; word = "plus"; }
        else if (op.matches("minus|-|less")) { r = a - b; word = "minus"; }
        else if (op.matches("times|x|\\*|multiplied by|into")) { r = a * b; word = "times"; }
        else {
            if (b == 0) return "Dividing by zero? Even robots can't do that! Hehe.";
            r = a / b; word = "divided by";
        }
        return num(a) + " " + word + " " + num(b) + " is " + num(r) + "! Easy peasy robot math.";
    }

    private static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Everyday mishearings from offline speech-to-text: "what tim is it" -> "what time is it". */
    static String misheard(String s) {
        return s.replaceAll("\\b(what|the) (tim|tam|thyme|tine)\\b", "$1 time").replaceAll("\\b(tim|tam|thyme|tine) (is it|now|please)\\b", "time $2")
            .replaceAll("\\b(sat|said|sit) (in|on|and|an) alarm\\b", "set an alarm").replaceAll("\\bset (in|on|and) alarm\\b", "set an alarm");
    }

    private static final String[] HOURS = {"twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven"};

    /** Speech-to-text often writes times as words: "seven I am" -> "7 am", "six thirty p m" -> "6 30 pm". */
    static String spokenTime(String s) {
        String t = s.replaceAll("\\bhalf past (\\w+)", "$1 thirty").replaceAll("\\bfifteen\\b", "15").replaceAll("\\bthirty\\b", "30")
            .replaceAll("\\bforty[ -]?five\\b", "45").replaceAll("\\bo'? ?clock\\b", "");
        for (int i = HOURS.length - 1; i >= 0; i--) t = t.replaceAll("\\b" + HOURS[i] + "\\b", String.valueOf(i == 0 ? 12 : i));
        t = t.replaceAll("(\\d) +(i|eye|a) ?am\\b", "$1 am").replaceAll("\\ba ?\\. ?m\\b\\.?|\\ba m\\b", "am").replaceAll("\\bp ?\\.? ?m\\b\\.?", "pm");
        return t.replaceAll("\\b(\\d{1,2}) (\\d{2})\\b", "$1:$2");
    }

    private static String cleanName(String who) {
        return who.replaceAll("\\b(please|now|for me|on whatsapp|on my phone|number)\\b", "").replaceAll("\\s+", " ").trim();
    }

    private static double word(String w) {
        switch (w) {
            case "a": case "an": case "one": return 1;
            case "two": return 2; case "three": return 3; case "five": return 5; case "ten": return 10;
            case "fifteen": return 15; case "twenty": return 20; case "thirty": return 30;
            case "half a": case "half an": return .5;
            default:
                try { return Double.parseDouble(w); } catch (Exception e) { return 0; }
        }
    }
}
