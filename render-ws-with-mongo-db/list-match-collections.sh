#!/bin/bash

OWNER="${1:-hess_wafers_60_61}"

printf "\nMatch collections for owner %s\n" "${OWNER}"

URL="http://localhost:8080/render-ws/v1/owner/${OWNER}/matchCollections"
curl -X GET --silent --header 'Accept: application/json' "${URL}" | jq -r '.[] | "\(.collectionId.name)"' | sort