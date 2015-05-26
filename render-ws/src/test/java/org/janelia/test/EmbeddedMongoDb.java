package org.janelia.test;

import com.mongodb.DB;
import com.mongodb.MongoClient;

import java.io.File;
import java.io.IOException;

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

    private IFeatureAwareVersion version;
    private int port;
    private MongodExecutable mongodExecutable;
    private MongodProcess mongodProcess;
    private MongoClient mongoClient;
    private DB db;

    public EmbeddedMongoDb(String dbName)
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

        this.db = mongoClient.getDB(dbName);
    }

    public MongoClient getMongoClient() {
        return mongoClient;
    }

    public void importCollection(String collectionName,
                                 File jsonFile,
                                 Boolean jsonArray,
                                 Boolean upsert,
                                 Boolean drop) throws IOException {

        IMongoImportConfig mongoImportConfig = new MongoImportConfigBuilder()
                .version(version)
                .net(new Net(port, Network.localhostIsIPv6()))
                .db(db.getName())
                .collection(collectionName)
                .upsert(upsert)
                .dropCollection(drop)
                .jsonArray(jsonArray)
                .importFile(jsonFile.getAbsolutePath())
                .build();

        MongoImportExecutable mongoImportExecutable =
                MongoImportStarter.getDefaultInstance().prepare(mongoImportConfig);
        mongoImportExecutable.start();
    }

    public void stop() throws Exception {
        db.dropDatabase();
        mongodProcess.stop();
        mongodExecutable.stop();
    }
}
