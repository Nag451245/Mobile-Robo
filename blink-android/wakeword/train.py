#!/usr/bin/env python3
"""Trains Sweety's own "Hey Sweety" wake-word model (openWakeWord style) on synthetic and public speech.

How it works
  openWakeWord's approach: audio becomes speech features with two shared models (a mel spectrogram and Google's
  speech_embedding, Apache-2.0): one 96-number embedding every 80 ms. A tiny network looks at the last 16 of them
  (1.28 s) and says how sure it is that "Hey Sweety" just ended. Only that tiny network is trained here.

Data (all permissively licensed)
  - "Hey / Hi / Hello / Okay Sweety" from piper-sample-generator (MIT) with its LibriTTS-R generator (CC BY 4.0):
    hundreds of different made-up voices, speeds and speaker blends. Some are followed straight away by a request
    ("...what time is it"), so she wakes even when you say it all in one breath.
  - Near misses that must NOT wake her: "sweaty", "sweater", "sweet tea", "hey Siri", "hey Swati", ...
  - Real everyday speech from LibriSpeech (CC BY 4.0): dev-clean to learn from, test-clean to count false wake-ups.
  - Room echo, phone-mic tone, quiet/far speech, and noise (hiss, rumble, babble), all made here in code.

Output (in --out): hey_sweety.onnx (input [1,16,96] -> score 0..1), metrics.json, report.txt
"""
import argparse, glob, json, os, random, subprocess, sys, tarfile, time, urllib.request, wave

import numpy as np
import scipy.signal as sps

SR = 16000
WIN = 2 * SR  # training windows: 2 s, which give exactly 16 embeddings
POSITIVE = ["hey sweety", "hey sweety", "hey sweety", "hi sweety", "hello sweety", "okay sweety", "ok sweety", "hey sweetie"]
REQUESTS = ["what time is it", "set an alarm for seven", "dance for me", "turn on the torch", "call mom",
            "tell me a joke", "play some music", "remind me to drink water", "what's the weather like"]
NEAR_MISSES = ["sweaty", "it was hot and sweaty", "those sweaty socks", "sweater", "a new sweater", "sweet tea",
               "sweet", "sweetest", "hey sweet", "hey city", "hey Swati", "my sister Swati", "hey Siri", "hey Steve",
               "hey seedy", "pretty", "twenty", "hey there", "okay Google", "hey sweetheart", "hello sweetheart",
               "sweet pea", "Swiss tea", "the suite", "hey sweater", "sweeties", "hey Sweden", "see you sweety pie",
               "hey Suzy", "hey Sid", "OK computer", "hello city", "hi Steve", "hey Sweeney"]
LIBRISPEECH = "https://www.openslr.org/resources/12/{}.tar.gz"
GENERATOR = "https://github.com/rhasspy/piper-sample-generator/releases/download/v2.0.0/en_US-libritts_r-medium.pt"
FEATURES = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/{}.onnx"


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def fetch(url, path):
    if not os.path.exists(path):
        log("download", url)
        urllib.request.urlretrieve(url, path + ".part")
        os.replace(path + ".part", path)
    return path


