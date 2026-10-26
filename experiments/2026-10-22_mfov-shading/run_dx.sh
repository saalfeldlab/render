#!/bin/bash
# Darkens the double-exposed MFOV overlaps (in the higher-numbered MFOV) by each offset in OFFSETS (default 2 4; 3 was
# run before), runs the baseline intensity correction on the result, renders the IC result locally (the server lacks
# the new filter) and profiles all dx variants. Must run outside of the Claude sandbox.
set -euo pipefail
cd "$(dirname "$0")"
source common.sh
PYTHON=~/Software/miniforge3/envs/cookiecutter/bin/python

for offset in ${OFFSETS:-2 4}; do
  DX=${SOURCE_STACK}_dx$offset
  echo "$(date +%T) creating $DX"
  $JAVA org.janelia.render.client.multisem.DoubleExposureCorrectionClient \
    --baseDataUrl $BASE_URL --owner $OWNER --project $PROJECT \
    --stack $SOURCE_STACK --targetStack $DX --offset -$offset > logs/dx$offset.log 2>&1

  echo "$(date +%T) running IC on $DX"
  SOURCE_STACK=$DX ./run_ic.sh base

  echo "$(date +%T) rendering ${DX}_ic_base"
  ./render_local.sh ${DX}_ic_base
  ./render_local.sh ${DX}_ic_base 0.023 120 200
done

IC_STACKS=$(ls renders | sed -n "s/^\(${SOURCE_STACK}_dx[0-9]*_ic_base\)\.png$/\1/p")
MPLCONFIGDIR=${TMPDIR:-/tmp} $PYTHON profile.py ${SOURCE_STACK}_ic_base $IC_STACKS | tee summary_dx.txt
$PYTHON dx_step.py ${SOURCE_STACK}_ic_base $IC_STACKS | tee -a summary_dx.txt
