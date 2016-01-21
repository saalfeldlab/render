var locationVars;
var sectionData = [];
var zToSectionDataMap = {};
var sectionFloatToZMap = {};
var minZForStack = 0;

function loadAndMapSectionData(data) {

    sectionData = data;
    zToSectionDataMap = {};
    minZForStack = undefined;

    var zIntegerValues = {};

    var integerZ;
    var minIntegerZ = undefined;
    var maxIntegerZ = undefined;
    var z;
    var sectionFloat;
    for (var index = 0; index < sectionData.length; index++) {

        z = sectionData[index].z;
        if ((minZForStack === undefined) || (z < minZForStack)) {
            minZForStack = z;
        }

        integerZ = parseInt(z);
        zIntegerValues[integerZ] = 1;
        if ((minIntegerZ === undefined) || (integerZ < minIntegerZ)) {
            minIntegerZ = integerZ;
        }
        if ((maxIntegerZ === undefined) || (integerZ > maxIntegerZ)) {
            maxIntegerZ = integerZ;
        }

        if (z in zToSectionDataMap) {
            zToSectionDataMap[z].zTileCount += sectionData[index].tileCount;
            zToSectionDataMap[z].sectionList.push(sectionData[index]);
        } else {
            zToSectionDataMap[z] = {
                'zTileCount': sectionData[index].tileCount,
                'sectionList': [ sectionData[index] ]
            };
        }

        sectionFloat = parseFloat(sectionData[index].sectionId);
        sectionFloatToZMap[sectionFloat] = z;
    }

    if (sectionData.length > 0) {

        for (z = minIntegerZ; z <= maxIntegerZ; z++) {
            if (!(z in zIntegerValues)) {
                zToSectionDataMap[z] = {
                    'zTileCount': 0,
                    'sectionList': []
                };
            }
        }

    } else {
        minZForStack = 0;
    }
}

function joinSectionIds(sectionList) {
    return sectionList.map(
            function(elem) {
                return elem.sectionId;
            }).join(', ');
}

function isOriginalSection(sectionId) {
    var isOriginal = true;
    var dotIndex = sectionId.lastIndexOf('.');
    if (dotIndex > 0) {
        isOriginal = (sectionId.substr(dotIndex) === '.0');
    }
    return isOriginal;
}

function getLinksForZ(baseDataUrl, baseRenderUrl, owner, project, stack, z) {
    var dataServiceZBase = '<a target="_blank" href="' + baseDataUrl + '/owner/' +
                           owner + '/project/' + project + '/stack/' + stack + '/z/' + z;
    var overview = '<a target="_blank" href="' + baseRenderUrl + '/owner/' +
                   owner + '/project/' + project + '/stack/' + stack  +
                   '/largeDataTileSource/2048/2048/small/' + z +
                   '.jpg?maxOverviewWidthAndHeight=400&maxTileSpecsToRender=1">overview</a>';
    var links = [
        dataServiceZBase + '/bounds">bounds</a>',
        dataServiceZBase + '/tileBounds">tile bounds</a>',
        dataServiceZBase + '/resolvedTiles">resolved tiles</a>',
        overview
    ];

    return links.join(', ');
}

