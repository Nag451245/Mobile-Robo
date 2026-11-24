#!/usr/bin/env python3
"""Tests Sweety's own "Hey Sweety" model (wakeword/train.py) the way the phone runs it (WakeModel.java).

  wake.py test <wake_dir> <list.tsv> [long_negative.wav]
      wake_dir holds hey_sweety.onnx, metrics.json, melspectrogram.onnx, embedding_model.onnx.
      Same report as kws.py test: clearly spoken clips must wake her (NEED), phone-mic and noisy ones are reported,
      ordinary sentences and the long chatter must never wake her. Writes detected.txt next to list.tsv.
"""
import json, os, sys, wave

import numpy as np
import onnxruntime as ort

NEED = 0.70  # share of clearly spoken CI clips that must wake her (the model scores 83% on its own held-out voices)


def load_wav(path):
    with wave.open(path) as w:
        rate = w.getframerate()
        a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    if rate != 16000:
        a = np.interp(np.arange(0, len(a), rate / 16000), np.arange(len(a)), a).astype(np.float32)
    return a


class Wake:
    def __init__(self, d):
        o = ort.SessionOptions(); o.intra_op_num_threads = 2
        self.mel = ort.InferenceSession(f"{d}/melspectrogram.onnx", o, providers=["CPUExecutionProvider"])
        self.emb = ort.InferenceSession(f"{d}/embedding_model.onnx", o, providers=["CPUExecutionProvider"])
        self.name = os.environ.get("WAKE_NAME", "hey_sweety")  # which wake word's model: hey_sweety or hey_buddy
        self.head = ort.InferenceSession(f"{d}/{self.name}.onnx", o, providers=["CPUExecutionProvider"])
        self.threshold = json.load(open(f"{d}/metrics.json"))["threshold"]

    def scores(self, audio, batch=512):
        """A score every 80 ms, as WakeModel.java: chunk + 30 ms before -> 8 mel frames (x/10+2) -> embedding of
        the latest 76 frames -> model on the latest 16 embeddings. Starts like the phone after reset()."""
        pcm = np.concatenate([np.zeros(480, np.float32), np.clip(audio, -1, 1) * 32767])
        n = (len(pcm) - 480) // 1280
        if n < 16:
            return np.zeros(0)
        x = np.stack([pcm[k * 1280:k * 1280 + 1760] for k in range(n)]).astype(np.float32)
        m = np.concatenate([self.mel.run(None, {"input": x[b:b + batch]})[0].reshape(-1, 32) for b in range(0, n, batch)]) / 10 + 2
        m = np.concatenate([np.ones((76, 32), np.float32), m.astype(np.float32)])
        w = np.stack([m[8 * (k + 1):8 * (k + 1) + 76] for k in range(n)])[:, :, :, None].astype(np.float32)
        e = np.concatenate([self.emb.run(None, {"input_1": w[b:b + batch]})[0].reshape(-1, 96) for b in range(0, n, batch)])
        h = np.stack([e[k - 15:k + 1] for k in range(15, n)]).astype(np.float32)
        return np.concatenate([self.head.run(None, {"input": h[b:b + batch]})[0].reshape(-1) for b in range(0, len(h), batch)])

    def wakes(self, audio):
        """Times (s) she wakes up, with the phone's 2 s rest after each."""
        s, out, i = self.scores(audio), [], 0
        while i < len(s):
            if s[i] >= self.threshold:
                out.append(round((i + 16) * 0.08, 2)); i += 25
            else:
                i += 1
        return out


def test(d, listfile, long_negative=None):
    wk = Wake(d)
    print(f"Sweety's own {wk.name.replace('_', ' ').title()} model, threshold {wk.threshold}")
    wakes = ok = false = hwakes = hok = 0
    caught = []
    for line in open(listfile, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 2:
            continue
        expect, path = parts[0], parts[1]
        a = load_wav(path)
        got = wk.wakes(np.concatenate([np.zeros(16000, np.float32), a, np.zeros(16000, np.float32)]))  # 1 s of quiet each side
        robotic = "espeak" in path
        hard = "phone" in path or "noisy" in path
        want = expect.startswith("wake")
        good = bool(got) == want
        if want and not robotic:
            if hard:
                hwakes += 1; hok += good
            else:
                wakes += 1; ok += good
        if not want and got:
            false += 1
        if want and got:
            caught.append(os.path.basename(path))
        mark = "ok    " if good else ("miss  " if want and (robotic or hard) else "MISS  " if want else "FALSE ")
        print(f"{mark}{path}  -> {got or '(nothing)'}")
    with open(os.path.join(os.path.dirname(listfile), "detected.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(caught) + "\n")
    rate = ok / wakes if wakes else 0
    print(f"through a phone mic or in a noisy room: {hok} of {hwakes} ({hok / max(hwakes, 1):.0%})")
    print(f"wake word: {ok} of {wakes} clearly spoken '{wk.name.replace('_', ' ').title()}' clips detected ({rate:.0%}, need {NEED:.0%}); "
          f"false wakes on ordinary sentences: {false} (need 0)")
    fails = int(rate < NEED) + int(false > 0)
    if long_negative and os.path.exists(long_negative):
        a = load_wav(long_negative)
        got = wk.wakes(a)
        print(f"long ordinary speech: {len(a) / 16000 / 60:.1f} min, false wakes: {len(got)} {got}")
        fails += int(len(got) > 0)
    print("ALL PASSED" if fails == 0 else f"{fails} FAILED")
    return fails


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "test":
        sys.exit(1 if test(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else None) else 0)
    print(__doc__); sys.exit(2)
