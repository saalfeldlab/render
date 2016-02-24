#!/bin/bash
 
#------------------------------------------------------------------------------------------------------
# Wrapper script to setup environment variables before calling real Jetty server script (bin/jetty.sh).
#------------------------------------------------------------------------------------------------------

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
 
export JAVA_HOME="/opt/local/jdk1.8.0_45"
export PATH="${JAVA_HOME}/bin:${PATH}"
export JAVA="${JAVA_HOME}/bin/java"
 
# Uncomment appropriate options for server ...
 
# small 4GB server: 
export JAVA_OPTIONS="-Xms3g -Xmx3g -server -Djava.awt.headless=true"
 
# larger 16GB server
#export JAVA_OPTIONS="-Xms15g -Xmx15g -server -Djava.awt.headless=true"
 
# super 500GB server
#export JAVA_OPTIONS="-Xms400g -Xmx400g -server -Djava.awt.headless=true"
 
# run the real script ... 
${JETTY_HOME}/bin/jetty.sh $* 2>&1 1>>${JETTY_RUN}/jetty_bootstrap.log
