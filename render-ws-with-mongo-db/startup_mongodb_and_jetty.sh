#!/bin/bash

# ------------------------------------------------------------------------------------------------------
# From https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

echo "starting mongodb"
sudo -u mongodb /usr/bin/mongod -f /etc/mongod.conf &

# note that jetty_wrapper.sh start still does not seem to work and we want to keep the container active anyway
echo "starting jetty_wrapper.sh run (which should keep the container active)"
deploy/jetty_base/jetty_wrapper.sh run