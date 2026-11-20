package com.blink.buddy;

import android.graphics.Color;

/** The nine outfits: colours plus a list of accessories. Same palette as the desktop and web versions. */
final class Skin {
    final String id, name, acc;
    final int shell, belly, trim, limb, accent, inner, bow, hat, leaf, eye, bg0, bg1, blush;
    final boolean catMouth;

    private Skin(String id, String name, String shell, String belly, String trim, String limb, String accent, String inner,
                 String bow, String hat, String leaf, String eye, String bg0, String bg1, String blush, boolean catMouth, String acc) {
        this.id = id; this.name = name; this.acc = "," + acc + ",";
        this.shell = Color.parseColor(shell); this.belly = Color.parseColor(belly); this.trim = Color.parseColor(trim);
        this.limb = Color.parseColor(limb != null ? limb : shell); this.accent = Color.parseColor(accent);
        this.inner = Color.parseColor(inner); this.bow = Color.parseColor(bow != null ? bow : accent);
        this.hat = Color.parseColor(hat != null ? hat : accent); this.leaf = Color.parseColor(leaf != null ? leaf : "#6CD47E");
        this.eye = Color.parseColor(eye); this.bg0 = Color.parseColor(bg0); this.bg1 = Color.parseColor(bg1);
        this.blush = Color.parseColor(blush); this.catMouth = catMouth;
    }

    boolean has(String a) { return acc.contains("," + a + ","); }

    static final Skin[] ALL = {
        new Skin("bubblegum", "Bubblegum", "#FFB3D4", "#FFF0F7", "#56C9BA", null, "#FFD447", "#FF86B8", "#FF5FA2", null, null, "#7DF9FF", "#3A1652", "#170829", "#FF7AB6", false, "antenna,discs,bow"),
        new Skin("sunny", "Sunny", "#FFD84A", "#FFF6CF", "#3FA2F7", null, "#FF6F59", "#FF9F80", null, null, null, "#9BFFF0", "#0E2B4D", "#061629", "#FF8A65", false, "antenna,discs"),
        new Skin("kitty", "Mint Kitty", "#8FE3C8", "#F0FFF8", "#2E8C7C", null, "#FF9EC7", "#FFB0D0", null, null, null, "#FFF38A", "#0F3838", "#051E1E", "#FF8FC0", true, "catEars,tail"),
        new Skin("bunny", "Lavender Bunny", "#C9B5FF", "#FFFFFF", "#7B5CE0", null, "#FFB3D9", "#FFC2E0", null, null, null, "#FFD6F5", "#24154A", "#0E0826", "#FF9ACB", true, "bunnyEars"),
        new Skin("panda", "Panda", "#FAFAFA", "#FFFFFF", "#2A2A33", "#2A2A33", "#7ED957", "#4A4A55", null, null, "#7ED957", "#B8F3FF", "#1A1D24", "#0A0B0F", "#FF9EB5", false, "bearEars,leaf"),
        new Skin("sprout", "Sprout", "#FFEFD0", "#FFFFFF", "#3E9B6B", null, "#7BD88F", "#FFB38A", null, null, "#6CD47E", "#B9FF9C", "#123222", "#06180F", "#FF9F8A", false, "leaf,discs"),
        new Skin("classic", "Classic", "#F2F4F7", "#FFFFFF", "#2A3544", null, "#FFB020", "#FFB020", null, null, null, "#6FF3FF", "#0F1D2F", "#050B14", "#FF7AB0", false, "antenna,discs"),
        new Skin("snow", "Snow Buddy", "#DDF1FF", "#FFFFFF", "#5C8FD6", null, "#FF5A6E", "#FF5A6E", null, "#FF4D64", null, "#A8F0FF", "#102A4A", "#061328", "#FF8FA8", false, "beanie"),
        new Skin("nightcap", "Sleepy Cap", "#A9B8FF", "#E8ECFF", "#3D4B9E", null, "#FFE27A", "#FFE27A", null, "#4B5BD6", null, "#FFE9A8", "#121640", "#070820", "#FF9AC2", true, "nightcap")
    };

    static Skin byId(String id) {
        for (Skin s : ALL) if (s.id.equals(id)) return s;
        return ALL[0];
    }
}
