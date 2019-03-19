/**
 * @typedef {Object} CanvasMatches
 * @property {String} pGroupId
 * @property {String} pId
 * @property {String} qGroupId
 * @property {String} qId
 * @property {Array} matches
 */

var JaneliaMatchTrialImage = function(renderParametersUrl, row, column, viewScale, cellMargin) {

    this.imageUrl = renderParametersUrl.replace('render-parameters', 'jpeg-image');
    this.row = row;
    this.column = column;
    this.viewScale = viewScale; // TODO: use viewScale when loading image
    this.cellMargin = cellMargin;

    var renderScale = parseFloat(new URL(renderParametersUrl).searchParams.get('scale'));
    this.renderScale = isNaN(renderScale) ? 1.0 : renderScale;

    this.image = new Image();
    this.x = -1;
    this.y = -1;
    this.imagePositioned = false;

    var self = this;
    this.image.onload = function() {
        self.positionImage();
    };

    this.matches = [];
    this.matchIndex = -1;
};

JaneliaMatchTrialImage.prototype.loadImage = function() {
    this.imagePositioned = false;
    this.image.src = this.imageUrl;
};

JaneliaMatchTrialImage.prototype.positionImage = function() {
    var scaledWidth = this.image.naturalWidth;
    var scaledHeight = this.image.naturalHeight;
    this.x = (this.column * (scaledWidth + this.cellMargin)) + this.cellMargin;
    this.y = (this.row * (scaledHeight + this.cellMargin)) + this.cellMargin;
    this.imagePositioned = true;
};

JaneliaMatchTrialImage.prototype.drawLoadedImage = function(canvas) {
    var context = canvas.getContext("2d");
    context.drawImage(this.image, this.x, this.y);
};

JaneliaMatchTrialImage.prototype.getCanvasWidth = function() {
    return (this.image.naturalWidth); // TODO: use viewScale when calculating canvas size
};

JaneliaMatchTrialImage.prototype.getCanvasHeight = function() {
    return (this.image.naturalHeight); // TODO: use viewScale when calculating canvas size
};

var JaneliaMatchTrial = function(baseUrl, owner, canvas, viewScale) {

    this.baseUrl = baseUrl;
    this.owner = owner;
    this.canvas = canvas;
    this.viewScale = viewScale;
    this.cellMargin = 4;

    this.matchTrialUrl = this.baseUrl + "/owner/" + this.owner + "/matchTrial";

    this.pImage = undefined;
    this.qImage = undefined;
    this.trialResults = undefined;
    this.matchCount = undefined;
    this.matchIndex = 0;

    this.util = new JaneliaScriptUtilities();
};

JaneliaMatchTrial.prototype.clearCanvas = function() {
    var context = this.canvas.getContext("2d");
    context.clearRect(0, 0, this.canvas.width, this.canvas.height);
};

JaneliaMatchTrial.prototype.loadTrial = function(trialId) {

    this.clearCanvas();
    this.trialResults = undefined;
    this.matchCount = undefined;
    this.matchIndex = 0;

    var self = this;
    $.ajax({
               url: self.matchTrialUrl + '/' + trialId,
               cache: false,
               success: function(data) {
                   self.loadTrialResults(data);
               },
               error: function(data, text, xhr) {
                   console.log(xhr);
               }
           });
};

JaneliaMatchTrial.prototype.openNewTrialWindow = function() {

    var self = this;
    var stop = window.location.href.indexOf('?');
    var url;
    if (stop === -1) {
        url = window.location.href;
    } else {
        url = window.location.href.substring(0, stop);
    }

    var newTrialWindow = window.open(url + '?matchTrialId=TBD', '_blank');
    newTrialWindow.focus();

    newTrialWindow.addEventListener('load', function() {
        self.initNewTrialWindow(newTrialWindow, 0);
    }, true);

};

JaneliaMatchTrial.prototype.initNewTrialWindow = function(newTrialWindow, retryCount) {

    var self = this;
    if (typeof self.trialResults !== 'undefined') {

        if (typeof newTrialWindow.matchTrial !== 'undefined') {

            newTrialWindow.matchTrial.initNewTrialForm(self.trialResults.parameters);

        } else if (retryCount < 3) {
            setTimeout(function () {
                self.initNewTrialWindow(newTrialWindow, (retryCount + 1));
            }, 500);
        } else {
            console.log('initNewTrialWindow: stopping init attempts after ' + retryCount + ' retires');
        }
    }

};

