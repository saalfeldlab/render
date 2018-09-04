var JaneliaHierarchicalData = function(baseUrl, owner, project, stack, tileZ, maxTileSpecsToRender, parentStackId, tierSelectId, layerSelectId, tileBoundsCanvas, tilePixelsCanvas, tierCanvas) {

    this.baseUrl = baseUrl;
    this.renderBaseUrl = "http://renderer:8080/render-ws/v1"; // TODO: make this a parameter

    this.owner = owner;
    this.project = project;
    this.stack = stack;
    this.tileZ = tileZ;
    this.maxTileSpecsToRender = maxTileSpecsToRender;
    this.parentStackId = parentStackId;
    this.tierSelectId = tierSelectId;
    this.layerSelectId = layerSelectId;
    this.tileBoundsCanvas = tileBoundsCanvas;
    this.tilePixelsCanvas = tilePixelsCanvas;
    this.tierCanvas = tierCanvas;

    this.util = new JaneliaScriptUtilities();

    this.tileBoundsContext = this.tileBoundsCanvas.getContext("2d");
    this.tilePixelsContext = this.tilePixelsCanvas.getContext("2d");
    this.tierContext = this.tierCanvas.getContext("2d");

    this.stackMetaData = undefined;
    this.stackBounds = undefined;
    this.scale = 1.0;
    this.displayTileBounds = true;
    this.qualityThreshold = 0.0;

    this.tierProjectPrefix = this.project + "_" + this.stack + "_tier_";
    this.tierProjects = [];
    this.splitStackList = [];
    this.otherStackList = [];
    this.scaledBoundsList = [];
    this.missingTierMatchLayers = {};
};

JaneliaHierarchicalData.prototype.getOwnerUrl = function() {
    return this.baseUrl + "/owner/" + this.owner;
};

JaneliaHierarchicalData.prototype.getStackUrl = function() {
    return this.getOwnerUrl() + "/project/" + this.project + "/stack/" + this.stack;
};

JaneliaHierarchicalData.prototype.getRenderStackUrl = function() {
    return this.renderBaseUrl + "/owner/" + this.owner + "/project/" + this.project + "/stack/" + this.stack;
};

JaneliaHierarchicalData.prototype.setStackBoundsAndScale = function(stackBounds) {

    this.stackBounds = stackBounds;

    var widthScale = this.tileBoundsContext.canvas.width / (stackBounds.maxX - stackBounds.minX);
    var heightScale = this.tileBoundsContext.canvas.height / (stackBounds.maxY - stackBounds.minY);
    this.scale = widthScale;
    if (this.scale > heightScale) {
        this.scale = heightScale;
    }

};

JaneliaHierarchicalData.prototype.setTierProjects = function(projectList) {

    this.tierProjects = [];

    for (var i = 0; i < projectList.length; i++) {
        if (projectList[i].startsWith(this.tierProjectPrefix)) {
            this.tierProjects.push(projectList[i]);
        }
    }

    if (this.tierProjects.length > 0) {
        this.util.updateSelectOptions(this.tierSelectId, this.tierProjects, this.tierProjects[0]);
        this.selectTierProject(this.tierProjects[0]);
    } else {
        alert('No tier projects found!');
    }
};

JaneliaHierarchicalData.prototype.drawScaledBox = function(context, tileBounds, fillStyle) {

    var offsetMinX = tileBounds.minX - this.stackBounds.minX;
    var offsetMinY = tileBounds.minY - this.stackBounds.minY;
    var offsetMaxX = tileBounds.maxX - this.stackBounds.minX;
    var offsetMaxY = tileBounds.maxY - this.stackBounds.minY;
    var x = this.scale * offsetMinX;
    var y = this.scale * offsetMinY;
    var w = this.scale * (offsetMaxX - offsetMinX);
    var h = this.scale * (offsetMaxY - offsetMinY);

    context.beginPath();
    context.rect(x, y, w, h);
    context.stroke();
    if (fillStyle !== undefined) {
        context.fillStyle = fillStyle;
        context.fill();
    }

    return { x: x, y: y, width: w, height: h}
};

