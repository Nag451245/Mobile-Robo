package com.blink.buddy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds "Hey Sweety" in what Whistle heard, however it spelled her name: "hey sweetie", "hi sweaty",
 * "okay sweet tea", "hey swathi", "a sweety"… Plain Java, so the CI tests run it on real transcripts.
 */
final class WakePhrase {
    private static final Pattern WORD = Pattern.compile("[a-z']+");
    private static final String[] GREETINGS = {"hey", "hay", "hei", "hai", "hi", "hello", "hallo", "ok", "okay", "oh", "o", "yo", "he", "heh", "hiya", "ay", "eh",
        "i", "ah", "aye", "high", "hie", "his", "hes", "he's", "hase", "haze", "hays", "hay's", "hey's", "heys", "hies"};
    /** Greetings Whistle wrote with the "s" of "Sweety" stuck on ("hey sweety" -> "his weedy", "hase witty"). */
    private static final String[] S_GREETINGS = {"his", "hes", "he's", "hase", "haze", "hays", "hay's", "hey's", "heys", "hies"};
    /** Words that look like her name to the sound rules but are everyday words. */
    private static final String[] NOT_NAME = {"sweet", "sweets", "suit", "suits", "set", "sit", "seat", "street", "state", "sweat", "swat", "sweater", "sweden",
        "swiggy", "switch", "swim", "swing", "swear", "sweep", "swift", "south", "stay", "story", "start", "stop", "store", "site", "sweetly"};

    private WakePhrase() {}

    /** The words after the wake phrase ("" when nothing follows), or null when she wasn't called. */
    static String find(String heard) {
        if (heard == null) return null;
        String low = heard.toLowerCase(Locale.ROOT).replace('’', '\'');
        List<int[]> spans = new ArrayList<>();
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(low);
        while (m.find()) { spans.add(new int[]{m.start(), m.end()}); words.add(m.group()); }
        for (int i = 0; i < words.size(); i++) {
            boolean greeted = i > 0 && greeting(words.get(i - 1));
            boolean atStart = i == 0 || (i == 1 && ("a".equals(words.get(0)) || greeting(words.get(0))));
            if (!greeted && !atStart) continue;
            int len = nameLength(words, i, greeted);
            // "his weedy", "hase witty": the s went to the greeting
            if (len == 0 && greeted && words.get(i).startsWith("w") && isAny(words.get(i - 1), S_GREETINGS)) {
                List<String> fixed = new ArrayList<>(words);
                fixed.set(i, "s" + words.get(i));
                len = nameLength(fixed, i, true);
            }
            // "heysweety", "hisweety": greeting and name run together
            if (len == 0 && atStart && words.get(i).matches("(hey|hay|hi|he|his|hes)s?w.*")) {
                String w = words.get(i).replaceFirst("^(hey|hay|hi|he|his|hes)", "");
                if (!w.startsWith("s")) w = "s" + w;
                List<String> fixed = new ArrayList<>(words);
                fixed.set(i, w);
                len = nameLength(fixed, i, true);
            }
            if (len == 0) continue;
            int end = spans.get(i + len - 1)[1];
            return heard.substring(end).replaceAll("^[\\s,.!?;:\\-]+", "").replaceAll("[.!]+$", "").trim();
        }
        return null;
    }

    private static boolean greeting(String w) { return isAny(w, GREETINGS); }

    private static boolean isAny(String w, String[] list) { for (String g : list) if (g.equals(w)) return true; return false; }

    /** How many words starting at i spell her name (0 = not her name). Loose spellings only count after a greeting. */
    private static int nameLength(List<String> words, int i, boolean greeted) {
        String w = words.get(i);
        String next = i + 1 < words.size() ? words.get(i + 1) : "";
        // "sweet tea", "sweet e", "suite y": her name split in two
        if ((w.equals("sweet") || w.equals("suite") || w.equals("sweat") || w.equals("swee")) && next.matches("tea|tee|t|e|ee|y|ie|ty|tie|teeth|ii")) return 2;
        if (exact(w)) return 1;
        if (!greeted) return 0;
        if (w.equals("sweet") || w.equals("suite") || w.equals("sweetly")) return 1; // "hey sweet, dance"
        for (String n : NOT_NAME) if (n.equals(w)) return 0;
        return soundsLike(w) ? 1 : 0;
    }

    private static boolean exact(String w) {
        return w.matches("swee?t(y|ie|ey|i|ee|ty|tie|ies|ys|ii)|sweaty|swetty|swety|sweti|sweeti|sweetee|suit(e)?y|switty|sweety's|sweetie's");
    }

    /** Sound skeleton: first letter kept, vowels and h dropped, doubles merged. "swathi" -> "swt", "sweety" -> "swt". */
    static String skeleton(String w) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < w.length(); k++) {
            char c = w.charAt(k);
            if (k > 0 && "aeiouyh'".indexOf(c) >= 0) continue;
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == c) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean soundsLike(String w) {
        if (w.length() < 4 || w.length() > 9) return false;
        if (!(w.startsWith("sw") || w.startsWith("su") || w.startsWith("sv"))) return false;
        String k = skeleton(w);
        if (k.equals("swt") || k.equals("svt") || k.equals("swd")) return true;
        if (w.startsWith("su") && k.equals("st") && w.length() >= 5) return true; // "suity", "suitey"
        return w.startsWith("swee") && k.length() <= 4; // "sweeny", "sweeby"
    }
}
