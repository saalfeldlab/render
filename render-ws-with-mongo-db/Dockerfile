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
# To build:
#   cd ${RENDER_REPO_ROOT_DIR}
#   docker build --file render-ws-with-mongo-db/Dockerfile --no-cache --tag ghcr.io/janeliascicomp/render-ws-with-mongodb:0.0.3 .
#
# To run a container in detached mode:
#   docker run --publish 8080:8080 --detach --rm ghcr.io/janeliascicomp/render-ws-with-mongodb:0.0.3
#
# To launch an interactive bash shell within a container:
#   docker run --interactive --tty --publish 8080:8080 --entrypoint /bin/bash --rm ghcr.io/janeliascicomp/render-ws-with-mongodb:0.0.3
#
# To push the image to the janeliascicomp containers GitHub registry (see https://github.com/JaneliaSciComp/containers/tree/main):
#   docker push ghcr.io/janeliascicomp/render-ws-with-mongodb:0.0.3
#
# Published Containers Notes:
#   - Before pushing, your docker instance needs to be logged in to GitHub.
#     For details on how to do that from docker see:
#     https://github.com/JaneliaSciComp/containers/tree/main?tab=readme-ov-file#publishing-images
#
#   - The list of successfully published render-ws-with-mongodb containers can be found at:
#     https://github.com/JaneliaSciComp/containers/pkgs/container/render-ws-with-mongodb
# ======================================================================================

FROM ubuntu:22.04
LABEL maintainer="Eric Trautman <trautmane@janelia.hhmi.org>"

# 1: Install Git, Maven, curl, vim, gnupg (needed for keyring that Google Cloud SDK install requires)
RUN apt-get update && \
    apt-get -y upgrade && \
    apt-get install -y git maven curl vim gnupg

# 1b: Install Google Cloud SDK ( steps from https://stackoverflow.com/a/61420657 )
# RUN echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] http://packages.cloud.google.com/apt cloud-sdk main" | tee -a /etc/apt/sources.list.d/google-cloud-sdk.list && \
#    curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | apt-key --keyring /usr/share/keyrings/cloud.google.gpg add - && \
#    apt-get update && \
#    apt-get install -y google-cloud-sdk

# 2. Clone the Repository
WORKDIR /var/www/
RUN git clone https://github.com/saalfeldlab/render.git

# 3. Install JDK and Jetty
WORKDIR /var/www/render/

# Uncomment next line to switch to different source branch
RUN git checkout newsolver

RUN ./render-ws/src/main/scripts/install.sh && \
    cp ./render-ws-with-mongo-db/startup_mongodb_and_jetty.sh .

# 4. Build only the render-app and render-ws modules and skip tests
RUN { echo 'JAVA_HOME="$(readlink -m ./deploy/*jdk*)"'; } >> ~/.mavenrc  && \
    mvn --version; mvn --batch-mode --projects ".,render-app,render-ws" -Dproject.build.sourceEncoding=UTF-8 -Dmaven.test.skip package

# 5. Deploy Web Service and remove all unneeded source and build artifacts
#    leaving only render/deploy and render/startup_mongodb_and_jetty.sh
RUN cp render-ws/target/render-ws-*.war deploy/jetty_base/webapps/render-ws.war && \
    rm -rf .[dgi]* Dockerfile LICENSE README.md docker-build-and-push.sh docs examples pom.xml \
    render-app render-ws render-ws-java-client render-ws-spark-client render-ws-with-mongo-db trakem2-scripts \
    /tmp/extract* /root/.m2

# 6. Install MongoDB 6.0.4

# needed for access to https mongodb resources
RUN apt-get -y install apt-transport-https gnupg wget sudo

# this line is not in the MongoDB steps, but is needed to skip interactive tzdata prompt ( see https://stackoverflow.com/a/44333806 )
RUN DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tzdata

# steps from https://www.mongodb.com/docs/v6.0/tutorial/install-mongodb-on-ubuntu/#install-mongodb-community-edition
# with all packages except mongodb-org-server and mongodb-org-tools removed
RUN wget -qO - https://www.mongodb.org/static/pgp/server-6.0.asc | sudo apt-key add - && \
    sudo ln -s /bin/true /bin/systemctl && \
    echo "deb [ arch=amd64,arm64 ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/6.0 multiverse" | sudo tee /etc/apt/sources.list.d/mongodb-org-6.0.list && \
    sudo apt-get update && \
    sudo apt-get install -y mongodb-org-server=6.0.4 mongodb-org-tools=6.0.4 && \
    echo "mongodb-org-server hold" | sudo dpkg --set-selections && \
    echo "mongodb-org-tools hold" | sudo dpkg --set-selections && \
    sudo rm /bin/systemctl

# expose the render port
EXPOSE 8080

CMD ./startup_mongodb_and_jetty.sh