JaneliaMatchTrial.prototype.initNewTrialForm = function(parameters) {

    var fParams = parameters.featureAndMatchParameters.siftFeatureParameters;
    $('#fdSize').val(fParams.fdSize);
    $('#minScale').val(fParams.minScale);
    $('#maxScale').val(fParams.maxScale);
    $('#steps').val(fParams.steps);

    var mParams = parameters.featureAndMatchParameters.matchDerivationParameters;
    $('#matchModelType').val(mParams.matchModelType);
    $('#matchRod').val(mParams.matchRod);
    $('#matchIterations').val(mParams.matchIterations);
    $('#matchMaxEpsilon').val(mParams.matchMaxEpsilon);
    $('#matchMinInlierRatio').val(mParams.matchMinInlierRatio);
    $('#matchMinNumInliers').val(mParams.matchMinNumInliers);
    $('#matchMaxTrust').val(mParams.matchMaxTrust);
    $('#matchFilter').val(mParams.matchFilter);

    $('#fillWithNoise').val(parameters.fillWithNoise);

    var pClipPosition = parameters.featureAndMatchParameters.pClipPosition;
    if ((typeof pClipPosition !== undefined) && (pClipPosition !== 'NO CLIP')) {
        $('#pClipPosition').val(pClipPosition);
        $('#clipPixels').val(parameters.featureAndMatchParameters.clipPixels);
    }

    $('#pRenderParametersUrl').val(parameters.pRenderParametersUrl);
    $('#qRenderParametersUrl').val(parameters.qRenderParametersUrl);
};

JaneliaMatchTrial.prototype.runTrial = function(runTrialButtonSelector, trialRunningSelector, errorMessageSelector) {

    var featureAndMatchParameters = {
        "siftFeatureParameters" : {
            "fdSize" : parseInt($('#fdSize').val()),
            "minScale" : parseFloat($('#minScale').val()),
            "maxScale" : parseFloat($('#maxScale').val()),
            "steps" : parseInt($('#steps').val())
        },
        "matchDerivationParameters" : {
            "matchRod" : parseFloat($('#matchRod').val()),
            "matchModelType" : this.util.getSelectedValue('matchModelType'),
            "matchIterations" : parseInt($('#matchIterations').val()),
            "matchMaxEpsilon" : parseFloat($('#matchMaxEpsilon').val()),
            "matchMinInlierRatio" : parseFloat($('#matchMinInlierRatio').val()),
            "matchMinNumInliers" : parseInt($('#matchMinNumInliers').val()),
            "matchMaxTrust" : parseFloat($('#matchMaxTrust').val()),
            "matchFilter" : this.util.getSelectedValue('matchFilter')
        }
    };

    var pClipPosition = $('#pClipPosition').val();
    if ((typeof pClipPosition !== undefined) && (pClipPosition !== 'NO CLIP')) {
        featureAndMatchParameters['pClipPosition'] = pClipPosition;
        featureAndMatchParameters['clipPixels'] = parseInt($('#clipPixels').val());
    }

    var requestData = {
        featureAndMatchParameters: featureAndMatchParameters,
        pRenderParametersUrl: $('#pRenderParametersUrl').val(),
        qRenderParametersUrl: $('#qRenderParametersUrl').val()
    };

    var parametersUrlRegex = /.*render-parameters.*/;
    if (requestData.pRenderParametersUrl.match(parametersUrlRegex) &&
        requestData.pRenderParametersUrl.match(parametersUrlRegex)) {

        errorMessageSelector.text('');
        runTrialButtonSelector.prop("disabled", true);
        trialRunningSelector.show();

        var self = this;
        $.ajax({
                   type: "POST",
                   headers: {
                       'Accept': 'application/json',
                       'Content-Type': 'application/json'
                   },
                   url: self.matchTrialUrl,
                   data: JSON.stringify(requestData),
                   cache: false,
                   success: function(data) {
                       var stop = window.location.href.indexOf('?');
                       window.location = window.location.href.substring(0, stop) +
                                         '?matchTrialId=' + data.id + '&viewScale=' + self.viewScale;
                   },
                   error: function(data, text, xhr) {
                       console.log(xhr);
                       errorMessageSelector.text(data.statusText + ': ' + data.responseText);
                       runTrialButtonSelector.prop("disabled", false);
                       trialRunningSelector.hide();
                   }
               });

    } else {

        errorMessageSelector.text('render parameters URLs must contain "render-parameters"');

    }

};

