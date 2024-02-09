/**
 * @typedef {Object} CanvasMatches
 * @property {String} pGroupId
 * @property {String} pId
 * @property {String} qGroupId
 * @property {String} qId
 * @property {Array} matches
 */
const JaneliaMatchTrialImage = function (renderParametersUrl, row, column, viewScale, cellMargin) {
    this.row = row;
    this.column = column;
    this.viewScale = isNaN(viewScale) ? 0.2 : viewScale;
    this.renderScale = viewScale;
    this.cellMargin = cellMargin;

    // use viewScale for rendering image
    const trialImageUrl = new URL(renderParametersUrl.replace('render-parameters', 'jpeg-image'));
    const renderParametersScale = parseFloat(trialImageUrl.searchParams.get('scale'));
    this.trialRenderScale = isNaN(renderParametersScale) ? 1.0 : renderParametersScale;
    trialImageUrl.searchParams.set('scale', this.viewScale.toString());
    this.imageUrl = trialImageUrl.href;

    $('#trialRenderScale').html(this.trialRenderScale);

    this.image = new Image();
    this.x = -1;
    this.y = -1;
    this.imagePositioned = false;

    const self = this;
    this.image.onload = function () {
        self.positionImage(self.image.naturalWidth, self.image.naturalHeight);
    };

    this.matches = [];
    this.matchIndex = -1;
};

JaneliaMatchTrialImage.prototype.loadImage = function() {
    this.imagePositioned = false;
    this.image.src = this.imageUrl;
};

JaneliaMatchTrialImage.prototype.positionImage = function(scaledCellWidth, scaledCellHeight) {
    this.x = (this.column * (scaledCellWidth + this.cellMargin)) + this.cellMargin;
    this.y = (this.row * (scaledCellHeight + this.cellMargin)) + this.cellMargin;
    this.imagePositioned = true;
};

JaneliaMatchTrialImage.prototype.drawLoadedImage = function(canvas, drawBorder) {
    const context = canvas.getContext("2d");
    context.drawImage(this.image, this.x, this.y);
    if (drawBorder) {
        context.strokeStyle = '#e6194B';
        context.strokeRect(this.x, this.y, this.image.naturalWidth, this.image.naturalHeight);
    }
};

JaneliaMatchTrialImage.prototype.getCanvasWidth = function() {
    return (this.image.naturalWidth);
};

JaneliaMatchTrialImage.prototype.getCanvasHeight = function() {
    return (this.image.naturalHeight);
};

JaneliaMatchTrialImage.prototype.drawClipLine = function(context, relativePosition, clipSize) {

    const scaledClipSize = clipSize * this.viewScale;

    let clipStartX, clipStartY, clipStopX, clipStopY;

    if ((relativePosition === "LEFT") || (relativePosition === "RIGHT")) {
        clipStartY = this.y;
        clipStopY = clipStartY + this.image.naturalHeight;
        if (relativePosition === "LEFT") {
            clipStartX = this.x + this.image.naturalWidth - scaledClipSize;
        } else { // RIGHT
            clipStartX = this.x + scaledClipSize;
        }
        clipStopX = clipStartX;
    } else {
        clipStartX = this.x;
        clipStopX = clipStartX + this.image.naturalWidth;
        if (relativePosition === "TOP") {
            clipStartY = this.y + this.image.naturalHeight - scaledClipSize;
        } else { // BOTTOM
            clipStartY = this.y + scaledClipSize;
        }
        clipStopY = clipStartY;
    }

    context.strokeStyle = '#e6194B';
    context.setLineDash([10, 10]);

    context.beginPath();
    context.moveTo(clipStartX, clipStartY);
    context.lineTo(clipStopX, clipStopY);
    context.stroke();
};

const JaneliaMatchTrial = function (baseUrl, owner, trialId, canvas, viewScale) {

    this.baseUrl = baseUrl;
    this.owner = owner;
    this.canvas = canvas;
    this.viewScale = viewScale;
    this.cellMargin = 4;

    this.matchTrialUrl = this.baseUrl + "/owner/" + this.owner + "/matchTrial";
    this.trialId = trialId;

    this.pImage = undefined;
    this.qImage = undefined;
    this.trialResults = undefined;
    this.isClipped = false;
    this.matchCount = undefined;
    this.matchIndex = 0;
    this.initNewTrialAfterLoad = false;

    this.util = new JaneliaScriptUtilities();

    this.drawMatchLines = false;
};

JaneliaMatchTrial.prototype.clearCanvas = function() {
    const context = this.canvas.getContext("2d");
    context.clearRect(0, 0, this.canvas.width, this.canvas.height);
};

JaneliaMatchTrial.prototype.loadTrial = function(trialId, initNewTrialAfterLoad) {

    this.clearCanvas();
    this.trialResults = undefined;
    this.matchCount = undefined;
    this.matchIndex = 0;
    this.initNewTrialAfterLoad = initNewTrialAfterLoad;

    const self = this;
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

    const self = this;
    const url = new URL(window.location.href);
    url.searchParams.set("matchTrialId", "TBD");

    const newTrialWindow = window.open(url.href, '_blank');
    newTrialWindow.focus();

    newTrialWindow.addEventListener('load', function() {
        self.initNewTrialWindow(newTrialWindow, 0);
    }, true);

};

