"""Drops cloned clips that don't say their sentence (the cloner sometimes garbles or skips words).

Whisper transcribes each clip in work/synth; clips whose word error rate is above the limit, or
that are oddly long or short for their text, are left out of work/synth/metadata.csv (the
original is kept as metadata.all.csv).

    .venv-clone/Scripts/python check_clips.py
"""
import csv
import re
import shutil
from pathlib import Path

import soundfile as sf
import torch
from transformers import pipeline

SYNTH = Path(__file__).parent / "work" / "synth"
MAX_WER = 0.25
NUMBERS = {w: str(i) for i, w in enumerate(
    "zero one two three four five six seven eight nine ten eleven twelve".split())}


def words(text: str) -> list[str]:
    text = re.sub(r"[^a-z0-9' ]+", " ", text.lower().replace("-", " "))
    return [NUMBERS.get(w, w) for w in text.split()]


def wer(ref: list[str], hyp: list[str]) -> float:
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
    return d[-1] / max(1, len(ref))


def main() -> None:
    all_rows = SYNTH / "metadata.all.csv"
    if not all_rows.exists():
        shutil.copy(SYNTH / "metadata.csv", all_rows)
    rows = list(csv.reader(all_rows.open(encoding="utf-8"), delimiter="|"))

    asr = pipeline("automatic-speech-recognition", model="openai/whisper-large-v3-turbo",
                   dtype=torch.float16, device="cuda")
    kept, dropped = [], []
    for i, (name, text) in enumerate(rows):
        audio, rate = sf.read(SYNTH / name, dtype="float32")
        seconds = len(audio) / rate
        heard = asr({"raw": audio, "sampling_rate": rate}, generate_kwargs={"language": "english"})["text"]
        if re.search(r"\d", heard):
            # Whisper writes numbers as digits ("7.30am" for "seven thirty AM"): word matching
            # would fail, so only catch clips that lost most of their text.
            score = 0.0 if len(heard) > 0.5 * len(text) else 1.0
        else:
            score = wer(words(text), words(heard))
        per_char = seconds / max(1, len(text))
        ok = score <= MAX_WER and 0.035 < per_char < 0.16
        (kept if ok else dropped).append((name, text, heard, score, seconds))
        if (i + 1) % 100 == 0:
            print(f"{i + 1}/{len(rows)} checked, {len(dropped)} dropped", flush=True)

    with (SYNTH / "metadata.csv").open("w", encoding="utf-8", newline="") as f:
        for name, text, *_ in kept:
            f.write(f"{name}|{text}\n")
    with (SYNTH / "dropped.tsv").open("w", encoding="utf-8") as f:
        for name, text, heard, score, seconds in dropped:
            f.write(f"{name}\t{score:.2f}\t{seconds:.1f}s\t{text}\t{heard}\n")
    print(f"kept {len(kept)}, dropped {len(dropped)} (see synth/dropped.tsv)")


if __name__ == "__main__":
    main()
