#!/bin/bash

# --------------------------------------------------------------------
# Runs basic installation instructions documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

./test-install.sh

# --------------------------------------------------------------------
# Runs web services example documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws-example.md

# TODO: 2204 - remove hacks to workaround jetty startup issue
echo "resetting terminal"
reset

ps -ef | grep java

echo "
$(date)

working dir:
$(pwd)

/var/www/render/deploy/jetty_base/logs:
$(ls -alh /var/www/render/deploy/jetty_base/logs)

/var/www/render/deploy/jetty_base/logs/20*:
$(ls -alh /var/www/render/deploy/jetty_base/logs/20*)

priming web service ...
"

# hack that waits for web service to start up successfully by retrying GET request ...
wget --tries=20 "http://localhost:8080/render-ws/v1/owner/demo/project/example_1/stack/v1_acquire"

echo "
$(date)

/var/www/render/deploy/jetty_base/logs:
$(ls -alh /var/www/render/deploy/jetty_base/logs)

/var/www/render/deploy/jetty_base/logs/20*:
$(ls -alh /var/www/render/deploy/jetty_base/logs/20*)

running example ...
"

cd render || exit 1
JAVA_HOME=$(readlink -m ./deploy/*jdk*)
PATH="${PATH}:${JAVA_HOME}/bin"
export PATH

../test-example.sh