JaneliaMatchTrial.prototype.deleteTrial = function() {

    const self = this;

    $.ajax({
               url: self.matchTrialUrl + '/' + self.trialId,
               type: 'DELETE',
               success: function () {
                   const trialIdSuffix = self.trialId.substring(self.trialId.length - 7);
                   $('#trialId').html(trialIdSuffix + ' <span style="color:red;">DELETED</span>');
                   $('#deleteTrial').hide();
               },
               error: function (data,
                                text,
                                xhr) {
                   console.log(xhr);
               }
           });

};

/**
 * @typedef {Object} newTrialWindow
 * @property {String} matchTrial
 */
JaneliaMatchTrial.prototype.initNewTrialWindow = function(newTrialWindow, retryCount) {

    const self = this;
    if (typeof self.trialResults !== 'undefined') {

        if (typeof newTrialWindow.matchTrial !== 'undefined') {

            newTrialWindow.matchTrial.initNewTrialForm(self.trialResults.parameters,
                                                       self.pImage.trialRenderScale);

        } else if (retryCount < 3) {
            setTimeout(function () {
                self.initNewTrialWindow(newTrialWindow, (retryCount + 1));
            }, 500);
        } else {
            console.log('initNewTrialWindow: stopping init attempts after ' + retryCount + ' retires');
        }
    }

};

/**
 * @typedef {Object} parameters
 * @property {String} featureAndMatchParameters
 * @property {String} fillWithNoise
 * @property {String} filterListName
 * @property {String} pRenderParametersUrl
 * @property {String} qRenderParametersUrl
 */
JaneliaMatchTrial.prototype.initNewTrialForm = function(parameters, trialRenderScale) {

    const fmParams = parameters.featureAndMatchParameters;
    $('#fdSize').val(fmParams.siftFeatureParameters.fdSize);
    $('#minScale').val(fmParams.siftFeatureParameters.minScale);
    $('#maxScale').val(fmParams.siftFeatureParameters.maxScale);
    $('#steps').val(fmParams.siftFeatureParameters.steps);

    const initMatchDerivationParameters = function(mParams, selectorPrefix) {
        $('#' + selectorPrefix + 'ModelType').val(mParams.matchModelType);

        const matchRegularizerModelType = mParams.matchRegularizerModelType;
        const matchInterpolatedModelLambda = mParams.matchInterpolatedModelLambda;

        if ((typeof matchRegularizerModelType !== "undefined") &&
            (matchRegularizerModelType !== "NOT INTERPOLATED") &&
            (typeof matchInterpolatedModelLambda !== "undefined")) {
            $('#' + selectorPrefix + 'RegularizerModelType').val(matchRegularizerModelType);
            $('#' + selectorPrefix + 'InterpolatedModelLambda').val(matchInterpolatedModelLambda);
        }

        if (selectorPrefix === 'match') {
            $('#' + selectorPrefix + 'Rod').val(mParams.matchRod);
        }

        $('#' + selectorPrefix + 'Iterations').val(mParams.matchIterations);

        if (typeof mParams.matchMaxEpsilonFullScale === "undefined") {
            mParams.matchMaxEpsilonFullScale = mParams.matchMaxEpsilon * (1.0 / trialRenderScale);
        }

        $('#' + selectorPrefix + 'MaxEpsilonFullScale').val(mParams.matchMaxEpsilonFullScale);
        $('#' + selectorPrefix + 'MinInlierRatio').val(mParams.matchMinInlierRatio);
        $('#' + selectorPrefix + 'MinNumInliers').val(mParams.matchMinNumInliers);
        $('#' + selectorPrefix + 'MaxTrust').val(mParams.matchMaxTrust);
        $('#' + selectorPrefix + 'Filter').val(mParams.matchFilter);
        $('#' + selectorPrefix + 'FullScaleCoverageRadius').val(mParams.matchFullScaleCoverageRadius);
    };

    initMatchDerivationParameters(fmParams.matchDerivationParameters, 'match');

    $('#fillWithNoise').val(parameters.fillWithNoise);

    const pClipPosition = fmParams.pClipPosition;
    if ((typeof pClipPosition !== "undefined") && (pClipPosition !== "NO CLIP")) {
        $('#pClipPosition').val(pClipPosition);
        $('#clipPixels').val(fmParams.clipPixels);
    }

    const setRenderParametersUrl = function(selector, trialUrl, initNewTrialAfterLoad) {
        let url = trialUrl;
        if (initNewTrialAfterLoad) {
            const urlBeforeLoad = selector.val();
            if ((urlBeforeLoad !== 'undefined') && (urlBeforeLoad.trim().length > 0)) {
                url = urlBeforeLoad;
            }
        }
        selector.val(url);
    }
    setRenderParametersUrl($('#pRenderParametersUrl'), parameters.pRenderParametersUrl, this.initNewTrialAfterLoad);
    setRenderParametersUrl($('#qRenderParametersUrl'), parameters.qRenderParametersUrl, this.initNewTrialAfterLoad);

    const gdamp = parameters.geometricDescriptorAndMatchFilterParameters;
    if (typeof gdamp !== 'undefined') {

        $('#gdRenderScale').val(gdamp.renderScale);
        $('#gdRenderWithFilter').prop('checked', gdamp.renderWithFilter);
        if (typeof gdamp.filterListName !== 'undefined') {
            $('#gdFilterListName').val(gdamp.filterListName);
        }

        const gdParams = gdamp.geometricDescriptorParameters;
        $('#gdNumberOfNeighbors').val(gdParams.numberOfNeighbors);
        $('#gdRedundancy').val(gdParams.redundancy);
        $('#gdSignificance').val(gdParams.significance);
        $('#gdSigma').val(gdParams.sigma);
        $('#gdThreshold').val(gdParams.threshold);
        $('#gdLocalization').val(gdParams.localization);
        $('#gdLookForMinima').prop('checked', gdParams.lookForMinima);
        $('#gdLookForMaxima').prop('checked', gdParams.lookForMaxima);
        $('#gdSimilarOrientation').prop('checked', gdParams.similarOrientation);
        $('#gdFullScaleBlockRadius').val(gdParams.fullScaleBlockRadius);
        $('#gdFullScaleNonMaxSuppressionRadius').val(gdParams.fullScaleNonMaxSuppressionRadius);
        $('#gdStoredMatchWeight').val(gdParams.gdStoredMatchWeight);

        initMatchDerivationParameters(gdamp.matchDerivationParameters, 'gdMatch');

        $('#includeGeometric').prop('checked', true);
        $('#gdFormDiv').show();
    }

};

