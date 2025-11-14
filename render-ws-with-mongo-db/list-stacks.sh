#!/bin/bash

OWNER="${1:-hess_wafers_60_61}"

OWNER_URL="http://localhost:8080/render-ws/v1/owner/${OWNER}"

mapfile -t PROJECT_NAMES < <(curl -s "${OWNER_URL}/projects" | tr -d '[]" ' | tr ',' '\n')

if [ "${#PROJECT_NAMES[@]}" -eq 0 ]; then
  printf "\nNo projects found for owner %s, exiting\n\n" "${OWNER}"
  exit 0
fi

if [ "${#PROJECT_NAMES[@]}" -eq 1 ]; then
  PROJECT="${PROJECT_NAMES[0]}"
else
  echo "Which project do you want to use?"
  select PROJECT in "${PROJECT_NAMES[@]}"; do
    if [ -n "${PROJECT}" ]; then
      break
    else
      echo "Invalid selection, try again."
    fi
  done
fi

printf "\nStacks for owner %s and project %s are:\n\n" "${OWNER}" "${PROJECT}"

STACKS_URL="${OWNER_URL}/project/${PROJECT}/stacks"
curl -X GET --silent --header 'Accept: application/json' "${STACKS_URL}" | \
    jq -r '.[] | "  \(.stackId.stack) with maxZ \(.stats.stackBounds.maxZ)"' | sort

# .../stacks JSON example:
# {
#   "stackId": {
#     "owner": "hess_wafers_60_61",
#     "project": "w61_serial_100_to_109",
#     "stack": "w61_s109_r00_gc_par_align"
#   },
#   "state": "COMPLETE",
#   "lastModifiedTimestamp": "2025-10-07T02:54:53.321Z",
#   "currentVersionNumber": 0,
#   "currentVersion": {
#     "createTimestamp": "2025-10-07T00:16:29.550Z",
#     "versionNotes": "derived from stack with owner 'hess_wafers_60_61', project 'w61_serial_100_to_109', and name 'w61_s109_r00_gc_par'",
#     "stackResolutionX": 8,
#     "stackResolutionY": 8,
#     "stackResolutionZ": 8
#   },
#   "stats": {
#     "stackBounds": {
#       "minX": -5318,
#       "minY": -783,
#       "minZ": 1,
#       "maxX": 130342,
#       "maxY": 104672,
#       "maxZ": 82
#     },
#     "sectionCount": 82,
#     "nonIntegralSectionCount": 0,
#     "tileCount": 216125,
#     "transformCount": 0,
#     "minTileWidth": 1717,
#     "maxTileWidth": 2368,
#     "minTileHeight": 1520,
#     "maxTileHeight": 2009,
#     "channelNames": []
#   }
# }

echo