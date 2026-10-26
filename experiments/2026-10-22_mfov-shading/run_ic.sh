#!/bin/bash
# Usage: run_ic.sh <suffix> [extra intensity correction flags ...]
# Runs the 2D intensity correction of pipe.03.w6n.ic2d-stitch-only.json (without spark) on the test layer
# and writes the result to <SOURCE_STACK>_ic_<suffix>. Extra flags replace the defaults of the same name.
set -euo pipefail
source "$(dirname "$0")/common.sh"
SUFFIX=$1; shift
TARGET=${SOURCE_STACK}_ic_${SUFFIX}
DEFAULTS=(
  --maxAllowedErrorGlobal 10.0 --maxIterationsGlobal 10000 --maxPlateauWidthGlobal 100
  --maxAllowedError 0.01 --maxIterations 10000 --maxPlateauWidth 2000
  --lambda1 0.1 --lambda2 0.0 --maxPixelCacheGb 10 --renderScale 0.1 --zDistance 0
  --numCoefficients 4 --equilibrationWeight 0.0
)
# jcommander rejects repeated options, so drop the defaults that are overridden
ARGS=()
for ((i = 0; i < ${#DEFAULTS[@]}; i += 2)); do
  [[ " $* " == *" ${DEFAULTS[i]} "* ]] || ARGS+=("${DEFAULTS[i]}" "${DEFAULTS[i+1]}")
done
echo "$(date) $TARGET $*" >> "$EXP_DIR/runs.txt"
$JAVA org.janelia.render.client.newsolver.DistributedIntensityCorrectionSolver \
  --baseDataUrl $BASE_URL --owner $OWNER --project $PROJECT \
  --stack $SOURCE_STACK --targetStack $TARGET --completeTargetStack \
  --threadsWorker $THREADS --threadsGlobal 1 --cacheRenderedTiles --blockSizeZ 1 \
  "${ARGS[@]}" "$@" > "$EXP_DIR/logs/ic_${SUFFIX}.log" 2>&1
