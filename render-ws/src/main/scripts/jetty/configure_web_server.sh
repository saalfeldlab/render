#!/bin/sh

# -------------------------------------------------------------------------------------------
# This script downloads logback and swagger-ui components (via curl) to the current directory and
# copies the components into appropriate jetty deployment directories.

if [ -z "${JETTY_BASE}" ] | [ ! -d "${JETTY_BASE}" ] ; then
  echo "ERROR: JETTY_BASE directory '${JETTY_BASE}' not found" >&2
  exit 1
else
  JETTY_BASE_DIR="${JETTY_BASE}"
fi

LOGBACK_VERSION="1.1.5"
SLF4J_VERSION="1.7.16"
SWAGGER_UI_VERSION="2.1.4"

SLF4J_URL="https://www.slf4j.org/dist/slf4j-${SLF4J_VERSION}.tar.gz"
LOGBACK_URL="https://logback.qos.ch/dist/logback-${LOGBACK_VERSION}.tar.gz"
SWAGGER_UI_URL="https://github.com/swagger-api/swagger-ui/archive/v${SWAGGER_UI_VERSION}.tar.gz"

curl "${SLF4J_URL}" | tar xz
curl "${LOGBACK_URL}" | tar xz
curl -L "${SWAGGER_UI_URL}" | tar xz

SLF4J_SOURCE_DIR="slf4j-${SLF4J_VERSION}"
LOGBACK_SOURCE_DIR="logback-${LOGBACK_VERSION}"
SWAGGER_UI_SOURCE_DIR="swagger-ui-${SWAGGER_UI_VERSION}"

# -------------------------------------------------------------------------------------------
# setup logging components

JETTY_LIB_EXT="${JETTY_BASE_DIR}/lib/ext"
mkdir -p "${JETTY_BASE_DIR}/logs" "${JETTY_LIB_EXT}"

mv "${LOGBACK_SOURCE_DIR}/logback-access-${LOGBACK_VERSION}.jar" "${JETTY_LIB_EXT}"
mv "${LOGBACK_SOURCE_DIR}/logback-classic-${LOGBACK_VERSION}.jar" "${JETTY_LIB_EXT}"
mv "${LOGBACK_SOURCE_DIR}/logback-core-${LOGBACK_VERSION}.jar" "${JETTY_LIB_EXT}"

mv "${SLF4J_SOURCE_DIR}/jcl-over-slf4j-${SLF4J_VERSION}.jar" "${JETTY_LIB_EXT}"
mv "${SLF4J_SOURCE_DIR}/jul-to-slf4j-${SLF4J_VERSION}.jar" "${JETTY_LIB_EXT}"
mv "${SLF4J_SOURCE_DIR}/log4j-over-slf4j-${SLF4J_VERSION}.jar" "${JETTY_LIB_EXT}"
mv "${SLF4J_SOURCE_DIR}/slf4j-api-${SLF4J_VERSION}.jar" "${JETTY_LIB_EXT}"

# -------------------------------------------------------------------------------------------
# setup swagger components

SWAGGER_UI_DEPLOY_DIR="${JETTY_BASE_DIR}/webapps/swagger-ui"
cp -r ${SWAGGER_UI_SOURCE_DIR}/dist "${SWAGGER_UI_DEPLOY_DIR}"

# modify index.html to dynamically derive the swagger.json URL and sort functions by method
SWAGGER_INDEX_HTML="${SWAGGER_UI_DEPLOY_DIR}/index.html"
cp -p "${SWAGGER_INDEX_HTML}" "${SWAGGER_INDEX_HTML}.original"
sed -i '
  s/url.*petstore.*/url = window.location.href.replace(\/swagger-ui.*\/, "render-ws\/swagger.json");/
  s/apisSorter: "alpha",/apisSorter: "alpha", validatorUrl: null, operationsSorter: function(a, b) { var methodMap = { 'get': 1, 'put': 2, 'post': 3, 'delete': 4 }; if (a.method in methodMap \&\& b.method in methodMap) { var aMethodValue = methodMap[a.method]; var bMethodValue = methodMap[b.method]; if (aMethodValue == bMethodValue) { return a.path.localeCompare(b.path); } else { return aMethodValue - bMethodValue; } } else { return -1; } },/
' "${SWAGGER_INDEX_HTML}"

# workaround bug in current swagger.json that breaks swagger-ui.js
SWAGGER_UI_JS="${SWAGGER_UI_DEPLOY_DIR}/swagger-ui.js"
cp -p "${SWAGGER_UI_JS}" "${SWAGGER_UI_JS}.original"
sed -i '
  s/var ref = property.\$ref;/if (typeof property === "undefined") { property = ""; console.log("skipping undefined property"); } var ref = property.\$ref;/
' "${SWAGGER_UI_JS}"

# clean-up unused downloaded stuff
rm -rf "${SLF4J_SOURCE_DIR}"
rm -rf "${LOGBACK_SOURCE_DIR}"
rm -rf "${SWAGGER_UI_SOURCE_DIR}"