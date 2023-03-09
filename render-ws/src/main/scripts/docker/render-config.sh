#!/bin/sh

# --------------------------------------------------------------
# This script configures files in the container
# based upon run-time environment variables.

set -e

# --------------------------------------------------------------
# Strips quotes inserted by Ansible into environment variables
stripQuotes() {
  echo $* | sed -e 's/^"//' -e 's/"$//'
}

MONGO_HOST=$(stripQuotes ${MONGO_HOST})
MONGO_PORT=$(stripQuotes ${MONGO_PORT})
MONGO_USERNAME=$(stripQuotes ${MONGO_USERNAME})
MONGO_PASSWORD=$(stripQuotes ${MONGO_PASSWORD})
MONGO_AUTH_DB=$(stripQuotes ${MONGO_AUTH_DB})
MONGO_CONNECTION_STRING=$(stripQuotes ${MONGO_CONNECTION_STRING})
MONGO_CONNECTION_STRING_USES_AUTH=$(stripQuotes ${MONGO_CONNECTION_STRING_USES_AUTH})

JETTY_USER_NAME=$(stripQuotes ${JETTY_USER_NAME})
JETTY_GROUP_NAME=$(stripQuotes ${JETTY_GROUP_NAME})

JETTY_THREADPOOL_MIN_THREADS=$(stripQuotes ${JETTY_THREADPOOL_MIN_THREADS})
JETTY_THREADPOOL_MAX_THREADS=$(stripQuotes ${JETTY_THREADPOOL_MAX_THREADS})

LOG_ACCESS_ROOT_APPENDER=$(stripQuotes ${LOG_ACCESS_ROOT_APPENDER})
LOG_JETTY_ROOT_APPENDER=$(stripQuotes ${LOG_JETTY_ROOT_APPENDER})
LOG_JETTY_ROOT_LEVEL=$(stripQuotes ${LOG_JETTY_ROOT_LEVEL})
LOG_JETTY_JANELIA_LEVEL=$(stripQuotes ${LOG_JETTY_JANELIA_LEVEL})

NDVIZHOST=$(stripQuotes ${NDVIZHOST})
NDVIZPORT=$(stripQuotes ${NDVIZPORT})
NDVIZ_URL=$(stripQuotes ${NDVIZ_URL})

VIEW_CATMAID_HOST_AND_PORT=$(stripQuotes ${VIEW_CATMAID_HOST_AND_PORT})
VIEW_DYNAMIC_RENDER_HOST_AND_PORT=$(stripQuotes ${VIEW_DYNAMIC_RENDER_HOST_AND_PORT})
VIEW_RENDER_STACK_OWNER=$(stripQuotes ${VIEW_RENDER_STACK_OWNER})
VIEW_RENDER_STACK_PROJECT=$(stripQuotes ${VIEW_RENDER_STACK_PROJECT})
VIEW_RENDER_STACK=$(stripQuotes ${VIEW_RENDER_STACK})
VIEW_MATCH_OWNER=$(stripQuotes ${VIEW_MATCH_OWNER})
VIEW_MATCH_COLLECTION=$(stripQuotes ${VIEW_MATCH_COLLECTION})

WEB_SERVICE_MAX_TILE_SPECS_TO_RENDER=$(stripQuotes ${WEB_SERVICE_MAX_TILE_SPECS_TO_RENDER})
WEB_SERVICE_MAX_IMAGE_PROCESSOR_GB=$(stripQuotes ${WEB_SERVICE_MAX_IMAGE_PROCESSOR_GB})

# --------------------------------------------------------------
# Mongo config

RENDER_DB_PROPERTIES="${JETTY_BASE}/resources/render-db.properties"


if [ -z "${MONGO_HOST}" ] && [ -z "${MONGO_CONNECTION_STRING}" ]; then
  echo "ERROR: either MONGO_HOST or MONGO_CONNECTION_STRING must be defined"
  exit 1
fi

sed -i "s/servers=.*/servers=${MONGO_HOST}/" ${RENDER_DB_PROPERTIES}

if [ -n "${MONGO_PORT}" ]; then
  sed -i "s/#port=.*/port=${MONGO_PORT}/" ${RENDER_DB_PROPERTIES}
fi

if [ -n "${MONGO_USERNAME}" ]; then
  sed -i """
    s/#authenticationDatabase=/authenticationDatabase=/
    s/#userName=.*/userName=${MONGO_USERNAME}/
    s/#password=.*/password=${MONGO_PASSWORD}/
  """ ${RENDER_DB_PROPERTIES}
fi

