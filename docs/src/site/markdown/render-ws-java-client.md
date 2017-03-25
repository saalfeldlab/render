# Java Clients for Render Web Services 

The following Java client applications/tools utilize the [render web services] to retrieve and/or store persisted 
specifications.  Clients work with retrieved data locally enabling large scale parallel processing of data.
  
Name                              | Description                  
--------------------------------- | -----------
[Box Client]                      | Renders uniform arbitrarily sized boxes to disk for one or more layers of a stack. 
[Coordinate Client]               | Translates coordinates between stacks with different alignments of common source tiles.
[Copy Stack Client]               | Copies tiles from one stack to another.
[Import JSON Client]              | Imports JSON tile and transform specifications into a stack.
[Import Match Client]             | Imports JSON point match data into a match collection.
[Import Transform Changes Client] | Imports JSON transform changes for existing stack tiles into a new stack.
[Mipmap Client]                   | Renders down-sampled mipmap images for a stack's source tiles to disk.
[Render Section Client]           | Renders a composite image of all tiles in a layer for one or more stack layers.
[Section Update Client]           | Updates the z value for all tiles in a stack section.
[Stack Client]                    | Supports stack management functions like creation, state changing, and deletion.
[Tile Pair Client]                | Calculates potential neighbor pairs for all tiles in a range of stack layers.
[Tile Removal Client]             | Supports various mechanisms for identifying and removing sets of stack tiles. 
[Transform Section Client]        | Adds a common shared transform to all tiles in one or more stack layers.
[Validate Tiles Client]           | Validates all tiles in one or more stack layers removing any invalid tiles.
[Warp Transform Client]           | Generates warp (Thin Plate Spline or Moving Least Squares) transforms for one or more stack layers.



## Box Client
Renders uniform arbitrarily sized boxes to disk for one or more layers of a stack.
All generated images have the same dimensions and pixel count and are stored within a 
[CATMAID LargeDataTileSource] directory structure that looks like this:
```
[root directory]/[project]/[stack]/[tile width]x[tile height]/[mipmap level]/[z]/[row]/[col].[format]
 ```

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.BoxClient [options] Z values for layers to render

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
  * --rootDirectory
       Root directory for rendered tiles (e.g. /tier2/flyTEM/nobackup/rendered_boxes)
  * --height
       Height of each box
  * --width
       Width of each box
       
    --binaryMask
       use binary mask (e.g. for DMG data)
       Default: false
    --createIGrid
       create an IGrid file
       Default: false
    --label
       Generate single color tile labels instead of actual tile images
       Default: false
    --skipInterpolation
       skip interpolation (e.g. for DMG data)
       Default: false

    --forceGeneration
       Regenerate boxes even if they already exist
       Default: false
    --format
       Format for rendered boxes
       Default: png
    --maxLevel
       Maximum mipmap level to generate
       Default: 0
    --maxOverviewWidthAndHeight
       Max width and height of layer overview image (omit or set to zero to disable overview generation)
       
    --numberOfRenderGroups
       Total number of parallel jobs being used to render this layer (omit if only one job is being used)
    --renderGroup
       Index (1-n) that identifies portion of layer to render (omit if only one job is being used)
```

### Source Code: 
* Java: [BoxClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/BoxClient.java)
* Launch Script: [render_catmaid_boxes.sh](../../../../render-ws-java-client/src/main/scripts/render_catmaid_boxes.sh) 



## Coordinate Client 
Translates coordinates between stacks with different alignments of common source tiles.

Although the [render web services] provide APIs for mapping coordinates directly on the render server, 
those APIs should only be used for small scale mapping efforts.  The coordinate client is designed to minimize 
remote service requests and maximize local CPU usage for mapping large batches of coordinates.

This tool was originally developed to support batch mapping of JSON coordinates within a specific layer.
More recently, support for mapping coordinates across layers and for mapping coordinates in [SWC format] files was added.   

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.CoordinateClient [options]

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
       
    --toOwner
       Name of target stack owner (for round trip mapping, default is source owner)
    --toProject
       Name of target stack project (for round trip mapping, default is source owner)
    --toStack
       Name of target stack (for round trip mapping, omit for one way mapping)
       
    --fromJson
       JSON file containing coordinates to be mapped (.json, .gz, or .zip)
    --toJson
       JSON file where mapped coordinates are to be stored (.json, .gz, or .zip)
       
    --fromSwcDirectory
       directory containing source .swc files
    --toSwcDirectory
       directory to write target .swc files with mapped coordinates

    --numberOfThreads
       Number of threads to use for conversion
       Default: 1
    --localToWorld
       Convert from local to world coordinates (default is to convert from world to local)
       Default: false
    --z
       Z value for all source coordinates
```

