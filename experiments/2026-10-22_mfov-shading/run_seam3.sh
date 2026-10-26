#!/bin/bash
# Runs the baseline intensity correction with --mfovSeamShift -3 (on-the-fly shift of the top-of-seam tile's samples
# in MFOV seam matches), renders the result server-side and compares it to base IC and the dx3 pre-processing.
# Must run outside of the Claude sandbox.
set -euo pipefail
cd "$(dirname "$0")"
source common.sh
PYTHON=~/Software/miniforge3/envs/cookiecutter/bin/python
STACK=${SOURCE_STACK}_ic_sub3

echo "$(date +%T) running IC $STACK"
./run_ic.sh sub3 --mfovSeamShift -3

echo "$(date +%T) rendering $STACK"
./render.sh $STACK
./render.sh $STACK 0.023 120 200

STACKS="${SOURCE_STACK}_ic_base $STACK ${SOURCE_STACK}_dx3_ic_base"
MPLCONFIGDIR=${TMPDIR:-/tmp} $PYTHON profile.py $STACKS | tee summary_sub3.txt
$PYTHON dx_step.py $STACKS | tee -a summary_sub3.txt
