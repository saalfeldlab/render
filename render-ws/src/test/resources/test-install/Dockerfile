# --------------------------------------------------------------------
# Builds an Ubuntu container and runs basic installation instructions documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md
#
# followed by web services example documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws-example.md
#
# To test:
#   docker build -t render-ws-install:latest .
#   docker run -it --rm render-ws-install:latest

FROM ubuntu:16.04
LABEL maintainer="Eric Trautman <trautmane@janelia.hhmi.org>"

# update apt repo and add sudo before test to mimic standard Ubuntu install
RUN apt-get update && apt-get install -y sudo curl

WORKDIR /var/www/
COPY *.sh ./
RUN chmod 755 *.sh

ENTRYPOINT ["/var/www/test-entrypoint.sh"]
