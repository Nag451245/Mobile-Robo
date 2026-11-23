package com.blink.buddy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sweety's smart brain: Google's Gemini (with the user's own API key) answers, keeps the conversation going and
 * does phone actions through function calling. Plain Java (no Android), so the CI tests can run it against a fake
 * Gemini server. Blocking: call off the main thread.
 */
final class GeminiBrain {
    /** What Gemini decided: words to say (with a gesture and mood) and/or phone actions to run. */
    static final class Answer {
        String text = "", gesture = "nod", emotion = "happy";
        final List<Call> calls = new ArrayList<>();
        final List<String> remember = new ArrayList<>();
    }

    interface Store { String get(String key, String def); void put(String key, String value); }

    static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com";
    static final String FALLBACK_MODEL = "gemini-2.5-flash";
    private static final Pattern TAG = Pattern.compile("^\\s*\\[\\s*([a-z]+)\\s*[|,/ ]\\s*([a-z]+)\\s*\\]\\s*", Pattern.CASE_INSENSITIVE);
    private static final String GESTURES = "wave,nod,dance,jump,spin,flip,cartwheel,laugh,shrug,stretch,sleep";
    private static final String MOODS = "happy,love,curious,surprised,sad,sleepy,laugh,dizzy,angry";

    private final Store store;
    private final String base;
    private final List<JSONObject> history = new ArrayList<>();
    private long lastTurn;
    String lastError;

    GeminiBrain(Store store, String base) { this.store = store; this.base = base == null || base.isEmpty() ? DEFAULT_BASE : base; }

    String key() { return store.get("gemini_key", "").trim(); }
    boolean configured() { return !key().isEmpty(); }

    /* ------------------------------------------------------------ the request */
    static String persona(Calendar now, List<String> memory) {
        StringBuilder sb = new StringBuilder()
            .append("You are Sweety, a warm, playful little robot girl who lives on the user's phone as their personal assistant and friend. ")
            .append("Be genuinely helpful and smart: give correct, specific answers, like a caring, clever friend. ")
            .append("Speak naturally, the way you'd talk out loud: usually one to three sentences, warm and a little cheeky, no lists, no markdown, no emojis. ")
            .append("If a request needs a phone action (alarm, timer or reminder, call, message, torch, music, notes, battery or time, a dance or trick, hiding), call the matching function instead of describing it. ")
            .append("If the user tells you something worth remembering about them (name, birthday, likes, people), call remember. ")
            .append("Start EVERY spoken reply with a tag [gesture|mood], gesture one of ").append(GESTURES)
            .append(" and mood one of ").append(MOODS).append(", for example: [wave|happy] Hi! ")
            .append(DayTime.chatContext(now));
        if (memory != null && !memory.isEmpty()) {
            sb.append(" Things you remember about the user: ");
            for (String m : memory) sb.append(m).append("; ");
        }
        return sb.toString();
    }

    /** The phone actions, in Gemini's function-calling format (types upper-cased), plus "remember". */
    static JSONArray functions() throws Exception {
        JSONArray tools = new JSONArray(Call.TOOLS_JSON), out = new JSONArray();
        for (int i = 0; i < tools.length(); i++) out.put(upperTypes(tools.getJSONObject(i)));
        out.put(new JSONObject().put("name", "remember").put("description", "Remember a fact about the user for later chats, e.g. their name, birthday or favourite food")
            .put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("fact", new JSONObject().put("type", "STRING")))
                .put("required", new JSONArray().put("fact"))));
        out.put(new JSONObject().put("name", "read_notes").put("description", "Read back the notes the user saved"));
        return out;
    }

    private static JSONObject upperTypes(JSONObject o) throws Exception {
        JSONObject r = new JSONObject();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = o.get(k);
            if ("type".equals(k) && v instanceof String) r.put(k, ((String) v).toUpperCase(Locale.ROOT));
            else if (v instanceof JSONObject) r.put(k, upperTypes((JSONObject) v));
            else r.put(k, v);
        }
        return r;
    }

    JSONObject request(String text, Calendar now, List<String> memory, boolean thinkingOff) throws Exception {
        JSONArray contents = new JSONArray();
        for (JSONObject h : history) contents.put(h);
        contents.put(turn("user", text));
        JSONObject gen = new JSONObject().put("temperature", 0.8).put("maxOutputTokens", 400);
        if (thinkingOff) gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)); // answer fast: this is a voice chat
        return new JSONObject()
            .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", persona(now, memory)))))
            .put("contents", contents)
            .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", functions())))
            .put("generationConfig", gen);
    }

    private static JSONObject turn(String role, String text) throws Exception {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    /* ------------------------------------------------------------ talking to Gemini */
    /** Sweety's answer to what the user said, or null if Gemini couldn't be reached (lastError says why). */
    Answer ask(String text, List<String> memory) {
        if (!configured()) { lastError = "no Gemini key"; return null; }
        if (System.currentTimeMillis() - lastTurn > 30 * 60_000L) history.clear(); // a new chat after a long pause
        try {
            String model = model();
            boolean thinkingOff = !"no".equals(store.get("gemini_thinking_off", "yes"));
            String[] res = post("/v1beta/models/" + model + ":generateContent", request(text, Calendar.getInstance(), memory, thinkingOff).toString());
            if (res[0].equals("400") && thinkingOff && res[1].toLowerCase(Locale.ROOT).contains("thinking")) {
                store.put("gemini_thinking_off", "no"); // this model doesn't take that setting
                res = post("/v1beta/models/" + model + ":generateContent", request(text, Calendar.getInstance(), memory, false).toString());
            }
            if (res[0].equals("404")) { // that model is gone: pick again
                store.put("gemini_model", "");
                model = model();
                res = post("/v1beta/models/" + model + ":generateContent", request(text, Calendar.getInstance(), memory, false).toString());
            }
            if (!res[0].equals("200")) { lastError = describe(res); return null; }
            Answer a = parse(new JSONObject(res[1]));
            remember(text, a);
            lastError = null;
            return a;
        } catch (Exception e) {
            lastError = "can't reach Gemini (" + e.getClass().getSimpleName() + ")";
            return null;
        }
    }

    private static String describe(String[] res) {
        String msg = "";
        try { msg = new JSONObject(res[1]).getJSONObject("error").optString("message", ""); } catch (Exception ignored) { }
        switch (res[0]) {
            case "400": case "401": case "403": return msg.toLowerCase(Locale.ROOT).contains("key") ? "the Gemini key wasn't accepted" : "Gemini refused the request (" + res[0] + ": " + shorten(msg) + ")";
            case "429": return "Gemini's free limit is used up for now";
            default: return "Gemini error " + res[0] + (msg.isEmpty() ? "" : ": " + shorten(msg));
        }
    }

    private static String shorten(String s) { return s.length() > 90 ? s.substring(0, 90) + "…" : s; }

    /** The newest Flash model this key can use (asked once and kept), so Sweety keeps up as Google renames models. */
    String model() {
        String m = store.get("gemini_model", "");
        if (!m.isEmpty()) return m;
        try {
            String[] res = get("/v1beta/models?pageSize=200");
            if (res[0].equals("200")) {
                m = pickModel(new JSONObject(res[1]).optJSONArray("models"));
                if (m != null) { store.put("gemini_model", m); return m; }
            }
        } catch (Exception ignored) { }
        return FALLBACK_MODEL;
    }

    static String pickModel(JSONArray models) {
        if (models == null) return null;
        String best = null;
        double bestV = -1;
        for (int i = 0; i < models.length(); i++) {
            JSONObject o = models.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "").replaceFirst("^models/", "");
            JSONArray methods = o.optJSONArray("supportedGenerationMethods");
            if (methods == null || !methods.toString().contains("generateContent")) continue;
            if (!name.matches("gemini-[0-9.]+-flash(-[0-9]+)?")) continue; // plain Flash: no lite, image, tts, live or preview
            Matcher v = Pattern.compile("gemini-([0-9.]+)-flash").matcher(name);
            if (!v.find()) continue;
            double ver;
            try { ver = Double.parseDouble(v.group(1).replaceAll("^(\\d+\\.\\d+).*", "$1")); } catch (Exception e) { continue; }
            if (ver > bestV || ver == bestV && name.length() < best.length()) { bestV = ver; best = name; }
        }
        return best;
    }

    /* ------------------------------------------------------------ the answer */
    static Answer parse(JSONObject res) throws Exception {
        Answer a = new Answer();
        JSONArray cands = res.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) { a.text = "Hmm, I'm not sure what to say to that!"; a.gesture = "shrug"; a.emotion = "curious"; return a; }
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (p.optBoolean("thought", false)) continue;
            if (p.has("text")) sb.append(p.getString("text"));
            JSONObject fc = p.optJSONObject("functionCall");
            if (fc != null) {
                String name = fc.optString("name");
                JSONObject args = fc.optJSONObject("args");
                if ("remember".equals(name)) { if (args != null && !args.optString("fact").isEmpty()) a.remember.add(args.optString("fact")); }
                else a.calls.add(new Call(name, args, 1f, "gemini"));
            }
        }
        String text = sb.toString().trim();
        Matcher m = TAG.matcher(text);
        if (m.find()) {
            String g = m.group(1).toLowerCase(Locale.ROOT), e = m.group(2).toLowerCase(Locale.ROOT);
            if (("," + GESTURES + ",").contains("," + g + ",")) a.gesture = g;
            if (("," + MOODS + ",").contains("," + e + ",")) a.emotion = e;
            text = text.substring(m.end());
        }
        a.text = text.replaceAll("[*#_`]", "").replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "").replaceAll("\\s+", " ").trim();
        return a;
    }

    /** Keeps a short history of plain words (actions are summarised), so follow-up questions make sense. */
    private void remember(String userText, Answer a) throws Exception {
        String said = a.text;
        if (said.isEmpty() && !a.calls.isEmpty()) said = "(I did that: " + a.calls.get(0).name + " " + a.calls.get(0).args + ")";
        if (said.isEmpty()) return;
        history.add(turn("user", userText));
        history.add(turn("model", said));
        while (history.size() > 20) history.remove(0);
        lastTurn = System.currentTimeMillis();
    }

    void forget() { history.clear(); }

    /* ------------------------------------------------------------ HTTP */
    private String[] post(String path, String body) throws Exception { return http("POST", path, body); }
    private String[] get(String path) throws Exception { return http("GET", path, null); }

    private String[] http(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setRequestProperty("x-goog-api-key", key()); // in a header, never in the URL
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); in.close(); }
            return new String[]{String.valueOf(code), bo.toString("UTF-8")};
        } finally { c.disconnect(); }
    }
}
