#!/bin/bash

# ------------------------------------------------------------------------------------------------------
# From https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

# start mongodb
sudo -u mongodb /usr/bin/mongod -f /etc/mongod.conf &

# start jetty
deploy/jetty_base/jetty_wrapper.sh start