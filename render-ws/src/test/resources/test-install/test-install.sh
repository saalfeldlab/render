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
# TODO: 2204 - remove switch to branch once testing is done
cd ./render
git fetch origin
git switch ubuntu-22-04-and-mongodb-6-0

echo """
# --------------------------------------------------------------------
# 3. Install JDK and Jetty
"""

# assumes cloned render repository is in ./render
# TODO: 2204 - uncomment next line once testing is done
#cd ./render
./render-ws/src/main/scripts/install.sh

echo """
# --------------------------------------------------------------------
# 4. Build the Render Modules
"""

# assumes current directory is still the cloned render repository root (./render)
JAVA_HOME=$(readlink -m ./deploy/*jdk*)
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
sudo apt-get install -y apt-transport-https gnupg wget

# steps from https://www.mongodb.com/docs/v6.0/tutorial/install-mongodb-on-ubuntu/#install-mongodb-community-edition
wget -qO - https://www.mongodb.org/static/pgp/server-6.0.asc | sudo apt-key add -
echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/6.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-6.0.list
sudo apt-get update

# this line is not in the MongoDB steps, but is needed to skip interactive tzdata prompt ( see https://stackoverflow.com/a/44333806 )
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tzdata
# this line is not in the MongoDB steps, but is needed to workaround 'mongodb-org-server.postinst: systemctl: not found' error
sudo ln -s /bin/true /bin/systemctl

sudo apt-get install -y mongodb-org=6.0.4 mongodb-org-database=6.0.4 mongodb-org-server=6.0.4 mongodb-org-mongos=6.0.4 mongodb-org-tools=6.0.4

echo "mongodb-org hold" | sudo dpkg --set-selections
echo "mongodb-org-database hold" | sudo dpkg --set-selections
echo "mongodb-org-server hold" | sudo dpkg --set-selections
echo "mongodb-mongosh hold" | sudo dpkg --set-selections
echo "mongodb-org-mongos hold" | sudo dpkg --set-selections
echo "mongodb-org-tools hold" | sudo dpkg --set-selections

# this line is not in the MongoDB steps, but is needed to workaround 'mongodb-org-server.postinst: systemctl: not found' error
sudo rm /bin/systemctl

echo """
# --------------------------------------------------------------------
# 7. Start MongoDB
"""

# launch mongodb in background (can't run service in Docker container)
sudo -u mongodb /usr/bin/mongod -f /etc/mongod.conf &

echo """
# --------------------------------------------------------------------
# 8. Start Jetty
"""

# assumes current directory is still the cloned render repository root (./render)
deploy/jetty_base/jetty_wrapper.sh start