#!/bin/bash

set -e

MOUNTED_DISK_DIR="${1:-/mnt/disks/google-mongodb-dump-disk-a}"

if [ ! -d "${MOUNTED_DISK_DIR}" ]; then
  echo "ERROR: ${MOUNTED_DISK_DIR} not found"
  exit 1
fi

DUMP_DIR="${MOUNTED_DISK_DIR}/dump/${HOSTNAME}"
mkdir -p "${DUMP_DIR}"

URI="mongodb://localhost:27017"
RUN_TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

for DB in render match; do
  # see https://www.mongodb.com/docs/database-tools/mongodump/
  mongodump --uri="${URI}" --db="${DB}" --archive="${DUMP_DIR}/${RUN_TIMESTAMP}.${DB}.dump.gz" --gzip
done