JaneliaMatchTrial.prototype.runTrial = function(runTrialButtonSelector, trialRunningSelector, errorMessageSelector) {

    const self = this;

    const getMatchDerivationParameters = function(selectorPrefix) {
        const p = {
            "matchModelType": self.util.getSelectedValue(selectorPrefix + 'ModelType'),
            "matchIterations": parseInt($('#' + selectorPrefix + 'Iterations').val()),
            "matchMaxEpsilonFullScale": parseFloat($('#' + selectorPrefix + 'MaxEpsilonFullScale').val()),
            "matchMinInlierRatio": parseFloat($('#' + selectorPrefix + 'MinInlierRatio').val()),
            "matchMinNumInliers": parseInt($('#' + selectorPrefix + 'MinNumInliers').val()),
            "matchMaxTrust": parseFloat($('#' + selectorPrefix + 'MaxTrust').val()),
            "matchFilter": self.util.getSelectedValue(selectorPrefix + 'Filter'),
            "matchFullScaleCoverageRadius": parseFloat($('#' + selectorPrefix + 'FullScaleCoverageRadius').val())
        };
        if (selectorPrefix === 'match') {
            p["matchRod"] = parseFloat($('#' + selectorPrefix + 'Rod').val());
        }
        const matchRegularizerModelType = $('#' + selectorPrefix + 'RegularizerModelType').val();
        const matchInterpolatedModelLambda = $('#' + selectorPrefix + 'InterpolatedModelLambda').val();

        if ((typeof matchRegularizerModelType !== "undefined") &&
            (matchRegularizerModelType !== "NOT INTERPOLATED") &&
            (typeof matchInterpolatedModelLambda !== "undefined")) {

            p["matchRegularizerModelType"] = matchRegularizerModelType;
            p["matchInterpolatedModelLambda"] = parseFloat(matchInterpolatedModelLambda);
        }

        return p;
    };

    const featureAndMatchParameters = {
        "siftFeatureParameters": {
            "fdSize": parseInt($('#fdSize').val()),
            "minScale": parseFloat($('#minScale').val()),
            "maxScale": parseFloat($('#maxScale').val()),
            "steps": parseInt($('#steps').val())
        },
        "matchDerivationParameters": getMatchDerivationParameters('match')
    };

    const pClipPosition = $('#pClipPosition').val();
    if ((typeof pClipPosition !== "undefined") && (pClipPosition !== "NO CLIP")) {
        featureAndMatchParameters['pClipPosition'] = pClipPosition;
        featureAndMatchParameters['clipPixels'] = parseInt($('#clipPixels').val());
    }

    const requestData = {
        featureAndMatchParameters: featureAndMatchParameters,
        pRenderParametersUrl: $('#pRenderParametersUrl').val(),
        qRenderParametersUrl: $('#qRenderParametersUrl').val()
    };

    if ($('#includeGeometric').is(':checked')) {
        const p = {
            "gdEnabled": true,
            "geometricDescriptorParameters": {
                "numberOfNeighbors": parseInt($('#gdNumberOfNeighbors').val()),
                "redundancy": parseInt($('#gdRedundancy').val()),
                "significance": parseFloat($('#gdSignificance').val()),
                "sigma": parseFloat($('#gdSigma').val()),
                "threshold": parseFloat($('#gdThreshold').val()),
                "localization": $('#gdLocalization').val(),
                "lookForMinima": $('#gdLookForMinima').is(':checked'),
                "lookForMaxima": $('#gdLookForMaxima').is(':checked'),
                "similarOrientation": $('#gdSimilarOrientation').is(':checked'),
                "fullScaleBlockRadius": parseFloat($('#gdFullScaleBlockRadius').val()),
                "fullScaleNonMaxSuppressionRadius": parseFloat($('#gdFullScaleNonMaxSuppressionRadius').val()),
                "gdStoredMatchWeight": parseFloat($('#gdStoredMatchWeight').val())
            },
            "matchDerivationParameters": getMatchDerivationParameters('gdMatch'),
            "renderScale": parseFloat($('#gdRenderScale').val()),
            "renderWithFilter": $('#gdRenderWithFilter').is(':checked')
        };
        const filterListName = $('#gdRenderFilterListName').val().trim();
        if (filterListName.length > 0) {
            p["renderFilterListName"] = filterListName;
        }
        requestData["geometricDescriptorAndMatchFilterParameters"] = p;
    }

    const parametersUrlRegex = /.*render-parameters.*/;
    if (requestData.pRenderParametersUrl.match(parametersUrlRegex) &&
        requestData.qRenderParametersUrl.match(parametersUrlRegex)) {

        errorMessageSelector.text('');
        runTrialButtonSelector.prop("disabled", true);
        trialRunningSelector.show();

        const self = this;
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
                       self.trialId = data.id;
                       const url = new URL(window.location.href);
                       url.searchParams.set("matchTrialId", self.trialId);
                       url.searchParams.delete("createNewTrial");
                       url.searchParams.delete("pRenderParametersUrl");
                       url.searchParams.delete("qRenderParametersUrl");
                       window.location = url;
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
    const splitUrl = parametersUrl.split('/');
    let urlName;
    if (splitUrl.length > 2) {
        urlName = splitUrl[splitUrl.length - 2];
    } else {
        urlName = parametersUrl;
    }
    return '<a target="_blank" href="' + parametersUrl + '">' + urlName + '</a>';
};

/**
 * @param data.parameters.featureAndMatchParameters.siftFeatureParameters.fdSize
 * @param data.parameters.featureAndMatchParameters.siftFeatureParameters.minScale
 * @param data.parameters.featureAndMatchParameters.siftFeatureParameters.maxScale
 * @param data.parameters.featureAndMatchParameters.siftFeatureParameters.steps
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchRod
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchModelType
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchIterations
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchMaxEpsilon
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchMinInlierRatio
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchMinNumInliers
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchMaxNumInliers
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchMaxTrust
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchFilter
 * @param data.parameters.featureAndMatchParameters.matchDerivationParameters.matchFullScaleCoverageRadius
 * @param data.parameters.featureAndMatchParameters.pClipPosition
 * @param data.parameters.featureAndMatchParameters.clipPixels
 * @param data.parameters.pRenderParametersUrl
 * @param data.parameters.qRenderParametersUrl
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.renderScale
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.renderWithFilter
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.renderFilterListName
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.numberOfNeighbors
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.redundancy
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.significance
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.sigma
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.threshold
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.localization
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.lookForMinima
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.lookForMaxima
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.similarOrientation
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.fullScaleBlockRadius
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.fullScaleNonMaxSuppressionRadius
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.geometricDescriptorParameters.gdStoredMatchWeight
 * @param data.parameters.geometricDescriptorAndMatchFilterParameters.matchDerivationParameters
 * @param data.parameters.fillWithNoise
 * @param {Array} data.matches
 * @param data.stats.pFeatureCount
 * @param data.stats.pFeatureDerivationMilliseconds
 * @param data.stats.qFeatureCount
 * @param data.stats.qFeatureDerivationMilliseconds
 * @param {Array} data.stats.consensusSetSizes
 * @param data.stats.aggregateDeltaXStandardDeviation
 * @param data.stats.aggregateDeltaYStandardDeviation
 * @param {Array} data.stats.consensusSetDeltaXStandardDeviations
 * @param {Array} data.stats.consensusSetDeltaYStandardDeviations
 * @param data.stats.matchDerivationMilliseconds
 * @param data.stats.overlappingImagePixels
 * @param data.stats.overlappingCoveragePixels
 * @param data.gdStats
 */
JaneliaMatchTrial.prototype.loadTrialResults = function(data) {

    this.trialResults = data;

    const self = this;

    //console.log(data);

    const fmParams = data.parameters.featureAndMatchParameters;
    this.isClipped = (typeof fmParams.pClipPosition !== 'undefined') && (typeof fmParams.clipPixels !== 'undefined');

    let pRow = 0;
    let pColumn = 0;
    let qRow = 0;
    let qColumn = 1;
    if (this.isClipped) {
        if (fmParams.pClipPosition === 'RIGHT') {
            pColumn = 1;
            qColumn = 0;
        } else if (fmParams.pClipPosition === 'TOP') {
            qRow = 1;
            qColumn = 0;
        } else if (fmParams.pClipPosition === 'BOTTOM') {
            pRow = 1;
            qRow = 0;
            qColumn = 0;
        }
    }

    this.pImage = new JaneliaMatchTrialImage(data.parameters.pRenderParametersUrl, pRow, pColumn, this.viewScale, this.cellMargin);
    this.pImage.loadImage();

    this.qImage = new JaneliaMatchTrialImage(data.parameters.qRenderParametersUrl, qRow, qColumn, this.viewScale, this.cellMargin);
    this.qImage.loadImage();

    $('#trialFdSize').html(fmParams.siftFeatureParameters.fdSize);
    $('#trialMinScale').html(fmParams.siftFeatureParameters.minScale);
    $('#trialMaxScale').html(fmParams.siftFeatureParameters.maxScale);
    $('#trialSteps').html(fmParams.siftFeatureParameters.steps);

    if (this.isClipped) {
        const trialClipRowHtml =
                '<td>Clip Parameters:</td>' +
                '<td colspan="4">' +
                '  pRelativePosition: <span class="parameterValue">' + fmParams.pClipPosition + '</span>' +
                '  clipPixels: <span class="parameterValue">' + fmParams.clipPixels + '</span>' +
                '</td>';
        $('#trialClipRow').html(trialClipRowHtml);
    } else {
        $('#trialClipRow').hide();
    }

    const trialRenderScale = this.pImage.trialRenderScale;

    const setMatchData = function (mParams,
                                   selectPrefix) {
        $('#' + selectPrefix + 'MatchModelType').html(mParams.matchModelType);
        if (selectPrefix === 'trial') {
            $('#' + selectPrefix + 'MatchRod').html(mParams.matchRod);
        }
        $('#' + selectPrefix + 'MatchIterations').html(mParams.matchIterations);
        if (typeof mParams.matchMaxEpsilonFullScale === "undefined") {
            mParams.matchMaxEpsilonFullScale = mParams.matchMaxEpsilon * (1.0 / trialRenderScale);
        }
        $('#' + selectPrefix + 'MatchMaxEpsilonFullScale').html(mParams.matchMaxEpsilonFullScale);
        $('#' + selectPrefix + 'MatchMinInlierRatio').html(mParams.matchMinInlierRatio);
        $('#' + selectPrefix + 'MatchMinNumInliers').html(mParams.matchMinNumInliers);
        $('#' + selectPrefix + 'MatchMaxTrust').html(mParams.matchMaxTrust);
        $('#' + selectPrefix + 'MatchFilter').html(mParams.matchFilter);
        $('#' + selectPrefix + 'MatchFullScaleCoverageRadius').html(mParams.matchFullScaleCoverageRadius);

        let interpolatedModelFieldsHtml = "";
        if ((typeof mParams.matchRegularizerModelType !== "undefined") &&
            (typeof mParams.matchInterpolatedModelLambda !== "undefined")) {
            interpolatedModelFieldsHtml =
                    'regularizerModelType: <span class="parameterValue">' + mParams.matchRegularizerModelType +
                    '</span> ' +
                    'interpolatedModelLambda: <span class="parameterValue">' + mParams.matchInterpolatedModelLambda +
                    '</span>';
        }
        $('#' + selectPrefix + 'InterpolatedModelFields').html(interpolatedModelFieldsHtml);

        if (typeof mParams.matchMaxNumInliers !== 'undefined') {
            $('#' + selectPrefix + 'MatchMaxNumInliers').html(mParams.matchMaxNumInliers);
        }
    };

    setMatchData(data.parameters.featureAndMatchParameters.matchDerivationParameters, 'trial');

    $('#trialFillWithNoise').html(data.parameters.fillWithNoise);

    $('#trialpRenderParametersUrl').html(this.getRenderParametersLink(data.parameters.pRenderParametersUrl));
    $('#trialqRenderParametersUrl').html(this.getRenderParametersLink(data.parameters.qRenderParametersUrl));

    const stats = this.trialResults.stats;

    const updateMatchStatCountsAndTimes = function(featureOrPeak, siftOrGdStats) {
        $('#p' + featureOrPeak + 'Stats').html(
                siftOrGdStats.pFeatureCount + ' ' + featureOrPeak.toLowerCase() +
                's were derived in ' + siftOrGdStats.pFeatureDerivationMilliseconds + ' ms');
        $('#q' + featureOrPeak + 'Stats').html(
                siftOrGdStats.qFeatureCount + ' ' + featureOrPeak.toLowerCase() +
                's were derived in ' + siftOrGdStats.qFeatureDerivationMilliseconds + ' ms');
    };

    updateMatchStatCountsAndTimes('Feature', stats);

    const getDeltaHtml = function(value) {
        let html = value.toFixed(1);
        if (value > 8) {
            html = '<span style="color:red;">' + html + '</span>';
        }
        return html;
    };

    const getStandardDeviationHtml = function(xOrY,
                                              aggregateStandardDeviationValue,
                                              standardDeviationValues) {
        let html = '<br/>Delta ' + xOrY + ' Standard Deviation:';
        if (Array.isArray(standardDeviationValues)) {
            if (standardDeviationValues.length > 1) {
                if (typeof aggregateStandardDeviationValue !== 'undefined') {
                    html += ' aggregate ' + getDeltaHtml(aggregateStandardDeviationValue) + ',';
                }
                html += ' sets [ ' + getDeltaHtml(standardDeviationValues[0]);
                for (let i = 1; i < standardDeviationValues.length; i++) {
                    html = html + ', ' + getDeltaHtml(standardDeviationValues[i]);
                }
                html += ' ] pixels';
            } else if (typeof aggregateStandardDeviationValue !== 'undefined') {
                html += ' ' + getDeltaHtml(aggregateStandardDeviationValue) + ' pixels';
            } else {
                html += ' n/a';
            }
        }
        return html;
    };

    const getCoverageHtml = function(overlappingCoveragePixels,
                                     overlappingImagePixels) {
        let html = '';
        if ((typeof overlappingCoveragePixels !== 'undefined') && (typeof overlappingImagePixels !== 'undefined')) {
            const formattedCoveragePixels = self.util.numberWithCommas(overlappingCoveragePixels);
            const formattedImagePixels = self.util.numberWithCommas(overlappingImagePixels);
            const coveragePercentage = Math.round(overlappingCoveragePixels / overlappingImagePixels * 100);
            html = '<br/>Overlapping Area Coverage: ' + formattedCoveragePixels +
                   ' out of ' + formattedImagePixels  + ' pixels (' + coveragePercentage + '%)';
        }
        return html;
    };

    const getMatchStatsHtml = function (matchStats) {
        const csSizes = typeof matchStats.consensusSetSizes !== 'undefined' ? matchStats.consensusSetSizes : [0];
        let csText;
        if (csSizes.length === 1) {
            if (csSizes[0] === 0) {
                csText = 'NO matches were '
            } else {
                csText = '1 consensus set with ' + csSizes[0] + ' matches was';
            }
        } else {
            csText = csSizes.length + ' consensus sets with [' + csSizes.toString() + '] matches were';
        }

        return csText + " derived in " + matchStats.matchDerivationMilliseconds + " ms<br/>" +
               getStandardDeviationHtml('X',
                                        matchStats.aggregateDeltaXStandardDeviation,
                                        matchStats.consensusSetDeltaXStandardDeviations) +
               getStandardDeviationHtml('Y',
                                        matchStats.aggregateDeltaYStandardDeviation,
                                        matchStats.consensusSetDeltaYStandardDeviations) +
               getCoverageHtml(matchStats.overlappingCoveragePixels, matchStats.overlappingImagePixels)
    };

    $('#matchStats').html(getMatchStatsHtml(stats));

    let gdTotalMs = 0;

    if (typeof data.parameters.geometricDescriptorAndMatchFilterParameters !== 'undefined') {

        const gdamp = data.parameters.geometricDescriptorAndMatchFilterParameters;
        const gdParams = gdamp.geometricDescriptorParameters;

        $('#gdTrialRenderScale').html(gdamp.renderScale);
        $('#gdTrialRenderWithFilter').html(gdamp.renderWithFilter.toString());
        if (typeof gdamp.renderFilterListName === "undefined") {
            $('#gdTrialRenderFilterListNameLabel').hide();
        } else {
            $('#gdTrialRenderFilterListName').html(gdamp.renderFilterListName);
        }

        $('#gdTrialSimilarOrientation').html(gdParams.similarOrientation.toString());
        $('#gdTrialNumberOfNeighbors').html(gdParams.numberOfNeighbors);
        $('#gdTrialRedundancy').html(gdParams.redundancy);
        $('#gdTrialSignificance').html(gdParams.significance);

        $('#gdTrialSigma').html(gdParams.sigma);
        $('#gdTrialThreshold').html(gdParams.threshold);
        $('#gdTrialLocalization').html(gdParams.localization);
        $('#gdTrialLookForMinima').html(gdParams.lookForMinima.toString());
        $('#gdTrialLookForMaxima').html(gdParams.lookForMaxima.toString());

        $('#gdTrialFullScaleBlockRadius').html(gdParams.fullScaleBlockRadius);
        $('#gdTrialFullScaleNonMaxSuppressionRadius').html(gdParams.fullScaleNonMaxSuppressionRadius);
        $('#gdTrialStoredMatchWeight').html(gdParams.gdStoredMatchWeight);

        setMatchData(gdamp.matchDerivationParameters, 'gdTrial');

        const gdStats = data.gdStats;

        updateMatchStatCountsAndTimes('Peak', gdStats);
        $('#gdMatchStats').html(getMatchStatsHtml(gdStats));

        $('#gdHeaderDiv').show();

        gdTotalMs = (gdStats.pFeatureDerivationMilliseconds +
                     gdStats.qFeatureDerivationMilliseconds +
                     gdStats.matchDerivationMilliseconds)

    }

    const totalMs = stats.pFeatureDerivationMilliseconds + stats.qFeatureDerivationMilliseconds +
                    stats.matchDerivationMilliseconds + gdTotalMs;

    $('#trialElapsedMessage').html(', took ' + this.util.numberWithCommas(totalMs) + ' ms to process');

    // hack to populate aggregate std dev for older match trials that have only one consensus set ...
    if ((typeof stats.aggregateDeltaXStandardDeviation === 'undefined') &&
        (data.parameters.featureAndMatchParameters.matchDerivationParameters.matchFilter !== 'AGGREGATED_CONSENSUS_SETS') &&
        (typeof stats.consensusSetDeltaXStandardDeviations !== 'undefined') &&
        (stats.consensusSetDeltaXStandardDeviations.length === 1)) {

        stats.aggregateDeltaXStandardDeviation = stats.consensusSetDeltaXStandardDeviations[0];
        stats.aggregateDeltaYStandardDeviation = stats.consensusSetDeltaYStandardDeviations[0];
    }

    const consensusSetMatches = this.trialResults.matches;
    this.matchCount = 0;
    for (let consensusSetIndex = 0; consensusSetIndex < consensusSetMatches.length; consensusSetIndex++) {
        const matches = consensusSetMatches[consensusSetIndex];
        this.matchCount += matches.w.length;
    }

    if (this.initNewTrialAfterLoad) {
        this.initNewTrialWindow(window, 99); // should not need to retry
    } else {
        $('#deleteTrial').show();
        this.drawAllMatches();
    }
};

JaneliaMatchTrial.prototype.drawAllMatches = function() {
    this.drawSelectedMatches(undefined);
};

JaneliaMatchTrial.prototype.drawSelectedMatches = function(matchIndexDelta) {

    if (this.pImage.imagePositioned && this.qImage.imagePositioned) {

        const firstImage = this.pImage.column === 0 && this.pImage.row === 0 ? this.pImage : this.qImage;

        // reposition images based upon image sizes and relative clip position
        this.pImage.positionImage(firstImage.image.naturalWidth, firstImage.image.naturalHeight);
        this.qImage.positionImage(firstImage.image.naturalWidth, firstImage.image.naturalHeight);

        let pRelativePosition = "LEFT";
        let qRelativePosition = "RIGHT";

        const context = this.canvas.getContext("2d");

        if (this.pImage.column === this.qImage.column) {
            context.canvas.width = Math.max(this.pImage.getCanvasWidth(),
                                            this.qImage.getCanvasWidth()) + this.cellMargin;
        } else if (this.pImage.column === 0) { // p is left of q
            context.canvas.width = this.qImage.x + this.qImage.getCanvasWidth() + this.cellMargin;
        } else { // q is left of p
            context.canvas.width = this.pImage.x + this.pImage.getCanvasWidth() + this.cellMargin;
            pRelativePosition = "RIGHT";
            qRelativePosition = "LEFT";
        }

        if (this.pImage.row === this.qImage.row) {
            context.canvas.height = Math.max(this.pImage.getCanvasHeight(),
                                             this.qImage.getCanvasHeight()) + this.cellMargin;
        } else if (this.pImage.row === 0) { // p is above q
            context.canvas.height = this.qImage.y + this.qImage.getCanvasHeight() + this.cellMargin;
            pRelativePosition = "TOP";
            qRelativePosition = "BOTTOM";
        } else { // q is above p
            context.canvas.height = this.pImage.y + this.pImage.getCanvasHeight() + this.cellMargin;
            pRelativePosition = "BOTTOM";
            qRelativePosition = "TOP";
        }

        this.clearCanvas();
        this.pImage.drawLoadedImage(this.canvas, true);
        this.qImage.drawLoadedImage(this.canvas, false);

        if (this.isClipped) {
            const clipSize = this.trialResults.parameters.featureAndMatchParameters.clipPixels;
            this.pImage.drawClipLine(context, pRelativePosition, clipSize);
            this.qImage.drawClipLine(context, qRelativePosition, clipSize);
        }

        if (this.matchCount > 0) {

            const consensusSetMatches = this.trialResults.matches;

            context.lineWidth = 1;

            const matchInfoSelector = $('#matchInfo');
            let consensusSetIndex = 0;
            let matches;
            let i;

            if (typeof matchIndexDelta !== 'undefined') {

                this.matchIndex = (this.matchIndex + matchIndexDelta) % this.matchCount;
                if (this.matchIndex < 0) {
                    this.matchIndex = this.matchCount - 1;
                }

                let lastI = 0;
                for (; consensusSetIndex < consensusSetMatches.length; consensusSetIndex++) {
                    matches = consensusSetMatches[consensusSetIndex];
                    i = this.matchIndex - lastI;
                    if (i > -1) {
                        context.strokeStyle = '#00ff00';
                        context.fillStyle = context.strokeStyle;
                        this.drawMatch(matches, i, this.pImage, this.qImage, context);
                        break;
                    }
                    lastI = lastI + matches.w.length;
                }

                matchInfoSelector.html('match ' + (this.matchIndex + 1) + ' of ' + this.matchCount);

            } else {

                this.matchIndex = 0;

                if (consensusSetMatches.length === 1) {

                    matches = consensusSetMatches[0];

                    const colors = ['#00ff00', '#f48342', '#42eef4', '#f442f1'];

                    for (i = 0; i < matches.w.length; i++) {
                        context.strokeStyle = colors[i % colors.length];
                        context.fillStyle = context.strokeStyle;
                        this.drawMatch(matches, i, this.pImage, this.qImage, context);
                    }

                } else {

                    // color set adapted from https://sashat.me/2017/01/11/list-of-20-simple-distinct-colors/
                    const colors = [
                        '#4363d8', '#e6194b', '#3cb44b', '#ffe119',
                        '#f58231', '#911eb4', '#46f0f0', '#f032e6',
                        '#bcf60c', '#fabebe', '#008080', '#e6beff',
                        '#9a6324', '#800000', '#aaffc3',
                    ];

                    for (; consensusSetIndex < consensusSetMatches.length; consensusSetIndex++) {
                        matches = consensusSetMatches[consensusSetIndex];
                        context.strokeStyle = colors[consensusSetIndex % colors.length];
                        context.fillStyle = context.strokeStyle;
                        for (i = 0; i < matches.w.length; i++) {
                            this.drawMatch(matches, i, this.pImage, this.qImage, context);
                        }
                    }

                }

                matchInfoSelector.html(this.matchCount + ' total matches');

            }
        }

    } else {

        const self = this;
        setTimeout(function () {
            self.drawSelectedMatches(matchIndexDelta);
        }, 500);

    }

};

JaneliaMatchTrial.prototype.drawMatch = function(matches, matchIndex, pImage, qImage, context) {

    const pMatches = matches.p;
    const qMatches = matches.q;

    const px = (pMatches[0][matchIndex] * pImage.viewScale) + pImage.x;
    const py = (pMatches[1][matchIndex] * pImage.viewScale) + pImage.y;
    const qx = (qMatches[0][matchIndex] * qImage.viewScale) + qImage.x;
    const qy = (qMatches[1][matchIndex] * qImage.viewScale) + qImage.y;

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
        context.fill();
        context.beginPath();
        context.arc(qx, qy, radius, 0, twoPI);
        context.fill();
    }

};

