#!/bin/bash

OWNER="${1:-hess_wafers_60_61}"

OWNER_URL="http://localhost:8080/render-ws/v1/owner/${OWNER}"

mapfile -t PROJECT_NAMES < <(curl -s "${OWNER_URL}/projects" | tr -d '[]" ' | tr ',' '\n')

if [ "${#PROJECT_NAMES[@]}" -eq 1 ]; then
  PROJECT="${PROJECT_NAMES[0]}"
else
  printf "\nWhich project do you want to use?\n\n"
  select PROJECT in "${PROJECT_NAMES[@]}"; do
    if [ -n "${PROJECT}" ]; then
      break
    else
      echo "Invalid selection, try again."
    fi
  done
fi

STACK_IDS_URL="${OWNER_URL}/project/${PROJECT}/stackIds"

mapfile -t STACK_NAMES < <(curl -s "${STACK_IDS_URL}" | jq -r '.[].stack' | sort)

if (( ${#STACK_NAMES[@]} == 0 )); then
    printf "\nExiting, no stacks exist for project %s\n\n" "${PROJECT}"
    exit 1
fi

if [ "${#STACK_NAMES[@]}" -eq 1 ]; then

  STACK="${STACK_NAMES[0]}"

  echo
  read -rp "Are you sure you want to remove stack ${STACK} ? (y/n): " CONFIRM
  if [[ ${CONFIRM} =~ ^[Yy]$ ]]; then
    curl -X DELETE --header 'Accept: application/json' "${OWNER_URL}/project/${PROJECT}/stack/${STACK}"
  else
    echo
    exit 0
  fi

else

  printf "\nHere are the current %s project stacks:\n\n" "${PROJECT}"

  for i in "${!STACK_NAMES[@]}"; do
    printf "  %d) %s\n" $((i+1)) "${STACK_NAMES[i]}"
  done

  echo
  read -rp "Enter the numbers of the stacks you wish to remove (space-separated): " STACK_NUMBERS

  if [[ -z ${STACK_NUMBERS} ]]; then
    printf "\nExiting, no choice entered\n\n"
    exit 1
  fi

  SELECTED_STACK_NAMES=()
  for i in ${STACK_NUMBERS}; do
    if [[ $i =~ ^[0-9]+$ ]] && (( i >= 1 && i <= ${#STACK_NAMES[@]} )); then
      SELECTED_STACK_NAMES+=("${STACK_NAMES[i-1]}")
    else
      printf "\nExiting, choices must be between 1 and %d\n\n" "${#STACK_NAMES[@]}"
      exit 1
    fi
  done

  printf "\nYou chose the following stacks:\n\n"
  for i in "${!SELECTED_STACK_NAMES[@]}"; do
    printf "  %s\n" "${SELECTED_STACK_NAMES[i]}"
  done
  echo

  read -rp "Are you sure you want to remove these stacks? (y/n): " CONFIRM
  if [[ ${CONFIRM} =~ ^[Yy]$ ]]; then
      for STACK in "${SELECTED_STACK_NAMES[@]}"; do
          curl -X DELETE --header 'Accept: application/json' "${OWNER_URL}/project/${PROJECT}/stack/${STACK}"
      done
  else
    echo
    exit 0
  fi
fi

printf "\nStacks for owner %s and project %s are now:\n\n" "${OWNER}" "${PROJECT}"

curl -X GET --silent --header 'Accept: application/json' "${STACK_IDS_URL}" | jq -r '.[] | "  \(.stack)"' | sort

echo