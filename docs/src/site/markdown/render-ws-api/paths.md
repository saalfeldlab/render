## Resources
### Bounding Box Data APIs
#### Get number of tiles within box
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height}/tile-count
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|integer (int64)|


##### Produces

* application/json

#### Get parameters to render all tiles within the specified box
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/render-parameters
```

##### Description

For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|convertToGray||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|RenderParameters|


##### Produces

* application/json

#### Get parameters to render all tiles within the specified group and box
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/render-parameters
```

##### Description

For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|groupId||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|convertToGray||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|RenderParameters|


##### Produces

* application/json

### Bounding Box Image APIs
#### Render JPEG image for the specified bounding box and groupId
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|groupId||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/jpeg

#### Render PNG image for the specified large data (type 5) tile
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/largeDataTileSource/{width}/{height}/{level}/{z}/{row}/{column}.png
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|level||true|integer (int32)||
|PathParameter|z||true|number (double)||
|PathParameter|row||true|integer (int32)||
|PathParameter|column||true|integer (int32)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/png

#### Render PNG image for the specified large data (type 5) section overview
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/largeDataTileSource/{width}/{height}/small/{z}.png
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|z||true|number (double)||
|QueryParameter|maxOverviewWidthAndHeight||false|integer (int32)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/png

#### Render JPEG image for the specified large data (type 5) tile
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/largeDataTileSource/{width}/{height}/{level}/{z}/{row}/{column}.jpg
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|level||true|integer (int32)||
|PathParameter|z||true|number (double)||
|PathParameter|row||true|integer (int32)||
|PathParameter|column||true|integer (int32)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/jpeg

#### Render JPEG image for the specified large data (type 5) section overview
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/largeDataTileSource/{width}/{height}/small/{z}.jpg
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|z||true|number (double)||
|QueryParameter|maxOverviewWidthAndHeight||false|integer (int32)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/jpeg

#### Render TIFF image for the specified bounding box
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/tiff-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/tiff

#### Render PNG image for the specified bounding box
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/png

#### Render JPEG image for the specified bounding box
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/jpeg

#### Render TIFF image for the specified bounding box and groupId
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/tiff-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|groupId||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/tiff

#### Render PNG image for the specified bounding box and groupId
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|groupId||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||
|PathParameter|width||true|integer (int32)||
|PathParameter|height||true|integer (int32)||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/png

### Coordinate Mapping APIs
#### Derive world coordinates for specified tile local coordinates
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/local-to-world-coordinates/{x},{y}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TileCoordinates|
|404|tile not found|No Content|


##### Produces

* application/json

#### Derive local coordinates for specified world coordinates using provided tile spec
```
PUT /v1/owner/{owner}/world-to-local-coordinates/{x},{y}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|QueryParameter|meshCellSize||false|number (double)||
|BodyParameter|body||false|TileSpec||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|number (double) array|
|500|coordinates not invertible|No Content|


##### Consumes

* application/json

##### Produces

* application/json

#### Derive world coordinates for specified local coordinates using provided tile spec
```
PUT /v1/owner/{owner}/local-to-world-coordinates/{x},{y}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|BodyParameter|body||false|TileSpec||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|number (double) array|


##### Consumes

* application/json

##### Produces

* application/json

#### Map world coordinates to tileId(s)
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/tileIdsForCoordinates
```

##### Description

Locates all tiles that contain each specified world coordinate.  An array of arrays of coordinates with tileIds are then returned.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|BodyParameter|body||false|TileCoordinates array||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TileCoordinates array|
|400|problem with specified coordinates|No Content|


##### Consumes

* application/json

##### Produces

* application/json

#### Derive array of arrays of local coordinates for provided array of world coordinates
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/world-to-local-coordinates
```

##### Description

World coordinates can map to multiple (overlapping) tiles.  For each provided world coordinate, an array of local coordinates, one element per tile, is returned with the visible (last drawn) tile marked.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|BodyParameter|body||false|TileCoordinates array||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TileCoordinates array array|
|400|missing tile or coordinate data|No Content|
|500|coordinates not invertible|No Content|


##### Consumes

* application/json

##### Produces

* application/json

#### Derive array of world coordinates for provided tile local coordinates
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/local-to-world-coordinates
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|BodyParameter|body||false|TileCoordinates array||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TileCoordinates array|
|400|missing tile or coordinate data|No Content|
|404|tile not found|No Content|


##### Consumes

* application/json

##### Produces

