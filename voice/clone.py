"""Reads sentences aloud in the reference voice with F5-TTS (zero-shot cloning from short lines).

Each sentence uses one of several short reference lines in turn, so the training set picks up
the voice's range rather than one take. Skips sentences already done, so it can be stopped and
resumed. Writes work/synth/NNNNN.wav (22.05 kHz mono) and work/synth/metadata.csv for Piper.

    .venv-clone/Scripts/python clone.py               # the whole corpus
    .venv-clone/Scripts/python clone.py --test 6      # a few samples into work/test/
"""
import argparse
import csv
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
import torchaudio


def _load(path, *_, **__):
    """torchaudio.load via soundfile: torchaudio's own loader needs FFmpeg's shared DLLs."""
    data, rate = sf.read(str(path), dtype="float32", always_2d=True)
    return torch.from_numpy(data.T.copy()), rate


torchaudio.load = _load
from f5_tts.api import F5TTS  # noqa: E402  (after the patch: it imports torchaudio.load)

HERE = Path(__file__).parent
WORK = HERE / "work"
SEGS = WORK / "segs"

# Reference lines under ~12 s with clear, typical delivery (F5 clips longer references).
REFERENCES = ["seg12.wav", "seg16.wav", "seg06.wav", "seg02.wav", "seg19.wav", "seg17.wav", "seg04.wav", "seg08.wav"]
OUT_RATE = 22050


def load_transcripts() -> dict[str, str]:
    rows = (SEGS / "transcripts.tsv").read_text(encoding="utf-8").splitlines()
    return dict(row.split("\t", 1) for row in rows if row.strip())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", type=int, default=0, help="only synthesise this many test sentences")
    parser.add_argument("--nfe", type=int, default=32, help="flow steps: more is cleaner but slower")
    args = parser.parse_args()

    transcripts = load_transcripts()
    refs = [(str(SEGS / name), transcripts[name]) for name in REFERENCES]
    sentences = [s.strip() for s in (WORK / "corpus.txt").read_text(encoding="utf-8").splitlines() if s.strip()]

    out = WORK / ("test" if args.test else "synth")
    out.mkdir(exist_ok=True)
    if args.test:
        sentences = sentences[: args.test]

    tts = F5TTS(device="cuda")
    resample = torchaudio.transforms.Resample(tts.target_sample_rate, OUT_RATE)

    meta_path = out / "metadata.csv"
    done = set()
    if meta_path.exists():
        done = {row[0] for row in csv.reader(meta_path.open(encoding="utf-8"), delimiter="|")}
    with meta_path.open("a", encoding="utf-8", newline="") as meta:
        for i, text in enumerate(sentences):
            name = f"{i:05d}.wav"
            if name in done:
                continue
            ref_file, ref_text = refs[i % len(refs)]
            wav, rate, _ = tts.infer(
                ref_file=ref_file,
                ref_text=ref_text,
                gen_text=text,
                nfe_step=args.nfe,
                remove_silence=False,
                seed=i,
                show_info=lambda *_: None,
            )
            audio = resample(torch.from_numpy(np.asarray(wav, dtype=np.float32))[None])[0].numpy()
            peak = float(np.abs(audio).max()) or 1.0
            sf.write(out / name, audio * min(1.0, 0.95 / peak), OUT_RATE, subtype="PCM_16")
            meta.write(f"{name}|{text}\n")
            meta.flush()
            print(f"{i + 1}/{len(sentences)} {len(audio) / OUT_RATE:.1f}s {text[:60]}", flush=True)


if __name__ == "__main__":
    main()
