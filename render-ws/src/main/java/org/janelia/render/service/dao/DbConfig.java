package org.janelia.render.service.dao;

import com.mongodb.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Database connection configuration properties.
 *
 * @author Eric Trautman
 */
public class DbConfig {

    private String host;
    private Integer port;
    private String userName;
    private String userNameSource; // typically the database where userName is defined
    private String password;

    public DbConfig(String host,
                    Integer port,
                    String userName,
                    String userNameSource,
                    String password) {
        this.host = host;
        this.port = port;
        this.userName = userName;
        this.userNameSource = userNameSource;
        this.password = password;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    public String getUserName() {
        return userName;
    }

    public String getUserNameSource() {
        return userNameSource;
    }

    public char[] getPassword() {
        return password.toCharArray();
    }

    public static DbConfig fromFile(File file)
            throws IllegalArgumentException {

        DbConfig dbConfig = null;
        Properties properties = new Properties();

        final String path = file.getAbsolutePath();

        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            properties.load(in);

            final String host = getRequiredProperty("host", properties, path);
            final String userName = getRequiredProperty("userName", properties, path);
            final String userNameSource = getRequiredProperty("userNameSource", properties, path);
            final String password = getRequiredProperty("password", properties, path);

            Integer port;
            final String portStr = properties.getProperty("port");
            if (portStr == null) {
                port = ServerAddress.defaultPort();
            } else {
                try {
                    port = new Integer(portStr);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid port value (" + portStr +
                                                       ") specified in " + path, e);
                }
            }

            dbConfig = new DbConfig(host, port, userName, userNameSource, password);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to load properties from " + path, e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    LOG.warn("failed to close " + path + ", ignoring error");
                }
            }
        }

        return dbConfig;
    }

    private static String getRequiredProperty(String propertyName,
                                              Properties properties,
                                              String path)
            throws IllegalArgumentException {

        final String value = properties.getProperty(propertyName);
        if (value == null) {
            throw new IllegalArgumentException(propertyName + " value is missing from " + path);
        }
        return value;
    }

    private static final Logger LOG = LoggerFactory.getLogger(DbConfig.class);

}
