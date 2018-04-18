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

        var stackInfoSelect = $('#stackInfo tbody');

        var StackNameFunctions = function(stackName) {

            this.stackName = stackName;
            this.stackActionsId = stackName + "__actions";
            this.stackDeleteId = this.stackActionsId + '__delete';
            this.actionHtml = '<a id="' + this.stackDeleteId + '" href="#" onclick="return false;">Delete Stack</a>';

            var self = this;
            this.deleteStackWithName = function () {

                var localStackName = self.stackName;

                var deleteStackCallbacks = {
                    success: function() {
                        location.reload();
                    },
                    error: new JaneliaMessageUI('message', 'Failed to delete ' + localStackName + '.').displayError
                };

                renderData.deleteStack(localStackName, deleteStackCallbacks);

                var actionsSelector = $('#' + self.stackActionsId);
                actionsSelector.html('<i>delete in progress</i>');
                actionsSelector.css('color', 'red');

                return false;
            };

            this.updateActions = function() {
                $('#' + self.stackActionsId).html(self.actionHtml);
                $('#' + self.stackDeleteId).click(self.deleteStackWithName);
            }
        };

        $.tablesorter.clearTableBody( $("#stackInfo")[0] );

        var projectStackMetaDataList = renderData.getProjectStackMetaDataList();
        var summaryHtml;
        for (var index = 0; index < projectStackMetaDataList.length; index++) {
            summaryHtml = renderDataUi.getStackSummaryHtml(renderData.getOwnerUrl(),
                                                           projectStackMetaDataList[index],
                                                           true);
            stackInfoSelect.append(summaryHtml);
            new StackNameFunctions(projectStackMetaDataList[index].stackId.stack).updateActions();
        }

        $("#stackInfo").trigger("update");

    };

    renderDataUi.addProjectChangeCallback(projectChangeCallback);
    renderDataUi.loadData();
};

