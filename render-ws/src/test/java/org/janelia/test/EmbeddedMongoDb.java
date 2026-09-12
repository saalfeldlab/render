package org.janelia.test;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.flapdoodle.embed.mongo.commands.MongoImportArguments;
import de.flapdoodle.embed.mongo.commands.MongodArguments;
import de.flapdoodle.embed.mongo.commands.ServerAddress;
import de.flapdoodle.embed.mongo.distribution.IFeatureAwareVersion;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.ExecutedMongoImportProcess;
import de.flapdoodle.embed.mongo.transitions.MongoImport;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.embed.process.io.ProcessOutput;
import de.flapdoodle.reverse.StateID;
import de.flapdoodle.reverse.Transition;
import de.flapdoodle.reverse.TransitionWalker;
import de.flapdoodle.reverse.Transitions;
import de.flapdoodle.reverse.transitions.Start;

/**
 * Manages an embedded mongo database for use in testing.
 * Because it takes a second or two to start up and shutdown, instances should be shared across tests.
 *
 * @author Eric Trautman
 */
public class EmbeddedMongoDb {

    private final IFeatureAwareVersion version;
    private final TransitionWalker.ReachedState<RunningMongodProcess> runningMongod;
    private final ServerAddress serverAddress;
    private final MongoClient mongoClient;
    private final MongoDatabase db;

    public EmbeddedMongoDb(final String dbName) {

        this.version = Version.Main.V4_0;

        // flapdoodle picks a free port for us, so ask the running process where it ended up
        this.runningMongod = SILENT_MONGOD.start(version);

        // mongod is started without --bind_ip, so it only listens on the loopback interface.
        // Reuse only the port here because the host flapdoodle reports comes from
        // InetAddress.getLocalHost(), which can be this machine's external name.
        final int port = runningMongod.current().getServerAddress().getPort();
        this.serverAddress = ServerAddress.of("localhost", port);

        final ConnectionString connectionString = new ConnectionString("mongodb://" + serverAddress);
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

        final MongoImportArguments importArguments = MongoImportArguments.builder()
                .databaseName(db.getName())
                .collectionName(collectionName)
                .importFile(jsonFile.getAbsolutePath())
                .isJsonArray(jsonArray)
                .upsertDocuments(upsert)
                .dropCollection(drop)
                .build();

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
                // to see flapdoodle files on Mac: ls -al /var/folders/*/*/*/*mongo*
                runImport(importArguments);
                i = maxRetries; // break out of retry loop upon success
            } catch (final RuntimeException e) {
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
                    throw new IOException("failed to import " + jsonFile + " into " + collectionName, e);
                }
            }
        }
    }

    /**
     * Runs mongoimport against the running mongod, raising a {@link RuntimeException} if it fails.
     * The import process is started and stopped within this method so that its executable is released
     * before the next retry (see the retry comments in {@link #importCollection}).
     */
    private void runImport(final MongoImportArguments importArguments) {

        final Transitions importTransitions = SILENT_MONGO_IMPORT
                .transitions(version)
                .replace(Start.to(MongoImportArguments.class).initializedWith(importArguments))
                .addAll(Start.to(ServerAddress.class).initializedWith(serverAddress));

        try (final TransitionWalker.ReachedState<ExecutedMongoImportProcess> executed =
                     importTransitions.walker().initState(StateID.of(ExecutedMongoImportProcess.class))) {

            final int returnCode = executed.current().returnCode();
            if (returnCode != 0) {
                throw new IllegalStateException("mongoimport exited with return code " + returnCode);
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
            mongoClient.close();
        } catch (final Throwable t) {
            LOG.warn("failed to close mongo client", t);
        }

        try {
            runningMongod.close();
        } catch (final Throwable t) {
            LOG.warn("failed to stop mongod process", t);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedMongoDb.class);

    /**
     * Mongod configured to keep its process output off the console
     * (the flapdoodle 4.x replacement for the 3.x silent RuntimeConfig).
     */
    private static final Mongod SILENT_MONGOD = new Mongod() {
        @Override
        public Transition<MongodArguments> mongodArguments() {
            // use ephemeralForTest storage engine to fix super slow run times on Mac
            // see https://github.com/flapdoodle-oss/de.flapdoodle.embed.mongo/issues/166
            return Start.to(MongodArguments.class)
                    .initializedWith(MongodArguments.defaults().withStorageEngine("ephemeralForTest"));
        }
        @Override
        public Transition<ProcessOutput> processOutput() {
            return Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent());
        }
    };

    /**
     * MongoImport configured to keep its process output off the console.
     * Import processes are not daemons (flapdoodle's default ProcessConfig sets daemonProcess to false),
     * which avoids the shutdown issues described in
     * <a href="https://github.com/flapdoodle-oss/de.flapdoodle.embed.mongo/issues/191">flapdoodle issue 191</a> .
     */
    private static final MongoImport SILENT_MONGO_IMPORT = new MongoImport() {
        @Override
        public Transition<ProcessOutput> processOutput() {
            return Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent());
        }
    };
}
