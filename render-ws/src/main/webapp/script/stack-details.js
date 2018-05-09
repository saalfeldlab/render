var RenderWebServiceStackDetails = function(ownerSelectId, projectSelectId, stackSelectId, messageId, urlToViewId) {

    var queryParameters = new JaneliaQueryParameters();

    this.renderDataUi = new JaneliaRenderServiceDataUI(queryParameters, ownerSelectId, projectSelectId, stackSelectId, messageId, urlToViewId);

    this.renderData = this.renderDataUi.renderServiceData;
    this.sectionData = [];
    this.zToSectionDataMap = {};
    this.sectionFloatToZMap = {};
    this.minZForStack = undefined;
    this.isSectionDataLoaded = false;

    var self = this;

    var successfulLoadCallback = function () {

        var renderData = self.renderData;

        document.title = renderData.stack;
        $('#owner').text(renderData.owner + ' > ');

        var projectHref = 'stacks.html' + self.renderDataUi.queryParameters.getSearch();
        $('#bodyHeaderLink').attr("href", projectHref).text(renderData.owner + ' ' + renderData.project);

        $('#bodyHeader').text(renderData.stack);

        var stackInfoSelect = $('#stackInfo');
        var summaryHtml = self.renderDataUi.getStackSummaryHtml(renderData.getOwnerUrl(),
                                                                renderData.getStackMetaData(),
                                                                true,
                                                                false);
        stackInfoSelect.find('tr:last').after(summaryHtml);

        var sectionDataUrl = renderData.getProjectUrl() + "stack/" + renderData.stack + "/sectionData";

        // load section data
        $.ajax({
                   url: sectionDataUrl,
                   cache: false,
                   success: function(data) {
                       self.loadAndMapSectionData(data);
                   },
                   error: function(data, text, xhr) {
                       renderData.handleAjaxError(data, text, xhr);
                   }
               });

    };

    // hack to trigger section data load when initial project data loads
    this.renderDataUi.addProjectChangeCallback(successfulLoadCallback);

    this.renderDataUi.loadData();

};

/**
 * @param data
 * @param data.sectionId
 */
RenderWebServiceStackDetails.prototype.loadAndMapSectionData = function(data) {

    this.sectionData = data;
    this.zToSectionDataMap = {};
    this.minZForStack = undefined;

    var zIntegerValues = {};

    var integerZ;
    var minIntegerZ = undefined;
    var maxIntegerZ = undefined;
    var z;
    var sectionFloat;
    for (var index = 0; index < this.sectionData.length; index++) {

        z = this.sectionData[index].z;
        if ((this.minZForStack === undefined) || (z < this.minZForStack)) {
            this.minZForStack = z;
        }

        integerZ = parseInt(z);
        zIntegerValues[integerZ] = 1;
        if ((minIntegerZ === undefined) || (integerZ < minIntegerZ)) {
            minIntegerZ = integerZ;
        }
        if ((maxIntegerZ === undefined) || (integerZ > maxIntegerZ)) {
            maxIntegerZ = integerZ;
        }

        if (z in this.zToSectionDataMap) {
            this.zToSectionDataMap[z].zTileCount += this.sectionData[index].tileCount;
            this.zToSectionDataMap[z].sectionList.push(this.sectionData[index]);
        } else {
            this.zToSectionDataMap[z] = {
                'zTileCount': this.sectionData[index].tileCount,
                'sectionList': [ this.sectionData[index] ]
            };
        }

        sectionFloat = parseFloat(this.sectionData[index].sectionId);
        this.sectionFloatToZMap[sectionFloat] = z;
    }

    if (this.sectionData.length > 0) {

        for (z = minIntegerZ; z <= maxIntegerZ; z++) {
            if (!(z in zIntegerValues)) {
                this.zToSectionDataMap[z] = {
                    'zTileCount': 0,
                    'sectionList': []
                };
            }
        }

    } else {
        this.minZForStack = 0;
    }

    this.isSectionDataLoaded = true;

    $('#sectionDataStatus').hide();
};

RenderWebServiceStackDetails.prototype.joinSectionIds = function(sectionList) {
    return sectionList.map(
            function(elem) {
                return elem.sectionId;
            }).join(', ');
};

RenderWebServiceStackDetails.prototype.isOriginalSection = function(sectionId) {
    var isOriginal = true;
    var dotIndex = sectionId.lastIndexOf('.');
    if (dotIndex > 0) {
        isOriginal = (sectionId.substr(dotIndex) === '.0');
    }
    return isOriginal;
};

