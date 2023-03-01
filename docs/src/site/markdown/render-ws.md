# Render Web Services
The Render Web Services module provides [level 2 REST APIs] to persisted collections of tile and 
transform specifications (see [data model]).  The [Render Web Services API documentation] identifies 
the specific supported APIs.     

## Deployment Architecture
The Render Web Services module is packaged as a Java web application archive ([war file]) deployed 
on a [Jetty] web server/container.  It connects to a [MongoDB] database where tile and transform 
specifications are persisted (see [data model]).

The web server and database components can be run on the same machine for small scale processing or 
evaluation purposes.  For large scale processing needs, each component should be deployed on separate 
machines that are tuned for their specific purpose.

## Basic Installation
These installation instructions cover setup of an evaluation instance with a collocated web server and 
database running on Ubuntu 22.04.

> Note: In lieu of running these steps manually, you may prefer to use a [Docker deployment] instead.
Finally, if you'd like to run this exact installation process along with the [Render Web Services Example] in Docker, 
you can use this [Dockerfile](../../../../render-ws-java-client/src/main/resources/example_1/Dockerfile).  

### 1. Install Git and Maven
```bash
sudo apt-get install -y git maven
```
### 2. Clone the Repository
```bash
git clone https://github.com/saalfeldlab/render.git
```
### 3. Install JDK and Jetty
```bash
# assumes cloned render repository is in ./render
cd ./render
./render-ws/src/main/scripts/install.sh
```
### 4. Build the Render Modules
```bash
# assumes current directory is still the cloned render repository root (./render)
export JAVA_HOME=`readlink -m ./deploy/*jdk*`
mvn package
```
### 5. Deploy Web Service
```bash
# assumes current directory is still the cloned render repository root (./render)
cp render-ws/target/render-ws-*.war deploy/jetty_base/webapps/render-ws.war
```
### 6. Install MongoDB 6.0.4
> These instructions were taken from <https://www.mongodb.com/docs/v6.0/tutorial/install-mongodb-on-ubuntu/#install-mongodb-community-edition>

```bash
# needed for access to https mongodb resources
apt-get install -y apt-transport-https gnupg wget

# steps from https://www.mongodb.com/docs/v6.0/tutorial/install-mongodb-on-ubuntu/#install-mongodb-community-edition
wget -qO - https://www.mongodb.org/static/pgp/server-6.0.asc | sudo apt-key add -
echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/6.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-6.0.list
sudo apt-get update

# note: you will be prompted to select a geographic area and city for tzdata
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
```

### 7. Start MongoDB
```bash
# NOTE: The service has to be mongodb (instead of mongod) for some reason.
sudo service mongodb start
```

### 8. Start Jetty
```bash
# assumes current directory is still the cloned render repository root (./render)
deploy/jetty_base/jetty_wrapper.sh start
```

## Using the Installation
The [Render Web Services Example] workflow steps demonstrate how this installation can be used for a 
small example project.


  [data model]: <data-model.md>
  [Docker deployment]: <render-ws-docker.md>
  [Jetty]: <https://eclipse.org/jetty/>
  [level 2 REST APIs]: <http://martinfowler.com/articles/richardsonMaturityModel.html>
  [MongoDB]: <https://www.mongodb.org/>
  [Render Web Services API documentation]: <render-ws-api/render-ws-api.md>
  [Render Web Services Example]: <render-ws-example.md>
  [Swagger UI]: <http://swagger.io/swagger-ui/>
  [war file]: <https://docs.oracle.com/javaee/7/tutorial/packaging003.htm>
