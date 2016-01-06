var locationVars;
var sectionData = [];

function getSeriesData() {

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

function drawSectionDataChart(project, stack) {

    Highcharts.setOptions({
        lang: {
            thousandsSep: ','
        }
    });

    $('#container').highcharts({
        title: {
            text: 'Section Data'
        },
        subtitle: {
            text: project + ' ' + stack
        },
        chart: {
            type: 'scatter'
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
            pointFormat: '<span>Sections: "{point.name}"</span><br/><span>Z: {point.x}</span><br/>Tiles: {point.y}<br/>'
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
        series: getSeriesData()
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
                 drawSectionDataChart(locationVars.project, locationVars.stack);
             },
             function(xhr) {
                 console.error(xhr);
                 alert('ERROR: failed to load section data.\n\nDetails:\n' + xhr.responseText);
             });
}
