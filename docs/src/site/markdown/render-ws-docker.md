# Render Web Services Docker Instructions

The render web services can be built and deployed within [Docker](https://docs.docker.com/).
You'll need to first [install Docker](https://docs.docker.com/#run-docker-anywhere) for your OS. 

# Building Images

To build the full render-ws image: 

```bash
# cd to root directory of render repo (where Dockerfile is located) 
docker build -t render-ws:latest --target render-ws .
```

You can speed up future builds by building and tagging the build environment:

```bash
# cd to root directory of render repo (where Dockerfile is located) 
docker build -t render-ws-build-environment:latest --target build_environment .
```

To build just the web service JARs without deploying into a Jetty container:

```bash
# cd to root directory of render repo (where Dockerfile is located) 
# saves JAR and WAR files in /root/render-lib 
docker build -t render-ws:latest-build --target builder .
```

# Running Images

To run the full render-ws image:

```bash
# with database running on same host at default port and no authentication
docker run -p 8080:8080 -e "MONGO_HOST=localhost" --rm render-ws:latest

# with customized environment variables in a file (named dev.env)
docker run -p 8080:8080 --env-file dev.env --rm render-ws:latest
```

You must explicitly specify either the MONGO_HOST or MONGO_CONNECTION_STRING environment variables
when running a container based upon the full render-ws image.  

The full render-ws image supports the following set of environment variables (specified values are defaults):

### Environment Variables 

```bash
# ---------------------------------
# Docker Environment Variables File
# 
# NOTE: don't use quotes around values - details here:
#       https://docs.docker.com/engine/reference/commandline/run/#set-environment-variables--e---env---env-file

# ---------------------------------
# Database Connection Parameters 

# if a connection string is specified, other mongo connection variables are ignored
# format details are here: https://docs.mongodb.com/manual/reference/connection-string
# example: mongodb://<user>:<password>@replRender/render-mongodb2.int.janelia.org,render-mongodb3.int.janelia.org/admin
MONGO_CONNECTION_STRING=  

# should be 'y' if you are using a connection string that includes username and password 
MONGO_CONNECTION_STRING_USES_AUTH=n

MONGO_HOST=
MONGO_PORT=

# if authentication is not needed (or you are using a connection string), leave these empty
MONGO_USERNAME=                            
MONGO_PASSWORD=
MONGO_AUTH_DB=

# ---------------------------------
# Web Service JVM Parameters
 
JAVA_OPTIONS=-Xms3g -Xmx3g -server -Djava.awt.headless=true

# ---------------------------------
# Web Service Threadpool Parameters (leave these alone unless you really know what you are doing)

JETTY_THREADPOOL_MIN_THREADS=10
JETTY_THREADPOOL_MAX_THREADS=200

# ---------------------------------
# Web Service Logging Parameters

# appender options are 'STDOUT', 'FILE', or 'NONE'
LOG_ACCESS_ROOT_APPENDER=STDOUT
LOG_JETTY_ROOT_APPENDER=STDOUT

# log level options are: 'OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', or 'TRACE'
LOG_JETTY_ROOT_LEVEL=WARN 
LOG_JETTY_JANELIA_LEVEL=WARN 

# ---------------------------------
# Web Service Rendering Parameters

# use this to improve dynamic rendering speed for zoomed-out views,
# views that contain more than this number of tiles will have bounding boxes rendered instead of actual tile content 
WEB_SERVICE_MAX_TILE_SPECS_TO_RENDER=20          
                                             
# if left empty, the image processor cache will be sized at half of the memory allocated to the JVM
WEB_SERVICE_MAX_IMAGE_PROCESSOR_GB= 

# ---------------------------------
# Viewing Tools Parameters

NDVIZHOST=                                
NDVIZPORT=

# use the url parameter if you need https (overrides host and port parameters)
NDVIZ_URL=

# specify without protocol (assumes http) like: 'renderer-catmaid:8080'
VIEW_CATMAID_HOST_AND_PORT=                
VIEW_DYNAMIC_RENDER_HOST_AND_PORT=

VIEW_RENDER_STACK_OWNER=
VIEW_RENDER_STACK_PROJECT=
VIEW_RENDER_STACK=

VIEW_MATCH_OWNER=
VIEW_MATCH_COLLECTION=
```

### Entrypoints

The following entrypoints are provided:
* /render-docker/[render-run-jetty-entrypoint.sh](../../../../render-ws/src/main/scripts/docker/render-run-jetty-entrypoint.sh) (default)
* /render-docker/[render-export-jetty-entrypoint.sh](../../../../render-ws/src/main/scripts/docker/render-export-jetty-entrypoint.sh)

### Exporting JETTY_BASE for External Use

If you'd like to run the render-ws application on an external jetty server (outside of Docker), you can use 
/render-docker/[render-export-jetty-entrypoint.sh](../../../../render-ws/src/main/scripts/docker/render-export-jetty-entrypoint.sh)
to configure a container within Docker and then export the configured JETTY_BASE to a mounted volume for external use.
  
```bash
# export files to <working-directory>/jetty_base_<run-time> (change or drop --env-file as needed)
docker run -it --mount type=bind,source="$(pwd)",target=/render-export \
               --env-file ./env.janelia.prod \
               --entrypoint /render-docker/render-export-jetty-entrypoint.sh \
               --rm \
               render-ws:latest
```

# Other Useful Docker Commands

To open a Bourne shell on the latest running render-ws container: 

```bash
docker exec -it $(docker ps --latest --quiet) /bin/sh
```

To open a Bourne shell on a new render-ws container without running jetty (and remove the container on exit): 

```bash
docker run -it --entrypoint /bin/sh --rm render-ws:latest
```

To remove all stopped containers: 

```bash
docker rm $(docker ps --all --quiet)
```

To remove all untagged (\<none\>) images (including the large build artifacts image if you did not tag it): 

```bash
docker image rm $(docker images --filter "dangling=true" -q --no-trunc)
```
