#!/bin/bash

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`
REPO_DIR=`readlink -m ${SCRIPTS_DIR}/../../../..`
INSTALL_DIR=`readlink -m ${REPO_DIR}/deploy`

JAR=`readlink -m ${REPO_DIR}/render-ws-java-client/target/render-ws-java-client-*-standalone.jar`
JAVA_HOME=`readlink -m ${REPO_DIR}/deploy/jdk*`

COMMAND="${JAVA_HOME}/bin/java -cp ${JAR} org.janelia.render.client.StackClient $*"
echo """
  Running: ${COMMAND}

"""
${COMMAND}
exit $?