#!/bin/bash
# Usage: render.sh <stack> [scale] [minIntensity maxIntensity]
# Renders z=1 of the stack server-side (with tile filters, no ad hoc filter) to renders/<stack>.png
# (2972 x 2377 px at the default scale 0.023). Takes ~7 min since the server renders all 2639 tiles.
set -euo pipefail
source "$(dirname "$0")/common.sh"
STACK=$1; SCALE=${2:-0.023}
OUT=renders; QUERY=maxTileSpecsToRender=3000
if [ $# -ge 4 ]; then OUT=renders_$3_$4; QUERY="$QUERY&minIntensity=$3&maxIntensity=$4"; fi  # for viewing only
mkdir -p "$EXP_DIR/$OUT"
BOX=-96,752,129253,103375  # source stack bounds (minX, minY, width, height); identical for all IC results
curl -sSf -o "$EXP_DIR/$OUT/${STACK}.png" \
  "$BASE_URL/owner/$OWNER/project/$PROJECT/stack/$STACK/z/1/box/$BOX,$SCALE/png-image?$QUERY"
