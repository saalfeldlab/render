#!/bin/bash

if [ "$#" -ne 3 ]; then
    echo "
Usage: $0 OWNER PROJECT STACK

Example: $0 hess_wafers_60_61  w60_serial_360_to_369  w60_s364_r00_gc_pa_mat_render_align
"
    exit 1
fi

OWNER="$1"
PROJECT="$2"
STACK="$3"

echo "Removing stack
  ${OWNER} :: ${PROJECT} :: ${STACK}
"

read -r -p "Do you want to proceed? (y/n) "
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 0
fi

BASE_URL="http://localhost:8080/render-ws/v1"
URL="${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stack/${STACK}"
curl -X DELETE --header 'Accept: application/json' "${URL}"

echo "
Stacks for ${OWNER} :: ${PROJECT} are now:"
curl -X GET --silent --header 'Accept: application/json' "${BASE_URL}/owner/${OWNER}/project/${PROJECT}/stackIds" | jq '.'