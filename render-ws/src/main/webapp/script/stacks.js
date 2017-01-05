var RenderWebServiceProjectStacks = function(ownerSelectId, projectSelectId, messageId, urlToViewId) {

    var queryParameters = new JaneliaQueryParameters();

    var renderDataUi = new JaneliaRenderServiceDataUI(queryParameters, ownerSelectId, projectSelectId, undefined, messageId, urlToViewId);

    var projectChangeCallback = function () {

        var renderData = renderDataUi.renderServiceData;
        var ownerAndProject = renderData.owner + ' ' + renderData.project;

        document.title = ownerAndProject + ' Stacks';
        $('#bodyHeader').text(ownerAndProject);

        var stackSuffix = ' stacks)';
        if (renderData.stackCount == 1) {
            stackSuffix = ' stack)';
        }
        $('#bodyHeaderDetails').text('(' + renderData.stackCount + stackSuffix);

        var stackInfoSelect = $('#stackInfo');
        stackInfoSelect.find("tr:gt(0)").remove();

        var projectStackMetaDataList = renderData.getProjectStackMetaDataList();
        var summaryHtml;
        for (var index = 0; index < projectStackMetaDataList.length; index++) {
            summaryHtml = renderDataUi.getStackSummaryHtml(renderData.getOwnerUrl(),
                                                           projectStackMetaDataList[index]);
            stackInfoSelect.find('tr:last').after(summaryHtml);
        }

    };

    renderDataUi.addProjectChangeCallback(projectChangeCallback);
    renderDataUi.loadData();
};

