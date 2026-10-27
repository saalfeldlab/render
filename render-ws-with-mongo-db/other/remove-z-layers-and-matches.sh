#!/bin/bash

set -e

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"
PROJECT="w61_serial_070_to_079"
STACK="w61_s070_r01_gc_bc_par"
MATCH_COLLECTION="${STACK}_match"
MAX_Z=82

STACK_URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}"
MATCH_COLLECTION_URL="${BASE_URL}/owner/${OWNER}/matchCollection/${MATCH_COLLECTION}"

printMatchPairCount() {
  curl -s -X GET --header 'Accept: application/json' "${BASE_URL}/owner/${OWNER}/matchCollections" |
    jq -r --arg name "${MATCH_COLLECTION}" \
       '(.[] | select(.collectionId.name == $name) | "  \(.collectionId.name) has \(.pairCount) match pairs")
        // "  match collection \($name) not found"'
}

printf "\nbefore removing z layers, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${STACK_URL}" | jq '.'

printf "\nbefore removing z layers, match collection:\n"
printMatchPairCount

Z_TO_REMOVE="$(seq 1 9) $(seq 12 ${MAX_Z})"   # z 1 to 9, 12 to 82 (keeps z 10 and 11)
# Z_TO_REMOVE=$(seq 2 ${MAX_Z})                 # z 2 to 82
# Z_TO_REMOVE="1 $(seq 3 ${MAX_Z})"             # z 1, 3 to 82

# Match pairs are grouped by section id, which can differ from z (e.g. after scan removal renumbers z),
# so find the section ids for the removed layers before their tiles (and section data) are deleted.
# Section ids that are also used by a kept layer are not removed.
# shellcheck disable=SC2086
Z_TO_REMOVE_JSON=$(printf '%s\n' ${Z_TO_REMOVE} | jq -s '.')
GROUP_IDS_TO_REMOVE=$(
  curl -s -X GET --header 'Accept: application/json' "${STACK_URL}/sectionData" |
    jq -r --argjson removedZ "${Z_TO_REMOVE_JSON}" '
      def isRemoved: .z as $z | any($removedZ[]; . == $z);
      ([.[] | select(isRemoved | not) | .sectionId] | unique) as $keptGroupIds
      | [.[] | select(isRemoved) | .sectionId] | unique - $keptGroupIds | .[]')

if [ -z "${GROUP_IDS_TO_REMOVE}" ]; then
  printf "\nERROR: no section ids found for the z layers to remove in %s\n\n" "${STACK}"
  exit 1
fi

printf "\nmatch pairs will be removed for section ids: %s\n\n" "$(echo ${GROUP_IDS_TO_REMOVE})"

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/LOADING"

for Z in ${Z_TO_REMOVE}; do
  STACK_Z_URL="${STACK_URL}/z/${Z}"
  printf "deleting %s ...\n" "${STACK_Z_URL}"
  curl -s -X DELETE --header 'Accept: text/plain' "${STACK_Z_URL}"
done

curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' "${STACK_URL}/state/COMPLETE"

printf "\n"

# For each removed section id, the pGroup delete removes pairs within the section and pairs with it as
# the p group, and the matchesOutsideGroup delete removes pairs between it and any other section
# (with it as either the p or q group).  Together they remove every pair that involves the section.
for GROUP_ID in ${GROUP_IDS_TO_REMOVE}; do
  printf "deleting match pairs for section id %s ...\n" "${GROUP_ID}"
  curl -s -X DELETE --header 'Accept: text/plain' "${MATCH_COLLECTION_URL}/pGroup/${GROUP_ID}/matches"
  curl -s -X DELETE --header 'Accept: text/plain' "${MATCH_COLLECTION_URL}/group/${GROUP_ID}/matchesOutsideGroup"
done

printf "\nafter removing z layers, stack metadata is:\n"
curl -s -X GET --header 'Accept: application/json' "${STACK_URL}" | jq '.'

printf "\nafter removing z layers, match collection:\n"
printMatchPairCount
printf "\n"