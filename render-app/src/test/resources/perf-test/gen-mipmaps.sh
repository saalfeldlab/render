#!/bin/bash

# ./gen-mipmaps.sh <source image> <mipmap format> <max level>
#
# Example invocations:
#
#      ./gen-mipmaps.sh col0060_row0140_cam0.tif jpg 6
#
#      for file in level_0/*; do for fmt in jpg tif; do ./gen-mipmaps.sh $file $fmt 3; done; done
#      for fmt in pgm png; do ./gen-mipmaps.sh level_0/col0060_row0140_cam0.tif $fmt 3; done
#
# To read about filter details, see http://www.imagemagick.org/Usage/filter/nicolas/

CURRENT_SOURCE="$1"
BASE_NAME=`basename ${CURRENT_SOURCE}`
FORMAT="$2"
MAX_LEVEL="$3"
OUT_DIR="mipmaps/${FORMAT}"

mkdir -p ${OUT_DIR}

echo """
--------------------------------------------------------
Generating mipmaps for ${CURRENT_SOURCE} into ${OUT_DIR}
"""

for LEVEL in $(eval echo {1..${MAX_LEVEL}}); do

  if (( LEVEL <= MAX_LEVEL )); then

    TO_FILE="${OUT_DIR}/${BASE_NAME}_level_${LEVEL}_mipmap.${FORMAT}"

    if [[ -a ${TO_FILE} ]]; then
      echo -n "skipped creation of existing mipmap "
    else
      convert ${CURRENT_SOURCE} -filter Lanczos -distort Resize 50% ${TO_FILE}
      echo -n "created mipmap "
    fi

    ls -lh ${TO_FILE}
    CURRENT_SOURCE="${TO_FILE}"

  fi

done

echo
