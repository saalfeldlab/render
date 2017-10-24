#!/bin/bash
if [[ "$OSTYPE" == "darwin"* ]]; then
        # Mac OSX
      readl() { greadlink $@; } 
else
      readl() { readlink $@; } 
fi
ABSOLUTE_SCRIPT=`readl -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`
. ${SCRIPTS_DIR}/setup_java_env.sh 1G

runJavaCommandAndExit org.janelia.render.client.CoordinateClient $*