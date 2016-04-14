# Render Web Services API

The [render web services] module includes an [OpenAPI specification] resource path 
( http://\<server\>:\<port\>/render-ws/swagger.json ) which can be utilized by tools like 
[Swagger UI] to dynamically generate a view of the available APIs.

The best way to look at the current API documentation is to install the [render web services] components
and deploy the source code archive from the latest [Swagger UI release] into the Jetty webapps directory.

After installation, the APIs can be viewed at http://\<server\>:\<port\>/swagger-ui


You can download the [Swagger UI Source code archive] from GitHub, extract it into Fetching the [Swagger UI] components in the ${JETTY_BASE}/webapps directory  


The Render Web Services module provides [level 2 REST APIs] to persisted collections of tile and 
transform specifications (see [data model]).  

A static [API listing] is curated for each release.  The module also exposes an [OpenAPI specification] which 
can be utilized by tools like [Swagger UI] to dynamically generate a view of the available APIs.     




  [OpenAPI specification]: <https://openapis.org/specification>
  [render web services]: <../render-ws.md>
  [Swagger UI]: <http://swagger.io/swagger-ui/>
  [Swagger UI release]: <https://github.com/swagger-api/swagger-ui/releases>
  [war file]: <https://docs.oracle.com/javaee/7/tutorial/packaging003.htm>