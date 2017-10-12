#!/bin/bash

ABSOLUTE_ENV_SCRIPT=`readlink -m $0`

export SCRIPTS_DIR=`dirname ${ABSOLUTE_ENV_SCRIPT}`
export REPO_DIR=`readlink -m ${SCRIPTS_DIR}/../../../..`
export INSTALL_DIR=`readlink -m ${REPO_DIR}/deploy`

if [ -z "$RENDER_CLIENT_JAR" ]
then
    export RENDER_CLIENT_JAR=`readlink -m ${REPO_DIR}/render-ws-java-client/target/render-ws-java-client-*-standalone.jar`
fi
if [ -z "$RENDER_JAVA_HOME" ]
then
    export JAVA_HOME=`readlink -m ${REPO_DIR}/deploy/jdk*`
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
