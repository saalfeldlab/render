var RenderWebServiceData = function(successfulLoadCallback, failedLoadCallback) {

    this.successfulLoadCallback = successfulLoadCallback;
    this.failedLoadCallback = failedLoadCallback;

    var queryParameters = this.getUrlParameterMap();

    this.owner = queryParameters['owner'];
    this.project = queryParameters['project'];
    this.stack = queryParameters['stack'];

    var href = window.location.href;
    var stopIndex = href.indexOf('/view/');

    this.baseUrl =  href.substring(0, stopIndex);
    this.ownerList = [];

    var self = this;

    // load owner data
    $.ajax({
               url: this.baseUrl + '/v1/owners',
               cache: false,
               success: function(data) {
                   self.setOwnerList(data);
               },
               error: function(data, text, xhr) {
                   self.handleAjaxError(data, text, xhr);
               }
           });
};

RenderWebServiceData.prototype.getUrlParameterMap = function() {
    var queryParameters = {};
    var queryString = decodeURIComponent(window.location.search.substring(1));
    var re = /([^&=]+)=([^&]*)/g;
    var m;
    while (m = re.exec(queryString)) {
        queryParameters[m[1]] = m[2];
    }
    return queryParameters;
};

RenderWebServiceData.prototype.getOwnerUrl = function () {
    return this.baseUrl + '/v1/owner/' + this.owner + '/';
};

RenderWebServiceData.prototype.getProjectUrl = function() {
    return this.getOwnerUrl() + 'project/' + this.project + '/';
};

RenderWebServiceData.prototype.getProjectStackMetaDataList = function() {
    var projectStackMetaDataList = [];
    for (var index = 0; index < this.stackMetaDataList.length; index++) {
        if (this.stackMetaDataList[index].stackId.project == this.project) {
            projectStackMetaDataList.push(this.stackMetaDataList[index]);
        }
    }
    return projectStackMetaDataList;
};

RenderWebServiceData.prototype.getStackMetaDataWithStackName = function(stackName, stackMetaDataList) {
    var stackMetaData = undefined;
    for (var index = 0; index < stackMetaDataList.length; index++) {
        if (stackMetaDataList[index].stackId.stack == stackName) {
            stackMetaData = stackMetaDataList[index];
            break;
        }
    }
    return stackMetaData;
};

RenderWebServiceData.prototype.getStackMetaData = function() {
    return this.getStackMetaDataWithStackName(this.stack, this.getProjectStackMetaDataList());
};

RenderWebServiceData.prototype.changeOwnerAndProject = function(owner, project) {
    var queryParameters = this.getUrlParameterMap();
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
};

RenderWebServiceData.prototype.handleAjaxError = function(data, textStatus, xhr) {
    console.log(xhr);
    this.failedLoadCallback("Failed to load render data.  " + xhr);
};

RenderWebServiceData.prototype.setOwnerList = function(data) {

    this.ownerList = data;

    if (this.ownerList.length > 0) {

        var isOwnerValid = false;
        var index;
        for (index = 0; index < this.ownerList.length; index++) {
            if (this.ownerList[index] == this.owner) {
                isOwnerValid = true;
                break;
            }
        }

        var self = this;

        if (isOwnerValid) {
            $.ajax({
                       url: this.getOwnerUrl() + 'stacks',
                       cache: false,
                       success: function(data) {
                           self.setStackMetaDataList(data);
                       },
                       error: function(data, text, xhr) {
                           self.handleAjaxError(data, text, xhr);
                       }
                   });
        } else {
            location.search = $.param( { owner : this.ownerList[0] } );
        }

    } else {

        this.failedLoadCallback('The render data store is empty.');

    }
};

RenderWebServiceData.prototype.setStackMetaDataList = function(data) {

    this.stackMetaDataList = data;
    this.distinctProjects = [];

    if (this.stackMetaDataList.length > 0) {

        var projectToStackCountMap = {};

        var project;
        var index;
        for (index = 0; index < this.stackMetaDataList.length; index++) {
            project = this.stackMetaDataList[index].stackId.project;
            if (typeof projectToStackCountMap[project] == 'undefined') {
                projectToStackCountMap[project] = 0;
            }
            projectToStackCountMap[project]++;
        }

        this.distinctProjects = Object.keys(projectToStackCountMap);

        var selectedProjectIndex = -1;
        for (index = 0; index < this.distinctProjects.length; index++) {
            if (this.distinctProjects[index] == this.project) {
                selectedProjectIndex = index;
            }
        }

        if (selectedProjectIndex < 0) {
            location.search = $.param({
                                          owner : this.owner,
                                          project : this.distinctProjects[0]
                                      });
        } else {

            this.stackCount = projectToStackCountMap[this.project];
            var projectStackMetaDataList = this.getProjectStackMetaDataList();

            if (typeof this.stack == 'undefined') {

                this.stack = projectStackMetaDataList[0].stackId.stack;

            } else {

                var stackMetaData = this.getStackMetaDataWithStackName(this.stack, projectStackMetaDataList);
                if (typeof stackMetaData == 'undefined') {
                    location.search = $.param({
                                                  owner : this.owner,
                                                  project : this.project,
                                                  stack : projectStackMetaDataList[0].stackId.stack
                                              });
                }

            }

            this.successfulLoadCallback(this);
        }

    } else {

        this.failedLoadCallback("The render data store does not contain any projects owned by '" +
                                this.owner + "'.");

    }

};

