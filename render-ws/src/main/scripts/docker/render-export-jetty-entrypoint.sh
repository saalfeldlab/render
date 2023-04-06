#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPT_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

# configure this container using quote-stripped versions of current environment variables
. "${SCRIPT_DIR}"/render-env.sh
"${SCRIPT_DIR}"/render-config.sh

ROOT_EXPORT_DIR="/render-export"

if [ ! -d ${ROOT_EXPORT_DIR} ]; then
    echo """
ERROR: root export directory ${ROOT_EXPORT_DIR} not mounted

  container should be run with mount option something like:
  --mount source=host-export-dir,target=/render-export
"""
    exit 1
fi

if (( $# < 1 )); then
  CONTAINER_RUN_TIME=$(date +"%Y%m%d_%H%M%S")
  CONTAINER_EXPORT_DIR="${ROOT_EXPORT_DIR}/export_${CONTAINER_RUN_TIME}"
else
  CONTAINER_EXPORT_DIR="${ROOT_EXPORT_DIR}/${1}"
fi

JETTY_BASE_EXPORT_DIR="${CONTAINER_EXPORT_DIR}/jetty_base"
JETTY_HOME_EXPORT_DIR="${CONTAINER_EXPORT_DIR}/jetty_home"
JANELIA_SCRIPTS_EXPORT_DIR="${CONTAINER_EXPORT_DIR}/render-scripts"

if [ -d "${JETTY_BASE_EXPORT_DIR}" ]; then
    echo """
ERROR: jetty base export directory ${JETTY_BASE_EXPORT_DIR} already exists

  exiting so that existing files aren't overwritten
  re-run to export to a directory with a new time stamp
"""
    exit 1
fi

mkdir -p "${JETTY_BASE_EXPORT_DIR}" "${JETTY_HOME_EXPORT_DIR}" "${JANELIA_SCRIPTS_EXPORT_DIR}"

echo "
exporting $(du -sh "${JETTY_BASE}" | awk '{ print $1 " in " $2 }') to ${JETTY_BASE_EXPORT_DIR}
"

# copy jetty base to export directory
cp -r "${JETTY_BASE}"/* "${JETTY_BASE_EXPORT_DIR}"

echo "exporting $(du -sh "${JETTY_HOME}" | awk '{ print $1 " in " $2 }') to ${JETTY_HOME_EXPORT_DIR}
"

# copy jetty home to export directory
cp -r "${JETTY_HOME}"/* "${JETTY_HOME_EXPORT_DIR}"

# copy scripts to export directory
cp /render-scripts/* "${JANELIA_SCRIPTS_EXPORT_DIR}"