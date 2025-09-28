#!/bin/bash

# Leave ~6–10 GB free for the OS and filesystem cache since MongoDB benefits a lot from page cache
HEADROOM_MEM_GB=${1:-8}

# ----------------------------------------------------------
# Detect container memory (GB)
# ----------------------------------------------------------

# Try cgroup (works in most recent Docker versions)
if [ -f /sys/fs/cgroup/memory.max ]; then
    RAW_MEM=$(cat /sys/fs/cgroup/memory.max)
elif [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
    RAW_MEM=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)
else
    # Fallback: total system memory from free
    RAW_MEM=$(free -b | awk '/Mem:/ {print $2}')
fi

# Handle case where memory.max is "max" (no limit → use host mem)
if [ "${RAW_MEM}" = "max" ]; then
    RAW_MEM=$(free -b | awk '/Mem:/ {print $2}')
fi

CONTAINER_MEM_GB=$(( RAW_MEM / 1024 / 1024 / 1024 ))

echo "detected that container has ${CONTAINER_MEM_GB} GB memory available"

# ----------------------------------------------------------
# Memory allocations
# ----------------------------------------------------------

if [ "${CONTAINER_MEM_GB}" -lt 3 ]; then
    echo "ERROR: container memory is less than 3 GB, exiting"
    exit 1
fi

if [ "${HEADROOM_MEM_GB}" -gt $(( CONTAINER_MEM_GB / 2 )) ]; then
    HEADROOM_MEM_GB=1
fi

echo "setting aside ${HEADROOM_MEM_GB} GB for 'headroom' memory"

MONGOD_CACHE_SIZE_GB=$(( (CONTAINER_MEM_GB - HEADROOM_MEM_GB) / 2 ))
JETTY_MEM_GB=$(( CONTAINER_MEM_GB - HEADROOM_MEM_GB - MONGOD_CACHE_SIZE_GB ))

export RENDER_JETTY_MIN_AND_MAX_MEMORY="${JETTY_MEM_GB}g"

# ----------------------------------------------------------
# MongoDB configuration
# ----------------------------------------------------------

MONGOD_CONF_FILE="/etc/mongod.conf"
MONGOD_BACKUP_FILE="${MONGOD_CONF_FILE}.bak"

# update mongod.conf to set wiredTiger cache size if not already done
if [ ! -f "${MONGOD_BACKUP_FILE}" ]; then

  # Change ...
  #
  #   storage:
  #     dbPath: /var/lib/mongodb
  #   #  engine:
  #   #  wiredTiger:
  #
  # To ...
  #
  #   storage:
  #     dbPath: /var/lib/mongodb
  #   #  engine:
  #     wiredTiger:
  #       engineConfig:
  #         cacheSizeGB: 27

  sed -i.bak "s/#  wiredTiger:.*/  wiredTiger:\n    engineConfig:\n      cacheSizeGB: ${MONGOD_CACHE_SIZE_GB}/" "${MONGOD_CONF_FILE}"

fi

# ------------------------------------------------------------------------------------------------------
# From https://github.com/saalfeldlab/render/blob/master/docs/src/site/markdown/render-ws.md

echo "starting mongodb with cache size ${MONGOD_CACHE_SIZE_GB} GB"
/usr/bin/mongod -f ${MONGOD_CONF_FILE} &

export RENDER_JETTY_MIN_AND_MAX_MEMORY="${JETTY_MEM_GB}g"

# jetty_wrapper.sh start does not seem to work and we want to keep the container active anyway
# jetty_wrapper.sh run keeps the container active
echo "starting jetty_wrapper.sh run with memory ${RENDER_JETTY_MIN_AND_MAX_MEMORY}"
deploy/jetty_base/jetty_wrapper.sh run