function getOrderData() {

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

    for (z in zToSectionDataMap) {

        if (zToSectionDataMap.hasOwnProperty(z)) {

            zFloat = parseFloat(z);
            sectionList = zToSectionDataMap[z].sectionList;

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
}

function getTileCountData() {

    var originalData = [];
    var reacquiredData = [];
    var mergedData = [];
    var missingData = [];

    var dataObject;
    var sectionList;
    for (z in zToSectionDataMap) {

        if (zToSectionDataMap.hasOwnProperty(z)) {

            dataObject = [parseFloat(z), zToSectionDataMap[z].zTileCount];
            sectionList = zToSectionDataMap[z].sectionList;

            if (sectionList.length > 1) {

                mergedData.push(dataObject);

            } else if (sectionList.length > 0) {

                if (isOriginalSection(sectionList[0].sectionId)) {
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
}

function getBoundsData() {

    var minXData = [];
    var maxXData = [];
    var minYData = [];
    var maxYData = [];

    var sectionList;
    var floatZ;
    for (z in zToSectionDataMap) {
        if (zToSectionDataMap.hasOwnProperty(z)) {
            sectionList = zToSectionDataMap[z].sectionList;
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
}

function drawSectionDataCharts(data, owner, project, stack) {

    loadAndMapSectionData(data);

    //noinspection JSJQueryEfficiency
    $('#sectionDataStatus').text('building section ordering chart ...');

    Highcharts.setOptions({
        lang: {
            thousandsSep: ','
        }
    });

    // TODO: determine best way to make render server references dynamic
    var baseRenderUrl = 'http://renderer.int.janelia.org:8080/render-ws/v1';
    var baseDataUrl = '../v1';

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
            min: minZForStack
        },
        yAxis: {
            title: {
                text: ''
            },
            categories: ['From', 'To', ''],
            max: 2
        },
        tooltip: {
            formatter: function() {
                var z = parseFloat(this.x);
                var toZ = z;
                if (this.y == 0) {
                    toZ = sectionFloatToZMap[z];
                }
                var sectionIds = joinSectionIds(zToSectionDataMap[toZ].sectionList);
                if (sectionIds.length > 0) {
                    sectionIds = sectionIds + ' --> ';
                }
                var links = getLinksForZ(baseDataUrl, baseRenderUrl, owner, project, stack, toZ);
                return '<span>' + this.series.name + ' Section</span><br/>' +
                       '<span>' + sectionIds + toZ + '</span><br/>' +
                       links;
            },
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
        series: getOrderData()
    });

    //noinspection JSJQueryEfficiency
    $('#sectionDataStatus').text('building tile counts chart ...');

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
            formatter: function() {
                var z = parseFloat(this.x);
                var sectionIds = joinSectionIds(zToSectionDataMap[z].sectionList);
                var links = getLinksForZ(baseDataUrl, baseRenderUrl, owner, project, stack, z);
                return '<span>Z: ' + z + '</span><br/>' +
                       '<span>Sections: ' + sectionIds + '</span><br/>' +
                       '<span>Type: ' + this.series.name + '</span><br/>' +
                       '<span>Tile Count: ' + this.y + '</span><br/>' +
                       links;
            },
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
        series: getTileCountData()
    });

    //noinspection JSJQueryEfficiency
    $('#sectionDataStatus').text('building bounds chart ...');

    $('#sectionBounds').highcharts({
        title: {
            text: 'Section Bounds'
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
                text: 'Bounds'
            }
        },
        tooltip: {
            formatter: function() {
                var z = parseFloat(this.x);
                var sectionIds = joinSectionIds(zToSectionDataMap[z].sectionList);
                var links = getLinksForZ(baseDataUrl, baseRenderUrl, owner, project, stack, z);
                return '<span>Z: ' + z + '</span><br/>' +
                       '<span>Sections: ' + sectionIds + '</span><br/>' +
                       '<span>' + this.series.name + ': ' + this.y + '</span><br/>' +
                       links;
            },
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
        series: getBoundsData()
    });

    //noinspection JSJQueryEfficiency
    $('#sectionDataStatus').hide();
}

function initPage() {

    locationVars = new LocationVars();

    var stackUrl = locationVars.ownerUrl + "project/" + locationVars.project + "/stack/" + locationVars.stack;

    loadJSON(stackUrl,
             function(data) {
                 addStackInfo(locationVars.ownerUrl, data);
                 document.title = locationVars.stack;
                 $('#owner').text(locationVars.owner + ' > ');

                 var projectHref = 'stacks.html?owner=' + locationVars.owner + '&project=' + locationVars.project;
                 $('#project').attr("href", projectHref).text(locationVars.project);

                 $('#bodyHeader').text(locationVars.stack);
             },
             function(xhr) { console.error(xhr); });

    loadJSON(stackUrl + "/sectionData",
             function(data) {
                 drawSectionDataCharts(data, locationVars.owner, locationVars.project, locationVars.stack);
             },
             function(xhr) {
                 console.error(xhr);
                 alert('ERROR: failed to load section data.\n\nDetails:\n' + xhr.responseText);
             });
}