* application/json

#### Derive array of local coordinates for specified world coordinates
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/world-to-local-coordinates/{x},{y}
```

##### Description

World coordinates can map to multiple (overlapping) tiles.  An array of local coordinates, one element per tile, is returned with the visible (last drawn) tile marked.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|x||true|number (double)||
|PathParameter|y||true|number (double)||
|PathParameter|z||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TileCoordinates array|
|500|coordinates not invertible|No Content|


##### Produces

* application/json

### Layout Data APIs
#### Get layout file text for all stack layers
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/layoutFile
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* text/plain

#### Get layout file text for specified stack layers
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/zRange/{minZ},{maxZ}/layoutFile
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|minZ||true|number (double)||
|PathParameter|maxZ||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* text/plain

### Point Match APIs
#### Find matches between the specified objects
```
GET /v1/owner/{owner}/matchCollection/{matchCollection}/group/{pGroupId}/id/{pId}/matchesWith/{qGroupId}/id/{qId}
```

##### Description

Find all matches between two specific tiles.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|matchCollection||true|string||
|PathParameter|pGroupId||true|string||
|PathParameter|pId||true|string||
|PathParameter|qGroupId||true|string||
|PathParameter|qId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|A canvas match is the set of all weighted point correspondences between two canvases. array|
|404|Match collection not found|No Content|


##### Produces

* application/json

#### Find matches within the specified group
```
GET /v1/owner/{owner}/matchCollection/{matchCollection}/group/{groupId}/matchesWithinGroup
```

##### Description

Find all matches where both tiles are in the specified layer.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|matchCollection||true|string||
|PathParameter|groupId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|A canvas match is the set of all weighted point correspondences between two canvases. array|
|404|Match collection not found|No Content|


##### Produces

* application/json

#### Delete matches outside the specified group
```
DELETE /v1/owner/{owner}/matchCollection/{matchCollection}/group/{groupId}/matchesOutsideGroup
```

##### Description

Delete all matches with one tile in the specified layer and another tile outside that layer.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|matchCollection||true|string||
|PathParameter|groupId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Match collection not found|No Content|


#### Find matches outside the specified group
```
GET /v1/owner/{owner}/matchCollection/{matchCollection}/group/{groupId}/matchesOutsideGroup
```

##### Description

Find all matches with one tile in the specified layer and another tile outside that layer.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|matchCollection||true|string||
|PathParameter|groupId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|A canvas match is the set of all weighted point correspondences between two canvases. array|
|404|Match collection not found|No Content|


##### Produces

* application/json

#### Find matches between the specified groups
```
GET /v1/owner/{owner}/matchCollection/{matchCollection}/group/{pGroupId}/matchesWith/{qGroupId}
```

##### Description

Find all matches with one tile in the specified p layer and another tile in the specified q layer.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|matchCollection||true|string||
|PathParameter|pGroupId||true|string||
|PathParameter|qGroupId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|A canvas match is the set of all weighted point correspondences between two canvases. array|
|404|Match collection not found|No Content|


##### Produces

* application/json

#### Delete all matches in the collection
```
DELETE /v1/owner/{owner}/matchCollection/{matchCollection}/matches
```

##### Description

Use this wisely.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|matchCollection||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Match collection not found|No Content|


#### Save a set of matches
```
PUT /v1/owner/{owner}/matchCollection/{matchCollection}/matches
```

##### Description

Inserts or updates matches for the specified collection.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|matchCollection||true|string||
|BodyParameter|body||false|A canvas match is the set of all weighted point correspondences between two canvases. array||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|201|matches successfully saved|No Content|
|400|If no matches are provided|No Content|


##### Consumes

* application/json

### Section Data APIs
#### Set z value for section
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/section/{sectionId}/z
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|sectionId||true|string||
|BodyParameter|body||false|number||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|400|stack not in LOADING state|No Content|
|404|stack not found|No Content|


##### Consumes

* application/json

#### Get z value for section
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/section/{sectionId}/z
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|sectionId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|number (double)|
|404|stack or section not found|No Content|


##### Produces

* application/json

#### Save specified raw tile and transform specs for section
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/resolvedTiles
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|BodyParameter|body||false|ResolvedTileSpecCollection||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|400|stack not in LOADING state, invalid data provided|No Content|
|404|stack not found|No Content|


##### Consumes

* application/json

#### Get raw tile and transform specs for section with specified z
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/resolvedTiles
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|ResolvedTileSpecCollection|
|400|too many (> 50,000) tiles in section|No Content|
|404|no tile specs found|No Content|


##### Produces

* application/json

#### Set z value for specified tiles (e.g. to split a layer)
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/tileIds
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|BodyParameter|body||false|string array||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|400|stack not in LOADING state|No Content|
|404|stack not found|No Content|


##### Consumes

* application/json

#### Get parameters to render all tiles with the specified z
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/render-parameters
```

