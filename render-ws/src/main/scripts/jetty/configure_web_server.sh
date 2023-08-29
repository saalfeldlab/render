#!/bin/bash

# -------------------------------------------------------------------------------------------
# This script downloads logback and swagger-ui components (via curl) to the current directory and
# copies the components into appropriate jetty deployment directories.

if [[ -z "${JETTY_BASE}" || ! -d "${JETTY_BASE}" ]] ; then
  echo "ERROR: JETTY_BASE directory '${JETTY_BASE}' not found" >&2
  exit 1
else
  JETTY_BASE_DIR="${JETTY_BASE}"
fi

SLF4J_VERSION="2.0.5" # should be kept in sync with root pom.xml slf4j.version and root Dockerfile SLF4J_VERSION (for jetty)
LOGBACK_VERSION="1.3.5" # should be kept in sync with root pom.xml logback.version
SWAGGER_UI_VERSION="2.1.4"

MAVEN_CENTRAL_URL="https://repo1.maven.org"
LOGBACK_URL="${MAVEN_CENTRAL_URL}/maven2/ch/qos/logback"
SLF4J_URL="${MAVEN_CENTRAL_URL}/maven2/org/slf4j"
SWAGGER_UI_URL="https://github.com/swagger-api/swagger-ui/archive/v${SWAGGER_UI_VERSION}.tar.gz"

# -------------------------------------------------------------------------------------------
# setup logging components
echo "configure_web_server: setup logging"

JETTY_LIB_LOGGING="${JETTY_BASE_DIR}/lib/logging"
mkdir -p "${JETTY_BASE_DIR}/logs" "${JETTY_LIB_LOGGING}"

for MODULE in classic core; do
  MODULE_JAR="logback-${MODULE}-${LOGBACK_VERSION}.jar"
  curl -o "${JETTY_LIB_LOGGING}/${MODULE_JAR}" "${LOGBACK_URL}/logback-${MODULE}/${LOGBACK_VERSION}/${MODULE_JAR}"
done

for MODULE in jcl-over-slf4j jul-to-slf4j log4j-over-slf4j; do
  MODULE_JAR="${MODULE}-${SLF4J_VERSION}.jar"
  curl -o "${JETTY_LIB_LOGGING}/${MODULE_JAR}" "${SLF4J_URL}/${MODULE}/${SLF4J_VERSION}/${MODULE_JAR}"
done

# -------------------------------------------------------------------------------------------
# setup swagger components
echo "configure_web_server: setup swagger"

curl -L "${SWAGGER_UI_URL}" | tar xz

SWAGGER_UI_SOURCE_DIR="swagger-ui-${SWAGGER_UI_VERSION}"

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
rm -rf "${SWAGGER_UI_SOURCE_DIR}"

# -------------------------------------------------------------------------------------------
# make jetty base and tmp directories accessible to all so that containers can be run as different external users

chmod -R 777 "${JETTY_BASE_DIR}" "${TMPDIR}"