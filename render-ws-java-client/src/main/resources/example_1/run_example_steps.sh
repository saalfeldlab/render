#!/bin/bash

set -e

# ------------------------------------------------------------------------------------------------------
# From https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

# 7. Start MongoDB
service mongodb start

# 8. Start Jetty
deploy/jetty_base/jetty_wrapper.sh start

# ------------------------------------------------------------------------------------------------------
# From https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws-example.md

# 1. Setup Environment
export CLIENT_SCRIPTS=`readlink -m ./render-ws-java-client/src/main/scripts`
export EXAMPLE_1_PARAMS="--baseDataUrl http://localhost:8080/render-ws/v1 --owner demo --project example_1"
export EXAMPLE_1_DIR=`readlink -m ./examples/example_1`

echo """
=============================================================================
Step 2. Import Acquisition Data (Acquire)

"""
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --action CREATE --cycleNumber 1 --cycleStepNumber 1 
${CLIENT_SCRIPTS}/import_json.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --transformFile ${EXAMPLE_1_DIR}/cycle1_step1_acquire_transforms.json ${EXAMPLE_1_DIR}/cycle1_step1_acquire_tiles.json 
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --action SET_STATE --stackState COMPLETE

echo """
=============================================================================
Step 3. Import Intra-Layer Alignment Data (Montage)

"""
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_montage --action CREATE --cycleNumber 1 --cycleStepNumber 2
${CLIENT_SCRIPTS}/import_transform_changes.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --targetStack v1_montage --transformFile ${EXAMPLE_1_DIR}/cycle1_step2_montage_changes.json 
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_montage --action SET_STATE --stackState COMPLETE

echo """
=============================================================================
Step 4. Import Inter-Layer Alignment Data (Align)

"""
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_align --action CREATE --cycleNumber 1 --cycleStepNumber 3
${CLIENT_SCRIPTS}/import_transform_changes.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --targetStack v1_align --transformFile ${EXAMPLE_1_DIR}/cycle1_step3_align_changes.json 
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_align --action SET_STATE --stackState COMPLETE

echo """
=============================================================================
Step 5. Refine Alignment Data (Align TPS)

"""
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_align_tps --action CREATE --cycleNumber 1 --cycleStepNumber 4
${CLIENT_SCRIPTS}/generate_warp_transforms.sh ${EXAMPLE_1_PARAMS} --stack v1_montage --alignStack v1_align --targetStack v1_align_tps 3407 3408  
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_align_tps --action SET_STATE --stackState COMPLETE

# 6. View Data Dashboards

echo """
=============================================================================
Step 
7. Render Results to Disk for Review

"""
#${CLIENT_SCRIPTS}/render_catmaid_boxes.sh ${EXAMPLE_1_PARAMS} --stack v1_align_tps --rootDirectory ${EXAMPLE_1_DIR}/boxes --height 2048 --width 2048 --format jpg --maxLevel 9 --maxOverviewWidthAndHeight 192 3407 3408

# NOTE: Set JVM max memory to 1G and reduce render to just one section and two levels so results fit in default Docker container,
#       Without these changes, JVM will exit with code 137.
export MAX_MEMORY="1G"
${CLIENT_SCRIPTS}/render_catmaid_boxes.sh ${EXAMPLE_1_PARAMS} --stack v1_align_tps --rootDirectory ${EXAMPLE_1_DIR}/boxes --height 2048 --width 2048 --format jpg --maxLevel 1 --maxOverviewWidthAndHeight 192 3407

echo """
=============================================================================
Step 8. Map Trace Coordinates

"""
${CLIENT_SCRIPTS}/map_coordinates.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --z 3407 --fromJson ${EXAMPLE_1_DIR}/v1_acquire_s3407_world_points.json --toJson ${EXAMPLE_1_DIR}/v1_acquire_s3407_local_points.json  
${CLIENT_SCRIPTS}/map_coordinates.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --z 3407 --localToWorld --fromJson ${EXAMPLE_1_DIR}/v1_acquire_s3407_local_points.json --toJson ${EXAMPLE_1_DIR}/v1_align_tps_s3407_world_points.json  

echo """
The final mapped results should include 3 mapped coordinates plus one error for the original coordinate (7777.0, 8888.0) that did not map to any v1_acquire tile:
"""
cat ${EXAMPLE_1_DIR}/v1_align_tps_s3407_world_points.json
