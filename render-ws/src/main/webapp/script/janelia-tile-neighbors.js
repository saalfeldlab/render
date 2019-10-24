/**
 * @typedef {Object} CanvasMatches
 * @property {String} pGroupId
 * @property {String} pId
 * @property {String} qGroupId
 * @property {String} qId
 * @property {Array} matches
 */

var JaneliaTile2 = function(tileSpec, stackUrl, matchCollectionUrl, renderQueryParameters, scale, canvas, janeliaTileMap) {

    this.tileSpec = tileSpec;
    this.stackUrl = stackUrl;
    this.matchCollectionUrl = matchCollectionUrl;
    this.scale = scale;
    this.canvas = canvas;
    this.janeliaTileMap = janeliaTileMap;

    this.specUrl = this.stackUrl + "/tile/" + this.tileSpec.tileId;

    this.renderUrl = this.specUrl + "/render-parameters?" + renderQueryParameters;
    this.imageUrl = this.specUrl + "/jpeg-image?" + renderQueryParameters + "&scale=" + this.scale;

    this.image = new Image();
    this.imagePositioned = false;
    this.x = -1;
    this.y = -1;

    var self = this;
    this.image.onload = function() {
        self.drawLoadedImage();
        if (typeof self.matchCollectionUrl !== 'undefined') {
            self.loadAllMatches()
        }
    };

    this.matches = [];
    this.matchIndex = -1;
    this.matchInfoSelector = undefined;
    this.drawMatchLines = true;
};

JaneliaTile2.prototype.getMatchesUrl = function(qTile) {
    return this.matchCollectionUrl + "/group/" + this.tileSpec.layout.sectionId +
                   "/id/" + this.tileSpec.tileId + "/matchesWith/" +
                   qTile.tileSpec.layout.sectionId + "/id/" + qTile.tileSpec.tileId;
};

JaneliaTile2.prototype.drawLoadedImage = function() {
    var scaledWidth = this.image.naturalWidth;
    var scaledHeight = this.image.naturalHeight;

    var context = this.canvas.getContext("2d");
    var canvasOffset = 4;
    var tileMargin = 4;
    // TODO: fix this for cases where tiles are different sizes
    this.x = (this.column * (scaledWidth + tileMargin)) + canvasOffset;
    this.y = (this.row * (scaledHeight + tileMargin)) + canvasOffset;
    context.drawImage(this.image, this.x, this.y);

    this.imagePositioned = true;
};

JaneliaTile2.prototype.setRowAndColumn = function(row, column) {
    this.row = row;
    this.column = column;
};

JaneliaTile2.prototype.setSelected = function(isSelected) {
    if (this.imagePositioned) {
        var context = this.canvas.getContext("2d");

        context.strokeStyle = 'white';
        if (isSelected) {
            context.strokeStyle = 'red';
        }

        context.beginPath();
        context.rect(this.x - 1, this.y - 1, this.image.naturalWidth + 2, this.image.naturalHeight + 2);
        context.lineWidth = 3;
        context.stroke();
    }
};

JaneliaTile2.prototype.loadImage = function() {
    this.imagePositioned = false;
    this.image.src = this.imageUrl;
};

JaneliaTile2.prototype.loadAllMatches = function() {

    this.matches = [];

    var pTile = this;
    var pGroupId = pTile.tileSpec.layout.sectionId;
    var pId = pTile.tileSpec.tileId;

    for (var qTileId in pTile.janeliaTileMap) {
        if (pTile.janeliaTileMap.hasOwnProperty(qTileId)) {

            var qTile = pTile.janeliaTileMap[qTileId];
            var qGroupId = qTile.tileSpec.layout.sectionId;

            if ((pGroupId < qGroupId) || ((pGroupId === qGroupId) && (pId < qTileId))) {

                var matchesUrl = pTile.getMatchesUrl(qTile);

                $.ajax({
                           url: matchesUrl,
                           cache: false,
                           success: function(data) {
                               pTile.addMatchPair(data);
                           },
                           error: function(data, text, xhr) {
                               console.log(xhr);
                           }
                       });

            }

        }

    }

};

