package com.blink.buddy;

import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Qwen3-0.6B on Google's LiteRT-LM. Runs only inside {@link ChatService}'s own process, so that if the engine ever
 * crashes in native code on some phone, Sweety herself (Hey Sweety, commands, the floating robot) keeps running.
 */
final class ChatEngine {
    private static final String TAG = "SweetyChat";
    // Kept short: every new chat re-reads it, and a small phone reads about 50-200 words a second.
    private static final String PERSONA =
        "You are Sweety, a tiny, bubbly, cheeky robot girl who lives on the user's phone. "
        + "Answer the question correctly and simply first, then add a playful touch. If teased, tease back sweetly. "
        + "Reply in one to three short spoken sentences, sometimes with \"hehe\". No emojis, lists or markdown. "
        + "You can't see the internet, weather or news.";

    /** Example chats the small model copies the style of. */
    private static List<Message> examples() {
        String[][] ex = {
            {"You're stupid.", "Hey, rude! I'm small, not silly. Ask me something tricky and I'll prove it!"},
            {"What is 12 times 3?", "That's 36! Easy peasy robot math."},
            {"I'm bored.", "Ooh, let's fix that! Want a joke, a riddle, or a little dance?"},
        };
        List<Message> out = new ArrayList<>();
        for (String[] e : ex) { out.add(Message.Companion.user(e[0])); out.add(Message.Companion.model(e[1])); }
        return out;
    }

    private final File model, cache;
    private Engine engine;
    private Conversation conv;
    private int turns;
    private String failure;
    /** "full": persona, example chats and gentle sampling. "plain": persona only, in case this engine refuses the rest. */
    private String mode = "full";
    private int convPart = -1;

    ChatEngine(File model, File cache) { this.model = model; this.cache = cache; }

    synchronized boolean ready() { return engine != null; }
    synchronized String failure() { return failure; }

    synchronized boolean ensure() {
        if (engine != null) return true;
        if (!model.exists()) { failure = "model file missing"; return false; }
        long t0 = System.currentTimeMillis();
        Engine e = null;
        try {
            // The cache dir lets LiteRT-LM keep its prepared weights, so later starts are quicker.
            e = new Engine(new EngineConfig(model.getAbsolutePath(), new Backend.CPU(), null, null, null, null, cache.getAbsolutePath()));
            e.initialize();
            engine = e;
            newConversation();
            failure = null;
            Log.i(TAG, "chat brain ready in " + (System.currentTimeMillis() - t0) + " ms (" + mode + " setup)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "chat engine failed", t);
            failure = "could not start (" + why(t) + ")";
            if (e != null) { try { e.close(); } catch (Throwable ignored) { } }
            engine = null;
            return false;
        }
    }

    /** The error in a few words, for the status line. */
    static String why(Throwable t) {
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m.replaceAll("\\s+", " "));
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private void newConversation() {
        if (conv != null) { try { conv.close(); } catch (Throwable ignored) { } conv = null; }
        turns = 0;
        // She knows the time, so late-night sleepiness and "good morning" chit-chat come out right.
        convPart = DayTime.now();
        String persona = PERSONA + " " + DayTime.chatContext(java.util.Calendar.getInstance());
        if ("full".equals(mode)) {
            try {
                // A little randomness (temperature 0.4) keeps her lively without wandering off the question.
                conv = engine.createConversation(new ConversationConfig(Contents.Companion.of(persona), examples(),
                    Collections.emptyList(), new SamplerConfig(20, 0.9, 0.4, (int) (System.nanoTime() & 0x7fffffff))));
                return;
            } catch (Throwable t) {
                Log.w(TAG, "full chat setup failed, using the plain one", t);
                mode = "plain";
            }
        }
        conv = engine.createConversation(new ConversationConfig(Contents.Companion.of(persona)));
    }

    /** One short reply in Sweety's voice, or null. Blocking. */
    synchronized String reply(String text) {
        if (!ensure()) return null;
        for (int attempt = 0; attempt < 2; attempt++) {
            long t0 = System.currentTimeMillis();
            try {
                // A fresh chat every few turns keeps the small context window clear, and a new part of the day updates her clock.
                if (conv == null || turns >= 4 || convPart != DayTime.now()) newConversation();
                turns++;
                String out = clean(conv.sendMessage(text));
                Log.i(TAG, "chat reply in " + (System.currentTimeMillis() - t0) + " ms (" + mode + "): " + out);
                failure = null;
                return out.isEmpty() ? null : out;
            } catch (Throwable t) {
                Log.w(TAG, "chat failed (try " + (attempt + 1) + ", " + mode + " setup)", t);
                failure = "last reply failed (" + why(t) + ")";
                if (!String.valueOf(t.getMessage()).contains("DEADLINE")) mode = "plain"; // the fancy setup may be what broke; retry with the plain one
                try { newConversation(); }
                catch (Throwable t2) { Log.w(TAG, "could not reset the chat", t2); failure = "could not reset (" + why(t2) + ")"; return null; }
            }
        }
        return null;
    }

    static String clean(Message m) {
        StringBuilder sb = new StringBuilder();
        if (m != null && m.getContents() != null)
            for (Content c : m.getContents().getContents()) if (c instanceof Content.Text) sb.append(((Content.Text) c).getText());
        String out = sb.toString().replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?s)<think>.*", "").replaceAll("[*#_`]", "")
            .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "") // emojis
            .replaceAll("^(?i)sweety\\s*:\\s*", "").replaceAll("\\s+", " ").trim();
        if (out.length() > 400) { int cut = out.lastIndexOf('.', 400); out = out.substring(0, cut > 80 ? cut + 1 : 400); }
        return out;
    }
}
