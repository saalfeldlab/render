/**
 *
 * @constructor
 */
var JaneliaScriptUtilities = function() {

    var self = this;

    this.getErrorMessage = function(data) {

        var contentType = data.getResponseHeader('content-type') || '';
        var message = data.responseText;
        if (contentType.indexOf('html') > -1) {
            message = data.statusText;
        }
        return message;
    };

    this.handleAjaxError = function(data, textStatus, xhr, failureCallbackFunction) {

        console.log(xhr);
        failureCallbackFunction(self.getErrorMessage(data));
    };

    this.getShortRenderHost = function() {
	var href = window.location.href;
        var hostIndex = href.indexOf(window.location.host);
        var stopIndex = href.indexOf('/', hostIndex);

	        // for localhost debugging:
        // return 'http://renderer-dev.int.janelia.org:8080/render-ws/v1';


        return href.substring(7, stopIndex);
	};

    this.getServicesBaseUrl = function() {
        var href = window.location.href;
        var hostIndex = href.indexOf(window.location.host);
        var stopIndex = href.indexOf('/', hostIndex);

                // for localhost debugging:
        // return 'http://renderer-dev.int.janelia.org:8080/render-ws/v1';

        return href.substring(0, stopIndex) + '/render-ws/v1';
    };

    this.getSelectedValue = function(selectId) {
        var selectElement = document.getElementById(selectId);
        return selectElement.options[selectElement.selectedIndex].value;
    };

    this.updateSelectOptions = function(selectId, optionList, selectedValue) {

        var selectElementSelector = $('#' + selectId);

        selectElementSelector.empty();

        var value;
        var isSelected;
        for (var index = 0; index < optionList.length; index++) {
            value = optionList[index];
            isSelected = (value == selectedValue);
            selectElementSelector.append($('<option/>').val(value).text(value).prop('selected', isSelected));
        }
        //console.log('updated ' + optionList.length + ' options for ' + selectId);
    };

    this.addOnChangeCallbackForSelect = function(selectId, changeCallback) {

        var selectElementSelector = $('#' + selectId);
        selectElementSelector.on(
                "change",
                function () {
                    var changedValue = self.getSelectedValue(selectId);
                    //console.log('changing ' + selectId + ' to ' + changedValue);
                    changeCallback(changedValue);
                });
    };

    this.addOnChangeCallbackForInput = function(inputId, changeCallback) {

        var inputElementSelector = $('#' + inputId);
        inputElementSelector.on(
                "change",
                function () {
                    var changedValue = inputElementSelector.val();
                    //console.log('changing ' + inputId + ' to ' + changedValue);
                    changeCallback(changedValue);
                });
    };

    this.getDefinedValue = function(obj) {
        var definedValue = '';
        if (typeof obj !== 'undefined') {
            definedValue = obj;
        }
        return definedValue;
    };

    this.getValidValue = function(selectedValue, validValueList, defaultValue) {
        var validValue = null;

        if ((typeof selectedValue !== 'undefined') &&
            (selectedValue != null) &&
            (validValueList.indexOf(selectedValue) > -1)) {
            validValue = selectedValue;
        }

        if ((validValue == null) && (validValueList.length > 0)) {

            if ((typeof defaultValue !== 'undefined') &&
                (defaultValue != null) &&
                (validValueList.indexOf(defaultValue) > -1)) {

                validValue = defaultValue;

            } else {
                validValue = validValueList[0];
            }
        }

        return validValue;
    };

    this.numberWithCommas = function(x) {
        var parts = x.toString().split(".");
        parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
        return parts.join(".");
    };
};

/**
 *
 * @constructor
 */
var JaneliaQueryParameters = function() {

    this.map = {};

    var queryString = decodeURIComponent(window.location.search.substring(1));
    var re = /([^&=]+)=([^&]*)/g;
    var m;
    while (m = re.exec(queryString)) {
        this.map[m[1]] = m[2];
    }
};

JaneliaQueryParameters.prototype.getSearch = function() {
    return '?' + $.param(this.map);
};

JaneliaQueryParameters.prototype.get = function(key, defaultValue) {
    var value = this.map[key];
    if ((typeof value == 'undefined') && (typeof defaultValue != 'undefined')){
        value = defaultValue;
    }
    return value;
};

JaneliaQueryParameters.prototype.updateParameter = function (key, value) {

    if (typeof key != 'undefined') {

        if (typeof value == 'undefined' || value == '') {
            delete this.map[key];
        } else {
            this.map[key] = value;
        }

    }
};

JaneliaQueryParameters.prototype.updateLink = function(urlToViewId) {
    if (typeof urlToViewId != 'undefined') {
        var search = this.getSearch();
        var href = location.protocol + '//' + location.host + location.pathname + search;
        $('#' + urlToViewId).attr('href', href);
    }
};

