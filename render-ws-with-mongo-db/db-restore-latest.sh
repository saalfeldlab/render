#!/bin/bash

set -e

MOUNTED_DISK_DIR="${1:-/mnt/disks/google-mongodb-dump-disk-a}"

if [ ! -d "${MOUNTED_DISK_DIR}" ]; then
  echo "ERROR: ${MOUNTED_DISK_DIR} not found"
  exit 1
fi

DUMP_DIR="${MOUNTED_DISK_DIR}/dump/${HOSTNAME}"

if [ ! -d "${DUMP_DIR}" ]; then
  echo "ERROR: ${DUMP_DIR} not found"
  exit 1
fi

# dump files have the format: ${RUN_TIMESTAMP}.${DB}.dump.gz
LATEST_DUMP=$(find "${DUMP_DIR}" -type f -name "*.dump.gz" | sort | tail -n 1)
if [ ! -f "${LATEST_DUMP}" ]; then
  echo "ERROR: no dumps found in ${DUMP_DIR}"
  exit 1
fi

LATEST_TIMESTAMP=$(basename "${LATEST_DUMP}" | cut -d. -f1)

URI="mongodb://localhost:27017"

for DB in render match; do
  DUMP_FILE="${DUMP_DIR}/${LATEST_TIMESTAMP}.${DB}.dump.gz"
  if [ -f "${DUMP_FILE}" ]; then
    echo "restoring ${DUMP_FILE} ..."
    mongorestore --uri="${URI}" --archive="${DUMP_FILE}" --gzip
  else
    echo "WARNING: ${DUMP_FILE} not found"
  fi
done