JaneliaMatchTrial.prototype.getRenderParametersLink = function(parametersUrl) {
    var splitUrl = parametersUrl.split('/');
    var urlName;
    if (splitUrl.length > 2) {
        urlName = splitUrl[splitUrl.length - 2];
    } else {
        urlName = parametersUrl;
    }
    return '<a target="_blank" href="' + parametersUrl + '">' + urlName + '</a>';
};

/**
 * @param data.parameters
 * @param {Array} data.matches
 * @param data.stats.pFeatureCount
 * @param data.stats.pFeatureDerivationMilliseconds
 * @param data.stats.qFeatureCount
 * @param data.stats.qFeatureDerivationMilliseconds
 * @param {Array} data.stats.consensusSetSizes
 * @param {Array} data.stats.consensusSetDeltaXStandardDeviations
 * @param {Array} data.stats.consensusSetDeltaYStandardDeviations
 * @param data.stats.matchDerivationMilliseconds
 */
JaneliaMatchTrial.prototype.loadTrialResults = function(data) {

    this.trialResults = data;

    //console.log(data);

    this.pImage = new JaneliaMatchTrialImage(data.parameters.pRenderParametersUrl, 0, 0, this.viewScale, this.cellMargin);
    this.pImage.loadImage();

    this.qImage = new JaneliaMatchTrialImage(data.parameters.qRenderParametersUrl, 0, 1, this.viewScale, this.cellMargin);
    this.qImage.loadImage();

    var fParams = data.parameters.featureAndMatchParameters.siftFeatureParameters;
    $('#trialFdSize').html(fParams.fdSize);
    $('#trialMinScale').html(fParams.minScale);
    $('#trialMaxScale').html(fParams.maxScale);
    $('#trialSteps').html(fParams.steps);

    var pClipPosition = data.parameters.featureAndMatchParameters.pClipPosition;
    if (typeof pClipPosition === 'undefined') {
        pClipPosition = 'n/a';
    }

    var clipPixels = data.parameters.featureAndMatchParameters.clipPixels;
    if (typeof clipPixels === 'undefined') {
        clipPixels = 'n/a';
    }
    $('#trialPClipPosition').html(pClipPosition);
    $('#trialClipPixels').html(clipPixels);

    var mParams = data.parameters.featureAndMatchParameters.matchDerivationParameters;
    $('#trialMatchModelType').html(mParams.matchModelType);
    $('#trialMatchRod').html(mParams.matchRod);
    $('#trialMatchIterations').html(mParams.matchIterations);
    $('#trialMatchMaxEpsilon').html(mParams.matchMaxEpsilon);
    $('#trialMatchMinInlierRatio').html(mParams.matchMinInlierRatio);
    $('#trialMatchMinNumInliers').html(mParams.matchMinNumInliers);
    $('#trialMatchMaxTrust').html(mParams.matchMaxTrust);
    $('#trialMatchFilter').html(mParams.matchFilter);

    if (typeof mParams.matchMaxNumInliers !== 'undefined') {
        $('#trialMatchMaxNumInliers').html(mParams.matchMaxNumInliers);
    }

    $('#trialFillWithNoise').html(data.parameters.fillWithNoise);

    $('#trialpRenderParametersUrl').html(this.getRenderParametersLink(data.parameters.pRenderParametersUrl));
    $('#trialqRenderParametersUrl').html(this.getRenderParametersLink(data.parameters.qRenderParametersUrl));

    var consensusSetMatches = this.trialResults.matches;
    this.matchCount = 0;
    for (var consensusSetIndex = 0; consensusSetIndex < consensusSetMatches.length; consensusSetIndex++) {
        var matches = consensusSetMatches[consensusSetIndex];
        this.matchCount += matches.w.length;
    }

    var stats = this.trialResults.stats;

    $('#pFeatureStats').html(stats.pFeatureCount + ' features were derived in ' +
                             stats.pFeatureDerivationMilliseconds + ' ms');
    $('#qFeatureStats').html(stats.qFeatureCount + ' features were derived in ' +
                             stats.qFeatureDerivationMilliseconds + ' ms');

    var csSizes = stats.consensusSetSizes;
    var csText;
    if (csSizes.length === 1) {
        if (this.matchCount === 0) {
            csText = 'NO matches were '
        } else {
            csText = '1 consensus set with ' + this.matchCount + ' matches was';
        }
    } else {
        csText = csSizes.length + ' consensus sets with [' + csSizes.toString() + '] matches were';
    }

    var html = csText + ' derived in ' + stats.matchDerivationMilliseconds + ' ms' +
               this.getStandardDeviationHtml('X', stats.consensusSetDeltaXStandardDeviations) +
               this.getStandardDeviationHtml('Y', stats.consensusSetDeltaYStandardDeviations);

    $('#matchStats').html(html);

    this.drawAllMatches();
};

