
//noinspection JSUnusedGlobalSymbols
var mongoClientStub = function() {
    function db() {}
    function getCollection() {}
    function getCollectionNames() {}
    function getSiblingDB() {}
    function hasNext() {}
    //noinspection SpellCheckingInspection
    function printjson() {}
};

//noinspection JSUnusedGlobalSymbols
var tileSpecStub = { "mipmapLevels" : { "0" : { "imageUrl" : "a", "maskUrl" : "b" } } };

function getRenderDb() {
    return db.getSiblingDB('render');
}

function getRenderCollection(collectionName) {
    var renderDb = getRenderDb();
    return renderDb.getCollection(collectionName);
}

function getRenderCollectionNames(namePattern) {
    var matchingNames = [];
    var renderDb = getRenderDb();
    var collectionNames = renderDb.getCollectionNames();
    var name;
    for (var i = 0; i < collectionNames.length; i++) {
        name = collectionNames[i];
        if (namePattern.test(collectionNames[i])) {
            matchingNames.push(name);
        }
    }
    return matchingNames;
}

function processAllMatchingCollections(namePattern, processCollectionWithName) {
    var collectionNames = getRenderCollectionNames(namePattern);
    for (var i = 0; i < collectionNames.length; i++) {
        processCollectionWithName(collectionNames[i]);
    }
}

function printLensCorrectionTransforms(collectionName) {
    print("\ncollection: " + collectionName + "\n");
    var collection = getRenderCollection(collectionName);
    var cursor = collection.find({"id":/.*camera.*/},{"id":1,"_id":0}).sort({"id":1});
    while (cursor.hasNext()) {
        printjson(cursor.next());
    }
}

function printDistinctImageAndMaskRoots(collectionName) {
    var distinctImageRoots = {};
    var distinctMasks = {};
    var collection = getRenderCollection(collectionName);
    var cursor = collection.find();
    var count = 0;
    while (cursor.hasNext()) {
        var mipmap0 = cursor.next().mipmapLevels["0"];
        distinctImageRoots[mipmap0.imageUrl.substring(0,41)] = true;
        distinctMasks[mipmap0.maskUrl] = true;
        count++;
        if (count % 1000000 == 0) {
            print("checked " + count + " documents");
        }
    }
    print("\ncollection: " + collectionName + "\n");
    printjson(distinctImageRoots);
    printjson(distinctMasks);
}

// To print lens correction transform ids for all FAFB00 transform collections:
//   mongo ${MONGO_PARMS} render_db_queries.js
//   mongo ${MONGO_PARMS} --eval "var cName='all_transform'" render_db_queries.js
//
// To print lens correction transform ids for one FAFB00 tile collection:
//   mongo ${MONGO_PARMS} --eval "var cName='flyTEM__FAFB00__v12_acquire__transform'" render_db_queries.js
//
// To print distinct image and mask roots for one FAFB00 tile collection:
//   mongo ${MONGO_PARMS} --eval "var cName='flyTEM__FAFB00__v12_acquire__tile'" render_db_queries.js
//
// To print distinct image and mask roots for all FAFB00 tile collections:
//   mongo ${MONGO_PARMS} --eval "var cName='all_tile'" render_db_queries.js

var scriptCollectionName;
//noinspection JSUnresolvedVariable
if (typeof cName === "undefined") {
    scriptCollectionName = "all_transform";
} else {
    //noinspection JSUnresolvedVariable
    scriptCollectionName = cName;
}

if (scriptCollectionName == "all_transform") {

    processAllMatchingCollections(/flyTEM__FAFB00__.*__transform/, printLensCorrectionTransforms);

} else if (scriptCollectionName == "all_tile") {

    processAllMatchingCollections(/flyTEM__FAFB00__.*__tile/, printDistinctImageAndMaskRoots);

} else if (scriptCollectionName.indexOf("__tile") > -1) {

    printDistinctImageAndMaskRoots(scriptCollectionName);

} else {

    printLensCorrectionTransforms(scriptCollectionName);

}
