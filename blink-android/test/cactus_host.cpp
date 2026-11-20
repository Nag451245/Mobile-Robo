// Runs Sweety's two Cactus models on the CI machine (Linux x86_64 build of the same Cactus 4.1.4 runtime):
//   cactus_host hear  whistle.cact needle3.cact list.tsv [keywords] -> "expect<TAB>file<TAB>what Whistle heard"
//   cactus_host think whistle.cact needle3.cact list.tsv   -> "expect<TAB>text<TAB>Needle 3 output"
// The models load in the same order as on the phone (Needle, Whistle, then Needle's tools again).
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include "cactus.h"

static std::vector<unsigned char> slurp(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return std::vector<unsigned char>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

// 16-bit PCM mono WAV at 16 kHz -> floats in [-1, 1]
static std::vector<float> wav(const std::string& path) {
    std::vector<unsigned char> b = slurp(path.c_str());
    std::vector<float> out;
    size_t p = 12;
    while (p + 8 <= b.size()) {
        unsigned n = b[p + 4] | (b[p + 5] << 8) | (b[p + 6] << 16) | ((unsigned) b[p + 7] << 24);
        if (!memcmp(&b[p], "data", 4)) {
            for (size_t i = p + 8; i + 1 < b.size() && i < p + 8 + n; i += 2) out.push_back((short) (b[i] | (b[i + 1] << 8)) / 32768.f);
            break;
        }
        p += 8 + n + (n & 1);
    }
    return out;
}

static std::string clean(const char* s) {
    std::string r;
    for (; *s; ++s) r.push_back(*s == '\t' || *s == '\n' || *s == '\r' ? ' ' : *s);
    return r;
}

int main(int argc, char** argv) {
    if (argc < 5) { fprintf(stderr, "usage: cactus_host hear|think whistle.cact needle3.cact list.tsv\n"); return 2; }
    std::string mode = argv[1];
    const char* keywords = argc > 5 && argv[5][0] ? argv[5] : nullptr; // optional Whistle keyword hint, like the phone uses
    std::vector<unsigned char> needle = slurp(argv[3]), whistle = slurp(argv[2]);
    std::string sys = "You are Sweety, a cute robot assistant on an Android phone. Pick the tool that does what the user asks.";
    std::string toolsJson;
    { std::ifstream t("tools.json"); std::stringstream ss; ss << t.rdbuf(); toolsJson = ss.str(); }
    cactus_set_threads(4);
    if (cactus_load(needle.data(), needle.size()) < 0) { fprintf(stderr, "needle load failed: %s\n", cactus_last_error()); return 1; }
    if (cactus_init(sys.c_str(), toolsJson.c_str(), nullptr) < 0) fprintf(stderr, "needle init failed: %s\n", cactus_last_error());
    if (cactus_load(whistle.data(), whistle.size()) < 0) { fprintf(stderr, "whistle load failed: %s\n", cactus_last_error()); return 1; }
    if (cactus_init(sys.c_str(), toolsJson.c_str(), nullptr) < 0) fprintf(stderr, "needle re-init failed: %s\n", cactus_last_error());
    std::vector<char> out(32768);
    std::ifstream list(argv[4]);
    std::string line;
    while (std::getline(list, line)) {
        size_t tab = line.find('\t');
        if (tab == std::string::npos) continue;
        std::string expect = line.substr(0, tab), item = line.substr(tab + 1);
        std::fill(out.begin(), out.end(), 0);
        int rc;
        if (mode == "hear") {
            std::vector<float> pcm = wav(item);
            rc = cactus_transcribe(pcm.data(), (int) pcm.size(), "en", keywords, 0, out.data(), (int) out.size() - 1);
        } else {
            cactus_reset();
            rc = cactus_complete(item.c_str(), nullptr, 0, 160, out.data(), (int) out.size() - 1);
        }
        printf("%s\t%s\t%s\n", expect.c_str(), item.c_str(), rc < 0 ? (std::string("ERROR ") + clean(cactus_last_error())).c_str() : clean(out.data()).c_str());
        fflush(stdout);
    }
    return 0;
}