RenderWebServiceStackDetails.prototype.getLinksForZ = function(z) {

    var baseRenderUrl = this.renderDataUi.getDynamicRenderBaseUrl();
    var baseDataUrl = '../v1';
    var owner = this.renderData.owner;
    var project = this.renderData.project;
    var stack = this.renderData.stack;

    var dataServiceZBase = '<a target="_blank" href="' + baseDataUrl + '/owner/' +
                           owner + '/project/' + project + '/stack/' + stack + '/z/' + z;
    var links = [
        dataServiceZBase + '/bounds">bounds</a>',
        dataServiceZBase + '/tileBounds">tile bounds</a>',
        dataServiceZBase + '/resolvedTiles">resolved tiles</a>'
    ];

    if (typeof baseRenderUrl != 'undefined') {
        //noinspection HtmlUnknownTarget
        var overview = '<a target="_blank" href="' + baseRenderUrl + '/owner/' +
                       owner + '/project/' + project + '/stack/' + stack  +
                       '/largeDataTileSource/2048/2048/small/' + z +
                       '.jpg?maxOverviewWidthAndHeight=400&maxTileSpecsToRender=1&translateOrigin=true">overview</a>';
        links.push(overview);
    }

    return links.join(', ');
};

function RenderWebServiceChart(stackDetails, chartId, title) {

    this.stackDetails = stackDetails;
    this.chartId = chartId;
    this.title = title;

    this.maxNumberOfItems = 8000;
    this.series = [];
    this.sampledSeries = [];
    this.numberOfItems = 0;
    this.hasSamples = false;
    this.sampleSize = 1;
    this.displaySamples = true;

    this.chart = undefined;
    this.chartOptions = undefined;

    var self = this;

    this.isSectionDataLoaded = function() {
        return self.stackDetails.isSectionDataLoaded;
    };

    this.addSeries = function(name, data, lineWidth) {
        var seriesData = {name: name, data: data};
        if (typeof(lineWidth) != 'undefined') {
            seriesData.lineWidth = lineWidth;
        }
        self.series.push(seriesData);
        self.numberOfItems = Math.max(self.numberOfItems, data.length);
    };

    this.sampleAllSeries = function() {
        self.sampledSeries = [];

        if (self.numberOfItems > self.maxNumberOfItems) {
            self.sampleSize = Math.ceil(self.numberOfItems / self.maxNumberOfItems);
            for (var i = 0; i < self.series.length; i++) {
                var fullData = self.series[i].data;
                var sampledData = [];
                for (var j = 0; j < fullData.length; j += self.sampleSize) {
                    sampledData.push(fullData[j]);
                }
                if (self.numberOfItems % this.maxNumberOfItems > 0) {
                    sampledData.push(fullData[fullData.length - 1]);
                }
                self.sampledSeries.push({name: self.series[i].name, data: sampledData})
            }
            self.hasSamples = true;
        } else {
            self.sampledSeries = self.series;
        }
    };

    this.toggleSampleDisplay = function(successCallback) {
        if (self.hasSamples) {
            self.displaySamples = ! self.displaySamples;

            self.destroy();

            // delay redraw so that chart clears immediately
            setTimeout(function() {
                self.redraw();
                successCallback();
            }, 100);
        } else {
            successCallback();
        }
    };

    this.buildChartOptions = function() {
        return undefined; // should be implemented by specific chart
    };

    this.buildSeries = function() {
        // should be implemented by specific chart
    };

    this.getEffectiveSeries = function() {
        if (self.isSectionDataLoaded()) {
            if (self.series.length == 0) {
                self.buildSeries();
                self.sampleAllSeries();
            }
        }

        return self.displaySamples ? self.sampledSeries : self.series;
    };

    this.getEffectiveSubtitle = function() {
        var subtitle = 'project: ' + self.stackDetails.renderData.project +
                       ', stack: ' + self.stackDetails.renderData.stack;
        if (self.displaySamples && (self.sampleSize > 1)) {
            subtitle += ', sample size: ' + self.sampleSize;
        }
        return subtitle;
    };

    this.destroy = function() {
        if (self.chart !== undefined) {
            self.chart.destroy();
        }
    };

    this.redraw = function() {

        if (self.chart === undefined) {
            self.chartOptions = self.buildChartOptions();
            self.chartOptions.title = {text: self.title};
        }

        self.chartOptions.series = null;
        self.chartOptions.series = self.getEffectiveSeries();
        // add subtitle after series data because it relies upon that data
        self.chartOptions.subtitle = {text: self.getEffectiveSubtitle()};

        console.log('building ' + self.title + ' chart ...');
        self.chart = Highcharts.chart(self.chartId, self.chartOptions);
        console.log(self.title + ' chart is ready');
    };

}

