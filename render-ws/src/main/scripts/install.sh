#!/bin/bash

JDK_VERSION="jdk1.8.0_131"
JETTY_VERSION="9.3.7.v20160115"
JETTY_DIST="jetty-distribution-${JETTY_VERSION}"
LOGBACK_VERSION="1.1.5"
SLF4J_VERSION="1.7.16"
SWAGGER_UI_VERSION="2.1.4"

# URL for JDK 8
# This occasionally needs to be updated when Oracle moves things around.
# You can find latest Linux x64 download link at:
# http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html
JDK_URL="http://download.oracle.com/otn-pub/java/jdk/8u131-b11/d54c1d3a095b4ff2b6607d096fa80163/jdk-8u131-linux-x64.tar.gz"

# URLs for Jetty 9, SLF4J 1.7, Logback 1.1, and Swagger 2.1
JETTY_URL="http://central.maven.org/maven2/org/eclipse/jetty/jetty-distribution/${JETTY_VERSION}/${JETTY_DIST}.tar.gz"
SLF4J_URL="https://www.slf4j.org/dist/slf4j-${SLF4J_VERSION}.tar.gz"
LOGBACK_URL="https://logback.qos.ch/dist/logback-${LOGBACK_VERSION}.tar.gz"
SWAGGER_UI_URL="https://github.com/swagger-api/swagger-ui/archive/v${SWAGGER_UI_VERSION}.tar.gz"

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
download JDK, Jetty, SLF4J, Logback, and Swagger UI ...
"""
curl -j -k -L -H "Cookie: oraclelicense=accept-securebackup-cookie" ${JDK_URL} | tar xz
curl ${JETTY_URL} | tar xz
curl ${SLF4J_URL} | tar xz
curl ${LOGBACK_URL} | tar xz
curl -L ${SWAGGER_UI_URL} | tar xz


echo """
configure Jetty ...
"""
JETTY_BASE="${INSTALL_DIR}/jetty_base"
mkdir -p ${JETTY_BASE}
cd ${JETTY_BASE}

mkdir -p etc lib/ext lib/logging logs modules resources webapps work

cp ${SCRIPTS_DIR}/jetty/start.ini .
cp ${SCRIPTS_DIR}/jetty/etc/* etc
cp ${SCRIPTS_DIR}/jetty/logs/* logs
cp ${SCRIPTS_DIR}/jetty/modules/* modules
cp ${SCRIPTS_DIR}/jetty/resources/* resources

# setup logging components
JETTY_LIB_LOGGING="${JETTY_BASE}/lib/logging"

cp ${INSTALL_DIR}/logback-${LOGBACK_VERSION}/logback-access-${LOGBACK_VERSION}.jar ${JETTY_LIB_LOGGING}
cp ${INSTALL_DIR}/logback-${LOGBACK_VERSION}/logback-classic-${LOGBACK_VERSION}.jar ${JETTY_LIB_LOGGING}
cp ${INSTALL_DIR}/logback-${LOGBACK_VERSION}/logback-core-${LOGBACK_VERSION}.jar ${JETTY_LIB_LOGGING}

cp ${INSTALL_DIR}/slf4j-${SLF4J_VERSION}/jcl-over-slf4j-${SLF4J_VERSION}.jar ${JETTY_LIB_LOGGING}
cp ${INSTALL_DIR}/slf4j-${SLF4J_VERSION}/jul-to-slf4j-${SLF4J_VERSION}.jar ${JETTY_LIB_LOGGING}
cp ${INSTALL_DIR}/slf4j-${SLF4J_VERSION}/log4j-over-slf4j-${SLF4J_VERSION}.jar ${JETTY_LIB_LOGGING}
cp ${INSTALL_DIR}/slf4j-${SLF4J_VERSION}/slf4j-api-${SLF4J_VERSION}.jar ${JETTY_LIB_LOGGING}

# hack to fix logback access issue 1052
cp ${SCRIPTS_DIR}/jetty/lib/logging/*.jar ${JETTY_LIB_LOGGING}


# setup start script
JETTY_HOME="${INSTALL_DIR}/${JETTY_DIST}"
JAVA_HOME="${INSTALL_DIR}/${JDK_VERSION}"
JETTY_WRAPPER_SCRIPT="${JETTY_BASE}/jetty_wrapper.sh"

sed "
  s~/opt/local/jetty_home~${JETTY_HOME}~
  s~/opt/local/jetty_base~${JETTY_BASE}~
  s~/opt/local/jdk1.8.0_45~${JAVA_HOME}~
" ${SCRIPTS_DIR}/jetty/jetty_wrapper.sh > ${JETTY_WRAPPER_SCRIPT}

chmod 755 ${JETTY_WRAPPER_SCRIPT}

# deploy Swagger UI to webapps
SWAGGER_UI_DEPLOY_DIR="${JETTY_BASE}/webapps/swagger-ui"

cp -r ${INSTALL_DIR}/swagger-ui-${SWAGGER_UI_VERSION}/dist ${SWAGGER_UI_DEPLOY_DIR}

# modify index.html to dynamically derive the swagger.json URL and sort functions by method
${SCRIPTS_DIR}\fix_swagger.sh

echo """
setup example data ...
"""

# setup example data with install path
CLIENT_RESOURCES_DIR="${REPO_DIR}/render-ws-java-client/src/main/resources"
EXAMPLE_1_SOURCE_DIR="${CLIENT_RESOURCES_DIR}/example_1"
EXAMPLE_1_INSTALL_DIR="${REPO_DIR}/examples/example_1"

mkdir -p ${EXAMPLE_1_INSTALL_DIR}

cd ${EXAMPLE_1_SOURCE_DIR}
for JSON_FILE in *.json; do
  sed '
    s@/tmp@'"${CLIENT_RESOURCES_DIR}"'@
  ' ${JSON_FILE} > ${EXAMPLE_1_INSTALL_DIR}/${JSON_FILE}
done


echo """
completed installation in ${INSTALL_DIR}

Jetty wrapper script is ${JETTY_WRAPPER_SCRIPT}
"""
