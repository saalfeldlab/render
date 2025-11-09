#!/bin/bash

set -e

# ----------------------------------------------------------------------------
# Generate mongodb collection dump files from a render-ws-mongodb Google Cloud VM container.
#
# Dump files for the collections are written to:
#   /mnt/disks/mongodb_dump_fs/dump/render-ws-mongodb-16c-64gb-[dump-suffix]/collections/[collection-dir | run-time]

if [ $# -lt 3 ]; then
  echo "
Usage:    $0 <db> <dump-suffix> <collection-pattern> [collection-dir]

Examples: $0  render  par    '.*_par_.*'       w61_s140_to_149_par
          $0  match   match  '.*_s15[5-9]_.*'  w61_s155_to_159
          $0  render  align  '.*align.*'       w61_s070_to_071_r00_align
          $0  render  ic2d   '.*ic2d.*'        w61_s100_to_s109_r00_ic2d_nc4_hist

          $0  render  mat    '.*w60_s360_r00_(gc|gc_mat|gc_mat_render)__.*'
"
  exit 1
fi

RUN_TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

DB="${1}"
BASE_DUMP_SUFFIX="${2}"
COLLECTION_PATTERN="${3}"
COLLECTION_DIR="${4:-${RUN_TIMESTAMP}}"

BASE_DUMP_DIR="/mnt/disks/mongodb_dump_fs/dump"
if [ ! -d "${BASE_DUMP_DIR}" ]; then
  echo "ERROR: ${BASE_DUMP_DIR} not found"
  exit 1
fi

# /mnt/disks/mongodb_dump_fs/dump/render-ws-mongodb-16c-64gb-align/collections/w61_s100_to_101_r00_align
FULL_DUMP_DIR="${BASE_DUMP_DIR}/render-ws-mongodb-16c-64gb-${BASE_DUMP_SUFFIX}/collections/${COLLECTION_DIR}"
if [ -d "${FULL_DUMP_DIR}" ]; then
  echo "ERROR: ${FULL_DUMP_DIR} already exists"
  exit 1
fi

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

mkdir -p "${FULL_DUMP_DIR}"

DUMP_WAIT_SECONDS=3

for COLLECTION in ${COLLECTIONS}; do

  mongodump --uri="${CONNECTION_URI}" --db="${DB}" --collection="${COLLECTION}" --gzip --out="${FULL_DUMP_DIR}"

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
  echo "${SMD_QUERY}]}" > "${FULL_DUMP_DIR}/render/admin__stack_meta_data.query.txt"
  mongodump --uri="${CONNECTION_URI}" --db="${DB}" --collection=admin__stack_meta_data --query "${SMD_QUERY}]}" --gzip --out="${FULL_DUMP_DIR}"

  COLLECTION_COUNT=$((COLLECTION_COUNT+1))
fi

echo "
Dumped ${COLLECTION_COUNT} collections to:
  ${FULL_DUMP_DIR}
"