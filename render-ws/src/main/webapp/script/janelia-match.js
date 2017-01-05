/**
 * @typedef {Object} CanvasMatches
 * @property {String} pGroupId
 * @property {String} pId
 * @property {String} qGroupId
 * @property {String} qId
 * @property {Array} matches
 */

/**
 * @typedef {Object} CollectionId
 * @property {String} owner
 * @property {String} name
 */

/**
 * @typedef {Object} MatchCollectionMetaData
 * @property {CollectionId} collectionId
 * @property {number} pairCount
 */

var JaneliaMatchServiceData = function(owner, collection) {

    this.util = new JaneliaScriptUtilities();

    this.owner = owner;
    this.collection = collection;

    this.baseUrl =  this.util.getServicesBaseUrl();

    this.ownerList = [];
    this.collectionList = [];
    this.collectionPairCounts = {};
};

JaneliaMatchServiceData.prototype.loadOwnerList = function (loadCallbacks) {

    var self = this;
    $.ajax({
               url: self.baseUrl + '/matchCollectionOwners',
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

JaneliaMatchServiceData.prototype.setOwnerList = function(data) {

    this.ownerList = data;
    if (this.ownerList.length > 0) {
        if (this.ownerList.indexOf(this.owner) == -1) {
            this.owner = this.ownerList[0];
        }
    }
};

JaneliaMatchServiceData.prototype.loadCollectionList = function (loadCallbacks) {

    var self = this;
    $.ajax({
               url: this.baseUrl + '/owner/' + this.owner + '/matchCollections',
               cache: false,
               success: function(data) {
                   self.setCollectionList(data);
                   loadCallbacks.success();
               },
               error: function(data, text, xhr) {
                   self.util.handleAjaxError(data, text, xhr, loadCallbacks.error);
               }
           });
};

JaneliaMatchServiceData.prototype.setCollectionList = function(data) {

    this.collectionList = [];
    this.collectionPairCounts = {};

    if (data.length > 0) {

        var isCollectionValid = false;
        var index;
        for (index = 0; index < data.length; index++) {
            if (data[index].collectionId.name == this.collection) {
                isCollectionValid = true;
            }
            this.collectionList.push(data[index].collectionId.name);
            this.collectionPairCounts[data[index].collectionId.name] = data[index].pairCount;
        }

        this.collectionList.sort();

        if (! isCollectionValid) {
            this.collection = this.collectionList[0];
        }
    }
};

//JaneliaMatchServiceData.prototype.loadMatchesForGroup = function(groupId, loadCallbacks) {
//    var matchesUrl = this.baseUrl + '/owner/' + this.owner + '/matchCollection/' + this.collection +
//                     '/group/' + groupId + '/matches';
//
//    var self = this;
//    $.ajax({
//               url: matchesUrl,
//               cache: false,
//               success: function(data) {
//                   loadCallbacks.success(data);
//               },
//               error: function(data, text, xhr) {
//                   self.util.handleAjaxError(data, text, xhr, loadCallbacks.error);
//               }
//           });
//};

JaneliaMatchServiceData.prototype.loadMatchesForGroup = function(groupId, loadCallbacks, matchLoadMessageSelector) {
    var baseMatchesUrl = this.baseUrl + '/owner/' + this.owner + '/matchCollection/' + this.collection +
                     '/group/' + groupId;

    var self = this;

    var loadMatchesOutsideGroup = function(data) {

        var withinData = data;

        matchLoadMessageSelector.text('loading matches outside group ...');
        $.ajax({
                   url: baseMatchesUrl + '/matchesOutsideGroup',
                   cache: false,
                   success: function(data) {
                       loadCallbacks.success(withinData.concat(data));
                   },
                   error: function(data, text, xhr) {
                       self.util.handleAjaxError(data, text, xhr, loadCallbacks.error);
                   }
               });
    };

    matchLoadMessageSelector.text('loading matches within group ...');
    $.ajax({
               url: baseMatchesUrl + '/matchesWithinGroup',
               cache: false,
               success: function(data) {
                   loadMatchesOutsideGroup(data);
               },
               error: function(data, text, xhr) {
                   self.util.handleAjaxError(data, text, xhr, loadCallbacks.error);
               }
           });
};

/**
 *
 * @param {JaneliaQueryParameters} queryParameters
 * @param {String} ownerSelectId
 * @param {String} collectionSelectId
 * @param {String} collectionPairCountId
 * @param {String} messageId
 * @param {String} urlToViewId
 * @constructor
 */
var JaneliaMatchServiceDataUI = function(queryParameters, ownerSelectId, collectionSelectId, collectionPairCountId, messageId, urlToViewId) {

    this.util = new JaneliaScriptUtilities();

    this.queryParameters = queryParameters;

    this.matchServiceData = new JaneliaMatchServiceData(queryParameters.map[ownerSelectId],
                                                        queryParameters.map[collectionSelectId]);

    var self = this;

    var setCollection = function(selectedCollection) {
        self.matchServiceData.collection = selectedCollection;
        self.queryParameters.updateParameterAndLink(collectionSelectId, selectedCollection, urlToViewId);
        var formattedPairCount =
                self.util.numberWithCommas(self.matchServiceData.collectionPairCounts[selectedCollection]);
        $('#' + collectionPairCountId).text(formattedPairCount + ' pairs');
    };

    this.util.addOnChangeCallbackForSelect(collectionSelectId, setCollection);

    var collectionLoadCallbacks = {
        success: function() {
            self.util.updateSelectOptions(collectionSelectId,
                                          self.matchServiceData.collectionList,
                                          self.matchServiceData.collection);
            setCollection(self.matchServiceData.collection);
        },
        error: new JaneliaMessageUI(messageId, 'Failed to load match collections.').displayError
    };

    var setOwnerAndUpdateCollections = function(selectedOwner) {
        self.matchServiceData.owner = selectedOwner;
        self.queryParameters.updateParameterAndLink(ownerSelectId, selectedOwner, urlToViewId);
        self.matchServiceData.loadCollectionList(collectionLoadCallbacks);
    };

    this.util.addOnChangeCallbackForSelect(ownerSelectId, setOwnerAndUpdateCollections);

    var ownerLoadCallbacks = {
        success: function() {
            self.util.updateSelectOptions(ownerSelectId,
                                          self.matchServiceData.ownerList,
                                          self.matchServiceData.owner);
            setOwnerAndUpdateCollections(self.matchServiceData.owner);
        },
        error: new JaneliaMessageUI(messageId, 'Failed to load match collection owners.').displayError
    };

    this.matchServiceData.loadOwnerList(ownerLoadCallbacks);
};

var JaneliaMatchPairData = function(groupId, tileDate, tileColumn, tileSuffix, otherGroupId, otherTileId, changeCallback) {

    this.setGroupId(groupId);

    this.tileDate = tileDate;
    this.tileColumn = tileColumn;
    this.tileSuffix = tileSuffix;
    this.otherGroupId = otherGroupId;
    this.otherTileId = otherTileId;

    this.changeCallback = changeCallback;
    this.util = new JaneliaScriptUtilities();
};

JaneliaMatchPairData.prototype.setGroupId = function(selectedGroupId) {

    this.groupId = selectedGroupId;
//    this.tileDate = null;
//    this.tileColumn = null;
//    this.tileSuffix = null;
//    this.otherGroupId = null;
//    this.otherTileId = null;

    this.canvasMatches = [];
    this.filteredGroupMatches = [];
    this.groupIdList = [];
    this.dateList = [];
    this.columnList = [];
    this.otherTileIdList = [];
};

JaneliaMatchPairData.prototype.getTileId = function() {
    return this.tileDate + this.tileColumn + this.tileSuffix;
};

JaneliaMatchPairData.prototype.setCanvasMatches = function(data) {

    this.canvasMatches = data;

    var distinctGroupIds = new Set();
    var pair;
    for (var index = 0; index < data.length; index++) {
        pair = data[index];
        distinctGroupIds.add(pair.pGroupId);
        distinctGroupIds.add(pair.qGroupId);
    }

    this.groupIdList = Array.from(distinctGroupIds).sort();

    this.setOtherGroupId(this.util.getValidValue(this.otherGroupId, this.groupIdList, this.groupId));
};

JaneliaMatchPairData.prototype.setOtherGroupId = function(selectedOtherGroupId) {

    this.otherGroupId = selectedOtherGroupId;

    this.pGroupId = this.groupId;
    this.qGroupId = this.otherGroupId;
    this.filteredGroupMatches = [];

    var distinctDates = new Set();

    if (this.otherGroupId == null) {

        this.groupComparisonResult = 1;

    } else {

        this.groupComparisonResult = this.pGroupId.localeCompare(this.qGroupId);

        if (this.groupComparisonResult > 0) {
            this.pGroupId = this.otherGroupId;
            this.qGroupId = this.groupId;
        }

        var pair;
        for (var index = 0; index < this.canvasMatches.length; index++) {
            pair = this.canvasMatches[index];
            if ((pair.pGroupId == this.pGroupId) && (pair.qGroupId == this.qGroupId)) {

                this.filteredGroupMatches.push(pair);

                if (this.groupComparisonResult > 0) {
                    distinctDates.add(pair.qId.substr(0, 12));
                } else if (this.groupComparisonResult < 0) {
                    distinctDates.add(pair.pId.substr(0, 12));
                } else {
                    distinctDates.add(pair.pId.substr(0, 12));
                    distinctDates.add(pair.qId.substr(0, 12));
                }
            }
        }

    }

    this.dateList = Array.from(distinctDates).sort();

    this.setTileDate(this.util.getValidValue(this.tileDate, this.dateList));
};

JaneliaMatchPairData.prototype.setTileDate = function(selectedTileDate) {

    this.tileDate = selectedTileDate;

    var distinctColumns = new Set();

    if (this.tileDate != null) {
        var pair;
        for (var index = 0; index < this.filteredGroupMatches.length; index++) {

            pair = this.filteredGroupMatches[index];

            if (this.groupComparisonResult > 0) {
                if (pair.qId.substr(0, 12) == this.tileDate) {
                    distinctColumns.add(pair.qId.substr(12, 3));
                }
            } else {
                if (pair.pId.substr(0, 12) == this.tileDate) {
                    distinctColumns.add(pair.pId.substr(12, 3));
                }
                if (this.groupComparisonResult == 0) {
                    if (pair.qId.substr(0, 12) == this.tileDate) {
                        distinctColumns.add(pair.qId.substr(12, 3));
                    }
                }
            }
        }
    }

    this.columnList = Array.from(distinctColumns).sort();

    this.setTileColumn(this.util.getValidValue(this.tileColumn, this.columnList));
};

JaneliaMatchPairData.prototype.setTileColumn = function(selectedTileColumn) {

    this.tileColumn = selectedTileColumn;

    var distinctSuffixes = new Set();

    if (this.tileColumn != null) {
        var pair;
        for (var index = 0; index < this.filteredGroupMatches.length; index++) {

            pair = this.filteredGroupMatches[index];

            if (this.groupComparisonResult > 0) {
                if (pair.qId.substr(0, 12) == this.tileDate) {
                    if ((pair.qId.substr(12, 3) == this.tileColumn)) {
                        distinctSuffixes.add(pair.qId.substr(15));
                    }
                }
            } else {
                if (pair.pId.substr(0, 12) == this.tileDate) {
                    if ((pair.pId.substr(12, 3) == this.tileColumn)) {
                        distinctSuffixes.add(pair.pId.substr(15));
                    }
                }
                if (this.groupComparisonResult == 0) {
                    if ((pair.qId.substr(12, 3) == this.tileColumn)) {
                        distinctSuffixes.add(pair.qId.substr(15));
                    }
                }
            }

        }
    }

    this.suffixList = Array.from(distinctSuffixes).sort();

    this.setTileSuffix(this.util.getValidValue(this.tileSuffix, this.suffixList));
};

JaneliaMatchPairData.prototype.setTileSuffix = function(selectedTileSuffix) {

    this.tileSuffix = selectedTileSuffix;

    this.otherTileIdList = [];

    if (this.tileSuffix != null) {
        var tileId = this.getTileId();

        var pair;
        for (var index = 0; index < this.filteredGroupMatches.length; index++) {

            pair = this.filteredGroupMatches[index];

            if (pair.pId == tileId) {
                this.otherTileIdList.push(pair.qId);
            } else if (pair.qId == tileId) {
                this.otherTileIdList.push(pair.pId);
            }
        }
    }

    this.otherTileIdList.sort();

    this.setOtherTileId(this.util.getValidValue(this.otherTileId, this.otherTileIdList));
};

JaneliaMatchPairData.prototype.setOtherTileId = function(selectedOtherTileId) {

    this.otherTileId = selectedOtherTileId;
    this.changeCallback(this);
};
