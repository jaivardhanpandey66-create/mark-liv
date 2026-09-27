#!/usr/bin/env bash
# Downloads the bundled brain model for the MARK-LIV offline app.
#
# The APK ships this file inside assets/models/, so a normal user never needs
# this script. It exists for building the APK from source, because the model is
# ~491 MB and is therefore kept out of Git.
#
#   Qwen2.5-0.5B-Instruct  Q4_K_M   (no API, no network at runtime)
set -e
DEST="$(cd "$(dirname "$0")" && pwd)/app/assets/models"
FILE="qwen2.5-0.5b-q4_k_m.gguf"
URL="https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-q4_k_m.gguf"

mkdir -p "$DEST"
if [ -s "$DEST/$FILE" ]; then
  echo "already there: $DEST/$FILE ($(stat -c%s "$DEST/$FILE") bytes)"
  exit 0
fi

echo "downloading $FILE (~491 MB) ..."
curl -fL --retry 3 -C - -o "$DEST/$FILE.part" "$URL"
mv "$DEST/$FILE.part" "$DEST/$FILE"
echo "done: $DEST/$FILE ($(stat -c%s "$DEST/$FILE") bytes)"
echo "now run:  bash build_apk.sh"
