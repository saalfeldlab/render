package org.janelia.render.service.dao;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;

import java.util.List;

import org.bson.Document;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mongodb utility methods for all DAOs.
 *
 * @author Eric Trautman
 */
public class MongoUtil {

    public static final UpdateOptions UPSERT_OPTION = new UpdateOptions().upsert(true);
    public static final BulkWriteOptions UNORDERED_OPTION = new BulkWriteOptions().ordered(false);

    public static String action(final UpdateResult result) {
        final String action;
        if (result.getMatchedCount() > 0) {
            action = "update";
        } else {
            action = "insert";
        }
        return action;
    }

    public static void createIndex(final MongoCollection<Document> collection,
                                   final Document keys,
                                   final IndexOptions options) {
        LOG.debug("createIndex: entry, collection={}, keys={}, options={}",
                  MongoUtil.fullName(collection), keys.toJson(), toJson(options));
        collection.createIndex(keys, options);
        LOG.debug("createIndex: exit");
    }

    public static MongoCollection<Document> getExistingCollection(final MongoDatabase database,
                                                                  final String collectionName)
            throws ObjectNotFoundException {
        if (! exists(database, collectionName)) {
            throw new ObjectNotFoundException(
                    database.getName() + " collection '" + collectionName + "' does not exist");
        }
        return database.getCollection(collectionName);
    }

    public static boolean exists(final MongoDatabase database,
                                 final String collectionName) {
        for (final String name : database.listCollectionNames()) {
            if (name.equals(collectionName)) {
                return true;
            }
        }
        return false;
    }

    public static String fullName(final MongoCollection<Document> collection) {
        return collection.getNamespace().getFullName();
    }

    public static Integer toInteger(final Double value) {
        Integer integerValue = null;
        if (value != null) {
            integerValue = value.intValue();
        }
        return integerValue;
    }

    public static String toMessage(final String context,
                                   final BulkWriteResult result,
                                   final int objectCount) {

        final StringBuilder message = new StringBuilder(128);

        message.append("processed ").append(objectCount).append(" ").append(context);

        if (result.wasAcknowledged()) {
            final int updates = result.getMatchedCount();
            final int inserts = objectCount - updates;
            message.append(" with ").append(inserts).append(" inserts and ");
            message.append(updates).append(" updates");
        } else {
            message.append(" (result NOT acknowledged)");
        }

        return message.toString();
    }

    public static String toJson(final IndexOptions options) {

        final StringBuilder sb = new StringBuilder(128);

        sb.append("{ \"name\": \"").append(options.getName()).append("\"");

        if (options.isUnique()) {
            sb.append(", \"unique\": true");
        }

        if (options.isBackground()) {
            sb.append(", \"background\": true");
        }

        // add other options if/when they are used

        sb.append(" }");

        return sb.toString();
    }

    public static String toJson(final List<Document> list) {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(list.get(i).toJson());
        }
        sb.append(']');
        return sb.toString();
    }

    public static void validateRequiredParameter(final String context,
                                                 final Object value)
            throws IllegalArgumentException {

        if (value == null) {
            throw new IllegalArgumentException(context + " value must be specified");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MongoUtil.class);

}
