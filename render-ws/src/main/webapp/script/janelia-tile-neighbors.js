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

    this.renderQueryParameters = renderQueryParameters;
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

            if ((pGroupId < qGroupId) || ((pGroupId == qGroupId) && (pId < qTileId))) {

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
            $(pTile.matchInfoSelector).html(matchCount + ' total matches');
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

    context.beginPath();
    context.moveTo(px, py);
    context.lineTo(qx, qy);
    context.stroke();
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

    this.stackUrl = this.baseUrl + "/owner/" + this.owner + "/project/" + this.project + "/stack/" + this.stack;
    this.tileUrl = this.stackUrl + "/tile/";

    if ((typeof matchOwner !== 'undefined') && (matchOwner.length > 0) &&
        (typeof matchCollection !== 'undefined') && (matchCollection.length > 0)) {

        this.matchCollectionUrl = this.baseUrl + "/owner/" + this.matchOwner + "/matchCollection/" + this.matchCollection;

    } else {
        this.matchCollectionUrl = undefined;
    }

    this.janeliaTileMap = {};
};

JaneliaTileWithNeighbors.prototype.setTileId = function(tileId) {
    this.tileId = tileId;

    $("#tileId").html(tileId);

    var ctx = this.canvas.getContext("2d");
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

    var self = this;
    $.ajax({
               url: self.tileUrl + tileId + "/withNeighbors/render-parameters",
               cache: false,
               success: function(data) {
                   self.loadNeighbors(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

/**
 * @param data
 * @param data.layout
 * @param data.layout.imageRow
 * @param data.layout.imageCol
 */
JaneliaTileWithNeighbors.prototype.loadNeighbors = function(data) {

    this.janeliaTileMap = {};

    var sectionMap = {};
    var tileSpecs = data["tileSpecs"];

    var tileSpec;
    var sectionData;
    var originalTileWidth;
    var originalTileHeight;

    for (var index = 0; index < tileSpecs.length; index++) {

        tileSpec = tileSpecs[index];

        if (! sectionMap.hasOwnProperty(tileSpec.layout.sectionId)) {
            sectionMap[tileSpec.layout.sectionId] = {
                minXList: [],
                minYList: [],
                janeliaTiles: {}
            };
        }

        sectionData = sectionMap[tileSpec.layout.sectionId];

        sectionData.minXList.push({"tileId": tileSpec.tileId, "value": tileSpec.minX});
        sectionData.minYList.push({"tileId": tileSpec.tileId, "value": tileSpec.minY});

        if (this.tileId == tileSpec.tileId) {
            originalTileWidth = tileSpec.width;
            originalTileHeight = tileSpec.height;
        }

        sectionData.janeliaTiles[tileSpec.tileId] =
                new JaneliaTile2(tileSpec, this.stackUrl, this.matchCollectionUrl, this.renderQueryParameters, this.scale, this.canvas, this.janeliaTileMap);
    }

    var compareValues = function(a, b) {
        return a.value - b.value;
    };

    var deriveRowOrColumn = function(forTileId, list, size) {
        var rowOrColumn = 0;
        if ((list.length > 0) && (forTileId != list[0].tileId)) {
            for (var index = 1; index < list.length; index++) {
                var prevDelta = list[index].value - list[index - 1].value;
                if ((prevDelta / size) > 0.5) {
                    rowOrColumn = rowOrColumn + 1;
                }
                if (forTileId == list[index].tileId) {
                    break;
                }
            }
        }
        return rowOrColumn;
    };

    var firstColumnForSection = 0;
    var tileId;
    var janeliaTile2;

    for (var sectionId in sectionMap) {
        if (sectionMap.hasOwnProperty(sectionId)) {
            sectionData = sectionMap[sectionId];

            sectionData.minXList.sort(compareValues);
            sectionData.minYList.sort(compareValues);

            for (tileId in sectionData.janeliaTiles) {
                if (sectionData.janeliaTiles.hasOwnProperty(tileId)) {

                    janeliaTile2 = sectionData.janeliaTiles[tileId];

                    this.janeliaTileMap[tileId] = janeliaTile2;

                    tileSpec = janeliaTile2.tileSpec;

                    janeliaTile2.setRowAndColumn(
                            deriveRowOrColumn(tileId, sectionData.minYList, originalTileHeight),
                            firstColumnForSection + deriveRowOrColumn(tileId, sectionData.minXList, originalTileWidth));
                }
            }
            firstColumnForSection = firstColumnForSection + sectionData.minXList[sectionData.minXList.length - 1].value;
        }
    }

    // only load images (and then point matches) after rows and columns have been set for all tiles
    for (tileId in this.janeliaTileMap) {
        if (this.janeliaTileMap.hasOwnProperty(tileId)) {
            janeliaTile2 = this.janeliaTileMap[tileId];
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

    var canvasOffset = 4;
    var tileMargin = 4;

    var pTileWidth = (orderedPair.pTileSpec.maxX - orderedPair.pTileSpec.minX + 1) * this.scale;
    var qTileWidth = (orderedPair.qTileSpec.maxX - orderedPair.qTileSpec.minX + 1) * this.scale;
    var canvasWidth = canvasOffset + tileMargin + pTileWidth + qTileWidth;

    var pTileHeight = (orderedPair.pTileSpec.maxY - orderedPair.pTileSpec.minY + 1) * this.scale;
    var qTileHeight = (orderedPair.qTileSpec.maxY - orderedPair.qTileSpec.minY + 1) * this.scale;
    var canvasHeight = canvasOffset + tileMargin + pTileHeight + qTileHeight;

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

JaneliaTileWithNeighbors.prototype.updateTileIdHtml = function(porq, tile2) {
    var tileIdHtml = '<a href=\"' + tile2.specUrl + '" target="_blank"">' + tile2.tileSpec.tileId + '</a>' +
                      '<br/>(<a href=\"' + tile2.renderUrl + '" target="_blank"">normalized render parameters</a>)';
    var selectorId = '#' + porq + 'TileId';
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

        pTile.drawLoadedImage();
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

    if (pTile.row == 0) {
        if (qTile.row == 0) {
            if (pTile.column == 0) {
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
                if ((neighbor.row == nextRow) && (neighbor.column == nextColumn)) {
                    nextTileId = neighborTileId;
                    break;
                }
            }
        }

        if (nextTileId != this.tileId) {
            this.setTileId(nextTileId);
        }

    }

};

JaneliaTileWithNeighbors.prototype.selectTile = function(canvasClickX, canvasClickY, shiftKey) {

    var selectedTile = undefined;

    if ((typeof this.tileId !== 'undefined') && (this.tileId.length > 0)) {

        for (var neighborTileId in this.janeliaTileMap) {
            if (this.janeliaTileMap.hasOwnProperty(neighborTileId)) {
                var neighbor = this.janeliaTileMap[neighborTileId];
                if (neighbor.imagePositioned) {
                    if ((neighbor.x <= canvasClickX) && (neighbor.y <= canvasClickY)) {
                        var maxX = neighbor.x + neighbor.image.naturalWidth;
                        var maxY = neighbor.y + neighbor.image.naturalHeight;
                        if ((maxX >= canvasClickX) && (maxY >= canvasClickY)) {

                            if (! shiftKey) {
                                neighbor.setSelected(true);
                            }

                            selectedTile = neighbor;

                        } else {

                            if (! shiftKey) {
                                neighbor.setSelected(false);
                            }

                        }
                    } else {

                        if (! shiftKey) {
                            neighbor.setSelected(false);
                        }

                    }
                }
            }
        }

    }

    return selectedTile;
};

JaneliaTileWithNeighbors.prototype.getOrderedTileSpecPair = function(tileSpecA, tileSpecB) {

    var orderedPair = { pTileSpec: tileSpecA, qTileSpec: tileSpecB };

    var aGroupId = tileSpecA.layout.sectionId;
    var aId = tileSpecA.tileId;
    var bGroupId = tileSpecB.layout.sectionId;
    var bId = tileSpecB.tileId;

    if ((aGroupId > bGroupId) || ((aGroupId == bGroupId) && (aId > bId))) {
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