JaneliaQueryParameters.prototype.updateParameterAndLink = function (key, value, urlToViewId) {

    this.updateParameter(key, value);
    this.updateLink(urlToViewId);
};

/**
 *
 * @param owner
 * @param project
 * @param stack
 * @constructor
 */
var JaneliaRenderServiceData = function(owner, project, stack) {

    this.owner = owner;
    this.project = project;
    this.stack = stack;

    this.util = new JaneliaScriptUtilities();
    this.baseUrl =  this.util.getServicesBaseUrl();
    this.shortbaseUrl = this.util.getShortRenderHost();
    this.ownerList = [];
    this.stackMetaDataList = [];
    this.projectToStackCountMap = {};
    this.distinctProjects = [];
    this.stackCount = 0;
};

JaneliaRenderServiceData.prototype.getOwnerUrl = function () {
    return this.baseUrl + '/owner/' + this.owner + '/';
};

JaneliaRenderServiceData.prototype.getProjectUrl = function() {
    return this.getOwnerUrl() + 'project/' + this.project + '/';
};

JaneliaRenderServiceData.prototype.loadOwnerList = function (loadCallbacks) {

    var self = this;
    $.ajax({
               url: self.baseUrl + '/owners',
               cache: false,
               success: function(data) {
                   self.setOwnerList(data);
                   loadCallbacks.success();
               },
               error: function(data, text, xhr) {
                   self.util.handleAjaxError(data, text, xhr, loadCallbacks.error);
               }
           });
};

JaneliaRenderServiceData.prototype.setOwnerList = function(data) {

    this.ownerList = data;
    if (this.ownerList.length > 0) {
        if (this.ownerList.indexOf(this.owner) == -1) {
            this.owner = this.ownerList[0];
        }
    }
};

JaneliaRenderServiceData.prototype.loadStackMetaDataList = function (forOwner, loadCallbacks) {

    var self = this;
    $.ajax({
               url: this.baseUrl + '/owner/' + forOwner + '/stacks',
               cache: false,
               success: function(data) {
                   self.owner = forOwner;
                   self.setStackMetaDataList(data);
                   loadCallbacks.success();
               },
               error: function(data, text, xhr) {
                   self.util.handleAjaxError(data, text, xhr, loadCallbacks.error);
               }
           });
};

JaneliaRenderServiceData.prototype.setStackMetaDataList = function(data) {

    this.stackMetaDataList = data;
    this.distinctProjects = [];

    if (this.stackMetaDataList.length > 0) {

        this.projectToStackCountMap = {};

        var project;
        for (var index = 0; index < this.stackMetaDataList.length; index++) {
            project = this.stackMetaDataList[index].stackId.project;
            if (typeof this.projectToStackCountMap[project] == 'undefined') {
                this.projectToStackCountMap[project] = 0;
            }
            this.projectToStackCountMap[project]++;
        }

        this.distinctProjects = Object.keys(this.projectToStackCountMap);

        this.setProject(this.project);
    }
};

JaneliaRenderServiceData.prototype.getStackMetaDataWithStackName = function(stackName, stackMetaDataList) {

    var stackMetaData = undefined;
    if (typeof stackName != 'undefined') {
        for (var index = 0; index < stackMetaDataList.length; index++) {
            if (stackMetaDataList[index].stackId.stack == stackName) {
                stackMetaData = stackMetaDataList[index];
                break;
            }
        }
    }
    return stackMetaData;
};

JaneliaRenderServiceData.prototype.getProjectStackMetaDataList = function() {

    var projectStackMetaDataList = [];
    for (var index = 0; index < this.stackMetaDataList.length; index++) {
        if (this.stackMetaDataList[index].stackId.project == this.project) {
            projectStackMetaDataList.push(this.stackMetaDataList[index]);
        }
    }
    return projectStackMetaDataList;
};

JaneliaRenderServiceData.prototype.getProjectStackList = function() {

    var projectStackList = [];
    var projectStackMetaDataList = this.getProjectStackMetaDataList();
    for (var index = 0; index < projectStackMetaDataList.length; index++) {
        projectStackList.push(projectStackMetaDataList[index].stackId.stack);
    }
    return projectStackList;
};

JaneliaRenderServiceData.prototype.getStackMetaData = function() {
    return this.getStackMetaDataWithStackName(this.stack, this.getProjectStackMetaDataList());
};

JaneliaRenderServiceData.prototype.setProject = function(selectedProject) {

    if (this.distinctProjects.indexOf(selectedProject) == -1) {
        this.project = this.distinctProjects[0];
    } else {
        this.project = selectedProject;
    }

    this.stackCount = this.projectToStackCountMap[this.project];

    this.setStack(this.stack);
};

