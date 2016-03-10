package org.janelia.test;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.flapdoodle.embed.mongo.MongoImportExecutable;
import de.flapdoodle.embed.mongo.MongoImportStarter;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.IMongoImportConfig;
import de.flapdoodle.embed.mongo.config.MongoImportConfigBuilder;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.IFeatureAwareVersion;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;

/**
 * Manages an embedded mongo database for use in testing.
 * Because it takes a second or two to start up and shutdown, instances should be shared across tests.
 *
 * @author Eric Trautman
 */
public class EmbeddedMongoDb {

    private static final MongodStarter STARTER = MongodStarter.getDefaultInstance();

    private final IFeatureAwareVersion version;
    private final int port;
    private final MongodExecutable mongodExecutable;
    private final MongodProcess mongodProcess;
    private final MongoClient mongoClient;
    private final MongoDatabase db;

    public EmbeddedMongoDb(final String dbName)
            throws IOException {

        this.version = Version.Main.V2_6;
        this.port = 12345;

        // see MongodForTestsFactory for example verbose startup options
        this.mongodExecutable = STARTER.prepare(new MongodConfigBuilder()
                                                        .version(version)
                                                        .net(new Net(port, Network.localhostIsIPv6()))
                                                        .build());
        this.mongodProcess = mongodExecutable.start();

        this.mongoClient = new MongoClient("localhost", port);

        this.db = mongoClient.getDatabase(dbName);
    }

    public MongoClient getMongoClient() {
        return mongoClient;
    }

    public void importCollection(final String collectionName,
                                 final File jsonFile,
                                 final Boolean jsonArray,
                                 final Boolean upsert,
                                 final Boolean drop) throws IOException {

        final IMongoImportConfig mongoImportConfig = new MongoImportConfigBuilder()
                .version(version)
                .net(new Net(port, Network.localhostIsIPv6()))
                .db(db.getName())
                .collection(collectionName)
                .upsert(upsert)
                .dropCollection(drop)
                .jsonArray(jsonArray)
                .importFile(jsonFile.getAbsolutePath())
                .build();

        final MongoImportExecutable mongoImportExecutable =
                MongoImportStarter.getDefaultInstance().prepare(mongoImportConfig);

        mongoImportExecutable.start();
    }

    public void stop() {
        try {
            db.drop();
            mongodProcess.stop();
            mongodExecutable.stop();
        } catch (final Throwable t) {
            LOG.warn("failed to stop embedded mongodb", t);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedMongoDb.class);

}
