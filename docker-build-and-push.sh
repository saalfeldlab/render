#!/bin/bash

# checks stolen from https://github.com/JaneliaSciComp/workstation/blob/master/release.sh
STATUS=$(git status --porcelain)
if [[ "${STATUS}" ]]; then
    echo "ERROR: found local changes"
    echo "${STATUS}"
    exit 1
fi

CURR_BRANCH=$(git rev-parse --abbrev-ref HEAD)
# shellcheck disable=SC2086
UNPUSHED=$(git log origin/${CURR_BRANCH}..${CURR_BRANCH})
if [ -n "${UNPUSHED}" ]; then
    echo "ERROR: found unpushed commits"
    echo "${UNPUSHED}"
    exit 1
fi

IGNORED=$(git ls-files -v | grep "^[[:lower:]]")
if [ -n "${IGNORED}" ]; then
    echo "ERROR: found ignored files"
    echo "${IGNORED}"
    exit 1
fi

echo "Pulling latest updates from Github..."
git pull

# Notes about maven project version:
# - Decided not to use it in tags since we rarely update it.
#   Using branch, timestamp, and commit info in the tag names makes more sense for now.
# - Originally wanted to use maven to get version but help plugin is too slow:
#   MAVEN_PROJECT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
# - Switched to xmllint since that was much faster:
#   - xmlns in root pom project element screws up xmllint, so use sed to crop it all out first
#   MAVEN_PROJECT_VERSION=$(sed 's/^<project.*/<project>/' pom.xml | xmllint --xpath "/project/version/text()" -)  # 4.0.1-SNAPSHOT
#   MAVEN_PROJECT_VERSION_NUMBER="${MAVEN_PROJECT_VERSION%%-*}"                                                    # 4.0.1

BUILD_TIMESTAMP=$(date +'%Y%m%d_%H%M')  # 20230314_1353 (no need for seconds since we are appending commit abbrev)

LAST_LOG=$(git log -1 --oneline)        # 62b496e9 (HEAD -> ibeam_msem, origin/ibeam_msem) Merge branch ...
LAST_COMMIT_ABBREV="${LAST_LOG%% *}"    # 62b496e9

BRANCH_COMMIT_TAG="${CURR_BRANCH}-${BUILD_TIMESTAMP}-${LAST_COMMIT_ABBREV}"
LATEST_BRANCH_TAG="${CURR_BRANCH}-latest"

REGISTRY_AND_NAMESPACE="${REGISTRY_AND_NAMESPACE:-registry.int.janelia.org/janelia-render/render-ws}"
TAGS="-t ${REGISTRY_AND_NAMESPACE}:${BRANCH_COMMIT_TAG} -t ${REGISTRY_AND_NAMESPACE}:${LATEST_BRANCH_TAG}"

if (( $# > 0 )); then
  echo "
docker build ${TAGS} --target render-ws .

docker image push ${REGISTRY_AND_NAMESPACE}:${BRANCH_COMMIT_TAG}
docker image push ${REGISTRY_AND_NAMESPACE}:${LATEST_BRANCH_TAG}
"
else
  docker build ${TAGS} --target render-ws .

  docker image push ${REGISTRY_AND_NAMESPACE}:${BRANCH_COMMIT_TAG}
  docker image push ${REGISTRY_AND_NAMESPACE}:${LATEST_BRANCH_TAG}
fi