#!/bin/bash

# Run this script to deploy a Jetty render-ws docker export locally on a Janelia VM.

set -e

ABSOLUTE_SCRIPT=$(readlink -m "${0}")
SCRIPTS_DIR=$(dirname "${ABSOLUTE_SCRIPT}")

if (( $# < 1 )); then
  echo "
USAGE: ${0} <export dir> [install dir] [jetty memory]
            e.g. /opt/local/docker_exports/export-ibeam_msem-20230404_1322-8c5d66be /opt/local 15g
"
  exit 1
fi

FULL_EXPORT_DIR="${1}" # /opt/local/docker_exports/export-ibeam_msem-20230404_1322-8c5d66be
INSTALL_PARENT_DIR="${2:-/opt/local}"
JETTY_MEMORY="${3:15g}"

RENDER_WS_WAR_FILE="${FULL_EXPORT_DIR}/jetty_base/webapps/render-ws.war"
if [ ! -f "${RENDER_WS_WAR_FILE}" ]; then
  echo "ERROR: ${RENDER_WS_WAR_FILE} not found"
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

sudo curl "${JETTY_URL}" | sudo tar xz

# ----------------------------
# install Jetty stuff

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

sudo echo "JAVA=${INSTALL_PARENT_DIR}/${JDK_VERSION}/bin/java
JAVA_OPTIONS=\"-Xms${JETTY_MEMORY}g -Xmx${JETTY_MEMORY}g -server -Djava.awt.headless=true\"
JETTY_HOME=${JETTY_HOME}
JETTY_BASE=${JETTY_BASE}
JETTY_RUN=${JETTY_LOGS}
JETTY_STATE=${JETTY_LOGS}/jetty.state" | sudo tee /etc/default/jetty

# setup jetty as a service that gets automatically restarted after reboots
sudo cp "${JETTY_BASE}"/jetty.service /etc/systemd/system/jetty.service
sudo systemctl enable jetty