JaneliaRenderServiceData.prototype.setStack = function(selectedStack) {

    var projectStackMetaDataList = this.getProjectStackMetaDataList();
    var stackMetaData = this.getStackMetaDataWithStackName(selectedStack, projectStackMetaDataList);
    if (typeof stackMetaData == 'undefined') {
        this.stack = projectStackMetaDataList[0].stackId.stack;
    } else {
        this.stack = selectedStack;
    }
};

JaneliaRenderServiceData.prototype.deleteStack = function (stackName, deleteCallbacks) {

    if (confirm("Are you sure you want to delete the " + stackName + " stack?")) {

        var self = this;

        $.ajax({
                   url: self.getProjectUrl() + 'stack/' + stackName,
                   type: 'DELETE',
                   success: function () {
                       deleteCallbacks.success();
                   },
                   error: function (data,
                                    text,
                                    xhr) {
                       self.util.handleAjaxError(data, text, xhr, deleteCallbacks.error);
                   }
               });
    }
};

/**
 *
 * @param messageId
 * @param summaryMessage
 * @constructor
 */
var JaneliaMessageUI = function(messageId, summaryMessage) {
    this.displayError = function(detailedErrorMessage) {
        var messageSelect = $('#' + messageId);
        messageSelect.text(summaryMessage + '  Detailed error: ' + detailedErrorMessage);
        messageSelect.addClass("error");
    }
};

/**
 *
 * @param {JaneliaQueryParameters} queryParameters
 * @param {String} ownerSelectId
 * @param {String} projectSelectId
 * @param {String} stackSelectId
 * @param {String} messageId
 * @param {String} urlToViewId
 * @constructor
 */
var JaneliaRenderServiceDataUI = function(queryParameters, ownerSelectId, projectSelectId, stackSelectId, messageId, urlToViewId) {

    this.util = new JaneliaScriptUtilities();
    this.shortbaseUrl = this.util.getShortRenderHost();
    this.queryParameters = queryParameters;
    this.catmaidHost = queryParameters.map['catmaidHost'];
    this.ndvizHost = queryParameters.map['ndvizHost'];
    this.dynamicRenderHost = queryParameters.map['dynamicRenderHost'];

    this.renderServiceData = new JaneliaRenderServiceData(queryParameters.map[ownerSelectId],
                                                          queryParameters.map[projectSelectId],
                                                          queryParameters.map[stackSelectId]);

    this.projectChangeCallbacks = [];

    var self = this;

    var setStack = function(selectedStack) {
        self.renderServiceData.stack = selectedStack;
        self.queryParameters.updateParameterAndLink(stackSelectId, selectedStack, urlToViewId);
    };

    if (typeof stackSelectId != 'undefined') {
        this.util.addOnChangeCallbackForSelect(stackSelectId, setStack);
    }

    var setProjectAndUpdateStackList = function(selectedProject) {

        self.renderServiceData.setProject(selectedProject);
        self.queryParameters.updateParameterAndLink(projectSelectId, selectedProject, urlToViewId);

        if (typeof stackSelectId != 'undefined') {
            self.util.updateSelectOptions(stackSelectId,
                                          self.renderServiceData.getProjectStackList(),
                                          self.renderServiceData.stack);
        }
        setStack(self.renderServiceData.stack);
        for (var i = 0; i < self.projectChangeCallbacks.length; i++) {
            self.projectChangeCallbacks[i]();
        }
    };

    this.util.addOnChangeCallbackForSelect(projectSelectId, setProjectAndUpdateStackList);

    var stackMetaDataLoadCallbacks = {
        success: function() {
            self.util.updateSelectOptions(projectSelectId,
                                          self.renderServiceData.distinctProjects,
                                          self.renderServiceData.project);
            setProjectAndUpdateStackList(self.renderServiceData.project);
        },
        error: new JaneliaMessageUI(messageId, 'Failed to load render stack metadata.').displayError
    };

    var setOwnerAndUpdateStackMetaData = function(selectedOwner) {
        self.renderServiceData.owner = selectedOwner;
        self.queryParameters.updateParameterAndLink(ownerSelectId, selectedOwner, urlToViewId);
        self.renderServiceData.loadStackMetaDataList(selectedOwner, stackMetaDataLoadCallbacks);
    };

    this.util.addOnChangeCallbackForSelect(ownerSelectId, setOwnerAndUpdateStackMetaData);

    this.ownerLoadCallbacks = {
        success: function() {
            self.util.updateSelectOptions(ownerSelectId,
                                          self.renderServiceData.ownerList,
                                          self.renderServiceData.owner);
            setOwnerAndUpdateStackMetaData(self.renderServiceData.owner);
        },
        error: new JaneliaMessageUI(messageId, 'Failed to load render stack owners.').displayError
    };

};

JaneliaRenderServiceDataUI.prototype.addProjectChangeCallback = function(callback) {
    this.projectChangeCallbacks.push(callback);
};

