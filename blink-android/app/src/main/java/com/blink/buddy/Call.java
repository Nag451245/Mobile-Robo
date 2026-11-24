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
        + "{\"name\":\"sweety_fun\",\"description\":\"Make Sweety the robot dance, wave, jump, spin, do a backflip or cartwheel, laugh, tell a joke, sleep, change outfit, catch a butterfly or bird, hide, or come back\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"action\":{\"type\":\"string\",\"enum\":[\"dance\",\"wave\",\"jump\",\"spin\",\"flip\",\"cartwheel\",\"laugh\",\"joke\",\"sleep\",\"outfit\",\"catch\",\"hide\",\"show\"]},\"outfit\":{\"type\":\"string\",\"description\":\"Outfit name like kitty, bunny, panda, sunny\"},\"critter\":{\"type\":\"string\",\"enum\":[\"butterfly\",\"bird\"],\"description\":\"What to catch\"}},\"required\":[\"action\"]}}"
        + "]";

    /**
     * More phone actions (docs/requirements.md, section 3), for Gemini and the rules. Not given to the small
     * offline Needle model, whose short tool list it was trained and tested on.
     */
    static final String EXTRA_TOOLS_JSON = "["
        + "{\"name\":\"open_app\",\"description\":\"Open an app on the phone by its name, for example open YouTube or open the calculator\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"name\":{\"type\":\"string\",\"description\":\"The app's name\"}},\"required\":[\"name\"]}},"
        + "{\"name\":\"navigate\",\"description\":\"Directions to a place in Maps, or find places nearby (restaurants, petrol stations, ATMs)\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"to\":{\"type\":\"string\",\"description\":\"The place, address, or home / work\"},\"nearby\":{\"type\":\"boolean\",\"description\":\"true to search for places of this kind nearby\"},"
        + "\"mode\":{\"type\":\"string\",\"enum\":[\"driving\",\"walking\",\"transit\",\"bicycling\"]}},\"required\":[\"to\"]}},"
        + "{\"name\":\"web_search\",\"description\":\"Search the web and show the results\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}},"
        + "{\"name\":\"phone_setting\",\"description\":\"Open a phone setting so the user can switch it: wifi, bluetooth, mobile data, airplane mode, hotspot, location, brightness, volume, battery saver, nfc, or all settings\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"setting\":{\"type\":\"string\",\"enum\":[\"wifi\",\"bluetooth\",\"mobile_data\",\"airplane\",\"hotspot\",\"location\",\"brightness\",\"volume\",\"battery_saver\",\"nfc\",\"all\"]}},\"required\":[\"setting\"]}},"
        + "{\"name\":\"do_not_disturb\",\"description\":\"Turn Do Not Disturb (silence notifications and calls) on or off\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"on\":{\"type\":\"boolean\"}},\"required\":[\"on\"]}},"
        + "{\"name\":\"stopwatch\",\"description\":\"Start, stop, reset or read a stopwatch\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"action\":{\"type\":\"string\",\"enum\":[\"start\",\"stop\",\"reset\",\"read\"]}},\"required\":[\"action\"]}},"
        + "{\"name\":\"find_phone\",\"description\":\"Ring the phone loudly so the user can find it\",\"parameters\":{\"type\":\"object\",\"properties\":{}}},"
        + "{\"name\":\"list\",\"description\":\"The user's shopping list or to-do list: add items, read it, remove an item, or clear it\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"action\":{\"type\":\"string\",\"enum\":[\"add\",\"read\",\"remove\",\"clear\"]},\"list\":{\"type\":\"string\",\"description\":\"shopping or todo\"},"
        + "\"item\":{\"type\":\"string\",\"description\":\"What to add or remove; several items separated by commas\"}},\"required\":[\"action\",\"list\"]}},"
        + "{\"name\":\"play_media\",\"description\":\"Play a particular song, artist, playlist, podcast or video, on YouTube, YouTube Music or Spotify\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"query\":{\"type\":\"string\"},\"app\":{\"type\":\"string\",\"enum\":[\"youtube\",\"youtube_music\",\"spotify\",\"any\"]}},\"required\":[\"query\"]}},"
        + "{\"name\":\"camera\",\"description\":\"Open the camera to take a photo, a selfie or a video\",\"parameters\":{\"type\":\"object\",\"properties\":{"
        + "\"mode\":{\"type\":\"string\",\"enum\":[\"photo\",\"selfie\",\"video\"]}}}}"
        + "]";

    /** The action names in EXTRA_TOOLS_JSON. */
    static final java.util.List<String> EXTRA = java.util.Arrays.asList("open_app", "navigate", "web_search", "phone_setting",
        "do_not_disturb", "stopwatch", "find_phone", "list", "play_media", "camera");

    static final String SYSTEM_PROMPT = "You are Sweety, a cute robot assistant on an Android phone. Pick the tool that does what the user asks.";
}
