#!/bin/bash

set -e

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"
PROJECT="w61_serial_100_to_109"
FROM_STACK="test_61_109_00_z2_align"
TO_STACK="test_61_109_00_z1_and_z2_align"
Z=2

FROM_RT_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${FROM_STACK}/resolvedTiles?minZ=${Z}&maxZ=${Z}"
RESOLVED_TILES=$(curl -s -X GET --header 'Accept: application/json' "${FROM_RT_URL}" | jq -c '.')
if [ -z "${RESOLVED_TILES}" ]; then
  echo "ERROR: no tiles retrieved from ${FROM_RT_URL}" >&2
  exit 1
fi

TO_STACK_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${TO_STACK}"
TO_RT_URL="${TO_STACK_URL}/resolvedTiles?deriveData=false"

printf "\nbefore adding z layer, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${TO_STACK_URL}" | jq '.'

printf "\nsetting ${TO_STACK} state to LOADING\n"
curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${TO_STACK_URL}/state/LOADING"

echo "copying tiles"
TMP_FILE=$(mktemp)
echo "${RESOLVED_TILES}" > "${TMP_FILE}"
curl -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' --data-binary "@${TMP_FILE}" "${TO_RT_URL}"
rm -f "${TMP_FILE}"

echo "setting ${TO_STACK} state to COMPLETE"
curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${TO_STACK_URL}/state/COMPLETE"

printf "\nafter copy, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${TO_STACK_URL}" | jq '.'