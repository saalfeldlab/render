#!/usr/bin/env bash

# ----------------------------------------------------------------------------
# Usage: db-restore-collection.sh [--pattern DUMP_PATTERN]
#
# Example DUMP_PATTERNS are: 'par.*s70', 'match.*s115', 'align.*s90', 'ic2d.*s080'
#
# Restore dump files to the mongodb database running on the current Google Cloud VM container.
#
# If you do not specify a dump pattern, you will be prompted to select one or more dump directories within
#   /mnt/disks/mongodb_dump_fs/dump/janelia
#
# Dump directories have the pattern:
#   /mnt/disks/mongodb_dump_fs/dump/<location>/<stage>/<project>/<slab-group>/<db>
#
# For example:
#   /mnt/disks/mongodb_dump_fs/dump/google/01_match/w61_serial_110_to_119/s115_to_s119_r00/match

BASE_DUMP_DIR="/mnt/disks/mongodb_dump_fs/dump"
DUMP_PATTERN=""
PATTERN_IS_ARG=false

echo
echo "Base dump directory is: ${BASE_DUMP_DIR}"
echo

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pattern)
            DUMP_PATTERN="${2:-}"
            PATTERN_IS_ARG=true
            shift 2
            ;;
        *)
            echo "Unknown argument: $1"
            echo "Usage: $0 [--pattern DUMP_PATTERN]"
            exit 1
            ;;
    esac
done

# List unique child directory names one level below $1.
list_level() {
    local BASE="${1%/}"
    find "$BASE" -mindepth 1 -maxdepth 1 -type d | sed "s|^${BASE}/||" | sort
}

# Display a numbered list and prompt for one or more selections.
# Prepends "all" as option 1. Sets SELECTED array to chosen items.
pick_many() {
    local PROMPT="$1"; shift
    local ITEMS=("$@")
    printf "\n%s\n" "$PROMPT"
    for (( I=0; I<${#ITEMS[@]}; I++ )); do
        printf "%5d) %s\n" $(( I+1 )) "${ITEMS[$I]#"$BASE_DUMP_DIR/"}"
    done
    printf "\nEnter numbers separated by spaces or commas, or 'all'.\n"
    while true; do
        read -rp "Selection: " RAW
        RAW="${RAW//,/ }"
        if [[ "$RAW" == "all" ]]; then
            SELECTED=("${ITEMS[@]}"); return 0
        fi
        SELECTED=()
        local VALID=true
        for TOK in $RAW; do
            if [[ "$TOK" =~ ^[0-9]+$ ]] && (( TOK >= 1 && TOK <= ${#ITEMS[@]} )); then
                SELECTED+=("${ITEMS[$((TOK-1))]}")
            else
                echo "  Invalid entry '$TOK' — enter numbers between 1 and ${#ITEMS[@]}."
                VALID=false; break
            fi
        done
        $VALID && [[ ${#SELECTED[@]} -gt 0 ]] && return 1
        $VALID && echo "  No selection made — please choose at least one."
    done
}

# build DUMP_PATTERN interactively if not supplied
# GLOB_PATTERNS holds a list of full -path glob patterns, one per selected branch
if [[ -z "$DUMP_PATTERN" ]]; then
    LEVELS=("LOCATION" "STAGE" "PROJECT" "SLAB_GROUP" "DB")
    # PARTIAL_PATHS: relative paths being drilled into, e.g. ("google/")
    PARTIAL_PATHS=("")

    for (( LVL=0; LVL<${#LEVELS[@]}; LVL++ )); do
        LABEL="${LEVELS[$LVL]}"

        # Collect deduplicated children across all current partial paths
        CHILDREN=()
        while IFS= read -r U; do CHILDREN+=("$U"); done < <(
            for P in "${PARTIAL_PATHS[@]}"; do
                list_level "$BASE_DUMP_DIR/$P"
            done | sort -u)

        if pick_many "Select one or more ${LABEL}s:" "${CHILDREN[@]}"; then
            # Append wildcards for remaining levels to each partial path
            STARS=""
            for (( R=LVL; R<${#LEVELS[@]}; R++ )); do STARS="${STARS}*/"; done
            GLOB_PATTERNS=()
            for P in "${PARTIAL_PATHS[@]}"; do
                GLOB_PATTERNS+=("$BASE_DUMP_DIR/${P}${STARS%/}")
            done
            break
        else
            # Fan out partial paths with each selected child, skipping
            # combinations that do not exist on disk (e.g. google_test/00_gc/).
            NEW_PATHS=()
            for P in "${PARTIAL_PATHS[@]}"; do
                for SEL in "${SELECTED[@]}"; do
                    [[ -d "$BASE_DUMP_DIR/${P}${SEL}" ]] && NEW_PATHS+=("${P}${SEL}/")
                done
            done
            PARTIAL_PATHS=("${NEW_PATHS[@]}")

            # Last level: exact paths, no wildcards needed
            if (( LVL == ${#LEVELS[@]} - 1 )); then
                GLOB_PATTERNS=()
                for P in "${PARTIAL_PATHS[@]}"; do
                    GLOB_PATTERNS+=("$BASE_DUMP_DIR/${P%/}")
                done
            fi

        fi
    done
fi

# collect matching directories
MATCHES=()
if $PATTERN_IS_ARG; then
    # Argument is a free-form substring — match anywhere in the path
    while IFS= read -r D; do MATCHES+=("$D"); done < <(
        find "$BASE_DUMP_DIR" -mindepth 5 -maxdepth 5 -type d | grep "$DUMP_PATTERN" | sort)
elif [[ -n "${GLOB_PATTERNS[*]}" ]]; then
    # One or more glob patterns built interactively
    while IFS= read -r D; do MATCHES+=("$D"); done < <(
        for PAT in "${GLOB_PATTERNS[@]}"; do
            eval "find \"$BASE_DUMP_DIR\" -mindepth 5 -maxdepth 5 -type d -path \"$PAT\""
        done | sort -u)
fi

if [[ ${#MATCHES[@]} -eq 0 ]]; then
    echo "No mongodb dump directories found."
    exit 1
fi

# multi-select and print results
pick_many "Select one or more mongodb dump directories:" "${MATCHES[@]}"
ALL_SELECTED=$?

printf "\nYou selected the following mongodb dump directories:\n"
if (( ALL_SELECTED == 0 )); then
    printf '  %s\n' "${MATCHES[@]}"
else
    for SEL in "${SELECTED[@]}"; do
        echo "  $SEL"
    done
fi

echo

URI="mongodb://localhost:27017"

shopt -s nullglob
for DUMP_DIR in "${SELECTED[@]}"; do
  for DUMP_FILE in "${DUMP_DIR}"/*.bson.gz; do

    # check for match db dumps and prompt for load since they are typically large and take ~3 minutes to load
    if [[ "$DUMP_FILE" == *match.bson.gz ]]; then
      while true; do
        DUMP_BASENAME=$(basename "${DUMP_FILE}")
        read -rp "Do you want to load ${DUMP_BASENAME}? [y/n]: " CONFIRM
        case "$CONFIRM" in
          y) break ;;
          n) continue 2 ;;
          *) echo "  Please enter 'y' or 'n'." ;;
        esac
      done
    fi

    echo
    echo
    echo "restoring ${DUMP_FILE} ..."
    echo
    # see https://www.mongodb.com/docs/database-tools/mongorestore/
    # notes: --dryRun option exists
    #        you can load into a different database and/or collection by specifying --db and --collection
    mongorestore --uri="${URI}" --gzip "${DUMP_FILE}"
  done
done