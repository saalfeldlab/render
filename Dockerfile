# ======================================================================================
# Stage 0: builder

FROM openjdk:8-jdk as builder
LABEL maintainer="Forrest Collman <forrestc@alleninstitute.org>, Eric Trautman <trautmane@janelia.hhmi.org>"

RUN apt-get update && apt-get install -y maven

# ---------------------------------
# Install library dependencies before actually building source.
# This caches libraries into an image layer that can be reused when only source code has changed.

WORKDIR /var/www/render/
COPY pom.xml .
COPY render-ws/pom.xml render-ws/pom.xml
COPY render-ws-java-client/pom.xml render-ws-java-client/pom.xml
COPY render-ws-spark-client/pom.xml render-ws-spark-client/pom.xml
COPY render-app/pom.xml render-app/pom.xml
COPY trakem2-scripts/pom.xml trakem2-scripts/pom.xml
COPY docs/pom.xml docs/pom.xml
RUN mvn -T 1C verify clean --fail-never

# ---------------------------------
# Build the source code, save resulting jar and war files, and remove everything else

COPY . /var/www/render/
RUN mvn clean

# use -T 1C maven option to multi-thread process
RUN mvn -T 1C -Dproject.build.sourceEncoding=UTF-8 package && \
    mkdir -p /root/render-lib && \
    mv */target/*.*ar /root/render-lib && \
    printf "\nsaved the following build artifacts:\n\n" && \
    ls -alh /root/render-lib/* && \
    printf "\nremoving everything else ...\n\n" && \
    rm -rf /var/www/render/* && \
    rm -rf /root/.m2 && \
    rm -rf /root/.embedmongo

# ======================================================================================
# Stage 1: render-ws

# NOTE: jetty version should be kept in sync with values in render/render-ws/pom.xml and render/render-ws/src/main/scripts/install.sh
FROM jetty:9.4.6-jre8-alpine as render-ws

# add curl and coreutils (for gnu readlink) not included in alpine
RUN apk add --update curl && \
    apk add --update coreutils && \
    rm -rf /var/cache/apk/*

WORKDIR $JETTY_BASE

COPY render-ws/src/main/scripts/jetty/ .
RUN ls -al $JETTY_BASE/* && \
    chmod 755 ./configure_web_server.sh && \
    ./configure_web_server.sh

COPY --from=builder /root/render-lib/render-ws-*.war webapps/render-ws.war
COPY render-ws/src/main/scripts/docker /render-docker
RUN chown -R jetty:jetty $JETTY_BASE 

EXPOSE 8080

ENV JAVA_OPTIONS="-Xms3g -Xmx3g -server -Djava.awt.headless=true" \
    JETTY_THREADPOOL_MIN_THREADS="10" \
    JETTY_THREADPOOL_MAX_THREADS="200" \
    LOG_ACCESS_ROOT_APPENDER="STDOUT" \
    LOG_JETTY_ROOT_APPENDER="STDOUT" \
    LOG_JETTY_ROOT_LEVEL="WARN" \
    LOG_JETTY_JANELIA_LEVEL="WARN" \
    MONGO_HOST="" \
    MONGO_PORT="" \
    MONGO_USERNAME="" \
    MONGO_PASSWORD="" \
    MONGO_AUTH_DB="" \
    MONGO_CONNECTION_STRING="" \
    MONGO_CONNECTION_STRING_USES_AUTH="" \
    NDVIZHOST="" \
    NDVIZPORT="" \
    NDVIZ_URL="" \
    VIEW_CATMAID_HOST_AND_PORT="" \
    VIEW_DYNAMIC_RENDER_HOST_AND_PORT="" \
    VIEW_RENDER_STACK_OWNER="" \
    VIEW_RENDER_STACK_PROJECT="" \
    VIEW_RENDER_STACK="" \
    VIEW_MATCH_OWNER="" \
    VIEW_MATCH_COLLECTION="" \
    WEB_SERVICE_MAX_TILE_SPECS_TO_RENDER="20" \
    WEB_SERVICE_MAX_IMAGE_PROCESSOR_GB=""

USER jetty
ENTRYPOINT ["/render-docker/render-run-jetty-entrypoint.sh"]
