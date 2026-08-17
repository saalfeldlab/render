#!/bin/bash

# Batch identifier appended to each slab group name (edit this for each round of runs).
SLAB_GROUP_SUFFIX="20260811b"

VM_IPS=(10.150.0.2 10.150.0.3 10.150.0.4 10.150.0.5 10.150.0.6 10.150.0.7)

printf "\nWhich VM do you want to use?\n\n"
select VM_IP in "${VM_IPS[@]}"; do
  if [ -n "${VM_IP}" ]; then
    break
  else
    echo "Invalid selection, try again."
  fi
done

printf "\nWhich wafer do you want to use?\n\n"
select WAFER in 60 61; do
  if [ -n "${WAFER}" ]; then
    break
  else
    echo "Invalid selection, try again."
  fi
done

echo
read -rp "Enter the first serial number (a multiple of 5 between 0 and 410): " FIRST_SERIAL_NUMBER

if [[ ! ${FIRST_SERIAL_NUMBER} =~ ^[0-9]+$ ]]; then
  printf "\nExiting, '%s' is not a number\n\n" "${FIRST_SERIAL_NUMBER}"
  exit 1
fi

# force base 10 so that zero padded values (e.g. 070) are not treated as octal
FIRST_SERIAL_NUMBER=$(( 10#${FIRST_SERIAL_NUMBER} ))

if (( FIRST_SERIAL_NUMBER > 410 )) || (( FIRST_SERIAL_NUMBER % 5 != 0 )); then
  printf "\nExiting, %d is not a multiple of 5 between 0 and 410\n\n" "${FIRST_SERIAL_NUMBER}"
  exit 1
fi

LAST_SERIAL_NUMBER=$(( FIRST_SERIAL_NUMBER + 4 ))

# even serial numbers are the first half of a project's slabs, odd ones are the second half
if (( FIRST_SERIAL_NUMBER % 2 == 0 )); then
  KEEP_OR_REMOVE="[k]ept"
  FIRST_PROJECT_NUMBER=${FIRST_SERIAL_NUMBER}
else
  KEEP_OR_REMOVE="[r]emoved"
  FIRST_PROJECT_NUMBER=$(( FIRST_SERIAL_NUMBER - 5 ))
fi

LAST_PROJECT_NUMBER=$(( FIRST_PROJECT_NUMBER + 9 ))

FIRST_SERIAL=$(printf "%03d" "${FIRST_SERIAL_NUMBER}")
LAST_SERIAL=$(printf "%03d" "${LAST_SERIAL_NUMBER}")
FIRST_PROJECT=$(printf "%03d" "${FIRST_PROJECT_NUMBER}")
LAST_PROJECT=$(printf "%03d" "${LAST_PROJECT_NUMBER}")

SLAB_GROUP="s${FIRST_SERIAL}_to_s${LAST_SERIAL}_${SLAB_GROUP_SUFFIX}"
BATCH_NAME="rough-w${WAFER}-s${FIRST_SERIAL}-to-s${LAST_SERIAL}"
PROJECT_GROUP="w${WAFER}_serial_${FIRST_PROJECT}_to_${LAST_PROJECT}"

echo "
Set up for slab group ${SLAB_GROUP} from project group ${PROJECT_GROUP}:

  On ${VM_IP}, run:

    ./db-restore-collections.sh --pattern 'janelia/00_gc/.*s${FIRST_PROJECT}'

    ./other/remove-stacks.sh

      you want stacks to be ${KEEP_OR_REMOVE}
      then enter ' 1 2 3 4 5 6 7 8 9 10 '


  On launch box, run:

    ./02_run_pipeline.sh  ${VM_IP}  00_rough_align/pipe.00.w${WAFER}.icc-match-mat.json  120  4  premium  120  ${BATCH_NAME}


  After the run completes (typically 8 to 12 hours), on ${VM_IP}, run:

    ./list-stacks.sh

    ./list-match-collections.sh

    ./db-dump-google-collections.sh

      Select database:  render
      Select stage:     00_par
      Select project:   ${PROJECT_GROUP}
      Enter slab-group: ${SLAB_GROUP}

      Enter collection pattern regex:  .*

      Should dump collections to:
        /mnt/disks/mongodb_dump_fs/dump/google/00_par/${PROJECT_GROUP}/${SLAB_GROUP}/render

    ./db-dump-google-collections.sh

      Select database:  match
      Select stage:     00_par
      Select project:   ${PROJECT_GROUP}
      Enter slab-group: ${SLAB_GROUP}

      Enter collection pattern regex:  .*

      Should dump collections to:
        /mnt/disks/mongodb_dump_fs/dump/google/00_par/${PROJECT_GROUP}/${SLAB_GROUP}/match
"
