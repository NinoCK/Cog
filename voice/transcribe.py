"""Transcribes the cut reference lines (work/segs/*.wav) with Whisper into work/segs/transcripts.tsv.

Check and correct the transcripts by hand: the cloner copies whatever the text says.

    .venv-clone/Scripts/python transcribe.py
"""
from pathlib import Path

import torch
from transformers import pipeline

SEGS = Path(__file__).parent / "work" / "segs"

asr = pipeline(
    "automatic-speech-recognition",
    model="openai/whisper-large-v3-turbo",
    torch_dtype=torch.float16,
    device="cuda",
)
rows = []
for wav in sorted(SEGS.glob("seg*.wav")):
    text = asr(str(wav), generate_kwargs={"language": "english", "task": "transcribe"})["text"].strip()
    rows.append(f"{wav.name}\t{text}")
    print(wav.name, text)
(SEGS / "transcripts.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
