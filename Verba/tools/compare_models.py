"""Compare a converted Qwen3-ASR model with the stock Qwen3-ASR 0.6B on the test clips.

Run by the "Speech models" GitHub workflow after converting a model. It
prints a Markdown table (also appended to the job summary page) and exits
with an error if the new model clearly does not work (empty or mostly wrong
output), so a broken conversion is never published.

usage: python compare_models.py NEW_MODEL_DIR BASE_MODEL_DIR CLIPS_DIR
"""

import json
import os
import re
import sys
import time
import unicodedata

import numpy as np
import sherpa_onnx
import soundfile as sf


def recognizer(d):
    return sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=f"{d}/conv_frontend.onnx",
        encoder=f"{d}/encoder.int8.onnx",
        decoder=f"{d}/decoder.int8.onnx",
        tokenizer=f"{d}/tokenizer",
        num_threads=4,
        max_new_tokens=256,
    )


def run(rec, x, language):
    s = rec.create_stream()
    if language:
        s.set_option("language", language)
    s.accept_waveform(16000, x)
    rec.decode_stream(s)
    return s.result.text.strip()


def norm(t):
    t = unicodedata.normalize("NFC", t).lower().replace("़", "")
    t = re.sub(r"[।॥,.!?;:\"'()\-–—]", " ", t)
    return re.sub(r"\s+", "", t)


def cer(ref, hyp):
    a, b = norm(ref), norm(hyp)
    d = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        p, d[0] = d[0], i
        for j, y in enumerate(b, 1):
            p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (x != y))
    return d[-1] / max(1, len(a))


def main():
    new_dir, base_dir, clips = sys.argv[1:4]
    refs = json.load(open(os.path.join(clips, "refs.json"), encoding="utf-8"))
    new, base = recognizer(new_dir), recognizer(base_dir)
    rows, empty, t_new, t_audio = [], 0, 0.0, 0.0
    sums = {"base": 0.0, "new": 0.0}
    for name in sorted(refs):
        x, sr = sf.read(os.path.join(clips, name), dtype="float32")
        if x.ndim > 1:
            x = x.mean(axis=1)
        assert sr == 16000, name
        b = run(base, x, "")
        t = time.time()
        n = run(new, x, "None")
        t_new += time.time() - t
        t_audio += len(x) / 16000
        empty += not n
        cb, cn = cer(refs[name], b), cer(refs[name], n)
        sums["base"] += cb
        sums["new"] += cn
        rows.append((name, refs[name], b, cb, n, cn))

    k = len(rows)
    out = [
        f"### {os.path.basename(new_dir.rstrip('/'))} vs stock Qwen3-ASR 0.6B",
        "",
        f"Character error rate over {k} clips (lower is better): "
        f"**stock {sums['base'] / k:.1%}** → **new {sums['new'] / k:.1%}**. "
        f"Speed on this machine: {t_new / max(t_audio, 1e-6):.2f}× real time.",
        "",
        "| clip | should be | stock Qwen3-ASR | new model |",
        "|---|---|---|---|",
    ]
    for name, ref, b, cb, n, cn in rows:
        out.append(f"| {name} | {ref} | {b} ({cb:.0%}) | {n} ({cn:.0%}) |")
    text = "\n".join(out) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)

    if empty > k // 3 or sums["new"] / k > 0.6:
        print("::error::The converted model gives empty or mostly wrong text; not publishing it.")
        sys.exit(1)


if __name__ == "__main__":
    main()
