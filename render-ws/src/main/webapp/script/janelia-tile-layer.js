
const JaneliaTileLayer = function(baseUrl, owner, project, stack, zText,
                                  matchOwner, matchCollection,
                                  useStackBoundsText, boxScaleText,
                                  canvas, selectionCanvas,
                                  janeliaScriptUtilities) {

    this.baseUrl = baseUrl;
    this.owner = owner;
    this.project = project;
    this.stack = stack;
    this.z = parseFloat(zText);
    this.matchOwner = matchOwner;
    this.matchCollection = matchCollection;
    this.useStackBounds = (typeof useStackBoundsText !== "undefined") &&
                          (useStackBoundsText.toLowerCase() === "true");
    this.boxScale = parseFloat(boxScaleText);
    this.canvas = canvas;
    this.selectionCanvas = selectionCanvas;
    this.janeliaScriptUtilities = janeliaScriptUtilities;

    this.context2d = canvas.getContext("2d");

    this.stackUrl = this.baseUrl + "/owner/" + this.owner + "/project/" + this.project + "/stack/" + this.stack;
    this.zValues = [ this.z ];
    this.zIndex = 0;
    this.tileBoundsLists = [];
    this.tileCount = 0;
    this.stackMetaData = undefined;
    this.layerBounds = undefined;
    this.viewBounds = undefined;
    this.tilesScale = undefined;
    this.canvasMargin = 5;

    this.layerRTree = undefined;
    this.selectedTileBounds = undefined;

    this.missingConnectionMatchCount = 0;
    this.missingConnectionMatchCountThreshold = 10;
    this.layoutRequested = false;
    this.distinctSectionIds = new Set();
    this.rowToColumnDataMap = new Map();
    this.expectedOrderedPairKeys = [];
    this.matchCountResponseSectionIds = new Set();
    this.pairKeyToMatchDataMap = new Map();
    this.tileIdToBoundsMap = new Map();

    this.requestStackMetaData();
    this.requestZValues();
    this.requestBounds();
};

JaneliaTileLayer.prototype.changeZTitle = function() {
    $(document).attr('title', 'z ' + this.z + ' ' + this.stack + ' Tiles ');
};

JaneliaTileLayer.prototype.setZ = function(z) {
    this.z = z;
    this.changeZTitle();
    $("#zValue").val(this.z);
};

JaneliaTileLayer.prototype.isClustered = function() {
    return ((typeof this.matchCollection !== "undefined") && (this.matchCollection.length > 0));
};

JaneliaTileLayer.prototype.getBoundsUrl = function() {
    let boundsUrl;
    if (this.isClustered()) {
        boundsUrl = this.stackUrl + "/z/" + this.z + "/clusteredTileBoundsForCollection/" + this.matchCollection;
        if (typeof this.matchOwner !== "undefined") {
            boundsUrl += "?matchOwner=" + this.matchOwner;
        }
    } else {
        boundsUrl = this.stackUrl + "/z/" + this.z + "/tileBounds"
    }
    return boundsUrl;
};

