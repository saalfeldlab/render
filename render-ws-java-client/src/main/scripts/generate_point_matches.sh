#!/bin/bash

#########################################
#
# Shell script wrapper for running Java point match generation client.
#
# USAGE: $0 [--memory memory] <match-arg-0> ... <match-arg-n>
#
#########################################
if [[ "$OSTYPE" == "darwin"* ]]; then
        # Mac OSX
      readl() { greadlink $@; } 
else
      readl() { readlink $@; } 
fi
if [[ $1 = "--memory" ]]; then
  if (($# > 1)); then
    MEMORY="$2"
    shift 2
  fi
else
  # memory for 1 slot is 7.5G, use most of that (7G) to reduce chance of need for garbage collection
  MEMORY="7G"
fi

MAIN_CLASS="org.janelia.render.client.PointMatchClient"

ABSOLUTE_SCRIPT=`readl -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`
. ${SCRIPTS_DIR}/setup_java_env.sh ${MEMORY}

runJavaCommandAndExit ${MAIN_CLASS} $*