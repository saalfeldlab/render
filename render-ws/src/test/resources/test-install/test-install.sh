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
export JAVA_HOME=`readlink -m ./deploy/jdk*`
mvn package

echo """
# --------------------------------------------------------------------
# 5. Deploy Web Service
"""

# assumes current directory is still the cloned render repository root (./render)
cp render-ws/target/render-ws-*.war deploy/jetty_base/webapps/render-ws.war

echo """
# --------------------------------------------------------------------
# 6. Install MongoDB 3.2
"""

#    These instructions were taken from https://docs.mongodb.com/v3.2/tutorial/install-mongodb-on-ubuntu/

# import MongoDB public GPG key
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv EA312927

# create a list file for MongoDB
echo "deb http://repo.mongodb.org/apt/ubuntu xenial/mongodb-org/3.2 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-3.2.list

# reload local package database
sudo apt-get update

# install default MongoDB (2.6) packages
# NOTE: This step is not documented on the MongoDB web site, but for some reason is required to avoid "mongod: unrecognized service" errors later
#       See https://github.com/Microsoft/WSL/issues/1822 for details
sudo apt-get install -y mongodb

# install MongoDB packages (this should also start the mongod process)
sudo apt-get install -y mongodb-org=3.2.18 mongodb-org-server=3.2.18 mongodb-org-shell=3.2.18 mongodb-org-mongos=3.2.18 mongodb-org-tools=3.2.18

# Pin specific version of MongoDB
echo "mongodb-org hold" | sudo dpkg --set-selections
echo "mongodb-org-server hold" | sudo dpkg --set-selections
echo "mongodb-org-shell hold" | sudo dpkg --set-selections
echo "mongodb-org-mongos hold" | sudo dpkg --set-selections
echo "mongodb-org-tools hold" | sudo dpkg --set-selections

# Create systemd service file
# NOTE: This file has to be named mongodb.service (instead of mongod.service) for some reason
sudo echo """
[Unit]
Description=High-performance, schema-free document-oriented database
After=network.target
Documentation=https://docs.mongodb.org/manual

[Service]
User=mongodb
Group=mongodb
ExecStart=/usr/bin/mongod --quiet --config /etc/mongod.conf

[Install]
WantedBy=multi-user.target
""" > /lib/systemd/system/mongodb.service

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