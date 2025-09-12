#!/bin/bash

OWNER="${1:-hess_wafers_60_61}"
PROJECT="${2:-w60_serial_360_to_369}"
URL="http://localhost:8080/render-ws/v1/owner/${OWNER}/project/${PROJECT}/stackIds"
curl -X GET --silent --header 'Accept: application/json' "${URL}" | jq '.'