##### Description

For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|RenderParameters|


##### Produces

* application/json

#### Get flattened tile specs with the specified z
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/tile-specs
```

##### Description

For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TileSpec array|


##### Produces

* application/json

#### Deletes all tiles in section
```
DELETE /v1/owner/{owner}/project/{project}/stack/{stack}/section/{sectionId}
```

##### Description

Deletes all tiles in the specified stack with the specified sectionId value.  This operation can only be performed against stacks in the LOADING state

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|sectionId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|400|stack state is not LOADING|No Content|
|404|stack not found|No Content|


#### Deletes all tiles in layer
```
DELETE /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}
```

##### Description

Deletes all tiles in the specified stack with the specified z value.  This operation can only be performed against stacks in the LOADING state

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|400|stack state is not LOADING|No Content|
|404|stack not found|No Content|


#### Get bounds for each tile with specified z
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/tileBounds
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TileBounds array|


##### Produces

* application/json

#### Get bounds for section with specified z
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/bounds
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|Bounds|


##### Produces

* application/json

### Section Image APIs
#### Render TIFF image for a section
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/tiff-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/tiff

#### Render PNG image for a section
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/png-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/png

#### Render JPEG image for a section
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}/jpeg-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|maxTileSpecsToRender||false|integer (int32)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Produces

* image/jpeg

### Spec Image APIs
#### Render TIFF image from a provided spec
```
PUT /v1/owner/{owner}/tiff-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|BodyParameter|body||false|RenderParameters||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Consumes

* application/json

##### Produces

* image/tiff

#### Render JPEG image from a provided spec
```
PUT /v1/owner/{owner}/jpeg-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|BodyParameter|body||false|RenderParameters||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Consumes

* application/json

##### Produces

* image/jpeg

#### Render PNG image from a provided spec
```
PUT /v1/owner/{owner}/png-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|BodyParameter|body||false|RenderParameters||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


##### Consumes

* application/json

##### Produces

* image/png

### Stack Data APIs
#### Save specified raw tile and transform specs
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/resolvedTiles
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|BodyParameter|body||false|ResolvedTileSpecCollection||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|400|stack not in LOADING state, invalid data provided|No Content|
|404|stack not found|No Content|


##### Consumes

* application/json

#### Get raw tile and transform specs for specified group or bounding box
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/resolvedTiles
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|QueryParameter|minZ||false|number (double)||
|QueryParameter|maxZ||false|number (double)||
|QueryParameter|groupId||false|string||
|QueryParameter|minX||false|number (double)||
|QueryParameter|maxX||false|number (double)||
|QueryParameter|minY||false|number (double)||
|QueryParameter|maxY||false|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|ResolvedTileSpecCollection|
|400|too many (> 50,000) matching tiles found|No Content|
|404|no tile specs found|No Content|


##### Produces

* application/json

#### Bounds for the specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/bounds
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|Bounds|
|400|stack bounds not available|No Content|
|404|stack not found|No Content|


##### Produces

* application/json

#### Sets the stack's current state
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/state/{state}
```

##### Description

Transitions stack from LOADING to COMPLETE to OFFLINE.  Transitioning to COMPLETE is a potentially long running operation since it creates indexes and aggregates meta data.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|state||true|enum (LOADING, COMPLETE, OFFLINE)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|201|state successfully changed|No Content|
|400|stack state cannot be changed because of current state|No Content|
|404|stack not found|No Content|


#### Saves new version of stack metadata
```
POST /v1/owner/{owner}/project/{project}/stack/{stack}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|BodyParameter|body||false|StackVersion||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|201|stackVersion successfully created|No Content|
|400|stackVersion not specified|No Content|


##### Consumes

* application/json

#### Deletes specified stack
```
DELETE /v1/owner/{owner}/project/{project}/stack/{stack}
```

##### Description

Deletes all tiles, transformations, meta data, and unsaved snapshot data for the stack.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


#### Metadata for the specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|StackMetaData|
|404|Stack not found|No Content|


##### Produces

