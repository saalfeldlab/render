# TrakEM2 Integration

The [render data model] utilizes the same [MPICBG module] transformation classes utilized by 
the [TrakEM2 ImageJ plugin], making it possible (with a little effort) to migrate alignment data between 
TrakEM2 projects and render stacks.  To facilitate data management for extremely large EM volumes, 
the [render data model] includes the following optimizations that should be considered when exporting 
TrakEM2 projects to render or importing render stacks into TrakEM2 projects:

* Shared transforms (and lists of transforms) for things like lens correction are stored separately in render and
are "linked" via reference transform specifications.  This significantly reduces render's transform data storage 
footprint when thousands or millions of tiles share the same set of transforms.  If you are exporting TrakEM2 data 
to render that contains shared transforms, you should store them as reference transforms rather than duplicating 
the data in render unless you only plan to export a small number of patches. 

* Common shared masks are stored as paths in render but are stored as separate (duplicate) files for each patch 
in TrakEM2.  This significantly reduces render's mask storage footprint when thousands or millions of tiles share 
the same mask.  If you are importing render data with masks into TrakEM2, keep in mind that a copy of each mask 
will be stored for each tile/patch.

# Prototype TrakEM2 Import/Export Plugins

At Janelia, we have rarely needed to migrate data between render and TrakEM2 so the migration tools 
we've developed are just prototypes.  The tools may still be useful to others either as developed or simply 
as examples.  The following table lists the plugins that have been developed:  
  
Name                                 | Description                  
------------------------------------ | -----------
[Export To Render As Is]             | Exports TrakEM2 patch data to a render stack. 
[Export To Render Using Basis Stack] | Exports TrakEM2 last patch transforms to a render stack using a basis stack. 
[Import From Render]                 | Imports render tiles into an existing TrakEM2 project. 
[New Project From Render]            | Imports render tiles into a new TrakEM2 project. 

## Build Plugins JAR

The ImageJ plugins JAR is built and packaged as part of render's standard [maven] build.  

If you are familiar with [maven], the easiest way to build the plugins JAR is to follow the [render web services] 
basic installation steps 1 through 4 (Build the Render Modules).  The plugins JAR will be saved to:
```bash
render/trakem2-scripts/target/render-ws_trakem2_plugins-<version>-SNAPSHOT.jar
``` 

