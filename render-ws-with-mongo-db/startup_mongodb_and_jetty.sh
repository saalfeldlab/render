#!/bin/bash

# ------------------------------------------------------------------------------------------------------
# From https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

echo "starting mongodb"
sudo -u mongodb /usr/bin/mongod -f /etc/mongod.conf &

echo "starting jetty"
deploy/jetty_base/jetty_wrapper.sh start

echo "sleeping 'infinity' to keep container running"
sleep infinity