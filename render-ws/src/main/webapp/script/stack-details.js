var locationVars;
var sectionData = [];

function getOrderData() {

    var originalData = [];
    var reorderedData = [];
    var z;
    var zInteger;
    var sectionFloat;
    var sectionInteger;
    var name;
    for (var index = 0; index < sectionData.length; index++) {
        z = sectionData[index].z;
        zInteger = parseInt(z);
        sectionFloat = parseFloat(sectionData[index].sectionId);
        sectionInteger = parseInt(sectionFloat);
        if (zInteger == sectionInteger) {
            data = originalData;
        } else {
            data = reorderedData;
        }
        name = sectionFloat + ' --> ' + z;
        data.push({ 'x': 0, 'y': sectionFloat, 'name': name });
        data.push({ 'x': 1, 'y': z, 'name': name });
        data.push(null);
    }

    return [
        { 'name': 'Original', 'data': originalData },
        { 'name': 'Reordered', 'data': reorderedData }
    ];
}

function getTileCountData() {

    var zToSectionDataMap = {};
    var zIntegerValues = {};

    var integerZ;
    var minIntegerZ = undefined;
    var maxIntegerZ = undefined;
    var z;
    var tileCount;
    for (var index = 0; index < sectionData.length; index++) {
        z = sectionData[index].z;
        integerZ = parseInt(z);
        zIntegerValues[integerZ] = 1;
        if ((minIntegerZ === undefined) || (integerZ < minIntegerZ)) {
            minIntegerZ = integerZ;
        }
        if ((maxIntegerZ === undefined) || (integerZ > maxIntegerZ)) {
            maxIntegerZ = integerZ;
        }

        // handle older sectionData without tileCounts
        tileCount = sectionData[index].tileCount;
        if (tileCount === undefined) {
            tileCount = -1;
        }

        if (z in zToSectionDataMap) {
            zToSectionDataMap[z].y += tileCount;
            zToSectionDataMap[z].name = zToSectionDataMap[z].name + ', ' + sectionData[index].sectionId;
            zToSectionDataMap[z].sectionData.push(sectionData[index]);
        } else {
            tileCount =
            zToSectionDataMap[z] = {
                'x': sectionData[index].z,
                'y': tileCount,
                'name': sectionData[index].sectionId,
                'sectionData': [ sectionData[index] ]
            };
        }
    }

    if (sectionData.length > 0) {
        for (z = minIntegerZ; z <= maxIntegerZ; z++) {
            if (!(z in zIntegerValues)) {
                zToSectionDataMap[z] = {
                    'x': z,
                    'y': 0,
                    'name': 'n/a',
                    'sectionData': []
                };
            }
        }
    }

    var originalData = [];
    var reacquiredData = [];
    var mergedData = [];
    var missingData = [];

    var dataObject;
    for (z in zToSectionDataMap) {
        if (zToSectionDataMap.hasOwnProperty(z)) {
            dataObject = zToSectionDataMap[z];
            if (dataObject.y == 0) {
                missingData.push(dataObject);
            } else if (dataObject.sectionData.length > 1) {
                mergedData.push(dataObject);
            } else if (dataObject.sectionData.length > 0) {
                if (dataObject.sectionData[0].sectionId.substr(-2) === '.0') {
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

function drawSectionDataCharts(owner, project, stack) {

    Highcharts.setOptions({
        lang: {
            thousandsSep: ','
        }
    });

    var chartHeight = 600;
    var orderingChartWidth = 280;
    var tileCountChartWidth = 1400 - orderingChartWidth;

    // TODO: determine best way to make render server references dynamic
    var baseRenderUrl = 'http://renderer.int.janelia.org:8080/render-ws/v1';
    var baseOverviewZUrl = baseRenderUrl + '/owner/' + owner + '/project/' + project + '/stack/' + stack +
                            '/largeDataTileSource/2048/2048/small/';
    var overviewZUrlSuffix = '.jpg?maxOverviewWidthAndHeight=800&maxTileSpecsToRender=1';

    $('#sectionOrdering').highcharts({
        title: {
            text: 'Section Ordering'
        },
        subtitle: {
            text: 'FAFB00 v10_acquire'
        },
        chart: {
            type: 'scatter',
            zoomType: 'y',
            height: chartHeight,
            width: orderingChartWidth
        },
        xAxis: {
            categories: ['From', 'To'],
            max: 1

        },
        yAxis: {
            title: {
                text: 'Z'
            }
        },
        tooltip: {
            headerFormat: '<span>{series.name} Section</span><br>',
            pointFormat: '<span>{point.name}</span> <a target="_blank" href="' +
                         baseOverviewZUrl + '{point.y}' + overviewZUrlSuffix + '">overview</a>',
            useHTML: true
        },
        legend: {
            layout: 'vertical',
            align: 'right',
            verticalAlign: 'middle',
            borderWidth: 0
        },
        plotOptions: {
            series: {
                marker: {
                    radius: 2
                },
                // see http://api.highcharts.com/highcharts#plotOptions.series.turboThreshold
                turboThreshold: 0
            },
            scatter:{
                lineWidth:2
            }
        },
        series: getOrderData()
    });

    $('#sectionTileCounts').highcharts({
        title: {
            text: 'Section Data'
        },
        subtitle: {
            text: project + ' ' + stack
        },
        chart: {
            type: 'scatter',
            zoomType: 'x',
            height: chartHeight,
            width: tileCountChartWidth
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
            headerFormat: '<span>Type: {series.name}</span><br>',
            pointFormat: '<span>Sections: "{point.name}"</span><br/><span>Z: {point.x}</span> <a target="_blank" href="' +
                         baseOverviewZUrl + '{point.x}' + overviewZUrlSuffix + '">overview</a><br/>Tiles: {point.y}',
            useHTML: true
        },
        legend: {
            layout: 'vertical',
            align: 'right',
            verticalAlign: 'middle',
            borderWidth: 0
        },
        plotOptions: {
            series: {
                // see http://api.highcharts.com/highcharts#plotOptions.series.turboThreshold
                turboThreshold: 0
            }
        },
        series: getTileCountData()
    });
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
                 sectionData = data;
                 drawSectionDataCharts(locationVars.owner, locationVars.project, locationVars.stack);
             },
             function(xhr) {
                 console.error(xhr);
                 alert('ERROR: failed to load section data.\n\nDetails:\n' + xhr.responseText);
             });
}
