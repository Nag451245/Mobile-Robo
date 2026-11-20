package com.blink.buddy;

/** JNI bridge to Cactus Compute's Needle 2 engine (libneedle, Apache-2.0), linked into libsweety.so. */
final class NeedleNative {
    static final boolean LOADED;

    static {
        boolean ok;
        try { System.loadLibrary("sweety"); ok = true; } catch (Throwable t) { ok = false; }
        LOADED = ok;
    }

    static native int load(byte[] model);
    static native int init(String systemPrompt, String toolsJson);
    static native String complete(String input, int maxNewTokens);
    static native void reset();

    private NeedleNative() {}
}
