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

function addStackInfo(ownerUrl, stackInfo) {

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
    var xp = 0;
    var yp = 0;
    var zp = 0;

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

            if (typeof version !== 'undefined') {
                xp = ((bounds.maxX - bounds.minX) / 2) * version.stackResolutionX;
                yp = ((bounds.maxY - bounds.minY) / 2) * version.stackResolutionY;
                zp = bounds.minZ * version.stackResolutionZ;
            }
        }
        values.push(numberWithCommas(getDefinedValue(stats.sectionCount)));
        values.push(numberWithCommas(getDefinedValue(stats.nonIntegralSectionCount)));
        values.push(numberWithCommas(getDefinedValue(stats.tileCount)));
        values.push(numberWithCommas(getDefinedValue(stats.transformCount)));
    }

    var baseStackUrl = ownerUrl + 'project/' + stackInfo.stackId.project +
                       '/stack/' + stackInfo.stackId.stack;

    var CATMAIDUrl = 'http://renderer-catmaid.int.janelia.org:8000/?tool=navigator&s0=8' +
                     '&pid=' + stackInfo.stackId.project +
                     '&sid0=' + stackInfo.stackId.stack +
                     '&zp=' + zp + '&yp=' + yp  + '&xp=' + xp;

    var linksHtml = '<a target="_blank" href="' + baseStackUrl + '">Metadata</a> ' +
                    '<a target="_blank" href="' + CATMAIDUrl + '">CATMAID-alpha</a> ' +
                    '<a target="_blank" href="' + baseStackUrl + '/zValues">Z Values</a> ' +
                    '<a target="_blank" href="' + baseStackUrl + '/highDoseLowDoseZValues">HDLD</a>';

    if (stackInfo.state == 'OFFLINE') {
        linksHtml = '';
    }

    var detailsLink = '<a href="stack-details.html?owner=' + stackInfo.stackId.owner +
                      '&project=' + stackInfo.stackId.project + '&stack=' + stackInfo.stackId.stack +
                      '">' + stackInfo.stackId.stack  +'</a>';

    var infoHtml = '<tr class="' + stackInfo.state + '">\n' +
                   '  <td class="number">' + values[0] + '</td>\n' +
                   '  <td class="number">' + values[1] + '</td>\n' +
                   '  <td>' + detailsLink + '</td>\n' +
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

function getUrlParameterMap() {
    var queryParameters = {};
    var queryString = decodeURIComponent(window.location.search.substring(1));
    var re = /([^&=]+)=([^&]*)/g;
    var m;
    while (m = re.exec(queryString)) {
        queryParameters[m[1]] = m[2];
    }
    return queryParameters;
}

function changeOwnerAndProject(owner, project) {
    var queryParameters = getUrlParameterMap();
    var reloadPage = false;

    if (owner != queryParameters['owner']) {
        queryParameters['owner'] = owner;
        reloadPage = true;
    }

    if (project != queryParameters['project']) {
        if (typeof project == 'undefined') {
            delete queryParameters['project'];
        } else {
            queryParameters['project'] = project;
        }
        reloadPage = true;
    }

    if (reloadPage) {
        location.search = $.param(queryParameters);
    }
}

var LocationVars = function() {

    var queryParameters = getUrlParameterMap();

    this.owner = queryParameters['owner'];
    this.project = queryParameters['project'];

    if (typeof this.owner == 'undefined') {

        this.owner ='flyTEM';

        if (typeof this.project == 'undefined') {
            this.project ='FAFB00';
        }
    }

    this.stack = queryParameters['stack'];

    var href = window.location.href;
    var stopIndex = href.indexOf('/view/');

    this.baseUrl =  href.substring(0, stopIndex);
    this.ownerUrl = href.substring(0, stopIndex) + '/v1/owner/' + this.owner + '/';
};