JaneliaTileLayer.prototype.requestStackMetaData = function() {
    const self = this;
    $.ajax({
               url: self.stackUrl,
               cache: false,
               success: function(data) {
                   self.handleStackMetaDataResponse(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaTileLayer.prototype.handleStackMetaDataResponse = function(data) {
    this.stackMetaData = data;
    if (this.useStackBounds) {
        this.resizeCanvas();
    }
};

JaneliaTileLayer.prototype.requestZValues = function() {
    const self = this;
    $.ajax({
               url: self.stackUrl + "/zValues",
               cache: false,
               success: function(data) {
                   self.handleZValuesResponse(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaTileLayer.prototype.handleZValuesResponse = function(data) {

    this.zValues = data;

    const zValueSelector = $("#zValue");
    zValueSelector.empty();
    for (let i = 0; i < this.zValues.length; i++) {
        const currentZ = this.zValues[i];
        if (this.z === currentZ) {
            zValueSelector.append(new Option(currentZ, currentZ, true, true));
            this.zIndex = i;
        } else {
            zValueSelector.append(new Option(currentZ, currentZ));
        }
    }
};

JaneliaTileLayer.prototype.requestBounds = function() {
    const self = this;

    $("#clusterInfo").html(""); // clear cluster info

    // reset tile selection info
    this.layerRTree = undefined;
    this.selectedTileBounds = undefined;

    // reset missing connection info
    this.tileIdToBoundsMap.clear();
    this.layoutRequested = false;

    $.ajax({
               url: self.getBoundsUrl(),
               cache: false,
               success: function(data) {
                   self.handleBoundsResponse(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaTileLayer.prototype.handleBoundsResponse = function(data) {

    if (this.isClustered()) {
        this.tileBoundsLists = data;
    } else {
        this.tileBoundsLists = [data];
    }

    let layerBounds = {
        minX: Number.MAX_VALUE,
        minY: Number.MAX_VALUE,
        maxX: -Number.MAX_VALUE,
        maxY: -Number.MAX_VALUE
    };

    let tileWidthSum = 0;
    let tileHeightSum = 0;
    let tileSizes = {
        avgWidth: 0,
        minWidth: Number.MAX_VALUE,
        maxWidth: 0,
        avgHeight: 0,
        minHeight: Number.MAX_VALUE,
        maxHeight: 0
    };

    let tileCount = 0;
    this.tileBoundsLists.forEach(clusterList => {
        clusterList.forEach(tileBounds => {
            layerBounds.minX = Math.min(layerBounds.minX, tileBounds.minX);
            layerBounds.minY = Math.min(layerBounds.minY, tileBounds.minY);
            layerBounds.maxX = Math.max(layerBounds.maxX, tileBounds.maxX);
            layerBounds.maxY = Math.max(layerBounds.maxY, tileBounds.maxY);

            let tileWidth = tileBounds.maxX - tileBounds.minX;
            let tileHeight = tileBounds.maxY - tileBounds.minY;

            tileWidthSum += tileWidth;
            tileHeightSum += tileHeight;
            tileSizes.minWidth = Math.min(tileSizes.minWidth, tileWidth);
            tileSizes.maxWidth = Math.max(tileSizes.maxWidth, tileWidth);
            tileSizes.minHeight = Math.min(tileSizes.minHeight, tileHeight);
            tileSizes.maxHeight = Math.max(tileSizes.maxHeight, tileHeight);
            tileCount++;
        });
    });

    this.tileCount = tileCount;

    if (tileCount > 0) {
        this.layerBounds = layerBounds;
        tileSizes.avgWidth = tileWidthSum / tileCount;
        tileSizes.avgHeight = tileHeightSum / tileCount;
    } else {
        this.layerBounds = undefined;
    }

    this.resizeCanvas();

    const tileSuffix = (this.tileCount.length === 1) ? '' : 's';
    const clusterSuffix = (this.tileBoundsLists.length === 1) ? '' : 's';
    const clusterInfo = this.tileCount + " tile" + tileSuffix + ", " +
                        this.tileBoundsLists.length + " cluster" + clusterSuffix;
    $("#clusterInfo").html(clusterInfo);

    if (tileCount > 0) {
        $("#tileWidthInfo").html(tileSizes.minWidth + " to " + tileSizes.maxWidth +
                                 ", mean: " + tileSizes.avgWidth.toFixed(0));
        $("#tileHeightInfo").html(tileSizes.minHeight + " to " + tileSizes.maxHeight +
                                  ", mean: " + tileSizes.avgHeight.toFixed(0));
    } else {
        $("#tileWidthInfo").html("no tiles");
        $("#tileHeightInfo").html("no tiles");
    }
};

JaneliaTileLayer.prototype.requestLayout = function() {
    this.layoutRequested = true;
    const self = this;
    $.ajax({
               url: self.stackUrl + "/z/" + self.z + "/layoutFile",
               data: {
                   format: "KARSH"
               },
               cache: false,
               success: function(data) {
                   self.handleLayoutResponse(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaTileLayer.prototype.getOrderedPairKey = function(tileId, otherTileId) {
    if (tileId < otherTileId) {
        return tileId + "::" + otherTileId;
    } else {
        return otherTileId + "::" + tileId;
    }
};

JaneliaTileLayer.prototype.getPairForKey = function(key) {
    const delimiterIndex = key.indexOf("::");
    return {pId: key.substring(0, delimiterIndex), qId: key.substring(delimiterIndex+2)};
};

JaneliaTileLayer.prototype.addExpectedPairKey = function(tileId, otherTileId) {
    if ((tileId !== undefined) && (otherTileId !== undefined)) {
        this.expectedOrderedPairKeys.push(
                this.getOrderedPairKey(tileId, otherTileId));
    }
};

JaneliaTileLayer.prototype.handleLayoutResponse = function(data) {

    this.distinctSectionIds = new Set();
    this.rowToColumnDataMap = new Map();
    this.expectedOrderedPairKeys = [];
    this.matchCountResponseSectionIds = new Set();
    this.pairKeyToMatchDataMap = new Map();

    const lines = data.split('\n');

    lines.forEach(line => {

        // KARSH format (tab separated values):
        //   sectionId tileId affineData(6+values) imageCol imageRow camera rawPath temca rotation z uriString
        const fields = line.split('\t');

        if (fields.length > 15) {

            const sectionId = fields[0];
            const tileId = fields[1];
            const imageCol = parseInt(fields[fields.length - 8]);
            const imageRow = parseInt(fields[fields.length - 7]);

            if (isFinite(imageCol) && isFinite(imageRow)) {

                this.distinctSectionIds.add(sectionId);

                let columnToTileIdMap = this.rowToColumnDataMap.get(imageRow);
                if (columnToTileIdMap === undefined) {
                    columnToTileIdMap = new Map();
                    this.rowToColumnDataMap.set(imageRow, columnToTileIdMap);
                }
                columnToTileIdMap.set(imageCol, tileId);
            }

        }
    });

    for (const [row, columnToTileIdMap] of this.rowToColumnDataMap.entries()) {
        const columnToTileIdMapForPriorRow = this.rowToColumnDataMap.get(row - 1);
        for (const [column, tileId] of columnToTileIdMap.entries()) {
            if (columnToTileIdMapForPriorRow) {
                this.addExpectedPairKey(tileId, columnToTileIdMapForPriorRow.get(column));
            }
            this.addExpectedPairKey(tileId, columnToTileIdMap.get(column - 1));
        }
    }

    this.distinctSectionIds.forEach(sectionId => {
        this.requestMatchCounts(sectionId);
    })
};

JaneliaTileLayer.prototype.requestMatchCounts = function(pGroup) {
    const matchCollectionUrl = this.baseUrl + "/owner/" + this.matchOwner + "/matchCollection/" + this.matchCollection;
    const self = this;
    $.ajax({
               url: matchCollectionUrl + "/pGroup/" + pGroup + "/matches",
               data: {
                   excludeMatchDetails: true
               },
               cache: false,
               success: function(data) {
                   self.handleMatchCountResponse(data, pGroup);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaTileLayer.prototype.handleMatchCountResponse = function(data, pGroup) {

     data.forEach(pair => {
        this.pairKeyToMatchDataMap.set(this.getOrderedPairKey(pair.pId, pair.qId), pair);
    });

    this.matchCountResponseSectionIds.add(pGroup);

    if (this.matchCountResponseSectionIds.size === this.distinctSectionIds.size) {

        if (this.tileIdToBoundsMap.size === 0) {

            this.tileBoundsLists.forEach(clusterList => {
                clusterList.forEach(tileBounds => {
                    this.tileIdToBoundsMap.set(tileBounds.tileId, tileBounds);
                });
            });

        }

        this.drawLayer();
    }

};

JaneliaTileLayer.prototype.clearCanvas = function() {
    this.context2d.clearRect(0, 0, this.context2d.canvas.width, this.context2d.canvas.height);
};

JaneliaTileLayer.prototype.resizeCanvas = function() {

    this.clearCanvas();

    this.viewBounds = undefined;

    if (this.useStackBounds) {

        if (typeof this.stackMetaData !== "undefined") {
            this.viewBounds = this.stackMetaData.stats.stackBounds;
        }

    } else if (typeof this.layerBounds !== "undefined") {
        this.viewBounds = this.layerBounds;
    }

    if ((typeof this.viewBounds !== 'undefined') && (this.viewBounds)) {

        const viewWidth = (this.viewBounds.maxX - this.viewBounds.minX);
        const viewHeight = (this.viewBounds.maxY - this.viewBounds.minY);
        const minControlsPanelWidth = 400;
        const canvasPadding = 10;

        const layerDivSelector = $("#layerDiv");
        const fitWidthScale = (layerDivSelector.width() - this.canvasMargin - minControlsPanelWidth) / viewWidth;
        const fitHeightScale = (layerDivSelector.height() - this.canvasMargin - canvasPadding) / viewHeight;
        const fitBothScale = Math.min(fitWidthScale, fitHeightScale);

        this.context2d.canvas.width = layerDivSelector.width() - minControlsPanelWidth;
        this.context2d.canvas.height = layerDivSelector.height() - canvasPadding;

        this.selectionCanvas.width = this.context2d.canvas.width;
        this.selectionCanvas.height = this.context2d.canvas.height;

        this.tilesScale = fitBothScale * 0.98; // TODO: is this needed? puts a margin around the layer
    }

    this.drawLayer();
};

JaneliaTileLayer.prototype.drawLayer = function() {

    this.clearCanvas();

    if (this.tileCount > 0) {

        // color set adapted from https://sashat.me/2017/01/11/list-of-20-simple-distinct-colors/
        const colors = [
            '#4363d8', '#e6194b', '#3cb44b', '#ffe119',
            '#f58231', '#911eb4', '#46f0f0', '#f032e6',
            '#bcf60c', '#fabebe', '#008080', '#e6beff',
            '#9a6324', '#800000', '#aaffc3',
        ];
        let clusterIndex = 0;

        const smallClusterGrays = [
            '#D3D3D3', '#C0C0C0', '#A9A9A9', '#808080'
        ];
        let smallClusterIndex = 0;

        this.context2d.lineWidth = 1;

        this.tileBoundsLists.forEach(clusterList => {
            if (clusterList.length < 4) {
                this.context2d.strokeStyle = smallClusterGrays[smallClusterIndex % smallClusterGrays.length];
                smallClusterIndex++;
            } else {
                this.context2d.strokeStyle = colors[clusterIndex % colors.length];
                clusterIndex++;
            }

            clusterList.forEach(tileBounds => {
                this.drawTileBounds(this.context2d, tileBounds);
             });
        });

        this.missingConnectionMatchCount = 0;

        if ((this.missingConnectionMatchCountThreshold > 0) &&
            (this.matchCountResponseSectionIds.size > 0) &&
            (this.matchCountResponseSectionIds.size === this.distinctSectionIds.size)) {

            this.context2d.lineWidth = 3;

            this.expectedOrderedPairKeys.forEach(pairKey => {
                const matchData = this.pairKeyToMatchDataMap.get(pairKey);
                if (matchData === undefined) {
                    const pair = this.getPairForKey(pairKey);
                    const pBounds = this.tileIdToBoundsMap.get(pair.pId);
                    const qBounds = this.tileIdToBoundsMap.get(pair.qId);
                    this.drawMissingConnection(pBounds, qBounds, 0);
                } else if (matchData.matchCount <= this.missingConnectionMatchCountThreshold) {
                    const pBounds = this.tileIdToBoundsMap.get(matchData.pId);
                    const qBounds = this.tileIdToBoundsMap.get(matchData.qId);
                    this.drawMissingConnection(pBounds, qBounds, matchData.matchCount);
                }
            });

        }

        $("#missingConnectionsCount").html(this.missingConnectionMatchCount);

    }
};

JaneliaTileLayer.prototype.drawTileBounds = function(ctx, tileBounds) {

    const tileWidth = (tileBounds.maxX - tileBounds.minX);
    const tileHeight = (tileBounds.maxY - tileBounds.minY);

    let x = (tileBounds.minX - this.viewBounds.minX) * this.tilesScale;
    let y = (tileBounds.minY - this.viewBounds.minY) * this.tilesScale;
    let width = tileWidth * this.tilesScale;
    let height = tileHeight * this.tilesScale;

    if (this.boxScale < 1.0) {
        const offsetFactor = (1.0 - this.boxScale) / 2;
        const xOffset = offsetFactor * width;
        const yOffset = offsetFactor * height;

        width = width * this.boxScale;
        x = x + xOffset;
        height = height * this.boxScale;
        y = y + yOffset;
    }

    x = x + this.canvasMargin;
    y = y + this.canvasMargin;

    ctx.beginPath();
    ctx.strokeRect(x, y, width, height);
};

JaneliaTileLayer.prototype.drawMissingConnection = function(pBounds, qBounds, matchCount) {

    if (pBounds !== undefined && qBounds !== undefined) {

        this.missingConnectionMatchCount++;

        const pCenterX = pBounds.minX + ((pBounds.maxX - pBounds.minX) / 2);
        const pCenterY = pBounds.minY + ((pBounds.maxY - pBounds.minY) / 2);
        const qCenterX = qBounds.minX + ((qBounds.maxX - qBounds.minX) / 2);
        const qCenterY = qBounds.minY + ((qBounds.maxY - qBounds.minY) / 2);

        const px = ((pCenterX - this.viewBounds.minX) * this.tilesScale) + this.canvasMargin;
        const py = ((pCenterY - this.viewBounds.minY) * this.tilesScale) + this.canvasMargin;
        const qx = ((qCenterX - this.viewBounds.minX) * this.tilesScale) + this.canvasMargin;
        const qy = ((qCenterY - this.viewBounds.minY) * this.tilesScale) + this.canvasMargin;

        if (matchCount === 0) {
            this.context2d.strokeStyle = "black";
        } else {
            this.context2d.strokeStyle = "gray";
        }

        this.context2d.beginPath();
        this.context2d.moveTo(px, py);
        this.context2d.lineTo(qx, qy);
        this.context2d.stroke();

    }
};

JaneliaTileLayer.prototype.changeZ = function(zText) {
    const newZ = parseFloat(zText);
    if (newZ !== this.z) {

        let start = Math.max(0, (this.zIndex - 20));
        for (let i = 0; i < this.zValues.length; i++) {
            const index = (start + i) % this.zValues.length;
            if (newZ === this.zValues[index]) {
                this.zIndex = index;
                this.setZ(newZ);
                this.requestBounds();
                break;
            }
        }
        
    }
};

JaneliaTileLayer.prototype.scrollZ = function(indexDelta) {
    const index = this.zIndex + indexDelta;
    if ((index >= 0) && (index < this.zValues.length)) {
        this.zIndex = index;
        this.setZ(this.zValues[index]);
        this.requestBounds();
    }
};

JaneliaTileLayer.prototype.setViewBounds = function(useStackBounds) {
    if (useStackBounds !== this.useStackBounds) {
        this.useStackBounds = useStackBounds;
        this.resizeCanvas();
    }
};

JaneliaTileLayer.prototype.setBoxScale = function(boxScale) {
    this.boxScale = parseFloat(boxScale);
    this.drawLayer();
};

JaneliaTileLayer.prototype.setMissingConnectionMatchCountThreshold = function(threshold) {

    this.missingConnectionMatchCountThreshold = threshold;

    if (! this.layoutRequested) {

        $("#missingConnectionsCount").html("...");
        this.requestLayout();

    } else if ((this.matchCountResponseSectionIds.size > 0) &&
               (this.matchCountResponseSectionIds.size === this.distinctSectionIds.size)) {
        this.drawLayer();
    }

};

JaneliaTileLayer.prototype.selectTile = function(event) {

    // clear selection info
    const selectionContext = this.selectionCanvas.getContext("2d");
    selectionContext.clearRect(0, 0, this.selectionCanvas.width, this.selectionCanvas.height);
    this.selectedTileBounds = undefined;

    if (typeof this.stackMetaData !== "undefined") {

        const worldX = ((event.clientX - this.canvasMargin) / this.tilesScale) + this.viewBounds.minX;
        const worldY = ((event.clientY - this.canvasMargin) / this.tilesScale) + this.viewBounds.minY;

        // lazy load RTree on first click ...
        if (this.layerRTree === undefined) {
            this.layerRTree = new RBush(); // see https://github.com/mourner/rbush for library details
            this.tileBoundsLists.forEach(clusterList => {
                this.layerRTree.load(clusterList);
            });
        }

        const intersectingTileBoundsList = this.layerRTree.search({ minX: worldX, minY: worldY,
                                                                      maxX: worldX, maxY: worldY });

        if (intersectingTileBoundsList && intersectingTileBoundsList.length > 0) {
            this.selectedTileBounds = intersectingTileBoundsList[0];
            selectionContext.strokeStyle = "blue";
            selectionContext.lineWidth = 3;
            this.drawTileBounds(selectionContext, this.selectedTileBounds);
            // console.log("intersectingTileBoundsList: found " + intersectingTileBoundsList.length +
            //             " tiles, first is " + this.selectedTileBounds.tileId);
        }

     }

};

JaneliaTileLayer.prototype.openNewWindow = function(newWindowUrl) {
    if (newWindowUrl.length > 0) {
        const win = window.open(newWindowUrl);
        if (win) {
            win.focus();
        } else {
            alert('Please allow popups for this website');
        }
    }
};

JaneliaTileLayer.prototype.viewSelectedTileNeighbors = function() {
    if (this.selectedTileBounds) {
        const newWindowUrl = this.janeliaScriptUtilities.getViewBaseUrl() + "/tile-with-neighbors.html" +
                             "?tileId=" + this.selectedTileBounds.tileId +
                             "&renderScale=0.1" +
                             "&renderStackOwner=" + this.stackMetaData.stackId.owner +
                             "&renderStackProject=" + this.stackMetaData.stackId.project +
                             "&renderStack=" + this.stackMetaData.stackId.stack +
                             "&matchOwner=" + this.matchOwner +
                             "&matchCollection=" + this.matchCollection;
        this.openNewWindow(newWindowUrl);
    }
};

JaneliaTileLayer.prototype.viewSelectedTileInCATMAID = function(catmaidScaleLevel) {
    if (this.selectedTileBounds) {
        const catmaidBaseUrl = 'http://renderer-catmaid.int.janelia.org:8000';
        const selectedTileWidth = this.selectedTileBounds.maxX - this.selectedTileBounds.minX;
        const selectedTileHeight = this.selectedTileBounds.maxY - this.selectedTileBounds.minY;
        const worldTileCenterX = this.selectedTileBounds.minX + (selectedTileWidth / 2);
        const worldTileCenterY = this.selectedTileBounds.minY + (selectedTileHeight / 2);
        const newWindowUrl = this.janeliaScriptUtilities.getCatmaidUrl(catmaidBaseUrl,
                                                                       this.stackMetaData.stackId,
                                                                       this.stackMetaData.currentVersion,
                                                                       worldTileCenterX, worldTileCenterY, this.z,
                                                                       catmaidScaleLevel);
        this.openNewWindow(newWindowUrl);
    }
};

