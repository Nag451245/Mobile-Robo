package com.blink.buddy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Continuous conversation, like a real assistant: once woken, Sweety keeps listening after each answer until the
 * user ends it ("stop", "that's all", "bye"...) or stays quiet for a while. Plain Java, so CI tests the rules.
 */
final class Conversation {
    enum End { NO, STOP, FAREWELL }

    /** Quiet this long after her answer ends the conversation (settable; 0 = only when the user says so). */
    static final int DEFAULT_IDLE_S = 20;

    private static final String NAME = "(?:hey |ok(?:ay)? |hi )?(?:sweety|sweetie|sweet tea|buddy)";
    private static final String SOFT = "(?:ok(?:ay)?|alright|all right|no|nah|um|uh|oh|right|cool|great|perfect|got it)";

    /** Ends at once, without an answer: she just stops. */
    private static final Pattern STOP = Pattern.compile("^(?:" + SOFT + "[, ]+)*(?:" + NAME + "[, ]+)?"
        + "(?:stop(?: it| now| listening| talking| that)?|cancel(?: that)?|never ?mind|forget it|shut up|be quiet|quiet|hush|silence"
        + "|nothing(?: else)?|no(?:thing)? thanks?(?: you)?|that'?s (?:all|it|enough)|that will be all|that'?ll be all"
        + "|i'?m (?:done|good|fine|ok(?:ay)?)|we'?re done|all done|done|enough|end(?: the)? (?:conversation|chat)|dismiss|go away for now|stop the conversation)"
        + "(?:[, ]+(?:thanks?(?: you)?|please))?(?:[, ]+" + NAME + ")?[.!? ]*$");

    /** Ends after her answer (she says goodbye, you're welcome or good night first). */
    private static final Pattern FAREWELL = Pattern.compile("^(?:" + SOFT + "[, ]+)*(?:" + NAME + "[, ]+)?"
        + "(?:thanks?(?: you)?(?: so much| very much| a lot)?(?:[, ]+that'?s (?:all|it))?|cheers|bye(?: bye)?|goodbye|good night|night night"
        + "|see you(?: later| soon)?|talk (?:to you )?(?:later|soon)|go to sleep|sleep now|catch you later|later)"
        + "(?:[, ]+(?:" + NAME + "|for now|then))*[.!? ]*$");

    /** Does this end the conversation? Only whole replies count: "stop the music" or "thanks, and what's the weather" don't. */
    static End ending(String said) {
        if (said == null) return End.NO;
        String s = said.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return End.NO;
        if (STOP.matcher(s).matches()) return End.STOP;
        if (FAREWELL.matcher(s).matches()) return End.FAREWELL;
        return End.NO;
    }

    /** The wake word said again in the middle of a conversation is just her name: drop it. */
    static String withoutWakeWord(String said) {
        if (said == null) return null;
        String rest = WakePhrase.find(said);
        if (rest != null && !rest.trim().isEmpty()) return rest.trim();
        return said.replaceFirst("(?i)^\\s*(?:hey|hi|okay|ok)\\s+buddy[,.!]?\\s+", "").trim();
    }

    /** A short line when the user stops her. */
    static String stopLine(java.util.Random r) {
        String[] l = {"Okay! Call me when you need me.", "Got it. I'm here if you need me.", "Okay, going quiet!", "Sure thing. Talk soon!"};
        return l[r.nextInt(l.length)];
    }
}
