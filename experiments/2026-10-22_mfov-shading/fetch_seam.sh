#!/bin/bash
# Usage: fetch_seam.sh <stack>
# Downloads the full-resolution seam example tiles (see seam_tiles.py), rendered in world space, to seam/<stack>/<tileId>.png.
set -euo pipefail
source "$(dirname "$0")/common.sh"
STACK=$1
mkdir -p "$EXP_DIR/seam/$STACK"
~/Software/miniforge3/envs/cookiecutter/bin/python "$EXP_DIR/seam_tiles.py" | xargs -P 8 -I{} curl -sSf \
  -o "$EXP_DIR/seam/$STACK/{}.png" "$BASE_URL/owner/$OWNER/project/$PROJECT/stack/$STACK/tile/{}/png-image?scale=1.0"