An alternative option is to use [Docker](https://docs.docker.com/) to build the plugins JAR.  Simply clone the 
render repository and from GitHub, cd to the repo root directory, and then run a docker build using the builder target:

```bash
# cd to root directory of render repo (where Dockerfile is located) and create builder image
docker build -t render-ws:latest-build --target builder .

# create container from image (will print container id)
docker create render-ws:latest-build

# copy /root/render-lib/render-ws_trakem2_plugins-<version>-SNAPSHOT.jar to Fiji plugins directory
# look at output from builder image creation step to get exact JAR name with version
docker cp <containerId>:/root/render-lib/render-ws_trakem2_plugins-<version>-SNAPSHOT.jar <Fiji directory>/plugins

# remove the builder container 
docker rm -f <containerId>
``` 

## Deploy Plugins JAR

The render plugins JAR should be copied to the plugins directory for your Fiji (ImageJ) installation.  On 
a standard Mac installation, this directory is /Applications/Fiji.app/plugins.  There should only be one 
render plugins JAR deployed, so make sure to remove any prior render plugins JAR files you might have.  

Once the plugins JAR is deployed, start Fiji.  The render plugin options should be visible under the File -> New, 
File -> Import and File -> Export menus. 

## Run Plugins



### Export To Render As Is

Exports TrakEM2 patch data to a render stack.  
All transforms are exported as they exist in TrakEM2.  
All mask paths are exported as they exist in TrakEM2 (even when differing paths reference the same mask bytes).

#### Usage:
```
open TrakEM2 project
select menus File -> Export -> Render Web Services As Is
enter export parameters (see descriptions below)
progress is logged to ImageJ Log window
process is complete when 'org.janelia.render.trakem2.ExportToRenderAsIs_Plugin.run: exit' is logged
  
```

#### Parameters:
Name                              | Description                
--------------------------------- | -----------
Render Web Services Base URL      | base render web services URL (e.g. http://host:port/render-ws/v1)
TrakEM2 Min Z                     | z value of first layer to export
TrakEM2 Max Z                     | z value of last layer to export
Only Export Visible Patches       | if checked, only export visible TrakEM2 patches
Use Patch Title For Tile ID       | if checked, use patch title for tile ID; otherwise use patch object ID as tile ID");
Target Render Stack Owner         | name of render stack owner
Target Render Stack Project       | name of render stack project
Target Render Stack Name          | name of render stack
Stack Resolution X                | render stack x resolution (nm/pixel)
Stack Resolution Y                | render stack y resolution (nm/pixel)
Stack Resolution Z                | render stack z resolution (nm/pixel)
Complete Stack After Export       | if checked, completes render stack after importing all TrakEM2 patches

#### Source Code: 
* [ExportToRenderAsIs_Plugin.java](../../../../trakem2-scripts/src/main/java/org/janelia/render/trakem2/ExportToRenderAsIs_Plugin.java)



### Export To Render Using Basis Stack

Exports TrakEM patch data into a render web services stack using a basis stack to identify shared transformations.

> WARNING: this is a hack!

Make sure transformation logic in the exportPatches method in [ExportToRenderUsingBasisStack_Plugin.java] 
matches your use case before using.

#### Usage:
```
open TrakEM2 project
select menus File -> Export -> Render Web Services Using Basis
enter export parameters (see descriptions below)
progress is logged to ImageJ Log window
process is complete when 'org.janelia.render.trakem2.ExportToRenderUsingBasisStack_Plugin.run: exit' is logged
```

#### Parameters:
Name                              | Description                
--------------------------------- | -----------
Render Web Services Base URL      | base render web services URL (e.g. http://host:port/render-ws/v1)
Basis Render Stack Owner          | name of basis render stack owner
Basis Render Stack Project        | name of basis render stack project
Basis Render Stack Name           | name of basis render stack
TrakEM2 Min Z                     | z value of first layer to export
TrakEM2 Max Z                     | z value of last layer to export
Target Render Stack Owner         | name of target render stack owner
Target Render Stack Project       | name of target render stack project
Target Render Stack Name          | name of target render stack
TrakEM2 Z to Target Z Map         | leave empty to skip mapping, format is a=b,c=d
Complete Stack After Export       | if checked, completes render stack after importing all TrakEM2 patches

#### Source Code: 
* [ExportToRenderUsingBasisStack_Plugin.java]



### Import From Render

Imports render tiles into an existing TrakEM2 project.

#### Usage:
```
open TrakEM2 project
select menus File -> Import -> Render Web Services Layers
enter import parameters (see descriptions below)
progress is logged to ImageJ Log window
process is complete when 'org.janelia.render.trakem2.ImportFromRender_Plugin.run: exit' is logged
  
```
#### Parameters:
Name                              | Description                
--------------------------------- | -----------
Render Web Services Base URL      | base render web services URL (e.g. http://host:port/render-ws/v1)
Render Stack Owner                | name of render stack owner
Render Stack Project              | name of render stack project
Render Stack Name                 | name of render stack
Min Z                             | z value of first layer to import
Max Z                             | z value of last layer to import
Image Plus Type                   | image plus type for all imported tiles; supported values are: 0 for GRAY8, 1 for GRAY16, 2 for GRAY32, 3 for COLOR_256 and 4 for COLOR_RGB"; use '-1' to slowly derive dynamically
Load Masks                        | if checked, loads (potentially duplicate) mask data into the TrakEM2 project
Split Sections                    | if checked, splits tiles with different sectionId values in the same render layer (z) into separate TrakEM2 layers 
Replace Last Transform With Stage | if checked, replaces the last transform for each tile with a simple translation transform that moves the tile to its layout stage position
Number of threads for mipmaps     | specifies how many threads TrakEM2 should use to generate missing mipmaps when imported data is viewed

#### Source Code: 
* [ImportFromRender_Plugin.java](../../../../trakem2-scripts/src/main/java/org/janelia/render/trakem2/ImportFromRender_Plugin.java)



### New Project From Render

Imports render tiles into a new TrakEM2 project.

#### Usage:
```
select menus File -> New -> TrakEM2 (from Render Web Services)
select storage folder for new TrakEM2 project 
enter import parameters (see descriptions below)
progress is logged to ImageJ Log window
process is complete when 'org.janelia.render.trakem2.ImportFromRender_Plugin.run: exit' is logged
  
```
#### Parameters:
Name                              | Description                
--------------------------------- | -----------
Render Web Services Base URL      | base render web services URL (e.g. http://host:port/render-ws/v1)
Render Stack Owner                | name of render stack owner
Render Stack Project              | name of render stack project
Render Stack Name                 | name of render stack
Min Z                             | z value of first layer to import
Max Z                             | z value of last layer to import
Image Plus Type                   | image plus type for all imported tiles; supported values are: 0 for GRAY8, 1 for GRAY16, 2 for GRAY32, 3 for COLOR_256 and 4 for COLOR_RGB"; use '-1' to slowly derive dynamically
Load Masks                        | if checked, loads (potentially duplicate) mask data into the TrakEM2 project
Split Sections                    | if checked, splits tiles with different sectionId values in the same render layer (z) into separate TrakEM2 layers 
Replace Last Transform With Stage | if checked, replaces the last transform for each tile with a simple translation transform that moves the tile to its layout stage position
Number of threads for mipmaps     | specifies how many threads TrakEM2 should use to generate missing mipmaps when imported data is viewed

#### Source Code: 
* [ImportFromRender_Plugin.java](../../../../trakem2-scripts/src/main/java/org/janelia/render/trakem2/ImportFromRender_Plugin.java)


  [Export To Render As Is]: <#export-to-render-as-is>  
  [Export To Render Using Basis Stack]: <#export-to-render-using-basis-stack> 
  [ExportToRenderUsingBasisStack_Plugin.java]: <../../../../trakem2-scripts/src/main/java/org/janelia/render/trakem2/ExportToRenderUsingBasisStack_Plugin.java> 
  [Import From Render]: <#import-from-render> 
  [maven]: <https://maven.apache.org/> 
  [MPICBG module]: <https://github.com/axtimwalde/mpicbg>
  [New Project From Render]: <#new-project-from-render> 
  [render data model]: <../../../../docs/src/site/markdown/data-model.md>
  [render Docker packaging]: <../../../../docs/src/site/markdown/render-ws-docker.md>
  [render web services]: <../../../../docs/src/site/markdown/render-ws.md>
  [TrakEM2 ImageJ plugin]: <https://imagej.net/TrakEM2>