JaneliaRenderServiceDataUI.prototype.loadData = function() {
    this.renderServiceData.loadOwnerList(this.ownerLoadCallbacks);
};

JaneliaRenderServiceDataUI.prototype.isDynamicRenderHostDefined = function() {
    return typeof this.dynamicRenderHost != 'undefined';
};

JaneliaRenderServiceDataUI.prototype.getDynamicRenderBaseUrl = function() {
    var baseRenderUrl = undefined;
    if (this.isDynamicRenderHostDefined()) {
        baseRenderUrl = 'http://' + this.dynamicRenderHost + '/render-ws/v1';
    }
    return baseRenderUrl;
};

JaneliaRenderServiceDataUI.prototype.isNdvizHostDefined = function() {
    return typeof this.ndvizHost != 'undefined';

};

JaneliaRenderServiceDataUI.prototype.isCatmaidHostDefined = function() {
    return typeof this.catmaidHost != 'undefined';
};

JaneliaRenderServiceDataUI.prototype.buildStackQueryParameters = function(owner, project, stack) {

    var parameters = {};

    var keyValueList = [
        ['renderStackOwner', owner],
        ['renderStackProject', project],
        ['renderStack', stack],
        ['dynamicRenderHost', this.dynamicRenderHost],
        ['catmaidHost', this.catmaidHost],
        ['ndvizHost', this.ndvizHost]
    ];

    var key;
    var value;
    for (var i = 0; i < keyValueList.length; i++) {
        key = keyValueList[i][0];
        value = keyValueList[i][1];
        if (typeof value != 'undefined') {
            parameters[key] = value;
        }
    }

    return $.param(parameters);
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
JaneliaRenderServiceDataUI.prototype.getStackSummaryHtml = function(ownerUrl, stackInfo) {
    var values = [];
    var version = stackInfo.currentVersion;
    if (typeof version === 'undefined') {
        values.push('');
        values.push('');
    } else {
        values.push(this.util.getDefinedValue(version.cycleNumber));
        values.push(this.util.getDefinedValue(version.cycleStepNumber));
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
            values.push(this.util.getDefinedValue(bounds.minZ));
            values.push(this.util.getDefinedValue(bounds.maxZ));

            if (typeof version !== 'undefined') {
                xp = (bounds.minX + ((bounds.maxX - bounds.minX) / 2)) * version.stackResolutionX;
                yp = (bounds.minY + ((bounds.maxY - bounds.minY) / 2)) * version.stackResolutionY;
                zp = bounds.minZ * version.stackResolutionZ;
            }
        }
        values.push(this.util.numberWithCommas(this.util.getDefinedValue(stats.sectionCount)));
        values.push(this.util.numberWithCommas(this.util.getDefinedValue(stats.nonIntegralSectionCount)));
        values.push(this.util.numberWithCommas(this.util.getDefinedValue(stats.tileCount)));
        values.push(this.util.numberWithCommas(this.util.getDefinedValue(stats.transformCount)));
    }

    var stackId = stackInfo.stackId;
    var baseStackUrl = ownerUrl + 'project/' + stackId.project + '/stack/' + stackId.stack;

    //noinspection HtmlUnknownTarget
    var linksHtml = '<a target="_blank" href="' + baseStackUrl + '">Metadata</a> ' +
                    '<a target="_blank" href="' + baseStackUrl + '/zValues">Z Values</a> ' +
                    '<a target="_blank" href="' + baseStackUrl + '/mergeableZValues">mergeable Z Values</a>';

    if (this.isCatmaidHostDefined()) {
        var CATMAIDUrl = 'http://' + this.catmaidHost + '/?tool=navigator&s0=8' +
                         '&pid=' + stackId.project + '&sid0=' + stackId.stack +
                         '&zp=' + zp + '&yp=' + yp  + '&xp=' + xp;
        linksHtml = linksHtml + ' <a target="_blank" href="' + CATMAIDUrl + '">CATMAID-alpha</a>';
    }
    if (this.isNdvizHostDefined()) {
        var NDVIZUrl = 'http://' + this.ndvizHost + '/render/' + this.shortbaseUrl + '/' +
                    stackId.owner + '/' + stackId.project + '/' + stackId.stack + '/';
        linksHtml = linksHtml + ' <a target="_blank" href="' + NDVIZUrl + '">NdViz</a>';
    }

    linksHtml = linksHtml + ' <span id="' + stackId.stack + '__actions"></span>';

    if (stackInfo.state == 'OFFLINE') {
        linksHtml = '';
    }

    var detailsQueryString = '?' + this.buildStackQueryParameters(stackId.owner, stackId.project, stackId.stack);
    //noinspection HtmlUnknownTarget
    var detailsLink = '<a target="_blank" href="stack-details.html' + detailsQueryString + '">' + stackId.stack  +'</a>';

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
