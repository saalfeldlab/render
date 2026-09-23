#!/bin/bash

# ----------------------------------------------------------------------------
# Removes stacks or match collections from the render web service running on this VM.
#
# Usage: remove-collections.sh [--db render|match] [--owner OWNER]
#                              [--method remove|keep] [--items "1 3 5"|all]
#
# You are prompted for anything that is not specified.
# The confirmation prompts are always asked, even when --method and --items are given.
#
# --method remove selects the items to remove while --method keep selects the items to keep.
# The interactive prompt still accepts just 'r' or 'k'.
#
# The render db holds stacks, which are scoped to a project (you are prompted when the owner
# has more than one).  The match db holds match collections, which are scoped to the owner.
# Both can be selected either by the ones to remove or by the ones to keep.

BASE_URL="http://localhost:8080/render-ws/v1"

ARG_DB=""
ARG_OWNER="hess_wafers_60_61"
ARG_ITEMS=""
ARG_METHOD=""

usage() {
  printf "\nUSAGE: %s [--db render|match] [--owner OWNER] [--method remove|keep] [--items \"1 3 5\"|all]\n\n" \
         "$(basename "$0")"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "${1}" in
    --db)
      ARG_DB="${2:?'--db requires a value'}"
      shift 2
      ;;
    --owner)
      ARG_OWNER="${2:?'--owner requires a value'}"
      shift 2
      ;;
    --items)
      ARG_ITEMS="${2:?'--items requires a value'}"
      shift 2
      ;;
    --method)
      ARG_METHOD="${2:?'--method requires a value'}"
      shift 2
      ;;
    *)
      echo "ERROR: unrecognized parameter '${1}'"
      usage
      ;;
  esac
done

if [ -z "${ARG_DB}" ]; then
  echo "
Select database:"
  select ARG_DB in "render" "match"; do
    case "${ARG_DB}" in
      render|match) break ;;
      *) echo "  Invalid selection, please enter 1 or 2." ;;
    esac
  done
fi

case "${ARG_DB}" in
  render|match)
    ;;
  *)
    echo "ERROR: --db must be 'render' or 'match' (not '${ARG_DB}')"
    usage
    ;;
esac

if [ -n "${ARG_METHOD}" ]; then
  case "${ARG_METHOD}" in
    remove|keep)
      ;;
    *)
      echo "ERROR: --method must be 'remove' or 'keep' (not '${ARG_METHOD}')"
      usage
      ;;
  esac
fi

OWNER="${ARG_OWNER}"
OWNER_URL="${BASE_URL}/owner/${OWNER}"

# ----------------------------------------------------------------------------
# Shared helpers

