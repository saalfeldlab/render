#!/bin/bash

set -e

# ----------------------------------------------------------------------------
# Creates a small test stack by cloning selected z layers from a source stack and then
# removing all tiles that are not in the selected MFOVs.
#
# The test stack is named <source-stack>_<test-suffix> and the project is derived from
# the source stack name, so only the source stack, suffix, layers, and MFOVs need to be specified.

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"

# ----------------------------------------------------------------------------
# Parse named parameters

ARG_SOURCE_STACK=""
ARG_TEST_SUFFIX=""
ARG_Z_VALUES=()
ARG_MFOVS=()

usage() {
  echo "
USAGE $0 --source-stack <stack> --test-suffix <suffix> --z <z> [<z> ...] [--mfov <mfov> [<mfov> ...]]

  --source-stack  stack to clone (required, must start with w<wafer>_s<slab>_r<region>,
                  e.g. w61_s070_r00_gc_icc_par_crc)
  --test-suffix   suffix appended to the source stack name for the test stack (required, e.g. test_a)
  --z             one or more z layers to keep (required, e.g. 11 12 13)
  --mfov          one or more MFOVs to keep (optional, e.g. m0014 m0015; all MFOVs are kept if omitted)

Example:
  $0 --source-stack w61_s070_r00_gc_icc_par_crc --test-suffix test_a --z 11 12 13 --mfov m0014 m0015
"
  exit 1
}

