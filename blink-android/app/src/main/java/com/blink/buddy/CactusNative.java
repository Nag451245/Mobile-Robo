package com.blink.buddy;

/** JNI bridge to the Cactus 4 runtime (libcactus, Apache-2.0): Needle 3 for actions, Whistle for speech. */
final class CactusNative {
    static final int TEXT = 1, SPEECH = 2;
    static final boolean LOADED;

    static {
        boolean ok;
        try { System.loadLibrary("sweety"); ok = true; } catch (Throwable t) { ok = false; }
        LOADED = ok;
    }

    static native int load(byte[] model);
    static native int models();
    static native String lastError();
    static native void setThreads(int threads);
    static native int init(String systemPrompt, String toolsJson);
    static native String complete(String input, int maxNewTokens);
    static native String transcribe(float[] pcm16k, String language);
    static native void reset();

    private CactusNative() {}
}
