#!/bin/sh
# Downloads models that are too large for the git repository (>100 MB).
# Run once after cloning; the base.en model and intent model are committed.
set -e
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/ggml-small.en-q5_1.bin
if [ -f "$DEST" ]; then
  echo "already present: $DEST"
else
  echo "downloading whisper small.en-q5_1 (~181 MB) for accuracy mode..."
  curl -L -o "$DEST" \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin"
fi
ls -lh "$DEST"
