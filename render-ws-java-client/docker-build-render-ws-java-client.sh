#!/bin/bash

# This script grabs the current git context (branch + commit) so that
# information can be included as labels within the built docker image.

set -e

GIT_TAG=$(git rev-parse --abbrev-ref HEAD)
GIT_SHORT_COMMIT=$(git rev-parse --short HEAD)
GIT_COMMIT=$(git rev-parse HEAD)

IMAGE_REPOSITORY="registry.int.janelia.org/"
IMAGE_TAG="${IMAGE_REPOSITORY}saalfeldlab/render-ws-java-client:${GIT_TAG}.${GIT_SHORT_COMMIT}"

CMD="docker build"
CMD="${CMD} --build-arg=GIT_TAG=${GIT_TAG}"
CMD="${CMD} --build-arg=GIT_COMMIT=${GIT_COMMIT}"
CMD="${CMD} --tag ${IMAGE_TAG}"

echo """
Running:
  ${CMD} - < Dockerfile
"""

${CMD} - < Dockerfile

echo """
To push this image to the repository:

  docker login
  docker push ${IMAGE_TAG}
"""