JaneliaMatchTrial.prototype.toggleLinesAndPoints = function() {
    this.drawMatchLines = ! this.drawMatchLines;
    this.drawSelectedMatches(undefined);

    const toggleLinesAndPointsSelector = $("#toggleLinesAndPoints");
    if (this.drawMatchLines) {
        toggleLinesAndPointsSelector.prop('value', 'Points')
    } else {
        toggleLinesAndPointsSelector.prop('value', 'Lines')
    }
};

JaneliaMatchTrial.prototype.saveTrialResultsToCollection = function(saveToOwner, saveToCollection, errorMessageSelector) {

    // Typical tile render URL pattern is:
    //   <base_url>/owner/<owner>/project/<project>/stack/<stack>/tile/<tile_id>/render-parameters?...
    // where <tile_id> pattern is:
    //   <acquisition_timestamp>.<section_id>
    const typicalTileRenderRegEx = /(.*\/render-ws).*\/owner\/([^\/]+)\/project\/([^\/]+)\/stack\/([^\/]+)\/tile\/([^\\.]+\.(\d+\.\d+))\/render-parameters.*/;

    const pUrlMatch = this.trialResults.parameters.pRenderParametersUrl.match(typicalTileRenderRegEx);
    const qUrlMatch = this.trialResults.parameters.qRenderParametersUrl.match(typicalTileRenderRegEx);


    if ((typeof saveToCollection === 'undefined') || (saveToCollection.length === 0)) {

        errorMessageSelector.text("alpha version of save feature requires saveToCollection query parameter to be defined");

    } else if (this.trialResults.matches.length < 1) {

        errorMessageSelector.text("no matches to save");

    } else if (pUrlMatch && qUrlMatch) {

        let matchesToSave = this.trialResults.matches[0];
        for (let i = 1; i < this.trialResults.matches.length; i++) {
            const secondaryMatches = this.trialResults.matches[i];
            matchesToSave.p[0] = matchesToSave.p[0].concat(secondaryMatches.p[0]);
            matchesToSave.p[1] = matchesToSave.p[1].concat(secondaryMatches.p[1]);
            matchesToSave.q[0] = matchesToSave.q[0].concat(secondaryMatches.q[0]);
            matchesToSave.q[1] = matchesToSave.q[1].concat(secondaryMatches.q[1]);
            matchesToSave.w = matchesToSave.w.concat(secondaryMatches.w);
        }

        const baseViewUrl = pUrlMatch[1] + "/view";
        const renderStackOwner = pUrlMatch[2];
        const renderStackProject = pUrlMatch[3];
        const renderStack = pUrlMatch[4];

        const pTileId = pUrlMatch[5];
        const pGroupId = pUrlMatch[6];
        const qTileId = qUrlMatch[5];
        const qGroupId = qUrlMatch[6];

        const matchPairData = {
            "pGroupId": pGroupId,
            "pId": pTileId,
            "qGroupId": qGroupId,
            "qId": qTileId,
            "matches": matchesToSave
        };
        const matchPairDataArray = [ matchPairData ];

        // set match owner to render owner if match owner was not explicitly specified
        const effectiveSaveToOwner = (typeof saveToOwner === 'undefined') ? renderStackOwner : saveToOwner;

        const matchesUrl = this.baseUrl + "/owner/" + effectiveSaveToOwner + "/matchCollection/" + saveToCollection + "/matches";
        const tilePairUrl = baseViewUrl + "/tile-pair.html?renderScale=0.1&renderStackOwner=" + renderStackOwner +
                            "&renderStackProject=" + renderStackProject +
                            "&renderStack=" + renderStack +
                            "&matchOwner=" + saveToOwner +
                            "&matchCollection=" + saveToCollection +
                            "&pGroupId=" + pGroupId +
                            "&pId=" + pTileId +
                            "&qGroupId=" + qGroupId +
                            "&qId=" + qTileId;

        errorMessageSelector.text("");

        // noinspection JSUnusedLocalSymbols
        $.ajax({
                   type: "PUT",
                   headers: {
                       'Accept': 'application/json',
                       'Content-Type': 'application/json'
                   },
                   url: matchesUrl,
                   data: JSON.stringify(matchPairDataArray),
                   cache: false,
                   success: function(data) {
                       const matchPairHtml = '<a target="_blank" href="' + tilePairUrl + '">match pair</a>';
                       errorMessageSelector.html("saved " + matchPairHtml + " to " + saveToCollection);
                   },
                   error: function(data, text, xhr) {
                       console.log(xhr);
                       errorMessageSelector.text(data.statusText + ': ' + data.responseText);
                   }
               });

    } else {

        errorMessageSelector.text("alpha version of save feature cannot parse render URL(s) for this match trial");

    }

};