# Lists ITEM_NAMES with numbers, reads a space separated selection, and sets SELECTED_NAMES.
# $1 is the verb shown in the prompt (e.g. remove, keep).
selectItems() {
  local ACTION="$1"
  local NUMBERS
  local I

  printf "\nHere are the current %s:\n\n" "${ITEM_CONTEXT}"

  for I in "${!ITEM_NAMES[@]}"; do
    printf "  %d) %s\n" $((I+1)) "${ITEM_NAMES[I]}"
  done

  echo
  if [ -n "${ARG_ITEMS}" ]; then
    NUMBERS="${ARG_ITEMS}"
    printf "Using --items '%s' for the %s to %s\n" "${NUMBERS}" "${ITEM_PLURAL}" "${ACTION}"
  else
    read -rp "Enter the numbers of the ${ITEM_PLURAL} you wish to ${ACTION} (space-separated, or 'all'): " NUMBERS
  fi

  if [[ -z ${NUMBERS} ]]; then
    printf "\nExiting, no choice entered\n\n"
    exit 1
  fi

  if [ "${NUMBERS}" = "all" ]; then
    SELECTED_NAMES=("${ITEM_NAMES[@]}")
    return
  fi

  SELECTED_NAMES=()
  for I in ${NUMBERS}; do
    if [[ $I =~ ^[0-9]+$ ]] && (( I >= 1 && I <= ${#ITEM_NAMES[@]} )); then
      SELECTED_NAMES+=("${ITEM_NAMES[I-1]}")
    else
      printf "\nExiting, choices must be between 1 and %d\n\n" "${#ITEM_NAMES[@]}"
      exit 1
    fi
  done
}

# Deletes each name in NAMES_TO_REMOVE.
removeItems() {
  local NAME
  for NAME in "${NAMES_TO_REMOVE[@]}"; do
    curl -X DELETE --header 'Accept: application/json' "${DELETE_URL_PREFIX}${NAME}"
  done
}

# ----------------------------------------------------------------------------
# Gather the items for the requested type

if [ "${ARG_DB}" = "render" ]; then

  mapfile -t PROJECT_NAMES < <(curl -s "${OWNER_URL}/projects" | tr -d '[]" ' | tr ',' '\n')

  if [ "${#PROJECT_NAMES[@]}" -eq 0 ]; then
    printf "\nNo projects found for owner %s, exiting\n\n" "${OWNER}"
    exit 0
  fi

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

  LIST_URL="${OWNER_URL}/project/${PROJECT}/stackIds"
  LIST_FILTER='.[] | "  \(.stack)"'
  DELETE_URL_PREFIX="${OWNER_URL}/project/${PROJECT}/stack/"
  ITEM_SINGULAR="stack"
  ITEM_PLURAL="stacks"
  ITEM_CONTEXT="${PROJECT} project stacks"

  mapfile -t ITEM_NAMES < <(curl -s "${LIST_URL}" | jq -r '.[].stack' | sort)

else

  LIST_URL="${OWNER_URL}/matchCollections"
  LIST_FILTER='.[] | "  \(.collectionId.name)"'
  DELETE_URL_PREFIX="${OWNER_URL}/matchCollection/"
  ITEM_SINGULAR="match collection"
  ITEM_PLURAL="match collections"
  ITEM_CONTEXT="${OWNER} match collections"

  mapfile -t ITEM_NAMES < <(curl -s "${LIST_URL}" | jq -r '.[].collectionId.name' | sort)

fi

if (( ${#ITEM_NAMES[@]} == 0 )); then
  printf "\nExiting, no %s exist for owner %s\n\n" "${ITEM_PLURAL}" "${OWNER}"
  exit 1
fi

# ----------------------------------------------------------------------------
# Select and remove

NAMES_TO_REMOVE=()

if [ "${#ITEM_NAMES[@]}" -eq 1 ]; then

  echo
  read -rp "Are you sure you want to remove ${ITEM_SINGULAR} ${ITEM_NAMES[0]} ? (y/n): " CONFIRM
  if [[ ${CONFIRM} =~ ^[Yy]$ ]]; then
    NAMES_TO_REMOVE=("${ITEM_NAMES[0]}")
    removeItems
  else
    echo
    exit 0
  fi

else

  if [ -n "${ARG_METHOD}" ]; then
    REMOVE_OR_KEEP="${ARG_METHOD}"
    printf "\nUsing --method to %s the selected %s\n" "${REMOVE_OR_KEEP}" "${ITEM_PLURAL}"
  else
    read -rp "Do you want to select ${ITEM_PLURAL} to be [r]emoved or to be [k]ept? (r/k): " REMOVE_OR_KEEP_CHOICE
    if [[ ${REMOVE_OR_KEEP_CHOICE} =~ ^[Rr]$ ]]; then
      REMOVE_OR_KEEP="remove"
    elif [[ ${REMOVE_OR_KEEP_CHOICE} =~ ^[Kk]$ ]]; then
      REMOVE_OR_KEEP="keep"
    else
      printf "\nExiting, you did not select 'r' or 'k'\n\n"
      exit 1
    fi
  fi

  selectItems "${REMOVE_OR_KEEP}"

  printf "\nYou chose to %s the following %s:\n\n" "${REMOVE_OR_KEEP}" "${ITEM_PLURAL}"
  printf "  %s\n" "${SELECTED_NAMES[@]}"
  echo

  if [[ ${REMOVE_OR_KEEP} == "keep" ]]; then
    for NAME in "${ITEM_NAMES[@]}"; do
      # shellcheck disable=SC2076
      if [[ ! " ${SELECTED_NAMES[*]} " =~ " ${NAME} " ]]; then
        NAMES_TO_REMOVE+=("${NAME}")
      fi
    done
  else
    NAMES_TO_REMOVE=("${SELECTED_NAMES[@]}")
  fi

  if [[ ${REMOVE_OR_KEEP} == "keep" ]]; then
    read -rp "Last check ... remove all other ${ITEM_PLURAL} (keeping the ones listed above)? (y/n): " CONFIRM
  else
    read -rp "Last check ... remove these ${ITEM_PLURAL}? (y/n): " CONFIRM
  fi
  if [[ ${CONFIRM} =~ ^[Yy]$ ]]; then
    removeItems
  else
    echo
    exit 0
  fi

fi

# ----------------------------------------------------------------------------
# Show what is left

if [ "${ARG_DB}" = "render" ]; then
  printf "\nStacks for owner %s and project %s are now:\n\n" "${OWNER}" "${PROJECT}"
else
  printf "\nMatch collections for owner %s are now:\n\n" "${OWNER}"
fi

curl -X GET --silent --header 'Accept: application/json' "${LIST_URL}" | jq -r "${LIST_FILTER}" | sort

echo