### Cluster Usage: 
The tool's default setup uses 1 thread for processing allowing it to be run on a single core.
Alternatively, you can request more cores and then increase the --numberOfThreads option accordingly.
At Janelia, we've mapped 1.8 million points in an hour using ten 32 core nodes with 30 threads per node.
This works out to a mapping rate of roughly 100 points per minute per core.

### Source Code: 
* Java: [CoordinateClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/CoordinateClient.java)
* Launch Script: [map_coordinates.sh](../../../../render-ws-java-client/src/main/scripts/map_coordinates.sh) 

### Examples:

#### JSON World To Local Example

* Given world-v4.json:
```javascript
[
  {"world": [230000.0, 79000.0]}
]
```

* Map world to local for v4 stack:
```sh
map_coordinates.sh --baseDataUrl http://tem-services:8080/render-ws/v1 --owner flyTEM --project FAFB00 --stack v4_align_tps --z 4186 --fromJson world-v4.json --toJson local-v4.json
```

* Creates result file local-v4.json:
```javascript
[
  [
    { "tileId": "140828171446014019.4186.0", "local": [ 2041.0829262039915, 1373.9360369769856, 4186.0 ] },
    { "tileId": "140828171446015019.4186.0", "visible": true, "local": [ 46.64010738010984, 1406.3592571603513, 4186.0 ] }
  ]
]
```

#### JSON Local To World Example

* Map local-v4.json to v5 stack:
```sh
map_coordinates.sh --baseDataUrl http://tem-services:8080/render-ws/v1 --owner flyTEM --project FAFB00 --stack v5_align_tps --z 4186 --fromJson local-v4.json --toJson world-v5.json --localToWorld
```

* Creates result file world-v5.json:
```javascript
[
  { "tileId": "140828171446015019.4186.0", "world": [ 28469.717357674046, 36159.01272794745, 4186.0 ] }
]
```

#### JSON Round Trip Example

* Given world-v4.json:
```javascript
[
  {"world": [230000.0, 79000.0]}
]
```

* Map v4 world to v5 world:
```sh
map_coordinates.sh --baseDataUrl http://tem-services:8080/render-ws/v1 --owner flyTEM --project FAFB00 --stack v4_align_tps --toStack v5_align_tps --z 4186 --fromJson world-v4.json --toJson world-v5.json
```

* Creates result file world-v5.json:
```javascript
[
  { "tileId": "140828171446015019.4186.0", "world": [ 28469.717357674046, 36159.01272794745, 4186.0 ] }
]
```

#### SWC Round Trip Example

* Given v4/skeleton.swc:
```sh
# SWC format file
# based on specifications at http://research.mssm.edu/cnic/swc.html
# PointNo Label X Y Z Radius Parent 
1 0 920000 316000 146510 2 -1
```

* Map v4 swc files to v5:
```sh
map_coordinates.sh --baseDataUrl http://tem-services:8080/render-ws/v1 --owner flyTEM --project FAFB00 --stack v4_align_tps --toStack v5_align_tps --fromSwcDirectory v4 --toSwcDirectory v5
```

* Creates result file v5/skeleton.swc:
```sh
# SWC format file
# based on specifications at http://research.mssm.edu/cnic/swc.html
# PointNo Label X Y Z Radius Parent 
1 0 113878.869430696184 144636.0509117898 146510 2 -1
```

> Note that if the v4 directory contained multiple swc files, each one would get mapped and saved to the v5 directory.



