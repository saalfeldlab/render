function loadJSON(path, success, error) {

    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4) {
            if (xhr.status === 200) {
                if (success) {
                    success(JSON.parse(xhr.responseText));
                }
            } else if (error) {
                error(xhr);
            }
        }
    };

    var nonCachedPath = path + "?t=" + new Date().getTime();
    xhr.open("GET", nonCachedPath, true);
    xhr.send();
}

function getSelectedValue(id) {
    var select = document.getElementById(id);
    return select.options[select.selectedIndex].value;
}

function getDefinedValue(obj) {
    var definedValue;
    if (typeof obj === 'undefined') {
        definedValue = "";
    } else {
        definedValue = obj;
    }
    return definedValue;
}

function numberWithCommas(x) {
    var parts = x.toString().split(".");
    parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    return parts.join(".");
}

function addStackInfo(stackInfo) {

    var values = [];
    var version = stackInfo.currentVersion;
    if (typeof version === 'undefined') {
        values.push('');
        values.push('');
    } else {
        values.push(getDefinedValue(version.cycleNumber));
        values.push(getDefinedValue(version.cycleStepNumber));
    }

    var stats = stackInfo.stats;
    if (typeof stats === 'undefined') {
        values.push('');
        values.push('');
        values.push('');
        values.push('');
        values.push('');
        values.push('');
    } else {
        var bounds = stats.stackBounds;
        if (typeof bounds === 'undefined') {
            values.push('');
            values.push('');
        } else {
            values.push(getDefinedValue(bounds.minZ));
            values.push(getDefinedValue(bounds.maxZ));
        }
        values.push(numberWithCommas(getDefinedValue(stats.sectionCount)));
        values.push(numberWithCommas(getDefinedValue(stats.nonIntegralSectionCount)));
        values.push(numberWithCommas(getDefinedValue(stats.tileCount)));
        values.push(numberWithCommas(getDefinedValue(stats.transformCount)));
    }

    var baseStackUrl = ownerUrl + 'project/' + stackInfo.stackId.project +
                       '/stack/' + stackInfo.stackId.stack;
    var linksHtml = '<a target="_blank" href="' + baseStackUrl + '">Metadata</a> ' +
                    '<a target="_blank" href="' + baseStackUrl + '/bounds">Bounds</a> ' +
                    '<a target="_blank" href="' + baseStackUrl + '/zValues">Z Values</a>';

    if (stackInfo.state == 'OFFLINE') {
        linksHtml = '';
    }

    var infoHtml = '<tr class="' + stackInfo.state + '">\n' +
                   '  <td class="number">' + values[0] + '</td>\n' +
                   '  <td class="number">' + values[1] + '</td>\n' +
                   '  <td>' + stackInfo.stackId.stack + '</td>\n' +
                   '  <td>' + stackInfo.state + '</td>\n' +
                   '  <td class="number">' + values[2] + '</td>\n' +
                   '  <td class="number">' + values[3] + '</td>\n' +
                   '  <td class="number">' + values[4] + '</td>\n' +
                   '  <td class="number">' + values[5] + '</td>\n' +
                   '  <td class="number">' + values[6] + '</td>\n' +
                   '  <td class="number">' + values[7] + '</td>\n' +
                   '  <td>' + linksHtml + '</td>\n' +
                   '</tr>\n';

    $('#stackInfo').find('tr:last').after(infoHtml);
}

function updateStackInfoForProject(projectName) {

    $('#stackInfo').find("tr:gt(0)").remove();

    for (var index = 0; index < stackMetaData.length; index++) {
        if (stackMetaData[index].stackId.project == projectName) {
            addStackInfo(stackMetaData[index]);
        }
    }
}

function updateOwnerList(ownerList, selectedOwner) {
    var ownerSelect = $('#owner');
    var isSelected;
    for (index = 0; index < ownerList.length; index++) {
        isSelected = (ownerList[index] == selectedOwner);
        ownerSelect.append($('<option>', { value : ownerList[index] }).text(ownerList[index]).prop('selected', isSelected));
    }

    $('#ownerName').text(selectedOwner);

    ownerSelect.change(function() {
        window.location.href = baseUrl + '/view/stacks.html?owner=' + getSelectedValue("owner");
    });
}

function updateStackMetaData(data, selectedProject) {

    stackMetaData = data;

    var distinctProjectsMap = {};
    for (var index = 0; index < stackMetaData.length; index++) {
        distinctProjectsMap[stackMetaData[index].stackId.project] = 1;
    }

    var distinctProjects = Object.keys(distinctProjectsMap);

    $('#stackCount').text('(' + distinctProjects.length + ' projects, ' + stackMetaData.length + ' stacks)');

    var projectSelect = $('#project');
    var isSelected;
    for (index = 0; index < distinctProjects.length; index++) {
        isSelected = (distinctProjects[index] == selectedProject);
        projectSelect.append($('<option>', { value : distinctProjects[index] }).text(distinctProjects[index]).prop('selected', isSelected));
    }

    projectSelect.change(function() {
        updateStackInfoForProject(getSelectedValue("project"));
    });

    projectSelect.trigger("change");
}

function getUrlParameter(parameterName) {
    var queryString = decodeURIComponent(window.location.search.substring(1));
    var queryPairs = queryString.split('&');
    var pair;
    var values = [];

    for (var i = 0; i < queryPairs.length; i++) {
        pair = queryPairs[i].split('=');
        if ((pair.length == 2) && (pair[0] === parameterName)) {
            values.push(pair[1]);
        }
    }

    return values;
}

function getFirstUrlParameter(parameterName) {
    var values = getUrlParameter(parameterName);
    var firstValue = undefined;
    if (values.length > 0) {
        firstValue = values[0];
    }
    return firstValue;
}

var baseUrl;
var ownerUrl;
var stackMetaData;

function initPage() {

    // http://tem-services.int.janelia.org:8080/render-ws/view/stacks.html?owner=flyTEM&project=FAFB00

    var selectedOwner = getFirstUrlParameter('owner');
    if (typeof selectedOwner == 'undefined') {
        selectedOwner = 'flyTEM';
    }

    var selectedProject = getFirstUrlParameter('project');

    var href = window.location.href;
    var stopIndex = href.indexOf('/view/stacks.html');
    baseUrl =  href.substring(0, stopIndex);
    ownerUrl = href.substring(0, stopIndex) + '/v1/owner/' + selectedOwner + '/';

    loadJSON(baseUrl + '/v1/owners',
             function(data) { updateOwnerList(data, selectedOwner); },
             function(xhr) { console.error(xhr); });

    loadJSON(ownerUrl + "stacks",
             function(data) { updateStackMetaData(data, selectedProject); },
             function(xhr) { console.error(xhr); });
}

