#!/bin/bash
# Runs all intensity correction variants, renders the source and all results, and computes shading profiles.
# The top of an MFOV is lighter (double exposure bleaches; images are inverted), so shift/scale darken the
# top-of-MFOV tile during matching and the slope darkens the MFOV top after fitting.
# The IC runs must happen outside of the Claude sandbox (java needs direct network access to render-ws and GCS).
set -euo pipefail
cd "$(dirname "$0")"
source common.sh
PYTHON=~/Software/miniforge3/envs/cookiecutter/bin/python

declare -A VARIANTS=(
  [base]=""
  [w0]="--mfovSeamMatchWeight 0"
  [w001]="--mfovSeamMatchWeight 0.01"
  [w01]="--mfovSeamMatchWeight 0.1"
  [sub15]="--mfovSeamShift -15"
  [mul091]="--mfovSeamScale 0.91"
  [slopem15]="--mfovSlope -15"
  [add5]="--mfovSeamShift 5"
  [sub5]="--mfovSeamShift -5"
  [mul097]="--mfovSeamScale 0.97"
  [mul103]="--mfovSeamScale 1.03"
  [slopep5]="--mfovSlope 5"
  [slopem5]="--mfovSlope -5"
  [add10]="--mfovSeamShift 10"
  [sub10]="--mfovSeamShift -10"
  [mul094]="--mfovSeamScale 0.94"
  [mul106]="--mfovSeamScale 1.06"
  [slopep3]="--mfovSlope 3"
  [slopep8]="--mfovSlope 8"
  [slopep5_add5]="--mfovSlope 5 --mfovSeamShift 5"
  [slopep5_sub5]="--mfovSlope 5 --mfovSeamShift -5"
  [nc2]="--numCoefficients 2"
  [nc8]="--numCoefficients 8"
  [l1_001]="--lambda1 0.01"
  [l1_1]="--lambda1 1.0"
)
# variants to run: given as arguments, or the current round by default (round 1: base w0 w001 w01 sub15 mul091 slopem15)
ORDER=("$@"); [ ${#ORDER[@]} -gt 0 ] || ORDER=(add5 sub5 add10 sub10 mul097 mul103 mul094 mul106 slopep3 slopep5 slopep8 slopem5
  slopep5_add5 slopep5_sub5 nc2 nc8 l1_001 l1_1)

[ -f renders/${SOURCE_STACK}.png ] || ./render.sh $SOURCE_STACK
STACKS=($SOURCE_STACK ${SOURCE_STACK}_ic_base)
for v in "${ORDER[@]}"; do
  stack=${SOURCE_STACK}_ic_$v
  echo "$(date +%T) running $v"
  # shellcheck disable=SC2086
  ./run_ic.sh $v ${VARIANTS[$v]} || { echo "$(date +%T) $v FAILED, see logs/ic_$v.log"; continue; }
  echo "$(date +%T) rendering $stack"
  ./render.sh $stack
  ./render.sh $stack 0.023 120 200
  [ $v = base ] || STACKS+=($stack)
done

MPLCONFIGDIR=${TMPDIR:-/tmp} $PYTHON profile.py "${STACKS[@]}" | tee summary.txt
