#!/bin/bash

PROJECT_URL="http://localhost:8080/render-ws/v1/owner/hess_wafers_60_61/project/w61_serial_070_to_079"

for i in 2 3 4 5 6 7 8 9; do
  for REGION in r00 r01; do

    for SUFFIX in gc_pa_mat gc_pa_mat_render; do

      STACK="w61_s07${i}_${REGION}_${SUFFIX}"  # w61_s072_r00_gc_pa_mat
      STACK_URL="${PROJECT_URL}/stack/${STACK}"
      SECTION_COUNT=$(curl --silent "${STACK_URL}" | grep "sectionCount")
      TILE_COUNT=$(curl --silent "${STACK_URL}" | grep "tileCount")

      printf "%-40s %s %s\n" "${STACK}" "${SECTION_COUNT}" "${TILE_COUNT}"

    done

    echo

  done
done