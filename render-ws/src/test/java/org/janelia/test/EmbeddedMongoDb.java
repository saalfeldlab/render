package org.janelia.test;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.flapdoodle.embed.mongo.Command;
import de.flapdoodle.embed.mongo.MongoImportExecutable;
import de.flapdoodle.embed.mongo.MongoImportStarter;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.Defaults;
import de.flapdoodle.embed.mongo.config.ImmutableMongoCmdOptions;
import de.flapdoodle.embed.mongo.config.MongoCmdOptions;
import de.flapdoodle.embed.mongo.config.MongoImportConfig;
import de.flapdoodle.embed.mongo.config.MongodConfig;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.IFeatureAwareVersion;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.config.ImmutableRuntimeConfig;
import de.flapdoodle.embed.process.config.RuntimeConfig;
import de.flapdoodle.embed.process.config.process.ProcessOutput;
import de.flapdoodle.embed.process.runtime.Network;

/**
 * Manages an embedded mongo database for use in testing.
 * Because it takes a second or two to start up and shutdown, instances should be shared across tests.
 *
 * @author Eric Trautman
 */
public class EmbeddedMongoDb {

    private final IFeatureAwareVersion version;
    private final int port;
    private final MongodExecutable mongodExecutable;
    private final MongodProcess mongodProcess;
    private final MongoClient mongoClient;
    private final MongoDatabase db;

    public EmbeddedMongoDb(final String dbName)
            throws IOException {

        this.version = Version.Main.V4_0;
        this.port = Network.freeServerPort(Network.getLocalHost());

        // use ephemeralForTest storage engine to fix super slow run times on Mac
        // see https://github.com/flapdoodle-oss/de.flapdoodle.embed.mongo/issues/166
        final ImmutableMongoCmdOptions mongoCmdOptions =
                MongoCmdOptions.builder().storageEngine("ephemeralForTest").build();

        final MongodConfig mongodConfig = MongodConfig.builder()
                .version(version)
                .net(new Net(port, Network.localhostIsIPv6()))
                .cmdOptions(mongoCmdOptions)
                .build();
        
        this.mongodExecutable = STARTER.prepare(mongodConfig);

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

        final MongoImportConfig mongoImportConfig = MongoImportConfig.builder()
                .version(version)
                .net(new Net(port, Network.localhostIsIPv6()))
                .databaseName(db.getName())
                .collectionName(collectionName)
                .isUpsertDocuments(upsert)
                .isDropCollection(drop)
                .isJsonArray(jsonArray)
                .importFile(jsonFile.getAbsolutePath())
                .build();

        final MongoImportExecutable mongoImportExecutable =
                MongoImportStarter.getInstance(MONGO_IMPORT_RUNTIME_CONFIG).prepare(mongoImportConfig);

        mongoImportExecutable.start();
    }

    public void stop() {

        try {
            db.drop();
        } catch (final Throwable t) {
            LOG.warn("failed to drop test database", t);
        }

        try {
            mongodProcess.stop();
        } catch (final Throwable t) {
            LOG.warn("failed to stop mongod process", t);
        }

        try {
            mongodExecutable.stop();
        } catch (final Throwable t) {
            LOG.warn("failed to stop mongod executable", t);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedMongoDb.class);

    private static final RuntimeConfig MONGO_IMPORT_RUNTIME_CONFIG = ImmutableRuntimeConfig.builder()
            .processOutput(ProcessOutput.silent())
            .artifactStore(Defaults.extractedArtifactStoreFor(Command.MongoImport))
            .isDaemonProcess(false) // make sure import processes are not daemons to avoid shutdown issues (see https://github.com/flapdoodle-oss/de.flapdoodle.embed.mongo/issues/191 )
            .build();

    private static final RuntimeConfig MONGOD_RUNTIME_CONFIG = ImmutableRuntimeConfig.builder()
            .processOutput(ProcessOutput.silent())
            .artifactStore(Defaults.extractedArtifactStoreFor(Command.MongoD))
            .build();

    private static final MongodStarter STARTER = MongodStarter.getInstance(MONGOD_RUNTIME_CONFIG);
}
