# ======================================================================================
# Builds an all-inclusive fat Docker image that contains:
#
# 1. the tools needed to build render web services and clients
# 2. the render source code and dependent libraries
# 3. a Jetty web server
# 4. a MongoDB server
#
# The steps in this Dockerfile should match the basic installation steps listed at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md
#
# The default endpoint for the image runs a script (run_example_steps.sh)
# that contains the render web services example steps listed at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws-example.md
#
# To build the example:
#   copy this file to your local directory (save it as Dockerfile)
#   docker build -t render_example_1:latest .
#
# To run an example container:
#   docker run -it --rm render_example_1:latest
#
# To launch interactive bash shell within an example container:
#   docker run -it --entrypoint /bin/bash --rm render_example_1:latest
# ======================================================================================

FROM ubuntu:16.04
LABEL maintainer="Eric Trautman <trautmane@janelia.hhmi.org>"

RUN apt-get update
RUN apt-get -y upgrade

# From

# 1: Install Git and Maven
RUN apt-get install -y git \
  maven \
  curl

# 2. Clone the Repository
WORKDIR /var/www/
RUN git clone https://github.com/saalfeldlab/render.git

# 3. Install JDK and Jetty
WORKDIR /var/www/render/

# Uncomment next line to switch to different source branch
# RUN git checkout hierarchical

RUN ./render-ws/src/main/scripts/install.sh

# 4. Build the Render Modules
RUN { echo 'JAVA_HOME="$(readlink -m ./deploy/jdk*)"'; } >> ~/.mavenrc
RUN mvn --version; mvn -Dproject.build.sourceEncoding=UTF-8 package

# 5. Deploy Web Service
RUN cp render-ws/target/render-ws-*.war deploy/jetty_base/webapps/render-ws.war

# 6. Install MongoDB 4.0.6

# needed for access to https mongodb resources
RUN apt-get install apt-transport-https

# steps from https://docs.mongodb.com/manual/tutorial/install-mongodb-on-ubuntu/
RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 9DA31620334BD75D9DCB49F368818C72E52529D4 && \
    echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu xenial/mongodb-org/4.0 multiverse" | tee /etc/apt/sources.list.d/mongodb-org-4.0.list && \
    apt-get update && \
    apt-get install -y mongodb && \
    apt-get install -y mongodb-org=4.0.6 mongodb-org-server=4.0.6 mongodb-org-shell=4.0.6 mongodb-org-mongos=4.0.6 mongodb-org-tools=4.0.6 && \
    echo "mongodb-org hold" | dpkg --set-selections && \
    echo "mongodb-org-server hold" | dpkg --set-selections && \
    echo "mongodb-org-shell hold" | dpkg --set-selections && \
    echo "mongodb-org-mongos hold" | dpkg --set-selections && \
    echo "mongodb-org-tools hold" | dpkg --set-selections

# NOTE: This file has to be named mongodb.service (instead of mongod.service) for some reason
RUN curl -o /lib/systemd/system/mongodb.service "https://raw.githubusercontent.com/mongodb/mongo/v4.0/rpm/mongod.service"

# expose the render port
EXPOSE 8080

CMD ./render-ws-java-client/src/main/resources/example_1/run_example_steps.sh