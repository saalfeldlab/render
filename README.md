# Render Tools and Services

A collection of Java tools and HTTP services (APIs) for rendering transformed image tiles that includes:

  - a JSON [data model] for render, tile, and transform specifications,
  - a Java stand-alone [render application] / library,
  - a set of [render web services] that provide [RESTful APIs] to 
    persisted collections of tile and transform specifications, and 
  - a set of [Java clients] for processing/rendering data that utilize the [render web services] 
    to retrieve and/or store persisted specifications.
  - [Docker packaging] for building the libraries and deploying the [render web services]  
  
  ![Render Components Diagram](docs/src/site/resources/image/render-components.png)
  
  [data model]: <docs/src/site/markdown/data-model.md>
  [Java clients]: <docs/src/site/markdown/render-ws-java-client.md>
  [level 2 REST]: <http://martinfowler.com/articles/richardsonMaturityModel.html>
  [render application]: <docs/src/site/markdown/render-app.md>
  [render web services]: <docs/src/site/markdown/render-ws.md>
  [RESTful APIs]: <docs/src/site/markdown/render-ws-api/render-ws-api.md>
  [Docker packaging]: <docs/src/site/markdown/render-ws-docker.md>
  
  
# Openseadragon Viewer for Render service:
  
  In this branch Openseadragon Viewer feature is developed for the Render Service. 
  
  Openseadragon is a web-based viewer for high resolution zoomable images implemented in Javascript. To know more about it please refer (https://openseadragon.github.io/)
  
  In this branch Rendered images are converted to Deep Zoom images to feed into openseadragon viewer. 
  
  MagickSlicer is used to convert the rendered images to DZI(Deep Zoom Images) (https://github.com/VoidVolker/MagickSlicer)
  
  The Rendered images and the converted DZI images are stored on a http server, which are used by openseadragon viewer.
  
  A python script is used to run the DZI image convertion pipeline on the HPC cluster. This python script needs to be placed on HPC Cluster and 
  the file path should be provided as the parameter in the Dockerfile.
  
  Here are the parameters which should be provided in Dockerfile to enable openseadragon viewer feature.
  
  VIEW_OPENSEADRAGON_HOST_AND_PORT=""  (render-ws/src/main/webapp/view/openseadragon.html file web URL)
  VIEW_DATA_PREP="" \ (python file Path on the HPC cluster, openseadragon/derived_data_preparation_code/data_preparation.py)
  VIEW_DATA_PREPSH="" \ (magick-slicer shell script path which is placed on HPC cluster, https://github.com/VoidVolker/MagickSlicer)
  VIEW_OPENSEADRAGON_DATA_HOST="" \ (HTTP webserver where the rendered and DZI images are placed)
  VIEW_OPENSEADRAGON_DATA_SOURCE_FOLDER="" \ (rendered images path on HPC cluster or http webserver link)
  VIEW_OPENSEADRAGON_DATA_DESTINATION_FOLDER="" \ (Destination folder PATH on the HPC cluster)
  
  
  In this branch, there is feature where you can submit a job to the cluster to convert rendered images to DZI images, cluster details and credentials can be provided through the user interface in stacks.html page.
  
  you can view the DZI images of the stacks in the openseadragon viewer by clicking on 'view in openseadragon' hyperlink in the stacks section.
  
  
  
  
  
  
  
  