JaneliaTile2.prototype.addMatchPair = function(data) {
    for (var i = 0; i < data.length; i++) {
        this.matches.push(data[i]);
        this.drawMatches(data[i]);
    }
};

JaneliaTile2.prototype.drawMatches = function(canvasMatches) {

    var pTile = this.janeliaTileMap[canvasMatches.pId];
    var qTile = this.janeliaTileMap[canvasMatches.qId];

    var colors = ['#00ff00', '#f48342', '#42eef4', '#f442f1'];

    if (pTile.imagePositioned && qTile.imagePositioned) {

        var context = this.canvas.getContext("2d");
        context.lineWidth = 1;

        var matchCount = canvasMatches.matches.w.length;

        for (var matchIndex = 0; matchIndex < matchCount; matchIndex++) {
            context.strokeStyle = colors[matchIndex % colors.length];
            this.drawMatch(canvasMatches, matchIndex, pTile, qTile, context);
        }

        if ((typeof pTile.matchInfoSelector !== 'undefined')) {
            var matchesUrl = pTile.getMatchesUrl(qTile);
            var matchInfoHtml = matchCount + ' total matches ' +
                                "<input type='button' value='Delete Matches' onclick='tilePair.deleteMatches(\"" +
                                matchesUrl + "\")' />";
            $(pTile.matchInfoSelector).html(matchInfoHtml);
        }

    } else {

        var self = this;
        setTimeout(function(){
            self.drawMatches(canvasMatches);
        }, 500);

    }
};

JaneliaTile2.prototype.drawMatch = function(canvasMatches, matchIndex, pTile, qTile, context) {

    var pMatches = canvasMatches.matches.p;
    var qMatches = canvasMatches.matches.q;

    var px = (pMatches[0][matchIndex] * this.scale) + pTile.x;
    var py = (pMatches[1][matchIndex] * this.scale) + pTile.y;
    var qx = (qMatches[0][matchIndex] * this.scale) + qTile.x;
    var qy = (qMatches[1][matchIndex] * this.scale) + qTile.y;

    if (this.drawMatchLines) {
        context.beginPath();
        context.moveTo(px, py);
        context.lineTo(qx, qy);
        context.stroke();
    } else {
        const radius = 3;
        const twoPI = Math.PI * 2;
        context.beginPath();
        context.arc(px, py, radius, 0, twoPI);
        context.stroke();
        context.beginPath();
        context.arc(qx, qy, radius, 0, twoPI);
        context.stroke();
    }
};

var JaneliaTileWithNeighbors = function(baseUrl, owner, project, stack, matchOwner, matchCollection, renderQueryParameters, scale, canvas) {

    this.baseUrl = baseUrl;
    this.owner = owner;
    this.project = project;
    this.stack = stack;
    this.matchOwner = matchOwner;
    this.matchCollection = matchCollection;
    this.renderQueryParameters = renderQueryParameters;
    this.scale = scale;
    this.canvas = canvas;
    this.neighborSizeFactor = 0.3;

    this.stackUrl = this.baseUrl + "/owner/" + this.owner + "/project/" + this.project + "/stack/" + this.stack;
    this.tileUrl = this.stackUrl + "/tile/";

    if ((typeof matchOwner !== 'undefined') && (matchOwner.length > 0) &&
        (typeof matchCollection !== 'undefined') && (matchCollection.length > 0)) {

        this.matchCollectionUrl = this.baseUrl + "/owner/" + this.matchOwner + "/matchCollection/" + this.matchCollection;

    } else {
        this.matchCollectionUrl = undefined;
    }

    this.drawMatchLines = true;

    this.janeliaTileMap = {};

    this.selectedTile = undefined;
};

JaneliaTileWithNeighbors.prototype.setNeighborSizeFactor = function(factor) {
    this.neighborSizeFactor = factor;
};