// =========================================================================================================
// Utility Functions
// =========================================================================================================

RenderWebServiceData.prototype.getSelectedValue = function(id) {
    var select = document.getElementById(id);
    return select.options[select.selectedIndex].value;
};

RenderWebServiceData.prototype.getDefinedValue = function(obj) {
    var definedValue;
    if (typeof obj === 'undefined') {
        definedValue = "";
    } else {
        definedValue = obj;
    }
    return definedValue;
};

RenderWebServiceData.prototype.numberWithCommas = function(x) {
    var parts = x.toString().split(".");
    parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    return parts.join(".");
};

/**
 * @param ownerUrl
 * @param stackInfo
 * @param stackInfo.stackId.owner
 * @param stackInfo.stackId.project
 * @param stackInfo.stackId.stack
 * @param stackInfo.state
 * @param stackInfo.currentVersion.cycleNumber
 * @param stackInfo.currentVersion.cycleStepNumber
 * @param stackInfo.currentVersion.stackResolutionX
 * @param stackInfo.currentVersion.stackResolutionY
 * @param stackInfo.currentVersion.stackResolutionZ
 * @param stackInfo.stats.stackBounds.minZ
 * @param stackInfo.stats.stackBounds.maxZ
 * @param stackInfo.stats.sectionCount
 * @param stackInfo.stats.nonIntegralSectionCount
 * @param stackInfo.stats.tileCount
 * @param stackInfo.stats.transformCount
 */
RenderWebServiceData.prototype.getStackSummaryHtml = function(ownerUrl, stackInfo) {
    var values = [];
    var version = stackInfo.currentVersion;
    if (typeof version === 'undefined') {
        values.push('');
        values.push('');
    } else {
        values.push(this.getDefinedValue(version.cycleNumber));
        values.push(this.getDefinedValue(version.cycleStepNumber));
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
            values.push(this.getDefinedValue(bounds.minZ));
            values.push(this.getDefinedValue(bounds.maxZ));

            if (typeof version !== 'undefined') {
                xp = ((bounds.maxX - bounds.minX) / 2) * version.stackResolutionX;
                yp = ((bounds.maxY - bounds.minY) / 2) * version.stackResolutionY;
                zp = bounds.minZ * version.stackResolutionZ;
            }
        }
        values.push(this.numberWithCommas(this.getDefinedValue(stats.sectionCount)));
        values.push(this.numberWithCommas(this.getDefinedValue(stats.nonIntegralSectionCount)));
        values.push(this.numberWithCommas(this.getDefinedValue(stats.tileCount)));
        values.push(this.numberWithCommas(this.getDefinedValue(stats.transformCount)));
    }

    var baseStackUrl = ownerUrl + 'project/' + stackInfo.stackId.project +
                       '/stack/' + stackInfo.stackId.stack;

    var CATMAIDUrl = 'http://renderer-catmaid.int.janelia.org:8000/?tool=navigator&s0=8' +
                     '&pid=' + stackInfo.stackId.project +
                     '&sid0=' + stackInfo.stackId.stack +
                     '&zp=' + zp + '&yp=' + yp  + '&xp=' + xp;

    //noinspection HtmlUnknownTarget
    var linksHtml = '<a target="_blank" href="' + baseStackUrl + '">Metadata</a> ' +
                    '<a target="_blank" href="' + CATMAIDUrl + '">CATMAID-alpha</a> ' +
                    '<a target="_blank" href="' + baseStackUrl + '/zValues">Z Values</a> ' +
                    '<a target="_blank" href="' + baseStackUrl + '/mergeableZValues">mergeable Z Values</a>';

    if (stackInfo.state == 'OFFLINE') {
        linksHtml = '';
    }

    //noinspection HtmlUnknownTarget
    var detailsLink = '<a href="stack-details.html?owner=' + stackInfo.stackId.owner +
                      '&project=' + stackInfo.stackId.project + '&stack=' + stackInfo.stackId.stack +
                      '">' + stackInfo.stackId.stack  +'</a>';

    return '<tr class="' + stackInfo.state + '">\n' +
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
};

