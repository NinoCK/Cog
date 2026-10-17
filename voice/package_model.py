"""Turns an exported Piper voice into the files the app's sherpa-onnx engine loads.

Adds the metadata sherpa-onnx reads from the model (espeak voice, sample rate, ...) and writes
tokens.txt from the training config's phoneme map. With --install, copies both into
app/src/main/assets/cog/ (the espeak-ng-data there comes from a sherpa-onnx Piper package).

    .venv-clone/Scripts/python package_model.py work/cog.onnx work/cog-config.json --install
"""
import argparse
import json
import shutil
from pathlib import Path

import onnx

ASSETS = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "cog"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("config", type=Path)
    parser.add_argument("--out", type=Path, help="output folder (default: next to the model)")
    parser.add_argument("--install", action="store_true", help="copy into the app's assets")
    args = parser.parse_args()

    config = json.loads(args.config.read_text(encoding="utf-8"))
    out = args.out or args.model.parent / "packaged"
    out.mkdir(parents=True, exist_ok=True)

    model = onnx.load(str(args.model))
    meta = {
        "model_type": "vits",
        "comment": "piper",
        "language": "English",
        "voice": config["espeak"]["voice"],
        "has_espeak": "1",
        "n_speakers": str(config.get("num_speakers", 1)),
        "sample_rate": str(config["audio"]["sample_rate"]),
    }
    del model.metadata_props[:]
    for key, value in meta.items():
        model.metadata_props.add(key=key, value=value)
    onnx.save(model, str(out / "cog.onnx"))

    # sherpa-onnx reads single-character tokens only. The map's multi-character entries are
    # vowel clusters ("aɪ"), used only when training sets --data.vowel_clusters (we don't).
    if config.get("vowel_clusters"):
        raise SystemExit("vowel clusters can't be used with sherpa-onnx")
    tokens = sorted(
        ((t, ids) for t, ids in config["phoneme_id_map"].items() if len(t) == 1),
        key=lambda kv: kv[1][0],
    )
    with (out / "tokens.txt").open("w", encoding="utf-8", newline="\n") as f:
        for token, ids in tokens:
            f.write(f"{token} {ids[0]}\n")
    print("wrote", out / "cog.onnx", "and", len(tokens), "tokens")

    if args.install:
        ASSETS.mkdir(parents=True, exist_ok=True)
        shutil.copy(out / "cog.onnx", ASSETS / "cog.onnx")
        shutil.copy(out / "tokens.txt", ASSETS / "tokens.txt")
        print("installed into", ASSETS)


if __name__ == "__main__":
    main()
