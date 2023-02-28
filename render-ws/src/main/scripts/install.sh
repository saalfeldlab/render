#!/bin/bash

JDK_VERSION="zulu8.50.0.51-ca-jdk8.0.275-linux_x64"
JETTY_VERSION="9.4.43.v20210629" # NOTE: jetty version should be kept in sync with values in render/render-ws/pom.xml and render/Dockerfile
JETTY_DIST="jetty-distribution-${JETTY_VERSION}"

# URL for JDK 8
# You can find latest Linux x64 download links at:
# https://www.azul.com/downloads/?version=java-8-lts&os=linux&architecture=x86-64-bit&package=jdk&show-old-builds=true
JDK_URL="https://cdn.azul.com/zulu/bin/${JDK_VERSION}.tar.gz"

# URLs for Jetty 9, SLF4J 1.7, Logback 1.1, and Swagger 2.1
MAVEN_CENTRAL_URL="https://repo1.maven.org"
JETTY_URL="${MAVEN_CENTRAL_URL}/maven2/org/eclipse/jetty/jetty-distribution/${JETTY_VERSION}/${JETTY_DIST}.tar.gz"

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`
REPO_DIR=`readlink -m ${SCRIPTS_DIR}/../../../..`
INSTALL_DIR=`readlink -m ${REPO_DIR}/deploy`
if (( $# > 0 )); then
  INSTALL_DIR=`readlink -m ${1}`
fi

function exitIfDirectoryHasSpaces {

  CONTEXT="$1"
  DIR_TO_CHECK="$2"

  if (( `echo "${DIR_TO_CHECK}" | wc -w` > 1 )); then
    echo """
The ${CONTEXT} directory

  ${DIR_TO_CHECK}

contains one or more spaces which will break assumptions in this fragile script.
To run this script, please move the ${CONTEXT} directory into a path without spaces.
"""
    exit 1
  fi

}

exitIfDirectoryHasSpaces "base render clone" "${REPO_DIR}"
exitIfDirectoryHasSpaces "install" "${INSTALL_DIR}"

echo """
setup install area ${INSTALL_DIR} ...
"""
mkdir -p ${INSTALL_DIR}
cd ${INSTALL_DIR}


echo """
download JDK and Jetty ...
"""
curl "${JDK_URL}" | tar xz
curl ${JETTY_URL} | tar xz

echo """
configure Jetty ...
"""
export JETTY_BASE="${INSTALL_DIR}/jetty_base"
mkdir -p ${JETTY_BASE}
cd ${JETTY_BASE}

mkdir -p etc lib/ext lib/logging modules resources webapps work

cp ${SCRIPTS_DIR}/jetty/etc/* etc
cp ${SCRIPTS_DIR}/jetty/modules/* modules
cp ${SCRIPTS_DIR}/jetty/resources/* resources
cp ${SCRIPTS_DIR}/jetty/webapps/*.xml webapps

cp -r ${SCRIPTS_DIR}/jetty/start.d .

# remove setuid module since non-container installs may not be running as root
rm start.d/setuid.ini

# hack to fix logback access issue 1052
cp ${SCRIPTS_DIR}/jetty/lib/ext/*.jar lib/ext

echo """
download and install SLF4J, Logback, and Swagger UI ...
"""
${SCRIPTS_DIR}/jetty/configure_web_server.sh

# setup start script
JETTY_HOME="${INSTALL_DIR}/${JETTY_DIST}"
JAVA_HOME="${INSTALL_DIR}/${JDK_VERSION}"
JETTY_WRAPPER_SCRIPT="${JETTY_BASE}/jetty_wrapper.sh"

sed "
  s~/opt/local/jetty_home~${JETTY_HOME}~
  s~/opt/local/jetty_base~${JETTY_BASE}~
  s~/misc/sc/jdks/8.0.275.fx-zulu~${JAVA_HOME}~
" ${SCRIPTS_DIR}/jetty/jetty_wrapper.sh > ${JETTY_WRAPPER_SCRIPT}

chmod 755 ${JETTY_WRAPPER_SCRIPT}

echo """
setup example data ...
"""

# setup example data with install path
CLIENT_RESOURCES_DIR="${REPO_DIR}/render-ws-java-client/src/main/resources"
EXAMPLE_1_SOURCE_DIR="${CLIENT_RESOURCES_DIR}/example_1"
EXAMPLE_1_INSTALL_DIR="${REPO_DIR}/examples/example_1"

if [ -d ${EXAMPLE_1_SOURCE_DIR} ]; then
  mkdir -p ${EXAMPLE_1_INSTALL_DIR}

  cd ${EXAMPLE_1_SOURCE_DIR}
  for JSON_FILE in *.json; do
    sed '
      s@/tmp@'"${CLIENT_RESOURCES_DIR}"'@
    ' ${JSON_FILE} > ${EXAMPLE_1_INSTALL_DIR}/${JSON_FILE}
  done
else
  echo "no example source data found"
fi

echo """
Completed installation in ${INSTALL_DIR}:

$(ls -alh "${INSTALL_DIR}")

Jetty wrapper script is ${JETTY_WRAPPER_SCRIPT}
"""
