// JNI bridge between Sweety (Java) and the Cactus 4 runtime (libcactus.a, Apache-2.0) by Cactus Compute.
// One engine runs Needle 3 (understands requests, calls tools) and Whistle (speech to text), fully offline.
#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include "cactus.h"

static std::vector<std::vector<unsigned char>> g_models;  // model bytes stay alive while the engine uses them
static std::vector<char> g_out(32768);

static std::string utf(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// Keep results plain ASCII so NewStringUTF never sees invalid modified UTF-8.
static jstring ascii(JNIEnv* env, const char* p) {
    std::string out;
    for (; p && *p; ++p) out.push_back((unsigned char) *p < 0x80 ? *p : '?');
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_blink_buddy_CactusNative_load(JNIEnv* env, jclass, jbyteArray data) {
    if (!data) return -1;
    jsize n = env->GetArrayLength(data);
    g_models.emplace_back((size_t) n);
    std::vector<unsigned char>& m = g_models.back();
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(m.data()));
    return cactus_load(m.data(), (unsigned long long) n);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_blink_buddy_CactusNative_models(JNIEnv*, jclass) { return cactus_models(); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_blink_buddy_CactusNative_lastError(JNIEnv* env, jclass) { return ascii(env, cactus_last_error()); }

extern "C" JNIEXPORT void JNICALL
Java_com_blink_buddy_CactusNative_setThreads(JNIEnv*, jclass, jint n) { cactus_set_threads((int) n); }

extern "C" JNIEXPORT jint JNICALL
Java_com_blink_buddy_CactusNative_init(JNIEnv* env, jclass, jstring systemPrompt, jstring toolsJson) {
    std::string sp = utf(env, systemPrompt), tools = utf(env, toolsJson);
    return cactus_init(sp.c_str(), tools.c_str(), nullptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_blink_buddy_CactusNative_complete(JNIEnv* env, jclass, jstring input, jint maxNewTokens) {
    std::string in = utf(env, input);
    std::fill(g_out.begin(), g_out.end(), 0);
    int rc = cactus_complete(in.c_str(), nullptr, 0, (int) maxNewTokens, g_out.data(), (int) g_out.size() - 1);
    if (rc < 0) return nullptr;
    return ascii(env, g_out.data());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_blink_buddy_CactusNative_transcribe(JNIEnv* env, jclass, jfloatArray pcm, jstring language) {
    if (!pcm) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples((size_t) n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    std::string lang = utf(env, language);
    std::fill(g_out.begin(), g_out.end(), 0);
    int rc = cactus_transcribe(samples.data(), (int) n, lang.empty() ? nullptr : lang.c_str(), nullptr, 0, g_out.data(), (int) g_out.size() - 1);
    if (rc < 0) return nullptr;
    return ascii(env, g_out.data());
}

extern "C" JNIEXPORT void JNICALL
Java_com_blink_buddy_CactusNative_reset(JNIEnv*, jclass) { cactus_reset(); }
