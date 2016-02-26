var RenderWebServiceProjectStacks = function() {

    var self = this;

    var updateStackInfoForProject = function() {

        var renderData = self.renderData;
        var stackInfoSelect = $('#stackInfo');
        stackInfoSelect.find("tr:gt(0)").remove();

        var projectStackMetaDataList = renderData.getProjectStackMetaDataList();
        var summaryHtml;
        for (var index = 0; index < projectStackMetaDataList.length; index++) {
            summaryHtml = renderData.getStackSummaryHtml(renderData.getOwnerUrl(),
                                                         projectStackMetaDataList[index]);
            stackInfoSelect.find('tr:last').after(summaryHtml);
        }
    };

    var successfulLoadCallback = function () {

        var renderData = self.renderData;
        var ownerAndProject = renderData.owner + ' ' + renderData.project;

        document.title = ownerAndProject + ' Stacks';
        $('#bodyHeader').text(ownerAndProject);

        var stackSuffix = ' stacks)';
        if (renderData.stackCount == 1) {
            stackSuffix = ' stack)';
        }
        $('#bodyHeaderDetails').text('(' + renderData.stackCount + stackSuffix);

        var ownerSelect = $('#owner');
        var index;
        var owner;
        var isSelected;
        for (index = 0; index < renderData.ownerList.length; index++) {
            owner = renderData.ownerList[index];
            isSelected = (owner == renderData.owner);
            ownerSelect.append($('<option/>').val(owner).text(owner).prop('selected', isSelected));
        }

        ownerSelect.change(function () {
            var selectedOwner = renderData.getSelectedValue("owner");
            renderData.changeOwnerAndProject(selectedOwner, undefined);
        });

        var projectSelect = $('#project');
        var project;
        for (index = 0; index < renderData.distinctProjects.length; index++) {
            project = renderData.distinctProjects[index];
            isSelected = (project == renderData.project);
            projectSelect.append($('<option/>').val(project).text(project).prop('selected', isSelected));
        }

        projectSelect.change(function () {
            renderData.changeOwnerAndProject(renderData.owner,
                                             renderData.getSelectedValue("project"));
        });

        updateStackInfoForProject();
    };

    var failedLoadCallback = function (message) {
        var messageSelect = $('#message');
        messageSelect.text(message);
        messageSelect.addClass("error");
    };

    // load data and update page
    self.renderData = new RenderWebServiceData(successfulLoadCallback, failedLoadCallback);

};

