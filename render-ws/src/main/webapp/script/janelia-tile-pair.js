/**
 * @typedef {Object} CanvasMatches
 * @property {String} pGroupId
 * @property {String} pId
 * @property {String} qGroupId
 * @property {String} qId
 * @property {Array} matches
 */

var JaneliaTile = function(label, tileId, tilePair, onloadCallback) {

    this.label = label;
    this.tileId = tileId;
    this.stackUrl = tilePair.stackUrl;
    this.scale = tilePair.scale;

    this.isLoaded = false;
    this.specUrl = this.stackUrl + "/tile/" + tileId;
    this.imageUrl = this.specUrl + "/jpeg-image?excludeMask=true&filter=true&scale=" + this.scale;

    this.image = new Image();

    this.image.onload = onloadCallback;
    this.image.src = this.imageUrl;
};

var JaneliaTilePair = function(baseUrl, owner, project, stack, matchOwner, matchCollection, scale, groupId, tileId, otherGroupId, otherTileId) {

    this.baseUrl = baseUrl;
    this.owner = owner;
    this.project = project;
    this.stack = stack;
    this.matchOwner = matchOwner;
    this.matchCollection = matchCollection;
    this.scale = scale;

    this.pGroupId = groupId;
    this.pTileId = tileId;
    this.qGroupId = otherGroupId;
    this.qTileId = otherTileId;

    if ((groupId.localeCompare(otherGroupId) > 0) ||
        ((groupId.localeCompare(otherGroupId) == 0) && (tileId.localeCompare(otherTileId) > 0))) {
        this.pGroupId = otherGroupId;
        this.pTileId = otherTileId;
        this.qGroupId = groupId;
        this.qTileId = tileId;
    }

    this.stackUrl = this.baseUrl + "/owner/" + this.owner + "/project/" + this.project + "/stack/" + this.stack;
    this.matchCollectionUrl = this.baseUrl + "/owner/" + this.matchOwner + "/matchCollection/" + this.matchCollection;
    this.matchUrl = this.matchCollectionUrl + "/group/" + this.pGroupId + "/id/" + this.pTileId +
                    "/matchesWith/" + this.qGroupId + "/id/" + this.qTileId;

    var sep = '&nbsp;&nbsp;&nbsp;&nbsp;';

    $("#renderStack").html(this.owner + sep + this.project + sep + this.stack);

    var stackLinkHtml = '(<a href="' + this.stackUrl + '" target="_blank">stack metadata</a>)';
    $("#renderStackLink").html(stackLinkHtml);

    $("#matchCollection").html(this.matchOwner + sep + this.matchCollection);

    var pairMatchesLinkHtml = '(<a href="' + this.matchUrl + '" target="_blank">pair matches</a>)';
    $("#pairMatchesLink").html(pairMatchesLinkHtml);

    var self = this;

    var qOnloadCallback = function() {
        self.drawTile(self.qTile);
        self.positionCanvas(self.qTile);
        self.qTile.isLoaded = true;
        self.positionCanvas(self.qTile); // hack: not sure why this needs to be called a second time for the tile div offset to be correct
        self.drawMatch(0);
    };

    var pOnloadCallback = function() {
        self.drawTile(self.pTile);
        self.positionCanvas(self.pTile);
        self.pTile.isLoaded = true;
        self.qTile = new JaneliaTile("q", self.qTileId, self, qOnloadCallback);
    };

    this.pTile = new JaneliaTile("p", this.pTileId, self, pOnloadCallback);

    this.matches = {
        "p": [ [ ], [ ] ],
        "q": [ [ ], [ ] ],
        "w": [ ]
    };
    this.matchCount = 0;

    $.ajax({
               url: self.matchUrl,
               cache: false,
               success: function(data) {
                   self.setMatches(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });

    this.matchIndex = 0;
};

JaneliaTilePair.prototype.setMatches = function(data) {
    if (data.length > 0) {
        this.matches = data[0].matches;
        this.matchCount = this.matches.w.length;
        this.drawMatch(0);
    } else {
        // TODO: clear previous?
    }
};

JaneliaTilePair.prototype.drawTile = function(janeliaTile) {

    var scaledWidth = janeliaTile.image.naturalWidth;
    var scaledHeight = janeliaTile.image.naturalHeight;

    var fullScaleWidth = Math.round(scaledWidth * (1.0 / janeliaTile.scale));
    var fullScaleHeight = Math.round(scaledHeight * (1.0 / janeliaTile.scale));

    var tileImageElement = $("#" + janeliaTile.label + "TileImage");
    tileImageElement.attr("src", janeliaTile.imageUrl);

    var tileIdHtml = '<a href=\"' + janeliaTile.specUrl + '" target="_blank"">' + janeliaTile.tileId + '</a>';
    $("#" + janeliaTile.label + "TileId").html(tileIdHtml);
    $("#" + janeliaTile.label + "FullScaleSize").html(fullScaleWidth + " x " + fullScaleHeight);
};

JaneliaTilePair.prototype.positionCanvas = function(janeliaTile) {

    var scaledWidth = janeliaTile.image.naturalWidth;
    var scaledHeight = janeliaTile.image.naturalHeight;

    var tileImageElement = $("#" + janeliaTile.label + "TileImage");

    var canvasElementId = janeliaTile.label + "Canvas";
    var canvas = document.getElementById(canvasElementId);

    // must set canvas element width and height explicitly (independent of css)
    // see http://stackoverflow.com/questions/2588181/canvas-is-stretched-when-using-css-but-normal-with-width-height-properties
    canvas.width = scaledWidth;
    canvas.height = scaledHeight;

    var offset = tileImageElement.offset();

    var canvasStyle = {
        position: "absolute", left: offset.left + "px", top: offset.top + "px",
        width: scaledWidth + "px", height: scaledHeight + "px",
        border: "solid 1px green"
    };

    $("#" + canvasElementId).css(canvasStyle);
};

JaneliaTilePair.prototype.drawPoint = function(matchLabel, x, y, w) {

    var scaledX = x * this.scale;
    var scaledY = y * this.scale;
    var scaledRadius = 50 * this.scale;

    var canvasElementId = matchLabel + "Canvas";

    var canvas = document.getElementById(canvasElementId);
    var ctx = canvas.getContext("2d");

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    ctx.strokeStyle = '#00ff00';
    ctx.lineWidth = 2;

    ctx.beginPath();
    ctx.arc(scaledX, scaledY, scaledRadius, 0, 2 * Math.PI, false);
    ctx.stroke();

    var pointHtml = "match " + (this.matchIndex + 1) + " of " + this.matchCount +
                    ": x = " + this.getCoordinateHtml(x, scaledX, canvas.width) +
                    ", y = " + this.getCoordinateHtml(y, scaledY, canvas.height) +
                    ", w = " + w;
    $("#" + matchLabel + "Point").html(pointHtml);
};

JaneliaTilePair.prototype.getCoordinateHtml = function(value, scaledValue, maxScaledValue) {
    var spanClass = "";
    if (scaledValue > maxScaledValue) {
        spanClass = ' class="error"';
    }
    return '<span' + spanClass + '>' + Math.floor(value) + '</span>'
};

JaneliaTilePair.prototype.drawMatch = function(matchIndexDelta) {

    if (this.pTile.isLoaded && this.qTile.isLoaded && (this.matchCount > 0)) {

        this.matchIndex = (this.matchIndex + matchIndexDelta) % this.matchCount;
        if (this.matchIndex < 0) {
            this.matchIndex = this.matchCount - 1;
        }

        var px = this.matches.p[0][this.matchIndex];
        var py = this.matches.p[1][this.matchIndex];
        var qx = this.matches.q[0][this.matchIndex];
        var qy = this.matches.q[1][this.matchIndex];
        var w = this.matches.w[this.matchIndex];

        this.drawPoint("p", px, py, w);
        this.drawPoint("q", qx, qy, w);

    }
};

JaneliaTilePair.prototype.drawNextMatch = function() {
    this.drawMatch(1);
};

JaneliaTilePair.prototype.drawPreviousMatch = function() {
    this.drawMatch(-1);
};
