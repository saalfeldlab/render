package org.janelia.render.service.dao;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
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
 *     For every request to the DB (find, insert, etc) the Java thread will obtain a connection
 *     from the pool, execute the operation, and release the connection.
 *
 * @author Eric Trautman
 */
public class SharedMongoClient {

    private static SharedMongoClient sharedMongoClient;

    public static MongoClient getInstance()
            throws UnknownHostException {
        if (sharedMongoClient == null) {
            setSharedMongoClient();
        }
        return sharedMongoClient.client;
    }

    private final MongoClient client;

    public SharedMongoClient(final DbConfig dbConfig)
            throws UnknownHostException {

        final List<MongoCredential> credentialsList;
        if (dbConfig.hasCredentials()) {
            final MongoCredential credential =
                    MongoCredential.createMongoCRCredential(dbConfig.getUserName(),
                                                            dbConfig.getAuthenticationDatabase(),
                                                            dbConfig.getPassword());
            credentialsList = Collections.singletonList(credential);
        } else {
            credentialsList = Collections.emptyList();
        }

        final MongoClientOptions options = new MongoClientOptions.Builder()
                .connectionsPerHost(dbConfig.getMaxConnectionsPerHost())
                .maxConnectionIdleTime(dbConfig.getMaxConnectionIdleTime())
                .readPreference(dbConfig.getReadPreference())
                .build();

        LOG.info("creating {} client for server(s) {} with {}",
                 getMongoClientVersion(), dbConfig.getServerAddressList(), options);


        client = new MongoClient(dbConfig.getServerAddressList(), credentialsList, options);
    }

    private static synchronized void setSharedMongoClient()
            throws UnknownHostException {
        if (sharedMongoClient == null) {
            final File dbConfigFile = new File("logs/render-db.properties");
            final DbConfig dbConfig = DbConfig.fromFile(dbConfigFile);
            sharedMongoClient = new SharedMongoClient(dbConfig);
        }
    }

    private static String getMongoClientVersion() {
        String versionString = "?";
        final Class clazz = MongoClient.class;
        final String className = clazz.getSimpleName() + ".class";
        final String classPath = clazz.getResource(className).toString();
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

    private static final Logger LOG = LoggerFactory.getLogger(DbConfig.class);
}
