#!/bin/bash

JDK_VERSION="jdk1.8.0_73"
JETTY_VERSION="9.3.7.v20160115"
JETTY_DIST="jetty-distribution-${JETTY_VERSION}"
LOGBACK_VERSION="1.1.5"
SLF4J_VERSION="1.7.16"

# URLs for JDK 8, Jetty 9, SLF4J 1.7, and Logback 1.1
JDK_URL="http://download.oracle.com/otn-pub/java/jdk/8u73-b02/jdk-8u73-linux-x64.tar.gz"
JETTY_URL="http://download.eclipse.org/jetty/${JETTY_VERSION}/dist/${JETTY_DIST}.tar.gz"
SLF4J_URL="http://www.slf4j.org/dist/slf4j-${SLF4J_VERSION}.tar.gz"
LOGBACK_URL="http://logback.qos.ch/dist/logback-${LOGBACK_VERSION}.tar.gz"

ABSOLUTE_SCRIPT=`readlink -m $0`
SCRIPTS_DIR=`dirname ${ABSOLUTE_SCRIPT}`
REPO_DIR=`readlink -m ${SCRIPTS_DIR}/../../../..`
INSTALL_DIR=`readlink -m ${REPO_DIR}/deploy`
if (( $# > 0 )); then
  INSTALL_DIR=`readlink -m ${1}`
fi

echo """
setup install area ${INSTALL_DIR} ...
"""
mkdir -p ${INSTALL_DIR}
cd ${INSTALL_DIR}


echo """
download JDK, Jetty, SLF4J, and Logback ...
"""
curl -j -k -L -H "Cookie: oraclelicense=accept-securebackup-cookie" ${JDK_URL} | tar xz
curl ${JETTY_URL} | tar xz
curl ${SLF4J_URL} | tar xz
curl ${LOGBACK_URL} | tar xz


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

echo """
completed installation in ${INSTALL_DIR}

Jetty wrapper script is ${JETTY_WRAPPER_SCRIPT}
"""
