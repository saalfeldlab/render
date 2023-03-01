# --------------------------------------------------------------------
# Builds an Ubuntu container and runs steps 3 and 5-8 of the basic installation instructions documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md
#
# The slow render build steps (1, 2, and 4) are skipped assuming that they have already
# been done and deployed to a render-ws-install:latest-post-build image.
# This is intended to make it easier to test and debug MongoDB and Jetty deployment issues.
#
# Once installation is complete, the web services example is run:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws-example.md
#
# To test post build install, first (and only once) build the janelia-render:latest-builder image:
#   cd ../../../../..
#   docker build -t janelia-render:latest-builder --target builder .
#
# then build the render-ws-install:latest-post-build image and run it as often as needed:
#   cd -
#   docker build -t render-ws-install:latest-post-build -f test-post-build.Dockerfile
#   docker run -it --rm render-ws-install:latest-post-build
#
# To see what is in the image before running the install script (or to run it manually):
#   docker run -it --rm --entrypoint /bin/bash render-ws-install:latest-post-build

FROM ubuntu:22.04
LABEL maintainer="Eric Trautman <trautmane@janelia.hhmi.org>"

# update apt repo and add sudo before test to mimic standard Ubuntu install
RUN apt-get update && apt-get install -y sudo curl

WORKDIR /var/www/
COPY *.sh ./
RUN chmod 755 *.sh

COPY --from=janelia-render:latest-builder /var/www/render .

ENV SKIP_RENDER_BUILD="y"

ENTRYPOINT ["/var/www/test-entrypoint.sh"]