* application/json

#### List plain text data for all mergeable sections in specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/mergeableData
```

##### Description

Data format is 'toIndex < fromIndex : toZ < fromZ'.  Only layers with both an integral (.0) and at least one non-integral (e.g. .1) section are included.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|string|
|400|section data not generated|No Content|


##### Produces

* text/plain

#### List z and sectionId for all sections in specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/sectionData
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|SectionData array|
|400|section data not generated|No Content|


##### Produces

* application/json

#### List z and sectionId for all reordered sections in specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/reorderedSectionData
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|SectionData array|
|400|section data not generated|No Content|


##### Produces

* application/json

#### Tile IDs for the specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tileIds
```

##### Description

For stacks with large numbers of tiles, this will produce a large amount of data (e.g. 500MB for 18 million tiles) - use wisely.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|string array|


##### Produces

* application/json

#### List z values for all mergeable layers in specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/mergeableZValues
```

##### Description

Only layers with both an integral (.0) and at least one non-integral (e.g. .1) section are included.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|number (double) array|
|400|section data not generated|No Content|


##### Produces

* application/json

#### List z values for all merged sections in specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/mergedZValues
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|number (double) array|
|400|section data not generated|No Content|


##### Produces

* application/json

#### Clones one stack to another
```
PUT /v1/owner/{owner}/project/{fromProject}/stack/{fromStack}/cloneTo/{toStack}
```

##### Description

This operation copies all fromStack tiles and transformations to a new stack with the specified metadata.  This is a potentially long running operation (depending upon the size of the fromStack).

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|fromProject||true|string||
|PathParameter|fromStack||true|string||
|PathParameter|toStack||true|string||
|QueryParameter|z||false|csv number (double) array||
|QueryParameter|toProject||false|string||
|QueryParameter|skipTransforms||false|boolean||
|BodyParameter|body||false|StackVersion||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|201|stack successfully cloned|No Content|
|400|toStack already exists|No Content|
|404|fromStack not found|No Content|


##### Consumes

* application/json

#### List z values for specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/zValues
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|number (double) array|


##### Produces

* application/json

### Stack Management APIs
#### Bounds for the specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/bounds
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|Bounds|
|400|stack bounds not available|No Content|
|404|stack not found|No Content|


##### Produces

* application/json

#### Sets the stack's current state
```
PUT /v1/owner/{owner}/project/{project}/stack/{stack}/state/{state}
```

##### Description

Transitions stack from LOADING to COMPLETE to OFFLINE.  Transitioning to COMPLETE is a potentially long running operation since it creates indexes and aggregates meta data.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|state||true|enum (LOADING, COMPLETE, OFFLINE)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|201|state successfully changed|No Content|
|400|stack state cannot be changed because of current state|No Content|
|404|stack not found|No Content|


#### List of all data owners
```
GET /v1/owners
```

##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|string array|


##### Produces

* application/json

#### Saves new version of stack metadata
```
POST /v1/owner/{owner}/project/{project}/stack/{stack}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|BodyParameter|body||false|StackVersion||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|201|stackVersion successfully created|No Content|
|400|stackVersion not specified|No Content|


##### Consumes

* application/json

#### Deletes specified stack
```
DELETE /v1/owner/{owner}/project/{project}/stack/{stack}
```

##### Description

Deletes all tiles, transformations, meta data, and unsaved snapshot data for the stack.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


#### Metadata for the specified stack
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|StackMetaData|
|404|Stack not found|No Content|


##### Produces

* application/json

#### A (very likely) globally unique identifier
```
GET /v1/likelyUniqueId
```

##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|string|


##### Produces

* text/plain

#### List of stack metadata for the specified owner
```
GET /v1/owner/{owner}/stacks
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|StackMetaData array|


##### Produces

* application/json

#### Clones one stack to another
```
PUT /v1/owner/{owner}/project/{fromProject}/stack/{fromStack}/cloneTo/{toStack}
```

##### Description

This operation copies all fromStack tiles and transformations to a new stack with the specified metadata.  This is a potentially long running operation (depending upon the size of the fromStack).

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|fromProject||true|string||
|PathParameter|fromStack||true|string||
|PathParameter|toStack||true|string||
|QueryParameter|z||false|csv number (double) array||
|QueryParameter|toProject||false|string||
|QueryParameter|skipTransforms||false|boolean||
|BodyParameter|body||false|StackVersion||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|201|stack successfully cloned|No Content|
|400|toStack already exists|No Content|
|404|fromStack not found|No Content|


##### Consumes

* application/json

#### List of stack identifiers for the specified owner
```
GET /v1/owner/{owner}/stackIds
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|StackId array|


