#!/bin/bash

# ------------------------------------------------------------------------------------------------------
# From https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

echo """
  Install 7. Start MongoDB
"""
sudo -u mongodb /usr/bin/mongod -f /etc/mongod.conf &

echo """
  Install 8. Start Jetty
"""

echo """
  Hack: Passing run instead of start to jetty.sh to work-around startup failure.
        See https://github.com/eclipse/jetty.project/issues/7008 and
        https://github.com/eclipse/jetty.project/issues/7095
"""
deploy/jetty_base/jetty_wrapper.sh run
