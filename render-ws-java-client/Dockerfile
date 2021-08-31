# ---------------------------------------------------------------------
# Building this Dockerfile will create an image that contains the
# render-ws-java-client java archive and the run_ws_client.sh script
# to facilitate running Render Web Services Java clients in a container.
#
# Look at the docker-build-render-ws-java-client.sh script (in this directory) to see
# how the docker build is typically invoked.

# ---------------------------------------------------------------------
# Stage 1: Create builder image that will contain all code and build artifacts.
#
# NOTE: I originally tried basing this off of https://hub.docker.com/r/janeliascicomp/builder
#       but that alpine image is missing stuff needed for render tests to succeed
#       so I switched to use the same zulu image used for the final image
#       rather than try to debug alpine issues.

FROM azul/zulu-openjdk-debian:8 as builder

RUN apt-get update && apt-get install -y git maven

ARG GIT_TAG=master
ARG GIT_COMMIT=latest

# Checkout and build the code ...
#
# NOTES:
#   avoid --depth 1 option for git since that breaks some of the build
#   use mvn --batch_mode to reduce output during build
#   use mvn --projects and --also-make to build only the client module and its dependent modules
WORKDIR /tmp/render
RUN git clone --branch $GIT_TAG https://github.com/saalfeldlab/render.git . \
    && mvn package --batch-mode --projects render-ws-java-client --also-make

# ---------------------------------------------------------------------
# Stage 2: Create slimmed down final image that only contains client jar and scripts.

FROM azul/zulu-openjdk-debian:8

# ARGs need to be re-declared for each stage in multi-stage builds - who knew?
ARG GIT_COMMIT

# Add labels to image to help folks who need to figure out what they are running later.

# See https://github.com/opencontainers/image-spec/blob/main/annotations.md for these "standard" labels.
LABEL org.opencontainers.image.title="Render Web Services Java Clients"
LABEL org.opencontainers.image.description="Invoke packaged java clients for render web services"
LABEL org.opencontainers.image.authors="trautmane@janelia.hhmi.org"
LABEL org.opencontainers.image.source="https://github.com/saalfeldlab/render.git"
LABEL org.opencontainers.image.revision="$GIT_COMMIT"

# Full URL to the Dockerfile used to build image is really helpful - should be part of standard!
LABEL org.janelia.build.dockerfile="https://github.com/saalfeldlab/render/blob/$GIT_COMMIT/render-ws-java-client/Dockerfile"
LABEL org.janelia.build.entrypoint="https://github.com/saalfeldlab/render/blob/$GIT_COMMIT/render-ws-java-client/src/main/scripts/run_ws_client.sh"

# Fix for this error: https://github.com/tianon/docker-brew-debian/issues/45 (not sure if this is needed, but doesn't hurt)
RUN echo "LC_ALL=en_US.UTF-8" >> /etc/environment \
    && echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen \
    && echo "LANG=en_US.UTF-8" > /etc/locale.conf \
    && locale-gen en_US.UTF-8

# Maintaining legacy (source) path structure helps keep compatibility with older tools that depend upon it.
COPY --from=builder /tmp/render/render-ws-java-client/target/render-ws-java-client-*-standalone.jar /render/render-ws-java-client/target/
COPY --from=builder /tmp/render/render-ws-java-client/src/main/scripts /render/render-ws-java-client/src/main/scripts

ENTRYPOINT [ "/render/render-ws-java-client/src/main/scripts/run_ws_client.sh"]
