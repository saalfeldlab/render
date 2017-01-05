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

    this.renderUrl = this.specUrl + "/render-parameters" + renderQueryParameters;
    this.imageUrl = this.specUrl + "/jpeg-image" + renderQueryParameters + "&scale=" + this.scale;

    this.image = new Image();
    this.imagePositioned = false;
    this.x = -1;
    this.y = -1;

    var self = this;
    this.image.onload = function() {
        var scaledWidth = self.image.naturalWidth;
        var scaledHeight = self.image.naturalHeight;

        var context = self.canvas.getContext("2d");
        var canvasOffset = 4;
        var tileMargin = 4;
        self.x = (self.column * (scaledWidth + tileMargin)) + canvasOffset;
        self.y = (self.row * (scaledHeight + tileMargin)) + canvasOffset;
        context.drawImage(self.image, self.x, self.y);

        self.imagePositioned = true;

        if (typeof self.matchCollectionUrl !== 'undefined') {
            self.loadAllMatches()
        }
    };
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

    var self = this;
    for (var tileId in self.janeliaTileMap) {
        if (self.janeliaTileMap.hasOwnProperty(tileId)) {

            var janeliaTile2 = self.janeliaTileMap[tileId];
            var tileSpec = janeliaTile2.tileSpec;

            if ((self.tileSpec.layout.sectionId <= tileSpec.layout.sectionId) &&
                (self.tileSpec.tileId < tileId)){

                var matchUrl = self.matchCollectionUrl + "/group/" + self.tileSpec.layout.sectionId +
                               "/id/" + self.tileSpec.tileId + "/matchesWith/" +
                               tileSpec.layout.sectionId + "/id/" + tileId;
                $.ajax({
                           url: matchUrl,
                           cache: false,
                           success: function(data) {
                               self.addMatchPair(data);
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

        var pMatches = canvasMatches.matches.p;
        var qMatches = canvasMatches.matches.q;

        for (var matchIndex = 0; matchIndex < canvasMatches.matches.w.length; matchIndex++) {

            var px = (pMatches[0][matchIndex] * this.scale) + pTile.x;
            var py = (pMatches[1][matchIndex] * this.scale) + pTile.y;
            var qx = (qMatches[0][matchIndex] * this.scale) + qTile.x;
            var qy = (qMatches[1][matchIndex] * this.scale) + qTile.y;

            var ctx = this.canvas.getContext("2d");
            ctx.strokeStyle = colors[matchIndex % colors.length];
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(px, py);
            ctx.lineTo(qx, qy);
            ctx.stroke();
        }

    } else {

        var self = this;
        setTimeout(function(){
            self.drawMatches(canvasMatches);
        }, 500);

    }
};

var JaneliaTileWithNeighbors = function(baseUrl, owner, project, stack, matchOwner, matchCollection, tileId, renderQueryParameters, scale, canvas) {

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

    if ((typeof tileId !== 'undefined') && (tileId.length > 0)) {
        this.setTileId(tileId);
    }

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

JaneliaTileWithNeighbors.prototype.viewTilePair = function(tileA, tileB, renderScale) {

    var pGroupId = tileA.tileSpec.layout.sectionId;
    var pId = tileA.tileSpec.tileId;

    var qGroupId = tileB.tileSpec.layout.sectionId;
    var qId = tileB.tileSpec.tileId;

    if ((pGroupId > qGroupId) || ((pGroupId == qGroupId) && (pId > qId))) {
        var swapGroupId = pGroupId;
        var swapId = pId;
        pGroupId = qGroupId;
        pId = qId;
        qGroupId = swapGroupId;
        qId = swapId;
    }

    var parameters = {
        'renderStackOwner': this.owner, 'renderStackProject': this.project, 'renderStack': this.stack,
        'renderScale': renderScale,
        'matchOwner': this.matchOwner, 'matchCollection': this.matchCollection,
        'pGroupId': pGroupId, 'pId': pId,
        'qGroupId': qGroupId, 'qId': qId
    };

    var tilePairUrl = "tile-pair.html?" + $.param(parameters);

    var win = window.open(tilePairUrl);
    if (win) {
        win.focus();
    } else {
        alert('Please allow popups for this website');
    }

};