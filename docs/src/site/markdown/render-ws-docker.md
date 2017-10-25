# Render Web Services Docker Instructions



# Build

to build the render-ws, you should do 

```bash
docker build -t IMAGE_NAME --target render-ws .
```

to compile the JARs you can target the first builder stage with

```bash
docker build -t IMAGE_NAME --target builder .
```

# Running render-ws
The image paramaterizing many of the configuration variables via runtime environment variables that should be set via docker run or docker-compose

Here are the present environment variables and their default values

* MONGO_HOST=localhost
* MONGO_PORT=27017
* MONGO_USERNAME= (default is no authentication)
* MONGO_PASSWORD= (default is no authenticaiton)
* NDVIZHOST= (default blank)
* NDVIZPORT= (default blank)
* NDVIZ_URL= (default blank, use either NDVIZHOST:NDVIZPORT or NDVIZ_URL if you want to specify https)
* JAVA_OPTIONS="-Xms3g -Xmx3g -server -Djava.awt.headless=true"




