#!/usr/bin/env python3
"""Checks Sweety's natural voice (Kokoro on sherpa-onnx): every voice the app offers speaks a sentence,
at a sensible length, faster than real time.   voice.py <kokoro_dir> <out_dir>
The kokoro dir needs lexicon-en-us.txt and sweety-words.txt (written by HostTest lexicon), as in the app."""
import os, sys, time, wave

import numpy as np
import sherpa_onnx

d, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
model = f"{d}/model.int8.onnx" if os.path.exists(f"{d}/model.int8.onnx") else f"{d}/model.onnx"

# sherpa-onnx 2 turns words into sounds with a pronunciation dictionary; the app adds Sweety's own small one.
k = sherpa_onnx.OfflineTtsKokoroModelConfig(model=model, voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt",
                                            lexicon=f"{d}/lexicon-en-us.txt,{d}/sweety-words.txt")
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(kokoro=k, num_threads=2, provider="cpu"), max_num_sentences=1))

# Words only, as the app's SpeakText makes them; "Sweety" and "or" come from her own word list.
line = "Hi! I'm Sweety. Good morning, sunshine! Shall I set an alarm for seven, or eight?"
probe = tts.generate("Hello.", sid=1, speed=1.0)
if len(probe.samples) == 0:
    print("FAIL  no audio: the voice can't read words (dictionary missing?)")
    sys.exit(1)
speakers = {1: "Bella", 4: "Sky", 2: "Nicole", 3: "Sarah", 7: "Emma"}  # the same ids as KokoroVoice.SPEAKERS
fails = 0
print(f"{tts.num_speakers} speakers, {tts.sample_rate} Hz")
for sid, name in speakers.items():
    t0 = time.time()
    a = tts.generate(line, sid=sid, speed=1.0)
    took = time.time() - t0
    s = np.array(a.samples, dtype=np.float32)
    secs = len(s) / max(1, a.sample_rate)
    peak = float(np.abs(s).max()) if len(s) else 0
    ok = 3 < secs < 10 and peak > 0.05 and took < secs * 2
    fails += not ok
    print(f"{'ok    ' if ok else 'FAIL  '}{name} (id {sid}): {secs:.1f} s of audio made in {took:.1f} s, peak {peak:.2f}")
    if len(s):
        with wave.open(f"{out}/voice-{name}.wav", "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate)
            w.writeframes((np.clip(s, -1, 1) * 32767).astype(np.int16).tobytes())
print("ALL PASSED" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
