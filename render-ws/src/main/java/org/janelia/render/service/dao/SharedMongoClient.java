package org.janelia.render.service.dao;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ClusterDescription;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class maintains the single MongoClient instance that is shared for all service requests.
 *
 * From <a href="http://docs.mongodb.org/ecosystem/drivers/java-concurrency/">
 *     http://docs.mongodb.org/ecosystem/drivers/java-concurrency/</a>:
 *
 *     The Java MongoDB driver is thread safe. If you are using in a web serving environment,
 *     for example, you should create a single MongoClient instance, and you can use it in every request.
 *     The MongoClient object maintains an internal pool of connections to the database
 *     (default maximum pool size of 100).
 *     For every request to the DB (find, insert, etc.) the Java thread will obtain a connection
 *     from the pool, execute the operation, and release the connection.
 *
 * @author Eric Trautman
 */
public class SharedMongoClient {

    private static SharedMongoClient sharedMongoClient;

    public static MongoClient getInstance() {
        if (sharedMongoClient == null) {
            setSharedMongoClient();
        }
        return sharedMongoClient.client;
    }

    private final MongoClient client;

    public SharedMongoClient(final DbConfig dbConfig) {

        final MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder();
        if (dbConfig.hasConnectionString()) {

            // use connectionString for everything (see https://docs.mongodb.com/manual/reference/connection-string )
            final ConnectionString connectionString = new ConnectionString(dbConfig.getConnectionString());
            settingsBuilder.applyConnectionString(connectionString);

        } else {

            // use explicitly configured parameters

            settingsBuilder.applyToConnectionPoolSettings(builder -> builder
                            .maxSize(dbConfig.getMaxConnectionsPerHost())
                            .maxConnectionIdleTime(dbConfig.getMaxConnectionIdleTime(), TimeUnit.MILLISECONDS))
                    .readPreference(dbConfig.getReadPreference())
                    .applyToClusterSettings(builder -> builder
                            .hosts(dbConfig.getServerAddressList()));

            if (dbConfig.hasCredentials()) {
                final MongoCredential credential =
                        MongoCredential.createCredential(dbConfig.getUserName(),
                                                         dbConfig.getAuthenticationDatabase(),
                                                         dbConfig.getPassword());
                settingsBuilder.credential(credential);
            }

        }

        final MongoClientSettings settings = settingsBuilder.build();
        client = MongoClients.create(settings);

        final ClusterDescription clusterDescription = client.getClusterDescription();
        LOG.info("created {} client for cluster {} with {}",
                 getMongoClientVersion(),
                 clusterDescription.getShortDescription(),
                 settings.getConnectionPoolSettings());
    }

    private static synchronized void setSharedMongoClient() {
        if (sharedMongoClient == null) {
            final File dbConfigFile = new File("resources/render-db.properties");
            final DbConfig dbConfig = DbConfig.fromFile(dbConfigFile);
            sharedMongoClient = new SharedMongoClient(dbConfig);
        }
    }

    private static String getMongoClientVersion() {
        String versionString = "?";
        final Class<MongoClient> clazz = MongoClient.class;
        final String className = clazz.getSimpleName() + ".class";
        final String classPath = String.valueOf(clazz.getResource(className));
        if (classPath.startsWith("jar")) {
            final String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
            try (final InputStream manifestStream = new URL(manifestPath).openStream()) {
                final Manifest manifest = new Manifest(manifestStream);
                final Attributes attr = manifest.getMainAttributes();
                versionString = attr.getValue("Bundle-Name") + " (" + attr.getValue("Bundle-Version") + ")";
            } catch (final Throwable t) {
                LOG.warn("failed to read java mongodb client version from manifest", t);
            }
        }
        return versionString;
    }

    private static final Logger LOG = LoggerFactory.getLogger(SharedMongoClient.class);
}
