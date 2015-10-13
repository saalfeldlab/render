var locationVars;

function initPage() {

    locationVars = new LocationVars();

    loadJSON(locationVars.ownerUrl + "project/" + locationVars.project + "/stack/" + locationVars.stack,
             function(data) {
                 addStackInfo(locationVars.ownerUrl, data);
                 document.title = locationVars.stack;
                 $('#owner').text(locationVars.owner + ' > ');

                 var projectHref = 'stacks.html?owner=' + locationVars.owner + '&project=' + locationVars.project;
                 $('#project').attr("href", projectHref).text(locationVars.project);

                 $('#bodyHeader').text(locationVars.stack);
             },
             function(xhr) { console.error(xhr); });
}
