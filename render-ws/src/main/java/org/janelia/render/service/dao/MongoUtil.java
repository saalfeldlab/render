package org.janelia.render.service.dao;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.UpdateResult;

import java.util.List;

import org.bson.Document;

/**
 * Mongodb utility methods for all DAOs.
 *
 * @author Eric Trautman
 */
public class MongoUtil {

    public static String action(final UpdateResult result) {
        final String action;
        if (result.getMatchedCount() > 0) {
            action = "update";
        } else {
            action = "insert";
        }
        return action;
    }

    public static boolean exists(final MongoDatabase database,
                                 final String collectionName) {
        for (final String name : database.listCollectionNames()) {
            if (name.equalsIgnoreCase(collectionName)) {
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

        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
        final Document document = new Document();

        if (options.isBackground()) {
            document.append("background", true);
        }
        if (options.isUnique()) {
            document.append("unique", true);
        }

        // add other options if/when they are used

        return document.toJson();
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

}
