var locationVars;
var stackMetaData;

function updateStackInfoForProject(ownerUrl, projectName) {

    $('#stackInfo').find("tr:gt(0)").remove();

    for (var index = 0; index < stackMetaData.length; index++) {
        if (stackMetaData[index].stackId.project == projectName) {
            addStackInfo(ownerUrl, stackMetaData[index]);
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

    ownerSelect.change(function() {
        var selectedOwner = getSelectedValue("owner");
        changeOwnerAndProject(selectedOwner, undefined);
    });
}

function updateStackMetaData(data, selectedProject) {

    stackMetaData = data;

    var projectToStackCountMap = {};
    var project;
    for (var index = 0; index < stackMetaData.length; index++) {
        project = stackMetaData[index].stackId.project;
        if (typeof projectToStackCountMap[project] == 'undefined') {
            projectToStackCountMap[project] = 0;
        }
        projectToStackCountMap[project]++;
    }

    var distinctProjects = Object.keys(projectToStackCountMap);

    var selectedProjectIndex = 0;
    for (index = 0; index < distinctProjects.length; index++) {
        if (distinctProjects[index] == selectedProject) {
            selectedProjectIndex = index;
        }
    }

    var projectSelect = $('#project');
    var isSelected;
    for (index = 0; index < distinctProjects.length; index++) {
        isSelected = (index == selectedProjectIndex);
        projectSelect.append($('<option>', {value: distinctProjects[index]}).text(distinctProjects[index]).prop('selected', isSelected));
    }

    locationVars.project = distinctProjects[selectedProjectIndex];

    document.title = locationVars.owner + ' ' + locationVars.project + ' Stacks';
    $('#bodyHeader').text(locationVars.owner + ' ' + locationVars.project);

    var stackCount = projectToStackCountMap[locationVars.project];
    var stackSuffix = ' stacks)';
    if (stackCount == 1) {
        stackSuffix = ' stack)';
    }
    $('#bodyHeaderDetails').text('(' + stackCount + stackSuffix);

    updateStackInfoForProject(locationVars.ownerUrl, locationVars.project);

    projectSelect.change(function () {
        changeOwnerAndProject(locationVars.owner, getSelectedValue("project"));
    });

}

function initPage() {

    locationVars = new LocationVars();

    loadJSON(locationVars.baseUrl + '/v1/owners',
             function(data) { updateOwnerList(data, locationVars.owner); },
             function(xhr) { console.error(xhr); });

    loadJSON(locationVars.ownerUrl + "stacks",
             function(data) { updateStackMetaData(data, locationVars.project); },
             function(xhr) { console.error(xhr); });
}

