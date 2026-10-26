#!/bin/bash
# Usage: render_local.sh <stack> [scale] [minIntensity maxIntensity]
# Like render.sh, but renders locally with the client jar (needed for filters that the render-ws server doesn't know).
set -euo pipefail
source "$(dirname "$0")/common.sh"
STACK=$1; SCALE=${2:-0.023}
OUT=renders; INTENSITY=()
if [ $# -ge 4 ]; then OUT=renders_$3_$4; INTENSITY=(--minIntensity $3 --maxIntensity $4); fi  # for viewing only
TMP_OUT=local_render_tmp/$OUT/$STACK
# same box as render.sh: xmin xmax ymin ymax
$JAVA org.janelia.render.client.RenderSectionClient \
  --baseDataUrl $BASE_URL --owner $OWNER --project $PROJECT --stack $STACK \
  --rootDirectory "$EXP_DIR" --customOutputFolder $TMP_OUT --format png --doFilter false \
  --bounds -96,129157,752,104127 --scale $SCALE --numberOfThreads $THREADS "${INTENSITY[@]}" \
  1 > "$EXP_DIR/logs/render_${STACK}_${OUT}.log" 2>&1
mkdir -p "$EXP_DIR/$OUT"
mv "$EXP_DIR/$TMP_OUT/1.0.png" "$EXP_DIR/$OUT/${STACK}.png"