## Copy Stack Client 
Copies tiles from one stack to another.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.CopyStackClient [options]

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --fromStack
       Name of source stack
  * --toStack
       Name of target stack
  * --z
       Z value of section to be copied

    --toOwner
       Name of target stack owner (default is same as source stack owner)
    --toProject
       Name of target stack project (default is same as source stack project)
    --keepExisting
       Keep any existing target stack tiles with the specified z (default is to remove them)
       Default: false
    --maxX
       Maximum X value for all tiles
    --maxY
       Maximum Y value for all tiles
    --minX
       Minimum X value for all tiles
    --minY
       Minimum Y value for all tiles
    --completeToStackAfterCopy
       Complete the to stack after copying all layers
       Default: false
    --replaceLastTransformWithStage
       Replace the last transform in each tile space with a 'stage identity' transform
       Default: false
    --splitMergedSections
       Reset z values for tiles so that original sections are separated
       Default: false
```

### Source Code: 
* Java: [CopyStackClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/CopyStackClient.java)



## Import JSON Client
Imports JSON tile and transform specifications into a stack.
 
### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.ImportJsonClient [options] list of tile spec files (.json, .gz, or .zip)

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Name of stack for imported data
       
    --transformFile
       file containing shared JSON transform specs (.json, .gz, or .zip)
    --validatorClass
       Name of validator class (e.g. org.janelia.alignment.spec.validator.TemTileSpecValidator).  Exclude to skip validation.
    --validatorData
       Initialization data for validator instance.
```

### Source Code: 
* Java: [ImportJsonClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/ImportJsonClient.java)
* Launch Script: [import_json.sh](../../../../render-ws-java-client/src/main/scripts/import_json.sh) 



## Import Match Client
Imports JSON point match data into a match collection.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.ImportMatchClient [options] list of canvas match data files, each file (.json, .gz, or .zip) can contain an arbitrary set of matches

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Match collection owner
  * --collection
       Match collection name
       
    --batchSize
       maximum number of matches to batch in a single request
       Default: 10000
```

### Source Code: 
* Java: [ImportMatchClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/ImportMatchClient.java)



## Import Transform Changes Client
Imports JSON transform changes for existing stack tiles into a new stack.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.ImportTransformChangesClient [options]

  Options:

  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Name of source stack containing base tile specifications
  * --targetStack
       Name of target (align, montage, etc.) stack that will contain imported transforms
  * --transformFile
       File containing list of transform changes (.json, .gz, or .zip).  
       For best performance, changes for all tiles with the same z should be grouped into the same file.

    --changeMode
       Specifies how the transforms should be applied to existing data
       Default: REPLACE_LAST
       Possible Values: [APPEND, REPLACE_LAST, REPLACE_ALL]
    --targetOwner
       Name of owner for target stack that will contain imported transforms (default is to reuse source owner)
    --targetProject
       Name of project for target stack that will contain imported transforms (default is to reuse source project)
    --validatorClass
       Name of validator class (e.g. org.janelia.alignment.spec.validator.TemTileSpecValidator).  Exclude to skip validation.
    --validatorData
       Initialization data for validator instance.
```

### Source Code: 
* Java: [ImportTransformChangesClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/ImportTransformChangesClient.java)
* Launch Script: [import_transform_changes.sh](../../../../render-ws-java-client/src/main/scripts/import_transform_changes.sh) 



## Mipmap Client 
Renders down-sampled mipmap images for a stack's source tiles to disk.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.MipmapClient [options] Z values for layers to render

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
  * --rootDirectory
       Root directory for mipmaps (e.g. /tier2/flyTEM/nobackup/rendered_mipmaps/FAFB00)

    --forceGeneration
       Regenerate mipmaps even if they already exist
       Default: false
    --format
       Format for mipmaps (tiff, jpg, png)
       Default: tiff
    --minLevel
       Minimum mipmap level to generate
       Default: 1
    --maxLevel
       Maximum mipmap level to generate
       Default: 6

    --numberOfRenderGroups
       Total number of parallel jobs being used to render this layer (omit if only one job is being used)
       Default: 1
    --renderGroup
       Index (1-n) that identifies portion of layer to render (omit if only one job is being used)
       Default: 1
