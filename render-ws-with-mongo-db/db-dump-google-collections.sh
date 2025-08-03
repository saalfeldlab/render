#!/bin/bash

set -e

# ----------------------------------------------------------------------------
# Generate mongodb collection dump files from a render-ws-mongodb Google Cloud VM container.
#
# Dump files for the collections are written to:
#   /mnt/disks/mongodb_dump_fs/dump/${HOSTNAME}/collections/${RUN_TIMESTAMP}/${DB}/${COLLECTION}.bson.gz

if [ $# != 3 ]; then
  echo "
Usage:    $0 <hostname> <db> <collection-pattern>

Examples: $0 render-ws-mongodb-8c-32gb-abc render 'trautmane.*_google_cluster0_align.*'

"
  exit 1
fi

DUMP_SUBDIR="${1}"
DB="${2}"
PATTERN="${3}"

BASE_DUMP_DIR="/mnt/disks/mongodb_dump_fs/dump"

if [ ! -d "${BASE_DUMP_DIR}" ]; then
  echo "ERROR: ${BASE_DUMP_DIR} not found"
  exit 1
fi

CONNECTION_URI="mongodb://localhost:27017/${DB}"
EVAL_CMD="printjson(db.getCollectionNames().filter(c => /${PATTERN}/.test(c)).sort());"

COLLECTIONS=$( mongosh "${CONNECTION_URI}" --quiet --eval "${EVAL_CMD}" | grep "'" | sed "s/[',]//g" )

# shellcheck disable=SC2206
COLLECTION_ARRAY=(${COLLECTIONS})
COLLECTION_COUNT=${#COLLECTION_ARRAY[@]}

if [ "${COLLECTION_COUNT}" -eq 0 ]; then
  echo "no collections found matching collection-pattern ${PATTERN}"
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
Found ${COLLECTION_COUNT} collections matching pattern ${PATTERN}:
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

RUN_TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
# /mnt/disks/mongodb_dump_fs/dump/render-ws-mongodb-8c-32gb-abc/collections/20250217_215500
DUMP_DIR="${BASE_DUMP_DIR}/${DUMP_SUBDIR}/collections/${RUN_TIMESTAMP}"
mkdir -p "${DUMP_DIR}"

for COLLECTION in ${COLLECTIONS}; do
  mongodump --uri="${CONNECTION_URI}" --db="${DB}" --collection="${COLLECTION}" --gzip --out="${DUMP_DIR}"
done

if [[ -v SMD_QUERY ]]; then
  echo "
dumping admin__stack_meta_data with query:
${SMD_QUERY}]}
"
  echo "${SMD_QUERY}]}" > "${DUMP_DIR}/render/admin__stack_meta_data.query.txt"
  mongodump --uri="${CONNECTION_URI}" --db="${DB}" --collection=admin__stack_meta_data --query "${SMD_QUERY}]}" --gzip --out="${DUMP_DIR}"

  COLLECTION_COUNT=$((COLLECTION_COUNT+1))
fi


echo "
Dumped ${COLLECTION_COUNT} collections to:
  ${DUMP_DIR}
"