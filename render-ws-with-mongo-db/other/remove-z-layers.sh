#!/bin/bash

set -e

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"
PROJECT="w61_serial_100_to_109"
STACK="w61_s109_r00_gc_par"
MAX_Z=82

STACK_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}"

printf "\nbefore removing z layers, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${STACK_URL}" | jq '.'

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/LOADING"

Z_TO_REMOVE=$(seq 2 ${MAX_Z})       # z 2 to 82
# Z_TO_REMOVE="1 $(seq 3 ${MAX_Z})"   # z 1, 3 to 82

for Z in ${Z_TO_REMOVE}; do
  STACK_Z_URL="${STACK_URL}/z/${Z}"
  printf "deleting %s ...\n" "${STACK_Z_URL}"
  curl -s -X DELETE --header 'Accept: text/plain' "${STACK_Z_URL}"
done

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/COMPLETE"

printf "\nafter removing z layers, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${STACK_URL}" | jq '.'