if (( $# < 1 )); then
  usage
fi

# collects the values that follow a variable arity parameter, stopping at the next --parameter
collect_values() {
  COLLECTED_VALUES=()
  while [[ $# -gt 0 && "${1}" != --* ]]; do
    COLLECTED_VALUES+=("${1}")
    shift
  done
  COLLECTED_COUNT="${#COLLECTED_VALUES[@]}"
}

while [[ $# -gt 0 ]]; do
  case "${1}" in
    --source-stack)
      ARG_SOURCE_STACK="${2:?'--source-stack requires a value'}"
      shift 2
      ;;
    --test-suffix)
      ARG_TEST_SUFFIX="${2:?'--test-suffix requires a value'}"
      shift 2
      ;;
    --z)
      shift
      collect_values "$@"
      ARG_Z_VALUES=("${COLLECTED_VALUES[@]}")
      shift "${COLLECTED_COUNT}"
      ;;
    --mfov)
      shift
      collect_values "$@"
      ARG_MFOVS=("${COLLECTED_VALUES[@]}")
      shift "${COLLECTED_COUNT}"
      ;;
    *)
      echo "ERROR: unrecognized parameter '${1}'"
      usage
      ;;
  esac
done

# ----------------------------------------------------------------------------
# Validate parameters

if [ -z "${ARG_SOURCE_STACK}" ]; then
  echo "ERROR: --source-stack is required"
  usage
fi

if [ -z "${ARG_TEST_SUFFIX}" ]; then
  echo "ERROR: --test-suffix is required"
  usage
fi

if (( ${#ARG_Z_VALUES[@]} == 0 )); then
  echo "ERROR: --z requires at least one z layer"
  usage
fi

for Z in "${ARG_Z_VALUES[@]}"; do
  if [[ ! "${Z}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: --z values must be integers (not '${Z}')"
    exit 1
  fi
done

for MFOV in "${ARG_MFOVS[@]}"; do
  if [[ ! "${MFOV}" =~ ^m[0-9]{4}$ ]]; then
    echo "ERROR: --mfov values must look like m0014 (not '${MFOV}')"
    exit 1
  fi
done

# ----------------------------------------------------------------------------
# Derive the project and test stack names from the source stack name

if [[ ! "${ARG_SOURCE_STACK}" =~ ^w([0-9]+)_s([0-9]+)_r([0-9]+) ]]; then
  echo "ERROR: --source-stack '${ARG_SOURCE_STACK}' must start with w<wafer>_s<slab>_r<region> (e.g. w61_s070_r00)"
  exit 1
fi

WAFER="${BASH_REMATCH[1]}"
SLAB="${BASH_REMATCH[2]}"

# Projects hold ten slabs each (w61_s070_r00... lives in w61_serial_070_to_079), so the project
# name comes from the slab's decade.  The 10# prefix keeps 070 from being read as octal.
SLAB_NUMBER=$((10#${SLAB}))
FIRST_SLAB_IN_PROJECT=$(( SLAB_NUMBER / 10 * 10 ))
LAST_SLAB_IN_PROJECT=$(( FIRST_SLAB_IN_PROJECT + 9 ))
PROJECT=$(printf 'w%s_serial_%03d_to_%03d' "${WAFER}" "${FIRST_SLAB_IN_PROJECT}" "${LAST_SLAB_IN_PROJECT}")

TEST_STACK="${ARG_SOURCE_STACK}_${ARG_TEST_SUFFIX}"

PROJECT_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}"
STACK_URL="${PROJECT_URL}/stack/${TEST_STACK}"

# ----------------------------------------------------------------------------
# Clone the selected z layers into the test stack

Z_QUERY=""
for Z in "${ARG_Z_VALUES[@]}"; do
  if [ -z "${Z_QUERY}" ]; then
    Z_QUERY="?z=${Z}"
  else
    Z_QUERY="${Z_QUERY}&z=${Z}"
  fi
done

echo "
Creating test stack with:
  owner:        ${OWNER}
  project:      ${PROJECT}
  source stack: ${ARG_SOURCE_STACK}
  test stack:   ${TEST_STACK}
  z layers:     ${ARG_Z_VALUES[*]}
  mfovs:        ${ARG_MFOVS[*]:-<all>}
"

# cloneTo requires a stack version body that includes the resolution, so read the resolution from
# the source stack instead of hardcoding it.  This request also fails fast when the source stack
# name is wrong or is not in the derived project.
RESOLUTION_URL="${PROJECT_URL}/stack/${ARG_SOURCE_STACK}/resolutionValues"
mapfile -t RESOLUTION_VALUES < <(curl -s --header 'Accept: application/json' "${RESOLUTION_URL}" | jq -r '.[]?')

if (( ${#RESOLUTION_VALUES[@]} != 3 )); then
  echo "ERROR: expected 3 values from ${RESOLUTION_URL} but found ${#RESOLUTION_VALUES[@]}"
  echo "       (check that stack ${ARG_SOURCE_STACK} exists in project ${PROJECT})"
  exit 1
fi

printf "cloning z layers %s from %s with resolution %s (this is a slow process) ...\n" \
       "${ARG_Z_VALUES[*]}" "${ARG_SOURCE_STACK}" "${RESOLUTION_VALUES[*]}"

CREATE_TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: application/json' -d "{
  \"createTimestamp\": \"${CREATE_TIMESTAMP}\",
  \"versionNotes\": \"cloned from ${ARG_SOURCE_STACK}\",
  \"stackResolutionX\": ${RESOLUTION_VALUES[0]},
  \"stackResolutionY\": ${RESOLUTION_VALUES[1]},
  \"stackResolutionZ\": ${RESOLUTION_VALUES[2]}
}" "${PROJECT_URL}/stack/${ARG_SOURCE_STACK}/cloneTo/${TEST_STACK}${Z_QUERY}" > /dev/null

# ----------------------------------------------------------------------------
# Remove the tiles that are not in the selected MFOVs

if (( ${#ARG_MFOVS[@]} > 0 )); then

  curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/LOADING"

  # tile ids look like w61_magc0145_scan004_m0009_r32_s49, so keep any tile with an _<mfov>_ part
  KEEP_TILE_REGEX=$(printf '_%s_|' "${ARG_MFOVS[@]}")
  KEEP_TILE_REGEX="${KEEP_TILE_REGEX%|}"

  for Z in "${ARG_Z_VALUES[@]}"; do

    mapfile -t TILE_IDS < <(curl -s "${STACK_URL}/tileIds?minZ=${Z}&maxZ=${Z}" | jq -r '.[]' | sort)

    DELETED_COUNT=0
    for TILE_ID in "${TILE_IDS[@]}"; do
      if [[ ! "${TILE_ID}" =~ ${KEEP_TILE_REGEX} ]]; then
        curl -s -X DELETE --header 'Accept: text/plain' "${STACK_URL}/tile/${TILE_ID}"
        DELETED_COUNT=$(( DELETED_COUNT + 1 ))
      fi
    done

    printf "z %s: kept %d of %d tiles for %s\n" \
           "${Z}" "$(( ${#TILE_IDS[@]} - DELETED_COUNT ))" "${#TILE_IDS[@]}" "${ARG_MFOVS[*]}"

  done
fi

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/COMPLETE"

printf "\nstack metadata for %s :: %s :: %s is:\n" "${OWNER}" "${PROJECT}" "${TEST_STACK}"
curl -s -X GET --header 'Accept: application/json' "${STACK_URL}" | jq '.'
