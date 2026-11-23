#!/usr/bin/env python3
"""Sweety's "Hey Sweety" wake-word detector (sherpa-onnx keyword spotter).

  kws.py prepare <model_dir> <assets_kws_dir>     copy the model into the app and write keywords.txt
  kws.py test <assets_kws_dir> <list.tsv> [long_negative.wav]
                                                  detection rate on recordings ("wake"/"nowake" lines) and false
                                                  alarms on a long stretch of ordinary speech

The thresholds live here and in the generated keywords.txt, so the app and the tests always agree.
"""
import os, shutil, sys, wave

import numpy as np

PHRASES = ["HEY SWEETY", "HI SWEETY", "HELLO SWEETY", "OKAY SWEETY", "OK SWEETY", "HEY SWEETIE"]
BOOST = 1.5      # how strongly the decoder favours the phrase
THRESHOLD = 0.20  # how sure it must be (lower = wakes more easily)


def prepare(model_dir, out):
    import sentencepiece as spm
    os.makedirs(out, exist_ok=True)
    pick = {"encoder": "encoder-", "decoder": "decoder-", "joiner": "joiner-"}
    files = os.listdir(model_dir)
    for name, prefix in pick.items():
        cands = sorted(f for f in files if f.startswith(prefix) and f.endswith(".onnx"))
        int8 = [f for f in cands if ".int8." in f] if os.environ.get("KWS_INT8") == "1" else []  # full precision by default: the int8 encoder of the mobile build broke in onnxruntime
        src = (int8 or [f for f in cands if ".int8." not in f] or cands)[0]
        shutil.copy(os.path.join(model_dir, src), os.path.join(out, name + ".onnx"))
        print(f"{name}.onnx <- {src}")
    shutil.copy(os.path.join(model_dir, "tokens.txt"), os.path.join(out, "tokens.txt"))
    sp = spm.SentencePieceProcessor(model_file=os.path.join(model_dir, "bpe.model"))
    vocab = {l.split()[0] for l in open(os.path.join(model_dir, "tokens.txt"), encoding="utf-8") if l.strip()}
    lines = []
    for p in PHRASES:
        toks = sp.encode(p, out_type=str)
        missing = [t for t in toks if t not in vocab]
        if missing:
            print(f"skip {p}: tokens {missing} not in the model's vocabulary")
            continue
        lines.append(" ".join(toks) + f" :{BOOST} #{THRESHOLD} @{p.replace(' ', '_')}")
    with open(os.path.join(out, "keywords.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(open(os.path.join(out, "keywords.txt"), encoding="utf-8").read())


def load_wav(path):
    with wave.open(path) as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2, path
        rate = w.getframerate()
        a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    if rate != 16000:
        a = np.interp(np.arange(0, len(a), rate / 16000), np.arange(len(a)), a).astype(np.float32)
    return a


def spotter(d):
    import sherpa_onnx
    return sherpa_onnx.KeywordSpotter(
        tokens=f"{d}/tokens.txt", encoder=f"{d}/encoder.onnx", decoder=f"{d}/decoder.onnx", joiner=f"{d}/joiner.onnx",
        keywords_file=f"{d}/keywords.txt", num_threads=1, max_active_paths=4, num_trailing_blanks=1, provider="cpu")


def detect(kws, audio):
    """Feeds audio in 100 ms chunks like the phone does; returns the keywords heard."""
    s = kws.create_stream()
    found = []
    audio = np.concatenate([audio, np.zeros(16000, dtype=np.float32)])
    for i in range(0, len(audio), 1600):
        s.accept_waveform(16000, audio[i:i + 1600])
        while kws.is_ready(s):
            kws.decode_stream(s)
            r = kws.get_result(s)
            if r:
                found.append(r)
                kws.reset_stream(s)
    return found


def test(d, listfile, long_negative=None):
    kws = spotter(d)
    wakes = ok = false = 0
    for line in open(listfile, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 2:
            continue
        expect, path = parts[0], parts[1]
        try:
            got = detect(kws, load_wav(path))
        except Exception as e:  # report and keep going, so one bad clip doesn't hide the rest
            print(f"ERROR {path}: {e}")
            got = []
        robotic = "espeak" in path
        want = expect.startswith("wake")
        good = bool(got) == want
        if want and not robotic:
            wakes += 1
            ok += good
        if not want and got:
            false += 1
        mark = "ok    " if good else ("miss  " if want and robotic else "MISS  " if want else "FALSE ")
        print(f"{mark}{path}  -> {got or '(nothing)'}")
    rate = ok / wakes if wakes else 0
    print(f"wake word: {ok} of {wakes} natural-voice 'Hey Sweety' clips detected ({rate:.0%}, need 90%); "
          f"false wakes on ordinary sentences: {false} (need 0)")
    fails = int(rate < 0.9) + int(false > 0)
    if long_negative and os.path.exists(long_negative):
        a = load_wav(long_negative)
        got = detect(kws, a)
        mins = len(a) / 16000 / 60
        print(f"long ordinary speech: {mins:.1f} min, false wakes: {len(got)} {got}")
        fails += int(len(got) > 0)
    print("ALL PASSED" if fails == 0 else f"{fails} FAILED")
    return fails


if __name__ == "__main__":
    if sys.argv[1] == "prepare":
        prepare(sys.argv[2], sys.argv[3])
    else:
        sys.exit(1 if test(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None) else 0)
