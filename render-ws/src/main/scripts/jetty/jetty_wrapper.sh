#!/bin/bash
 
#------------------------------------------------------------------------------------------------------
# Wrapper script to setup environment variables before calling real Jetty server script (bin/jetty.sh).
# Default web server memory limit is 3g (intended for a 4g server).
# Override the default memory limit by setting the RENDER_JETTY_MIN_AND_MAX_MEMORY environment variable
# (e.g. "15g" for 16g server or "400g" for 500g server).
#------------------------------------------------------------------------------------------------------

JETTY_MIN_AND_MAX_MEMORY=${RENDER_JETTY_MIN_AND_MAX_MEMORY:-"3g"}

export JETTY_HOME="/opt/local/jetty_home"
export JETTY_BASE="/opt/local/jetty_base"
export JETTY_RUN="${JETTY_BASE}/logs"
export JETTY_PID="${JETTY_RUN}/jetty.pid"
export JETTY_STATE="${JETTY_RUN}/jetty.state"
 
# Use default values for these variables:
#
# JETTY_ARGS
# JETTY_USER
# JETTY_SHELL
 
export JAVA_HOME="/misc/sc/jdks/zulu21"
export PATH="${JAVA_HOME}/bin:${PATH}"
export JAVA="${JAVA_HOME}/bin/java"
export JAVA_OPTIONS="-Xms${JETTY_MIN_AND_MAX_MEMORY} -Xmx${JETTY_MIN_AND_MAX_MEMORY} -server -Djava.awt.headless=true"
 
if [ "$1" = "run-fg" ]; then

  #------------------------------------------------------------------------------------------------------
  # Start the server in the foreground, bypassing bin/jetty.sh.
  #
  # Older versions of bin/jetty.sh (e.g. 10.0.13) ran 'exec "${RUN_CMD[@]}"' for the "run" action, so the
  # script process became the JVM process and stayed in the foreground.  Newer versions (e.g. 10.0.26) run
  # 'echo ${RUN_ARGS[*]} | xargs ${JAVA} > /dev/null &' for every start action instead, which returns
  # immediately.  That breaks callers (like the render-ws-with-mongo-db container) that rely on this script
  # blocking to stay alive.
  #
  # Starting the JVM here with exec also makes it the container's main process, so it receives signals
  # (e.g. SIGTERM from 'docker stop') directly and logs to the container console instead of to
  # jetty_bootstrap.log.
  #------------------------------------------------------------------------------------------------------

  cd "${JETTY_BASE}" || exit 1
  exec "${JAVA}" ${JAVA_OPTIONS} -jar "${JETTY_HOME}/start.jar"

else

  # run the real script ...
  ${JETTY_HOME}/bin/jetty.sh "$@" 1>>${JETTY_RUN}/jetty_bootstrap.log 2>&1

fi