JaneliaHierarchicalData.prototype.clearContextCanvas = function(context) {
    context.clearRect(0, 0, context.canvas.width, context.canvas.height);
};

JaneliaHierarchicalData.prototype.drawTileBounds = function(layerTileBoundsList) {

    this.clearContextCanvas(this.tileBoundsContext);

    this.tileBoundsContext.lineWidth = 1;
    this.tileBoundsContext.strokeStyle = 'black';
    this.drawScaledBox(this.tileBoundsContext, this.stackBounds);

    this.tileBoundsContext.lineWidth = 1;
    this.tileBoundsContext.strokeStyle = 'green';
    for (var i = 0; i < layerTileBoundsList.length; i++) {
        this.drawScaledBox(this.tileBoundsContext, layerTileBoundsList[i]);
    }

};

JaneliaHierarchicalData.prototype.drawLayerPixels = function() {

    this.clearContextCanvas(this.tilePixelsContext);

    var bounds = this.stackMetaData.stats.stackBounds;

    var width = bounds.maxX - bounds.minX;
    var height = bounds.maxY - bounds.minY;
    var box = bounds.minX + ',' + bounds.minY + ',' + width + ',' + height + ',' + this.scale;

    var pixelsUrl = this.getRenderStackUrl() + '/z/' + this.tileZ + '/box/' + box +
                    '/jpeg-image?maxTileSpecsToRender=' + this.maxTileSpecsToRender;

    // TODO: check number of tiles (or maybe full scale pixels) first?

    var pixels = new Image();

    var self = this;
    pixels.onload = function() {
        self.tilePixelsContext.drawImage(pixels, 0, 0);
    };

    pixels.src = pixelsUrl;
};

JaneliaHierarchicalData.prototype.drawHierarchicalData = function() {

    this.clearContextCanvas(this.tierContext);

    this.tierContext.font = "16px Courier";
    var textWidth = this.tierContext.measureText("0.00").width;

    if (this.splitStackList.length > 0) {
        var firstSplitStack = this.splitStackList[0];
        var firstBounds = firstSplitStack.hierarchicalData.fullScaleBounds;
        var offsetMinX = firstBounds.minX - this.stackBounds.minX;
        var offsetMaxX = firstBounds.maxX - this.stackBounds.minX;
        var splitStackBoxWidth = this.scale * (offsetMaxX - offsetMinX) - 10;
        if (splitStackBoxWidth < textWidth) {
            this.tierContext.font = "10px Courier";
            textWidth = this.tierContext.measureText("0.00").width;
            if (splitStackBoxWidth < textWidth) {
                this.tierContext.font = "6px Courier";
                textWidth = this.tierContext.measureText("0.00").width;
            }
        }
    }

    var halfTextWidth = textWidth / 2;
    var halfTextHeight = this.tierContext.measureText("0").width / -2; // hack: this seems to work well enough

    this.scaledBoundsList = [];
    this.tierContext.lineWidth = 1;
    this.tierContext.strokeStyle = 'magenta';

    for (var i = 0; i < this.splitStackList.length; i++) {

        var hierarchicalData = this.splitStackList[i].hierarchicalData;

        var quality = undefined;
        var fillStyle = undefined;
        var badQuality = false;
        if ((hierarchicalData.alignmentQuality !== undefined) && (! isNaN(hierarchicalData.alignmentQuality))) {
            if ((hierarchicalData.totalTierColumnCount > 11) || (hierarchicalData.alignmentQuality < 0.0001)) {
                quality = hierarchicalData.alignmentQuality.toExponential(1);
            } else {
                quality = hierarchicalData.alignmentQuality.toFixed(4);
            }
            if (quality > this.qualityThreshold) {
                badQuality = true;
                //noinspection SpellCheckingInspection
                fillStyle = 'mistyrose';
            }
        }

        if (badQuality && (! this.displayTileBounds)) {
            this.tierContext.globalAlpha = 0.5;
        } else {
            this.tierContext.globalAlpha = 1.0;
        }

        var scaledBox = this.drawScaledBox(this.tierContext, hierarchicalData.fullScaleBounds, fillStyle);
        var centerX = scaledBox.x + (scaledBox.width / 2) - halfTextWidth;
        var centerY = scaledBox.y + (scaledBox.height / 2) - halfTextHeight;
        if (quality !== undefined) {
            if (this.displayTileBounds || badQuality) {
                this.tierContext.fillStyle = 'black';
            } else {
                this.tierContext.fillStyle = 'white';
            }
            this.tierContext.fillText(quality.toString(), centerX, centerY);
        }
        var scaledBounds = { minX: scaledBox.x, minY: scaledBox.y, maxX: (scaledBox.x + scaledBox.width), maxY: (scaledBox.y + scaledBox.height) };
        this.scaledBoundsList.push(scaledBounds);
    }

};

