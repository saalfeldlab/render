#!/bin/sh

set -e

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`

ROOT_EXPORT_DIR="/render-export"

if [ ! -d ${ROOT_EXPORT_DIR} ]; then
    echo """
ERROR: root export directory ${ROOT_EXPORT_DIR} not mounted

  container should be run with mount option something like:
  --mount source=host-export-dir,target=/render-export
"""
    exit 1
fi

CONTAINER_RUN_TIME=$(date +"%Y%m%d_%H%M%S")
JETTY_BASE_EXPORT_DIR="${ROOT_EXPORT_DIR}/jetty_base_${CONTAINER_RUN_TIME}"

if [ -d ${JETTY_BASE_EXPORT_DIR} ]; then
    echo """
ERROR: jetty base export directory ${JETTY_BASE_EXPORT_DIR} already exists

  exiting so that existing files aren't overwritten
  re-run to export to a directory with a new time stamp
"""
    exit 1
fi

mkdir -p ${JETTY_BASE_EXPORT_DIR}

# configure this container using current environment variables
${SCRIPTS_DIR}/render-config.sh

echo """
exporting $(du -sh ${JETTY_BASE} | awk '{ print $1 " in " $2 }') to $(basename ${JETTY_BASE_EXPORT_DIR})
"""

# copy jetty base to export directory
cp -r ${JETTY_BASE}/* ${JETTY_BASE_EXPORT_DIR}