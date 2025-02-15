#!/bin/bash

# Launch an interactive bash shell within an already running container when only one container is running.
docker exec --interactive --tty "$(docker ps -q)" /bin/bash