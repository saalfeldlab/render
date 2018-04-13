# Render Web Services Docker Instructions

# Building Images

To build the full render-ws image: 

```bash
# cd to root directory of render repo (where Dockerfile is located) 
docker build -t render-ws:latest --target render-ws .
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
# with database running on same host at default port
docker run -p 8080:8080 -e "MONGO_HOST=localhost" render-ws:latest

# with customized environment variables in an file
docker run -p 8080:8080 --env-file ./env.janelia.mongo render-ws:latest
```

You must explicitly specify either the MONGO_HOST or MONGO_CONNECTION_STRING environment variables
when running a container based upon the full render-ws image.  

The full render-ws image supports the following set of environment variables (specified values are defaults):

### Environment Variables 

```bash
# ---------------------------------
# database connection parameters 

# if a connection string is specified, other mongo connection variables are ignored
# format details are here: https://docs.mongodb.com/manual/reference/connection-string
MONGO_CONNECTION_STRING=""  

# should be 'y' if you are using a connection string that includes username and password 
MONGO_CONNECTION_STRING_USES_AUTH="n"

MONGO_HOST=""
MONGO_PORT=""

# if authentication is not needed, leave these empty
MONGO_USERNAME=""                            
MONGO_PASSWORD=""
MONGO_AUTH_DB=""

# ---------------------------------
# web service JVM parameters
 
JAVA_OPTIONS="-Xms3g -Xmx3g -server -Djava.awt.headless=true"

# ---------------------------------
# web service logging parameters

# appender options are 'STDOUT', 'FILE', or 'NONE'
LOG_ACCESS_ROOT_APPENDER="STDOUT"
LOG_JETTY_ROOT_APPENDER="STDOUT"

# log level options are: 'OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', or 'TRACE'
LOG_JETTY_ROOT_LEVEL="WARN" 
LOG_JETTY_JANELIA_LEVEL="WARN" 

# ---------------------------------
# web service rendering parameters

# use this to improve dynamic rendering speed for zoomed-out views
# views that contain more than this number of tiles will have bounding boxes rendered instead of actual tile content 
WEB_SERVICE_MAX_TILE_SPECS_TO_RENDER="20"          
                                             
# if left empty, the image processor cache will be sized at half of the memory allocated to the JVM
WEB_SERVICE_MAX_IMAGE_PROCESSOR_GB="" 

# ---------------------------------
# viewing tools parameters

NDVIZHOST=""                                
NDVIZPORT=""

# use the url parameter if you need https (overrides host and port parameters)
NDVIZ_URL=""

# specify without protocol (assumes http) like: 'renderer-catmaid:8080'
VIEW_CATMAID_HOST_AND_PORT=""                
VIEW_DYNAMIC_RENDER_HOST_AND_PORT=""

VIEW_RENDER_STACK_OWNER=""
VIEW_RENDER_STACK_PROJECT=""
VIEW_RENDER_STACK=""

VIEW_MATCH_OWNER=""
VIEW_MATCH_COLLECTION=""
```

### Entrypoint
 
The default render-ws image entrypoint script is here:
[render-config-entrypoint.sh](../../../../render-ws/src/main/scripts/docker/render-config-entrypoint.sh)

# Other Useful Docker Commands

To open a Bourne shell on the latest running render-ws container: 

```bash
docker exec -it $(docker ps --latest --quiet) /bin/sh
```

To open a Bourne shell on a new render-ws container without running jetty: 

```bash
docker run -it --entrypoint /bin/sh render-ws:latest
```

To remove all stopped containers: 

```bash
docker rm $(docker ps --all --quiet)
```

To remove all untagged \(\<none\>\) images \(including the large build artifacts image if you did not tag it\): 

```bash
docker image rm $(docker images --filter "dangling=true" -q --no-trunc)
```


