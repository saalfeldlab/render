#!/bin/bash

# Run this script to deploy a Jetty render-ws docker export locally on a Janelia VM.

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPTS_DIR=$(dirname "${ABSOLUTE_SCRIPT}")
DEFAULT_EXPORT_DIR=$(dirname "${SCRIPTS_DIR}")

USAGE="USAGE: ${0} [jetty gigibyte memory] [install dir] [export dir]

       $0 3
       $0 3 /opt/local
       $0 3 /opt/local /opt/local/docker_exports/export-ibeam_msem-20230404_1322-8c5d66be"

JETTY_GIGI_MEMORY="${1:-13}"
INSTALL_PARENT_DIR="${2:-/opt/local}"
FULL_EXPORT_DIR="${3:-${DEFAULT_EXPORT_DIR}}"

RENDER_WS_WAR_FILE="${FULL_EXPORT_DIR}/jetty_base/webapps/render-ws.war"
if [ ! -f "${RENDER_WS_WAR_FILE}" ]; then
  echo "
ERROR: ${RENDER_WS_WAR_FILE} not found

${USAGE}
"
fi

AVAILABLE_MEMORY=$(free --gibi | awk '/^Mem/{print $3 + $4}')
if (( AVAILABLE_MEMORY < JETTY_GIGI_MEMORY )); then
  echo "
ERROR: requested ${JETTY_GIGI_MEMORY}g for jetty but this machine only has ${AVAILABLE_MEMORY}g ( see free -g )

${USAGE}
"
  exit 1
fi

JDK_VARS="${SCRIPTS_DIR}/jdk-vars.sh"
if [ -f "${JDK_VARS}" ]; then
  # shellcheck source=src/main/scripts/jdk-vars.sh
  source "${JDK_VARS}"
else
  echo "ERROR: ${JDK_VARS} not found!"
fi

# ----------------------------
# install JDK

cd "${INSTALL_PARENT_DIR}" || exit 1
JAVA="${INSTALL_PARENT_DIR}/${JDK_VERSION}/bin/java"

if [ -f "${JAVA}" ]; then
  echo "found ${JAVA}, skipping JDK install"
else
  echo "installing JDK from ${JDK_URL} ..."
  sudo curl "${JDK_URL}" | sudo tar xz
fi

# ----------------------------
# install Jetty stuff
echo "setting up Jetty in ${INSTALL_PARENT_DIR} ..."

# change permissions on install directory so that jetty_home and jetty_base symbolic links can be created
sudo chmod 777 "${INSTALL_PARENT_DIR}"

JETTY_HOME="${INSTALL_PARENT_DIR}/jetty_home"
JETTY_BASE="${INSTALL_PARENT_DIR}/jetty_base"
JETTY_LOGS="${JETTY_BASE}/logs"
JETTY_WORK="${JETTY_BASE}/work"

# remove old jetty_home and jetty_base symbolic links if they exist
rm -f "${JETTY_HOME}" "${JETTY_BASE}"

# add links to new export
ln -sf "${FULL_EXPORT_DIR}"/jetty_home "${JETTY_HOME}"
ln -sf "${FULL_EXPORT_DIR}"/jetty_base "${JETTY_BASE}"

# restore permissions on install directory now that that jetty_home and jetty_base symbolic links are created
sudo chmod 755 "${INSTALL_PARENT_DIR}"

# create work directory
sudo mkdir -p "${JETTY_WORK}"

echo "setting up Jetty to run as service with jboss user ..."

# change ownership to legacy jbossadmin/jboss setup to ensure access to network mounted images
sudo chown -R jbossadmin:jboss "${FULL_EXPORT_DIR}"
sudo chown -R jboss:jboss "${JETTY_LOGS}" "${JETTY_WORK}"
sudo chown jboss "${JETTY_BASE}"/resources/render-db.properties
sudo chmod 600 "${JETTY_BASE}"/resources/render-db.properties

# change setuid config to start-up server as jboss user
sudo sed -i '
  s/^# jetty.setuid/jetty.setuid/
  s/Name=jetty/Name=jboss/
' "${JETTY_BASE}"/start.d/setuid.ini

# make render-ws.war writable to all to simplify future updates (obviously, don't do this if you are worried about security)
sudo chmod 666 "${JETTY_BASE}"/webapps/render-ws.war

JETTY_DEFAULT_ENV_FILE="/etc/default/jetty"
echo "
creating ${JETTY_DEFAULT_ENV_FILE} with:
"

sudo echo "JAVA=${INSTALL_PARENT_DIR}/${JDK_VERSION}/bin/java
JAVA_OPTIONS=\"-Xms${JETTY_GIGI_MEMORY}g -Xmx${JETTY_GIGI_MEMORY}g -server -Djava.awt.headless=true\"
JETTY_HOME=${JETTY_HOME}
JETTY_BASE=${JETTY_BASE}
JETTY_RUN=${JETTY_LOGS}
JETTY_STATE=${JETTY_LOGS}/jetty.state" | sudo tee "${JETTY_DEFAULT_ENV_FILE}"

# setup jetty as a service that gets automatically restarted after reboots
sudo cp "${JETTY_BASE}"/jetty.service /etc/systemd/system/jetty.service
sudo systemctl enable jetty

echo "
enabled jetty service to be restarted after reboots

to start service, run:
  sudo systemctl start jetty
"
