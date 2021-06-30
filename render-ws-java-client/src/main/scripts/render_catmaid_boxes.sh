#!/bin/bash
if [[ "$OSTYPE" == "darwin"* ]]; then
        # Mac OSX
      readl() { greadlink $@; } 
else
      readl() { readlink $@; } 
fi
ABSOLUTE_SCRIPT=`readl -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`

MAX_MEMORY=${MAX_MEMORY:-6G}
. ${SCRIPTS_DIR}/setup_java_env.sh ${MAX_MEMORY}

runJavaCommandAndExit org.janelia.render.client.BoxClient $*