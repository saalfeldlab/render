#!/bin/bash

OWNER="${1:-hess_wafers_60_61}"
PROJECT="${2:-w61_serial_070_to_079}"

printf "\nStacks for owner %s and project %s\n" "${OWNER}" "${PROJECT}"

URL="http://localhost:8080/render-ws/v1/owner/${OWNER}/project/${PROJECT}/stackIds"
curl -X GET --silent --header 'Accept: application/json' "${URL}" | jq -r '.[] | "\(.stack)"' | sort