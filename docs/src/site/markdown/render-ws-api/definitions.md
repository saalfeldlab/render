## Definitions
### Bounds
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|minX||false|number (double)||
|minY||false|number (double)||
|minZ||false|number (double)||
|maxX||false|number (double)||
|maxY||false|number (double)||
|maxZ||false|number (double)||
|boundingBoxDefined||false|boolean|false|
|deltaX||false|number (double)||
|deltaY||false|number (double)||


### CanvasMatches

The set of all weighted point correspondences between two canvases.

|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|pGroupId|Group (or section) identifier for all source coordinates|true|string||
|pId|Canvas (or tile) identifier for all source coordinates|true|string||
|qGroupId|Group (or section) identifier for all target coordinates|true|string||
|qId|Canvas (or tile) identifier for all target coordinates|true|string||
|matches|Weighted source-target point correspondences|true|Matches are a collection of n-dimensional weighted source-target point correspondences.||


### EntryIntegerImageAndMask
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|value||false|ImageAndMask||
|key||false|integer (int32)||


### ImageAndMask
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|imageUrl||false|string||
|maskUrl||false|string||
|imageFilePath||false|string||
|maskFilePath||false|string||


### InterpolatedTransformSpec
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|id||false|string||
|metaData||false|TransformSpecMetaData||
|unresolvedIds||false|string array||
|newInstance||false|CoordinateTransform||


### LayoutData
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|sectionId||false|string||
|temca||false|string||
|camera||false|string||
|imageRow||false|integer (int32)||
|imageCol||false|integer (int32)||
|stageX||false|number (double)||
|stageY||false|number (double)||
|rotation||false|number (double)||


### LeafTransformSpec
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|id||false|string||
|metaData||false|TransformSpecMetaData||
|unresolvedIds||false|string array||
|newInstance||false|CoordinateTransform||
|className||false|string||
|dataString||false|string||


### ListTransformSpec
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|id||false|string||
|metaData||false|TransformSpecMetaData||
|unresolvedIds||false|string array||
|newInstance||false|CoordinateTransform||


### Matches are a collection of n-dimensional weighted source-target point correspondences.
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|p|Source point coordinates|true|number (double) array array||
|q|Target point coordinates|true|number (double) array array||
|w|Weights|true|number (double) array||


### MipmapPathBuilder
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|numberOfLevels||false|integer (int32)||


### ReferenceTransformSpec
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|id||false|string||
|metaData||false|TransformSpecMetaData||
|unresolvedIds||false|string array||
|newInstance||false|CoordinateTransform||
|refId||false|string||
|effectiveRefId||false|string||


### RenderParameters
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|meshCellSize||false|number (double)||
|minMeshCellSize||false|number (double)||
|in||false|string||
|out||false|string||
|x||false|number (double)||
|y||false|number (double)||
|width||false|integer (int32)||
|height||false|integer (int32)||
|scale||false|number (double)||
|areaOffset||false|boolean|false|
|convertToGray||false|boolean|false|
|quality||false|number (float)||
|numberOfThreads||false|integer (int32)||
|skipInterpolation||false|boolean|false|
|binaryMask||false|boolean|false|
|backgroundRGBColor||false|integer (int32)||
|tileSpecs||false|TileSpec array||
|minBoundsMinX||false|number (double)||
|minBoundsMinY||false|number (double)||
|minBoundsMaxX||false|number (double)||
|minBoundsMaxY||false|number (double)||
|res||false|number (double)||
|outUri||false|string||


### ResolvedTileSpecCollection
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|tileCount||false|integer (int32)||
|tileSpecs||false|TileSpec array||
|transformCount||false|integer (int32)||
|transformSpecs||false|TransformSpec array||


### Response
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|status||false|integer (int32)||
|metadata||false|object||
|entity||false|object||


### SectionData
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|sectionId||false|string||
|z||false|number (double)||
|tileCount||false|integer (int64)||
|minX||false|number (double)||
|maxX||false|number (double)||
|minY||false|number (double)||
|maxY||false|number (double)||


### StackId
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|owner||false|string||
|project||false|string||
|stack||false|string||
|scopePrefix||false|string||
|sectionCollectionName||false|string||
|tileCollectionName||false|string||
|transformCollectionName||false|string||


### StackMetaData
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|stackId||false|StackId||
|state||false|enum (LOADING, COMPLETE, OFFLINE)||
|lastModifiedTimestamp||false|string (date-time)||
|currentVersionNumber||false|integer (int32)||
|currentVersion||false|StackVersion||
|stats||false|StackStats||
|loading||false|boolean|false|
|currentMipmapPathBuilder||false|MipmapPathBuilder||
|currentMaterializedBoxRootPath||false|string||
|layoutWidth||false|integer (int32)||
|layoutHeight||false|integer (int32)||


### StackStats
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|stackBounds||false|Bounds||
|sectionCount||false|integer (int64)||
|nonIntegralSectionCount||false|integer (int64)||
|tileCount||false|integer (int64)||
|transformCount||false|integer (int64)||
|minTileWidth||false|integer (int32)||
|maxTileWidth||false|integer (int32)||
|minTileHeight||false|integer (int32)||
|maxTileHeight||false|integer (int32)||


### StackTraceElement
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|methodName||false|string||
|fileName||false|string||
|lineNumber||false|integer (int32)||
|className||false|string||
|nativeMethod||false|boolean|false|


### StackVersion
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|createTimestamp||false|string (date-time)||
|versionNotes||false|string||
|cycleNumber||false|integer (int32)||
|cycleStepNumber||false|integer (int32)||
|stackResolutionX||false|number (double)||
|stackResolutionY||false|number (double)||
|stackResolutionZ||false|number (double)||
|materializedBoxRootPath||false|string||
|mipmapPathBuilder||false|MipmapPathBuilder||


### TileBounds
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|minX||false|number (double)||
|minY||false|number (double)||
|minZ||false|number (double)||
|maxX||false|number (double)||
|maxY||false|number (double)||
|maxZ||false|number (double)||
|tileId||false|string||
|boundingBoxDefined||false|boolean|false|
|deltaX||false|number (double)||
|deltaY||false|number (double)||


### TileCoordinates
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|tileId||false|string||
|visible||false|boolean|false|
|local||false|number (double) array||
|world||false|number (double) array||


### TileSpec
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|tileId||false|string||
|layout||false|LayoutData||
|groupId||false|string||
|z||false|number (double)||
|minX||false|number (double)||
|minY||false|number (double)||
|maxX||false|number (double)||
|maxY||false|number (double)||
|width||false|integer (int32)||
|height||false|integer (int32)||
|minIntensity||false|number (double)||
|maxIntensity||false|number (double)||
|transforms||false|ListTransformSpec||
|meshCellSize||false|number (double)||
|firstMipmapEntry||false|EntryIntegerImageAndMask||
|transformList||false|CoordinateTransformListCoordinateTransform||


### TransformSpec
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|id||false|string||
|metaData||false|TransformSpecMetaData||
|unresolvedIds||false|string array||
|newInstance||false|CoordinateTransform||


### TransformSpecMetaData
|Name|Description|Required|Schema|Default|
|----|----|----|----|----|
|group||false|string||