```

### Source Code: 
* Java: [MipmapClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/MipmapClient.java)
* Launch Script: [render_mipmaps.sh](../../../../render-ws-java-client/src/main/scripts/render_mipmaps.sh) 



## Render Section Client 
Renders a composite image of all tiles in a layer for one or more stack layers.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.RenderSectionClient [options] Z values for sections to render

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
  * --rootDirectory
       Root directory for rendered layers (e.g. /tier2/flyTEM/nobackup/rendered_boxes)

    --doFilter
       Use ad hoc filter to support alignment
       Default: true
    --fillWithNoise
       Fill image with noise before rendering to improve point match derivation
       Default: true
    --format
       Format for rendered boxes (png, jpg, tiff)
       Default: png
    --scale
       Scale for each rendered layer
       Default: 0.02
```

### Source Code: 
* Java: [RenderSectionClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/RenderSectionClient.java)



## Section Update Client 
Updates the z value for all tiles in a stack section.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.SectionUpdateClient [options]

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
  * --sectionId
       Section ID
  * --z
       Z value
```

### Source Code: 
* Java: [SectionUpdateClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/SectionUpdateClient.java)



## Stack Client 
Supports stack management functions like creation, state changing, and deletion.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.StackClient [options]

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
  * --action
       Management action to perform
       Possible Values: [CREATE, CLONE, SET_STATE, DELETE]

    --cycleNumber
       Processing cycle number
    --cycleStepNumber
       Processing cycle step number
    --materializedBoxRootPath
       Root path for materialized boxes
    --stackResolutionX
       X resoution (in nanometers) for the stack
    --stackResolutionY
       Y resoution (in nanometers) for the stack
    --stackResolutionZ
       Z resoution (in nanometers) for the stack
    --versionNotes
       Notes about the version being created

    --stackState
       New state for stack
       Possible Values: [LOADING, COMPLETE, READ_ONLY, OFFLINE]

    --cloneResultProject
       Name of project for stack created by clone operation (default is to use
       source project)
    --cloneResultStack
       Name of stack created by clone operation
    --skipSharedTransformClone
       Only clone tiles, skipping clone of shared transforms (default is false)

    --sectionId
       The sectionId to delete
    --zValues
       Z values for filtering
```

### Source Code: 
* Java: [StackClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/StackClient.java)
* Launch Script: [manage_stacks.sh](../../../../render-ws-java-client/src/main/scripts/manage_stacks.sh) 



## Tile Pair Client 
Calculates potential neighbor pairs for all tiles in a range of stack layers.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.TilePairClient [options]

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
  * --minZ
       Minimum Z value for all tiles
  * --maxZ
       Maximum Z value for all tiles
  * --toJson
       JSON file where tile pairs are to be stored (.json, .gz, or .zip)

    --xyNeighborFactor
       Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles
       Default: 0.9
    --zNeighborDistance
       Look for neighbor tiles with z values less than or equal to this distance from the current tile's z value
       Default: 2

    --baseOwner
       Name of base/parent owner from which the render stack was derived (default assumes same owner as render stack)
    --baseProject
       Name of base/parent project from which the render stack was derived (default assumes same project as render stack)
    --baseStack
       Name of base/parent stack from which the render stack was derived (default assumes same as render stack)

    --excludeCompletelyObscuredTiles
       Exclude tiles that are completely obscured by reacquired tiles
       Default: true
    --excludeCornerNeighbors
       Exclude neighbor tiles whose center x and y is outside the source tile's x and y range respectively
       Default: true
    --excludePairsInMatchCollection
       Name of match collection whose existing pairs should be excluded from the generated list (default is to include all pairs)
    --excludeSameLayerNeighbors
       Exclude neighbor tiles in the same layer (z) as the source tile
       Default: false
    --excludeSameSectionNeighbors
       Exclude neighbor tiles with the same sectionId as the source tile
       Default: false

    --minX
       Minimum X value for all tiles
    --maxX
       Maximum X value for all tiles
    --minY
       Minimum Y value for all tiles
    --maxY
       Maximum Y value for all tiles
