#!/bin/sh

# ======================================================================================
# Runs basic installation instructions documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

set -e


echo """
# --------------------------------------------------------------------
# 1. Install Git and Maven
"""

sudo apt-get install -y git maven

echo """
# --------------------------------------------------------------------
# 2. Clone the Repository
"""

git clone https://github.com/saalfeldlab/render.git

echo """
# --------------------------------------------------------------------
# 3. Install JDK and Jetty
"""

# assumes cloned render repository is in ./render
cd ./render
./render-ws/src/main/scripts/install.sh

echo """
# --------------------------------------------------------------------
# 4. Build the Render Modules
"""

# assumes current directory is still the cloned render repository root (./render)
JAVA_HOME=$(readlink -m ./deploy/jdk*)
export JAVA_HOME
echo "JAVA_HOME is ${JAVA_HOME}"
mvn package

echo """
# --------------------------------------------------------------------
# 5. Deploy Web Service
"""

# assumes current directory is still the cloned render repository root (./render)
cp render-ws/target/render-ws-*.war deploy/jetty_base/webapps/render-ws.war

echo """
# --------------------------------------------------------------------
# 6. Install MongoDB 6.0.4
"""

# These instructions were taken from https://www.mongodb.com/docs/v6.0/tutorial/install-mongodb-on-ubuntu/#install-mongodb-community-edition

# needed for access to https mongodb resources
sudo apt-get install apt-transport-https gnupg

# steps from https://www.mongodb.com/docs/v6.0/tutorial/install-mongodb-on-ubuntu/#install-mongodb-community-edition
wget -qO - https://www.mongodb.org/static/pgp/server-6.0.asc | sudo apt-key add -
echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/6.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-6.0.list
sudo apt-get update

sudo apt-get install -y mongodb-org=6.0.4 mongodb-org-database=6.0.4 mongodb-org-server=6.0.4 mongodb-org-mongos=6.0.4 mongodb-org-tools=6.0.4

echo "mongodb-org hold" | sudo dpkg --set-selections
echo "mongodb-org-database hold" | sudo dpkg --set-selections
echo "mongodb-org-server hold" | sudo dpkg --set-selections
echo "mongodb-mongosh hold" | sudo dpkg --set-selections
echo "mongodb-org-mongos hold" | sudo dpkg --set-selections
echo "mongodb-org-tools hold" | sudo dpkg --set-selections

# Create systemd service file
# NOTE: This file has to be named mongodb.service (instead of mongod.service) for some reason
sudo curl -o /lib/systemd/system/mongodb.service "https://raw.githubusercontent.com/mongodb/mongo/v6.0/rpm/mongod.service"

echo """
# --------------------------------------------------------------------
# 7. Start MongoDB
"""

# NOTE: The service has to be mongodb (instead of mongod) for some reason.
sudo service mongodb start

echo """
# --------------------------------------------------------------------
# 8. Start Jetty
"""

# assumes current directory is still the cloned render repository root (./render)
deploy/jetty_base/jetty_wrapper.sh start