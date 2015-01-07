#!/bin/bash

# ./gen-mipmaps.sh <source image> <mipmap format> <max level>
# ./gen-mipmaps.sh col0060_row0140_cam0.tif jpg 6

# for filter details, see http://www.imagemagick.org/Usage/filter/nicolas/

SOURCE_IMAGE="$1"
FORMAT="$2"
MAX_LEVEL="$3"

OUT_DIR="${FORMAT}"
mkdir -p ${OUT_DIR}

convert ${SOURCE_IMAGE} ${OUT_DIR}/level_0.${FORMAT}

for LEVEL in $(eval echo {1..${MAX_LEVEL}}); do
  SOURCE_LEVEL=$(( LEVEL - 1 ))
  convert ${OUT_DIR}/level_${SOURCE_LEVEL}.${FORMAT} -filter Lanczos -distort Resize 50% ${OUT_DIR}/level_${LEVEL}.${FORMAT}
done

ls -alh ${OUT_DIR}