if [ -n "${MONGO_CONNECTION_STRING}" ]; then

  ESCAPED_MONGO_CONNECTION_STRING=$(echo ${MONGO_CONNECTION_STRING} | sed 's/@/\\@/')
  sed -i "s@#connectionString=.*@connectionString=${ESCAPED_MONGO_CONNECTION_STRING}@" ${RENDER_DB_PROPERTIES}

  if [ "${MONGO_CONNECTION_STRING_USES_AUTH}" = "Y" ] || [ "${MONGO_CONNECTION_STRING_USES_AUTH}" = "y" ]; then
    sed -i "s/#authenticationDatabase=/authenticationDatabase=/" ${RENDER_DB_PROPERTIES}
  fi
fi

# --------------------------------------------------------------
# Jetty thread pool config

JETTY_THREADPOOL_INI="${JETTY_BASE}/start.d/threadpool.ini"

if [ -n "${JETTY_THREADPOOL_MIN_THREADS}" ]; then
  sed -i "s/^.*jetty.threadPool.minThreads=.*/jetty.threadPool.minThreads=${JETTY_THREADPOOL_MIN_THREADS}/" ${JETTY_THREADPOOL_INI}
fi

if [ -n "${JETTY_THREADPOOL_MAX_THREADS}" ]; then
  sed -i "s/^.*jetty.threadPool.maxThreads=.*/jetty.threadPool.maxThreads=${JETTY_THREADPOOL_MAX_THREADS}/" ${JETTY_THREADPOOL_INI}
fi

# --------------------------------------------------------------
# Logging config

# default access log is to a file, see if config has changed this ...
if [ "${LOG_ACCESS_ROOT_APPENDER}" = "NONE" ]; then
  rm "${JETTY_BASE}/start.d/requestlog.ini" # disable access logging
  rm "${JETTY_BASE}/etc/jetty-requestlog.xml"
else
    if [ "${LOG_ACCESS_ROOT_APPENDER}" = "STDOUT" ]; then
        sed -i "s@appender-ref ref=\"ACCESS_FILE\"@appender-ref ref=\"STDOUT\"@" "${JETTY_BASE}/resources/logback.xml"
    fi
fi

if [ "${LOG_JETTY_ROOT_APPENDER}" = "NONE" ]; then
    # turn off jetty server logging
    LOG_JETTY_ROOT_LEVEL="OFF"
    LOG_JETTY_JANELIA_LEVEL="OFF"
    LOG_JETTY_ROOT_APPENDER="STDOUT"
fi

sed -i """
  s@logger name=\"org.janelia\" level=\".*\"@logger name=\"org.janelia\" level=\"${LOG_JETTY_JANELIA_LEVEL}\"@
  s@root level=\".*\"@root level=\"${LOG_JETTY_ROOT_LEVEL}\"@
  s@appender-ref ref=\"STDOUT\"@appender-ref ref=\"${LOG_JETTY_ROOT_APPENDER}\"@
""" "${JETTY_BASE}/resources/logback.xml"

# --------------------------------------------------------------
# Render server properties

# if NDVIZ_URL is not defined, use HOST and PORT parameters
if [ -z "${NDVIZ_URL}" ] & [ -n "${NDVIZHOST}" ]; then
  if [ -n "${NDVIZPORT}" ]; then
    NDVIZ_URL="${NDVIZHOST}:${NDVIZPORT}"
  else
    NDVIZ_URL="${NDVIZHOST}"
  fi
fi

sed -i """
  s@view.catmaidHost=.*@view.catmaidHost=${VIEW_CATMAID_HOST_AND_PORT}@
  s@view.dynamicRenderHost=.*@view.dynamicRenderHost=${VIEW_DYNAMIC_RENDER_HOST_AND_PORT}@
  s@view.matchOwner=.*@view.matchOwner=${VIEW_MATCH_OWNER}@
  s@view.matchCollection=.*@view.matchCollection=${VIEW_MATCH_COLLECTION}@
  s@view.ndvizHost=.*@view.ndvizHost=${NDVIZ_URL}@
  s@view.renderStack=.*@view.renderStack=${VIEW_RENDER_STACK}@
  s@view.renderStackOwner=.*@view.renderStackOwner=${VIEW_RENDER_STACK_OWNER}@
  s@view.renderStackProject=.*@view.renderStackProject=${VIEW_RENDER_STACK_PROJECT}@
  s@webService.maxTileSpecsToRender=.*@webService.maxTileSpecsToRender=${WEB_SERVICE_MAX_TILE_SPECS_TO_RENDER}@
  s@webService.maxImageProcessorCacheGb=.*@webService.maxImageProcessorCacheGb=${WEB_SERVICE_MAX_IMAGE_PROCESSOR_GB}@
""" "${JETTY_BASE}/resources/render-server.properties"