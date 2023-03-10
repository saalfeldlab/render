#!/bin/bash
set -e

ABSOLUTE_SCRIPT=$(readlink -m $0)
SCRIPTS_DIR=$(dirname ${ABSOLUTE_SCRIPT})

CONFIG_RUNTIME_FILE="${JETTY_BASE}/configure-runtime.txt"

if [ ! -f "${CONFIG_RUNTIME_FILE}" ]; then

  # configure this container using quote-stripped versions of current environment variables
  . "${SCRIPTS_DIR}"/render-env.sh
  "${SCRIPTS_DIR}"/render-config.sh

  # save config runtime to file so config is not rerun if container gets restarted
  date > "${CONFIG_RUNTIME_FILE}"
  echo "configured render-ws deployment and saved timestamp to ${CONFIG_RUNTIME_FILE}"
fi

# then run the jetty docker image endpoint
exec /docker-entrypoint.sh "$@"