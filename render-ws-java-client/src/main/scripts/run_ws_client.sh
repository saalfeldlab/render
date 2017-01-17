#!/bin/bash

#########################################
#
# Generic shell script wrapper for running Java clients
# that use the render web services.
#
# USAGE: $0 <memory> <main-class> [client-arg-0] ... [client-arg-n]
#
#########################################

if (( $# < 2 )); then
  echo """
ERROR: missing memory and/or main class parameters

USAGE: $0 <memory> <main-class> [client-arg-0] ... [client-arg-n]

EXAMPLE: $0 1G org.janelia.render.client.ImportJsonClient --baseDataUrl http://localhost:8080/render-ws/v1 --owner demo ...
"""
  exit 1
fi

MEMORY="$1"
MAIN_CLASS="$2"
shift 2

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`
. ${SCRIPTS_DIR}/setup_java_env.sh ${MEMORY}

runJavaCommandAndExit ${MAIN_CLASS} $*