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
#   cd ${RENDER_REPO_ROOT_DIR}
#   docker build -f render-ws-java-client/src/main/resources/example_1/Dockerfile \
#          --no-cache -t janelia-render:latest-example .
#
# To run an example container:
#   docker run -it --rm janelia-render:latest-example
#
# To launch interactive bash shell within an example container:
#   docker run -it --entrypoint /bin/bash --rm janelia-render:latest-example
# ======================================================================================

FROM ubuntu:22.04
LABEL maintainer="Eric Trautman <trautmane@janelia.hhmi.org>"

RUN apt-get update
RUN apt-get -y upgrade

# From

# 1: Install Git and Maven (curl and vim also added here for convenience)
RUN apt-get install -y git maven curl vim

# 2. Clone the Repository
WORKDIR /var/www/
RUN git clone https://github.com/saalfeldlab/render.git

# 3. Install JDK and Jetty
WORKDIR /var/www/render/

# Uncomment next line to switch to different source branch
# RUN git checkout ibeam_msem

RUN ./render-ws/src/main/scripts/install.sh

# 4. Build the Render Modules
RUN { echo 'JAVA_HOME="$(readlink -m ./deploy/*jdk*)"'; } >> ~/.mavenrc
RUN mvn --version; mvn --batch-mode -Dproject.build.sourceEncoding=UTF-8 package

# 5. Deploy Web Service
RUN cp render-ws/target/render-ws-*.war deploy/jetty_base/webapps/render-ws.war

# 6. Install MongoDB 6.0.4

# needed for access to https mongodb resources
RUN apt-get -y install apt-transport-https gnupg wget sudo

# this line is not in the MongoDB steps, but is needed to skip interactive tzdata prompt ( see https://stackoverflow.com/a/44333806 )
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tzdata

# steps from https://www.mongodb.com/docs/v6.0/tutorial/install-mongodb-on-ubuntu/#install-mongodb-community-edition
RUN wget -qO - https://www.mongodb.org/static/pgp/server-6.0.asc | sudo apt-key add - && \
    sudo ln -s /bin/true /bin/systemctl && \
    echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/6.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-6.0.list && \
    sudo apt-get update && \
    sudo apt-get install -y mongodb-org=6.0.4 mongodb-org-database=6.0.4 mongodb-org-server=6.0.4 mongodb-org-mongos=6.0.4 mongodb-org-tools=6.0.4 && \
    echo "mongodb-org hold" | sudo dpkg --set-selections && \
    echo "mongodb-org-database hold" | sudo dpkg --set-selections && \
    echo "mongodb-org-server hold" | sudo dpkg --set-selections && \
    echo "mongodb-mongosh hold" | sudo dpkg --set-selections && \
    echo "mongodb-org-mongos hold" | sudo dpkg --set-selections && \
    echo "mongodb-org-tools hold" | sudo dpkg --set-selections && \
    sudo rm /bin/systemctl

# expose the render port
EXPOSE 8080

CMD ./render-ws-java-client/src/main/resources/example_1/run_example_steps.sh