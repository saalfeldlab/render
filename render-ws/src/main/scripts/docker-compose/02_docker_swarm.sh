#!/bin/bash

# This script is for running the render-ws in a docker swarm.

set -e

ABSOLUTE_SCRIPT=$(readlink -m $0)
LOCAL_RENDER_WS_BASE_DIR=$(dirname ${ABSOLUTE_SCRIPT})

# ---------------------------------------------
# edit these variables for the current server

SWARM_STACK_NAME="render-ws-swarm"
export RENDER_WS_IMAGE="janelia-render:latest-ws"
export JETTY_RUN_AS_USER_AND_GROUP_IDS="999:999"

# ---------------------------------------------
# everything below here should be left as is

if (( $# == 0 )); then
  BASENAME=$(basename "$0")
  echo "USAGE: ${BASENAME} <init|up|down>

          init  - initialize the swarm with the current host as the manager
                  (after init, use docker swarm join on other hosts to add them to the swarm)
                  
          up    - start render-ws containers on each host in the swarm
          down  - stop render-ws containers on each host in the swarm
"
  exit 1
fi

SWARM_CMD="$1"

if [ "${SWARM_CMD}" == "init" ]; then
  # see https://docs.docker.com/engine/swarm/swarm-mode/
  docker swarm init
  exit 0
fi

if [ "${SWARM_CMD}" == "down" ]; then
  # see https://docs.docker.com/engine/reference/commandline/stack_rm/
  docker stack down "${SWARM_STACK_NAME}"
  exit 0
fi

# else assume "up"
# see https://docs.docker.com/engine/reference/commandline/stack_deploy/

DOCKER_COMPOSE_YML="${LOCAL_RENDER_WS_BASE_DIR}/docker-compose.swarm.yml"

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

SWARM_OUT_FILE="${LOCAL_RENDER_WS_LOGS_DIR}/docker-swarm.out"
if [ ! -w "${SWARM_OUT_FILE}" ]; then
  echo "ERROR: missing writable output file

May need to run:
  sudo touch ${SWARM_OUT_FILE}
  sudo chmod 666 ${SWARM_OUT_FILE}
"
  exit 1
fi

LAUNCH_INFO="
------------------------------------------------------------------
$(date) running:
  docker stack up --compose-file ${DOCKER_COMPOSE_YML} --prune --resolve-image always ${SWARM_STACK_NAME}
"

echo "${LAUNCH_INFO}" >> "${STACK_OUT_FILE}"

nohup docker stack up --compose-file "${DOCKER_COMPOSE_YML}" --prune --resolve-image always "${SWARM_STACK_NAME}" >> "${STACK_OUT_FILE}" 2>&1 &
sleep 5

docker ps -a | tee -a "${SWARM_OUT_FILE}"