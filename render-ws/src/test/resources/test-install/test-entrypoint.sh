#!/bin/sh

# --------------------------------------------------------------------
# Runs basic installation instructions documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

./test-install.sh

# --------------------------------------------------------------------
# Runs web services example documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws-example.md

cd render
../test-example.sh
