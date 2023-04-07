#!/bin/bash

# Run this script to select an existing render-ws docker image
# and export its jetty files for use outside of docker.

set -e

if (( $# < 2 )); then
  echo "USAGE: ${0} <export parent dir> <env file> (e.g. /data/janelia-render-ws/export /data/janelia-render-ws/render-ws-015g.env)"
  exit 1
fi
EXPORT_PARENT_DIR="${1}"
DEPLOY_ENV_FILE="${2}"

if [ ! -d "${EXPORT_PARENT_DIR}" ]; then
  echo "ERROR: export parent directory ${EXPORT_PARENT_DIR} not found!"
  exit 1
fi

if [ ! -f "${DEPLOY_ENV_FILE}" ]; then
  echo "ERROR: env file ${DEPLOY_ENV_FILE} not found!"
  exit 1
fi

USER_ID=$(id -u)
GROUP_ID=$(id -g)

# Docker images should have repository names like:
#
# registry.int.janelia.org/janelia-render/render-ws   ibeam_msem-20230331_1115-24fca59e   202cfa0ff66e   3 days ago    383MB
# registry.int.janelia.org/janelia-render/render-ws   ibeam_msem-latest                   202cfa0ff66e   3 days ago    383MB
# registry.int.janelia.org/janelia-render/archive     ibeam_msem-20230331_1115-24fca59e   475d35a2eba1   3 days ago    4.09GB
# registry.int.janelia.org/janelia-render/archive     ibeam_msem-latest                   475d35a2eba1   3 days ago    4.09GB

IMAGE_GROUP="janelia-render/render-ws"

echo "
Found these ${IMAGE_GROUP} images:
"

echo -n "   "
docker images | head -1

declare -a DOCKER_IMAGES
readarray -t DOCKER_IMAGES < <(docker images | grep "${IMAGE_GROUP}")     # First < is redirection of input stream,
                                                                          # Construction <(command) means that output
                                                                          # of command is piped to named fifo
IMAGE_COUNT=${#DOCKER_IMAGES[@]}
if (( IMAGE_COUNT == 0 )); then
  echo "ERROR: no ${IMAGE_GROUP} docker images found!"
  exit 1
fi

PS3="Choose an image: "
select DOCKER_IMAGE_INFO in "${DOCKER_IMAGES[@]}"; do
  break
done

REPOSITORY=$(echo "${DOCKER_IMAGE_INFO}" | awk '{print $1}')
TAG=$(echo "${DOCKER_IMAGE_INFO}" | awk '{print $2}')
DOCKER_IMAGE="${REPOSITORY}:${TAG}"

EXPORT_DIR="export-${TAG}"

docker run --user "${USER_ID}:${GROUP_ID}" -it --mount type=bind,source="${EXPORT_PARENT_DIR}",target=/render-export --env-file "${DEPLOY_ENV_FILE}" --entrypoint /render-docker/render-export-jetty-entrypoint.sh --rm "${DOCKER_IMAGE}" "${EXPORT_DIR}"

echo "
To copy to other hosts:

  for HOST in renderer renderer-data4 renderer-dev; do
    scp -r ${EXPORT_PARENT_DIR}/${EXPORT_DIR} \${HOST}.int.janelia.org:/opt/local/docker_exports
  done
"