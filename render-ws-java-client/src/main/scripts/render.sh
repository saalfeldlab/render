#!/bin/bash

#########################################
#
# Shell script wrapper for running Java ARGB render client.
#
# USAGE: $0 [--memory memory] <render-arg-0> ... <render-arg-n>
#
#########################################

if [[ $1 = "--memory" ]]; then
  if (($# > 1)); then
    MEMORY="$2"
    shift 2
  fi
else
  MEMORY="6G"
fi

MAIN_CLASS="org.janelia.alignment.ArgbRenderer"

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`
. ${SCRIPTS_DIR}/setup_java_env.sh ${MEMORY}

runJavaCommandAndExit ${MAIN_CLASS} $*