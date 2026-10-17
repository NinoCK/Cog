#!/usr/bin/env bash
# Fine-tunes the British "alan" Piper voice into Cog on the cloned recordings, in Docker (GPU).
# Run from voice/ after clone.py has finished:  bash train.sh [epochs]
# Then export:  bash train.sh export
set -euo pipefail
cd "$(dirname "$0")"
WORK="$(cd work && pwd -W 2>/dev/null || pwd)"
EPOCHS="${1:-150}"
RUN=(docker run --rm --gpus all --shm-size 4g -v "$WORK:/data" cog-piper)
export MSYS_NO_PATHCONV=1

if [ "$EPOCHS" = "export" ]; then
    CKPT=$(ls -t work/run/lightning_logs/version_*/checkpoints/*.ckpt | head -1)
    echo "exporting $CKPT"
    "${RUN[@]}" python -m piper.train.export_onnx --checkpoint "/data/${CKPT#work/}" --output-file /data/cog.onnx
    exit
fi

# The training list: every cloned sentence plus the real game lines (resampled to 22.05 kHz).
mkdir -p work/real
cp work/synth/metadata.csv work/train.csv
while IFS=$'\t' read -r name text; do
    ffmpeg -hide_banner -loglevel error -y -i "work/segs/$name" -ar 22050 -ac 1 "work/real/$name"
    printf '../real/%s|%s\n' "$name" "$text" >> work/train.csv
done < work/segs/transcripts.tsv

"${RUN[@]}" python -m piper.train fit \
    --data.voice_name cog \
    --data.csv_path /data/train.csv \
    --data.audio_dir /data/synth \
    --data.espeak_voice en-gb-x-rp \
    --data.cache_dir /data/cache \
    --data.config_path /data/cog-config.json \
    --data.batch_size 16 \
    --data.num_workers 4 \
    --model.sample_rate 22050 \
    --model.warmstart_ckpt /data/base/alan-medium.ckpt \
    --model.mos_metric null \
    --trainer.max_epochs "$EPOCHS" \
    --trainer.check_val_every_n_epoch 10 \
    --trainer.default_root_dir /data/run