##### Produces

* application/json

#### Deletes all tiles in section
```
DELETE /v1/owner/{owner}/project/{project}/stack/{stack}/section/{sectionId}
```

##### Description

Deletes all tiles in the specified stack with the specified sectionId value.  This operation can only be performed against stacks in the LOADING state

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|sectionId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|400|stack state is not LOADING|No Content|
|404|stack not found|No Content|


#### Deletes all tiles in layer
```
DELETE /v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}
```

##### Description

Deletes all tiles in the specified stack with the specified z value.  This operation can only be performed against stacks in the LOADING state

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|z||true|number (double)||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|400|stack state is not LOADING|No Content|
|404|stack not found|No Content|


### Tile Data APIs
#### Get parameters for rendering 'uniform' tile
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/render-parameters
```

##### Description

The returned x, y, width, and height parameters are uniform for all tiles in the stack.

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|RenderParameters|
|404|tile not found|No Content|


##### Produces

* application/json

#### Get tile spec
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TileSpec|
|404|tile not found|No Content|


##### Produces

* application/json

#### Get parameters for rendering tile source image (without transformations or mask)
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/source/scale/{scale}/render-parameters
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|RenderParameters|
|404|tile not found|No Content|


##### Produces

* application/json

#### Get parameters for rendering a tile with its neighbors
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/withNeighbors/render-parameters
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|widthFactor||false|number (double)||
|QueryParameter|heightFactor||false|number (double)||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|convertToGray||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|RenderParameters|
|404|tile not found|No Content|


##### Produces

* application/json

#### Get parameters for rendering tile mask image (without transformations)
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/mask/scale/{scale}/render-parameters
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|PathParameter|scale||true|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|RenderParameters|
|404|tile not found|No Content|


##### Produces

* application/json

### Tile Image APIs
#### Render tile's mask image without transformations in TIFF format
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/mask/tiff-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/tiff

#### Render tile's source image without transformations in JPEG format
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/source/jpeg-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/jpeg

#### Render tile's source image without transformations in PNG format
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/source/png-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/png

#### Render tile's mask image without transformations in JPEG format
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/mask/jpeg-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/jpeg

#### Render tile with its neighbors in JPEG format
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/withNeighbors/jpeg-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||
|QueryParameter|binaryMask||false|boolean||
|QueryParameter|convertToGray||false|boolean||
|QueryParameter|widthFactor||false|number (double)||
|QueryParameter|heightFactor||false|number (double)||
|QueryParameter|boundingBoxesOnly||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/jpeg

#### Render tile's mask image without transformations in PNG format
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/mask/png-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/png

#### Render TIFF image for a tile
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/tiff-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/tiff

#### Render tile's source image without transformations in TIFF format
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/source/tiff-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/tiff

#### Render JPEG image for a tile
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/jpeg-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/jpeg

#### Render PNG image for a tile
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/png-image
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|tileId||true|string||
|QueryParameter|scale||false|number (double)||
|QueryParameter|filter||false|boolean||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|404|Tile not found|No Content|


##### Produces

* image/png

### Transform Data APIs
#### Get transform spec
```
GET /v1/owner/{owner}/project/{project}/stack/{stack}/transform/{transformId}
```

##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|PathParameter|project||true|string||
|PathParameter|stack||true|string||
|PathParameter|transformId||true|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|200|successful operation|TransformSpec|
|404|transform not found|No Content|


##### Produces

* application/json

### Validation APIs
#### PUT /v1/owner/{owner}/validate-json/transform
##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|BodyParameter|body||false|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


#### PUT /v1/owner/{owner}/validate-json/render
##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|BodyParameter|body||false|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


#### PUT /v1/owner/{owner}/validate-json/tile
##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|BodyParameter|body||false|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


#### PUT /v1/owner/{owner}/validate-json/transform-array
##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|BodyParameter|body||false|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


#### PUT /v1/owner/{owner}/validate-json/tile-array
##### Parameters
|Type|Name|Description|Required|Schema|Default|
|----|----|----|----|----|----|
|PathParameter|owner||true|string||
|BodyParameter|body||false|string||


##### Responses
|HTTP Code|Description|Schema|
|----|----|----|
|default|successful operation|No Content|


