#!/bin/bash

echo "copy this from ./other to ./go.sh"
exit 1

if (( $# < 1 )); then
  echo "USAGE: $0 <first serial slab number> [last serial slab number]

Examples:
  $0 070 071
  $0 145
"
  exit 1
fi

FIRST_SLAB="${1}"
LAST_SLAB="${2}"

if [ -z "${LAST_SLAB}" ]; then
  DUMP_DIR_NAME="w61_s${FIRST_SLAB}_r00_align"
else
  DUMP_DIR_NAME="w61_s${FIRST_SLAB}_to_${LAST_SLAB}_r00_align"
fi

./db-dump-google-collections.sh render-ws-mongodb-16c-64gb-align render '.*_align.*'

ALIGN_COL_DIR="/mnt/disks/mongodb_dump_fs/dump/render-ws-mongodb-16c-64gb-align/collections"
mv ${ALIGN_COL_DIR}/20251003* ${ALIGN_COL_DIR}/"${DUMP_DIR_NAME}"

printf "\n%s now contains:\n\n" "${ALIGN_COL_DIR}"
ls -1 "${ALIGN_COL_DIR}"

echo "
(1) To remove all of the current stacks, run:
      ./other/remove-stacks.sh

(2) If the next run is for a new match batch ...

    (a) Remove all current match collections:
          ./other/remove-match-collections.sh

    (b) To load match collections for next job, run (look at top of list for 64gb-match/..._match/match collections):
          ./db-restore-collections.sh

(3) To load stacks for the next job, run (look at end of list for _par/render collections):
      ./db-restore-collections.sh

(4) Finally, remove all stacks not valid for the next run with:
      ./other/remove-stacks.sh
"