JaneliaHierarchicalData.prototype.updateMaxQuality = function() {

    var maxQuality = 0;
    for (var i = 0; i < this.splitStackList.length; i++) {
        var hierarchicalData = this.splitStackList[i].hierarchicalData;
        if ((hierarchicalData.alignmentQuality !== undefined) && (! isNaN(hierarchicalData.alignmentQuality))) {
            maxQuality = Math.max(maxQuality, hierarchicalData.alignmentQuality);
        }
    }

    if (maxQuality > 0) {
        $('#qualitySlider').prop('max', ((maxQuality + 0.0001) * 10000));
        var qualitySelector = $('#quality');
        if (maxQuality < qualitySelector.val()) {
            qualitySelector.val(maxQuality.toFixed(4));
        }
    }

    //console.log('updateMaxQuality: maxQuality is now ' + maxQuality);
};

JaneliaHierarchicalData.prototype.loadAllData = function() {
    var self = this;
    $.ajax({
               url: self.getStackUrl(),
               cache: false,
               success: function(data) {
                   self.loadRoughTilesStackBounds(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaHierarchicalData.prototype.loadRoughTilesStackBounds = function(roughTilesData) {

    this.stackMetaData = roughTilesData;
    this.setStackBoundsAndScale(roughTilesData.stats.stackBounds);

    var self = this;
    $.ajax({
               url: self.getStackUrl() + "/zValues",
               cache: false,
               success: function(data) {
                   self.loadRoughTilesZValues(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaHierarchicalData.prototype.updateParentTierStackMetaData = function(parentStackData) {
    this.stackMetaData = parentStackData;
    this.setStackBoundsAndScale(parentStackData.stats.stackBounds);
    this.drawLayerPixels();
    this.drawHierarchicalData();
    this.updateMaxQuality();
};

JaneliaHierarchicalData.prototype.loadRoughTilesZValues = function(zValues) {

    if (typeof this.tileZ === 'undefined') {
        var middleIndex = Math.round(zValues.length / 2) - 1;
        this.tileZ = zValues[middleIndex];
    }

    this.util.updateSelectOptions(this.layerSelectId, zValues, this.tileZ);

    this.setLayer(this.tileZ);
};

JaneliaHierarchicalData.prototype.setLayer = function(z) {

    this.tileZ = z;

    var self = this;
    if (this.displayTileBounds) {

        $.ajax({
                   url: self.getStackUrl() + "/z/" + self.tileZ + "/tileBounds",
                   cache: false,
                   success: function (data) {
                       self.drawTileBounds(data);
                       self.loadTierProjects();
                   },
                   error: function (data,
                                    text,
                                    xhr) {
                       console.log(xhr);
                   }
               });

    } else {

        $.ajax({
                   url: self.getStackUrl(),
                   cache: false,
                   success: function(data) {
                       self.updateParentTierStackMetaData(data);
                   },
                   error: function(data, text, xhr) {
                       console.log(xhr);
                   }
               });

    }
};

JaneliaHierarchicalData.prototype.loadTierProjects = function() {

    if (this.tierProjects.length === 0) {

        var self = this;
        $.ajax({
                   url: self.getOwnerUrl() + "/projects",
                   cache: false,
                   success: function (data) {
                       self.setTierProjects(data);
                   },
                   error: function (data,
                                    text,
                                    xhr) {
                       console.log(xhr);
                   }
               });
    } else {
        this.drawHierarchicalData();
        this.updateMaxQuality();
    }
};

JaneliaHierarchicalData.prototype.selectTierProject = function(tierProjectName) {

    var self = this;
    $.ajax({
               url: self.getOwnerUrl() + "/project/" + tierProjectName + "/stacks",
               cache: false,
               success: function(data) {
                   self.loadStacksInTier(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });

    $.ajax({
               url: self.getOwnerUrl() + "/project/" + tierProjectName + "/missingTierMatchLayers",
               cache: false,
               success: function(data) {
                   self.loadMissingTierMatchLayers(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

/**
 * @param {Array} projectStackMetaDataList
 * @param projectStackMetaDataList.hierarchicalData
 * @param projectStackMetaDataList.hierarchicalData.parentTierStackId
 * @param projectStackMetaDataList.hierarchicalData.alignedStackId
 * @param projectStackMetaDataList.hierarchicalData.warpTilesStackId
 * @param projectStackMetaDataList.hierarchicalData.totalTierColumnCount
 * @param projectStackMetaDataList.hierarchicalData.fullScaleBounds
 * @param projectStackMetaDataList.hierarchicalData.matchCollectionId
 * @param projectStackMetaDataList.hierarchicalData.savedMatchPairCount
 * @param projectStackMetaDataList.hierarchicalData.alignmentQuality
 * @param projectStackMetaDataList.hierarchicalData.splitGroupIds
 */
JaneliaHierarchicalData.prototype.loadStacksInTier = function(projectStackMetaDataList) {

    this.splitStackList = [];
    this.otherStackList = [];

    for (var i = 0; i < projectStackMetaDataList.length; i++) {
        var stackData = projectStackMetaDataList[i];
        if (stackData.hierarchicalData !== undefined) {
            this.splitStackList.push(stackData);
        } else {
            this.otherStackList.push(stackData);
        }
    }

    this.drawHierarchicalData();
    this.updateMaxQuality();

    if (this.splitStackList.length > 0) {
        var parentTierStack = this.splitStackList[0].hierarchicalData.parentTierStackId.stack;
        if (this.stack !== parentTierStack) {

            this.stack = parentTierStack;
            $('#' + this.parentStackId).html(this.stack);

            this.setLayer(this.tileZ);
        }
    } else {
        this.clearContextCanvas(this.tileBoundsContext);
        this.clearContextCanvas(this.tilePixelsContext);
    }

};

/**
 * @param {Array} stackWithZValuesList
 * @param stackWithZValuesList.stackId
 * @param stackWithZValuesList.zValues
 */
JaneliaHierarchicalData.prototype.loadMissingTierMatchLayers = function(stackWithZValuesList) {

    this.missingTierMatchLayers = {};

    for (var i = 0; i < stackWithZValuesList.length; i++) {
        var stackName = stackWithZValuesList[i].stackId.stack;
        this.missingTierMatchLayers[stackName] = stackWithZValuesList[i];
    }

};

JaneliaHierarchicalData.prototype.selectSplitStack = function(canvasX, canvasY, splitStackPopupDetails) {

    var selectedSplitStack = undefined;

    for (var i = 0; i < this.scaledBoundsList.length; i++) {
        var scaledBounds = this.scaledBoundsList[i];
        if ((canvasX >= scaledBounds.minX) && (canvasX <= scaledBounds.maxX) &&
            (canvasY >= scaledBounds.minY) && (canvasY <= scaledBounds.maxY)) {
            selectedSplitStack = this.splitStackList[i];
            break;
        }
    }

    if (selectedSplitStack !== undefined) {

        var hd = selectedSplitStack.hierarchicalData;
        var splitStackId = selectedSplitStack.stackId;

        var viewBaseUrl = 'http://renderer-dev:8080/render-ws/view';
        var hosts = "dynamicRenderHost=renderer%3A8080&catmaidHost=renderer-catmaid%3A8000&renderDataHost=tem-services.int.janelia.org%3A8080";
        var renderProjectContext = '&renderStackOwner=' + splitStackId.owner + '&renderStackProject=' + splitStackId.project;
        var alignedStackContext = renderProjectContext + '&renderStack=' + hd.alignedStackId.stack;

        var tierProjectUrl = viewBaseUrl + '/stacks.html?' + hosts + renderProjectContext;

        var matchContext = '&matchOwner=' + this.owner + '&matchCollection=' + hd.matchCollectionId.name;
        var pmeUrl = viewBaseUrl + '/point-match-explorer.html?' + hosts + alignedStackContext + matchContext;

        var catmaidBaseUrl = 'http://renderer-catmaid:8000';

        var warpCatmaidUrl = this.util.getCenteredCatmaidUrl(catmaidBaseUrl,
                                                             hd.warpTilesStackId,
                                                             selectedSplitStack.currentVersion,
                                                             hd.fullScaleBounds,
                                                             this.tileZ,
                                                             2);

        var splitCatmaidUrl = this.util.getCenteredCatmaidUrl(catmaidBaseUrl,
                                                              splitStackId,
                                                              selectedSplitStack.currentVersion,
                                                              selectedSplitStack.stats.stackBounds,
                                                              this.tileZ,
                                                              0);

        var alignedCatmaidUrl = this.util.getCenteredCatmaidUrl(catmaidBaseUrl,
                                                                hd.alignedStackId,
                                                                selectedSplitStack.currentVersion,
                                                                selectedSplitStack.stats.stackBounds,
                                                                this.tileZ,
                                                                0);

        var matchPairRow;
        var zPlusOne = parseInt(this.tileZ) + 1;
        if ((hd.savedMatchPairCount !== undefined) && (hd.savedMatchPairCount > 0)) {
            var plusOnePairUrl = this.getPlusOnePairUrl(this.tileZ, splitStackId, hd, renderProjectContext, matchContext);
            matchPairRow = this.getPopupLinkRow('Match Pair:', plusOnePairUrl, 'matches between z ' + this.tileZ + ' and ' + zPlusOne);
        } else {
            matchPairRow = this.getPopupRow('Match Pair:', 'n/a')
        }

        var self = this;
        var missingMatchLayers = '';
        if (this.missingTierMatchLayers[splitStackId.stack] !== undefined) {
            missingMatchLayers = this.missingTierMatchLayers[splitStackId.stack].zValues.map(function(z) {
                var plusOnePairUrl = self.getPlusOnePairUrl(z, splitStackId, hd, renderProjectContext, matchContext);
                return self.getPopupLink(plusOnePairUrl, '' + z);
            }).join(', ');
        }

        var consensusIdsUrl = this.getOwnerUrl() + '/matchCollection/' + hd.matchCollectionId.name + '/multiConsensusGroupIds';
        var consensusDataSpanId = 'consensusDataSpan';
        var consensusRow = '<a target="_blank" href="' + consensusIdsUrl + '"><span id="' + consensusDataSpanId + '">...</span></a>';

        var splitConsensusGroupIds;
        if ((hd.splitGroupIds !== undefined) && (hd.splitGroupIds.length > 0)) {
            splitConsensusGroupIds = hd.splitGroupIds.toString();
        } else {
            splitConsensusGroupIds = "";
        }

        var warpDebugUrl = self.getOwnerUrl() + "/project/" + splitStackId.project + "/z/" + this.tileZ + "/affineWarpFieldTransformDebug?consensusBuildMethod=SIMPLE";

        var splitStackUrl = self.getOwnerUrl() + "/project/" + splitStackId.project + '/stack/' + splitStackId.stack + '/tile/';
        var tileIdSuffix = this.getSplitTileIdSuffix(hd);
        var pTileId = 'z_' + this.tileZ + tileIdSuffix;
        var qTileId = 'z_' + zPlusOne + tileIdSuffix;
        var renderSuffix = '/render-parameters?excludeMask=true&normalizeForMatching=true&filter=true&fillWithNoise=true';
        var pRenderParametersUrl = splitStackUrl + pTileId + renderSuffix;
        var qRenderParametersUrl = splitStackUrl + qTileId + renderSuffix;
        var matchTrialUrl = viewBaseUrl + '/match-trial.html?matchTrialId=TBD' +
                            '&pRenderParametersUrl=' + encodeURIComponent(pRenderParametersUrl) +
                            '&qRenderParametersUrl=' + encodeURIComponent(qRenderParametersUrl);

        var html = '<table>' +
                   this.getPopupLinkRow('Warp Stack:', warpCatmaidUrl, hd.warpTilesStackId.stack + ' (CATMAID)') +
                   this.getPopupLinkRow('Tier Project:', tierProjectUrl, hd.alignedStackId.project) +
                   this.getPopupLinkRow('Split Stack:', splitCatmaidUrl, splitStackId.stack + ' (CATMAID)') +
                   this.getPopupLinkRow('Aligned Stack:', alignedCatmaidUrl, hd.alignedStackId.stack + ' (CATMAID)') +
                   this.getPopupLinkRow('Match Collection:', pmeUrl, hd.matchCollectionId.name) +
                   this.getPopupRow('Match Pair Count:', hd.savedMatchPairCount) +
                   matchPairRow +
                   this.getPopupRow('Missing Match Layers:', missingMatchLayers) +
                   this.getPopupRow('Multi Consensus Group IDs:', consensusRow) +
                   this.getPopupRow('Split Consensus Group IDs:', splitConsensusGroupIds) +
                   this.getPopupRow('Alignment Quality:', hd.alignmentQuality) +
                   this.getPopupLinkRow('Warp Field Debug:', warpDebugUrl, this.tileZ) +
                   this.getPopupLinkRow('Match Trial:', matchTrialUrl, pTileId) +
                   '</table>';

        splitStackPopupDetails.html(html);

        $.ajax({
                   url: consensusIdsUrl,
                   cache: false,
                   success: function(data) {
                       $('#' + consensusDataSpanId).text(data.toString());
                   },
                   error: function(data, text, xhr) {
                       console.log(xhr);
                   }
               });
    }

    return selectedSplitStack;
};

JaneliaHierarchicalData.prototype.getPopupRow = function(header, value) {
    return '<tr><td>' + header + '</td><td>' + value + '</td></tr>';
};

JaneliaHierarchicalData.prototype.getPopupLink = function(url, linkText) {
    return '<a target="_blank" href="' + url + '">' + linkText + '</a>';
};

JaneliaHierarchicalData.prototype.getPopupLinkRow = function(header, url, linkText) {
    return this.getPopupRow(header, this.getPopupLink(url, linkText));
};

JaneliaHierarchicalData.prototype.getSplitTileIdSuffix = function(hierarchicalData) {
    var bounds = hierarchicalData.fullScaleBounds;
    return '.0_box_' + bounds.minX + '_' + bounds.minY + '_' +
                       (bounds.maxX - bounds.minX) + '_' + (bounds.maxY - bounds.minY) + '_' +
                       hierarchicalData.scale.toFixed(6);
};

JaneliaHierarchicalData.prototype.getPlusOnePairUrl = function(z, splitStackId, hierarchicalData, renderProjectContext, matchContext) {
    var zPlusOne = parseInt(z) + 1;
    var pairBaseUrl = 'http://renderer:8080/render-ws/view/tile-pair.html?';
    var tileIdSuffix = this.getSplitTileIdSuffix(hierarchicalData);
    return pairBaseUrl + 'pId=z_' + z + tileIdSuffix + '&qId=z_' + zPlusOne + tileIdSuffix + renderProjectContext +
           '&renderStack=' + splitStackId.stack + '&renderScale=1.0' + matchContext;
};

JaneliaHierarchicalData.prototype.setDisplayTileBounds = function(displayTileBounds) {

    if (this.displayTileBounds !== displayTileBounds) {
        this.displayTileBounds = displayTileBounds;
        if (displayTileBounds) {
            this.clearContextCanvas(this.tilePixelsContext);
        } else {
            this.clearContextCanvas(this.tileBoundsContext);
        }
        this.setLayer(this.tileZ); // will redraw tile data
    }
};

JaneliaHierarchicalData.prototype.setQualityThreshold = function(qualityThreshold) {
    this.qualityThreshold = qualityThreshold;
    this.drawHierarchicalData();
};