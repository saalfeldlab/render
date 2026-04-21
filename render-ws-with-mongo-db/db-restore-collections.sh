#!/usr/bin/env bash

# ----------------------------------------------------------------------------
# Usage: db-restore-collection.sh [DUMP_PATTERN]
#
# Restore dump files to the mongodb database running on the current Google Cloud VM container.
#
# You will be prompted to select one or more dump directories within
#   /mnt/disks/mongodb_dump_fs/dump/janelia
#
# Dump directories have the pattern:
#   /mnt/disks/mongodb_dump_fs/dump/<location>/<stage>/<project>/<slab-group>/<db>
#
# For example:
#   /mnt/disks/mongodb_dump_fs/dump/google/01_match/w61_serial_110_to_119/s115_to_s119_r00/match

BASE_DUMP_DIR="/mnt/disks/mongodb_dump_fs/dump"
DUMP_PATTERN="${1:-}"
PATTERN_IS_ARG=false
[[ -n "${1:-}" ]] && PATTERN_IS_ARG=true

# List unique child directory names one level below $1.
list_level() {
    local base="${1%/}"
    find "$base" -mindepth 1 -maxdepth 1 -type d | sed "s|^${base}/||" | sort
}

# Display a numbered list and prompt for one or more selections.
# Prepends "all" as option 1. Sets SELECTED array to chosen items.
pick_many() {
    local prompt="$1"; shift
    local items=("$@")
    printf "\n%s\n" "$prompt"
    for (( i=0; i<${#items[@]}; i++ )); do
        printf "%5d) %s\n" $(( i+1 )) "${items[$i]#"$BASE_DUMP_DIR/"}"
    done
    printf "\nEnter numbers separated by spaces or commas, or 'all'.\n"
    while true; do
        read -rp "Selection: " raw
        raw="${raw//,/ }"
        if [[ "$raw" == "all" ]]; then
            SELECTED=("${items[@]}"); return 0
        fi
        SELECTED=()
        local valid=true
        for tok in $raw; do
            if [[ "$tok" =~ ^[0-9]+$ ]] && (( tok >= 1 && tok <= ${#items[@]} )); then
                SELECTED+=("${items[$((tok-1))]}")
            else
                echo "  Invalid entry '$tok' — enter numbers between 1 and ${#items[@]}."
                valid=false; break
            fi
        done
        $valid && [[ ${#SELECTED[@]} -gt 0 ]] && return 1
        $valid && echo "  No selection made — please choose at least one."
    done
}

# build DUMP_PATTERN interactively if not supplied
# glob_patterns holds a list of full -path glob patterns, one per selected branch
if [[ -z "$DUMP_PATTERN" ]]; then
    levels=("LOCATION" "STAGE" "PROJECT" "SLAB_GROUP" "DB")
    # partial_paths: relative paths being drilled into, e.g. ("google/")
    partial_paths=("")

    for (( lvl=0; lvl<${#levels[@]}; lvl++ )); do
        label="${levels[$lvl]}"

        # Collect deduplicated children across all current partial paths
        children=()
        while IFS= read -r u; do children+=("$u"); done < <(
            for p in "${partial_paths[@]}"; do
                list_level "$BASE_DUMP_DIR/$p"
            done | sort -u)

        pick_many "Select one or more ${label}s:" "${children[@]}"

        if (( $? == 0 )); then
            # Append wildcards for remaining levels to each partial path
            stars=""
            for (( r=lvl; r<${#levels[@]}; r++ )); do stars="${stars}*/"; done
            glob_patterns=()
            for p in "${partial_paths[@]}"; do
                glob_patterns+=("$BASE_DUMP_DIR/${p}${stars%/}")
            done
            break
        else
            # Fan out partial paths with each selected child, skipping
            # combinations that do not exist on disk (e.g. google_test/00_gc/).
            new_paths=()
            for p in "${partial_paths[@]}"; do
                for sel in "${SELECTED[@]}"; do
                    [[ -d "$BASE_DUMP_DIR/${p}${sel}" ]] && new_paths+=("${p}${sel}/")
                done
            done
            partial_paths=("${new_paths[@]}")

            # Last level: exact paths, no wildcards needed
            if (( lvl == ${#levels[@]} - 1 )); then
                glob_patterns=()
                for p in "${partial_paths[@]}"; do
                    glob_patterns+=("$BASE_DUMP_DIR/${p%/}")
                done
            fi

        fi
    done
fi

# collect matching directories
matches=()
if $PATTERN_IS_ARG; then
    # Argument is a free-form substring — match anywhere in the path
    while IFS= read -r d; do matches+=("$d"); done < <(
        find "$BASE_DUMP_DIR" -mindepth 5 -maxdepth 5 -type d \
            | grep -F "$DUMP_PATTERN" | sort)
elif [[ -n "${glob_patterns[*]}" ]]; then
    # One or more glob patterns built interactively
    while IFS= read -r d; do matches+=("$d"); done < <(
        for pat in "${glob_patterns[@]}"; do
            eval "find \"$BASE_DUMP_DIR\" -mindepth 5 -maxdepth 5 -type d -path \"$pat\""
        done | sort -u)
fi

if [[ ${#matches[@]} -eq 0 ]]; then
    echo "No mongodb dump directories found."
    exit 1
fi

# multi-select and print results
pick_many "Select one or more mongodb dump directories:" "${matches[@]}"
all_selected=$?

printf "\nYou selected the following mongodb dump directories:\n"
if (( all_selected == 0 )); then
    printf '  %s\n' "${matches[@]}"
else
    for sel in "${SELECTED[@]}"; do
        echo "  $sel"
    done
fi

echo

URI="mongodb://localhost:27017"

shopt -s nullglob
for DUMP_DIR in "${SELECTED[@]}"; do
  for DUMP_FILE in "${DUMP_DIR}"/*.bson.gz; do
    echo "restoring ${DUMP_FILE} ..."
    # see https://www.mongodb.com/docs/database-tools/mongorestore/
    # notes: --dryRun option exists
    #        you can load into a different database and/or collection by specifying --db and --collection
    mongorestore --uri="${URI}" --gzip "${DUMP_FILE}"
  done
done
