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
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An offline chat brain (Gemma 4 E2B or Qwen3-0.6B) on Google's LiteRT-LM. Runs only inside {@link ChatService}'s
 * own process, so that if the engine ever crashes in native code on some phone, Sweety herself (Hey Sweety,
 * commands, the floating robot) keeps running.
 */
final class ChatEngine {
    private static final String TAG = "SweetyChat";
    // Kept short for the small brain: every new chat re-reads it, and a small phone reads about 50-200 words a second.
    private static final String PERSONA =
        "You are Sweety, a tiny, bubbly, cheeky robot girl who lives on the user's phone. "
        + "Answer the question correctly and simply first, then add a playful touch. If teased, tease back sweetly. "
        + "Reply in one to three short spoken sentences, sometimes with \"hehe\". No emojis, lists or markdown. "
        + "You can't see the internet, weather or news.";
    /** The big brain is smart enough to be a real assistant: a fuller brief. */
    private static final String PERSONA_BIG =
        "You are Sweety, a warm, playful little robot girl who lives on the user's phone as their personal assistant and friend. "
        + "Be genuinely helpful and smart: give correct, specific answers, explain things clearly, and help the user think, plan, write and decide. "
        + "You are speaking out loud, so talk naturally: usually one to three sentences, warm and a little cheeky; never lists, markdown or emojis. "
        + "Remember what the user said earlier in this conversation and follow up on it. "
        + "You are running offline on the phone, so you can't look up live things like news, weather, prices or scores: say so kindly if asked.";

    interface Pieces { void piece(String text); }

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
    private final boolean big, wantGpu;
    private Engine engine;
    private Conversation conv;
    private int turns;
    private String failure, backend;
    private boolean gpuFailed;
    /** "full": persona, example chats and gentle sampling. "plain": persona only, in case this engine refuses the rest. */
    private String mode = "full";
    private int convPart = -1;

    ChatEngine(File model, File cache, boolean big, boolean gpu) { this.model = model; this.cache = cache; this.big = big; this.wantGpu = gpu; }

    /** Already the engine for this brain (a GPU that failed here is on the CPU anyway). */
    synchronized boolean isFor(File m, boolean gpu) { return model.equals(m) && (wantGpu == gpu || gpuFailed); }
    synchronized boolean ready() { return engine != null; }
    synchronized String failure() { return failure; }
    synchronized String backend() { return backend; }
    synchronized boolean gpuFailed() { return gpuFailed; }

    synchronized boolean ensure() {
        if (engine != null) return true;
        if (!model.exists()) { failure = "model file missing"; return false; }
        // The big brain runs on the GPU when the phone allows (several times faster to start answering), else the CPU.
        if (big && wantGpu && !gpuFailed) {
            if (start(new Backend.GPU(), "GPU")) return true;
            gpuFailed = true;
            Log.w(TAG, "GPU didn't start, using the CPU");
        }
        return start(new Backend.CPU(), "CPU");
    }

