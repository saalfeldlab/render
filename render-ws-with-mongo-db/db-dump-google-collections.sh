#!/bin/bash

set -e

# ----------------------------------------------------------------------------
# Generate mongodb collection dump files from a render-ws-mongodb Google Cloud VM container.
#
# Dump files for the collections are written to:
#   /mnt/disks/mongodb_dump_fs/dump/<location>/<stage>/<project>/<slab-group>/<db>
#
# For example:
#   /mnt/disks/mongodb_dump_fs/dump/google/01_match/w61_serial_110_to_119/s115_to_s119_r00/match
#   /mnt/disks/mongodb_dump_fs/dump/google/02_align/w61_serial_090_to_099/s094_r00/render

BASE_DUMP_DIR="/mnt/disks/mongodb_dump_fs/dump"

if [ ! -d "${BASE_DUMP_DIR}" ]; then
  echo "ERROR: ${BASE_DUMP_DIR} not found"
  exit 1
fi

echo "
Select database:"
select DB in "render" "match"; do
  case "${DB}" in
    render|match) break ;;
    *) echo "  Invalid selection, please enter 1 or 2." ;;
  esac
done

LOCATION="google"

echo "
Select stage:"
select STAGE_CHOICE in "00_par" "01_match" "02_align" "03_ic2d_nc4_hist_rs0p5" "timestamp"; do
  case "${STAGE_CHOICE}" in
    00_par|01_match|02_align|03_ic2d_nc4_hist_rs0p5)
      STAGE="${STAGE_CHOICE}"
      break
      ;;
    timestamp)
      STAGE=$(date +"%Y%m%d_%H%M%S")
      break
      ;;
    *) echo "  Invalid selection, please enter a number from the list." ;;
  esac
done

echo "
Select project:"
PROJECTS=()
for i in $(seq 0 10 150); do
  PROJECTS+=("$(printf "w61_serial_%03d_to_%03d" "$i" "$((i+9))")")
done
select PROJECT in "${PROJECTS[@]}"; do
  if [[ -n "${PROJECT}" ]]; then
    break
  else
    echo "  Invalid selection, please enter a number from the list."
  fi
done

echo "
Enter slab-group (e.g.    s115_to_s119_r00    s094_r00    20260421_test    ):"
while true; do
  read -rp "  Slab-group: " SLAB_GROUP
  if [[ -n "${SLAB_GROUP}" ]]; then
    break
  fi
  echo "  Slab-group must not be empty."
done

echo "
Enter collection pattern regex (e.g.    .*_par_.*    .*_s11[5-9]_.*    .*align.*    ):"
while true; do
  read -rp "  Pattern: " COLLECTION_PATTERN
  if [[ -n "${COLLECTION_PATTERN}" ]]; then
    break
  fi
  echo "  Pattern must not be empty."
done

# ----------------------------------------------------------------------------
# Build and validate the target dump directory

FULL_SLAB_GROUP_DIR="${BASE_DUMP_DIR}/${LOCATION}/${STAGE}/${PROJECT}/${SLAB_GROUP}"
FULL_DB_DUMP_DIR="${FULL_SLAB_GROUP_DIR}/${DB}"

if [ -d "${FULL_DB_DUMP_DIR}" ]; then
  echo "ERROR: ${FULL_DB_DUMP_DIR} already exists"
  exit 1
else
  echo "
  Dump directory will be:
    ${FULL_DB_DUMP_DIR}
  "
fi

# ----------------------------------------------------------------------------
# Find matching collections

CONNECTION_URI="mongodb://localhost:27017/${DB}"
EVAL_CMD="printjson(db.getCollectionNames().filter(c => /${COLLECTION_PATTERN}/.test(c)).sort());"

COLLECTIONS=$( mongosh "${CONNECTION_URI}" --quiet --eval "${EVAL_CMD}" | grep "'" | sed "s/[',]//g" )