JaneliaTileWithNeighbors.prototype.setTileId = function(tileId) {
    this.tileId = tileId;

    $("#tileId").html(tileId);

    var ctx = this.canvas.getContext("2d");
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

    const queryParameters = "?widthFactor=" + this.neighborSizeFactor + "&heightFactor=" + this.neighborSizeFactor;

    var self = this;
    $.ajax({
               url: self.tileUrl + tileId + "/withNeighbors/render-parameters" + queryParameters,
               cache: false,
               success: function(data) {
                   self.loadNeighbors(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

const JaneliaGridOrientation = function(tileSpecs,
                                        columnOffset) {

    this.columnOffset = columnOffset;
    
    const self = this;

    let specA = tileSpecs[0];

    this.minRow = specA.layout.imageRow;
    this.maxRow = specA.layout.imageRow;
    this.minColumn = specA.layout.imageCol;
    this.maxColumn = specA.layout.imageCol;

    for (let index = 1; index < tileSpecs.length; index++) {
        const tileSpec = tileSpecs[index];
        this.minRow = Math.min(this.minRow, tileSpec.layout.imageRow);
        this.maxRow = Math.max(this.maxRow, tileSpec.layout.imageRow);
        this.minColumn = Math.min(this.minColumn, tileSpec.layout.imageCol);
        this.maxColumn = Math.max(this.maxColumn, tileSpec.layout.imageCol);
    }

    // assume actual orientation is same as layout:           AB
    this.getViewRowAndColumn = function (forTileSpec) {    // CD
        return {
            row: forTileSpec.layout.imageRow - self.minRow,
            column: forTileSpec.layout.imageCol - self.minColumn + self.columnOffset,
            maxViewRow: self.maxRow - self.minRow,
            maxViewColumn: self.maxColumn - self.minColumn + self.columnOffset
        };
    };

    // // actual orientation is rotated 90 degrees from layout:  DA
    // const rotate90 = function (forTileSpec) {              // CB
    //     return {
    //         row: forTileSpec.layout.imageCol - self.minColumn,
    //         column: self.maxRow - forTileSpec.layout.imageRow + self.columnOffset,
    //         maxViewColumn: self.maxRow - self.minRow + self.columnOffset
    //     };
    // };
    //
    // // actual orientation is rotated 180 degrees from layout: DC
    // const rotate180 = function (forTileSpec) {             // BA
    //     return {
    //         row: self.maxRow - forTileSpec.layout.imageRow,
    //         column: self.maxColumn - forTileSpec.layout.imageCol + self.columnOffset,
    //         maxViewColumn: self.maxColumn - self.minColumn + self.columnOffset
    //     };
    // };
    //
    // // actual orientation is rotated 270 degrees from layout: BC
    // const rotate270 = function (forTileSpec) {             // AD
    //     return {
    //         row: self.maxColumn - forTileSpec.layout.imageCol,
    //         column: forTileSpec.layout.imageRow - self.minRow + self.columnOffset,
    //         maxViewColumn: self.maxRow - self.minRow + self.columnOffset
    //     };
    // };
    //
    // if (tileSpecs.length > 1) {
    //
    //     let specB = tileSpecs[1];
    //
    //     if (specA.layout.imageRow > specB.layout.imageRow) {
    //         specB = specA;
    //         specA = tileSpecs[1];
    //     } else if (specA.layout.imageRow === specB.layout.imageRow) {
    //         if (specA.layout.imageCol > specB.layout.imageCol) {
    //             specB = specA;
    //             specA = tileSpecs[1];
    //         }
    //     }
    //
    //     const deltaX = specA.minX - specB.minX;
    //     const deltaY = specA.minY - specB.minY;
    //
    //     if (specA.layout.imageRow === specB.layout.imageRow) {
    //
    //         if (Math.abs(deltaX) > Math.abs(deltaY)) {
    //             if (specA.minX > specB.minX) {
    //                 this.getViewRowAndColumn = rotate180;
    //             } // else actual == layout
    //         } else if (specA.minY < specB.minY) {
    //             this.getViewRowAndColumn = rotate90;
    //         } else {
    //             this.getViewRowAndColumn = rotate270;
    //         }
    //
    //     } else if (specA.layout.imageCol === specB.layout.imageCol) {
    //
    //         if (Math.abs(deltaY) > Math.abs(deltaX)) {
    //             if (specA.minY > specB.minY) {
    //                 this.getViewRowAndColumn = rotate180;
    //             } // else actual == layout
    //         } else if (specA.minX < specB.minX) {
    //             this.getViewRowAndColumn = rotate270;
    //         } else {
    //             this.getViewRowAndColumn = rotate90;
    //         }
    //
    //     } else if (deltaX > 0) {
    //
    //         if (deltaY > 0) {
    //             this.getViewRowAndColumn = rotate180;
    //         } else {
    //             this.getViewRowAndColumn = rotate90;
    //         }
    //
    //     } else if (deltaY > 0) {
    //         this.getViewRowAndColumn = rotate270;
    //     } // else actual == layout
    //
    // }

    const viewRowAndColumnForSpecA = this.getViewRowAndColumn(specA);
    this.maxViewRow = viewRowAndColumnForSpecA.maxViewRow;
    this.maxViewColumn = viewRowAndColumnForSpecA.maxViewColumn;
};

/**
 * @param data
 * @param data.layout
 * @param data.layout.imageRow
 * @param data.layout.imageCol
 */
JaneliaTileWithNeighbors.prototype.loadNeighbors = function(data) {

    const tileSpecs = data["tileSpecs"];

    this.janeliaTileMap = {};

    const sectionToTileSpecsMap = {};

    let pageQueryParameters = new JaneliaQueryParameters();

    for (let index = 0; index < tileSpecs.length; index++) {

        const tileSpec = tileSpecs[index];

        if (tileSpec.layout.sectionId !== pageQueryParameters.get("sectionId", tileSpec.layout.sectionId)) {
            continue;
        }

        if (! sectionToTileSpecsMap.hasOwnProperty(tileSpec.layout.sectionId)) {
            sectionToTileSpecsMap[tileSpec.layout.sectionId] = [];
        }

        sectionToTileSpecsMap[tileSpec.layout.sectionId].push(tileSpec);

        this.janeliaTileMap[tileSpec.tileId] =
                new JaneliaTile2(tileSpec, this.stackUrl, this.matchCollectionUrl, this.renderQueryParameters,
                                 this.scale, this.canvas, this.janeliaTileMap);
    }

    let maxRow = 0;
    let maxColumn = 0;

    const sortedSectionIds = Object.keys(sectionToTileSpecsMap).sort();

    let columnOffset = 0;
    for (let i = 0; i < sortedSectionIds.length; i++) {
        const sectionId = sortedSectionIds[i];
        const sectionTileSpecs = sectionToTileSpecsMap[sectionId];

        // TODO: consider saving orientation for later moves

        const gridOrientation = new JaneliaGridOrientation(sectionTileSpecs,
                                                           columnOffset);

        maxRow = Math.max(maxRow, gridOrientation.maxViewRow);
        maxColumn = Math.max(maxColumn, gridOrientation.maxViewColumn);
        columnOffset = gridOrientation.maxViewColumn + 2;

        for (let j = 0; j < sectionTileSpecs.length; j++) {
            const tileSpec = sectionTileSpecs[j];
            const viewRowAndColumn = gridOrientation.getViewRowAndColumn(tileSpec);
            const janeliaTile2 = this.janeliaTileMap[tileSpec.tileId];
            janeliaTile2.setRowAndColumn(viewRowAndColumn.row, viewRowAndColumn.column);
        }
    }

    const context = this.canvas.getContext("2d");
    const canvasOffset = 4;
    const tileMargin = 4;
    // TODO: fix this for cases where tiles are different sizes and for non-FAFB tiles
    const scaledWidth = 2760 * this.scale;
    const scaledHeight = 2330 * this.scale;
    const maxX = ((maxColumn + 1) * (scaledWidth + tileMargin)) + canvasOffset;
    const maxY = ((maxRow + 1) * (scaledHeight + tileMargin)) + canvasOffset;

    context.canvas.width = maxX;
    context.canvas.height = maxY;

    // only load images (and then point matches) after rows and columns have been set for all tiles
    for (const tileId in this.janeliaTileMap) {
        if (this.janeliaTileMap.hasOwnProperty(tileId)) {
            const janeliaTile2 = this.janeliaTileMap[tileId];
            janeliaTile2.loadImage();
        }
    }

};

JaneliaTileWithNeighbors.prototype.setTilePair = function(tileId, otherTileId) {
    var self = this;
    $.ajax({
               url: self.tileUrl + tileId,
               cache: false,
               success: function(data) {
                   self.retrieveOtherTileSpec(data, otherTileId);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaTileWithNeighbors.prototype.retrieveOtherTileSpec = function(firstTileSpec, otherTileId) {
    var self = this;
    $.ajax({
               url: self.tileUrl + otherTileId,
               cache: false,
               success: function(data) {
                   self.buildTilePair(firstTileSpec, data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaTileWithNeighbors.prototype.buildTilePair = function(tileSpec, otherTileSpec) {

    this.janeliaTileMap = {};

    var orderedPair = this.getOrderedTileSpecPair(tileSpec, otherTileSpec);

    this.pTileId = orderedPair.pTileSpec.tileId;
    this.qTileId = orderedPair.qTileSpec.tileId;

    var pTile = this.buildTileAtPosition(orderedPair.pTileSpec, 0, 0);
    var qTile = this.buildTileAtPosition(orderedPair.qTileSpec, 0, 1);

    pTile.matchInfoSelector = "#matchInfo";

    var canvasMargin = 100;

    var pTileWidth = (orderedPair.pTileSpec.maxX - orderedPair.pTileSpec.minX + 1) * this.scale;
    var qTileWidth = (orderedPair.qTileSpec.maxX - orderedPair.qTileSpec.minX + 1) * this.scale;
    var canvasWidth = canvasMargin + pTileWidth + qTileWidth;

    var pTileHeight = (orderedPair.pTileSpec.maxY - orderedPair.pTileSpec.minY + 1) * this.scale;
    var qTileHeight = (orderedPair.qTileSpec.maxY - orderedPair.qTileSpec.minY + 1) * this.scale;
    var canvasHeight = canvasMargin + pTileHeight + qTileHeight;

    var context = this.canvas.getContext("2d");
    context.canvas.width = canvasWidth;
    context.canvas.height = canvasHeight;

    pTile.loadImage();
    qTile.loadImage();

    var matchesUrl = pTile.getMatchesUrl(qTile);
    $('#pairMatchesLink').html('(<a href=\"' + matchesUrl + '" target="_blank"">pair matches</a>)');

    this.updateTileIdHtml('p', pTile);
    this.updateTileIdHtml('q', qTile);
};

JaneliaTileWithNeighbors.prototype.updateTileIdHtml = function(pOrQ, tile2) {
    var tileIdHtml = '<a href=\"' + tile2.specUrl + '" target="_blank"">' + tile2.tileSpec.tileId + '</a>' +
                      '<br/>(<a href=\"' + tile2.renderUrl + '" target="_blank"">normalized render parameters</a>)';
    var selectorId = '#' + pOrQ + 'TileId';
    $(selectorId).html(tileIdHtml);
};

JaneliaTileWithNeighbors.prototype.buildTileAtPosition = function(tileSpec, row, column) {
    var tile = new JaneliaTile2(
            tileSpec,
            this.stackUrl, this.matchCollectionUrl, this.renderQueryParameters, this.scale,
            this.canvas, this.janeliaTileMap);
    tile.setRowAndColumn(row, column);
    this.janeliaTileMap[tileSpec.tileId] = tile;
    return tile;
};

JaneliaTileWithNeighbors.prototype.drawMatch = function(matchIndexDelta) {

    var canvasMatches;
    var pTile = undefined;
    var qTile = undefined;

    for (var tileId in this.janeliaTileMap) {
        if (this.janeliaTileMap.hasOwnProperty(tileId)) {
            var tile2 = this.janeliaTileMap[tileId];
            if (tile2.matches.length > 0) {
                canvasMatches = tile2.matches[0];
                pTile = this.janeliaTileMap[canvasMatches.pId];
                qTile = this.janeliaTileMap[canvasMatches.qId];
                break;
            }
        }
    }

    if ((typeof pTile !== 'undefined') && (typeof qTile !== 'undefined')) {

        var context = this.canvas.getContext("2d");
        context.clearRect(0, 0, this.canvas.width, this.canvas.height);

        // noinspection JSObjectNullOrUndefined
        pTile.drawLoadedImage();
        // noinspection JSObjectNullOrUndefined
        qTile.drawLoadedImage();

        var matchCount = canvasMatches.matches.w.length;

        if (typeof matchIndexDelta !== 'undefined') {

            pTile.matchIndex = (pTile.matchIndex + matchIndexDelta) % matchCount;
            if (pTile.matchIndex < 0) {
                pTile.matchIndex = matchCount - 1;
            }

            context.strokeStyle = '#00ff00';
            context.lineWidth = 1;

            pTile.drawMatch(canvasMatches, pTile.matchIndex, pTile, qTile, context);

            if ((typeof pTile.matchInfoSelector !== 'undefined')) {
                $(pTile.matchInfoSelector).html('match ' + (pTile.matchIndex + 1) + ' of ' + matchCount);
            }

        } else {

            pTile.matchIndex = -1;
            pTile.drawMatches(canvasMatches);

        }

    }
};

JaneliaTileWithNeighbors.prototype.drawAllMatches = function() {
    this.drawMatch(undefined);
};

JaneliaTileWithNeighbors.prototype.rotatePair = function() {

    var pTile = this.janeliaTileMap[this.pTileId];
    var qTile = this.janeliaTileMap[this.qTileId];

    if (pTile.row === 0) {
        if (qTile.row === 0) {
            if (pTile.column === 0) {
                // PQ -> P
                //       Q
                qTile.row = 1;
                qTile.column = 0;
            } else {
                // QP -> Q
                //       P
                pTile.row = 1;
                pTile.column = 0;
            }
        } else { // qTile.row == 1
            // P -> QP
            // Q
            qTile.row = 0;
            pTile.column = 1;
        }
    } else { // pTile.row == 1
        // Q -> PQ
        // P
        pTile.row = 0;
        qTile.column = 1;
    }

    this.drawAllMatches();
};

JaneliaTileWithNeighbors.prototype.move = function(rowDelta, columnDelta) {

    if ((typeof this.tileId !== 'undefined') && (this.tileId.length > 0)) {

        var nextRow = this.janeliaTileMap[this.tileId].row + rowDelta;
        var nextColumn = this.janeliaTileMap[this.tileId].column + columnDelta;

        var nextTileId = this.tileId;
        for (var neighborTileId in this.janeliaTileMap) {
            if (this.janeliaTileMap.hasOwnProperty(neighborTileId)) {
                var neighbor = this.janeliaTileMap[neighborTileId];
                if ((neighbor.row === nextRow) && (neighbor.column === nextColumn)) {
                    nextTileId = neighborTileId;
                    break;
                }
            }
        }

        if (nextTileId !== this.tileId) {
            this.setTileId(nextTileId);
        }

    }

};

JaneliaTileWithNeighbors.prototype.selectTile = function(canvasClickX, canvasClickY, shiftKey) {

    const previouslySelectedTile = this.selectedTile;

    this.selectedTile = undefined;

    if ((typeof this.tileId !== 'undefined') && (this.tileId.length > 0)) {

        const unselectedTiles = [];
        for (var neighborTileId in this.janeliaTileMap) {
            if (this.janeliaTileMap.hasOwnProperty(neighborTileId)) {
                var neighbor = this.janeliaTileMap[neighborTileId];
                if (neighbor.imagePositioned) {
                    if ((neighbor.x <= canvasClickX) && (neighbor.y <= canvasClickY)) {
                        var maxX = neighbor.x + neighbor.image.naturalWidth;
                        var maxY = neighbor.y + neighbor.image.naturalHeight;
                        if ((maxX >= canvasClickX) && (maxY >= canvasClickY)) {

                            this.selectedTile = neighbor;

                        } else {
                            unselectedTiles.push(neighbor);
                        }
                    } else {
                        unselectedTiles.push(neighbor);
                    }
                }
            }
        }

        const self = this;

        if ((self.selectedTile !== undefined) && (! shiftKey)) {

            const ctx = this.canvas.getContext("2d");
            ctx.clearRect(0, 0, self.canvas.width, self.canvas.height);

            self.selectedTile.drawLoadedImage();
            self.selectedTile.drawMatchLines = false;

            const selectedTileId = self.selectedTile.tileSpec.tileId;
            unselectedTiles.forEach(tile => {
                tile.drawLoadedImage();
                tile.matches.forEach(canvasMatches => {
                    if ((canvasMatches.pId === selectedTileId) || (canvasMatches.qId === selectedTileId)) {
                        self.selectedTile.drawMatches(canvasMatches);
                    }
                });
            });

            self.selectedTile.matches.forEach(canvasMatches => {
                self.selectedTile.drawMatches(canvasMatches)
            });

            self.selectedTile.setSelected(true);

        } else if (previouslySelectedTile !== undefined) {

            previouslySelectedTile.setSelected(false);
            previouslySelectedTile.drawMatchLines = true;

            unselectedTiles.forEach(tile => {
                if (tile.tileSpec.tileId !== previouslySelectedTile.tileSpec.tileId) {
                    tile.matches.forEach(canvasMatches => {
                        tile.drawMatches(canvasMatches)
                    });
                }
            });

        }

    }

    return this.selectedTile;
};

JaneliaTileWithNeighbors.prototype.getOrderedTileSpecPair = function(tileSpecA, tileSpecB) {

    var orderedPair = { pTileSpec: tileSpecA, qTileSpec: tileSpecB };

    var aGroupId = tileSpecA.layout.sectionId;
    var aId = tileSpecA.tileId;
    var bGroupId = tileSpecB.layout.sectionId;
    var bId = tileSpecB.tileId;

    if ((aGroupId > bGroupId) || ((aGroupId === bGroupId) && (aId > bId))) {
        orderedPair.pTileSpec = tileSpecB;
        orderedPair.qTileSpec = tileSpecA;
    }

    return orderedPair;
};

JaneliaTileWithNeighbors.prototype.viewTilePair = function(tileA, tileB, renderScale) {

    var orderedPair = this.getOrderedTileSpecPair(tileA.tileSpec, tileB.tileSpec);

    var parameters = {
        'renderStackOwner': this.owner, 'renderStackProject': this.project, 'renderStack': this.stack,
        'renderScale': renderScale,
        'matchOwner': this.matchOwner, 'matchCollection': this.matchCollection,
        'pGroupId': orderedPair.pTileSpec.layout.sectionId, 'pId': orderedPair.pTileSpec.tileId,
        'qGroupId': orderedPair.qTileSpec.layout.sectionId, 'qId': orderedPair.qTileSpec.tileId
    };

    var tilePairUrl = "tile-pair.html?" + $.param(parameters);

    var win = window.open(tilePairUrl);
    if (win) {
        win.focus();
    } else {
        alert('Please allow popups for this website');
    }

};

JaneliaTileWithNeighbors.prototype.deleteMatches = function(pairMatchesUrl) {
    if (confirm("This will remove all matches for this tile pair.  Are you sure you want to do this?")) {
        $.ajax({
                   url: pairMatchesUrl,
                   type: 'DELETE',
                   success: function () {
                       window.location.reload();
                   },
                   error: function (data,
                                    text,
                                    xhr) {
                       console.log(xhr);
                   }
               });
    }
    return false;
};

JaneliaTileWithNeighbors.prototype.runTrial = function(tileA, tileB, trialRunningSelector, errorMessageSelector) {

    const orderedPair = this.getOrderedTileSpecPair(tileA.tileSpec, tileB.tileSpec);

    const queryParameters = new JaneliaQueryParameters();
    const trialSourceStack = queryParameters.get("trialSourceStack", "v12_acquire");

    const baseRenderUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/flyTEM/project/FAFB00/stack/" +
                          trialSourceStack + "/tile/";
    const renderUrlSuffix = "/render-parameters?excludeMask=true&normalizeForMatching=true&width=2760&height=2330&filter=true&scale=0.7";
    const pRenderUrl = baseRenderUrl + orderedPair.pTileSpec.tileId + renderUrlSuffix;
    const qRenderUrl = baseRenderUrl + orderedPair.qTileSpec.tileId + renderUrlSuffix;

    let pClipPosition = "TOP";
    let deltaX = orderedPair.qTileSpec.minX - orderedPair.pTileSpec.minX;
    let deltaY = orderedPair.qTileSpec.minY - orderedPair.pTileSpec.minY;

    // if layout info is available, use original row and column instead of potentially rotated stack positions
    if (orderedPair.pTileSpec.layout && orderedPair.qTileSpec.layout) {
        deltaX = orderedPair.qTileSpec.layout.imageCol - orderedPair.pTileSpec.layout.imageCol;
        deltaY = orderedPair.qTileSpec.layout.imageRow - orderedPair.pTileSpec.layout.imageRow;
    }
    
    // noinspection JSSuspiciousNameCombination
    if (Math.abs(deltaX) > Math.abs(deltaY)) {
        pClipPosition = deltaX > 0 ? "LEFT" : "RIGHT";
    } else if (deltaY < 0) {
        pClipPosition = "BOTTOM";
    }
    
    const featureAndMatchParameters = {
        "siftFeatureParameters": {
            "fdSize": 4,
            "minScale": 0.8,
            "maxScale": 1.0,
            "steps": 3
        },
        "matchDerivationParameters": {
            "matchRod": 0.92,
            "matchModelType": "TRANSLATION",
            "matchIterations": 1000,
            "matchMaxEpsilon": 7,
            "matchMinInlierRatio": 0.0,
            "matchMinNumInliers": 7,
            "matchMaxTrust": 4,
            "matchFilter": "SINGLE_SET"
        },
        "pClipPosition": pClipPosition,
        "clipPixels": 600
    };

    const requestData = {
        featureAndMatchParameters: featureAndMatchParameters,
        pRenderParametersUrl: pRenderUrl,
        qRenderParametersUrl: qRenderUrl
    };

    const matchTrialUrl = this.baseUrl + "/owner/" + this.owner + "/matchTrial";
    const baseViewUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/view/match-trial.html?matchTrialId=";
    const viewParameters = "&scale=0.2&saveToCollection=" + this.matchCollection;

    errorMessageSelector.text('');
    trialRunningSelector.show();

    $.ajax({
               type: "POST",
               headers: {
                   'Accept': 'application/json',
                   'Content-Type': 'application/json'
               },
               url: matchTrialUrl,
               data: JSON.stringify(requestData),
               cache: false,
               success: function(data) {
                   const matchTrialResultUrl = baseViewUrl + data.id + viewParameters;
                   const win = window.open(matchTrialResultUrl);
                   if (win) {
                       win.focus();
                   } else {
                       alert('Please allow popups for this website');
                   }
                   trialRunningSelector.hide();
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
                   errorMessageSelector.text(data.statusText + ': ' + data.responseText);
                   trialRunningSelector.hide();
               }
           });

};

JaneliaTileWithNeighbors.prototype.toggleLinesAndPoints = function() {
    this.drawMatchLines = ! this.drawMatchLines;
    let neighborTileId;
    for (neighborTileId in this.janeliaTileMap) {
        if (this.janeliaTileMap.hasOwnProperty(neighborTileId)) {
            let neighbor = this.janeliaTileMap[neighborTileId];
            neighbor.drawMatchLines = this.drawMatchLines;
        }
    }

    this.drawAllMatches();

    const toggleLinesAndPointsSelector = $("#toggleLinesAndPoints");
    if (this.drawMatchLines) {
        toggleLinesAndPointsSelector.prop('value', 'Points')
    } else {
        toggleLinesAndPointsSelector.prop('value', 'Lines')
    }
};

