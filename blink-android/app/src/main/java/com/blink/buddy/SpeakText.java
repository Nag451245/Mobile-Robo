package com.blink.buddy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gets a reply ready for the natural voice. Kokoro reads words from a pronunciation dictionary and silently skips
 * anything that isn't in it, such as digits, "7:30" or "Sweety". So numbers, times, money and symbols become
 * words, her name is spelled the dictionary way, and little stage directions like *yawn* lose their stars.
 * Plain Java, so the CI tests check every line she says against the real dictionary.
 */
final class SpeakText {
    private SpeakText() {}

    /**
     * Words the voice's big dictionary is missing: three everyday ones it lost ("or", "her", "seem"), her name, and her
     * playful words. Written next to the dictionary as a second one. Sounds are built from words it does have
     * (seem = seems without the z, moonwalk = moon + walk…). The CI test fails if a line of hers needs a word in neither.
     */
    static final String EXTRA_LEXICON =
        "or || ˈ ɔː ɹ\n" +
        "her || hˈ ɜː\n" +
        "seem || sˈ iː m\n" +
        "sweety || s wˈ iː ɾ i\n" +
        "sweety's || s wˈ iː ɾ i z\n" +
        "sweetie's || s wˈ iː ɾ i z\n" +
        "backflip || bˈ æ k f l ɪ p\n" +
        "backflips || bˈ æ k f l ɪ p s\n" +
        "moonwalk || mˈ uː n w ɔː k\n" +
        "eyebags || ˈ a ɪ b æ ɡ z\n" +
        "nighty || nˈ a ɪ ɾ i\n" +
        "peasy || pˈ iː z i\n" +
        "sleepyhead || s lˈ iː p i h ɛ d\n" +
        "practising || p ɹˈ æ k t ɪ s ɪ ŋ\n" +
        "pyjamas || p ə d ʒˈ ɑː m ə z\n" +
        "shh || ʃˈ ʃ\n" +
        "whee || wˈ iː\n" +
        "wheee || wˈ iː iː\n" +
        "yaaawn || jˈ ɔː ɔː n\n";

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /** 0..999,999,999,999 in words, e.g. 1205 -> "one thousand two hundred five". */
    static String number(long n) {
        if (n < 0) return "minus " + number(-n);
        if (n < 20) return ONES[(int) n];
        if (n < 100) return TENS[(int) (n / 10)] + (n % 10 == 0 ? "" : " " + ONES[(int) (n % 10)]);
        if (n < 1000) return ONES[(int) (n / 100)] + " hundred" + (n % 100 == 0 ? "" : " " + number(n % 100));
        String[] big = {"thousand", "million", "billion"};
        long[] div = {1_000L, 1_000_000L, 1_000_000_000L};
        for (int i = big.length - 1; i >= 0; i--) {
            if (n >= div[i]) return number(n / div[i]) + " " + big[i] + (n % div[i] == 0 ? "" : " " + number(n % div[i]));
        }
        return String.valueOf(n);
    }

    /** 1 -> "first", 22 -> "twenty second". */
    static String ordinal(long n) {
        String w = number(n);
        String[][] irregular = {{"one", "first"}, {"two", "second"}, {"three", "third"}, {"five", "fifth"}, {"eight", "eighth"},
            {"nine", "ninth"}, {"twelve", "twelfth"}};
        int sp = w.lastIndexOf(' ');
        String head = sp < 0 ? "" : w.substring(0, sp + 1), last = sp < 0 ? w : w.substring(sp + 1);
        for (String[] p : irregular) if (last.equals(p[0])) return head + p[1];
        if (last.endsWith("y")) return head + last.substring(0, last.length() - 1) + "ieth";
        return head + last + "th";
    }

    /** A year said the usual way: 1999 -> "nineteen ninety nine", 2026 -> "twenty twenty six", 2005 -> "two thousand five". */
    static String year(int y) {
        if (y >= 2000 && y < 2010) return number(y);
        int hi = y / 100, lo = y % 100;
        return number(hi) + " " + (lo == 0 ? "hundred" : lo < 10 ? "oh " + number(lo) : number(lo));
    }

    private static String clock(int h, int m, String ampm) {
        String s = number(h) + (m == 0 ? (ampm == null ? " o'clock" : "") : m < 10 ? " oh " + number(m) : " " + number(m));
        if (ampm != null) s += ampm.toLowerCase(Locale.ROOT).startsWith("a") ? " ay em" : " pee em";
        return s;
    }

