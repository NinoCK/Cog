"""Speaks sentences with a packaged voice through sherpa-onnx, the engine the phone uses.

    .venv-clone/Scripts/python try_voice.py work/packaged out.wav "Hello there." "Another line."
"""
import sys
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

ESPEAK = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "cog" / "espeak-ng-data"

folder, out, *lines = sys.argv[1:]
config = sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(
            model=f"{folder}/cog.onnx", tokens=f"{folder}/tokens.txt", data_dir=str(ESPEAK)
        ),
        num_threads=2,
    )
)
tts = sherpa_onnx.OfflineTts(config)
pieces = []
for line in lines:
    audio = tts.generate(line, sid=0, speed=0.95)  # the app's speed
    pieces += [np.asarray(audio.samples), np.zeros(audio.sample_rate // 2, dtype=np.float32)]
sf.write(out, np.concatenate(pieces), tts.sample_rate)
print(out, f"{sum(len(p) for p in pieces) / tts.sample_rate:.1f}s")
