var RenderWebServiceStackDetails = function(ownerSelectId, projectSelectId, stackSelectId, messageId, urlToViewId) {

    var queryParameters = new JaneliaQueryParameters();

    this.renderDataUi = new JaneliaRenderServiceDataUI(queryParameters, ownerSelectId, projectSelectId, stackSelectId, messageId, urlToViewId);

    this.renderData = this.renderDataUi.renderServiceData;
    this.sectionData = [];
    this.zToSectionDataMap = {};
    this.sectionFloatToZMap = {};
    this.minZForStack = undefined;

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
                                                                false);
        stackInfoSelect.find('tr:last').after(summaryHtml);

        var sectionDataUrl = renderData.getProjectUrl() + "stack/" + renderData.stack + "/sectionData";

        // load section data
        $.ajax({
                   url: sectionDataUrl,
                   cache: false,
                   success: function(data) {
                       self.drawSectionDataCharts(data, renderData.owner, renderData.project, renderData.stack);
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

RenderWebServiceStackDetails.prototype.getLinksForZ = function(baseDataUrl, baseRenderUrl, owner, project, stack, z) {
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

RenderWebServiceStackDetails.prototype.getOrderData = function() {

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

    for (z in this.zToSectionDataMap) {

        if (this.zToSectionDataMap.hasOwnProperty(z)) {

            zFloat = parseFloat(z);
            sectionList = this.zToSectionDataMap[z].sectionList;

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

    return [
        { 'name': 'Original', 'data': originalData },
        { 'name': 'Reordered', 'data': reorderedData, lineWidth: 1 },
        { 'name': 'Merged', 'data': mergedData },
        { 'name': 'Missing', 'data': missingData }
    ];
};

RenderWebServiceStackDetails.prototype.getTileCountData = function() {

    var originalData = [];
    var reacquiredData = [];
    var mergedData = [];
    var missingData = [];

    var dataObject;
    var sectionList;
    for (var z in this.zToSectionDataMap) {

        if (this.zToSectionDataMap.hasOwnProperty(z)) {

            dataObject = [parseFloat(z), this.zToSectionDataMap[z].zTileCount];
            sectionList = this.zToSectionDataMap[z].sectionList;

            if (sectionList.length > 1) {

                mergedData.push(dataObject);

            } else if (sectionList.length > 0) {

                if (this.isOriginalSection(sectionList[0].sectionId)) {
                    originalData.push(dataObject);
                } else {
                    reacquiredData.push(dataObject);
                }

            } else {
                missingData.push(dataObject);
            }
        }
    }

    return [
        { 'name': 'Original ( .0)', 'data': originalData },
        { 'name': 'Reacquired ( .1+)', 'data': reacquiredData },
        { 'name': 'Merged', 'data': mergedData },
        { 'name': 'Missing', 'data': missingData }
    ];
};

RenderWebServiceStackDetails.prototype.getBoundsData = function() {

    var minXData = [];
    var maxXData = [];
    var minYData = [];
    var maxYData = [];

    var sectionList;
    var floatZ;
    for (var z in this.zToSectionDataMap) {
        if (this.zToSectionDataMap.hasOwnProperty(z)) {
            sectionList = this.zToSectionDataMap[z].sectionList;
            floatZ = parseFloat(z);
            for (var index = 0; index < sectionList.length; index++) {
                if (sectionList[index].minX !== undefined) {
                    minXData.push([floatZ, sectionList[index].minX]);
                    maxXData.push([floatZ, sectionList[index].maxX]);
                    minYData.push([floatZ, sectionList[index].minY]);
                    maxYData.push([floatZ, sectionList[index].maxY]);
                }
            }
        }
    }

    return [
        { 'name': 'minX', 'data': minXData },
        { 'name': 'maxX', 'data': maxXData },
        { 'name': 'minY', 'data': minYData },
        { 'name': 'maxY', 'data': maxYData }
    ];
};

RenderWebServiceStackDetails.prototype.drawSectionDataCharts = function(data, owner, project, stack) {

    var self = this;
    self.loadAndMapSectionData(data);

    //noinspection JSJQueryEfficiency
    $('#sectionDataStatus').text('building section ordering chart ...');

    Highcharts.setOptions({
        lang: {
            thousandsSep: ','
        }
    });

    var baseRenderUrl = self.renderDataUi.getDynamicRenderBaseUrl();
    var baseDataUrl = '../v1';

    var sectionOrderingTooltipFormatter = function() {
        var z = parseFloat(this.x);
        var toZ = z;
        if (this.y == 0) {
            toZ = self.sectionFloatToZMap[z];
        }
        var sectionIds = self.joinSectionIds(self.zToSectionDataMap[toZ].sectionList);
        if (sectionIds.length > 0) {
            sectionIds = sectionIds + ' --> ';
        }
        var links = self.getLinksForZ(baseDataUrl, baseRenderUrl, owner, project, stack, toZ);
        return '<span>' + this.series.name + ' Section</span><br/>' +
               '<span>' + sectionIds + toZ + '</span><br/>' +
               links;
    };

    $('#sectionOrdering').highcharts({
        title: {
            text: 'Section Ordering'
        },
        subtitle: {
            text: project + ' ' + stack
        },
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
            formatter: sectionOrderingTooltipFormatter,
            useHTML: true,
            shared : true
        },
        legend: {
            layout: 'vertical',
            align: 'right',
            verticalAlign: 'middle',
            borderWidth: 0,
            width: 130
        },
        series: self.getOrderData()
    });

    //noinspection JSJQueryEfficiency
    $('#sectionDataStatus').text('building tile counts chart ...');

    var sectionTileCountsTooltipFormatter = function() {
        var z = parseFloat(this.x);
        var sectionIds = self.joinSectionIds(self.zToSectionDataMap[z].sectionList);
        var links = self.getLinksForZ(baseDataUrl, baseRenderUrl, owner, project, stack, z);
        return '<span>Z: ' + z + '</span><br/>' +
               '<span>Sections: ' + sectionIds + '</span><br/>' +
               '<span>Type: ' + this.series.name + '</span><br/>' +
               '<span>Tile Count: ' + this.y + '</span><br/>' +
               links;
    };

    $('#sectionTileCounts').highcharts({
        title: {
            text: 'Tile Counts'
        },
        subtitle: {
            text: project + ' ' + stack
        },
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
            formatter: sectionTileCountsTooltipFormatter,
            useHTML: true,
            shared: true
        },
        legend: {
            layout: 'vertical',
            align: 'right',
            verticalAlign: 'middle',
            borderWidth: 0,
            width: 130
        },
        series: self.getTileCountData()
    });

    //noinspection JSJQueryEfficiency
    //$('#sectionDataStatus').text('building bounds chart ...');
    //
    //$('#sectionBounds').highcharts({
    //    title: {
    //        text: 'Section Bounds'
    //    },
    //    subtitle: {
    //        text: project + ' ' + stack
    //    },
    //    chart: {
    //        type: 'scatter',
    //        zoomType: 'x'
    //    },
    //    scrollbar: {
    //        enabled: true
    //    },
    //    xAxis: {
    //        title: {
    //            text: 'Z'
    //        }
    //    },
    //    yAxis: {
    //        title: {
    //            text: 'Bounds'
    //        }
    //    },
    //    tooltip: {
    //        formatter: function() {
    //            var z = parseFloat(this.x);
    //            var sectionIds = self.joinSectionIds(zToSectionDataMap[z].sectionList);
    //            var links = self.getLinksForZ(baseDataUrl, baseRenderUrl, owner, project, stack, z);
    //            return '<span>Z: ' + z + '</span><br/>' +
    //                   '<span>Sections: ' + sectionIds + '</span><br/>' +
    //                   '<span>' + this.series.name + ': ' + this.y + '</span><br/>' +
    //                   links;
    //        },
    //        useHTML: true,
    //        shared: true
    //    },
    //    legend: {
    //        layout: 'vertical',
    //        align: 'right',
    //        verticalAlign: 'middle',
    //        borderWidth: 0,
    //        width: 130
    //    },
    //    series: self.getBoundsData()
    //});

    //noinspection JSJQueryEfficiency
    $('#sectionDataStatus').hide();
};
