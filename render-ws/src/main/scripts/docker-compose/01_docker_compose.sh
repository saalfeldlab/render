#!/bin/bash

set -e

ABSOLUTE_SCRIPT=$(readlink -m $0)
LOCAL_RENDER_WS_BASE_DIR=$(dirname ${ABSOLUTE_SCRIPT})

# ---------------------------------------------
# edit these variables for the current server

export RENDER_WS_IMAGE="janelia-render:latest-ws"
export JETTY_RUN_AS_USER_AND_GROUP_IDS="999:999"

# ---------------------------------------------
# everything below here should be left as is

if (( $# == 0 )); then
  BASENAME=$(basename "$0")
  echo "USAGE: ${BASENAME} <up|down>"
  exit 1
fi

DOCKER_COMPOSE_YML="${LOCAL_RENDER_WS_BASE_DIR}/docker-compose.yml"

if [ ! -f "${DOCKER_COMPOSE_YML}" ]; then
  echo "ERROR: ${DOCKER_COMPOSE_YML} not found!"
  exit 1
fi

export LOCAL_RENDER_WS_LOGS_DIR="${LOCAL_RENDER_WS_BASE_DIR}/logs"

if [ ! -d "${LOCAL_RENDER_WS_LOGS_DIR}" ]; then
  echo "ERROR: ${LOCAL_RENDER_WS_LOGS_DIR} not found!"
  exit 1
fi

export LOCAL_RENDER_WS_ENV_FILE="${LOCAL_RENDER_WS_BASE_DIR}/render-ws.env"

if [ ! -f "${LOCAL_RENDER_WS_ENV_FILE}" ]; then
  echo "ERROR: ${LOCAL_RENDER_WS_ENV_FILE} not found!"
  exit 1
fi

COMPOSE_OUT_FILE="${LOCAL_RENDER_WS_LOGS_DIR}/docker-compose.out"
if [ ! -w "${COMPOSE_OUT_FILE}" ]; then
  echo "ERROR: missing writable output file

May need to run:
  sudo touch ${COMPOSE_OUT_FILE}
  sudo chmod 666 ${COMPOSE_OUT_FILE}
"
  exit 1
fi

LAUNCH_INFO="
------------------------------------------------------------------
$(date) running:
  docker compose -f ${DOCKER_COMPOSE_YML} $*
"

echo "${LAUNCH_INFO}  with launch info logged to ${COMPOSE_OUT_FILE}
"

echo "${LAUNCH_INFO}" >> "${COMPOSE_OUT_FILE}"

nohup docker compose -f "${DOCKER_COMPOSE_YML}" "$@" >> "${COMPOSE_OUT_FILE}" 2>&1 &

sleep 5

docker ps -a | tee -a "${COMPOSE_OUT_FILE}"