# shellcheck disable=SC2206
COLLECTION_ARRAY=(${COLLECTIONS})
COLLECTION_COUNT=${#COLLECTION_ARRAY[@]}

if [ "${COLLECTION_COUNT}" -eq 0 ]; then
  echo "no collections found matching collection-pattern ${COLLECTION_PATTERN}"
  exit 1
elif [[ "${COLLECTION_COUNT}" -eq 1 && "${COLLECTION_ARRAY[0]}" = "admin__stack_meta_data" ]]; then
  echo "ERROR: collection admin__stack_meta_data is not allowed by itself, change the collection-pattern"
  exit 1
fi

if [[ "${DB}" == "render" && "${COLLECTION_COUNT}" -gt 1 ]]; then
  # exclude admin__stack_meta_data collection from dump list because only parts of it should be dumped
  COLLECTIONS_WITHOUT_ADMIN_STACK_META_DATA=()
  for COLLECTION in "${COLLECTION_ARRAY[@]}"; do
    if [[ "${COLLECTION}" != "admin__stack_meta_data" ]]; then
      COLLECTIONS_WITHOUT_ADMIN_STACK_META_DATA+=("${COLLECTION}")
    fi
  done
  COLLECTION_ARRAY=("${COLLECTIONS_WITHOUT_ADMIN_STACK_META_DATA[@]}")
  COLLECTION_COUNT=${#COLLECTION_ARRAY[@]}
fi

echo "
Found ${COLLECTION_COUNT} collections matching pattern ${COLLECTION_PATTERN}:
"
unset SMD_QUERY
COUNT=0
for COLLECTION in "${COLLECTION_ARRAY[@]}"; do
  echo "  ${COLLECTION}"

  if [[ "${DB}" == "render" && "${COLLECTION}" == *__tile ]]; then

    COLLECTION_WITH_DASHES="${COLLECTION//__/-}" # replace __ with - so that IFS read works
    IFS="-" read -ra A <<< "${COLLECTION_WITH_DASHES}"
    NUMBER_OF_PARTS=${#A[@]}
    if (( NUMBER_OF_PARTS != 4 )); then
      echo "
ERROR: After converting ${COLLECTION} to ${COLLECTION_WITH_DASHES}
       found ${NUMBER_OF_PARTS} parts instead of 4 (for owner, project, stack, stack-collection)
"
      exit 1
    fi

    if (( COUNT > 0 )); then
      SMD_QUERY="${SMD_QUERY},"
    else
      SMD_QUERY="{\"\$or\":["
    fi
    SMD_QUERY="${SMD_QUERY}{\"stackId\":{\"owner\":\"${A[0]}\",\"project\":\"${A[1]}\",\"stack\":\"${A[2]}\"}}"
    COUNT=$((COUNT+1))

  fi

done

echo
read -p "Do you want to dump these collections? (y/n) " -n 1 -r
echo

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
  exit 1
fi

mkdir -p "${FULL_SLAB_GROUP_DIR}"

DUMP_WAIT_SECONDS=3

for COLLECTION in ${COLLECTIONS}; do

  mongodump --uri="${CONNECTION_URI}" --db="${DB}" --collection="${COLLECTION}" --gzip --out="${FULL_SLAB_GROUP_DIR}"

  if [ "${DB}" == "match" ]; then
    echo "sleeping for ${DUMP_WAIT_SECONDS} seconds in attempt to avoid container crash on larger dumps"
    sync # tell the kernel to flush all dirty buffers in memory to their backing storage right now
    sleep ${DUMP_WAIT_SECONDS}
  fi

done

if [[ -v SMD_QUERY ]]; then
  echo "
dumping admin__stack_meta_data with query:
${SMD_QUERY}]}
"
  echo "${SMD_QUERY}]}" > "${FULL_DB_DUMP_DIR}/admin__stack_meta_data.query.txt"
  mongodump --uri="${CONNECTION_URI}" --db="${DB}" --collection=admin__stack_meta_data --query "${SMD_QUERY}]}" --gzip --out="${FULL_SLAB_GROUP_DIR}"

  COLLECTION_COUNT=$((COLLECTION_COUNT+1))
fi

echo "
Dumped ${COLLECTION_COUNT} collections to:
  ${FULL_DB_DUMP_DIR}
"