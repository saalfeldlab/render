#!/bin/bash

set -e

# ----------------------------------------------------------------------------
# Reduces a stack's match collection to the matches among a few group ids (z layers).
#
# Everything that touches a group outside the specified list is removed, so the
# cross layer pairs between the specified groups (e.g. 11.0 to 12.0) are kept along
# with the within layer pairs.
#
# The match collection is <stack>_match and the project is derived from the stack name.

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"

# ----------------------------------------------------------------------------
# Parse named parameters

ARG_STACK=""
ARG_GROUP_IDS=()

usage() {
  echo "
USAGE $0 --stack <stack> --group-id <group-id> [<group-id> ...]

  --stack     stack whose <stack>_match collection should be reduced (required, must start with
              w<wafer>_s<slab>_r<region>, e.g. w61_s070_r00_gc_icc_par_crc)
  --group-id  one or more group ids (z layers) to keep (required, e.g. 11.0 12.0 13.0)

Example:
  $0 --stack w61_s070_r00_gc_icc_par_crc --group-id 11.0 12.0 13.0
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
    --stack)
      ARG_STACK="${2:?'--stack requires a value'}"
      shift 2
      ;;
    --group-id)
      shift
      collect_values "$@"
      ARG_GROUP_IDS=("${COLLECTED_VALUES[@]}")
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

if [ -z "${ARG_STACK}" ]; then
  echo "ERROR: --stack is required"
  usage
fi

if (( ${#ARG_GROUP_IDS[@]} == 0 )); then
  echo "ERROR: --group-id requires at least one group id"
  usage
fi

# group ids are z values with a fractional part, so normalize 11 and 11.0 to the same 11.0
KEEP_GROUP_IDS=()
for GROUP_ID in "${ARG_GROUP_IDS[@]}"; do
  if [[ ! "${GROUP_ID}" =~ ^[0-9]+(\.0)?$ ]]; then
    echo "ERROR: --group-id values must look like 11 or 11.0 (not '${GROUP_ID}')"
    exit 1
  fi
  KEEP_GROUP_IDS+=("${GROUP_ID%.0}.0")
done

# ----------------------------------------------------------------------------
# Derive the project and match collection names from the stack name

if [[ ! "${ARG_STACK}" =~ ^w([0-9]+)_s([0-9]+)_r([0-9]+) ]]; then
  echo "ERROR: --stack '${ARG_STACK}' must start with w<wafer>_s<slab>_r<region> (e.g. w61_s070_r00)"
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

MATCH_COLLECTION="${ARG_STACK}_match"

MATCH_COLLECTIONS_URL="${BASE_URL}/owner/${OWNER}/matchCollections"
MATCH_COLLECTION_URL="${BASE_URL}/owner/${OWNER}/matchCollection/${MATCH_COLLECTION}"

# ----------------------------------------------------------------------------
# Read the max z from the stack bounds

BOUNDS_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${ARG_STACK}/bounds"
MAX_Z=$(curl -s --header 'Accept: application/json' "${BOUNDS_URL}" | jq -r '.maxZ // empty')

if [[ ! "${MAX_Z}" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
  echo "ERROR: could not read maxZ from ${BOUNDS_URL}"
  echo "       (check that stack ${ARG_STACK} exists in project ${PROJECT})"
  exit 1
fi

MAX_Z="${MAX_Z%%.*}"  # bounds returns 82.0, the group loop below needs 82

# ----------------------------------------------------------------------------
# Work out which groups to remove

is_kept_group() {
  local CANDIDATE="${1}"
  local KEPT
  for KEPT in "${KEEP_GROUP_IDS[@]}"; do
    if [ "${KEPT}" = "${CANDIDATE}" ]; then
      return 0
    fi
  done
  return 1
}

REMOVE_GROUP_IDS=()
for (( Z=1; Z<=MAX_Z; Z++ )); do
  GROUP_ID="${Z}.0"
  if ! is_kept_group "${GROUP_ID}"; then
    REMOVE_GROUP_IDS+=("${GROUP_ID}")
  fi
done

echo "
Reducing match collection with:
  owner:            ${OWNER}
  project:          ${PROJECT}
  stack:            ${ARG_STACK}
  match collection: ${MATCH_COLLECTION}
  stack max z:      ${MAX_Z}
  groups to keep:   ${KEEP_GROUP_IDS[*]}
  groups to remove: ${#REMOVE_GROUP_IDS[@]}
"

printf "\nbefore removing matches, collections are:\n"
curl -s -X GET --header 'Accept: application/json' "${MATCH_COLLECTIONS_URL}" | jq '.'

# ----------------------------------------------------------------------------
# 1. remove every pair where a group being removed is the p side

printf "\nremoving matches for %d group(s) outside %s ...\n" "${#REMOVE_GROUP_IDS[@]}" "${KEEP_GROUP_IDS[*]}"

for GROUP_ID in "${REMOVE_GROUP_IDS[@]}"; do
  curl -s -X DELETE --header 'Accept: text/plain' "${MATCH_COLLECTION_URL}/pGroup/${GROUP_ID}/matches"
done

# ----------------------------------------------------------------------------
# 2. remove the pairs that reach from a kept group out to a group being removed
#    (the step above cannot catch these because the kept group is the p side)

printf "removing matches from each kept group out to those %d group(s) ...\n" "${#REMOVE_GROUP_IDS[@]}"

for KEPT_GROUP_ID in "${KEEP_GROUP_IDS[@]}"; do
  for GROUP_ID in "${REMOVE_GROUP_IDS[@]}"; do
    curl -s -X DELETE --header 'Accept: text/plain' \
      "${MATCH_COLLECTION_URL}/group/${KEPT_GROUP_ID}/matchesWith/${GROUP_ID}"
  done
done

printf "\nafter removing matches, collections are:\n"
curl -s -X GET --header 'Accept: application/json' "${MATCH_COLLECTIONS_URL}" | jq '.'

printf "\nremaining group ids in %s are:\n" "${MATCH_COLLECTION}"
curl -s -X GET --header 'Accept: application/json' "${MATCH_COLLECTION_URL}/groupIds" | jq -c '.'
