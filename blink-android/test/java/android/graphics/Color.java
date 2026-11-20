package android.graphics;

/** Stand-in for Android's Color so the plain-Java tests can load Sweety's outfits. */
public final class Color {
    public static int parseColor(String s) { return (int) Long.parseLong(s.substring(1), 16); }
    private Color() {}
}
