#!/bin/bash

# This script is for running the render-ws containers in a docker swarm.

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
LOCAL_RENDER_WS_BASE_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

# ---------------------------------------------
# edit these variables for the current server

export JETTY_RUN_AS_USER_AND_GROUP_IDS="999:999"

# ---------------------------------------------
# everything below here should be left as is

SWARM_STACK_NAME="render-swarm"
BASENAME=$(basename "${0}")
USAGE="USAGE:
  ${BASENAME} up <render-ws-image>
  ${BASENAME} up <render-ws-image repository> <render-ws-image tag>

  Starts the specified image (e.g. janelia-render-ws:latest-ws) in a docker swarm.

  The 3-parameter <repository> <tag> variant allows for easy cut+paste from the output of docker images.

  The superfluous 'up' argument is required to make it clear that this script only
  starts up containers (or shows this usage message).
  Containers should only be brought up after the swarm has been initialized and hosts have joined.

  Other useful docker commands (not handled by this script):

  - Initialize the swarm with the current host as the manager (also prints join commands for other hosts):
      # see https://docs.docker.com/engine/swarm/swarm-mode/
      docker swarm init

  - Force the current host to leave the swarm (forcing the last master to leave will destroy the swarm):
      # see https://docs.docker.com/engine/reference/commandline/swarm_leave/
      docker swarm leave --force

  - Get the join token for a worker to join the swarm:
      # see https://docs.docker.com/engine/reference/commandline/swarm_join-token/
      docker swarm join-token worker
      
  - Stop render-ws containers on each host in the swarm:
      # see https://docs.docker.com/engine/reference/commandline/stack_rm/
      docker stack down ${SWARM_STACK_NAME}

  - List the nodes in the swarm:
      # see https://docs.docker.com/engine/reference/commandline/node_ls/
      docker node ls

  - List the tasks running on the current node in the swarm:
      # see https://docs.docker.com/engine/reference/commandline/node_ps/
      docker node ps

  - List the services running in the swarm:
      # see https://docs.docker.com/engine/reference/commandline/service_ls/
      docker service ls
"

if (( $# < 2 )) || [ "$1" != "up" ]; then
  echo "${USAGE}"
  exit 1
fi

if (( $# == 2 )); then
  export RENDER_WS_IMAGE="${2}"
else
  export RENDER_WS_IMAGE="${2}:${3}"
fi

if [ "${JETTY_RUN_AS_USER_AND_GROUP_IDS}" == "999:999" ]; then
  echo "ERROR: JETTY_RUN_AS_USER_AND_GROUP_IDS is still set to the default value of 999:999.
      Please edit this script to set the correct value for the current server."
  exit 1
fi

# Running docker stack up ... ( see https://docs.docker.com/engine/reference/commandline/stack_deploy/ )

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

echo "${LAUNCH_INFO}" >> "${SWARM_OUT_FILE}"

nohup docker stack up --compose-file "${DOCKER_COMPOSE_YML}" --prune --resolve-image always "${SWARM_STACK_NAME}" >> "${SWARM_OUT_FILE}" 2>&1 &
sleep 5

docker node ls | tee -a "${SWARM_OUT_FILE}"