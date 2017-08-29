var DmgImageSizesView = function(baseDmgUrl) {

    this.baseDmgUrl = baseDmgUrl; // '/dmg/FAFB00/v14_align_tps_20170818/8192x8192/0/'

    var self = this;

    $('#layerNumber').change(function() {
        self.showLayer();
    });
    $('#showLayerButton').click(function() {
        self.showLayer();
    });
    $('#prevLayerButton').click(function() {
        self.setLayerNumber(-1);
        self.showLayer();
    });
    $('#nextLayerButton').click(function() {
        self.setLayerNumber(1);
        self.showLayer();
    });

};

DmgImageSizesView.prototype.drawGrid = function(body) {
    var links = {};

    var maxRow = -1;
    var maxCol = -1;

    // builds grid of images sizes by parsing Jetty directory listing like this:
    //
    // <tr>
    //   <td><a href="/dmg/FAFB00/v14_align_tps_20170818/8192x8192/bad_20170829_141832/1097/1097.0.1.24.png">1097.0.1.24.png&nbsp;</a></td>
    //   <td align="right">84305 bytes&nbsp;</td>
    //   <td>Aug 20, 2017 1:10:56 AM</td>
    // </tr>

    $(body).find('tr').each(
            function() {
                var href = $(this).find('a').attr('href');
                if (href.endsWith('.png')) {
                    var nameArr = href.split('.');
                    var row = nameArr[2];
                    var col = nameArr[3];
                    maxRow = Math.max(maxRow, row);
                    maxCol = Math.max(maxCol, col);
                    var key = row + ',' + col;
                    var megabytes = 0;
                    $(this).find('td').each(function() {
                        var i = $(this).html().indexOf(' bytes');
                        if (i > -1) {
                            megabytes = Math.round( parseInt($(this).html().substring(0, i)) / 1000000 );
                        }
                    });
                    links[key] = {'href':href, 'size': megabytes};
                }
            }
    );

    var tableHtml = '<tr><th></th>';
    for (var col = 0; col <= maxCol; col++) {
        tableHtml += '<th></th>'
    }
    tableHtml += '</tr>';

    for (var row = 0; row <= maxRow; row++) {
        tableHtml += '<tr><th></th>';
        for (col = 0; col <= maxCol; col++) {
            tableHtml += '<td>';
            var key = row + ',' + col;
            if (key in links) {
                tableHtml += '<a target="_blank" href="' + links[key].href + '">' + links[key].size + '</a>';
            }
            tableHtml += '</td>'
        }
        tableHtml += '</tr>';
    }

    $('#image-grid').find('tbody').html(tableHtml);
};

DmgImageSizesView.prototype.badLayer = function(layerText) {
    $('#image-grid').find('tbody').html('<tr><td class="error">Failed to load layer ' + layerText + '</td></tr>');
};

DmgImageSizesView.prototype.showLayer = function() {
    var self = this;
    var layerText = $('#layerNumber').val();
    var layer = parseInt(layerText);
    if (layer > 0) {
        var dirUrl = this.baseDmgUrl + layer + '/';
        $.get(dirUrl, function (data) {
            self.drawGrid($(data));
        }).error(function() {
            self.badLayer(layerText);
        });
    } else {
        self.badLayer(layerText);
    }
};

DmgImageSizesView.prototype.setLayerNumber = function(delta) {
    var layerNumberSelector = $('#layerNumber');
    var layer = parseInt(layerNumberSelector.val());
    layerNumberSelector.val(layer + delta);
};