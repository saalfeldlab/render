#!/bin/bash

# ----------------------------------------------------------------------------
# Generate mongodb dump files from Janelia's render-mongodb database.
#
# Dump files are written to:
#   ./dump_<run_timestamp>/<database>/<collection>.bson.gz

if [ $# != 2 ]; then
  echo "Usage: $0 <database> <collection-pattern>   (e.g. render '^trautmane.*__w60_s296_r00__' )"
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
  echo "no collections found matching pattern ${PATTERN}"
  exit 1
elif [ "${COLLECTION_COUNT}" -eq 1 ]; then
  if [ "${COLLECTION_ARRAY[0]}" == "admin__stack_meta_data" ]; then
    echo "no render collections found matching pattern ${PATTERN}"
    exit 1
  fi
fi

echo "
Found ${COLLECTION_COUNT} collections matching pattern ${PATTERN}:
"
for COLLECTION in ${COLLECTIONS}; do
  echo "  ${COLLECTION}"
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
  #"${MONGODUMP}" --uri="${CONNECTION_URI}" --db="${DB}" --collection="${COLLECTION}" --archive="${RUN_TIMESTAMP}.${DB}.${COLLECTION}.dump.gz" --gzip
  "${MONGODUMP}" --uri="${CONNECTION_URI}" --db="${DB}" --collection="${COLLECTION}" --gzip --out="${DUMP_DIR}"
done

echo "
Dumped ${COLLECTION_COUNT} collections to:
  ${DUMP_DIR}
"