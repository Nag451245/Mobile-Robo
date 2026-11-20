// JNI bridge between Sweety (Java) and Cactus Compute's Needle 2 engine (libneedle.a, Apache-2.0).
#include <jni.h>
#include <string>
#include <vector>
#include "needle.h"

static std::vector<unsigned char> g_model;   // Needle may keep pointers into the model bytes, so they live here.
static std::vector<char> g_out(32768);

static std::string utf(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_blink_buddy_NeedleNative_load(JNIEnv* env, jclass, jbyteArray data) {
    if (!data) return -1;
    jsize n = env->GetArrayLength(data);
    g_model.resize((size_t) n);
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(g_model.data()));
    return needle_load(g_model.data(), (unsigned long long) n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_blink_buddy_NeedleNative_init(JNIEnv* env, jclass, jstring systemPrompt, jstring toolsJson) {
    std::string sp = utf(env, systemPrompt), tools = utf(env, toolsJson);
    return needle_init(sp.c_str(), tools.c_str(), nullptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_blink_buddy_NeedleNative_complete(JNIEnv* env, jclass, jstring input, jint maxNewTokens) {
    std::string in = utf(env, input);
    std::fill(g_out.begin(), g_out.end(), 0);
    int rc = needle_complete(in.c_str(), (int) maxNewTokens, g_out.data(), (int) g_out.size() - 1);
    if (rc < 0) return nullptr;
    // Keep the result plain ASCII so NewStringUTF never sees invalid modified UTF-8.
    std::string out;
    for (const char* p = g_out.data(); *p; ++p) out.push_back((unsigned char) *p < 0x80 ? *p : '?');
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_blink_buddy_NeedleNative_reset(JNIEnv*, jclass) {
    needle_reset();
}
