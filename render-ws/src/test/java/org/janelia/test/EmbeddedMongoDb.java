package org.janelia.test;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
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
import de.flapdoodle.embed.process.config.io.ProcessOutput;
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

        final ConnectionString connectionString = new ConnectionString("mongodb://localhost:" + port);
        this.mongoClient = MongoClients.create(connectionString);

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

        // Occasionally during GitHub Action builds, imports will fail with
        //   java.io.IOException: error=26, Text file busy
        //
        // The following GitHub issues mention similar problems:
        //   https://github.com/flapdoodle-oss/de.flapdoodle.embed.mongo/issues/246
        //   https://github.com/flapdoodle-oss/de.flapdoodle.embed.mongo/issues/226
        //
        // but do not provide a useful solution for the import failures here (as far as I can tell).
        // I've added the following retry logic as a workaround.
        final int maxRetries = 3;
        final long retryWaitMilliseconds = 5000;
        for (int i = 0; i < maxRetries; i++) {
            try {
                mongoImportExecutable.start();
                i = maxRetries; // break out of retry loop upon success
            } catch (final IOException e) {
                final int numberOfAttempts = i + 1;
                if (numberOfAttempts < maxRetries) {
                    LOG.warn("importCollection: sleeping {}ms before next retry after catching exception {}",
                             retryWaitMilliseconds, e.getMessage());
                    try {
                        Thread.sleep(retryWaitMilliseconds);
                    } catch (final InterruptedException sleepEx) {
                        LOG.warn("importCollection: ignoring sleep exception and continuing", sleepEx);
                    }
                    LOG.warn("importCollection: retry import of {} after {} prior attempt(s)",
                             jsonFile, numberOfAttempts);
                } else {
                    LOG.warn("importCollection: failed {} times to import {}, giving up and re-raising exception",
                             numberOfAttempts, jsonFile);
                    throw e;
                }
            }
        }
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
