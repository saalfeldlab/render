#!/bin/bash

if [ "$#" -ne 6 ]; then
    echo "
Usage: $0 FROM_OWNER FROM_PROJECT FROM_STACK TO_OWNER TO_PROJECT TO_STACK

Example: $0 hess_wafers_60_61  w60_serial_360_to_369  w60_s360_r00_gc_pa  hess_wafers_60_61  test_b  w60_s360_r00_gc_pa
"
    exit 1
fi

FROM_OWNER="$1"
FROM_PROJECT="$2"
FROM_STACK="$3"
TO_OWNER="$4"
TO_PROJECT="$5"
TO_STACK="$6"

echo "Renaming stack
  ${FROM_OWNER} :: ${FROM_PROJECT} :: ${FROM_STACK}
to
  ${TO_OWNER} :: ${TO_PROJECT} :: ${TO_STACK}
"

read -r -p "Do you want to proceed? (y/n) "
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 0
fi

DATA="{\"owner\": \"${TO_OWNER}\", \"project\": \"${TO_PROJECT}\", \"stack\": \"${TO_STACK}\"}"
BASE_URL="http://localhost:8080/render-ws/v1"
URL="${BASE_URL}/owner/${FROM_OWNER}/project/${FROM_PROJECT}/stack/${FROM_STACK}/stackId"
curl -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' --data "${DATA}" "${URL}"

echo "
Stacks for ${TO_OWNER} :: ${TO_PROJECT} are now:"
curl -X GET --silent --header 'Accept: application/json' "${BASE_URL}/owner/${TO_OWNER}/project/${TO_PROJECT}/stackIds" | jq '.'