function RenderWebServiceTileCountsChart(stackDetails, chartId) {

    RenderWebServiceChart.call(this, stackDetails, chartId, 'Tile Counts');

    var tooltipFormatter = function () {
        var z = parseFloat(this.x);
        var sectionIds = stackDetails.joinSectionIds(stackDetails.zToSectionDataMap[z].sectionList);
        var links = stackDetails.getLinksForZ(z);
        return '<span>Z: ' + z + '</span><br/>' +
               '<span>Sections: ' + sectionIds + '</span><br/>' +
               '<span>Type: ' + this.series.name + '</span><br/>' +
               '<span>Tile Count: ' + this.y + '</span><br/>' +
               links;

    };

    this.buildChartOptions = function () {
        return {
            chart: {
                type: 'scatter',
                zoomType: 'x'
            },
            scrollbar: {
                enabled: true
            },
            xAxis: {
                title: {
                    text: 'Z'
                }
            },
            yAxis: {
                title: {
                    text: 'Tile Count'
                }
            },
            tooltip: {
                formatter: tooltipFormatter,
                useHTML: true,
                shared: true
            },
            legend: {
                layout: 'vertical',
                align: 'right',
                verticalAlign: 'middle',
                borderWidth: 0,
                width: 130
            }
        };
    };

    var self = this;

    this.buildSeries = function() {

        self.series = [];

        var originalData = [];
        var reacquiredData = [];
        var mergedData = [];
        var missingData = [];

        var dataObject;
        var sectionList;
        for (var z in stackDetails.zToSectionDataMap) {

            if (stackDetails.zToSectionDataMap.hasOwnProperty(z)) {

                dataObject = [parseFloat(z), stackDetails.zToSectionDataMap[z].zTileCount];
                sectionList = stackDetails.zToSectionDataMap[z].sectionList;

                if (sectionList.length > 1) {

                    mergedData.push(dataObject);

                } else if (sectionList.length > 0) {

                    if (stackDetails.isOriginalSection(sectionList[0].sectionId)) {
                        originalData.push(dataObject);
                    } else {
                        reacquiredData.push(dataObject);
                    }

                } else {
                    missingData.push(dataObject);
                }
            }
        }

        self.addSeries('Original ( .0)', originalData);
        self.addSeries('Reacquired ( .1+)', reacquiredData);
        self.addSeries('Merged', mergedData);
        self.addSeries('Missing', missingData);
    };
}

function RenderWebServiceSectionOrderingChart(stackDetails, chartId) {

    RenderWebServiceChart.call(this, stackDetails, chartId, 'Section Ordering');

    var tooltipFormatter = function () {
        var z = parseFloat(this.x);
        var toZ = z;
        if (this.y == 0) {
            toZ = stackDetails.sectionFloatToZMap[z];
        }
        var sectionIds = stackDetails.joinSectionIds(stackDetails.zToSectionDataMap[toZ].sectionList);
        if (sectionIds.length > 0) {
            sectionIds = sectionIds + ' --> ';
        }
        var links = stackDetails.getLinksForZ(toZ);
        return '<span>' + this.series.name + ' Section</span><br/>' +
               '<span>' + sectionIds + toZ + '</span><br/>' +
               links;
    };

    this.buildChartOptions = function () {
        return {
            chart: {
                type: 'scatter',
                zoomType: 'x',
                height: 250
            },
            scrollbar: {
                enabled: true
            },
            xAxis: {
                title: {
                    text: 'Z'
                },
                min: self.minZForStack
            },
            yAxis: {
                title: {
                    text: ''
                },
                categories: ['From', 'To', ''],
                max: 2
            },
            tooltip: {
                formatter: tooltipFormatter,
                useHTML: true,
                shared: true
            },
            legend: {
                layout: 'vertical',
                align: 'right',
                verticalAlign: 'middle',
                borderWidth: 0,
                width: 130
            }
        };
    };

    var self = this;

    this.buildSeries = function() {

        self.series = [];

        var originalData = [];
        var reorderedData = [];
        var mergedData = [];
        var missingData = [];

        var z;
        var zFloat;
        var sectionList;
        var zInteger;
        var sectionFloat;
        var sectionInteger;

        for (z in stackDetails.zToSectionDataMap) {

            if (stackDetails.zToSectionDataMap.hasOwnProperty(z)) {

                zFloat = parseFloat(z);
                sectionList = stackDetails.zToSectionDataMap[z].sectionList;

                if (sectionList.length > 1) {

                    zInteger = parseInt(z);
                    sectionFloat = parseFloat(sectionList[0].sectionId);
                    sectionInteger = parseInt(sectionFloat);

                    if (zInteger == sectionInteger) {
                        mergedData.push([zFloat, 1]);
                    } else {
                        reorderedData.push([sectionFloat, 0]);
                        reorderedData.push([zFloat, 1]);
                        reorderedData.push([null, null]);
                    }

                } else if (sectionList.length > 0) {

                    zInteger = parseInt(z);
                    sectionFloat = parseFloat(sectionList[0].sectionId);
                    sectionInteger = parseInt(sectionFloat);

                    if (zInteger == sectionInteger) {
                        originalData.push([zFloat, 1]);
                    } else {
                        reorderedData.push([sectionFloat, 0]);
                        reorderedData.push([zFloat, 1]);
                        reorderedData.push([null, null]);
                    }

                } else {
                    missingData.push([zFloat, 1]);
                }

            }
        }

        self.addSeries('Original', originalData);
        self.addSeries('Reordered', reorderedData, 1);
        self.addSeries('Merged', mergedData);
        self.addSeries('Missing', missingData);
    };
}

