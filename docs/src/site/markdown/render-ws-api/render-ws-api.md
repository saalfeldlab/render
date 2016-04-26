# Render Web Services APIs

The Render Web Services module provides [level 2 REST] APIs to persisted collections of tile and 
transform specifications (see [data model]).  

# Dynamic API Documentation (Swagger) 

The [render web services] module includes an [OpenAPI specification] resource path 
( http://\<server\>:\<port\>/render-ws/swagger.json ) which can be utilized by tools like 
[Swagger UI] to dynamically generate a view of the available APIs.

The best way to look at the current API documentation is to install the [render web services] components
along with the source code archive from the latest [Swagger UI release].
After installation, the APIs can be viewed and tested at http://\<server\>:\<port\>/swagger-ui .

The [render web services basic installation] process includes [Swagger UI] installation.  
For low level details, see the [render web services install script].


# Static API Documentation

The following static documentation is periodically generated for those that want to browse the APIs 
without installing the services:
* [Static API List]
* [Static API Definitions]


  [data model]: <../data-model.md>
  [level 2 REST]: <http://martinfowler.com/articles/richardsonMaturityModel.html>
  [OpenAPI specification]: <https://openapis.org/specification>
  [render web services]: <../render-ws.md>
  [render web services basic installation]: <../render-ws.md#basic-installation>
  [render web services install script]: <../../../../../render-ws/src/main/scripts/install.sh>
  [Static API Definitions]: <definitions.md>
  [Static API List]: <paths.md>
  [Swagger UI]: <http://swagger.io/swagger-ui/>
  [Swagger UI release]: <https://github.com/swagger-api/swagger-ui/releases>