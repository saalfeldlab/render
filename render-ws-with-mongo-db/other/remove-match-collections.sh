#!/bin/bash

OWNER="${1:-hess_wafers_60_61}"

OWNER_URL="http://localhost:8080/render-ws/v1/owner/${OWNER}"
MC_URL="${OWNER_URL}/matchCollections"

mapfile -t MC_NAMES < <(curl -s "${MC_URL}" | jq -r '.[] | "\(.collectionId.name)"' | sort)

if (( ${#MC_NAMES[@]} == 0 )); then
    printf "\nExiting, no match collections exist for owner %s\n\n" "${OWNER}"
    exit 1
fi

if [ "${#MC_NAMES[@]}" -eq 1 ]; then

  MC="${MC_NAMES[0]}"

  echo
  read -rp "Are you sure you want to remove match collection ${MC} ? (y/n): " CONFIRM
  if [[ ${CONFIRM} =~ ^[Yy]$ ]]; then
      curl -X DELETE --header 'Accept: application/json' "${OWNER_URL}/matchCollection/${MC}"
  else
    echo
    exit 0
  fi

else

  printf "\nHere are the current %s match collections:\n\n" "${OWNER}"

  for i in "${!MC_NAMES[@]}"; do
    printf "  %d) %s\n" $((i+1)) "${MC_NAMES[i]}"
  done

  echo
  read -rp "Enter the numbers of the match collections you wish to remove (space-separated): " MC_NUMBERS

  if [[ -z ${MC_NUMBERS} ]]; then
    printf "\nExiting, no choice entered\n\n"
    exit 1
  fi

  SELECTED_MC_NAMES=()
  for i in ${MC_NUMBERS}; do
    if [[ $i =~ ^[0-9]+$ ]] && (( i >= 1 && i <= ${#MC_NAMES[@]} )); then
      SELECTED_MC_NAMES+=("${MC_NAMES[i-1]}")
    else
      printf "\nExiting, choices must be between 1 and %d\n\n" "${#MC_NAMES[@]}"
      exit 1
    fi
  done

  printf "\nYou chose the following match collections:\n\n"
  for i in "${!SELECTED_MC_NAMES[@]}"; do
    printf "  %s\n" "${SELECTED_MC_NAMES[i]}"
  done
  echo

  read -rp "Are you sure you want to remove these match collections? (y/n): " CONFIRM
  if [[ ${CONFIRM} =~ ^[Yy]$ ]]; then
      for MC in "${SELECTED_MC_NAMES[@]}"; do
          curl -X DELETE --header 'Accept: application/json' "${OWNER_URL}/matchCollection/${MC}"
      done
  else
    echo
    exit 0
  fi
fi

printf "\nMatch collections for owner %s are now:\n\n" "${OWNER}"

curl -X GET --silent --header 'Accept: application/json' "${MC_URL}" | jq -r '.[] | "  \(.collectionId.name)"' | sort

echo