JaneliaMatchTrial.prototype.getStandardDeviationHtml = function(xOrY,
                                                                standardDeviationValues) {
    var html = '';
    if (Array.isArray(standardDeviationValues)) {
        if (standardDeviationValues.length > 1) {
            html = '<br/>Set Delta ' + xOrY + ' Standard Deviations: [ ' + this.getDeltaHtml(standardDeviationValues[0]);
            for (var i = 1; i < standardDeviationValues.length; i++) {
                html = html + ', ' + this.getDeltaHtml(standardDeviationValues[i]);
            }
            html += ' ]';
        } else if (standardDeviationValues.length === 1) {
            html = '<br/>Delta ' + xOrY + ' Standard Deviation: ' + this.getDeltaHtml(standardDeviationValues[0]);
        }
    }
    return html;
};

JaneliaMatchTrial.prototype.getDeltaHtml = function(value) {
    var html = value.toFixed(1);
    if (value > 8) {
        html = '<span style="color:red;">' + html + '</span>';
    }
    return html;
};

JaneliaMatchTrial.prototype.drawAllMatches = function() {
    this.drawSelectedMatches(undefined);
};

JaneliaMatchTrial.prototype.drawSelectedMatches = function(matchIndexDelta) {

    if (this.pImage.imagePositioned && this.qImage.imagePositioned) {

        var context = this.canvas.getContext("2d");

        context.canvas.width = this.qImage.x + this.qImage.getCanvasWidth() + this.cellMargin;
        context.canvas.height =
                Math.max(this.pImage.getCanvasHeight(), this.qImage.getCanvasHeight()) + this.cellMargin;

        this.clearCanvas();
        this.pImage.drawLoadedImage(this.canvas);
        this.qImage.drawLoadedImage(this.canvas);

        if (this.matchCount > 0) {

            var consensusSetMatches = this.trialResults.matches;

            context.lineWidth = 1;

            var matchInfoSelector = $('#matchInfo');
            var consensusSetIndex = 0;
            var matches;
            var i;

            if (typeof matchIndexDelta !== 'undefined') {

                this.matchIndex = (this.matchIndex + matchIndexDelta) % this.matchCount;
                if (this.matchIndex < 0) {
                    this.matchIndex = this.matchCount - 1;
                }

                var lastI = 0;
                for (; consensusSetIndex < consensusSetMatches.length; consensusSetIndex++) {
                    matches = consensusSetMatches[consensusSetIndex];
                    i = this.matchIndex - lastI;
                    if (i > -1) {
                        context.strokeStyle = '#00ff00';
                        this.drawMatch(matches, i, this.pImage, this.qImage, context);
                        break;
                    }
                    lastI = lastI + matches.w.length;
                }

                matchInfoSelector.html('match ' + (this.matchIndex + 1) + ' of ' + this.matchCount);

            } else {

                this.matchIndex = 0;

                var colors = ['#00ff00', '#f48342', '#42eef4', '#f442f1'];

                for (; consensusSetIndex < consensusSetMatches.length; consensusSetIndex++) {
                    matches = consensusSetMatches[consensusSetIndex];
                    for (i = 0; i < matches.w.length; i++) {
                        context.strokeStyle = colors[i % colors.length];
                        this.drawMatch(matches, i, this.pImage, this.qImage, context);
                    }
                }

                matchInfoSelector.html(this.matchCount + ' total matches');

            }
        }

    } else {

        var self = this;
        setTimeout(function () {
            self.drawSelectedMatches(matchIndexDelta);
        }, 500);

    }

};

JaneliaMatchTrial.prototype.drawMatch = function(matches, matchIndex, pImage, qImage, context) {

    var pMatches = matches.p;
    var qMatches = matches.q;

    var px = (pMatches[0][matchIndex] * pImage.renderScale * pImage.viewScale) + pImage.x;
    var py = (pMatches[1][matchIndex] * pImage.renderScale * pImage.viewScale) + pImage.y;
    var qx = (qMatches[0][matchIndex] * qImage.renderScale * qImage.viewScale) + qImage.x;
    var qy = (qMatches[1][matchIndex] * qImage.renderScale * qImage.viewScale) + qImage.y;

    context.beginPath();
    context.moveTo(px, py);
    context.lineTo(qx, qy);
    context.stroke();
};

