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

DOCKER_COMPOSE_YML="${LOCAL_RENDER_WS_BASE_DIR}/docker-compose.yml"

if [ ! -f "${DOCKER_COMPOSE_YML}" ]; then
  echo "ERROR: ${DOCKER_COMPOSE_YML} not found!"
  exit 1
fi

export LOCAL_RENDER_WS_LOGS_DIR="${LOCAL_RENDER_WS_BASE_DIR}/logs"
export LOCAL_RENDER_WS_ENV_FILE="${LOCAL_RENDER_WS_BASE_DIR}/render-ws.env"

if [ ! -f "${LOCAL_RENDER_WS_ENV_FILE}" ]; then
  echo "ERROR: ${LOCAL_RENDER_WS_ENV_FILE} not found!"
  exit 1
fi

mkdir -p "${LOCAL_RENDER_WS_LOGS_DIR}"

docker compose -f "${DOCKER_COMPOSE_YML}" up