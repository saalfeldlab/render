#!/bin/sh

# ======================================================================================
# Runs web services example documented at:
#   https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws-example.md

set -e

echo """
# --------------------------------------------------------------------
# 1. Setup Environment
"""

# assumes current directory is still the cloned render repository root (./render)

export CLIENT_SCRIPTS=`readlink -m ./render-ws-java-client/src/main/scripts`
export EXAMPLE_1_PARAMS="--baseDataUrl http://localhost:8080/render-ws/v1 --owner demo --project example_1"
export EXAMPLE_1_DIR=`readlink -m ./examples/example_1`

echo """
# --------------------------------------------------------------------
# 2. Import Acquisition Data (Acquire)
"""

# create v1_acquire stack
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --action CREATE --cycleNumber 1 --cycleStepNumber 1

# import v1_acquire tile and transform data
${CLIENT_SCRIPTS}/import_json.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --transformFile ${EXAMPLE_1_DIR}/cycle1_step1_acquire_transforms.json ${EXAMPLE_1_DIR}/cycle1_step1_acquire_tiles.json

# complete v1_acquire stack
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --action SET_STATE --stackState COMPLETE


echo """
# --------------------------------------------------------------------
# 3. Import Intra-Layer Alignment Data (Montage)
"""

# create v1_montage stack
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_montage --action CREATE --cycleNumber 1 --cycleStepNumber 2

# apply v1_montage transform data to existing v1_acquire data, storing results in v1_montage stack
${CLIENT_SCRIPTS}/import_transform_changes.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --targetStack v1_montage --transformFile ${EXAMPLE_1_DIR}/cycle1_step2_montage_changes.json

# complete v1_montage stack
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_montage --action SET_STATE --stackState COMPLETE


echo """
# --------------------------------------------------------------------
# 4. Import Inter-Layer Alignment Data (Align)
"""

# create v1_align stack
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_align --action CREATE --cycleNumber 1 --cycleStepNumber 3

# apply v1_align transform data to existing v1_acquire data, storing results in v1_align stack
${CLIENT_SCRIPTS}/import_transform_changes.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --targetStack v1_align --transformFile ${EXAMPLE_1_DIR}/cycle1_step3_align_changes.json

# complete v1_align stack
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_align --action SET_STATE --stackState COMPLETE


echo """
# --------------------------------------------------------------------
# 5. Refine Alignment Data (Align TPS)
"""

# create v1_align_tps stack
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_align_tps --action CREATE --cycleNumber 1 --cycleStepNumber 4

# warp v1_montage stack to v1_align stack, storing results in v1_align_tps stack
${CLIENT_SCRIPTS}/generate_warp_transforms.sh ${EXAMPLE_1_PARAMS} --stack v1_montage --alignStack v1_align --targetStack v1_align_tps 3407 3408

# complete v1_align_tps stack
${CLIENT_SCRIPTS}/manage_stacks.sh ${EXAMPLE_1_PARAMS} --stack v1_align_tps --action SET_STATE --stackState COMPLETE


echo """
# --------------------------------------------------------------------
# 6. View Data Dashboards
"""

curl http://localhost:8080/render-ws/view/stacks.html?renderStackOwner=demo&renderStackProject=example_1


echo """
# --------------------------------------------------------------------
# 7. Render Results to Disk for Review - skip
"""

# TODO: find out why this crashes - probably a container memory issue

# render 2048-x-2048 boxes for layers 3407 and 3408
#${CLIENT_SCRIPTS}/render_catmaid_boxes.sh ${EXAMPLE_1_PARAMS} --stack v1_align_tps --rootDirectory ${EXAMPLE_1_DIR}/boxes --height 2048 --width 2048 --format jpg --maxLevel 9 --maxOverviewWidthAndHeight 192 3407 3408


echo """
# --------------------------------------------------------------------
# 8. Map Trace Coordinates
"""

${CLIENT_SCRIPTS}/map_coordinates.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --z 3407 --fromJson ${EXAMPLE_1_DIR}/v1_acquire_s3407_world_points.json --toJson ${EXAMPLE_1_DIR}/v1_acquire_s3407_local_points.json

${CLIENT_SCRIPTS}/map_coordinates.sh ${EXAMPLE_1_PARAMS} --stack v1_acquire --z 3407 --localToWorld --fromJson ${EXAMPLE_1_DIR}/v1_acquire_s3407_local_points.json --toJson ${EXAMPLE_1_DIR}/v1_align_tps_s3407_world_points.json

echo """

The final results are saved to v1_align_tps_s3407_world_points.json and should include 3 mapped coordinates
plus one error for the original coordinate (7777.0, 8888.0) that did not map to any v1_acquire tile.

"""

cat ${EXAMPLE_1_DIR}/v1_align_tps_s3407_world_points.json
