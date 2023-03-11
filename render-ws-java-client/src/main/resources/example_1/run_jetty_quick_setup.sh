#!/bin/bash

# https://www.eclipse.org/jetty/documentation/jetty-10/operations-guide/index.html#og-quick-setup
set -e

DEPLOY_DIR=$(readlink -m .)
JETTY_HOME=$(readlink -m "${DEPLOY_DIR}/jetty-home-*")
export JETTY_HOME
export JETTY_BASE="${DEPLOY_DIR}/jetty-base-quick"

JAVA_HOME=$(readlink -m "${DEPLOY_DIR}/*jdk*")
export JAVA_HOME

export PATH="${JAVA_HOME}/bin:${PATH}"
export JAVA="${JAVA_HOME}/bin/java"

mkdir -p "${JETTY_BASE}"
cd "${JETTY_BASE}"

java -jar "${JETTY_HOME}/start.jar" --add-module=server,http
 
"${JETTY_HOME}/bin/jetty.sh" start
