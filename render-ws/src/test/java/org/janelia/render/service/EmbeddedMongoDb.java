package org.janelia.render.service;

import com.mongodb.DB;
import com.mongodb.MongoClient;
import de.flapdoodle.embed.mongo.MongoImportExecutable;
import de.flapdoodle.embed.mongo.MongoImportStarter;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.IMongoImportConfig;
import de.flapdoodle.embed.mongo.config.MongoImportConfigBuilder;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.config.Storage;
import de.flapdoodle.embed.mongo.distribution.IFeatureAwareVersion;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;

import java.io.File;
import java.io.IOException;

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
    private DB db;

    public EmbeddedMongoDb(String dbName)
            throws IOException {

        this.version = Version.Main.V2_6;
        this.port = 12345;

        final Storage replication = new Storage("src/test/resources/mongodb/data", null, 0);

        // see MongodForTestsFactory for example verbose startup options
        this.mongodExecutable = STARTER.prepare(new MongodConfigBuilder()
                                                        .version(version)
                                                        .net(new Net(port, Network.localhostIsIPv6()))
                                                        .replication(replication)
                                                        .build());
        this.mongodProcess = mongodExecutable.start();

        final MongoClient mongoClient = new MongoClient("localhost", port);
        this.db = mongoClient.getDB(dbName);
    }

    public void stop() throws Exception {
        db.dropDatabase();
        mongodProcess.stop();
        mongodExecutable.stop();
    }

    public DB getDb() {
        return db;
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

}
