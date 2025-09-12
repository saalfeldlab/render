#!/bin/bash

if [ "$#" -ne 4 ]; then
    echo "
Usage: $0 FROM_OWNER FROM_MATCH_COLLECTION TO_OWNER TO_MATCH_COLLECTION

Example: $0 hess_wafers_60_61   w60_s360_r00_gc_pa_mat_render_match   test_a   w60_s360_r00_gc_pa_mat_render_match_cmB
"
    exit 1
fi

FROM_OWNER="$1"
FROM_MATCH_COLLECTION="$2"
TO_OWNER="$3"
TO_MATCH_COLLECTION="$4"

echo "Renaming match collection
  ${FROM_OWNER} :: ${FROM_MATCH_COLLECTION}
to
  ${TO_OWNER} :: ${TO_MATCH_COLLECTION}
"

read -r -p "Do you want to proceed? (y/n) "
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 0
fi

DATA="{\"owner\": \"${TO_OWNER}\", \"name\": \"${TO_MATCH_COLLECTION}\"}"
BASE_URL="http://localhost:8080/render-ws/v1"
URL="${BASE_URL}/owner/${FROM_OWNER}/matchCollection/${FROM_MATCH_COLLECTION}/collectionId"
curl -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' --data "${DATA}" "${URL}"

echo "
Match collections for owner ${TO_OWNER} are now:"
curl -X GET --silent --header 'Accept: application/json' "${BASE_URL}/owner/${TO_OWNER}/matchCollections" | jq '.'