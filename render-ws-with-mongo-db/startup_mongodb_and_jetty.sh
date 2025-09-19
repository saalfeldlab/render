#!/bin/bash

# ------------------------------------------------------------------------------------------------------
# From https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

echo "starting mongodb"
sudo -u mongodb /usr/bin/mongod -f /etc/mongod.conf &

# default memory to 28g assuming that we have a 32g VM
export RENDER_JETTY_MIN_AND_MAX_MEMORY=${1:-28g}

# note that jetty_wrapper.sh start still does not seem to work and we want to keep the container active anyway
echo "starting jetty_wrapper.sh run (which should keep the container active)"
deploy/jetty_base/jetty_wrapper.sh run