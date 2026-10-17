# Piper voice training (Linux + CUDA). Build:  docker build -t cog-piper voice
FROM pytorch/pytorch:2.7.1-cuda12.8-cudnn9-devel

ENV DEBIAN_FRONTEND=noninteractive PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential cmake ninja-build git espeak-ng libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 https://github.com/OHF-voice/piper1-gpl.git /piper
WORKDIR /piper
RUN pip install scikit-build setuptools wheel cmake ninja cython \
    && pip install -e '.[train]' onnx onnxscript \
    && ./build_monotonic_align.sh \
    && python setup.py build_ext --inplace

WORKDIR /data