    private boolean start(Backend b, String name) {
        long t0 = System.currentTimeMillis();
        Engine e = null;
        try {
            // The cache dir lets LiteRT-LM keep its prepared weights, so later starts are quicker.
            e = new Engine(new EngineConfig(model.getAbsolutePath(), b, null, null, big ? 4096 : null, null, cache.getAbsolutePath()));
            e.initialize();
            engine = e;
            backend = name;
            newConversation();
            failure = null;
            Log.i(TAG, "chat brain " + model.getName() + " ready in " + (System.currentTimeMillis() - t0) + " ms on the " + name + " (" + mode + " setup)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "chat engine failed on the " + name, t);
            failure = "could not start (" + why(t) + ")";
            if (e != null) { try { e.close(); } catch (Throwable ignored) { } }
            engine = null;
            return false;
        }
    }

    synchronized void close() {
        if (conv != null) { try { conv.close(); } catch (Throwable ignored) { } conv = null; }
        if (engine != null) { try { engine.close(); } catch (Throwable ignored) { } engine = null; }
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
        String persona = (big ? PERSONA_BIG : PERSONA) + " " + DayTime.chatContext(java.util.Calendar.getInstance());
        if ("full".equals(mode)) {
            try {
                // A little randomness keeps her lively without wandering off the question.
                conv = engine.createConversation(new ConversationConfig(Contents.Companion.of(persona), big ? Collections.emptyList() : examples(),
                    Collections.emptyList(), new SamplerConfig(big ? 40 : 20, 0.9, big ? 0.7 : 0.4, (int) (System.nanoTime() & 0x7fffffff))));
                return;
            } catch (Throwable t) {
                Log.w(TAG, "full chat setup failed, using the plain one", t);
                mode = "plain";
            }
        }
        conv = engine.createConversation(new ConversationConfig(Contents.Companion.of(persona)));
    }

    /** One short reply in Sweety's voice, or null. With pieces, the answer is handed over as it is written. Blocking. */
    synchronized String reply(String text, Pieces pieces) {
        if (!ensure()) return null;
        for (int attempt = 0; attempt < 2; attempt++) {
            long t0 = System.currentTimeMillis();
            final boolean[] sent = {false};
            try {
                // A fresh chat every few turns keeps the context clear (the small brain's window is tiny), and a new
                // part of the day updates her clock.
                if (conv == null || turns >= (big ? 12 : 4) || convPart != DayTime.now()) newConversation();
                turns++;
                String out;
                if (pieces != null && attempt == 0) out = stream(text, piece -> { sent[0] = true; pieces.piece(piece); }, t0);
                else out = clean(conv.sendMessage(text));
                Log.i(TAG, "chat reply in " + (System.currentTimeMillis() - t0) + " ms (" + backend + ", " + mode + "): " + out);
                failure = null;
                return out.isEmpty() ? null : out;
            } catch (Throwable t) {
                Log.w(TAG, "chat failed (try " + (attempt + 1) + ", " + mode + " setup)", t);
                failure = "last reply failed (" + why(t) + ")";
                if (sent[0]) return null; // she already started saying it: don't start over
                if (!String.valueOf(t.getMessage()).contains("DEADLINE")) mode = "plain"; // the fancy setup may be what broke; retry with the plain one
                try { newConversation(); }
                catch (Throwable t2) { Log.w(TAG, "could not reset the chat", t2); failure = "could not reset (" + why(t2) + ")"; return null; }
            }
        }
        return null;
    }

    /** The answer as it's written: each new piece is passed on, the whole (cleaned) answer returned. */
    private String stream(String text, Pieces pieces, long t0) throws Throwable {
        StringBuilder all = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] err = {null};
        boolean[] first = {true};
        conv.sendMessageAsync(text, new MessageCallback() {
            @Override public void onMessage(Message m) {
                String t = textOf(m);
                if (t.isEmpty()) return;
                // Pieces are new text; if the engine ever sends the answer so far instead, take only what's new.
                String add = all.length() > 0 && t.startsWith(all.toString()) ? t.substring(all.length()) : t;
                if (add.isEmpty()) return;
                all.append(add);
                if (first[0]) { first[0] = false; Log.i(TAG, "first words after " + (System.currentTimeMillis() - t0) + " ms (" + backend + ")"); }
                if (all.indexOf("<think>") < 0 || all.indexOf("</think>") >= 0) pieces.piece(add.replaceAll("(?s)</?think>", ""));
            }
            @Override public void onDone() { done.countDown(); }
            @Override public void onError(Throwable t) { err[0] = t; done.countDown(); }
        });
        if (!done.await(150, TimeUnit.SECONDS)) throw new RuntimeException("DEADLINE: the answer took too long");
        if (err[0] != null) throw err[0];
        return cleanText(all.toString());
    }

    private static String textOf(Message m) {
        StringBuilder sb = new StringBuilder();
        if (m != null && m.getContents() != null)
            for (Content c : m.getContents().getContents()) if (c instanceof Content.Text) sb.append(((Content.Text) c).getText());
        return sb.toString();
    }

    static String clean(Message m) { return cleanText(textOf(m)); }

    static String cleanText(String text) {
        String out = text.replaceAll("(?s)<think>.*?</think>", "").replaceAll("(?s)<think>.*", "").replaceAll("[*#_`]", "")
            .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "") // emojis
            .replaceAll("^(?i)sweety\\s*:\\s*", "").replaceAll("\\s+", " ").trim();
        if (out.length() > 600) { int cut = out.lastIndexOf('.', 600); out = out.substring(0, cut > 80 ? cut + 1 : 600); }
        return out;
    }
}
