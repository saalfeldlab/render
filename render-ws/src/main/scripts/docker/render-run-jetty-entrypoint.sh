#!/bin/sh

set -e

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`

# configure this container using quote-stripped versions of current environment variables
. ${SCRIPTS_DIR}/render-env.sh
${SCRIPTS_DIR}/render-config.sh

# then run the jetty docker image endpoint
exec /docker-entrypoint.sh "$@"