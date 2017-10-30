#!/bin/bash
if [[ "$OSTYPE" == "darwin"* ]]; then
        # Mac OSX
      type greadlink 1>/dev/null 2>/dev/null
      READLINK_RC=$?
      if [ ${READLINK_RC} -ne 0 ]; then
          echo "ERROR: greadlink not available on Mac, use homebrew to install (e.g. brew install coreutils)"
          exit ${RC}
      fi
      unset READLINK_RC

      readl() { greadlink $@; }
else
      readl() { readlink $@; } 
fi

ABSOLUTE_ENV_SCRIPT=`readl -m $0`

export SCRIPTS_DIR=`dirname ${ABSOLUTE_ENV_SCRIPT}`
export REPO_DIR=`readl -m ${SCRIPTS_DIR}/../../../..`
export INSTALL_DIR=`readl -m ${REPO_DIR}/deploy`

if [ -z "$RENDER_CLIENT_JAR" ]
then
    export RENDER_CLIENT_JAR=`readl -m ${REPO_DIR}/render-ws-java-client/target/render-ws-java-client-*-standalone.jar`
fi
if [ -z "$RENDER_JAVA_HOME" ]; then
    export JAVA_HOME=`readl -m ${REPO_DIR}/deploy/jdk*`
else
    export JAVA_HOME="$RENDER_JAVA_HOME"
fi

export BASE_JAVA_COMMAND="${JAVA_HOME}/bin/java -cp ${RENDER_CLIENT_JAR}"

# request memory up-front and use serial garbage collector to keep GC threads from taking over cluster node
export JAVA_MEMORY="${1:-1G}"
export JAVA_OPTS="-Xms${JAVA_MEMORY} -Xmx${JAVA_MEMORY} -Djava.awt.headless=true -XX:+UseSerialGC"

function runJavaCommandAndExit {
  COMMAND="${BASE_JAVA_COMMAND} ${JAVA_OPTS} $*"
  echo """
  Running: ${COMMAND}

"""
  ${COMMAND}
  exit $?
}