def read_wav(path):
    with wave.open(path) as w:
        rate, n, ch = w.getframerate(), w.getnframes(), w.getnchannels()
        a = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768
    if ch > 1:
        a = a.reshape(-1, ch).mean(1)
    if rate != SR:
        g = np.gcd(rate, SR)
        a = sps.resample_poly(a, SR // g, rate // g).astype(np.float32)
    return a


def trim(a, thr=0.01):
    """Cuts silence at both ends, so a phrase's end can be placed exactly."""
    idx = np.where(np.abs(a) > thr * max(1e-4, np.abs(a).max()))[0]
    return a[idx[0]:idx[-1] + 1] if len(idx) else a


def generate(model, phrases, per_phrase, out, seed):
    """Synthetic speech: many made-up voices from the LibriTTS-R generator, varied speed and blend."""
    clips = []
    for k, text in enumerate(phrases):
        d = os.path.join(out, f"{seed}-{k}")
        if not glob.glob(os.path.join(d, "*.wav")):
            os.makedirs(d, exist_ok=True)
            subprocess.run([sys.executable, "-m", "piper_sample_generator", text, "--model", model,
                            "--max-samples", str(per_phrase), "--batch-size", "16", "--max-speakers", "800",
                            "--length-scales", "0.85", "1.0", "1.15", "1.3", "--slerp-weights", "0.3", "0.5", "0.7",
                            "--output-dir", d], check=True, stdout=subprocess.DEVNULL)
        for f in sorted(glob.glob(os.path.join(d, "*.wav"))):
            a = trim(read_wav(f))
            if 0.25 * SR < len(a) < 3.5 * SR:
                clips.append((text, a))
    return clips


def librispeech(root, name):
    """All of a LibriSpeech part as one long 16 kHz array (flac files)."""
    import soundfile as sf
    tgz = fetch(LIBRISPEECH.format(name), os.path.join(root, name + ".tar.gz"))
    d = os.path.join(root, "LibriSpeech", name)
    if not os.path.isdir(d):
        log("unpack", name)
        with tarfile.open(tgz) as t:
            t.extractall(root, filter="data") if hasattr(tarfile, "data_filter") else t.extractall(root)
    parts = []
    for f in sorted(glob.glob(os.path.join(d, "**", "*.flac"), recursive=True)):
        a, sr = sf.read(f, dtype="float32")
        parts.append(a)
    return np.concatenate(parts)


# ------------------------------------------------------------------ augmentation (made here, no datasets)
def rir(rng):
    """A made-up room: early reflections plus an exponentially decaying tail (RT60 0.15-0.8 s)."""
    rt60 = rng.uniform(0.15, 0.8)
    n = int(rt60 * SR)
    t = np.arange(n) / SR
    h = rng.normal(0, 1, n) * np.exp(-6.9 * t / rt60)
    h[0] = 1.0 / rng.uniform(0.2, 0.6)  # direct sound
    for _ in range(rng.integers(3, 8)):
        h[rng.integers(int(0.002 * SR), int(0.03 * SR))] += rng.uniform(0.2, 0.8)
    return (h / np.abs(h).sum() * 4).astype(np.float32)


def colored(rng, n):
    kind = rng.integers(3)
    w = rng.normal(0, 1, n).astype(np.float32)
    if kind == 1:  # pink-ish
        w = sps.lfilter([0.049922, -0.095993, 0.050613, -0.004408], [1, -2.494956, 2.017265, -0.522189], w)
    elif kind == 2:  # rumble (low-passed)
        w = sps.lfilter([1], [1, -0.98], w)
    return (w / (np.std(w) + 1e-6)).astype(np.float32)


def augment(x, rng, babble):
    y = x.copy()
    if rng.random() < 0.5:  # room echo
        y = sps.fftconvolve(y, rir(rng))[:len(y)].astype(np.float32)
    if rng.random() < 0.3:  # phone microphone tone
        b, a = sps.butter(2, [rng.uniform(150, 350), rng.uniform(3200, 3900)], "bandpass", fs=SR)
        y = sps.lfilter(b, a, y).astype(np.float32)
    speech_rms = np.sqrt(np.mean(y ** 2)) + 1e-6
    if rng.random() < 0.75:  # background: hiss/rumble or other people talking
        if rng.random() < 0.5 and babble is not None:
            s = rng.integers(0, len(babble) - len(y))
            n = babble[s:s + len(y)] / (np.std(babble[s:s + len(y)]) + 1e-6)
        else:
            n = colored(rng, len(y))
        snr = rng.uniform(0, 25)
        y = y + n * speech_rms / (10 ** (snr / 20))
    y = y * (10 ** (rng.uniform(-28, -3) / 20)) / (np.abs(y).max() + 1e-6)  # loud to quiet / far away
    return np.clip(y, -1, 1).astype(np.float32)


def place(clip, rng, end_jitter, before=None, after=None):
    """A 2 s window whose phrase ends end_jitter samples before the window's end (after = what follows it)."""
    w = np.zeros(WIN, dtype=np.float32)
    seg = clip
    if after is not None:
        seg = np.concatenate([clip, np.zeros(int(rng.uniform(0, 0.15) * SR), np.float32), after])
    end = WIN - end_jitter  # where the phrase ends
    start = end - len(clip)
    if start < 0:
        seg, start = seg[-start:], 0
    piece = seg[:WIN - start]
    w[start:start + len(piece)] = piece
    if before is not None and start > 0.3 * SR and rng.random() < 0.5:  # someone was talking just before
        gap = int(rng.uniform(0.1, 0.5) * SR)
        b = before[:max(0, start - gap)]
        w[start - gap - len(b):start - gap] += b
    return w


def to_int16(x):
    return (np.clip(x, -1, 1) * 32767).astype(np.int16)


# ------------------------------------------------------------------ model
def build_model(torch):
    nn = torch.nn
    return nn.Sequential(
        nn.Flatten(),
        nn.Linear(16 * 96, 64), nn.LayerNorm(64), nn.ReLU(),
        nn.Linear(64, 64), nn.LayerNorm(64), nn.ReLU(),
        nn.Linear(64, 1), nn.Sigmoid())


def window_feats(mel, emb, windows):
    """The 16 embeddings at the end of each 2 s window, computed exactly as the phone streams them: 480 samples of
    silence before the window, then 25 chunks of 80 ms (each with the 30 ms before it); the last 16 embeddings."""
    w = np.asarray(windows, np.float32)  # (B, 32000), int16-valued
    B = len(w)
    p = np.concatenate([np.zeros((B, 480), np.float32), w], 1)
    chunks = np.stack([p[:, k * 1280:k * 1280 + 1760] for k in range(25)], 1).reshape(B * 25, 1760)
    m = mel.run(None, {"input": chunks})[0].reshape(B, 25 * 8, 32) / 10 + 2
    m = np.concatenate([np.ones((B, 76, 32), np.float32), m.astype(np.float32)], 1)  # mel buffer starts with ones
    x = np.stack([m[:, 8 * (k + 1):8 * (k + 1) + 76] for k in range(9, 25)], 1).reshape(B * 16, 76, 32, 1)
    return emb.run(None, {"input_1": x.astype(np.float32)})[0].reshape(B, 16, 96).astype(np.float32)


def stream_scores(mel, emb, head, audio, batch=512):
    """Scores every 80 ms over long audio, the way the phone does it (WakeModel.java), batched for speed:
    each 80 ms chunk's mel frames come from that chunk plus the 30 ms before it (1760 samples -> 8 frames, scaled
    x/10 + 2); an embedding is made from the latest 76 mel frames; the model scores the latest 16 embeddings."""
    pcm = to_int16(audio).astype(np.float32)
    n = (len(pcm) - 480) // 1280
    frames = [np.ones((76, 32), np.float32)]  # openWakeWord starts its mel buffer with ones
    for b in range(0, n, batch):
        k = np.arange(b, min(n, b + batch))
        x = np.stack([pcm[i * 1280:i * 1280 + 1760] for i in k])
        frames.append(mel.run(None, {"input": x})[0].reshape(len(k) * 8, 32) / 10 + 2)
    m = np.concatenate(frames)
    embs = []
    for b in range(0, n, batch):
        k = np.arange(b, min(n, b + batch))
        x = np.stack([m[8 * (i + 1):8 * (i + 1) + 76] for i in k])[:, :, :, None].astype(np.float32)
        embs.append(emb.run(None, {"input_1": x})[0].reshape(len(k), 96))
    e = np.concatenate(embs)
    scores = np.zeros(n, np.float32)
    for b in range(15, n, batch):
        k = np.arange(b, min(n, b + batch))
        x = np.stack([e[i - 15:i + 1] for i in k]).astype(np.float32)
        scores[k] = head.run(None, {"input": x})[0].reshape(-1)
    return scores


def count_wakes(scores, thr, refractory=25):
    """Wake-ups: score crossing thr, then 2 s (25 frames) of rest, as the app does."""
    n, i = 0, 0
    while i < len(scores):
        if scores[i] >= thr:
            n += 1; i += refractory
        else:
            i += 1
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="work")
    ap.add_argument("--out", default="out")
    ap.add_argument("--positives", type=int, default=700, help="samples per wake phrase")
    ap.add_argument("--near", type=int, default=150, help="samples per near-miss phrase")
    ap.add_argument("--requests", type=int, default=120, help="samples per request phrase")
    ap.add_argument("--steps", type=int, default=12000)
    args = ap.parse_args()
    os.makedirs(args.work, exist_ok=True); os.makedirs(args.out, exist_ok=True)
    rng = np.random.default_rng(1)
    random.seed(1)

    import onnxruntime as ort
    import torch
    torch.manual_seed(1)
    mel = fetch(FEATURES.format("melspectrogram"), os.path.join(args.work, "melspectrogram.onnx"))
    emb = fetch(FEATURES.format("embedding_model"), os.path.join(args.work, "embedding_model.onnx"))
    mel_s = ort.InferenceSession(mel, providers=["CPUExecutionProvider"])
    emb_s = ort.InferenceSession(emb, providers=["CPUExecutionProvider"])
    gen = fetch(GENERATOR, os.path.join(args.work, "generator.pt"))

    log("synthetic speech")
    pos = generate(gen, POSITIVE, args.positives, os.path.join(args.work, "pos"), "p")
    near = generate(gen, NEAR_MISSES, args.near, os.path.join(args.work, "near"), "n")
    reqs = [a for _, a in generate(gen, REQUESTS, args.requests, os.path.join(args.work, "req"), "r")]
    log(f"{len(pos)} wake clips, {len(near)} near misses, {len(reqs)} requests")
    dev = librispeech(args.work, "dev-clean")
    test = librispeech(args.work, "test-clean")
    log(f"LibriSpeech: {len(dev) / SR / 3600:.1f} h to learn from, {len(test) / SR / 3600:.1f} h to test false wakes")

    # Hold out a fifth of the synthetic clips (different samples) to measure recall honestly.
    random.shuffle(pos); random.shuffle(near)
    cut_p, cut_n = len(pos) // 5, len(near) // 5
    pos_val, pos_tr, near_val, near_tr = pos[:cut_p], pos[cut_p:], near[:cut_n], near[cut_n:]

    # Windows are made and turned into features a chunk at a time (all at once would need several GB).
    def snippet(n):
        s = rng.integers(0, len(dev) - n)
        return dev[s:s + n]

    def windows_pos(clips, copies):
        for _ in range(copies):
            for _, a in clips:
                after = reqs[rng.integers(len(reqs))] if rng.random() < 0.4 else None  # said in one breath
                yield augment(place(a, rng, int(rng.uniform(0.0, 0.25) * SR), before=snippet(SR), after=after), rng, dev)

    def windows_neg(clips, copies):
        for _ in range(copies):
            for _, a in clips:
                yield augment(place(a, rng, int(rng.uniform(0.0, 0.6) * SR), before=snippet(SR)), rng, dev)

    def windows_speech(n):
        for _ in range(n):
            w = snippet(WIN)
            yield augment(w, rng, dev) if rng.random() < 0.6 else w * rng.uniform(0.05, 1)

    def windows_noise(n):
        for _ in range(n):
            yield augment(colored(rng, WIN) * 0.01, rng, dev)

    def embed(ws, chunk=64):
        out, buf = [], []
        for w in ws:
            buf.append(to_int16(w))
            if len(buf) == chunk:
                out.append(window_feats(mel_s, emb_s, buf)); buf = []
        if buf:
            out.append(window_feats(mel_s, emb_s, buf))
        return np.concatenate(out)

    log("features")
    Xp = embed(windows_pos(pos_tr, 3))
    Xn = np.concatenate([embed(windows_neg(near_tr, 3)), embed(windows_speech(len(Xp) * 2)), embed(windows_noise(len(Xp) // 3))])
    Vp = embed(windows_pos(pos_val, 1))
    Vn = embed(windows_neg(near_val, 2))
    log(f"train: {len(Xp)} wake windows, {len(Xn)} others; check: {len(Vp)} wake, {len(Vn)} near misses")

    log("training")
    model = build_model(torch)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=1e-3, total_steps=args.steps)
    Xp_t, Xn_t = torch.from_numpy(Xp), torch.from_numpy(Xn)
    bce = torch.nn.BCELoss(reduction="none")
    for step in range(args.steps):
        ip = torch.randint(0, len(Xp_t), (128,)); ineg = torch.randint(0, len(Xn_t), (384,))
        x = torch.cat([Xp_t[ip], Xn_t[ineg]]); y = torch.cat([torch.ones(128), torch.zeros(384)])
        p = model(x).squeeze(1)
        # false wake-ups cost more than misses, and more as training goes on (openWakeWord's recipe)
        wneg = 1 + 9 * step / args.steps
        loss = (bce(p, y) * torch.where(y > 0, 1.0, wneg)).mean()
        opt.zero_grad(); loss.backward(); opt.step(); sched.step()
        if step % 2000 == 0 or step == args.steps - 1:
            with torch.no_grad():
                rp = (model(torch.from_numpy(Vp)).squeeze(1) > 0.5).float().mean().item()
                fn = (model(torch.from_numpy(Vn)).squeeze(1) > 0.5).float().mean().item()
            log(f"step {step}: loss {loss.item():.4f}, held-out wake {rp:.1%}, near misses woken {fn:.1%}")

    model.eval()
    onnx_path = os.path.join(args.out, "hey_sweety.onnx")
    torch.onnx.export(model, torch.zeros(1, 16, 96), onnx_path, input_names=["input"], output_names=["output"],
                      dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}}, opset_version=13)
    head = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])

    log("false wake-ups on unseen everyday speech (streaming, like the phone)")
    sc_test = stream_scores(mel_s, emb_s, head, test)
    hours = len(test) / SR / 3600
    with torch.no_grad():
        vp = model(torch.from_numpy(Vp)).squeeze(1).numpy(); vn = model(torch.from_numpy(Vn)).squeeze(1).numpy()
    rows = []
    for thr in (0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9):
        rows.append({"threshold": thr, "wake_held_out": float((vp >= thr).mean()), "near_miss_woken": float((vn >= thr).mean()),
                     "false_wakes_per_hour": count_wakes(sc_test, thr) / hours})
    # The threshold: the most sensitive one with at most 0.5 false wake-ups an hour of everyday speech.
    ok = [r for r in rows if r["false_wakes_per_hour"] <= 0.5] or rows[-1:]
    best = ok[0]
    metrics = {"threshold": best["threshold"], "rows": rows, "train_wake": len(Xp), "train_other": len(Xn),
               "test_speech_hours": hours, "generator_voices": 800}
    json.dump(metrics, open(os.path.join(args.out, "metrics.json"), "w"), indent=1)
    lines = [f"threshold {r['threshold']:.1f}: held-out 'Hey Sweety' woken {r['wake_held_out']:.1%}, near misses woken "
             f"{r['near_miss_woken']:.1%}, false wake-ups {r['false_wakes_per_hour']:.2f}/hour of everyday speech" for r in rows]
    lines.append(f"CHOSEN threshold {best['threshold']}")
    open(os.path.join(args.out, "report.txt"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines), flush=True)


if __name__ == "__main__":
    main()
