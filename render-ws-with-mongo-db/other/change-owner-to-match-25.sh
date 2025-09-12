#!/bin/bash

FROM_OWNER="hess_wafers_60_61"
TO_OWNER="hess_wafer_60_match_25"
PROJECT="w60_serial_360_to_369"

SERIAL_INDEXES="360 361 362 363 364 365 366 367 368 369"

for SERIAL_INDEX in ${SERIAL_INDEXES}; do

  GC="w60_s${SERIAL_INDEX}_r00_gc"      # w60_s360_r00_gc
  PA="${GC}_pa"                         # w60_s360_r00_gc_pa
  PA_MAT="${PA}_mat"                    # w60_s360_r00_gc_pa_mat
  PA_MAT_R="${PA_MAT}_render"           # w60_s360_r00_gc_pa_mat_render
  PA_MAT_R_A="${PA_MAT_R}_align"        # w60_s360_r00_gc_pa_mat_render_align
  PA_MAT_R_A_A="${PA_MAT_R_A}_affine"   # w60_s360_r00_gc_pa_mat_render_align_affine
  PA_ROUGH="${PA}_rough"                # w60_s360_r00_gc_pa_rough

  for STACK in "${GC}" "${PA}" "${PA_MAT}" "${PA_MAT_R}" "${PA_MAT_R_A}" "${PA_MAT_R_A_A}" "${PA_ROUGH}"; do
    echo "Changing owner of ${STACK} from ${FROM_OWNER} to ${TO_OWNER}"
    DATA="{\"owner\": \"${TO_OWNER}\", \"project\": \"${PROJECT}\", \"stack\": \"${STACK}\"}"
    URL="http://localhost:8080/render-ws/v1/owner/${FROM_OWNER}/project/${PROJECT}/stack/${STACK}/stackId"
    curl -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' --data "${DATA}" "${URL}"
  done

  MATCH_COLLECTION="${PA_MAT_R}_match"  # w60_s360_r00_gc_pa_mat_render_match

  echo "Changing owner of ${MATCH_COLLECTION} from ${FROM_OWNER} to ${TO_OWNER}"
  DATA="{\"owner\": \"${TO_OWNER}\", \"name\": \"${MATCH_COLLECTION}\"}"
  URL="http://localhost:8080/render-ws/v1/owner/${FROM_OWNER}/matchCollection/${MATCH_COLLECTION}/collectionId"
  curl -X PUT --header 'Content-Type: application/json' --header 'Accept: text/plain' --data "${DATA}" "${URL}"

done