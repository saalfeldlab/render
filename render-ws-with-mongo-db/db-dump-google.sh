#!/bin/bash

set -e

# ----------------------------------------------------------------------------
# Generate mongodb dump files from a render-ws-mongodb Google Cloud VM container.
#
# Dump files for the render and match databases are written to:
#   /mnt/disks/mongodb_dump_fs/dump/${HOSTNAME}/${RUN_TIMESTAMP}.${DB}.dump.gz
#
# If an argument is provided, it is used instead of the current hostname as the subdirectory.

DUMP_SUBDIR="${1:-${HOSTNAME}}"
BASE_DUMP_DIR="/mnt/disks/mongodb_dump_fs/dump"

if [ ! -d "${BASE_DUMP_DIR}" ]; then
  echo "ERROR: ${BASE_DUMP_DIR} not found"
  exit 1
fi

# /mnt/disks/mongodb_dump_fs/dump/render-ws-mongodb-8c-32gb-aab
DUMP_DIR="${BASE_DUMP_DIR}/${DUMP_SUBDIR}"
mkdir -p "${DUMP_DIR}"

URI="mongodb://localhost:27017"
RUN_TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

unset DUMP_FILE_LIST
for DB in render match; do
  DUMP_FILE="${DUMP_DIR}/${RUN_TIMESTAMP}.${DB}.dump.gz"
  # see https://www.mongodb.com/docs/database-tools/mongodump/
  mongodump --uri="${URI}" --db="${DB}" --archive="${DUMP_FILE}" --gzip
  DUMP_FILE_LIST+=("${DUMP_FILE}")
done

echo
for DUMP_FILE in "${DUMP_FILE_LIST[@]}"; do
  echo "created ${DUMP_FILE}"
done