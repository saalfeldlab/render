#!/bin/bash

set -e

# change these variables for your use case ...
OWNER="hess_wafers_60_61"
PROJECT="w61_serial_100_to_109"
FROM_STACK="w61_s109_r00_gc_par_align_original"
TO_STACK="w61_s109_r00_gc_par_align"
QUERY_PARAMETERS="?z=11&z=13"                      # leave empty to copy all z layers

# ---------------------------------------------
PROJECT_URL="http://localhost:8080/render-ws/v1/owner/${OWNER}/project/${PROJECT}"

curl -X PUT --header 'Content-Type: application/json' --header 'Accept: application/json' -d '{
  "stackResolutionX": 8,
  "stackResolutionY": 8,
  "stackResolutionZ": 8
}' "${PROJECT_URL}/stack/${FROM_STACK}/cloneTo/${TO_STACK}${QUERY_PARAMETERS}"

curl -X PUT --header 'Content-Type: application/json' --header 'Accept: application/json' "${PROJECT_URL}/stack/${TO_STACK}/state/COMPLETE"

echo "
Stack metadata for ${OWNER} :: ${PROJECT} :: ${TO_STACK} is:"
curl -X GET --silent --header 'Accept: application/json' "${PROJECT_URL}/stack/${TO_STACK}" | jq -r '.'

echo