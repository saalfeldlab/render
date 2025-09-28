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

STACK_IDS_URL="${OWNER_URL}/project/${PROJECT}/stackIds"
curl -X GET --silent --header 'Accept: application/json' "${STACK_IDS_URL}" | jq -r '.[] | "\(.stack)"' | sort

echo