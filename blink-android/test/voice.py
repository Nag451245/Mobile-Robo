#!/usr/bin/env python3
"""Checks Sweety's natural voice (Kokoro on sherpa-onnx): every voice the app offers speaks a sentence,
at a sensible length, faster than real time.   voice.py <kokoro_dir> <out_dir>"""
import os, sys, time, wave

import numpy as np
import sherpa_onnx

d, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
model = f"{d}/model.int8.onnx" if os.path.exists(f"{d}/model.int8.onnx") else f"{d}/model.onnx"
cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=model, voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"),
    num_threads=2, provider="cpu"), max_num_sentences=1)
tts = sherpa_onnx.OfflineTts(cfg)
line = "Hi! I'm Sweety. Good morning, sunshine! Shall I set an alarm for seven?"
speakers = {1: "Bella", 4: "Sky", 2: "Nicole", 3: "Sarah", 7: "Emma"}  # the same ids as KokoroVoice.SPEAKERS
fails = 0
print(f"{tts.num_speakers} speakers, {tts.sample_rate} Hz")
for sid, name in speakers.items():
    t0 = time.time()
    a = tts.generate(line, sid=sid, speed=1.0)
    took = time.time() - t0
    s = np.array(a.samples, dtype=np.float32)
    secs = len(s) / a.sample_rate
    peak = float(np.abs(s).max()) if len(s) else 0
    ok = 2.5 < secs < 9 and peak > 0.05 and took < secs * 2
    fails += not ok
    print(f"{'ok    ' if ok else 'FAIL  '}{name} (id {sid}): {secs:.1f} s of audio made in {took:.1f} s, peak {peak:.2f}")
    with wave.open(f"{out}/voice-{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(a.sample_rate)
        w.writeframes((np.clip(s, -1, 1) * 32767).astype(np.int16).tobytes())
print("ALL PASSED" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
