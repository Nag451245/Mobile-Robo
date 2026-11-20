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
        s = s.replaceFirst("^ (hey |hi |ok |okay )?(sweety|sweetie|sweet)\\b", " ");
        String orig = " " + raw.replaceAll("[?!,]", " ").replaceAll("\\s+", " ").trim() + " "; // keeps the user's capitals for message and note text

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
            Matcher m = TIME.matcher(s);
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
        return null;
    }

    /**
     * Needle picks a tool for almost anything, so small talk ("are you dumb?") can come back as a trick.
     * A command only counts when the sentence has at least one word that belongs to it; otherwise it's chat.
     */
    /** Picks between Needle's call and the rules' call; null means "just chat". */
    static Call decide(Call needle, Call ruled, String text) {
        if (ruled != null && ("ask".equals(ruled.name) || "read_notes".equals(ruled.name))) return ruled;
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
            case "sweety_fun": k = "danc\\w*|party|groove|boogie|jump|hop|spin|twirl|flip|backflip|somersault|cart ?wheel\\w*|wheeler|wheelie|laugh|giggle|joke|jokes|funny|sleep|nap|night|outfit|clothes|dress|wear|costume|wave|kitty|bunny|panda|snow|sunny|trick|tricks"; break;
            default: return true;
        }
        return Pattern.compile("\\b(" + k + ")\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
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