```

### Source Code: 
* Java: [TilePairClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/TilePairClient.java)



## Tile Removal Client 
Supports various mechanisms for identifying and removing sets of stack tiles. 

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.TileRemovalClient [options] tileIds_to_remove

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name

    --tileIdJson
       JSON file containing array of tileIds to be removed (.json, .gz, or .zip)

    --hiddenTilesWithZ
       Z value for all hidden tiles to be removed

    --keepZ
       Z value for all tiles to be kept
    --keepMaxX
       Maximum X value for all tiles to be kept
    --keepMaxY
       Maximum Y value for all tiles to be kept
    --keepMinX
       Minimum X value for all tiles to be kept
    --keepMinY
       Minimum Y value for all tiles to be kept
```

### Source Code: 
* Java: [TileRemovalClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/TileRemovalClient.java)



## Transform Section Client 
Adds a common shared transform to all tiles in one or more stack layers.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.TransformSectionClient [options] Z values

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
  * --transformId
       Identifier for transformation
  * --transformClass
       Name of transformation implementation (java) class
  * --transformData
       Data with which transformation implementation should be initialized (expects values to be separated by ',' instead of ' ')
       
    --replaceLast
       Replace each tile's last transform with this one (default is to append new transform)
       Default: false

    --targetProject
       Name of target project that will contain transformed tiles (default is to reuse source project)
    --targetStack
       Name of target stack that will contain transformed tiles (default is to reuse source stack)

    --validatorClass
       Name of validator class (e.g. org.janelia.alignment.spec.validator.TemTileSpecValidator).  Exclude to skip validation.
    --validatorData
       Initialization data for validator instance.
```

### Source Code: 
* Java: [TransformSectionClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/TransformSectionClient.java)



## Validate Tiles Client 
Validates all tiles in one or more stack layers removing any invalid tiles.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.ValidateTilesClient [options] Z values

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Stack owner
  * --project
       Stack project
  * --stack
       Stack name
  * --validatorClass
       Name of validator class (e.g. org.janelia.alignment.spec.validator.TemTileSpecValidator).
  * --validatorData
       Initialization data for validator instance.
```

### Source Code: 
* Java: [ValidateTilesClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/ValidateTilesClient.java)



## Warp Transform Client 
Generates warp (Thin Plate Spline or Moving Least Squares) transforms for one or more stack layers.

### Usage:
```
java -cp current-ws-standalone.jar org.janelia.render.client.WarpTransformClient [options] Z values

  Options:

  * --baseDataUrl
       Base web service URL for data (e.g. http://host[:port]/render-ws/v1)
  * --owner
       Owner for all stacks
  * --project
       Project for all stacks
  * --alignStack
       Align stack name
  * --montageStack
       Montage stack name
  * --targetStack
       Target stack (tps or mls) name

    --alpha
       Alpha value for MLS transform
    --deriveMLS
       Derive moving least squares transforms instead of thin plate spline transforms
       Default: false

    --validatorClass
       Name of validator class (e.g. org.janelia.alignment.spec.validator.TemTileSpecValidator).  Exclude to skip validation.
    --validatorData
       Initialization data for validator instance.
```

### Source Code: 
* Java: [WarpTransformClient.java](../../../../render-ws-java-client/src/main/java/org/janelia/render/client/WarpTransformClient.java)
* Launch Script: [generate_warp_transforms.sh](../../../../render-ws-java-client/src/main/scripts/generate_warp_transforms.sh) 


    
  [Box Client]: <#box-client>
  [CATMAID LargeDataTileSource]: <https://github.com/catmaid/CATMAID/blob/master/django/applications/catmaid/static/js/tile-source.js>
  [Coordinate Client]: <#coordinate-client> 
  [Copy Stack Client]: <#copy-stack-client> 
  [Import JSON Client]: <#import-json-client> 
  [Import Match Client]: <#import-match-client> 
  [Import Transform Changes Client]: <#import-transform-changes-client> 
  [Mipmap Client]: <#mipmap-client> 
  [Render Section Client]: <#render-section-client> 
  [render web services]: <render-ws.md>
  [Section Update Client]: <#section-update-client> 
  [Stack Client]: <#stack-client> 
  [SWC format]: <http://research.mssm.edu/cnic/swc.html>
  [Tile Pair Client]: <#tile-pair-client> 
  [Tile Removal Client]: <#tile-removal-client> 
  [Transform Section Client]: <#transform-section-client> 
  [Validate Tiles Client]: <#validate-tiles-client> 
  [Warp Transform Client]: <#warp-transform-client>
