#!/bin/bash

BASE_URL="http://localhost:8080/render-ws/v1"
OWNER="hess_wafers_60_61"
P="07"

  PROJECT="w61_serial_${P}0_to_${P}9"
  echo "Processing project ${PROJECT} ..."
  for STACK in $(curl -s "${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stackIds" | jq -r '.[].stack'); do
    if [[ $STACK == *"rough"* ]]; then
      echo -n "R"
      PAR_STACK="${STACK/_rough/r}"
      DATA="{\"owner\": \"${OWNER}\", \"project\": \"${PROJECT}\", \"stack\": \"${PAR_STACK}\"}"
      BASE_URL="http://localhost:8080/render-ws/v1"
      URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}/stackId"
      curl -s -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' --data "${DATA}" "${URL}"
    else
      echo -n "x"
      URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}"
      curl -s -X DELETE --header 'Accept: application/json' "${URL}"
    fi
  done

echo "

Stacks for ${OWNER} :: ${PROJECT} are now:
"
curl -X GET --silent --header 'Accept: application/json' "${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stackIds" | jq -r '.[] | "\(.stack)"' | sort

./db-dump-google-collections.sh render-ws-mongodb-8c-32gb-abm render '.*'

ABM_DIR="/mnt/disks/mongodb_dump_fs/dump/render-ws-mongodb-8c-32gb-abm/collections"
echo "
ls -1 ${ABM_DIR}
"
ls -1 ${ABM_DIR}

echo "
mv ${ABM_DIR}/20250927_0* ${ABM_DIR}/${PROJECT}_par"

  for STACK in $(curl -s "${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stackIds" | jq -r '.[].stack'); do
    echo -n "x"
    URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}"
    curl -s -X DELETE --header 'Accept: application/json' "${URL}"
  done

echo "

Stacks for ${OWNER} :: ${PROJECT} are now:
"
curl -X GET --silent --header 'Accept: application/json' "${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stackIds" | jq -r '.[] | "\(.stack)"' | sort
