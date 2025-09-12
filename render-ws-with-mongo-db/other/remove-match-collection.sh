#!/bin/bash

if [ "$#" -ne 2 ]; then
    echo "
Usage: $0 OWNER MATCH_COLLECTION

Example: $0 hess_wafers_60_61  w60_s360_r00_gc_pa_mat_render_match
"
    exit 1
fi

OWNER="$1"
MATCH_COLLECTION="$2"

echo "Removing match collection
  ${OWNER} :: ${MATCH_COLLECTION}
"

read -r -p "Do you want to proceed? (y/n) "
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 0
fi

BASE_URL="http://localhost:8080/render-ws/v1"
URL="${BASE_URL}/owner/${OWNER}/matchCollection/${MATCH_COLLECTION}"
curl -X DELETE --header 'Accept: application/json' "${URL}"

echo "
Match collections for ${OWNER} are now:"
curl -X GET --silent --header 'Accept: application/json' "${BASE_URL}/owner/${OWNER}/matchCollections" | jq '.'