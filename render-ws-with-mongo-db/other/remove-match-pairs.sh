#!/bin/bash

set -e

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"
MATCH_COLLECTION="w61_s109_r00_gc_par_match"
MAX_Z=82

GROUP_ID="1.0"
Z_TO_REMOVE=$(seq 2 ${MAX_Z})       # z 2 to 82

# GROUP_ID="2.0"
# Z_TO_REMOVE="1 $(seq 3 ${MAX_Z})"   # z 1, 3 to 82

MATCH_COLLECTIONS_URL="${BASE_URL}/owner/${OWNER}/matchCollections"

printf "\nbefore removing matches, collections are:\n"
curl -s -X GET --header 'Accept: application/json' "${MATCH_COLLECTIONS_URL}" | jq '.'

MATCH_URL="${BASE_URL}/owner/${OWNER}/matchCollection/${MATCH_COLLECTION}/group/${GROUP_ID}/matchesOutsideGroup"
printf "\nsubmitting DELETE %s\n" "${MATCH_URL}"
curl -s -X DELETE --header 'Accept: text/plain' "${MATCH_URL}"

for Z in ${Z_TO_REMOVE}; do
  MATCH_URL="${BASE_URL}/owner/${OWNER}/matchCollection/${MATCH_COLLECTION}/pGroup/${Z}.0/matches"
  printf "submitting DELETE %s\n" "${MATCH_URL}"
  curl -s -X DELETE --header 'Accept: text/plain' "${MATCH_URL}"
done

printf "\nafter removing matches, collections are:\n"
curl -s -X GET --header 'Accept: application/json' "${MATCH_COLLECTIONS_URL}" | jq '.'