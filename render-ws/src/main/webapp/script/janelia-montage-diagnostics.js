/**
 * @constructor
 */
var JaneliaDiagnostics = function(diagnosticData, servicesBaseUrl) {

    this.diagnosticData = diagnosticData;
    this.servicesBaseUrl = servicesBaseUrl;

    var self = this;

    this.mapCoordinatesAndOpenWindow = function (fromStackInfo, z, toStackInfo, outlierData, catmaidBaseUrl, resolution) {

        var mapUrl = this.servicesBaseUrl + '/owner/' + fromStackInfo.owner +
                     '/project/' + fromStackInfo.project + '/stack/' + fromStackInfo.stack + '/z/' + z +
                     '/world-coordinates/' + outlierData.x + ',' + outlierData.y +
                     '/mapped-to-stack/' + toStackInfo.stack + '?toProject=' + toStackInfo.project;

        // noinspection JSUnresolvedVariable
        $.ajax({
                   url: mapUrl,
                   cache: false,
                   success: function(data) {
                       // { "tileId": "151210133802007013.481.0", "world": [ 145304.00274169602, 35759.008551272884, 481 ] }
                       var mappedCoordinates = data.world;
                       var href = self.getCatmaidUrl(catmaidBaseUrl, mappedCoordinates, resolution);
                       self.openWindow(href);
                   },
                   error: function(data, text, xhr) {
                       var sep = "                    "; // use spaces instead of newlines so that alert text is selectable
                       var message = "Coordinate map request failed with status code " + data.status +
                                     " and response '" + data.responseText + "'." + sep + "Request URL was " + mapUrl;
                       alert(message);
                       console.log(xhr);
                   }
               });
    };

    this.openWindow = function (href) {
        var win = window.open(href);
        if (win) {
            win.focus();
        } else {
            alert('Please allow popups for this website');
        }
    };

    this.getRenderCatmaidBaseUrl = function (baseUrl, stackInfo) {
        return baseUrl + '?pid=' + stackInfo.owner + '__' + stackInfo.project + '&sid0=' + stackInfo.stack;
    };

    this.getCatmaidUrl = function (baseUrl, worldCoordinates, resolution) {
        var x = parseInt(worldCoordinates[0] * resolution[0]);
        var y = parseInt(worldCoordinates[1] * resolution[1]);
        var z = parseInt(worldCoordinates[2] * resolution[2]);
        return baseUrl + '&zp=' + z + '&yp=' + y + '&xp=' + x + '&tool=navigator&s0=0';
    };

    this.getMatchUrl = function (baseUrl, scale, stackInfo, matchInfo, pId, qId) {
        return baseUrl + '?renderScale=' + scale + '&renderStackOwner=' + stackInfo.owner +
               '&renderStackProject=' + stackInfo.project + '&renderStack=' + stackInfo.stack +
               '&matchOwner=' + matchInfo.owner + '&matchCollection=' + matchInfo.collection +
               '&pId=' + pId + '&qId=' + qId;
    };

    /**
     * @param stackKey
     * @param layerIndex
     * @param outlierIndex
     * @param linkIndex
     */
    this.openOutlierLink = function (stackKey,
                                     layerIndex,
                                     outlierIndex,
                                     linkIndex) {

        var stackDataA = this.diagnosticData.stack_data.a;
        var stackDataB = this.diagnosticData.stack_data.b;
        var layerData = this.diagnosticData.layer_data[layerIndex];
        var resolution = [4, 4, 40];  // TODO: dynamically pull resolution from render stack metadata
        var matchRenderScale = 0.25;

        var outlierData;
        var fromStackInfo;
        var toStackInfo;
        var catmaidBaseUrl;
        var matchInfo;

        if (stackKey === 'a') {
            // stackKey 'a', linkIndex: 0,       1,           2,       3,       4,           5
            //                          v14 TPS, v15 montage, v15 TPS, v15 DMG, v14 matches, v15 matches

            outlierData = layerData.a.outlier_data[outlierIndex];
            fromStackInfo = stackDataA.montage;
            catmaidBaseUrl = stackDataA.tps.catmaid_base_url;

            switch (linkIndex) {
                case 0: toStackInfo = stackDataA.tps;     break;
                case 1: toStackInfo = stackDataB.montage; break;
                case 2: toStackInfo = stackDataB.tps;     break;
                case 3: toStackInfo = stackDataB.dmg;     break;
                case 4: matchInfo = stackDataA.match;     break;
                case 5: matchInfo = stackDataB.match;     break;
                default:                                  break;
            }

            if ((linkIndex > 0) && (linkIndex < 4)) {
                catmaidBaseUrl = this.getRenderCatmaidBaseUrl(this.diagnosticData.stack_data.catmaid_base_url,
                                                              toStackInfo);
             }

            if (linkIndex < 3) {

                this.mapCoordinatesAndOpenWindow(fromStackInfo, layerData.z, toStackInfo, outlierData,
                                                 catmaidBaseUrl, resolution);

            } else if (linkIndex === 3) {

                this.mapCoordinatesAndOpenWindow(fromStackInfo, layerData.z, stackDataA.tps, outlierData,
                                                 catmaidBaseUrl, resolution);

            }  else {

                this.openWindow(
                        this.getMatchUrl(this.diagnosticData.stack_data.match_base_url,
                                         matchRenderScale,
                                         stackDataB.montage,
                                         matchInfo,
                                         outlierData.p_id,
                                         outlierData.q_id));
            }

        } else {
            // stackKey 'b', linkIndex: 0,           1,       2,           3
            //                          v15 montage, v14 TPS, v14 matches, v15 matches

            outlierData = layerData.b.outlier_data[outlierIndex];

            if (linkIndex === 0) {

                catmaidBaseUrl = this.getRenderCatmaidBaseUrl(this.diagnosticData.stack_data.catmaid_base_url,
                                                              stackDataB.montage);
                this.openWindow(
                        this.getCatmaidUrl(catmaidBaseUrl,
                                           [ outlierData.x, outlierData.y, layerData.z ],
                                           resolution));

            } else if (linkIndex === 1) {

                this.mapCoordinatesAndOpenWindow(stackDataB.montage, layerData.z, stackDataA.tps, outlierData,
                                                 stackDataA.tps.catmaid_base_url, resolution);

            } else {

                matchInfo = (linkIndex > 2 ) ? stackDataB.match : stackDataA.match;
                this.openWindow(
                        this.getMatchUrl(this.diagnosticData.stack_data.match_base_url,
                                         matchRenderScale,
                                         stackDataB.montage,
                                         matchInfo,
                                         outlierData.p_id,
                                         outlierData.q_id));
            }

        }

        return false;
    };

    /**
     * @param stackData
     * @param stackData.a.alias
     * @param stackData.b.alias
     * @param layerIndex
     * @param outlierDetails
     * @param outlierDetails.outlier_data
     * @param outlierDetails.outlier_data.residual
     */
    this.getOutlierDetailsHtmlForA = function (stackData,
                                               layerIndex,
                                               outlierDetails) {

        var aliasA = stackData.a.alias;
        var aliasB = stackData.b.alias;

        var html = ' <td><div class="outlier_details"><ol>\n';

        if ('outlier_data' in outlierDetails) {

            for (var i = 0; i < outlierDetails.outlier_data.length; i++) {

                var htmlFragment = 'href="#" onclick="return diagnostics.openOutlierLink(\'a\', ' +
                                   layerIndex + ', ' + i;

                // noinspection HtmlUnknownAttribute
                html += '<li><a class="first" ' + htmlFragment + ', 0)">' + aliasA + ' TPS (' +
                        outlierDetails.outlier_data[i].residual + 'px)</a>' +
                        '<a ' + htmlFragment + ', 1)">' + aliasB + ' montage</a>' +
                        '<a ' + htmlFragment + ', 2)">' + aliasB + ' TPS</a>' +
                        '<a ' + htmlFragment + ', 3)">' + aliasB + ' DMG</a>' +
                        '<a ' + htmlFragment + ', 4)">' + aliasA + ' matches</a>' +
                        '<a ' + htmlFragment + ', 5)">' + aliasB + ' matches</a>' +
                        '</li>\n';
            }

        }

        return html + '\n</ol></div></td>'
    };

    this.getOutlierDetailsHtmlForB = function (stackData,
                                               layerIndex,
                                               outlierDetails) {

        var aliasA = stackData.a.alias;
        var aliasB = stackData.b.alias;

        var html = ' <td><div class="outlier_details"><ol>\n';

        if ('outlier_data' in outlierDetails) {

            for (var i = 0; i < outlierDetails.outlier_data.length; i++) {

                var htmlFragment = 'href="#" onclick="return diagnostics.openOutlierLink(\'b\', ' +
                                   layerIndex + ', ' + i;

                // noinspection HtmlUnknownAttribute
                html += '<li><a class="first" ' + htmlFragment + ', 0)">' + aliasB + ' montage (' +
                        outlierDetails.outlier_data[i].residual + 'px)</a>' +
                        '<a ' + htmlFragment + ', 1)">' + aliasA + ' TPS</a>' +
                        '<a ' + htmlFragment + ', 2)">' + aliasA + ' matches</a>' +
                        '<a ' + htmlFragment + ', 3)">' + aliasB + ' matches</a>' +
                        '</li>\n';
            }

        }

        return html + '\n</ol></div></td>'
    };

    this.getDiagnosticRowsHtml = function () {

        var stackData = this.diagnosticData.stack_data;
        var aliasA = stackData.a.alias;
        var aliasB = stackData.b.alias;

        var html = '<tr>\n' +
                   '  <th class="right">z</th>\n' +
                   '  <th class="narrow right">' + aliasA + ' outlier edge count</th>\n' +
                   '  <th class="narrow right">' + aliasB + ' outlier edge count</th>\n' +
                   '  <th class="center">residual summary</th>\n' +
                   '  <th class="center">' + aliasA + ' outliers</th>\n' +
                   '  <th class="center">' + aliasB + ' outliers</th>\n' +
                   '</tr>\n';

        for (var layerIndex = 0; layerIndex < this.diagnosticData.layer_data.length; layerIndex++) {

            var layerData = this.diagnosticData.layer_data[layerIndex];
            var outlierEdgeCountA = ('outlier_data' in layerData.a) ? layerData.a.outlier_data.length : 0;
            var outlierEdgeCountB = ('outlier_data' in layerData.b) ? layerData.b.outlier_data.length : 0;

            html += '<tr>\n' +
                    ' <td class="number">' + layerData.z + '</td>\n' +
                    ' <td class="number">' + outlierEdgeCountA + '</td>\n' +
                    ' <td class="number">' + outlierEdgeCountB + '</td>\n' +
                    ' <td><a target="_blank" href="' + layerData.summary_image + '"><img class="thumb" src="' +
                    layerData.summary_image + '" alt="' + layerData.z + ' summary"></a></td>\n' +
                    this.getOutlierDetailsHtmlForA(stackData, layerIndex, layerData.a) +
                    this.getOutlierDetailsHtmlForB(stackData, layerIndex, layerData.b) +
                    '</tr>\n';
        }

        return html;
    };

};