    private static final Pattern STARS = Pattern.compile("\\*([^*]{1,40})\\*");
    private static final Pattern NAME = Pattern.compile("\\bsweety\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile("\\b(\\d{1,2}):(\\d{2})(?:\\s*([AaPp])\\.?\\s?[Mm]\\b)?");
    private static final Pattern HOUR_AMPM = Pattern.compile("\\b(\\d{1,2})\\s*([AaPp])\\.?\\s?[Mm]\\b");
    private static final Pattern MONEY = Pattern.compile("([$£€₹])\\s?(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern PERCENT = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s?%");
    private static final Pattern DEGREES = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s?°\\s?([CF])?");
    private static final Pattern ORDINAL = Pattern.compile("\\b(\\d+)(st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\b(?:in|since|from|of|year)\\s+(1[1-9]\\d\\d|20\\d\\d)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECIMAL = Pattern.compile("(?<![\\d.])(-?)(\\d[\\d,]*)\\.(\\d+)(?![\\d.])");
    private static final Pattern INTEGER = Pattern.compile("(?<![\\w.])(-?)(\\d{1,3}(?:,\\d{3})+|\\d+)(?![\\d])");

    /** Text ready for Kokoro. */
    static String prepare(String text) {
        if (text == null) return "";
        String s = text;
        s = STARS.matcher(s).replaceAll(" $1 ");                     // *yawn* -> yawn
        s = s.replace('’', '\'');                                 // curly apostrophes, as the dictionary has straight ones
        s = s.replaceAll("(\\d)\\s?[-–]\\s?(\\d)", "$1 to $2");    // 5-10 minutes -> 5 to 10 minutes
        s = NAME.matcher(s).replaceAll("Sweetie");                    // the dictionary spells her name this way
        s = s.replaceAll("(?i)\\bz{3,}\\b", "");                      // "zzz" can't be said, just dropped
        s = replace(TIME, s, m -> clock(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3)));
        s = replace(HOUR_AMPM, s, m -> clock(Integer.parseInt(m.group(1)), 0, m.group(2)));
        s = replace(MONEY, s, m -> {
            String cur = m.group(1);
            String unit = cur.equals("$") ? "dollars" : cur.equals("£") ? "pounds" : cur.equals("€") ? "euros" : "rupees";
            return amount(m.group(2)) + " " + unit;
        });
        s = replace(PERCENT, s, m -> amount(m.group(1)) + " percent");
        s = replace(DEGREES, s, m -> amount(m.group(1)) + " degrees" + (m.group(2) == null ? "" : m.group(2).equalsIgnoreCase("C") ? " celsius" : " fahrenheit"));
        s = replace(ORDINAL, s, m -> ordinal(Long.parseLong(m.group(1))));
        s = replace(YEAR, s, m -> m.group().substring(0, m.group().length() - m.group(1).length()) + year(Integer.parseInt(m.group(1))));
        s = replace(DECIMAL, s, m -> (m.group(1).isEmpty() ? "" : "minus ") + amount(m.group(2) + "." + m.group(3)));
        s = replace(INTEGER, s, m -> (m.group(1).isEmpty() ? "" : "minus ") + amount(m.group(2)));
        s = s.replace("&", " and ").replace("+", " plus ").replace("=", " equals ").replace("@", " at ");
        // symbols and emoji she can't say; keep letters, digits, everyday punctuation and apostrophes
        s = s.replaceAll("[^\\p{L}\\p{N}\\s.,!?;:'’\\-…]", " ");
        s = s.replaceAll("\\s+([.,!?;:…])", "$1").replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    /** "1,250" -> "one thousand two hundred fifty", "3.5" -> "three point five", "12.50" -> "twelve point five zero". */
    private static String amount(String raw) {
        String r = raw.replace(",", "");
        int dot = r.indexOf('.');
        try {
            // long digit strings without commas (phone numbers, codes) are read digit by digit
            if (dot < 0) return r.length() > 6 && !raw.contains(",") || r.length() > 12 ? digits(r) : number(Long.parseLong(r));
            String whole = r.substring(0, dot), frac = r.substring(dot + 1);
            return (whole.isEmpty() ? "zero" : whole.length() > 12 ? digits(whole) : number(Long.parseLong(whole))) + " point " + digits(frac);
        } catch (NumberFormatException e) { return digits(r); }
    }

    private static String digits(String d) {
        StringBuilder b = new StringBuilder();
        for (char c : d.toCharArray()) if (Character.isDigit(c)) b.append(b.length() == 0 ? "" : " ").append(ONES[c - '0']);
        return b.toString();
    }

    private interface Fn { String apply(Matcher m); }

    private static String replace(Pattern p, String s, Fn f) {
        Matcher m = p.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(" " + f.apply(m) + " "));
        m.appendTail(out);
        return out.toString();
    }
}
