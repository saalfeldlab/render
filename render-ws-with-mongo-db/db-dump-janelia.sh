#!/bin/bash

# ----------------------------------------------------------------------------
# Generate mongodb dump files from Janelia's render-mongodb database.
#
# Dump files are written to:
#   ./dump_<run_timestamp>/<database>/<collection>.bson.gz

if [ $# != 2 ]; then
  echo "
Usage:    $0 <database> <collection-pattern>

Examples: $0  render 'trautmane__w60_serial_290_to_299__w60_.*google'
          $0  render 'trautmane__w60_serial_290_to_299__w60_.*google_cluster0'
"
  exit 1
fi

DB="${1}"
PATTERN="${2}"

read -rsp "Enter password for MongoDB root account: " MONGO_PWD
echo

MONGOSH="/groups/hess/hesslab/render/mongodb/mongosh-2.3.9-linux-x64/bin/mongosh"
MONGODUMP="/groups/hess/hesslab/render/mongodb/mongodb-database-tools-rhel93-x86_64-100.10.0/bin/mongodump"

CONNECTION_URI="mongodb://root:${MONGO_PWD}@render-mongodb4:27017,render-mongodb5:27017,render-mongodb6:27017/${DB}?authSource=admin&replicaSet=rsRender"
EVAL_CMD="printjson(db.getCollectionNames().filter(c => /${PATTERN}/.test(c) || c === \"admin__stack_meta_data\").sort());"

COLLECTIONS=$( ${MONGOSH} "${CONNECTION_URI}" --quiet --eval "${EVAL_CMD}" | grep "'" | sed "s/[',]//g" )

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
DUMP_DIR="dump_${RUN_TIMESTAMP}"

mkdir "${DUMP_DIR}"

for COLLECTION in ${COLLECTIONS}; do
  "${MONGODUMP}" --uri="${CONNECTION_URI}" --db="${DB}" --collection="${COLLECTION}" --gzip --out="${DUMP_DIR}"
done

if [[ -v SMD_QUERY ]]; then
  echo "
dumping admin__stack_meta_data with query:
${SMD_QUERY}]}
"
  echo "${SMD_QUERY}]}" > "${DUMP_DIR}/render/admin__stack_meta_data.query.txt"
  "${MONGODUMP}" --uri="${CONNECTION_URI}" --db="${DB}" --collection=admin__stack_meta_data --query "${SMD_QUERY}]}" --gzip --out="${DUMP_DIR}"

  COLLECTION_COUNT=$((COLLECTION_COUNT+1))
fi


echo "
Dumped ${COLLECTION_COUNT} collections to:
  ${DUMP_DIR}
"