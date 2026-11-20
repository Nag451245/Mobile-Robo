package com.blink.buddy;

import org.json.JSONObject;

/** One action Sweety decided to take: a tool name plus its arguments. */
final class Call {
    final String name;
    final JSONObject args;
    final float confidence;
    final String source;

    Call(String name, JSONObject args, float confidence, String source) {
        this.name = name;
        this.args = args != null ? args : new JSONObject();
        this.confidence = confidence;
        this.source = source;
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) o.put((String) kv[i], kv[i + 1]); } catch (Exception ignored) { }
        return o;
    }

    /** The tools Sweety's Needle 2 brain can call, in Needle's compact JSON format. */
    static final String TOOLS_JSON = "["
        + "{\"name\":\"set_alarm\",\"description\":\"Set an alarm clock for a time of day, for example wake me up at 7 am\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"hour\":{\"type\":\"integer\",\"description\":\"Hour 0-23 in 24-hour time\"},\"minute\":{\"type\":\"integer\",\"description\":\"Minute 0-59\"},\"label\":{\"type\":\"string\",\"description\":\"Optional alarm name\"}},\"required\":[\"hour\"]}},"
        + "{\"name\":\"set_timer\",\"description\":\"Start a countdown timer or a reminder after some minutes, for example remind me in 10 minutes to drink water\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"minutes\":{\"type\":\"number\",\"description\":\"Minutes from now\"},\"seconds\":{\"type\":\"integer\",\"description\":\"Extra seconds\"},\"label\":{\"type\":\"string\",\"description\":\"What to remind about\"}},\"required\":[\"minutes\"]}},"
        + "{\"name\":\"call_contact\",\"description\":\"Phone call a person by name or number\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"name\":{\"type\":\"string\",\"description\":\"Contact name like mom\"},\"number\":{\"type\":\"string\",\"description\":\"Phone number digits\"}}}},"
        + "{\"name\":\"send_message\",\"description\":\"Send a text or WhatsApp message to a person\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"name\":{\"type\":\"string\",\"description\":\"Contact name\"},\"number\":{\"type\":\"string\",\"description\":\"Phone number\"},\"text\":{\"type\":\"string\",\"description\":\"Message text\"},"
        + "\"app\":{\"type\":\"string\",\"enum\":[\"sms\",\"whatsapp\"],\"description\":\"Which app\"}},\"required\":[\"text\"]}},"
        + "{\"name\":\"flashlight\",\"description\":\"Turn the phone torch or flashlight on or off\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"on\":{\"type\":\"boolean\",\"description\":\"true to turn on, false to turn off\"}},\"required\":[\"on\"]}},"
        + "{\"name\":\"media_control\",\"description\":\"Control music: play, pause, next or previous song, volume up or down\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"action\":{\"type\":\"string\",\"enum\":[\"play\",\"pause\",\"next\",\"previous\",\"volume_up\",\"volume_down\"]}},\"required\":[\"action\"]}},"
        + "{\"name\":\"phone_status\",\"description\":\"Tell the battery level, the current time or today's date\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"item\":{\"type\":\"string\",\"enum\":[\"battery\",\"time\",\"date\"]}},\"required\":[\"item\"]}},"
        + "{\"name\":\"take_note\",\"description\":\"Write down a note to remember\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"text\":{\"type\":\"string\",\"description\":\"The note\"}},\"required\":[\"text\"]}},"
        + "{\"name\":\"sweety_fun\",\"description\":\"Make Sweety the robot dance, wave, jump, spin, do a backflip or cartwheel, laugh, tell a joke, sleep or change outfit\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"action\":{\"type\":\"string\",\"enum\":[\"dance\",\"wave\",\"jump\",\"spin\",\"flip\",\"cartwheel\",\"laugh\",\"joke\",\"sleep\",\"outfit\"]},\"outfit\":{\"type\":\"string\",\"description\":\"Outfit name like kitty, bunny, panda, sunny\"}},\"required\":[\"action\"]}}"
        + "]";

    static final String SYSTEM_PROMPT = "You are Sweety, a cute robot assistant on an Android phone. Pick the tool that does what the user asks.";
}