function RenderWebServiceSectionBoundsChart(stackDetails, chartId, forXValues, forDeltaValues) {

    var boundsType = forXValues ? 'X' : 'Y';
    var title = boundsType + ' Bounds';
    if (forDeltaValues) {
        title += ' Delta';
    }

    RenderWebServiceChart.call(this, stackDetails, chartId, title);

    var tooltipFormatter = function () {
        var z = parseFloat(this.x);
        var sectionIds = stackDetails.joinSectionIds(stackDetails.zToSectionDataMap[z].sectionList);
        var links = stackDetails.getLinksForZ(z);
        return '<span>Z: ' + z + '</span><br/>' +
               '<span>Sections: ' + sectionIds + '</span><br/>' +
               '<span>' + this.series.name + ': ' + this.y + '</span><br/>' +
               links;
    };

    this.buildChartOptions = function () {
        return {
            chart: {
                type: 'scatter',
                zoomType: 'x'
            },
            scrollbar: {
                enabled: true
            },
            xAxis: {
                title: {
                    text: 'Z'
                }
            },
            yAxis: {
                title: {
                    text: title
                }
            },
            tooltip: {
                formatter: tooltipFormatter,
                useHTML: true,
                shared: true
            },
            legend: {
                layout: 'vertical',
                align: 'right',
                verticalAlign: 'middle',
                borderWidth: 0,
                width: 130
            }
        };
    };

    var self = this;

    this.buildSeries = function() {

        self.series = [];

        var minData = [];
        var maxData = [];

        var sectionList;
        var floatZ;
        var previousMin = null;
        var previousMax = null;
        for (var z in stackDetails.zToSectionDataMap) {
            if (stackDetails.zToSectionDataMap.hasOwnProperty(z)) {

                sectionList = stackDetails.zToSectionDataMap[z].sectionList;
                floatZ = parseFloat(z);

                if (forDeltaValues && (previousMin == null)) {
                    if (forXValues) {
                        previousMin = sectionList[0].minX;
                        previousMax = sectionList[0].maxX;
                    } else {
                        previousMin = sectionList[0].minY;
                        previousMax = sectionList[0].maxY;
                    }
                }

                for (var index = 0; index < sectionList.length; index++) {
                    if (sectionList[index].minX !== undefined) {
                        if (forXValues) {
                            if (forDeltaValues) {
                                minData.push([floatZ, (sectionList[index].minX - previousMin)]);
                                maxData.push([floatZ, (sectionList[index].maxX - previousMax)]);
                                previousMin = sectionList[index].minX;
                                previousMax = sectionList[index].maxX;
                            } else {
                                minData.push([floatZ, sectionList[index].minX]);
                                maxData.push([floatZ, sectionList[index].maxX]);
                            }
                        } else {
                            if (forDeltaValues) {
                                minData.push([floatZ, (sectionList[index].minY - previousMin)]);
                                maxData.push([floatZ, (sectionList[index].maxY - previousMax)]);
                                previousMin = sectionList[index].minY;
                                previousMax = sectionList[index].maxY;
                            } else {
                                minData.push([floatZ, sectionList[index].minY]);
                                maxData.push([floatZ, sectionList[index].maxY]);
                            }
                        }
                    }
                }
            }
        }

        self.addSeries('min' + boundsType, minData);
        self.addSeries('max' + boundsType, maxData);
    };
}
