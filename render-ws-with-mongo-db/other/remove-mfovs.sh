#!/bin/bash

set -e

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"
PROJECT="w61_serial_170_to_179"
STACK="w61_s178_r00_gc_icc_par"

STACK_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}"

printf "\nbefore removing tiles, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${STACK_URL}" | jq '.'

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/LOADING"

for Z in $(seq 1 68); do
  BASE_TILE_IDS_URL="${STACK_URL}/tileIds?minZ=${Z}&maxZ=${Z}"

  for MFOV in m0016; do
    TILE_IDS_URL="${BASE_TILE_IDS_URL}&matchPattern=_${MFOV}"
    mapfile -t TILE_IDS < <(curl -s "${TILE_IDS_URL}" | jq -r '.[]' | sort)
    printf "deleting %d tiles for %s ...\n" "${#TILE_IDS[@]}" "${MFOV}"
    for TILE_ID in "${TILE_IDS[@]}"; do
      curl -s -X DELETE --header 'Accept: text/plain' "${STACK_URL}/tile/${TILE_ID}"
    done
  done
done

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/COMPLETE"

printf "\nafter removing tiles, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${STACK_URL}" | jq '.'