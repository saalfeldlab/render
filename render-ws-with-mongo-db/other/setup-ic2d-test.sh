#!/bin/bash

set -e

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"
PROJECT="w61_serial_100_to_109"
STACK="w61_s109_r00_gc_par_crc_align"

STACK_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}"

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/LOADING"

for Z in $(seq 1 19) $(seq 21 82); do
  STACK_Z_URL="${STACK_URL}/z/${Z}"
  printf "deleting %s ...\n" "${STACK_Z_URL}"
  curl -s -X DELETE --header 'Accept: text/plain' "${STACK_Z_URL}"
done

BASE_TILE_IDS_URL="${STACK_URL}/tileIds?minZ=20&maxZ=20"

for M in $(seq 0 12) $(seq 14 17) $(seq 19 28); do
  MFOV=$(printf 'm%04d' "$M") # m0000 m0026
  TILE_IDS_URL="${BASE_TILE_IDS_URL}&matchPattern=_${MFOV}"
  mapfile -t TILE_IDS < <(curl -s "${TILE_IDS_URL}" | jq -r '.[]' | sort)
  printf "deleting %d tiles for %s ...\n" "${#TILE_IDS[@]}" "${MFOV}"
  for TILE_ID in "${TILE_IDS[@]}"; do
    curl -s -X DELETE --header 'Accept: text/plain' "${STACK_URL}/tile/${TILE_ID}"
  done
done

MFOV="m0013"
TILE_IDS_URL="${BASE_TILE_IDS_URL}&matchPattern=_${MFOV}"
mapfile -t TILE_IDS < <(curl -s "${TILE_IDS_URL}" | jq -r '.[]' | sort)
for TILE_ID in "${TILE_IDS[@]}"; do
  if [[ ! "$TILE_ID" =~ m0013_r70_s90$ && ! "$TILE_ID" =~ m0013_r78_s89$ ]]; then
    curl -s -X DELETE --header 'Accept: text/plain' "${STACK_URL}/tile/${TILE_ID}"
  fi
done

MFOV="m0018"
TILE_IDS_URL="${BASE_TILE_IDS_URL}&matchPattern=_${MFOV}"
mapfile -t TILE_IDS < <(curl -s "${TILE_IDS_URL}" | jq -r '.[]' | sort)
for TILE_ID in "${TILE_IDS[@]}"; do
  if [[ ! "$TILE_ID" =~ m0018_r22_s75$ && ! "$TILE_ID" =~ m0018_r14_s74$ && ! "$TILE_ID" =~ m0018_r07_s73$ && ! "$TILE_ID" =~ m0018_r15_s47$ && ! "$TILE_ID" =~ m0018_r23_s48$ ]]; then
    curl -s -X DELETE --header 'Accept: text/plain' "${STACK_URL}/tile/${TILE_ID}"
  fi
done

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/COMPLETE"

printf "\nafter removing z layers and tiles, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${STACK_URL}" | jq '.'